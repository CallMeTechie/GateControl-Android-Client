package com.gatecontrol.android.vault

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.engines.XSalsa20Engine
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.math.ec.rfc7748.X25519
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64

/**
 * Vault's end-to-end encryption — bit for bit what client-core's `vault-crypto` does (the plugin README
 * "Kryptoformat" is the contract; `vault-vectors.json` in the unit tests pins it). Pure JVM on Bouncy Castle:
 * no native code, the same code runs in the unit tests.
 *
 *   master key    Argon2id(master password NFC, kdf.salt; kdf.m KiB, kdf.t passes, 1 lane) → 32 B, memory only
 *   user key      32 B random, stored wrapped by the master key (userKeyPw) and the recovery key (userKeyRk)
 *   key pair      X25519 (libsodium crypto_box); the secret key wrapped by the user key (privateKeyEnc)
 *   vault key     32 B random per vault and key generation, sealed (crypto_box_seal) to each member's public key
 *   wrapped       0x01 ‖ nonce (24 B) ‖ XChaCha20-Poly1305-IETF(plaintext, AD) — the AD binds it to its place
 *   encoding      base64url without padding
 *
 * Argon2id takes a second or more at 64 MiB: never on the main thread.
 */
object VaultCrypto {
    const val KEY_BYTES = 32
    private const val NONCE_BYTES = 24
    private const val SALT_BYTES = 16
    private const val TAG_BYTES = 16
    private const val VERSION: Byte = 1
    private const val PAD_BLOCK = 256
    /** The server's limit for one entry (decoded bytes). */
    private const val MAX_BLOB = 32768
    /** The server's bounds (account.js KDF); libsodium has exactly one lane. */
    val KDF_M = 19456..1048576
    val KDF_T = 2..10
    const val DEFAULT_M = 65536
    const val DEFAULT_T = 3
    val ITEM_TYPES = setOf("login", "totp", "note", "card", "wifi", "identity")

    object AD {
        const val USER_KEY_PW = "gcv1:user-key:pw"
        const val USER_KEY_RK = "gcv1:user-key:rk"
        const val PRIVATE_KEY = "gcv1:private-key"
        fun vaultName(vaultId: String) = "gcv1:vault-name:$vaultId"
        fun item(vaultId: String, itemId: String, rev: Int) = "gcv1:item:$vaultId:$itemId:$rev"
    }

    /** code: `decrypt` (wrong key, tampered, not where it belongs) · `invalid` · `recovery_key` · `too_large`. */
    class VaultCryptoException(val code: String, message: String? = null) : Exception(message ?: code)

    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    // ── encoding ────────────────────────────────────────────

    fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun unb64(text: String): ByteArray {
        if (!Regex("^[A-Za-z0-9_-]*$").matches(text)) throw VaultCryptoException("invalid", "not base64url")
        return try {
            Base64.getUrlDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            throw VaultCryptoException("invalid", "not base64url")
        }
    }

    /** A new id for a vault or an entry: 16 random bytes, base64url (22 characters). */
    fun newId(): String = b64(randomBytes(16))

    private fun key(k: ByteArray, what: String = "key"): ByteArray {
        if (k.size != KEY_BYTES) throw VaultCryptoException("invalid", "$what: 32 bytes expected")
        return k
    }

    // ── XChaCha20-Poly1305 (IETF) ───────────────────────────

    private fun rotl(v: Int, c: Int) = (v shl c) or (v ushr (32 - c))
    private fun le32(b: ByteArray, off: Int) = ByteBuffer.wrap(b, off, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
    private fun put32(out: ByteArray, off: Int, v: Int) {
        ByteBuffer.wrap(out, off, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(v)
    }

    /** HChaCha20 (draft-irtf-cfrg-xchacha §2.2): the subkey of XChaCha20. */
    internal fun hchacha20(key: ByteArray, nonce16: ByteArray): ByteArray {
        val x = IntArray(16)
        x[0] = 0x61707865; x[1] = 0x3320646e; x[2] = 0x79622d32; x[3] = 0x6b206574
        for (i in 0 until 8) x[4 + i] = le32(key, i * 4)
        for (i in 0 until 4) x[12 + i] = le32(nonce16, i * 4)
        fun qr(a: Int, b: Int, c: Int, d: Int) {
            x[a] += x[b]; x[d] = rotl(x[d] xor x[a], 16)
            x[c] += x[d]; x[b] = rotl(x[b] xor x[c], 12)
            x[a] += x[b]; x[d] = rotl(x[d] xor x[a], 8)
            x[c] += x[d]; x[b] = rotl(x[b] xor x[c], 7)
        }
        repeat(10) {
            qr(0, 4, 8, 12); qr(1, 5, 9, 13); qr(2, 6, 10, 14); qr(3, 7, 11, 15)
            qr(0, 5, 10, 15); qr(1, 6, 11, 12); qr(2, 7, 8, 13); qr(3, 4, 9, 14)
        }
        val out = ByteArray(32)
        intArrayOf(0, 1, 2, 3, 12, 13, 14, 15).forEachIndexed { i, w -> put32(out, i * 4, x[w]) }
        return out
    }

    private fun aead(encrypt: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray, ad: ByteArray): ByteArray {
        val sub = hchacha20(key, nonce.copyOfRange(0, 16))
        val n12 = ByteArray(12).also { nonce.copyInto(it, 4, 16, 24) }
        val c = ChaCha20Poly1305()
        c.init(encrypt, AEADParameters(KeyParameter(sub), TAG_BYTES * 8, n12, ad))
        val out = ByteArray(c.getOutputSize(input.size))
        val len = c.processBytes(input, 0, input.size, out, 0)
        try {
            c.doFinal(out, len)
        } catch (e: org.bouncycastle.crypto.InvalidCipherTextException) {
            throw VaultCryptoException("decrypt")
        } finally {
            sub.fill(0)
        }
        return out
    }

    /** 0x01 ‖ nonce ‖ ciphertext+tag. [nonce] only for test vectors. */
    fun wrap(key: ByteArray, plaintext: ByteArray, ad: String, nonce: ByteArray? = null): ByteArray {
        val n = nonce ?: randomBytes(NONCE_BYTES)
        if (n.size != NONCE_BYTES) throw VaultCryptoException("invalid", "nonce")
        val ct = aead(true, key(key), n, plaintext, ad.toByteArray(Charsets.UTF_8))
        return byteArrayOf(VERSION) + n + ct
    }

    fun unwrap(key: ByteArray, data: ByteArray, ad: String): ByteArray {
        if (data.size < 1 + NONCE_BYTES + TAG_BYTES || data[0] != VERSION) throw VaultCryptoException("invalid", "unknown format")
        return aead(false, key(key), data.copyOfRange(1, 1 + NONCE_BYTES), data.copyOfRange(1 + NONCE_BYTES, data.size), ad.toByteArray(Charsets.UTF_8))
    }

    fun unwrap(key: ByteArray, data: String, ad: String): ByteArray = unwrap(key, unb64(data), ad)

    // ── X25519 and crypto_box_seal ──────────────────────────

    class KeyPair(val publicKey: ByteArray, val secretKey: ByteArray)

    fun publicKeyOf(secretKey: ByteArray): ByteArray = ByteArray(32).also { X25519.scalarMultBase(key(secretKey, "secretKey"), 0, it, 0) }

    /** crypto_box_keypair. */
    fun newKeyPair(): KeyPair = randomBytes(32).let { KeyPair(publicKeyOf(it), it) }

    /** crypto_box_seed_keypair: secret key = SHA-512(seed)[0..32]. */
    fun seedKeyPair(seed: ByteArray): KeyPair {
        val sk = MessageDigest.getInstance("SHA-512").digest(key(seed, "seed")).copyOf(32)
        return KeyPair(publicKeyOf(sk), sk)
    }

    /** HSalsa20 core: the 32-byte key derivation of XSalsa20 / crypto_box_beforenm. */
    private fun hsalsa20(k: ByteArray, input16: ByteArray): ByteArray {
        val x = IntArray(16)
        x[0] = 0x61707865; x[5] = 0x3320646e; x[10] = 0x79622d32; x[15] = 0x6b206574
        for (i in 0 until 4) x[1 + i] = le32(k, i * 4)
        for (i in 0 until 4) x[11 + i] = le32(k, 16 + i * 4)
        for (i in 0 until 4) x[6 + i] = le32(input16, i * 4)
        fun s(t: Int, a: Int, b: Int, r: Int) { x[t] = x[t] xor rotl(x[a] + x[b], r) }
        repeat(10) {
            s(4, 0, 12, 7); s(8, 4, 0, 9); s(12, 8, 4, 13); s(0, 12, 8, 18)
            s(9, 5, 1, 7); s(13, 9, 5, 9); s(1, 13, 9, 13); s(5, 1, 13, 18)
            s(14, 10, 6, 7); s(2, 14, 10, 9); s(6, 2, 14, 13); s(10, 6, 2, 18)
            s(3, 15, 11, 7); s(7, 3, 15, 9); s(11, 7, 3, 13); s(15, 11, 7, 18)
            s(1, 0, 3, 7); s(2, 1, 0, 9); s(3, 2, 1, 13); s(0, 3, 2, 18)
            s(6, 5, 4, 7); s(7, 6, 5, 9); s(4, 7, 6, 13); s(5, 4, 7, 18)
            s(11, 10, 9, 7); s(8, 11, 10, 9); s(9, 8, 11, 13); s(10, 9, 8, 18)
            s(12, 15, 14, 7); s(13, 12, 15, 9); s(14, 13, 12, 13); s(15, 14, 13, 18)
        }
        val out = ByteArray(32)
        intArrayOf(0, 5, 10, 15, 6, 7, 8, 9).forEachIndexed { i, w -> put32(out, i * 4, x[w]) }
        return out
    }

    /** crypto_box_beforenm: HSalsa20(X25519(sk, pk), 0). */
    private fun beforenm(secretKey: ByteArray, publicKey: ByteArray): ByteArray {
        val shared = ByteArray(32)
        if (!X25519.calculateAgreement(secretKey, 0, publicKey, 0, shared, 0)) throw VaultCryptoException("decrypt")
        return hsalsa20(shared, ByteArray(16)).also { shared.fill(0) }
    }

    private fun sealNonce(epk: ByteArray, pk: ByteArray): ByteArray {
        val d = Blake2bDigest(NONCE_BYTES * 8)
        d.update(epk, 0, 32)
        d.update(pk, 0, 32)
        return ByteArray(NONCE_BYTES).also { d.doFinal(it, 0) }
    }

    /** crypto_secretbox (XSalsa20-Poly1305): mac(16) ‖ ciphertext; null when the mac does not fit. */
    private fun secretbox(encrypt: Boolean, k: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray? {
        val engine = XSalsa20Engine()
        engine.init(true, ParametersWithIV(KeyParameter(k), nonce))
        val polyKey = ByteArray(32)
        engine.processBytes(ByteArray(32), 0, 32, polyKey, 0)
        val mac = Poly1305()
        mac.init(KeyParameter(polyKey))
        val tag = ByteArray(16)
        return try {
            if (encrypt) {
                val ct = ByteArray(input.size)
                engine.processBytes(input, 0, input.size, ct, 0)
                mac.update(ct, 0, ct.size)
                mac.doFinal(tag, 0)
                tag + ct
            } else {
                if (input.size < 16) return null
                val ct = input.copyOfRange(16, input.size)
                mac.update(ct, 0, ct.size)
                mac.doFinal(tag, 0)
                if (!MessageDigest.isEqual(tag, input.copyOfRange(0, 16))) return null
                ByteArray(ct.size).also { engine.processBytes(ct, 0, ct.size, it, 0) }
            }
        } finally {
            polyKey.fill(0)
        }
    }

    /** crypto_box_seal: epk ‖ secretbox; only the holder of the recipient's secret key opens it. */
    fun seal(message: ByteArray, publicKey: ByteArray): ByteArray {
        val e = newKeyPair()
        val k = beforenm(e.secretKey, key(publicKey, "publicKey"))
        try {
            return e.publicKey + secretbox(true, k, sealNonce(e.publicKey, publicKey), message)!!
        } finally {
            k.fill(0)
            e.secretKey.fill(0)
        }
    }

    /** Opens a sealed 32-byte key. */
    fun openSealed(sealed: ByteArray, publicKey: ByteArray, secretKey: ByteArray): ByteArray {
        if (sealed.size < 32 + 16) throw VaultCryptoException("decrypt")
        val epk = sealed.copyOfRange(0, 32)
        val k = beforenm(key(secretKey, "secretKey"), epk)
        try {
            val out = secretbox(false, k, sealNonce(epk, key(publicKey, "publicKey")), sealed.copyOfRange(32, sealed.size))
            if (out == null || out.size != KEY_BYTES) throw VaultCryptoException("decrypt")
            return out
        } finally {
            k.fill(0)
        }
    }

    fun openSealed(sealed: String, publicKey: ByteArray, secretKey: ByteArray) = openSealed(unb64(sealed), publicKey, secretKey)

    // ── master key ──────────────────────────────────────────

    data class Kdf(val alg: String = "argon2id", val m: Int, val t: Int, val p: Int = 1, val salt: String)

    /** A new kdf for the account: Argon2id, 64 MiB, 3 passes, a fresh salt. */
    fun newKdf(m: Int = DEFAULT_M, t: Int = DEFAULT_T) = Kdf(m = m, t = t, salt = b64(randomBytes(SALT_BYTES)))

    private fun checkKdf(kdf: Kdf): ByteArray {
        if (kdf.alg != "argon2id" || kdf.m !in KDF_M || kdf.t !in KDF_T || kdf.p != 1) throw VaultCryptoException("invalid", "kdf")
        return unb64(kdf.salt).also { if (it.size != SALT_BYTES) throw VaultCryptoException("invalid", "kdf.salt") }
    }

    /** Argon2id(password) → the 32-byte master key. The password counts as UTF-8 after NFC normalization. */
    fun masterKey(password: String, kdf: Kdf): ByteArray {
        if (password.isEmpty()) throw VaultCryptoException("invalid", "password")
        val salt = checkKdf(kdf)
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(kdf.t)
            .withMemoryAsKB(kdf.m)
            .withParallelism(1)
            .withSalt(salt)
            .build()
        val gen = Argon2BytesGenerator()
        gen.init(params)
        val pw = Normalizer.normalize(password, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        val out = ByteArray(KEY_BYTES)
        gen.generateBytes(pw, out)
        pw.fill(0)
        return out
    }

    // ── recovery key ────────────────────────────────────────
    // 32 random bytes + 3 check bytes (BLAKE2b-256 of the key) = 35 bytes = 56 Crockford base32 characters in 14
    // groups of 4. Reading accepts lower case, spaces, missing dashes and O/I/L for 0/1/1.

    private const val B32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    private fun checkBytes(k: ByteArray): ByteArray {
        val d = Blake2bDigest(256)
        d.update(k, 0, k.size)
        return ByteArray(32).also { d.doFinal(it, 0) }.copyOf(3)
    }

    fun formatRecoveryKey(k: ByteArray): String {
        val all = key(k, "recovery key") + checkBytes(k)
        val sb = StringBuilder()
        var acc = 0
        var bits = 0
        for (b in all) {
            acc = (acc shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(B32[(acc shr bits) and 31])
            }
            acc = acc and ((1 shl bits) - 1)
        }
        return sb.chunked(4).joinToString("-")
    }

    fun parseRecoveryKey(text: String): ByteArray {
        val s = text.uppercase().replace(Regex("[\\s-]"), "").replace('O', '0').replace('I', '1').replace('L', '1')
        if (s.length != 56) throw VaultCryptoException("recovery_key")
        val all = ByteArray(35)
        var acc = 0
        var bits = 0
        var i = 0
        for (ch in s) {
            val v = B32.indexOf(ch)
            if (v < 0) throw VaultCryptoException("recovery_key")
            acc = (acc shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                all[i++] = ((acc shr bits) and 0xff).toByte()
            }
            acc = acc and ((1 shl bits) - 1)
        }
        val k = all.copyOf(32)
        if (!MessageDigest.isEqual(checkBytes(k), all.copyOfRange(32, 35))) throw VaultCryptoException("recovery_key")
        return k
    }

    // ── account ─────────────────────────────────────────────

    /** The account as the server keeps it (GET account). */
    data class Account(
        val kdf: Kdf,
        val userKeyPw: String,
        val userKeyRk: String,
        val publicKey: String,
        val privateKeyEnc: String,
        val rev: Int,
    )

    /** The keys held in memory while the vault is unlocked. */
    class Keys(val userKey: ByteArray, val publicKey: ByteArray, val secretKey: ByteArray) {
        fun wipe() {
            userKey.fill(0)
            secretKey.fill(0)
        }
    }

    data class Personal(val id: String, val nameEnc: String, val sealedKey: String)

    /** POST account's body. */
    data class AccountBody(
        val kdf: Kdf,
        val userKeyPw: String,
        val userKeyRk: String,
        val publicKey: String,
        val privateKeyEnc: String,
        val personal: Personal,
    )

    class Setup(val body: AccountBody, val keys: Keys, val personalKey: ByteArray, val recoveryKey: String)

    /** Fixed randomness for the test vectors only. */
    class Rnd(
        val userKey: ByteArray, val recoveryKey: ByteArray, val keySeed: ByteArray, val personalId: String,
        val personalKey: ByteArray, val nonces: List<ByteArray>,
    )

    /**
     * Everything for POST account: [Setup.body] goes to the server; [Setup.keys] stay in memory; the recovery key
     * (text) is shown to the person once — setup ends only after they confirmed keeping it.
     */
    fun createAccount(password: String, personalName: String, kdf: Kdf = newKdf(), rnd: Rnd? = null): Setup {
        val mk = masterKey(password, kdf)
        val userKey = rnd?.userKey?.copyOf() ?: randomBytes(KEY_BYTES)
        val recovery = rnd?.recoveryKey?.copyOf() ?: randomBytes(KEY_BYTES)
        val pair = rnd?.let { seedKeyPair(it.keySeed) } ?: newKeyPair()
        val personalId = rnd?.personalId ?: newId()
        val personalKey = rnd?.personalKey?.copyOf() ?: randomBytes(KEY_BYTES)
        val n = rnd?.nonces.orEmpty()
        try {
            val body = AccountBody(
                kdf = kdf,
                userKeyPw = b64(wrap(mk, userKey, AD.USER_KEY_PW, n.getOrNull(0))),
                userKeyRk = b64(wrap(recovery, userKey, AD.USER_KEY_RK, n.getOrNull(1))),
                publicKey = b64(pair.publicKey),
                privateKeyEnc = b64(wrap(userKey, pair.secretKey, AD.PRIVATE_KEY, n.getOrNull(2))),
                personal = Personal(
                    id = personalId,
                    nameEnc = encryptName(personalKey, personalId, personalName, n.getOrNull(3)),
                    sealedKey = b64(seal(personalKey, pair.publicKey)),
                ),
            )
            return Setup(body, Keys(userKey, pair.publicKey, pair.secretKey), personalKey, formatRecoveryKey(recovery))
        } finally {
            mk.fill(0)
            recovery.fill(0)
        }
    }

    /** The account's keys from the user key (after either unlock); the key pair must match the public key. */
    fun keysFrom(publicKeyB64: String, privateKeyEnc: String, userKey: ByteArray): Keys {
        val publicKey = unb64(publicKeyB64)
        val secretKey = unwrap(userKey, privateKeyEnc, AD.PRIVATE_KEY)
        if (secretKey.size != 32 || !MessageDigest.isEqual(publicKeyOf(secretKey), publicKey)) throw VaultCryptoException("decrypt")
        return Keys(userKey, publicKey, secretKey)
    }

    /** GET account + the master password → keys, or `decrypt` (wrong password). */
    fun unlock(account: Account, password: String): Keys {
        val mk = masterKey(password, account.kdf)
        try {
            return keysFrom(account.publicKey, account.privateKeyEnc, unwrap(mk, account.userKeyPw, AD.USER_KEY_PW))
        } finally {
            mk.fill(0)
        }
    }

    /** The recovery key (text) → keys, or `recovery_key` / `decrypt`. */
    fun unlockWithRecovery(account: Account, recoveryText: String): Keys {
        val rk = parseRecoveryKey(recoveryText)
        try {
            return keysFrom(account.publicKey, account.privateKeyEnc, unwrap(rk, account.userKeyRk, AD.USER_KEY_RK))
        } finally {
            rk.fill(0)
        }
    }

    /** PUT account: a new master password (the user key stays), or with [userKeyRk] also a new recovery key. */
    data class AccountUpdate(val rev: Int, val kdf: Kdf, val userKeyPw: String, val userKeyRk: String? = null)

    fun changePassword(account: Account, keys: Keys, newPassword: String, kdf: Kdf = newKdf()): AccountUpdate {
        val mk = masterKey(newPassword, kdf)
        try {
            return AccountUpdate(account.rev, kdf, b64(wrap(mk, keys.userKey, AD.USER_KEY_PW)))
        } finally {
            mk.fill(0)
        }
    }

    /** After a recovery: a new password and a new recovery key (shown once). */
    fun recover(account: Account, keys: Keys, newPassword: String, kdf: Kdf = newKdf()): Pair<AccountUpdate, String> {
        val update = changePassword(account, keys, newPassword, kdf)
        val rk = randomBytes(KEY_BYTES)
        try {
            return update.copy(userKeyRk = b64(wrap(rk, keys.userKey, AD.USER_KEY_RK))) to formatRecoveryKey(rk)
        } finally {
            rk.fill(0)
        }
    }

    // ── vaults and entries ──────────────────────────────────

    fun newVaultKey(): ByteArray = randomBytes(KEY_BYTES)

    /** A vault key sealed to a public key — POST vaults, members, rekey/start. */
    fun sealKey(vaultKey: ByteArray, publicKey: ByteArray): String = b64(seal(key(vaultKey), publicKey))

    fun encryptName(vaultKey: ByteArray, vaultId: String, name: String, nonce: ByteArray? = null): String {
        if (name.length > 200) throw VaultCryptoException("invalid", "name")
        return b64(wrap(vaultKey, name.toByteArray(Charsets.UTF_8), AD.vaultName(vaultId), nonce))
    }

    fun decryptName(vaultKey: ByteArray, vaultId: String, nameEnc: String): String =
        String(unwrap(vaultKey, nameEnc, AD.vaultName(vaultId)), Charsets.UTF_8)

    /** ISO/IEC 7816-4 padding as sodium_pad: 0x80, then zeros up to the block. */
    internal fun pad(b: ByteArray): ByteArray {
        val len = (b.size / PAD_BLOCK + 1) * PAD_BLOCK
        return b.copyOf(len).also { it[b.size] = 0x80.toByte() }
    }

    internal fun unpad(b: ByteArray): ByteArray {
        if (b.isEmpty() || b.size % PAD_BLOCK != 0) throw VaultCryptoException("decrypt")
        var i = b.size - 1
        while (i >= 0 && b[i] == 0.toByte()) i--
        if (i < 0 || b[i] != 0x80.toByte() || b.size - i > PAD_BLOCK) throw VaultCryptoException("decrypt")
        return b.copyOf(i)
    }

    /**
     * An entry's JSON (UTF-8) → its blob. [rev] is the revision this save creates (baseRev + 1; 1 for a new entry).
     * Padded to 256-byte blocks so the size gives little away; `too_large` over the server's 32 KiB.
     */
    fun encryptItemJson(vaultKey: ByteArray, vaultId: String, itemId: String, rev: Int, json: String, nonce: ByteArray? = null): String {
        if (rev < 1) throw VaultCryptoException("invalid", "rev")
        val padded = pad(json.toByteArray(Charsets.UTF_8))
        if (1 + NONCE_BYTES + padded.size + TAG_BYTES > MAX_BLOB) throw VaultCryptoException("too_large")
        return b64(wrap(vaultKey, padded, AD.item(vaultId, itemId, rev), nonce))
    }

    /** A blob → the entry's JSON; vault, id and revision come from the server's item, so a moved or replayed blob fails. */
    fun decryptItemJson(vaultKey: ByteArray, vaultId: String, itemId: String, rev: Int, blob: String): String =
        String(unpad(unwrap(vaultKey, blob, AD.item(vaultId, itemId, rev))), Charsets.UTF_8)

    // ── safety number, emergency access ─────────────────────

    /**
     * A person's safety number: 40 digits in 8 groups of 5 from SHA-256("gcv1:fingerprint:" ‖ public key), each a
     * 4-byte big-endian word mod 100000. Equal on both devices = the server handed out the right key.
     */
    fun safetyNumber(publicKey: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("gcv1:fingerprint:".toByteArray(Charsets.UTF_8))
        md.update(key(publicKey, "publicKey"))
        val h = ByteBuffer.wrap(md.digest())
        return (0 until 8).joinToString(" ") { "%05d".format((h.getInt(it * 4).toLong() and 0xffffffffL) % 100000) }
    }

    /** POST emergency's sealedKey: the grantor's user key sealed to the trusted person's public key. */
    fun sealForEmergency(keys: Keys, granteePublicKey: ByteArray): String = b64(seal(keys.userKey, granteePublicKey))

    /** GET emergency/<id>/access + the trusted person's keys → the grantor's keys. */
    fun openEmergency(sealedKey: String, grantorPublicKey: String, grantorPrivateKeyEnc: String, mine: Keys): Keys =
        keysFrom(grantorPublicKey, grantorPrivateKeyEnc, openSealed(sealedKey, mine.publicKey, mine.secretKey))
}
