package com.gatecontrol.android.drive.vault

import android.content.Context
import com.gatecontrol.android.familie.emergency.AesGcmCipher
import com.gatecontrol.android.familie.emergency.CacheCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This phone's X25519 key for Drive's vault folders. The private key lies on the disk only encrypted with a
 * non-exportable AES-256-GCM key of the Android Keystore (`MAGIC ‖ iv ‖ ciphertext`, in the no-backup files: it never
 * leaves the phone, a restored phone is a new device). A key the Keystore can no longer open (the Keystore was reset)
 * is replaced: the phone then asks the vaults again like a new device.
 */
@Singleton
class DriveVaultDeviceKey internal constructor(private val file: File, private val cipher: CacheCipher) {

    @Inject constructor(@ApplicationContext context: Context) : this(
        File(File(context.noBackupFilesDir, "drive-vault"), "device.key"),
        AesGcmCipher { AesGcmCipher.keystoreKey(KEYSTORE_ALIAS) },
    )

    @Volatile private var publicKey: ByteArray? = null

    /** The key pair (made on first use); the caller wipes the private key after use. */
    @Synchronized
    fun keyPair(): DriveVaultCrypto.KeyPair {
        read()?.let { return it }
        val pair = DriveVaultCrypto.newKeyPair()
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(MAGIC + cipher.encrypt(pair.privateKey))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "device key not stored" }
        }
        publicKey = pair.publicKey
        return pair
    }

    private fun read(): DriveVaultCrypto.KeyPair? {
        if (!file.isFile) return null
        return try {
            val blob = file.readBytes()
            require(blob.size > MAGIC.size && blob.copyOf(MAGIC.size).contentEquals(MAGIC)) { "unknown format" }
            val priv = cipher.decrypt(blob.copyOfRange(MAGIC.size, blob.size))
            require(priv.size == 32) { "bad key" }
            DriveVaultCrypto.keyFromSeed(priv).also { priv.fill(0); publicKey = it.publicKey }
        } catch (e: Exception) {
            Timber.w("Drive vault: the device key is unreadable (%s), a new one is made", e.javaClass.simpleName)
            file.delete()
            null
        }
    }

    /** The public key (32 raw bytes). */
    fun publicKey(): ByteArray = publicKey ?: keyPair().let { p -> p.publicKey.also { p.wipe() } }

    fun deviceId(): String = DriveVaultCrypto.deviceId(publicKey())

    fun safetyNumber(): String = DriveVaultCrypto.safetyNumber(publicKey())

    private companion object {
        const val KEYSTORE_ALIAS = "gatecontrol_drive_vault_device"
        val MAGIC = byteArrayOf(0x47, 0x44, 0x56, 0x4b, 0x01) // "GDVK" v1
    }
}
