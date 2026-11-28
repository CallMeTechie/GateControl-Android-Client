package com.gatecontrol.android.drive.vault

import com.gatecontrol.android.drive.vault.DriveVaultCrypto.DriveVaultException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * "Nur auf diesem Gerät" (Drive 1.32): the device's proof, the folder key LK sealed to the recovery key and to the device
 * key, and the content and names in LK with key version 0. The challenge below was sealed by the plugin's own
 * `hpkeSeal` (server/vaultlocal.js) to the device key of `drive-vault-vectors.json` — Android opens what the server sends.
 */
class DriveVaultLocalCryptoTest {

    private fun hex(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val vaultId = "Vt9bJm0n2QwErTyUiOpAsD"
    private val device = DriveVaultCrypto.keyFromSeed(hex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"))
    private val deviceId = DriveVaultCrypto.deviceId(device.publicKey)
    private val tk = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
    private val recovery = DriveVaultCrypto.recoveryKeyPair(hex("c0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedf"))

    /** Sealed by the plugin (Node, `hpkeSeal(pub, "gcdrive-v1:local-proof:<vault>:<device>", 0xa0…0xbf)`). */
    private val pluginChallenge = "AajkC0_9q1ArdJ97HWFC_furUtaNgF_aaxi0-IgQy-QcswERKXnLhqoFRcFB53i_ahkiMsfJqReuwTjBtRgy-dMUmMEdGTS7Mh-HBfmgcj4p"
    private val pluginProof = "oKGio6SlpqeoqaqrrK2ur7CxsrO0tba3uLm6u7y9vr8"

    private fun code(c: String, block: () -> Unit) {
        val e = assertThrows(DriveVaultException::class.java) { block() }
        assertEquals(c, e.code)
    }

    @Test
    fun `the plugin's challenge opens to its proof`() {
        assertEquals("a7K-bh8_xeRZDI08-8JcCw", deviceId)
        val proof = DriveVaultCrypto.openProof(pluginChallenge, device, vaultId, deviceId)
        assertEquals(pluginProof, proof)
        assertEquals(43, proof.length)
    }

    @Test
    fun `a challenge of another vault, another device or another key does not open`() {
        code("decrypt") { DriveVaultCrypto.openProof(pluginChallenge, device, "Xt9bJm0n2QwErTyUiOpAsD", deviceId) }
        code("decrypt") { DriveVaultCrypto.openProof(pluginChallenge, device, vaultId, "b7K-bh8_xeRZDI08-8JcCw") }
        code("decrypt") { DriveVaultCrypto.openProof(pluginChallenge, DriveVaultCrypto.newKeyPair(), vaultId, deviceId) }
        code("invalid") { DriveVaultCrypto.openProof(pluginChallenge.dropLast(4), device, vaultId, deviceId) }
        val otherVersion = DriveVaultCrypto.unb64u(pluginChallenge).also { it[0] = 2 }
        code("invalid") { DriveVaultCrypto.openProof(DriveVaultCrypto.b64u(otherVersion), device, vaultId, deviceId) }
    }

    @Test
    fun `seal and open of a proof agree`() {
        val secret = DriveVaultCrypto.randomBytes(32)
        val c = DriveVaultCrypto.sealProof(secret, device.publicKey, vaultId, deviceId)
        assertEquals(108, c.length)
        assertEquals(DriveVaultCrypto.b64u(secret), DriveVaultCrypto.openProof(c, device, vaultId, deviceId))
    }

    @Test
    fun `LK sealed to the recovery key opens only with it, for that very folder`() {
        val lk = DriveVaultCrypto.randomBytes(32)
        val info = DriveVaultCrypto.localKeyInfo(vaultId, 42)
        assertEquals("gcdrive-v1:lk:Vt9bJm0n2QwErTyUiOpAsD:42", info)
        val sealed = DriveVaultCrypto.sealLocalKey(lk, recovery.publicKey, info)
        assertEquals(108, sealed.length)
        assertArrayEquals(lk, DriveVaultCrypto.openLocalKey(sealed, recovery, info))
        code("decrypt") { DriveVaultCrypto.openLocalKey(sealed, recovery, DriveVaultCrypto.localKeyInfo(vaultId, 43)) }
        code("decrypt") { DriveVaultCrypto.openLocalKey(sealed, device, info) }
        // the device's own seal is another label: a recovery seal never opens as a device seal
        code("decrypt") { DriveVaultCrypto.openLocalKey(sealed, recovery, DriveVaultCrypto.localDeviceInfo(vaultId, 42)) }
    }

    @Test
    fun `LK seal with a fixed ephemeral key is byte-stable`() {
        val lk = hex("e0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
        val eph = DriveVaultCrypto.keyFromSeed(hex("2122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f40"))
        val a = DriveVaultCrypto.sealLocalKey(lk, recovery.publicKey, DriveVaultCrypto.localKeyInfo(vaultId, 7), eph)
        val b = DriveVaultCrypto.sealLocalKey(lk, recovery.publicKey, DriveVaultCrypto.localKeyInfo(vaultId, 7), eph)
        assertEquals(a, b)
        // the same LK as a wrap of TK (other label): another ciphertext
        assertNotEquals(DriveVaultCrypto.wrapKey(lk, recovery.publicKey, vaultId, 7, eph), a)
    }

    @Test
    fun `names and content in LK carry key version 0 and never open with the vault key`() {
        val lk = DriveVaultCrypto.randomBytes(32)
        val local: (Int) -> ByteArray? = { if (it == DriveVaultCrypto.LOCAL_KEY_VERSION) lk else null }
        val name = DriveVaultCrypto.encryptName(lk, vaultId, DriveVaultCrypto.LOCAL_KEY_VERSION, "Personalausweis.pdf")
        assertEquals(0, DriveVaultCrypto.nameVersion(name))
        assertEquals("Personalausweis.pdf", DriveVaultCrypto.decryptName(local, vaultId, name))
        code("decrypt") { DriveVaultCrypto.decryptName({ if (it == 0) tk else null }, vaultId, name) }

        val plain = ByteArray(3000) { (it * 7 + 3).toByte() }
        val out = ByteArrayOutputStream()
        DriveVaultCrypto.encryptContent(lk, vaultId, 0, ByteArrayInputStream(plain), plain.size.toLong(), out, chunkSize = 1024)
        assertEquals(0, DriveVaultCrypto.contentVersion(out.toByteArray()))
        val back = ByteArrayOutputStream()
        DriveVaultCrypto.decryptContent(local, vaultId, ByteArrayInputStream(out.toByteArray()), back)
        assertArrayEquals(plain, back.toByteArray())
    }

    @Test
    fun `transcode vault key to LK and back keeps every byte, the digest says so`() {
        val lk = DriveVaultCrypto.randomBytes(32)
        val plain = ByteArray(5000) { (it * 13 + 1).toByte() }
        val tkCipher = ByteArrayOutputStream().also { DriveVaultCrypto.encryptContent(tk, vaultId, 1, ByteArrayInputStream(plain), plain.size.toLong(), it, chunkSize = 1024) }
        val tkKeys: (Int) -> ByteArray? = { if (it == 1) tk else null }
        val lkKeys: (Int) -> ByteArray? = { if (it == 0) lk else null }
        val lkCipher = ByteArrayOutputStream()
        val a = DriveVaultLocalCrypt.transcode(tkKeys, vaultId, ByteArrayInputStream(tkCipher.toByteArray()), lk, 0, lkCipher)
        assertEquals(5000L, a.size)
        assertEquals(0, DriveVaultCrypto.contentVersion(lkCipher.toByteArray()))
        assertEquals(a, DriveVaultLocalCrypt.digest(lkKeys, vaultId, ByteArrayInputStream(lkCipher.toByteArray())))
        val again = ByteArrayOutputStream()
        val b = DriveVaultLocalCrypt.transcode(lkKeys, vaultId, ByteArrayInputStream(lkCipher.toByteArray()), tk, 2, again)
        assertEquals(a, b)
        assertEquals(2, DriveVaultCrypto.contentVersion(again.toByteArray()))
        val plainBack = ByteArrayOutputStream()
        DriveVaultCrypto.decryptContent({ if (it == 2) tk else null }, vaultId, ByteArrayInputStream(again.toByteArray()), plainBack)
        assertArrayEquals(plain, plainBack.toByteArray())
    }

    @Test
    fun `a changed ciphertext stops the transcode`() {
        val lk = DriveVaultCrypto.randomBytes(32)
        val plain = ByteArray(2000) { it.toByte() }
        val c = ByteArrayOutputStream().also { DriveVaultCrypto.encryptContent(tk, vaultId, 1, ByteArrayInputStream(plain), plain.size.toLong(), it, chunkSize = 1024) }.toByteArray()
        c[c.size - 20] = (c[c.size - 20].toInt() xor 1).toByte()
        code("decrypt") { DriveVaultLocalCrypt.transcode({ if (it == 1) tk else null }, vaultId, ByteArrayInputStream(c), lk, 0, ByteArrayOutputStream()) }
    }

    @Test
    fun `a name goes from the vault key to LK`() {
        val lk = DriveVaultCrypto.randomBytes(32)
        val c = DriveVaultCrypto.encryptName(tk, vaultId, 1, "Steuer 2025")
        val local = DriveVaultLocalCrypt.transcodeName({ if (it == 1) tk else null }, vaultId, c, lk, 0)
        assertEquals("Steuer 2025", DriveVaultCrypto.decryptName({ if (it == 0) lk else null }, vaultId, local))
    }
}
