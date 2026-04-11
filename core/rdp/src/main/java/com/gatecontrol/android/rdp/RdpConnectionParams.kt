package com.gatecontrol.android.rdp

import com.gatecontrol.android.network.RdpRoute

/**
 * Typed parameter bundle for an FreeRDP connection.
 *
 * Created via [fromRoute] to map an [RdpRoute] API model together with
 * user-supplied credentials into a single immutable value that can be passed
 * to [RdpSessionActivity] as Intent extras.
 */
data class RdpConnectionParams(
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
    val domain: String?,
    val resolutionWidth: Int,
    val resolutionHeight: Int,
    val colorDepth: Int,
    val redirectClipboard: Boolean,
    val redirectPrinters: Boolean,
    val redirectDrives: Boolean,
    val audioMode: String,
    val adminSession: Boolean,
    val routeName: String
) {

    companion object {

        /**
         * Factory method that maps an [RdpRoute] together with explicit credential
         * parameters into an [RdpConnectionParams].
         *
         * Null-safety defaults:
         * - [colorDepth] defaults to 32 when [RdpRoute.colorDepth] is null
         * - [audioMode] defaults to "local" when [RdpRoute.audioMode] is null
         * - Boolean redirect flags default to false
         * - Resolution dimensions default to 0
         * - [adminSession] defaults to false
         *
         * Domain resolution priority: explicit [domain] parameter wins;
         * falls back to [RdpRoute.domain] if the explicit parameter is null.
         */
        fun fromRoute(
            route: RdpRoute,
            username: String?,
            password: String?,
            domain: String?
        ): RdpConnectionParams = RdpConnectionParams(
            host = route.host,
            port = route.port,
            username = username,
            password = password,
            domain = domain ?: route.domain,
            // Use route-configured resolution, or 0 to signal "use device screen size".
            // The BookmarkBuilder coerces 0 to at least 800x600, but callers can
            // override with the device's actual display dimensions before launch.
            resolutionWidth = route.resolutionWidth ?: 0,
            resolutionHeight = route.resolutionHeight ?: 0,
            colorDepth = route.colorDepth ?: 32,
            redirectClipboard = route.redirectClipboard ?: false,
            redirectPrinters = route.redirectPrinters ?: false,
            redirectDrives = route.redirectDrives ?: false,
            audioMode = route.audioMode ?: "local",
            adminSession = route.adminSession ?: false,
            routeName = route.name
        )
    }
}
