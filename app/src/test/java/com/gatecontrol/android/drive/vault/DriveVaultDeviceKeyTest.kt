package com.gatecontrol.android.drive.vault

import com.gatecontrol.android.familie.emergency.AesGcmCipher
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.crypto.KeyGenerator

/** The device key lies on the disk only encrypted; an unreadable one is replaced (a new device for the vaults). */
class DriveVaultDeviceKeyTest {

    @TempDir lateinit var dir: File

    private val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun key(file: File = File(dir, "device.key"), secret: javax.crypto.SecretKey = aes) = DriveVaultDeviceKey(file, AesGcmCipher { secret })

    private fun contains(hay: ByteArray, needle: ByteArray): Boolean =
        (0..hay.size - needle.size).any { i -> needle.indices.all { hay[i + it] == needle[it] } }

    @Test
    fun `the key stays the same and is never stored in the clear`() {
        val first = key().keyPair()
        val again = key().keyPair()
        assertArrayEquals(first.publicKey, again.publicKey)
        assertArrayEquals(first.privateKey, again.privateKey)
        val stored = File(dir, "device.key").readBytes()
        assertFalse(contains(stored, first.privateKey))
        assertEquals(DriveVaultCrypto.deviceId(first.publicKey), key().deviceId())
        assertEquals(DriveVaultCrypto.safetyNumber(first.publicKey), key().safetyNumber())
    }

    @Test
    fun `a key the keystore cannot open is replaced`() {
        val first = key().keyPair()
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val next = key(secret = other).keyPair()
        assertNotEquals(DriveVaultCrypto.deviceId(first.publicKey), DriveVaultCrypto.deviceId(next.publicKey))
    }
}
