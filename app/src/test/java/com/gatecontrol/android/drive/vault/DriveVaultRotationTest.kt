package com.gatecontrol.android.drive.vault

import com.gatecontrol.android.drive.DVaultDevice
import com.gatecontrol.android.drive.DriveJson
import com.gatecontrol.android.drive.vault.DriveVaultCrypto.DriveVaultException
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A new vault key (`POST vaults/<id>/rotate`) and a new recovery key (`PUT vaults/<id>/recovery`) against the plugin's
 * vectors (`drive-vault-vectors.json`): the wraps are byte-exact with the fixed ephemeral key, the shapes the server's.
 */
class DriveVaultRotationTest {

    private val v: JsonObject = JsonParser.parseString(
        requireNotNull(javaClass.classLoader!!.getResource("drive-vault-vectors.json")).readText(),
    ).asJsonObject
    private val input = v.getAsJsonObject("input")
    private fun JsonObject.s(k: String): String = get(k).asString
    private fun hex(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val vaultId = input.s("vaultId")
    private val kv = input.get("keyVersion").asInt
    private val tk = hex(input.s("vaultKey"))
    private val device = DriveVaultCrypto.keyFromSeed(hex(input.s("deviceSeed")))
    private val ephemeral = DriveVaultCrypto.keyFromSeed(hex(input.s("ephemeralSeed")))
    private val recoveryKey = hex(input.s("recoveryKey"))
    private val rec = v.getAsJsonObject("recovery")
    private val dev = v.getAsJsonObject("device")

    private fun device(id: String = dev.s("deviceId"), pub: String = dev.s("publicKey"), state: String = "approved") =
        DVaultDevice(id, 7, "Pixel", "android", pub, state, null)

    private val other = DriveVaultCrypto.newKeyPair()
    private val otherDevice = device(DriveVaultCrypto.deviceId(other.publicKey), DriveVaultCrypto.b64u(other.publicKey))

    @Test
    fun `rotate body - the vector wraps for the device and the recovery key`() {
        val body = DriveVaultRotation.rotateBody(
            tk, vaultId, kv, listOf(device()), rec.s("publicKey"), null,
            ephemeral = { ephemeral }, recoveryEphemeral = ephemeral,
        )
        assertEquals(kv, body.get("keyVersion").asInt)
        val wraps = body.getAsJsonArray("wraps")
        assertEquals(1, wraps.size())
        assertEquals(dev.s("deviceId"), wraps[0].asJsonObject.s("deviceId"))
        assertEquals(v.s("wrap"), wraps[0].asJsonObject.s("wrap"))
        assertEquals(rec.s("wrap"), body.s("recoveryWrap"))
        assertFalse(body.has("recovery"))
        // the device opens it again
        assertArrayEquals(tk, DriveVaultCrypto.unwrapKey(wraps[0].asJsonObject.s("wrap"), device, vaultId, kv))
    }

    @Test
    fun `rotate body - exactly every approved device, by id`() {
        val pending = device(DriveVaultCrypto.deviceId(DriveVaultCrypto.newKeyPair().publicKey), DriveVaultCrypto.b64u(DriveVaultCrypto.newKeyPair().publicKey), "pending")
        val revoked = otherDevice.copy(deviceId = "AAAAAAAAAAAAAAAAAAAAAA", state = "revoked")
        val next = 2
        val newTk = DriveVaultCrypto.newVaultKey()
        val body = DriveVaultRotation.rotateBody(newTk, vaultId, next, listOf(otherDevice, pending, device(), revoked), rec.s("publicKey"), null)
        val ids = body.getAsJsonArray("wraps").map { it.asJsonObject.s("deviceId") }
        assertEquals(listOf(dev.s("deviceId"), otherDevice.deviceId).sorted(), ids)
        // each wrap is bound to the new version
        val mine = body.getAsJsonArray("wraps").first { it.asJsonObject.s("deviceId") == dev.s("deviceId") }.asJsonObject.s("wrap")
        assertArrayEquals(newTk, DriveVaultCrypto.unwrapKey(mine, device, vaultId, next))
        val e = assertThrows(DriveVaultException::class.java) { DriveVaultCrypto.unwrapKey(mine, device, vaultId, kv) }
        assertEquals("decrypt", e.code)
        // the recovery key gets it too
        val recPair = DriveVaultCrypto.recoveryKeyPair(recoveryKey)
        assertArrayEquals(newTk, DriveVaultCrypto.unwrapKey(body.s("recoveryWrap"), recPair, vaultId, next))
    }

    @Test
    fun `a device whose id is not its key's gets nothing`() {
        val swapped = device(pub = DriveVaultCrypto.b64u(other.publicKey))
        val e = assertThrows(DriveVaultException::class.java) {
            DriveVaultRotation.rotateBody(tk, vaultId, 2, listOf(swapped), rec.s("publicKey"), null)
        }
        assertEquals("invalid", e.code)
    }

    @Test
    fun `a new recovery key - the vector's public key, proof and wrap`() {
        val part = DriveVaultRotation.recovery(recoveryKey, vaultId, mapOf(kv to tk), kv, ephemeral = { ephemeral })
        assertEquals(rec.s("publicKey"), part.pub)
        assertEquals(rec.s("auth"), part.auth)
        assertEquals(1, part.wraps.size)
        assertEquals(kv, part.wraps[0].keyVersion)
        assertEquals(rec.s("wrap"), part.wraps[0].wrap)
        assertEquals("R30W-5GY4-RQ3C-FJ69-SB5W-SKEE-SZ8D-3MPK-TKAX-DNYR-V7DD-QQ6X-VVFJ-63ZJ", DriveVaultCrypto.formatRecoveryKey(recoveryKey))
        // PUT vaults/<id>/recovery
        val put = DriveJson.recoveryBody(part)
        assertEquals(rec.s("publicKey"), put.s("pub"))
        assertEquals(rec.s("auth"), put.s("auth"))
        assertEquals(rec.s("wrap"), put.getAsJsonArray("wraps")[0].asJsonObject.s("wrap"))
        assertEquals(kv, put.getAsJsonArray("wraps")[0].asJsonObject.get("keyVersion").asInt)
    }

    @Test
    fun `a rotation with a new recovery key wraps every version for it`() {
        val tk2 = DriveVaultCrypto.newVaultKey()
        val part = DriveVaultRotation.recovery(recoveryKey, vaultId, mapOf(1 to tk, 2 to tk2), 2)
        val body = DriveVaultRotation.rotateBody(tk2, vaultId, 2, listOf(device()), rec.s("publicKey"), part)
        assertFalse(body.has("recoveryWrap"))
        val r = body.getAsJsonObject("recovery")
        assertEquals(rec.s("publicKey"), r.s("pub"))
        assertEquals(rec.s("auth"), r.s("auth"))
        val wraps = r.getAsJsonArray("wraps").map { it.asJsonObject }
        assertEquals(listOf(1, 2), wraps.map { it.get("keyVersion").asInt })
        val pair = DriveVaultCrypto.recoveryKeyPair(recoveryKey)
        assertArrayEquals(tk, DriveVaultCrypto.unwrapKey(wraps[0].s("wrap"), pair, vaultId, 1))
        assertArrayEquals(tk2, DriveVaultCrypto.unwrapKey(wraps[1].s("wrap"), pair, vaultId, 2))
        // a version this device lacks: nothing is sent
        val e = assertThrows(DriveVaultException::class.java) { DriveVaultRotation.recovery(recoveryKey, vaultId, mapOf(2 to tk2), 2) }
        assertEquals("old_keys", e.code)
        // without a recovery key there is nothing to wrap for: a new one is needed
        val none = assertThrows(DriveVaultException::class.java) { DriveVaultRotation.rotateBody(tk2, vaultId, 2, listOf(device()), null, null) }
        assertEquals("recovery_required", none.code)
    }

    @Test
    fun `the refusals of a rotation`() {
        val devices = JsonParser.parseString("""{"error":"conflict","devices":["${dev.s("deviceId")}","bad id"]}""").asJsonObject
        assertEquals(listOf(dev.s("deviceId")), DriveJson.rotateDevices(devices))
        assertNull(DriveJson.rotateDevices(JsonParser.parseString("""{"error":"conflict","keyVersion":3}""").asJsonObject))
        assertTrue(DriveJson.recoveryRequired(JsonParser.parseString("""{"error":"conflict","reason":"recovery_required"}""").asJsonObject))
        assertFalse(DriveJson.recoveryRequired(devices))
        assertEquals(3, DriveJson.staleKeyVersion(JsonParser.parseString("""{"error":"conflict","keyVersion":3}""").asJsonObject))
    }
}
