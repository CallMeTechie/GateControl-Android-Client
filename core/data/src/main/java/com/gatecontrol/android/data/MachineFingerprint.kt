package com.gatecontrol.android.data

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device fingerprint for the server's machine binding (Gerätebindung).
 *
 * Sent as `X-Machine-Fingerprint` on client API requests and as `fingerprint`
 * when a setup code is redeemed. The server stores the first valid value per
 * token and rejects requests from any other device afterwards, so the value
 * must be stable and unique per device:
 *
 * - Normally `SHA-256(ANDROID_ID)` as 64 lowercase hex chars. Since Android 8
 *   ANDROID_ID is already scoped to the app's signing key, the user and the
 *   device; it survives reinstalls and changes on a factory reset. The
 *   derivation is the one the app has sent since the header was introduced,
 *   so tokens that are already bound keep working after an update.
 * - If ANDROID_ID is missing, empty or the known broken emulator/firmware
 *   value, a random 32-byte ID is generated once and kept in [EncryptedStorage]
 *   (it survives an app reset in Settings, not a reinstall).
 *
 * The value is cached in memory and never logged in full.
 */
@Singleton
class MachineFingerprint internal constructor(
    private val androidIdProvider: () -> String?,
    private val loadFallback: () -> String?,
    private val saveFallback: (String) -> Unit,
    private val randomBytes: () -> ByteArray,
) {

    @Inject
    constructor(@ApplicationContext context: Context, storage: EncryptedStorage) : this(
        androidIdProvider = { readAndroidId(context.applicationContext) },
        loadFallback = { storage.getString(FALLBACK_KEY, "").ifEmpty { null } },
        saveFallback = { storage.commitBatch(FALLBACK_KEY to it) },
        randomBytes = { ByteArray(32).also { SecureRandom().nextBytes(it) } },
    )

    @Volatile
    private var cached: String? = null

    /** The 64-char lowercase hex fingerprint. */
    fun get(): String {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: compute().also {
                cached = it
                Timber.i("Machine fingerprint ready (%s…)", shortForm(it))
            }
        }
    }

    /** First 8 hex chars — what the server shows as "Gebunden an Gerät ab12cd34…". */
    fun shortId(): String = shortForm(get())

    private fun compute(): String {
        val androidId = try {
            androidIdProvider()?.trim()
        } catch (e: Exception) {
            Timber.w("ANDROID_ID unavailable: %s", e.javaClass.simpleName)
            null
        }
        if (isUsableAndroidId(androidId)) return sha256Hex(androidId!!.toByteArray(Charsets.UTF_8))

        Timber.w("ANDROID_ID unusable, using the stored random device ID")
        val stored = loadFallback()?.takeIf { FALLBACK_RE.matches(it) }
        val seed = stored ?: toHex(randomBytes()).also { saveFallback(it) }
        return sha256Hex("$FALLBACK_SALT:$seed".toByteArray(Charsets.UTF_8))
    }

    companion object {
        /** Storage key of the random fallback ID; kept when the app setup is reset. */
        const val FALLBACK_KEY = "machine_fallback_id"

        /** Value some old devices/emulators return for every installation. */
        internal const val BROKEN_ANDROID_ID = "9774d56d682e549c"

        private const val FALLBACK_SALT = "gatecontrol-android-fallback-v1"

        /** Format the server accepts (`^[a-f0-9]{64}$`). */
        val FORMAT = Regex("^[a-f0-9]{64}$")

        private val FALLBACK_RE = Regex("^[a-f0-9]{64}$")

        fun shortForm(fingerprint: String): String = fingerprint.take(8)

        internal fun isUsableAndroidId(id: String?): Boolean =
            !id.isNullOrEmpty() && !id.equals(BROKEN_ANDROID_ID, ignoreCase = true) && id.any { it != '0' }

        internal fun sha256Hex(input: ByteArray): String =
            toHex(MessageDigest.getInstance("SHA-256").digest(input))

        private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        @SuppressLint("HardwareIds")
        private fun readAndroidId(context: Context): String? =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }
}
