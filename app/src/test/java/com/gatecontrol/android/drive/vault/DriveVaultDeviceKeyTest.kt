package com.gatecontrol.android.drive.vault

import com.gatecontrol.android.familie.emergency.AesGcmCipher
import com.gatecontrol.android.familie.emergency.CacheCipher
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The device key lies on the disk only encrypted; an unreadable one is replaced (a new device for the vaults). Since
 * Drive 1.11 it waits for the person's confirmation, and a key of before moves there without a new approval.
 */
class DriveVaultDeviceKeyTest {

    @TempDir lateinit var dir: File

    private fun aes(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val plainKey = aes()
    private val authKey = aes()

    /** A Keystore key that asks for the person: usable only while [authed] (as within the confirmation's time). */
    private class AuthCipher(secret: SecretKey) : CacheCipher {
        var authed = false
        var broken = false
        private val inner = AesGcmCipher { secret }

        private fun check() {
            if (broken) throw DriveVaultDeviceKey.Unavailable(IllegalStateException("keystore"))
            if (!authed) throw DriveVaultDeviceKey.AuthRequired()
        }

        override fun encrypt(plain: ByteArray): ByteArray = check().let { inner.encrypt(plain) }

        override fun decrypt(blob: ByteArray): ByteArray = check().let { inner.decrypt(blob) }
    }

    private val auth = AuthCipher(authKey)
    private var dropped = 0
    private val file get() = File(dir, "device.key")

    private fun key(plain: SecretKey = plainKey, wantAuth: Boolean = true) =
        DriveVaultDeviceKey(file, AesGcmCipher { plain }, auth, { wantAuth }, { dropped++ })

    private fun contains(hay: ByteArray, needle: ByteArray): Boolean =
        (0..hay.size - needle.size).any { i -> needle.indices.all { hay[i + it] == needle[it] } }

    @Test
    fun `the key stays the same and is never stored in the clear`() {
        val first = key().keyPair()
        val again = key().keyPair()
        assertArrayEquals(first.publicKey, again.publicKey)
        assertArrayEquals(first.privateKey, again.privateKey)
        val stored = file.readBytes()
        assertFalse(contains(stored, first.privateKey))
        assertEquals(DriveVaultCrypto.deviceId(first.publicKey), key().deviceId())
        assertEquals(DriveVaultCrypto.safetyNumber(first.publicKey), key().safetyNumber())
    }

    @Test
    fun `a key the keystore cannot open is replaced`() {
        val first = key().keyPair()
        val next = key(plain = aes()).keyPair()
        assertNotEquals(DriveVaultCrypto.deviceId(first.publicKey), DriveVaultCrypto.deviceId(next.publicKey))
    }

    @Test
    fun `a key of before Drive 1_11 moves behind the confirmation, the same pair, no new approval`() {
        // v1: "GDVK" 1 ‖ the plain Keystore key's encryption of the private key
        val pair = DriveVaultCrypto.newKeyPair()
        file.writeBytes(byteArrayOf(0x47, 0x44, 0x56, 0x4b, 0x01) + AesGcmCipher { plainKey }.encrypt(pair.privateKey))
        val k = key()
        // the device id needs no confirmation (the public key is written next to it now)
        assertEquals(DriveVaultCrypto.deviceId(pair.publicKey), k.deviceId())
        assertEquals(2, file.readBytes()[4].toInt())
        assertEquals(false, k.protectedByAuth())
        // without the confirmation nothing moves
        assertThrows(DriveVaultDeviceKey.AuthRequired::class.java) { k.protect(true) }
        assertEquals(false, k.protectedByAuth())
        assertEquals(0, dropped)
        // confirmed: moved, the old Keystore key goes
        auth.authed = true
        k.protect(true)
        assertEquals(true, k.protectedByAuth())
        assertEquals(1, dropped)
        assertFalse(contains(file.readBytes(), pair.privateKey))
        // later: the private key only with the confirmation, the public one always
        auth.authed = false
        val later = key(plain = aes())
        assertThrows(DriveVaultDeviceKey.AuthRequired::class.java) { later.keyPair() }
        assertTrue(file.isFile)
        assertEquals(DriveVaultCrypto.deviceId(pair.publicKey), later.deviceId())
        auth.authed = true
        assertArrayEquals(pair.privateKey, later.keyPair().privateKey)
    }

    @Test
    fun `switching the confirmation off moves the key back, the same pair`() {
        auth.authed = true
        val first = key().keyPair()
        assertEquals(true, key().protectedByAuth())
        key().protect(false)
        auth.authed = false
        val k = key(wantAuth = false)
        assertEquals(false, k.protectedByAuth())
        assertArrayEquals(first.privateKey, k.keyPair().privateKey)
    }

    @Test
    fun `a new key waits for the confirmation, then moves`() {
        assertNull(key().protectedByAuth())
        val pair = key().keyPair()
        assertEquals(false, key().protectedByAuth())
        auth.authed = true
        key().protect(true)
        assertEquals(true, key().protectedByAuth())
        assertArrayEquals(pair.publicKey, key().keyPair().publicKey)
    }

    @Test
    fun `a keystore failure never throws the key away`() {
        auth.authed = true
        val pair = key().keyPair()
        auth.broken = true
        assertThrows(DriveVaultDeviceKey.Unavailable::class.java) { key().keyPair() }
        assertTrue(file.isFile)
        auth.broken = false
        assertArrayEquals(pair.privateKey, key().keyPair().privateKey)
    }
}
