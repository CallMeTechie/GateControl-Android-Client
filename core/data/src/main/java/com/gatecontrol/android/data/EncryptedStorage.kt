package com.gatecontrol.android.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import timber.log.Timber
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Encrypts/decrypts single values. The key never leaves the implementation. */
interface SecretCipher {
    fun encrypt(plain: String): String
    fun decrypt(encoded: String): String
}

/** Minimal key/value persistence so the storage logic is testable without Android. */
interface SecretBackend {
    fun get(key: String): String?
    fun put(entries: Map<String, String>, sync: Boolean)
    fun remove(key: String)
    fun clear()
}

/**
 * Secret storage (API token, WireGuard config incl. private key, peer data).
 *
 * Values are encrypted with an AES-256-GCM key held in the Android Keystore
 * (non-exportable) and written to a regular SharedPreferences file. There is
 * deliberately **no plaintext fallback**: if the Keystore cannot be used, the
 * secrets are kept in memory only for this process and the user has to set
 * the app up again after a restart — they never reach disk unencrypted.
 *
 * On first use, data from the previous implementations is migrated and the
 * old files are deleted: the `security-crypto` EncryptedSharedPreferences
 * file and the plaintext fallback file older versions wrote when the
 * Keystore failed.
 */
@Singleton
class EncryptedStorage internal constructor(
    private val backendFactory: () -> Pair<SecretBackend, SecretCipher>?,
    private val legacyMigration: (EncryptedStorage) -> Unit,
) {

    @Inject
    constructor(context: Context) : this(
        backendFactory = { createKeystoreBackend(context.applicationContext) },
        legacyMigration = { storage -> migrateLegacy(context.applicationContext, storage) },
    )

    private val memory = ConcurrentHashMap<String, String>()

    private val store: Pair<SecretBackend, SecretCipher>? by lazy {
        val created = try {
            backendFactory()
        } catch (e: Exception) {
            Timber.e(e, "EncryptedStorage: Android Keystore unavailable")
            null
        }
        if (created == null) {
            Timber.e("EncryptedStorage: keeping stored values in memory only (not persisted)")
        }
        created
    }

    /** False when the Keystore failed and secrets only live in memory. */
    val isPersistent: Boolean get() = store != null

    init {
        try {
            legacyMigration(this)
        } catch (e: Exception) {
            Timber.w(e, "EncryptedStorage: legacy migration failed")
        }
    }

    fun putString(key: String, value: String) = write(mapOf(key to "s:$value"), sync = false)

    fun putInt(key: String, value: Int) = write(mapOf(key to "i:$value"), sync = false)

    /**
     * Write multiple key-value pairs in a single synchronous commit.
     * Use this when subsequent code reads from prefs immediately after writing.
     */
    fun commitBatch(vararg entries: Pair<String, Any>) {
        val encoded = entries.associate { (key, value) ->
            key to when (value) {
                is String -> "s:$value"
                is Int -> "i:$value"
                is Boolean -> "b:$value"
                else -> throw IllegalArgumentException("Unsupported type for key $key")
            }
        }
        write(encoded, sync = true)
    }

    fun getString(key: String, default: String): String {
        val raw = read(key) ?: return default
        return if (raw.startsWith("s:")) raw.substring(2) else default
    }

    fun getInt(key: String, default: Int): Int {
        val raw = read(key) ?: return default
        return if (raw.startsWith("i:")) raw.substring(2).toIntOrNull() ?: default else default
    }

    fun remove(key: String) {
        memory.remove(key)
        store?.first?.remove(key)
    }

    /** Remove every value except the [keep] keys (e.g. the device ID). */
    fun clear(keep: Set<String> = emptySet()) {
        val kept = keep.mapNotNull { key -> read(key)?.let { key to it } }.toMap()
        memory.clear()
        store?.first?.clear()
        if (kept.isNotEmpty()) write(kept, sync = true)
    }

    private fun write(entries: Map<String, String>, sync: Boolean) {
        val s = store
        if (s == null) {
            memory.putAll(entries)
            return
        }
        val (backend, cipher) = s
        backend.put(entries.mapValues { cipher.encrypt(it.value) }, sync)
    }

    private fun read(key: String): String? {
        val s = store ?: return memory[key]
        val (backend, cipher) = s
        val encoded = backend.get(key) ?: return null
        return try {
            cipher.decrypt(encoded)
        } catch (e: Exception) {
            // Key lost (e.g. device restore without Keystore) — the value is unusable.
            Timber.w(e, "EncryptedStorage: cannot decrypt '%s', dropping it", key)
            backend.remove(key)
            null
        }
    }

    /** Import a legacy value without its type prefix (String/Int/Boolean). */
    internal fun importLegacy(key: String, value: Any?) {
        when (value) {
            is String -> putString(key, value)
            is Int -> putInt(key, value)
            is Boolean -> commitBatch(key to value)
            is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) putInt(key, value.toInt())
            else -> Unit
        }
    }

    companion object {
        private const val PREFS_FILE = "gatecontrol_secure_v2"
        private const val KEY_ALIAS = "gatecontrol_storage_v2"
        private const val LEGACY_ENCRYPTED_FILE = "gatecontrol_secure"
        private const val LEGACY_PLAINTEXT_FILE = "gatecontrol_secure_fallback"

        private fun createKeystoreBackend(context: Context): Pair<SecretBackend, SecretCipher> {
            val cipher = KeystoreCipher(KEY_ALIAS)
            // Round-trip once so a broken Keystore is detected here, not on first write.
            check(cipher.decrypt(cipher.encrypt("probe")) == "probe") { "Keystore round-trip failed" }
            val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            return SharedPrefsBackend(prefs) to cipher
        }

        private fun migrateLegacy(context: Context, storage: EncryptedStorage) {
            // 1) Plaintext fallback of older versions: import, then always delete —
            //    a secret must not stay on disk unencrypted.
            val plain = context.getSharedPreferences(LEGACY_PLAINTEXT_FILE, Context.MODE_PRIVATE)
            if (plain.all.isNotEmpty()) {
                plain.all.forEach { (k, v) -> storage.importLegacy(k, v) }
                Timber.w("EncryptedStorage: migrated %d values from plaintext fallback", plain.all.size)
            }
            context.deleteSharedPreferences(LEGACY_PLAINTEXT_FILE)

            // 2) security-crypto EncryptedSharedPreferences (only readable if its key still works).
            if (!storage.isPersistent) return
            val legacyFile = java.io.File(context.applicationInfo.dataDir, "shared_prefs/$LEGACY_ENCRYPTED_FILE.xml")
            if (!legacyFile.exists()) return
            try {
                @Suppress("DEPRECATION")
                val legacy = EncryptedSharedPreferences.create(
                    LEGACY_ENCRYPTED_FILE,
                    MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
                val values = legacy.all
                values.forEach { (k, v) -> storage.importLegacy(k, v) }
                Timber.i("EncryptedStorage: migrated %d values from security-crypto storage", values.size)
            } catch (e: Exception) {
                Timber.w(e, "EncryptedStorage: legacy encrypted storage unreadable, discarding it")
            }
            context.deleteSharedPreferences(LEGACY_ENCRYPTED_FILE)
        }
    }
}

private class SharedPrefsBackend(private val prefs: SharedPreferences) : SecretBackend {
    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(entries: Map<String, String>, sync: Boolean) {
        val editor = prefs.edit()
        entries.forEach { (k, v) -> editor.putString(k, v) }
        if (sync) editor.commit() else editor.apply()
    }

    override fun remove(key: String) = prefs.edit().remove(key).apply()

    override fun clear() = prefs.edit().clear().apply()
}

/** AES-256-GCM with a non-exportable Android Keystore key; output is Base64(iv || ciphertext). */
private class KeystoreCipher(private val alias: String) : SecretCipher {

    private fun key(): SecretKey {
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

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
    }

    override fun decrypt(encoded: String): String {
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        require(data.size > GCM_IV_BYTES) { "ciphertext too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, GCM_IV_BYTES))
        return String(cipher.doFinal(data, GCM_IV_BYTES, data.size - GCM_IV_BYTES), Charsets.UTF_8)
    }

    private companion object {
        const val GCM_IV_BYTES = 12
    }
}
