package com.gatecontrol.android.media

/**
 * The app's own WireGuard tunnel as Medien needs it (the binding is the app's: the phone's TunnelConnector, the TV's
 * TvTunnel). Away, media go only through it (Netz-Regel); when it is off the app starts it itself on opening or
 * playing media.
 */
interface MediaVpn {
    /** The tunnel is up now. */
    fun up(): Boolean

    /** Android's VPN consent (`VpnService.prepare`) is missing: the screen must ask for it first. */
    fun consentNeeded(): Boolean

    /** Starts the tunnel with the stored config and waits until it is up (true) or failed (false). */
    suspend fun connect(): Boolean
}

/**
 * The way to a source now (Netz-Regel, contract „Medien-Plugin App-API v1“): at home straight through the LAN (bound to
 * the Wi-Fi / Ethernet, never the tunnel), away through the VPN, away to the HTTPS address the admin allowed, or not
 * at all (away, VPN off).
 */
enum class MediaRoute { HOME, VPN, DIRECT, BLOCKED }

/** The VPN for media away: not needed or up, being started („VPN wird verbunden …“), waiting for the consent, or off. */
enum class MediaGate { OPEN, CONNECTING, CONSENT, BLOCKED }

/** Pure rules of the way. Unit-tested. */
object MediaRouting {
    /**
     * [source] at home ([atHome]) or away with the tunnel [vpnUp] or not. A source the admin allowed away without the
     * VPN goes to its HTTPS address while the tunnel is down (through the tunnel when it is up anyway).
     */
    fun route(source: MediaSource, atHome: Boolean?, vpnUp: Boolean): MediaRoute = when {
        atHome == true -> MediaRoute.HOME
        vpnUp -> MediaRoute.VPN
        source.awayDirect -> MediaRoute.DIRECT
        else -> MediaRoute.BLOCKED
    }

    /**
     * The source as it is asked on [route]: away direct its HTTPS address instead of the home one; away through the VPN
     * the HTTPS address of its route (the tunnel reaches GateControl's routes, not the home network), the home address
     * only while the plugin gives none (older plugins). At home always the home address. SMB has no away address.
     */
    fun effective(source: MediaSource, route: MediaRoute): MediaSource {
        if (source.kind != MediaSourceKind.JELLYFIN && source.kind != MediaSourceKind.WEBDAV) return source
        val away = when (route) {
            MediaRoute.DIRECT -> source.awayAddress
            MediaRoute.VPN -> source.awayVpnAddress
            else -> null
        }
        return if (away != null) source.copy(address = away) else source
    }

    /** [source] on [route] goes to another address than its home one (its away address). */
    fun awayAddressUsed(source: MediaSource, route: MediaRoute): Boolean = effective(source, route).address != source.address

    /**
     * What „not reachable" of [source] on [route] means: away without the VPN [MediaProblem.VPN]; away through the VPN
     * without a route address (an older plugin) [MediaProblem.NO_ROUTE]; else [MediaProblem.OFFLINE].
     */
    fun offline(source: MediaSource, route: MediaRoute?): MediaProblem = when {
        route == MediaRoute.BLOCKED -> MediaProblem.VPN
        route == MediaRoute.VPN && (source.kind == MediaSourceKind.JELLYFIN || source.kind == MediaSourceKind.WEBDAV) && source.awayVpnAddress == null -> MediaProblem.NO_ROUTE
        else -> MediaProblem.OFFLINE
    }

    /** Away, the VPN is needed for at least one of [sources] (none allowed direct). */
    fun needsVpn(sources: List<MediaSource>): Boolean = sources.any { !it.awayDirect }
}
