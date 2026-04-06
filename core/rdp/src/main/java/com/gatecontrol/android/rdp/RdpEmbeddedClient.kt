package com.gatecontrol.android.rdp

import timber.log.Timber

/**
 * Embedded FreeRDP client for Android.
 *
 * This client wraps the FreeRDP Android library to provide an in-app RDP experience
 * without requiring an external RDP application. When the FreeRDP AAR is not available,
 * [isAvailable] returns false and the [RdpManager] should fall back to [RdpExternalClient].
 */
class RdpEmbeddedClient {

    companion object {
        private const val FREERDP_CLASS = "com.freerdp.freerdpcore.services.LibFreeRDP"
    }

    /**
     * Returns true if the FreeRDP library is available on the classpath.
     * When the AAR dependency is not included, this returns false and all
     * connect attempts should use [RdpExternalClient] instead.
     */
    fun isAvailable(): Boolean {
        return try {
            Class.forName(FREERDP_CLASS)
            true
        } catch (_: ClassNotFoundException) {
            Timber.d("FreeRDP library not available — embedded client disabled")
            false
        }
    }
}
