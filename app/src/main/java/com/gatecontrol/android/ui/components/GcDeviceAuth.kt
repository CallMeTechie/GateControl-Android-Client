package com.gatecontrol.android.ui.components

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Outcome of asking for the device unlock. */
enum class DeviceAuthResult {
    SUCCESS,

    /** The person cancelled (back, outside tap): nothing happens, no message needed. */
    CANCELLED,

    /** Not confirmed (too many attempts, error). */
    FAILED,

    /** The device has no screen lock: sensitive actions are refused. */
    UNAVAILABLE,
}

/**
 * Asks for the device unlock — a strong biometric or, as fallback, the
 * screen lock PIN / pattern / password (androidx.biometric BiometricPrompt
 * with DEVICE_CREDENTIAL). Used before a door is unlocked or opened.
 */
class GcDeviceAuth(private val context: Context) {

    fun available(): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /** Shows the system prompt with [title] / [subtitle]; [onResult] is called once. */
    fun authenticate(title: String, subtitle: String?, onResult: (DeviceAuthResult) -> Unit) {
        val activity = context.findFragmentActivity()
        if (activity == null || !available()) {
            onResult(DeviceAuthResult.UNAVAILABLE)
            return
        }
        var done = false
        fun finish(r: DeviceAuthResult) {
            if (!done) {
                done = true
                onResult(r)
            }
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = finish(DeviceAuthResult.SUCCESS)

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = finish(
                    when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED ->
                            DeviceAuthResult.CANCELLED
                        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL, BiometricPrompt.ERROR_NO_BIOMETRICS, BiometricPrompt.ERROR_HW_NOT_PRESENT ->
                            DeviceAuthResult.UNAVAILABLE
                        else -> DeviceAuthResult.FAILED
                    },
                )

                // A single wrong finger is not the end: the prompt stays open.
                override fun onAuthenticationFailed() = Unit
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { if (!subtitle.isNullOrBlank()) setSubtitle(subtitle) }
            .setAllowedAuthenticators(AUTHENTICATORS)
            // An explicit decision each time (no passive face unlock).
            .setConfirmationRequired(true)
            .build()
        prompt.authenticate(info)
    }

    companion object {
        /** Strong biometrics, else the screen lock credential (Android 11+). */
        const val AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

        private fun Context.findFragmentActivity(): FragmentActivity? {
            var c: Context? = this
            while (c is ContextWrapper) {
                if (c is FragmentActivity) return c
                c = c.baseContext
            }
            return null
        }
    }
}

@Composable
fun rememberGcDeviceAuth(): GcDeviceAuth {
    val context = LocalContext.current
    return remember(context) { GcDeviceAuth(context) }
}
