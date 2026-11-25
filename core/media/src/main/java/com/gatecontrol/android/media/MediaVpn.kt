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

    /** The source as it is asked on [route]: away direct its HTTPS address instead of the home one. */
    fun effective(source: MediaSource, route: MediaRoute): MediaSource =
        if (route == MediaRoute.DIRECT && source.awayAddress != null && (source.kind == MediaSourceKind.JELLYFIN || source.kind == MediaSourceKind.WEBDAV)) {
            source.copy(address = source.awayAddress)
        } else {
            source
        }

    /** Away, the VPN is needed for at least one of [sources] (none allowed direct). */
    fun needsVpn(sources: List<MediaSource>): Boolean = sources.any { !it.awayDirect }
}
