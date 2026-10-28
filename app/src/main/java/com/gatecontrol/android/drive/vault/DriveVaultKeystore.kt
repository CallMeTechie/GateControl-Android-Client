package com.gatecontrol.android.drive.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import com.gatecontrol.android.familie.emergency.AesGcmCipher
import com.gatecontrol.android.familie.emergency.CacheCipher
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The Keystore key behind the vault's device key (Drive 1.11): AES-256-GCM that works only for
 * [AUTH_TIMEOUT_S] seconds after the person confirmed with a strong biometric or the phone's PIN / pattern / password
 * (the vault's [com.gatecontrol.android.ui.components.GcDeviceAuth] prompt). It needs a screen lock; removing that, the
 * system wipes the key (the phone then asks the vaults again like a new device).
 */
internal object DriveVaultKeystore {
    /** Long enough for `GET keys` and unwrapping after the prompt, short enough to need it for each opening. */
    const val AUTH_TIMEOUT_S = 60

    private const val PROVIDER = "AndroidKeyStore"

    fun authCipher(alias: String): CacheCipher = object : CacheCipher {
        private val inner = AesGcmCipher { authKey(alias) }

        override fun encrypt(plain: ByteArray): ByteArray = guard(alias) { inner.encrypt(plain) }

        override fun decrypt(blob: ByteArray): ByteArray = guard(alias) { inner.decrypt(blob) }
    }

    private inline fun <T> guard(alias: String, block: () -> T): T = try {
        block()
    } catch (e: UserNotAuthenticatedException) {
        throw DriveVaultDeviceKey.AuthRequired()
    } catch (e: KeyPermanentlyInvalidatedException) {
        // the screen lock was removed (or reset): the key is gone for good, a new one can be made
        delete(alias)
        throw e
    } catch (e: AEADBadTagException) {
        throw e
    } catch (e: DriveVaultDeviceKey.NoScreenLock) {
        throw e
    } catch (e: Exception) {
        Timber.w("Drive vault: keystore failed (%s)", e.javaClass.simpleName)
        throw DriveVaultDeviceKey.Unavailable(e)
    }

    private fun authKey(alias: String): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(AUTH_TIMEOUT_S, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            // a new fingerprint does not lock the person out (the PIN opens it as well)
            .setInvalidatedByBiometricEnrollment(false)
            .build()
        return try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
        } catch (e: java.security.InvalidAlgorithmParameterException) {
            // "Secure lock screen must be enabled"
            throw DriveVaultDeviceKey.NoScreenLock()
        } catch (e: IllegalStateException) {
            throw DriveVaultDeviceKey.NoScreenLock()
        }
    }

    fun delete(alias: String) {
        runCatching { KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(alias) }
    }
}
