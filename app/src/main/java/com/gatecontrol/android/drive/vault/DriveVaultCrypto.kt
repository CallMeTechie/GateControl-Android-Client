package com.gatecontrol.android.drive.vault

import com.gatecontrol.android.vault.VaultCrypto
import org.bouncycastle.math.ec.rfc7748.X25519
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Drive's vault folders (Drive 1.9, feature `vault`) — end to end encrypted, bit for bit what the plugin's
 * `ui/vaultcrypto.js` does (README "Tresor-Ordner – Kryptografie"; `drive-vault-vectors.json` in the unit tests pins it).
 * Pure JVM (X25519 of Bouncy Castle, AES-GCM and HMAC of the JDK): the same code runs in the unit tests.
 *
 *   device key     X25519; its private key never leaves the phone unencrypted ([DriveVaultDeviceKey])
 *   device id      b64u(SHA-256("gcdrive-v1:device:" ‖ pub)[0..16])
 *   safety number  SHA-256("gcdrive-v1:fingerprint:" ‖ pub) → 4 uint32 BE, each mod 100000, 5 digits
 *   TK             32 random bytes per vault and key version — only in memory
 *   wrap           0x01 ‖ HPKE base mode (DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-256-GCM) of TK:
 *                  enc (32) ‖ ct (48); info "gcdrive-v1:tk:<vaultId>:<keyVersion>", aad empty
 *   nameCipher     0x01 ‖ kv (u32) ‖ nonce (12) ‖ GCM(nameKey, aad = the first 5 bytes, pad(NFC UTF-8))
 *   content        "GCDV" ‖ 0x01 ‖ kv (u32) ‖ chunkSize (u32) ‖ salt (16), then the chunks: GCM(fileKey,
 *                  0^8 ‖ u32(i), aad = header ‖ u32(i) ‖ last, piece); an empty file is one empty last chunk
 *   recovery key   32 bytes, Crockford base32 with 3 BLAKE2b check bytes (the format of the Vault plugin)
 *
 * Errors: [DriveVaultException] with `code` `decrypt` (wrong key, tampered, another vault), `invalid`, `recovery_key`.
 */
object DriveVaultCrypto {
    const val CHUNK = 1_048_576
    const val HEADER = 29
    const val TAG = 16
    const val KEY_BYTES = 32
    private const val WRAP_BYTES = 81
    private val MAGIC = byteArrayOf(0x47, 0x43, 0x44, 0x56) // "GCDV"
    private const val VERSION: Byte = 1

    class DriveVaultException(val code: String, message: String? = null) : Exception(message ?: code)

    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    // ── encoding ────────────────────────────────────────────

    fun b64u(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun unb64u(text: String): ByteArray {
        if (!Regex("^[A-Za-z0-9_-]*$").matches(text) || text.length % 4 == 1) throw DriveVaultException("invalid", "not base64url")
        return try {
            Base64.getUrlDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            throw DriveVaultException("invalid", "not base64url")
        }
    }

    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun u32(n: Long): ByteArray = ByteBuffer.allocate(4).putInt((n and 0xffffffffL).toInt()).array()

    private fun readU32(b: ByteArray, off: Int): Long = ByteBuffer.wrap(b, off, 4).int.toLong() and 0xffffffffL

    fun sha256(vararg parts: ByteArray): ByteArray {
        val d = MessageDigest.getInstance("SHA-256")
        parts.forEach { d.update(it) }
        return d.digest()
    }

    // ── HKDF-SHA256 (RFC 5869) ──────────────────────────────

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        // An empty salt is a salt of 32 zero bytes (HMAC pads the key); the JDK takes no empty key.
        val k = if (key.isEmpty()) ByteArray(32) else key
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(k, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun extract(salt: ByteArray, ikm: ByteArray): ByteArray = hmac(salt, ikm)

    private fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        var t = ByteArray(0)
        var done = 0
        var i = 1
        while (done < length) {
            t = hmac(prk, t + info + byteArrayOf(i.toByte()))
            val n = minOf(t.size, length - done)
            t.copyInto(out, done, 0, n)
            done += n
            i++
        }
        return out
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int = 32): ByteArray = expand(extract(salt, ikm), utf8(info), length)

    // ── AES-256-GCM ─────────────────────────────────────────

    private fun gcm(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray, off: Int = 0, len: Int = input.size): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, nonce))
        if (aad.isNotEmpty()) c.updateAAD(aad)
        return try {
            c.doFinal(input, off, len)
        } catch (e: AEADBadTagException) {
            throw DriveVaultException("decrypt")
        } catch (e: javax.crypto.BadPaddingException) {
            throw DriveVaultException("decrypt")
        }
    }

    // ── X25519 ──────────────────────────────────────────────

    /** An X25519 key pair; [privateKey] is the raw scalar (clamped when used). */
    class KeyPair(val publicKey: ByteArray, val privateKey: ByteArray) {
        fun wipe() = privateKey.fill(0)
    }

    fun publicKeyOf(privateKey: ByteArray): ByteArray {
        if (privateKey.size != 32) throw DriveVaultException("invalid", "private key")
        return ByteArray(32).also { X25519.scalarMultBase(privateKey, 0, it, 0) }
    }

    fun keyFromSeed(seed: ByteArray): KeyPair = KeyPair(publicKeyOf(seed), seed.copyOf())

    fun newKeyPair(): KeyPair = keyFromSeed(randomBytes(32))

    private fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        if (publicKey.size != 32) throw DriveVaultException("invalid", "public key")
        val out = ByteArray(32)
        if (!X25519.calculateAgreement(privateKey, 0, publicKey, 0, out, 0)) throw DriveVaultException("decrypt")
        return out
    }

    // ── device id, safety number ────────────────────────────

    fun deviceId(publicKey: ByteArray): String = b64u(sha256(utf8("gcdrive-v1:device:"), publicKey).copyOf(16))

    /** "44626 75008 87340 28578" — compared on both devices before a device gets the vault's keys. */
    fun safetyNumber(publicKey: ByteArray): String {
        val h = sha256(utf8("gcdrive-v1:fingerprint:"), publicKey)
        return (0 until 4).joinToString(" ") { i -> (readU32(h, i * 4) % 100_000).toString().padStart(5, '0') }
    }

    // ── HPKE (RFC 9180, mode base, X25519 / HKDF-SHA256 / AES-256-GCM) ──

    private val HPKE_V1 = utf8("HPKE-v1")
    private val SUITE_KEM = utf8("KEM") + byteArrayOf(0, 0x20)
    private val SUITE_HPKE = utf8("HPKE") + byteArrayOf(0, 0x20, 0, 1, 0, 2)

    private fun labeledExtract(suite: ByteArray, salt: ByteArray, label: String, ikm: ByteArray) =
        extract(salt, HPKE_V1 + suite + utf8(label) + ikm)

    private fun labeledExpand(suite: ByteArray, prk: ByteArray, label: String, info: ByteArray, length: Int) =
        expand(prk, byteArrayOf((length shr 8).toByte(), length.toByte()) + HPKE_V1 + suite + utf8(label) + info, length)

    private fun sharedSecret(dh: ByteArray, kemContext: ByteArray): ByteArray =
        labeledExpand(SUITE_KEM, labeledExtract(SUITE_KEM, ByteArray(0), "eae_prk", dh), "shared_secret", kemContext, 32)

    /** → key (32), base nonce (12). */
    private fun keySchedule(shared: ByteArray, info: ByteArray): Pair<ByteArray, ByteArray> {
        val empty = ByteArray(0)
        val ctx = byteArrayOf(0) + labeledExtract(SUITE_HPKE, empty, "psk_id_hash", empty) + labeledExtract(SUITE_HPKE, empty, "info_hash", info)
        val secret = labeledExtract(SUITE_HPKE, shared, "secret", empty)
        return labeledExpand(SUITE_HPKE, secret, "key", ctx, 32) to labeledExpand(SUITE_HPKE, secret, "base_nonce", ctx, 12)
    }

    /** Single-shot seal → enc ‖ ct. [ephemeral] only for test vectors (else a fresh key). */
    fun hpkeSeal(recipient: ByteArray, info: ByteArray, aad: ByteArray, plaintext: ByteArray, ephemeral: KeyPair? = null): ByteArray {
        val e = ephemeral ?: newKeyPair()
        try {
            val d = dh(e.privateKey, recipient)
            val shared = sharedSecret(d, e.publicKey + recipient)
            val (key, nonce) = keySchedule(shared, info)
            return e.publicKey + gcm(true, key, nonce, aad, plaintext).also { key.fill(0); d.fill(0); shared.fill(0) }
        } finally {
            if (ephemeral == null) e.wipe()
        }
    }

    /** Single-shot open of enc ‖ ct with the recipient's key pair. */
    fun hpkeOpen(recipient: KeyPair, info: ByteArray, aad: ByteArray, data: ByteArray): ByteArray {
        if (data.size < 32 + TAG) throw DriveVaultException("invalid", "sealed")
        val enc = data.copyOfRange(0, 32)
        val d = dh(recipient.privateKey, enc)
        val shared = sharedSecret(d, enc + recipient.publicKey)
        val (key, nonce) = keySchedule(shared, info)
        try {
            return gcm(false, key, nonce, aad, data, 32, data.size - 32)
        } finally {
            key.fill(0)
            d.fill(0)
            shared.fill(0)
        }
    }

    // ── the vault key ───────────────────────────────────────

    fun newVaultKey(): ByteArray = randomBytes(KEY_BYTES)

    /** A new vault id: 16 random bytes, base64url (22 characters). */
    fun newVaultId(): String = b64u(randomBytes(16))

    private fun wrapInfo(vaultId: String, keyVersion: Int) = utf8("gcdrive-v1:tk:$vaultId:$keyVersion")

    /** TK → its wrap for [publicKey] (base64url, 81 bytes). */
    fun wrapKey(tk: ByteArray, publicKey: ByteArray, vaultId: String, keyVersion: Int, ephemeral: KeyPair? = null): String {
        if (tk.size != KEY_BYTES) throw DriveVaultException("invalid", "key")
        return b64u(byteArrayOf(VERSION) + hpkeSeal(publicKey, wrapInfo(vaultId, keyVersion), ByteArray(0), tk, ephemeral))
    }

    /** A wrap → TK, with the device's (or the recovery key's) key pair. */
    fun unwrapKey(wrap: String, recipient: KeyPair, vaultId: String, keyVersion: Int): ByteArray {
        val b = unb64u(wrap)
        if (b.size != WRAP_BYTES || b[0] != VERSION) throw DriveVaultException("invalid", "wrap")
        val tk = hpkeOpen(recipient, wrapInfo(vaultId, keyVersion), ByteArray(0), b.copyOfRange(1, b.size))
        if (tk.size != KEY_BYTES) throw DriveVaultException("decrypt")
        return tk
    }

    // ── names ───────────────────────────────────────────────

    private fun pad(bytes: ByteArray): ByteArray {
        val n = ((bytes.size + 1 + 31) / 32) * 32
        return ByteArray(n).also { bytes.copyInto(it); it[bytes.size] = 0x80.toByte() }
    }

    private fun unpad(b: ByteArray): ByteArray {
        var i = b.size - 1
        while (i >= 0 && b[i] == 0.toByte()) i--
        if (i < 0 || b[i] != 0x80.toByte()) throw DriveVaultException("decrypt")
        return b.copyOf(i)
    }

    private fun nameKey(tk: ByteArray, vaultId: String) = hkdf(tk, ByteArray(0), "gcdrive-v1:name:$vaultId")

    /** A name → nameCipher (base64url). [nonce] only for test vectors. */
    fun encryptName(tk: ByteArray, vaultId: String, keyVersion: Int, name: String, nonce: ByteArray? = null): String {
        val head = byteArrayOf(VERSION) + u32(keyVersion.toLong())
        val n = nonce ?: randomBytes(12)
        val k = nameKey(tk, vaultId)
        try {
            val plain = pad(utf8(Normalizer.normalize(name, Normalizer.Form.NFC)))
            return b64u(head + n + gcm(true, k, n, head, plain))
        } finally {
            k.fill(0)
        }
    }

    /** The key version of a nameCipher; null when it is not one. */
    fun nameVersion(cipher: String): Int? = runCatching {
        val b = unb64u(cipher)
        if (b.size < 65 || b[0] != VERSION) null else readU32(b, 1).toInt()
    }.getOrNull()

    /** nameCipher → the name; [keys] gives the TK of a key version (null: not known). */
    fun decryptName(keys: (Int) -> ByteArray?, vaultId: String, cipher: String): String {
        val b = unb64u(cipher)
        if (b.size < 65 || b[0] != VERSION || (b.size - 33) % 32 != 0) throw DriveVaultException("invalid", "name")
        val tk = keys(readU32(b, 1).toInt()) ?: throw DriveVaultException("decrypt", "no key for this version")
        val k = nameKey(tk, vaultId)
        try {
            val plain = gcm(false, k, b.copyOfRange(5, 17), b.copyOfRange(0, 5), b, 17, b.size - 17)
            return String(unpad(plain), Charsets.UTF_8)
        } finally {
            k.fill(0)
        }
    }

    // ── content ─────────────────────────────────────────────

    private fun header(keyVersion: Int, chunkSize: Int, salt: ByteArray) =
        MAGIC + byteArrayOf(VERSION) + u32(keyVersion.toLong()) + u32(chunkSize.toLong()) + salt

    private fun chunkNonce(i: Long) = ByteArray(8) + u32(i)

    private fun chunkAad(head: ByteArray, i: Long, last: Boolean) = head + u32(i) + byteArrayOf(if (last) 1 else 0)

    private fun fileKey(tk: ByteArray, vaultId: String, salt: ByteArray) = hkdf(tk, salt, "gcdrive-v1:content:$vaultId")

    /** Bytes of the ciphertext of [size] plaintext bytes. */
    fun cipherSize(size: Long, chunkSize: Int = CHUNK): Long = HEADER + size + TAG * maxOf(1L, (size + chunkSize - 1) / chunkSize)

    /** The content's header → its key version (null: not a vault file). */
    fun contentVersion(head: ByteArray): Int? =
        if (head.size >= HEADER && head.copyOf(4).contentEquals(MAGIC) && head[4] == VERSION) readU32(head, 5).toInt() else null

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Int {
        var got = 0
        while (got < len) {
            val n = input.read(buf, got, len - got)
            if (n < 0) break
            got += n
        }
        return got
    }

    /**
     * Encrypts [size] bytes of [input] into [output], chunk by chunk (at most one chunk in memory). [salt] and
     * [chunkSize] other than the default only for test vectors. [onProgress] gets the plaintext bytes done.
     */
    fun encryptContent(
        tk: ByteArray,
        vaultId: String,
        keyVersion: Int,
        input: InputStream,
        size: Long,
        output: OutputStream,
        chunkSize: Int = CHUNK,
        salt: ByteArray = randomBytes(16),
        onProgress: (Long) -> Unit = {},
    ) {
        if (size < 0) throw DriveVaultException("invalid", "size")
        val head = header(keyVersion, chunkSize, salt)
        val n = maxOf(1L, (size + chunkSize - 1) / chunkSize)
        val key = fileKey(tk, vaultId, salt)
        try {
            output.write(head)
            val buf = ByteArray(chunkSize)
            var done = 0L
            for (i in 0 until n) {
                val want = minOf(chunkSize.toLong(), size - done).toInt()
                if (readFully(input, buf, want) != want) throw EOFException("shorter than announced")
                output.write(gcm(true, key, chunkNonce(i), chunkAad(head, i, i == n - 1), buf, 0, want))
                done += want
                onProgress(done)
            }
            if (input.read() >= 0) throw IOException("longer than announced")
        } finally {
            key.fill(0)
        }
    }

    /**
     * Decrypts the content read from [input] into [output] (the end is found by reading on; no size needed). [keys]
     * gives the TK of a key version. A cut-off, reordered or changed content throws `decrypt`.
     */
    fun decryptContent(keys: (Int) -> ByteArray?, vaultId: String, input: InputStream, output: OutputStream, onProgress: (Long) -> Unit = {}) {
        val head = ByteArray(HEADER)
        if (readFully(input, head, HEADER) != HEADER) throw DriveVaultException("invalid", "too short")
        val kv = contentVersion(head) ?: throw DriveVaultException("invalid", "not a vault file")
        val cs = readU32(head, 9)
        if (cs < 1024 || cs > 16_777_216) throw DriveVaultException("invalid", "chunk size")
        val tk = keys(kv) ?: throw DriveVaultException("decrypt", "no key for this version")
        val key = fileKey(tk, vaultId, head.copyOfRange(13, 29))
        val step = cs.toInt() + TAG
        val pin = PushbackInputStream(input, 1)
        val buf = ByteArray(step)
        var i = 0L
        var done = 0L
        try {
            while (true) {
                val got = readFully(pin, buf, step)
                if (got < TAG) throw DriveVaultException("decrypt", "cut off")
                val last = if (got < step) {
                    true
                } else {
                    val next = pin.read()
                    if (next >= 0) pin.unread(next)
                    next < 0
                }
                val plain = gcm(false, key, chunkNonce(i), chunkAad(head, i, last), buf, 0, got)
                output.write(plain)
                done += plain.size
                onProgress(done)
                if (last) break
                i++
            }
        } finally {
            key.fill(0)
        }
    }

    // ── recovery key ────────────────────────────────────────

    /** 32 bytes → "R30W-5GY4-…" (the Vault plugin's format: Crockford base32 with 3 BLAKE2b check bytes). */
    fun formatRecoveryKey(key: ByteArray): String = VaultCrypto.formatRecoveryKey(key)

    /** The typed text → the 32 bytes; a wrong check throws `recovery_key`. */
    fun parseRecoveryKey(text: String): ByteArray = try {
        VaultCrypto.parseRecoveryKey(text)
    } catch (e: VaultCrypto.VaultCryptoException) {
        throw DriveVaultException("recovery_key")
    }

    /** The recovery key's proof (base64url) — the server keeps its SHA-256. */
    fun recoveryAuth(key: ByteArray): String = b64u(hkdf(key, ByteArray(0), "gcdrive-v1:recovery-auth"))

    /** The recovery key's X25519 key pair: HKDF(key, empty, "gcdrive-v1:recovery") as the scalar. */
    fun recoveryKeyPair(key: ByteArray): KeyPair {
        val seed = hkdf(key, ByteArray(0), "gcdrive-v1:recovery")
        return keyFromSeed(seed).also { seed.fill(0) }
    }
}
