package com.gatecontrol.android.vault

import com.gatecontrol.android.vault.VaultCrypto.VaultCryptoException
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.Normalizer

/**
 * Vault crypto against `vault-vectors.json` — the very file client-core's tests pin (copied from
 * gatecontrol-client-core/test/fixtures; never edit it here): every byte Android writes, Windows and the portal read.
 */
class VaultCryptoTest {

    private val v: JsonObject = JsonParser.parseString(
        requireNotNull(javaClass.classLoader!!.getResource("vault-vectors.json")).readText(),
    ).asJsonObject

    private fun o(name: String) = v.getAsJsonObject(name)
    private fun JsonObject.s(k: String) = get(k).asString
    private fun hex(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun kdf(j: JsonObject) = VaultCrypto.Kdf(j.s("alg"), j.get("m").asInt, j.get("t").asInt, j.get("p").asInt, j.s("salt"))
    private val fast = { VaultCrypto.newKdf(m = 19456, t = 2) }

    private fun code(c: String, block: () -> Unit) {
        val e = assertThrows(VaultCryptoException::class.java) { block() }
        assertEquals(c, e.code)
    }

    @Test
    fun `master key - Argon2id, NFC and NFD give the same key`() {
        val m = o("masterKey")
        assertEquals(m.s("key"), hex(VaultCrypto.masterKey(m.s("password"), kdf(m.getAsJsonObject("kdf")))))
        val nfd = Normalizer.normalize(m.s("password"), Normalizer.Form.NFD)
        assertNotEquals(m.s("password"), nfd)
        assertEquals(m.s("key"), hex(VaultCrypto.masterKey(nfd, kdf(m.getAsJsonObject("kdf")))))
    }

    @Test
    fun `wrap, vault name, entry and recovery key are byte-exact`() {
        val w = o("wrap")
        assertEquals(w.s("wrapped"), VaultCrypto.b64(VaultCrypto.wrap(hex(w.s("key")), hex(w.s("plaintext")), w.s("ad"), hex(w.s("nonce")))))
        assertEquals(w.s("plaintext"), hex(VaultCrypto.unwrap(hex(w.s("key")), w.s("wrapped"), w.s("ad"))))
        val n = o("name")
        assertEquals(n.s("nameEnc"), VaultCrypto.encryptName(hex(n.s("key")), n.s("vaultId"), n.s("name"), hex(n.s("nonce"))))
        assertEquals(n.s("name"), VaultCrypto.decryptName(hex(n.s("key")), n.s("vaultId"), n.s("nameEnc")))
        val i = o("item")
        val rev = i.get("rev").asInt
        assertEquals(i.s("blob"), VaultCrypto.encryptItemJson(hex(i.s("key")), i.s("vaultId"), i.s("itemId"), rev, i.s("json"), hex(i.s("nonce"))))
        assertEquals(i.s("json"), VaultCrypto.decryptItemJson(hex(i.s("key")), i.s("vaultId"), i.s("itemId"), rev, i.s("blob")))
        val r = o("recoveryKey")
        assertEquals(r.s("text"), VaultCrypto.formatRecoveryKey(hex(r.s("key"))))
        assertEquals(r.s("key"), hex(VaultCrypto.parseRecoveryKey(r.s("text"))))
        val sn = o("safetyNumber")
        assertEquals(sn.s("text"), VaultCrypto.safetyNumber(hex(sn.s("publicKey"))))
    }

    @Test
    fun `the seeded key pair and libsodium's sealed box`() {
        val s = o("sealed")
        val pair = VaultCrypto.seedKeyPair(hex(s.s("seed")))
        assertEquals(s.s("publicKey"), hex(pair.publicKey))
        assertEquals(s.s("secretKey"), hex(pair.secretKey))
        assertEquals(s.s("key"), hex(VaultCrypto.openSealed(s.s("sealed"), pair.publicKey, pair.secretKey)))
        // and ours opens the same way
        val again = VaultCrypto.seal(hex(s.s("key")), pair.publicKey)
        assertEquals(80, again.size)
        assertEquals(s.s("key"), hex(VaultCrypto.openSealed(again, pair.publicKey, pair.secretKey)))
        val other = VaultCrypto.newKeyPair()
        code("decrypt") { VaultCrypto.openSealed(again, other.publicKey, other.secretKey) }
        code("decrypt") { VaultCrypto.openSealed(again.also { it[50] = (it[50] + 1).toByte() }, pair.publicKey, pair.secretKey) }
    }

    @Test
    fun `the whole account body, unlock with password and recovery key`() {
        val a = o("account")
        val r = a.getAsJsonObject("rnd")
        val rnd = VaultCrypto.Rnd(
            userKey = hex(r.s("userKey")), recoveryKey = hex(r.s("recoveryKey")), keySeed = hex(r.s("keySeed")),
            personalId = r.s("personalId"), personalKey = hex(r.s("personalKey")),
            nonces = r.getAsJsonArray("nonces").map { hex(it.asString) },
        )
        val setup = VaultCrypto.createAccount(a.s("password"), a.s("personalName"), kdf(a.getAsJsonObject("kdf")), rnd)
        val body = a.getAsJsonObject("body")
        assertEquals(body.s("userKeyPw"), setup.body.userKeyPw)
        assertEquals(body.s("userKeyRk"), setup.body.userKeyRk)
        assertEquals(body.s("publicKey"), setup.body.publicKey)
        assertEquals(body.s("privateKeyEnc"), setup.body.privateKeyEnc)
        assertEquals(body.getAsJsonObject("personal").s("id"), setup.body.personal.id)
        assertEquals(body.getAsJsonObject("personal").s("nameEnc"), setup.body.personal.nameEnc)
        assertEquals(a.s("recoveryKey"), setup.recoveryKey)
        assertEquals(r.s("recoveryKey"), hex(rnd.recoveryKey), "the caller's arrays stay as they were")
        assertEquals(r.s("personalKey"), hex(VaultCrypto.openSealed(setup.body.personal.sealedKey, setup.keys.publicKey, setup.keys.secretKey)))

        val account = VaultCrypto.Account(kdf(body.getAsJsonObject("kdf")), body.s("userKeyPw"), body.s("userKeyRk"), body.s("publicKey"), body.s("privateKeyEnc"), 1)
        assertEquals(r.s("userKey"), hex(VaultCrypto.unlock(account, a.s("password")).userKey))
        assertEquals(r.s("userKey"), hex(VaultCrypto.unlockWithRecovery(account, a.s("recoveryKey")).userKey))
        code("decrypt") { VaultCrypto.unlock(account, a.s("password") + "!") }
        code("decrypt") { VaultCrypto.unlock(account.copy(userKeyPw = account.userKeyRk), a.s("password")) }
    }

    @Test
    fun `recovery key typed loosely works, a typo is caught`() {
        val text = o("recoveryKey").s("text")
        val key = o("recoveryKey").s("key")
        assertEquals(key, hex(VaultCrypto.parseRecoveryKey(text.lowercase().replace("-", " ").replace('0', 'o').replace('1', 'l'))))
        val typo = text.replaceFirst('R', 'S')
        code("recovery_key") { VaultCrypto.parseRecoveryKey(typo) }
        code("recovery_key") { VaultCrypto.parseRecoveryKey(text.drop(5)) }
        code("recovery_key") { VaultCrypto.parseRecoveryKey(text.dropLast(1) + "U") }
    }

    @Test
    fun `new password and recovery keep the user key`() {
        val setup = VaultCrypto.createAccount("alt", "P", fast())
        val acc = VaultCrypto.Account(setup.body.kdf, setup.body.userKeyPw, setup.body.userKeyRk, setup.body.publicKey, setup.body.privateKeyEnc, 3)
        val up = VaultCrypto.changePassword(acc, setup.keys, "neu", fast())
        assertEquals(3, up.rev)
        val after = acc.copy(kdf = up.kdf, userKeyPw = up.userKeyPw)
        assertArrayEquals(setup.keys.userKey, VaultCrypto.unlock(after, "neu").userKey)
        code("decrypt") { VaultCrypto.unlock(after, "alt") }
        val (rec, newRk) = VaultCrypto.recover(acc, VaultCrypto.unlockWithRecovery(acc, setup.recoveryKey), "ganz neu", fast())
        val recovered = acc.copy(kdf = rec.kdf, userKeyPw = rec.userKeyPw, userKeyRk = rec.userKeyRk!!)
        assertArrayEquals(setup.keys.userKey, VaultCrypto.unlockWithRecovery(recovered, newRk).userKey)
        code("decrypt") { VaultCrypto.unlockWithRecovery(recovered, setup.recoveryKey) }
    }

    @Test
    fun `entries are bound to vault, id and revision, padded, 32 KiB at most`() {
        val key = VaultCrypto.newVaultKey()
        val json = """{"v":1,"type":"login","title":"Bank"}"""
        val blob = VaultCrypto.encryptItemJson(key, "vault-aaaaaaaaaaaaaa", "item-bbbbbbbbbbbbbbbb", 2, json)
        assertEquals(json, VaultCrypto.decryptItemJson(key, "vault-aaaaaaaaaaaaaa", "item-bbbbbbbbbbbbbbbb", 2, blob))
        code("decrypt") { VaultCrypto.decryptItemJson(key, "vault-aaaaaaaaaaaaaa", "item-bbbbbbbbbbbbbbbb", 3, blob) }
        code("decrypt") { VaultCrypto.decryptItemJson(key, "vault-cccccccccccccc", "item-bbbbbbbbbbbbbbbb", 2, blob) }
        code("decrypt") { VaultCrypto.decryptItemJson(key, "vault-aaaaaaaaaaaaaa", "item-dddddddddddddddd", 2, blob) }
        code("decrypt") { VaultCrypto.decryptItemJson(VaultCrypto.newVaultKey(), "vault-aaaaaaaaaaaaaa", "item-bbbbbbbbbbbbbbbb", 2, blob) }
        val longer = VaultCrypto.encryptItemJson(key, "v", "i", 1, """{"v":1,"type":"note","title":"${"a".repeat(150)}"}""")
        assertEquals(VaultCrypto.unb64(blob).size, VaultCrypto.unb64(longer).size)
        assertEquals(0, (VaultCrypto.unb64(blob).size - 41) % 256)
        code("too_large") { VaultCrypto.encryptItemJson(key, "v", "i", 1, "x".repeat(33000)) }
        code("invalid") { VaultCrypto.encryptItemJson(key, "v", "i", 0, json) }
    }

    @Test
    fun `padding edge cases`() {
        for (n in listOf(0, 1, 254, 255, 256, 511)) {
            val p = VaultCrypto.pad(ByteArray(n) { 7 })
            assertEquals(0, p.size % 256)
            assertEquals(n, VaultCrypto.unpad(p).size)
        }
        code("decrypt") { VaultCrypto.unpad(ByteArray(256)) }
    }

    @Test
    fun `emergency access opens the grantor's keys for the trusted person only`() {
        val anna = VaultCrypto.createAccount("a", "P", fast())
        val marc = VaultCrypto.createAccount("m", "P", fast()).keys
        val sealed = VaultCrypto.sealForEmergency(anna.keys, marc.publicKey)
        val grantor = VaultCrypto.openEmergency(sealed, anna.body.publicKey, anna.body.privateKeyEnc, marc)
        assertArrayEquals(anna.keys.secretKey, grantor.secretKey)
        assertArrayEquals(anna.personalKey, VaultCrypto.openSealed(anna.body.personal.sealedKey, grantor.publicKey, grantor.secretKey))
        code("decrypt") { VaultCrypto.openEmergency(sealed, anna.body.publicKey, anna.body.privateKeyEnc, anna.keys) }
    }

    @Test
    fun `garbage in - invalid, never a crash`() {
        code("invalid") { VaultCrypto.unwrap(VaultCrypto.newVaultKey(), "not base64!", "x") }
        code("invalid") { VaultCrypto.unwrap(VaultCrypto.newVaultKey(), "AAAA", "x") }
        code("invalid") { VaultCrypto.masterKey("pw", fast().copy(p = 2)) }
        code("invalid") { VaultCrypto.masterKey("pw", fast().copy(m = 1024)) }
        code("invalid") { VaultCrypto.masterKey("", fast()) }
        assertTrue(VaultCrypto.newId().matches(Regex("^[A-Za-z0-9_-]{22}$")))
    }
}
