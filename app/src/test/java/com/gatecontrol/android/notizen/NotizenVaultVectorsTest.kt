package com.gatecontrol.android.notizen

import com.gatecontrol.android.drive.vault.DriveVaultCrypto
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

/**
 * The Tresor notebooks of Notizen & Aufgaben: Drive's vault code with the label prefix `gcnotes-v1`, against the
 * plugin's frozen `test/vault-vectors.json` of gatecontrol-notizen **1.0.0** (gatecontrol-plugins main, commit
 * af53ac1; copied unchanged — never edit it here). The same inputs as Drive's vectors, so a key or wrap of one plugin
 * must not open anything of the other.
 */
class NotizenVaultVectorsTest {

    private val v: JsonObject = JsonParser.parseString(
        requireNotNull(javaClass.classLoader!!.getResource("notizen/vault-vectors.json")).readText(),
    ).asJsonObject

    private val input = v.getAsJsonObject("input")
    private fun JsonObject.s(k: String): String = get(k).asString
    private fun hex(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private val p = NotesVault.PREFIX
    private val vaultId = input.s("vaultId")
    private val kv = input.get("keyVersion").asInt
    private val tk = hex(input.s("vaultKey"))
    private val device = DriveVaultCrypto.keyFromSeed(hex(input.s("deviceSeed")))
    private val ephemeral = DriveVaultCrypto.keyFromSeed(hex(input.s("ephemeralSeed")))
    private val keys: (Int) -> ByteArray? = { if (it == kv) tk else null }

    @Test
    fun `the prefix is the plugin's`() {
        assertEquals(input.s("prefix"), p)
    }

    @Test
    fun `device id and safety number`() {
        val d = v.getAsJsonObject("device")
        assertEquals(d.s("publicKey"), DriveVaultCrypto.b64u(device.publicKey))
        assertEquals(d.s("deviceId"), DriveVaultCrypto.deviceId(device.publicKey, p))
        assertEquals(d.s("safetyNumber"), DriveVaultCrypto.safetyNumber(device.publicKey, p))
        // Drive's labels give another id: the two plugins never share a device id
        assertNotEquals(d.s("deviceId"), DriveVaultCrypto.deviceId(device.publicKey))
        assertEquals(v.s("ephemeralPublicKey"), DriveVaultCrypto.b64u(ephemeral.publicKey))
    }

    @Test
    fun `wrap is byte-exact, opens only with the notes labels`() {
        assertEquals(v.s("wrap"), DriveVaultCrypto.wrapKey(tk, device.publicKey, vaultId, kv, ephemeral, p))
        assertArrayEquals(tk, DriveVaultCrypto.unwrapKey(v.s("wrap"), device, vaultId, kv, p))
        val e = assertThrows(DriveVaultException::class.java) { DriveVaultCrypto.unwrapKey(v.s("wrap"), device, vaultId, kv) }
        assertEquals("decrypt", e.code)
    }

    @Test
    fun `name cipher of the notebook`() {
        val want = v.s("nameCipher")
        assertEquals(want, DriveVaultCrypto.encryptName(tk, vaultId, kv, input.s("name"), hex(input.s("nameNonce")), p))
        assertEquals(input.s("name"), DriveVaultCrypto.decryptName(keys, vaultId, want, p))
        assertEquals(input.s("name"), NotesVault.decryptName(keys, vaultId, want))
    }

    @Test
    fun `a note's markdown`() {
        val salt = hex(input.getAsJsonObject("content").s("salt"))
        val note = input.s("note")
        assertEquals(v.s("noteCipher"), NotesVault.encryptText(tk, vaultId, kv, note, salt))
        assertEquals(note, NotesVault.decryptText(keys, vaultId, v.s("noteCipher")))
        val e = assertThrows(DriveVaultException::class.java) {
            DriveVaultCrypto.decryptContent(keys, vaultId, ByteArrayInputStream(DriveVaultCrypto.unb64u(v.s("noteCipher"))), ByteArrayOutputStream())
        }
        assertEquals("decrypt", e.code)
    }

    @Test
    fun `attachment content in chunks`() {
        val c = input.getAsJsonObject("content")
        val plain = ByteArray(c.get("length").asInt) { ((it * 7 + 3) and 255).toByte() }
        val out = ByteArrayOutputStream()
        DriveVaultCrypto.encryptContent(tk, vaultId, kv, ByteArrayInputStream(plain), plain.size.toLong(), out, c.get("chunkSize").asInt, hex(c.s("salt")), p)
        val ct = out.toByteArray()
        val want = v.getAsJsonObject("content")
        assertEquals(want.get("size").asInt, ct.size)
        assertEquals(want.s("sha256"), hex(MessageDigest.getInstance("SHA-256").digest(ct)))
        assertEquals(want.s("header"), hex(ct.copyOf(DriveVaultCrypto.HEADER)))
        assertEquals(want.s("base64url"), DriveVaultCrypto.b64u(ct))
        val back = ByteArrayOutputStream()
        DriveVaultCrypto.decryptContent(keys, vaultId, ByteArrayInputStream(ct), back, p)
        assertArrayEquals(plain, back.toByteArray())
    }

    @Test
    fun `recovery key`() {
        val r = v.getAsJsonObject("recovery")
        val key = hex(input.s("recoveryKey"))
        assertEquals(r.s("text"), DriveVaultCrypto.formatRecoveryKey(key))
        assertArrayEquals(key, DriveVaultCrypto.parseRecoveryKey(r.s("text")))
        val pair = DriveVaultCrypto.recoveryKeyPair(key, p)
        assertEquals(r.s("publicKey"), DriveVaultCrypto.b64u(pair.publicKey))
        assertEquals(r.s("auth"), DriveVaultCrypto.recoveryAuth(key, p))
        assertEquals(r.s("wrap"), DriveVaultCrypto.wrapKey(tk, pair.publicKey, vaultId, kv, ephemeral, p))
        assertArrayEquals(tk, DriveVaultCrypto.unwrapKey(r.s("wrap"), pair, vaultId, kv, p))
    }
}
