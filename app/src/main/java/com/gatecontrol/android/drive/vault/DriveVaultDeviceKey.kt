package com.gatecontrol.android.drive.vault

import android.content.Context
import com.gatecontrol.android.drive.DriveSettings
import com.gatecontrol.android.familie.emergency.AesGcmCipher
import com.gatecontrol.android.familie.emergency.CacheCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This phone's X25519 key for Drive's vault folders. The private key lies on the disk only encrypted with a
 * non-exportable AES-256-GCM key of the Android Keystore, in the no-backup files: it never leaves the phone, a restored
 * phone is a new device.
 *
 * Since Drive 1.11 that Keystore key asks for the person ([auth]: fingerprint, face or the phone's PIN, valid for
 * [DriveVaultKeystore.AUTH_TIMEOUT_S] after the confirmation the vault shows): without it the private key cannot be
 * read ([AuthRequired]). The public key is stored next to it, so the device id and the safety number never need it.
 * Format v2: `MAGIC2 ‖ protection (1 = auth, 0 = plain) ‖ publicKey(32) ‖ iv ‖ ciphertext`; v1 (`MAGIC1 ‖ iv ‖
 * ciphertext`, the [plain] key) is read as it is and moved over with [protect] after the next confirmation — the same
 * key pair, so the device stays approved. A key the Keystore can no longer open (reset, the screen lock removed) is
 * replaced: the phone then asks the vaults again like a new device.
 */
@Singleton
class DriveVaultDeviceKey internal constructor(
    private val file: File,
    /** The Keystore key without confirmation (v1, and the setting "Mit Fingerabdruck öffnen" off). */
    private val plain: CacheCipher,
    /** The Keystore key that asks for the person. */
    private val auth: CacheCipher,
    /** A new key pair is stored with [auth] (the setting). */
    private val wantAuth: () -> Boolean = { true },
    /** The [plain] Keystore key is no longer needed (after the move to [auth]). */
    private val dropPlain: () -> Unit = {},
) {

    @Inject constructor(@ApplicationContext context: Context, settings: DriveSettings) : this(
        File(File(context.noBackupFilesDir, "drive-vault"), "device.key"),
        AesGcmCipher { AesGcmCipher.keystoreKey(KEYSTORE_ALIAS) },
        DriveVaultKeystore.authCipher(AUTH_ALIAS),
        { settings.current.vaultBiometric },
        { DriveVaultKeystore.delete(KEYSTORE_ALIAS) },
    )

    /** The private key needs the person's confirmation first (fingerprint, face or PIN). */
    class AuthRequired : Exception("device confirmation required")

    /** The phone has no screen lock: a key that asks for the person cannot be made. */
    class NoScreenLock : Exception("no screen lock")

    /** The Keystore failed for another reason (the key itself may be fine): nothing is replaced, the vault says so. */
    class Unavailable(cause: Throwable) : Exception("keystore unavailable", cause)

    private class Stored(val auth: Boolean, val publicKey: ByteArray, val blob: ByteArray)

    @Volatile private var publicKey: ByteArray? = null

    /** The key pair (made on first use); the caller wipes the private key after use. Throws [AuthRequired]. */
    @Synchronized
    fun keyPair(): DriveVaultCrypto.KeyPair {
        load()?.let { s ->
            val priv = try {
                (if (s.auth) auth else plain).decrypt(s.blob)
            } catch (e: AuthRequired) {
                throw e
            } catch (e: Unavailable) {
                throw e
            } catch (e: Exception) {
                unreadable(e)
                null
            }
            if (priv != null) {
                try {
                    if (priv.size == 32) {
                        val pair = DriveVaultCrypto.keyFromSeed(priv)
                        if (pair.publicKey.contentEquals(s.publicKey)) return pair.also { publicKey = it.publicKey }
                        pair.wipe()
                    }
                } finally {
                    priv.fill(0)
                }
                unreadable(IllegalStateException("bad key"))
            }
        }
        return create()
    }

    /** Whether the stored key asks for the person (null: there is none yet). */
    @Synchronized
    fun protectedByAuth(): Boolean? = load()?.auth

    /**
     * Stores the private key under the Keystore key the setting wants ([withAuth]): the move of a v1 key (Drive 1.11)
     * and the switch "Mit Fingerabdruck öffnen". Needs the person's confirmation just before when either side asks for
     * it ([AuthRequired], [NoScreenLock] otherwise; the key stays as it was). The key pair stays the same.
     */
    @Synchronized
    fun protect(withAuth: Boolean) {
        val s = load() ?: return
        if (s.auth == withAuth) return
        val from = if (s.auth) auth else plain
        val to = if (withAuth) auth else plain
        val priv = from.decrypt(s.blob)
        try {
            val blob = to.encrypt(priv)
            check(to.decrypt(blob).let { back -> back.contentEquals(priv).also { back.fill(0) } }) { "not readable back" }
            write(Stored(withAuth, s.publicKey, blob))
        } finally {
            priv.fill(0)
        }
        if (withAuth) runCatching { dropPlain() }
        Timber.i("Drive vault: device key now %s", if (withAuth) "behind the device confirmation" else "without confirmation")
    }

    /** The stored key, its public part readable without the person; a v1 file gets the public part now. */
    private fun load(): Stored? {
        if (!file.isFile) return null
        return try {
            val blob = file.readBytes()
            when {
                blob.size > MAGIC2.size + 1 + 32 && blob.copyOf(MAGIC2.size).contentEquals(MAGIC2) -> {
                    val flag = blob[MAGIC2.size].toInt()
                    require(flag == 0 || flag == 1) { "unknown protection" }
                    val pub = blob.copyOfRange(MAGIC2.size + 1, MAGIC2.size + 33)
                    Stored(flag == 1, pub, blob.copyOfRange(MAGIC2.size + 33, blob.size)).also { publicKey = pub }
                }
                blob.size > MAGIC1.size && blob.copyOf(MAGIC1.size).contentEquals(MAGIC1) -> {
                    // v1 (before Drive 1.11): the plain Keystore key; the public part is written next to it at once
                    val cipherBlob = blob.copyOfRange(MAGIC1.size, blob.size)
                    val priv = plain.decrypt(cipherBlob)
                    val pub = try {
                        require(priv.size == 32) { "bad key" }
                        DriveVaultCrypto.keyFromSeed(priv).let { p -> p.publicKey.also { p.wipe() } }
                    } finally {
                        priv.fill(0)
                    }
                    Stored(false, pub, cipherBlob).also { write(it); publicKey = pub }
                }
                else -> error("unknown format")
            }
        } catch (e: Exception) {
            unreadable(e)
            null
        }
    }

    private fun unreadable(e: Exception) {
        Timber.w("Drive vault: the device key is unreadable (%s), a new one is made", e.javaClass.simpleName)
        file.delete()
        publicKey = null
    }

    private fun create(): DriveVaultCrypto.KeyPair {
        val pair = DriveVaultCrypto.newKeyPair()
        // behind the confirmation when the setting wants it and the person just confirmed; else plain for now —
        // the next confirmation moves it ([protect])
        val stored = (if (wantAuth()) runCatching { Stored(true, pair.publicKey, auth.encrypt(pair.privateKey)) }.getOrNull() else null)
            ?: Stored(false, pair.publicKey, plain.encrypt(pair.privateKey))
        write(stored)
        publicKey = pair.publicKey
        return pair
    }

    private fun write(s: Stored) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(MAGIC2 + byteArrayOf(if (s.auth) 1 else 0) + s.publicKey + s.blob)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "device key not stored" }
        }
    }

    /** The public key (32 raw bytes): stored in the clear, never needs the person (only a new key pair is made). */
    fun publicKey(): ByteArray = publicKey ?: synchronized(this) { load()?.publicKey } ?: keyPair().let { p -> p.publicKey.also { p.wipe() } }

    fun deviceId(): String = DriveVaultCrypto.deviceId(publicKey())

    fun safetyNumber(): String = DriveVaultCrypto.safetyNumber(publicKey())

    private companion object {
        const val KEYSTORE_ALIAS = "gatecontrol_drive_vault_device"
        const val AUTH_ALIAS = "gatecontrol_drive_vault_device_auth"
        val MAGIC1 = byteArrayOf(0x47, 0x44, 0x56, 0x4b, 0x01) // "GDVK" v1
        val MAGIC2 = byteArrayOf(0x47, 0x44, 0x56, 0x4b, 0x02) // "GDVK" v2
    }
}
