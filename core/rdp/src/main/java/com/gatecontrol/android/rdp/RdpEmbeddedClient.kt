package com.gatecontrol.android.rdp

import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Embedded FreeRDP client for Android.
 *
 * This client wraps the FreeRDP Android library to provide an in-app RDP experience
 * without requiring an external RDP application. When the FreeRDP AAR is not available,
 * [isAvailable] returns false and the [RdpManager] should fall back to [RdpExternalClient].
 *
 * [buildSessionIntent] and [launchSession] construct an explicit Intent targeting
 * [RdpSessionActivity] by class-name string to avoid a compile-time dependency from
 * core:rdp to the app module.
 */
class RdpEmbeddedClient {

    companion object {
        private const val FREERDP_CLASS = "com.freerdp.freerdpcore.services.LibFreeRDP"
        private const val RDP_SESSION_ACTIVITY = "com.gatecontrol.android.rdp.RdpSessionActivity"

        // Intent extra keys read by RdpSessionActivity
        const val EXTRA_HOST = "rdp_host"
        const val EXTRA_PORT = "rdp_port"
        const val EXTRA_USERNAME = "rdp_username"
        const val EXTRA_PASSWORD = "rdp_password"
        const val EXTRA_DOMAIN = "rdp_domain"
        const val EXTRA_RESOLUTION_WIDTH = "rdp_resolution_width"
        const val EXTRA_RESOLUTION_HEIGHT = "rdp_resolution_height"
        const val EXTRA_COLOR_DEPTH = "rdp_color_depth"
        const val EXTRA_REDIRECT_CLIPBOARD = "rdp_redirect_clipboard"
        const val EXTRA_REDIRECT_PRINTERS = "rdp_redirect_printers"
        const val EXTRA_REDIRECT_DRIVES = "rdp_redirect_drives"
        const val EXTRA_AUDIO_MODE = "rdp_audio_mode"
        const val EXTRA_ADMIN_SESSION = "rdp_admin_session"
        const val EXTRA_ROUTE_NAME = "rdp_route_name"
    }

    /**
     * Returns `true` when the FreeRDP AAR is on the classpath. Since Phase 2
     * makes the AAR a required build dependency (see core/rdp/build.gradle.kts
     * and app/build.gradle.kts), this effectively returns `true` in all builds
     * — the check survives only as a defensive guard against broken AAR drops
     * or misconfigured classpaths.
     */
    fun isAvailable(): Boolean {
        return try {
            // Use the 3-arg form with initialize=false so loading the class
            // does not trigger LibFreeRDP's static initializer (which calls
            // System.loadLibrary and would fail on a JVM unit-test classpath
            // without the native .so files).
            Class.forName(FREERDP_CLASS, false, this::class.java.classLoader)
            true
        } catch (_: ClassNotFoundException) {
            Timber.e("FreeRDP AAR missing from classpath — check core/rdp/libs/")
            false
        }
    }

    /**
     * Collects all [RdpConnectionParams] fields as a flat key/value map of Intent extras.
     *
     * Null credential fields ([RdpConnectionParams.username], [RdpConnectionParams.password],
     * [RdpConnectionParams.domain]) are omitted from the map rather than stored as null
     * strings, so [Intent.hasExtra] can be used to distinguish "not provided" from "empty".
     *
     * This is an internal pure function exposed for unit testing without Android framework
     * dependencies.
     */
    internal fun collectExtras(params: RdpConnectionParams): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            EXTRA_HOST to params.host,
            EXTRA_PORT to params.port,
            EXTRA_RESOLUTION_WIDTH to params.resolutionWidth,
            EXTRA_RESOLUTION_HEIGHT to params.resolutionHeight,
            EXTRA_COLOR_DEPTH to params.colorDepth,
            EXTRA_REDIRECT_CLIPBOARD to params.redirectClipboard,
            EXTRA_REDIRECT_PRINTERS to params.redirectPrinters,
            EXTRA_REDIRECT_DRIVES to params.redirectDrives,
            EXTRA_AUDIO_MODE to params.audioMode,
            EXTRA_ADMIN_SESSION to params.adminSession,
            EXTRA_ROUTE_NAME to params.routeName,
        )
        params.username?.let { map[EXTRA_USERNAME] = it }
        params.password?.let { map[EXTRA_PASSWORD] = it }
        params.domain?.let { map[EXTRA_DOMAIN] = it }
        return map
    }

    /**
     * Builds an explicit [Intent] targeting [RdpSessionActivity] populated with all
     * fields from [params] as Intent extras.
     *
     * [FLAG_ACTIVITY_NEW_TASK] is set so the Intent can be started from non-Activity
     * contexts (e.g. a service or the application context).
     */
    fun buildSessionIntent(context: Context, params: RdpConnectionParams): Intent {
        val extras = collectExtras(params)
        return Intent().apply {
            setClassName(context, RDP_SESSION_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            extras.forEach { (key, value) ->
                when (value) {
                    is String -> putExtra(key, value)
                    is Int -> putExtra(key, value)
                    is Boolean -> putExtra(key, value)
                    else -> putExtra(key, value.toString())
                }
            }
        }
    }

    /**
     * Starts [RdpSessionActivity] with the given [params].
     *
     * Delegates to [buildSessionIntent] then calls [Context.startActivity].
     */
    fun launchSession(context: Context, params: RdpConnectionParams) {
        context.startActivity(buildSessionIntent(context, params))
    }
}
