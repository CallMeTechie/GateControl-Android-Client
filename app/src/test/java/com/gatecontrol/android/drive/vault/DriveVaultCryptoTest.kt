package com.gatecontrol.android.drive.vault

import com.gatecontrol.android.drive.vault.DriveVaultCrypto.DriveVaultException
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.Normalizer

/**
 * Drive's vault format against `drive-vault-vectors.json` — the very file the plugin's tests pin
 * (gatecontrol-plugins: plugins/gatecontrol-drive/test/vault-vectors.json; never edit it here): every byte Android
 * writes, the portal and Windows read.
 */
class DriveVaultCryptoTest {

    private val v: JsonObject = JsonParser.parseString(
        requireNotNull(javaClass.classLoader!!.getResource("drive-vault-vectors.json")).readText(),
    ).asJsonObject

    private val input = v.getAsJsonObject("input")
    private fun JsonObject.s(k: String): String = get(k).asString
    private fun hex(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private val vaultId = input.s("vaultId")
    private val kv = input.get("keyVersion").asInt
    private val tk = hex(input.s("vaultKey"))
    private val device = DriveVaultCrypto.keyFromSeed(hex(input.s("deviceSeed")))
    private val ephemeral = DriveVaultCrypto.keyFromSeed(hex(input.s("ephemeralSeed")))
    private val keys: (Int) -> ByteArray? = { if (it == kv) tk else null }

    private fun code(c: String, block: () -> Unit) {
        val e = assertThrows(DriveVaultException::class.java) { block() }
        assertEquals(c, e.code)
    }

    @Test
    fun `device key, device id and safety number`() {
        val d = v.getAsJsonObject("device")
        assertEquals(d.s("publicKey"), DriveVaultCrypto.b64u(device.publicKey))
        assertEquals(d.s("deviceId"), DriveVaultCrypto.deviceId(device.publicKey))
        assertEquals(d.s("safetyNumber"), DriveVaultCrypto.safetyNumber(device.publicKey))
        assertEquals(v.s("ephemeralPublicKey"), DriveVaultCrypto.b64u(ephemeral.publicKey))
    }

    @Test
    fun `wrap is byte-exact and unwraps to the vault key`() {
        assertEquals(v.s("wrap"), DriveVaultCrypto.wrapKey(tk, device.publicKey, vaultId, kv, ephemeral))
        assertArrayEquals(tk, DriveVaultCrypto.unwrapKey(v.s("wrap"), device, vaultId, kv))
        // a fresh ephemeral key: another wrap, the same key
        val fresh = DriveVaultCrypto.wrapKey(tk, device.publicKey, vaultId, kv)
        assertNotEquals(v.s("wrap"), fresh)
        assertArrayEquals(tk, DriveVaultCrypto.unwrapKey(fresh, device, vaultId, kv))
        // bound to the vault and the key version
        code("decrypt") { DriveVaultCrypto.unwrapKey(v.s("wrap"), device, vaultId, kv + 1) }
        code("decrypt") { DriveVaultCrypto.unwrapKey(v.s("wrap"), device, "x$vaultId".take(22), kv) }
        code("decrypt") { DriveVaultCrypto.unwrapKey(v.s("wrap"), DriveVaultCrypto.newKeyPair(), vaultId, kv) }
        code("invalid") { DriveVaultCrypto.unwrapKey(v.s("wrap").dropLast(4), device, vaultId, kv) }
    }

    @Test
    fun `nameCipher is byte-exact, NFC and decrypts`() {
        val name = input.s("name")
        assertEquals(v.s("nameCipher"), DriveVaultCrypto.encryptName(tk, vaultId, kv, name, hex(input.s("nameNonce"))))
        val nfd = Normalizer.normalize(name, Normalizer.Form.NFD)
        assertNotEquals(name, nfd)
        assertEquals(v.s("nameCipher"), DriveVaultCrypto.encryptName(tk, vaultId, kv, nfd, hex(input.s("nameNonce"))))
        assertEquals(name, DriveVaultCrypto.decryptName(keys, vaultId, v.s("nameCipher")))
        assertEquals(kv, DriveVaultCrypto.nameVersion(v.s("nameCipher")))
        // a random nonce: another cipher, the same name; another vault: not readable
        val again = DriveVaultCrypto.encryptName(tk, vaultId, kv, name)
        assertNotEquals(v.s("nameCipher"), again)
        assertEquals(name, DriveVaultCrypto.decryptName(keys, vaultId, again))
        code("decrypt") { DriveVaultCrypto.decryptName(keys, "Other0VaultId000000000", v.s("nameCipher")) }
        code("decrypt") { DriveVaultCrypto.decryptName({ null }, vaultId, v.s("nameCipher")) }
        assertEquals("", DriveVaultCrypto.decryptName(keys, vaultId, DriveVaultCrypto.encryptName(tk, vaultId, kv, "")))
    }

    private fun contentBytes(): ByteArray {
        val c = input.getAsJsonObject("content")
        return ByteArray(c.get("length").asInt) { ((it * 7 + 3) and 255).toByte() }
    }

    @Test
    fun `content is byte-exact in chunks and decrypts`() {
        val c = input.getAsJsonObject("content")
        val want = v.getAsJsonObject("content")
        val plain = contentBytes()
        val out = ByteArrayOutputStream()
        DriveVaultCrypto.encryptContent(tk, vaultId, kv, ByteArrayInputStream(plain), plain.size.toLong(), out, c.get("chunkSize").asInt, hex(c.s("salt")))
        val cipher = out.toByteArray()
        assertEquals(want.get("size").asLong, cipher.size.toLong())
        assertEquals(want.get("size").asLong, DriveVaultCrypto.cipherSize(plain.size.toLong(), c.get("chunkSize").asInt))
        assertEquals(want.s("header"), hex(cipher.copyOf(DriveVaultCrypto.HEADER)))
        assertEquals(want.s("sha256"), hex(MessageDigest.getInstance("SHA-256").digest(cipher)))
        assertEquals(want.s("base64url"), DriveVaultCrypto.b64u(cipher))

        val back = ByteArrayOutputStream()
        DriveVaultCrypto.decryptContent(keys, vaultId, ByteArrayInputStream(DriveVaultCrypto.unb64u(want.s("base64url"))), back)
        assertArrayEquals(plain, back.toByteArray())
    }

    @Test
    fun `content - empty file, default chunks, and tampering`() {
        val empty = ByteArrayOutputStream()
        DriveVaultCrypto.encryptContent(tk, vaultId, kv, ByteArrayInputStream(ByteArray(0)), 0, empty)
        assertEquals((DriveVaultCrypto.HEADER + DriveVaultCrypto.TAG).toLong(), empty.size().toLong())
        assertEquals(DriveVaultCrypto.cipherSize(0), empty.size().toLong())
        val emptyBack = ByteArrayOutputStream()
        DriveVaultCrypto.decryptContent(keys, vaultId, ByteArrayInputStream(empty.toByteArray()), emptyBack)
        assertEquals(0, emptyBack.size())

        // exactly two default chunks: the last one is full
        val two = ByteArray(2 * DriveVaultCrypto.CHUNK) { (it % 251).toByte() }
        val cipher = ByteArrayOutputStream().also { DriveVaultCrypto.encryptContent(tk, vaultId, kv, ByteArrayInputStream(two), two.size.toLong(), it) }.toByteArray()
        assertEquals(DriveVaultCrypto.cipherSize(two.size.toLong()), cipher.size.toLong())
        val back = ByteArrayOutputStream().also { DriveVaultCrypto.decryptContent(keys, vaultId, ByteArrayInputStream(cipher), it) }.toByteArray()
        assertArrayEquals(two, back)

        // cut after the first chunk: its "last" flag does not fit
        val cut = cipher.copyOf(DriveVaultCrypto.HEADER + DriveVaultCrypto.CHUNK + DriveVaultCrypto.TAG)
        code("decrypt") { DriveVaultCrypto.decryptContent(keys, vaultId, ByteArrayInputStream(cut), ByteArrayOutputStream()) }
        // one bit changed
        val bad = cipher.copyOf().also { it[DriveVaultCrypto.HEADER + 5] = (it[DriveVaultCrypto.HEADER + 5].toInt() xor 1).toByte() }
        code("decrypt") { DriveVaultCrypto.decryptContent(keys, vaultId, ByteArrayInputStream(bad), ByteArrayOutputStream()) }
        // another vault
        code("decrypt") { DriveVaultCrypto.decryptContent(keys, "Other0VaultId000000000", ByteArrayInputStream(cipher), ByteArrayOutputStream()) }
        code("invalid") { DriveVaultCrypto.decryptContent(keys, vaultId, ByteArrayInputStream("not a vault file at all, really".toByteArray()), ByteArrayOutputStream()) }
    }

    @Test
    fun `recovery key - text, key pair, proof and wrap`() {
        val r = v.getAsJsonObject("recovery")
        val key = hex(input.s("recoveryKey"))
        assertEquals(r.s("text"), DriveVaultCrypto.formatRecoveryKey(key))
        assertArrayEquals(key, DriveVaultCrypto.parseRecoveryKey(r.s("text")))
        assertArrayEquals(key, DriveVaultCrypto.parseRecoveryKey(r.s("text").lowercase().replace("-", " ")))
        code("recovery_key") { DriveVaultCrypto.parseRecoveryKey(r.s("text").replaceFirst('R', 'S')) }
        val pair = DriveVaultCrypto.recoveryKeyPair(key)
        assertEquals(r.s("publicKey"), DriveVaultCrypto.b64u(pair.publicKey))
        assertEquals(r.s("auth"), DriveVaultCrypto.recoveryAuth(key))
        assertEquals(r.s("wrap"), DriveVaultCrypto.wrapKey(tk, pair.publicKey, vaultId, kv, ephemeral))
        assertArrayEquals(tk, DriveVaultCrypto.unwrapKey(r.s("wrap"), pair, vaultId, kv))
    }
}
