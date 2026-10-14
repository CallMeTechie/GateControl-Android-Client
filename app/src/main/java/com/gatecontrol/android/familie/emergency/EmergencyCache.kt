package com.gatecontrol.android.familie.emergency

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.gatecontrol.android.familie.FamilieStorage
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Encrypts the emergency cache; pluggable so the file format is tested on the JVM. */
interface CacheCipher {
    /** Self-contained output (IV and tag included). */
    fun encrypt(plain: ByteArray): ByteArray

    /** Throws when the data was changed or the key is another one. */
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * AES-256-GCM, `iv(12) || ciphertext+tag`. The IV comes from the cipher
 * itself (the Android Keystore refuses caller-chosen IVs). [key] is asked
 * for on every use: the Keystore key is created on the first one.
 */
class AesGcmCipher(private val key: () -> SecretKey) : CacheCipher {

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        require(iv != null && iv.size == IV_BYTES) { "unexpected IV" }
        return iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES + TAG_BITS / 8) { "too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128

        /** A non-exportable AES-256 key of the Android Keystore under [alias] (created on first use). */
        fun keystoreKey(alias: String): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        }
    }
}

/**
 * The last `GET emergency` answer on the phone, encrypted at rest: one
 * file `MAGIC || encrypt({owner, savedAt, data})`. Bound to the login it
 * came from ([owner]); anything unreadable (other login, changed file, lost
 * key) counts as no cache and is removed. Pure JVM (no Android types).
 */
class EmergencyCache(private val file: File, private val cipher: CacheCipher) {

    data class Entry(val data: JsonObject, val savedAtMs: Long)

    @Synchronized
    fun write(owner: String, data: JsonObject, savedAtMs: Long): Boolean {
        val envelope = JsonObject().apply {
            addProperty("owner", owner)
            addProperty("savedAt", savedAtMs)
            add("data", data)
        }
        return try {
            val blob = MAGIC + cipher.encrypt(envelope.toString().toByteArray(Charsets.UTF_8))
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(blob)
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                    return false
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    fun read(owner: String): Entry? {
        if (!file.exists()) return null
        val entry = try {
            val bytes = file.readBytes()
            if (bytes.size <= MAGIC.size || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
                null
            } else {
                val plain = cipher.decrypt(bytes.copyOfRange(MAGIC.size, bytes.size))
                val o = JsonParser.parseString(String(plain, Charsets.UTF_8)).asJsonObject
                val savedAt = o.get("savedAt")?.takeIf { it.isJsonPrimitive }?.asLong
                val data = o.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                val stored = o.get("owner")?.takeIf { it.isJsonPrimitive }?.asString
                if (stored == owner && savedAt != null && data != null) Entry(data, savedAt) else null
            }
        } catch (e: Exception) {
            null
        }
        if (entry == null) clear()
        return entry
    }

    @Synchronized
    fun exists(): Boolean = file.exists()

    @Synchronized
    fun clear() {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }

    companion object {
        private val MAGIC = byteArrayOf('G'.code.toByte(), 'C'.code.toByte(), 'E'.code.toByte(), 1)
    }
}

/**
 * The app's [EmergencyCache]: in no-backup storage (`no_backup/familie/`,
 * never in a cloud or device-transfer backup), key in the Android Keystore.
 */
@Singleton
class EmergencyCacheStore @Inject constructor(
    @ApplicationContext context: Context,
    private val storage: FamilieStorage,
) {
    private val cache = EmergencyCache(
        File(File(context.noBackupFilesDir, "familie"), FILE),
        AesGcmCipher { AesGcmCipher.keystoreKey(KEY_ALIAS) },
    )

    fun read(): EmergencyCache.Entry? = cache.read(storage.ownerKey())

    fun write(data: JsonObject, savedAtMs: Long): Boolean = cache.write(storage.ownerKey(), data, savedAtMs)

    fun clear() {
        if (cache.exists()) cache.clear()
    }

    private companion object {
        const val FILE = "emergency.bin"
        const val KEY_ALIAS = "gatecontrol_familie_emergency"
    }
}
