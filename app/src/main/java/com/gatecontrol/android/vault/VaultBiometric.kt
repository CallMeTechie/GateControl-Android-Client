package com.gatecontrol.android.vault

import android.content.Context
import android.content.ContextWrapper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unlock with the fingerprint: the user key, encrypted by an Android Keystore key that only a strong biometric
 * releases (AES-GCM, `setUserAuthenticationRequired`, invalidated by a newly enrolled finger). The master password is
 * never stored. A new finger or a removed screen lock → the key is gone and the master password is needed again.
 */
@Singleton
class VaultBiometric @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("vault_biometric", Context.MODE_PRIVATE)

    sealed class Outcome {
        class Ok(val userKey: ByteArray) : Outcome()
        data object Cancelled : Outcome()
        /** The stored key is unusable (new finger, lock removed): turned off, use the password. */
        data object Invalidated : Outcome()
        data object Failed : Outcome()
    }

    fun available(): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** Fingerprint unlock is set up for this login ([owner] = VaultStore.owner()). */
    fun enabled(owner: String): Boolean = available() && prefs.getString(KEY_OWNER, null) == owner && prefs.contains(KEY_DATA)

    fun disable() {
        prefs.edit { clear() }
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun newKey(): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
                .build(),
        )
        return gen.generateKey()
    }

    private fun prompt(activity: FragmentActivity, title: String, negative: String, cipher: Cipher, done: (Cipher?, Boolean) -> Unit) {
        var finished = false
        fun finish(c: Cipher?, cancelled: Boolean) {
            if (!finished) {
                finished = true
                done(c, cancelled)
            }
        }
        val p = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                    finish(result.cryptoObject?.cipher, false)

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = finish(
                    null,
                    errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED,
                )

                override fun onAuthenticationFailed() = Unit
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText(negative)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .build()
        p.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    /** Turns fingerprint unlock on: one prompt, then the user key is stored encrypted. */
    fun enable(activityContext: Context, owner: String, userKey: ByteArray, title: String, negative: String, done: (Boolean) -> Unit) {
        val activity = activityContext.findFragmentActivity() ?: return done(false)
        val cipher = try {
            runCatching { keyStore().deleteEntry(ALIAS) }
            Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, newKey()) }
        } catch (e: Exception) {
            Timber.w("Vault: biometric key (%s)", e.javaClass.simpleName)
            return done(false)
        }
        val copy = userKey.copyOf()
        prompt(activity, title, negative, cipher) { c, _ ->
            if (c == null) {
                copy.fill(0)
                return@prompt done(false)
            }
            val ok = runCatching {
                val ct = c.doFinal(copy)
                prefs.edit {
                    putString(KEY_OWNER, owner)
                    putString(KEY_IV, Base64.getEncoder().encodeToString(c.iv))
                    putString(KEY_DATA, Base64.getEncoder().encodeToString(ct))
                }
            }.isSuccess
            copy.fill(0)
            done(ok)
        }
    }

    /** Asks for the finger and returns the user key. */
    fun unlock(activityContext: Context, owner: String, title: String, negative: String, done: (Outcome) -> Unit) {
        val activity = activityContext.findFragmentActivity() ?: return done(Outcome.Failed)
        if (!enabled(owner)) return done(Outcome.Invalidated)
        val cipher = try {
            val key = keyStore().getKey(ALIAS, null) as? SecretKey ?: throw KeyPermanentlyInvalidatedException()
            val iv = Base64.getDecoder().decode(prefs.getString(KEY_IV, "") ?: "")
            Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            disable()
            return done(Outcome.Invalidated)
        } catch (e: Exception) {
            Timber.w("Vault: biometric unlock (%s)", e.javaClass.simpleName)
            disable()
            return done(Outcome.Invalidated)
        }
        prompt(activity, title, negative, cipher) { c, cancelled ->
            if (c == null) return@prompt done(if (cancelled) Outcome.Cancelled else Outcome.Failed)
            val key = runCatching { c.doFinal(Base64.getDecoder().decode(prefs.getString(KEY_DATA, "") ?: "")) }.getOrNull()
            done(if (key != null && key.size == VaultCrypto.KEY_BYTES) Outcome.Ok(key) else Outcome.Failed)
        }
    }

    companion object {
        private const val ALIAS = "gc_vault_biometric"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val KEY_OWNER = "owner"
        private const val KEY_IV = "iv"
        private const val KEY_DATA = "data"

        internal fun Context.findFragmentActivity(): FragmentActivity? {
            var c: Context? = this
            while (c is ContextWrapper) {
                if (c is FragmentActivity) return c
                c = c.baseContext
            }
            return null
        }
    }
}
