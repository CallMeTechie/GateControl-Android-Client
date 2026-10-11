package com.gatecontrol.android.trustednet

import com.gatecontrol.android.network.HomeNetworkStatus

/**
 * What the trusted-network monitor does next. [PROBE] = ask the server
 * (through the connected tunnel) whether this is its home network, then
 * decide again with the answer.
 */
enum class TrustedNetAction { NONE, CONNECT, DISCONNECT, PROBE }

/** Tunnel as the rules see it. */
enum class TunnelPhase {
    /** Disconnected, failed or disconnecting. */
    DOWN,
    /** Connecting or reconnecting: wait for the result. */
    CONNECTING,
    CONNECTED,
}

/**
 * One home-network answer. [throughTunnel] = the tunnel was connected when
 * the request was made. Only such an answer may ever trust a network: the
 * server only says `same_lan: true` for requests through the tunnel, and a
 * direct request can look like a LAN source behind a reverse proxy or a
 * SNAT router.
 */
data class HomeProbe(val status: HomeNetworkStatus, val throughTunnel: Boolean) {
    /** In the home network: `same_lan` true, `via` tunnel, and asked through the tunnel. */
    val trusted: Boolean get() = throughTunnel && status is HomeNetworkStatus.Home && status.viaTunnel
}

/**
 * Result of [TrustedNetworkRules.decide]. [commitKey] = remember the current
 * network identity as handled, so nothing happens again until it changes.
 * False while the sequence for this network is still running (connect,
 * then probe).
 */
data class TrustedNetDecision(
    val action: TrustedNetAction,
    val commitKey: Boolean,
    val reason: Reason,
) {
    enum class Reason {
        NO_NETWORK,
        SAME_NETWORK,
        FEATURE_OFF,
        ALWAYS_ON_POLICY,
        TRUSTED_SSID,
        TRUSTED_HOME,
        UNTRUSTED,
        ALREADY_DISCONNECTED,
        ALREADY_CONNECTED,
        NO_VPN_CONSENT,
        NOT_CONFIGURED,
        /** Home check needs the tunnel: connect first. */
        CONNECT_FOR_HOME_CHECK,
        /** Tunnel on its way: decide when it is up (or not). */
        WAITING_FOR_TUNNEL,
        /** We already tried to bring the tunnel up on this network and it is down again. */
        TUNNEL_NOT_UP,
        HOME_CHECK,
        NOT_HOME,
    }
}

/**
 * Pure rules of "Vertrauenswürdige WLANs" (no Android types, unit tested).
 *
 * Safety rules:
 *  - act only when the network identity changes (never fight a manual
 *    connect / disconnect on the same network, nor after an app restart),
 *  - the home network only counts when the server confirms it for a
 *    request through the connected tunnel; a failed, direct or "not home"
 *    answer keeps the tunnel up,
 *  - never disconnect when the client policy says always-on,
 *  - connect only with VPN consent and a configured setup.
 */
object TrustedNetworkRules {

    /** Android's placeholder when the SSID is not available (WifiManager.UNKNOWN_SSID). */
    const val UNKNOWN_SSID = "<unknown ssid>"

    /** Longest SSID allowed by 802.11 (bytes). */
    const val MAX_SSID_BYTES = 32

    const val KEY_CELL = "cell"

    /**
     * SSID as the user knows it: surrounding quotes stripped (WifiInfo wraps
     * UTF-8 names in quotes), whitespace trimmed; [UNKNOWN_SSID] or empty →
     * null (none).
     */
    fun normalizeSsid(raw: String?): String? {
        var s = raw?.trim() ?: return null
        if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) s = s.substring(1, s.length - 1).trim()
        if (s.isEmpty() || s.equals(UNKNOWN_SSID, ignoreCase = true)) return null
        return s
    }

    /** A user-typed SSID, normalised, or null when it can't be a valid one. */
    fun validSsidInput(raw: String?): String? {
        val s = normalizeSsid(raw) ?: return null
        return s.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_SSID_BYTES }
    }

    /** Exact (case-sensitive) match of the normalised SSID against the list. */
    fun ssidMatches(ssid: String?, trusted: Set<String>): Boolean {
        val s = normalizeSsid(ssid) ?: return false
        return trusted.any { normalizeSsid(it) == s }
    }

    /**
     * Network identity: `wifi:<networkHandle>` for a WLAN, [KEY_CELL] for any
     * other (mobile data, Ethernet), null for no network at all. The handle
     * is new for every connection to a WLAN, so leaving and coming back is a
     * change while an app restart on the same connection is not. The SSID is
     * deliberately left out: granting the location permission later must not
     * look like a network change.
     */
    fun networkKey(wifiHandle: Long?, hasOtherNetwork: Boolean): String? = when {
        wifiHandle != null -> "wifi:$wifiHandle"
        hasOtherNetwork -> KEY_CELL
        else -> null
    }

    /**
     * @param previousKey identity handled last (persisted), null = never
     * @param currentKey identity now, null = no network
     * @param ssidTrusted the WLAN's name is on the user's list
     * @param homeMode home detection is on and the device is in a WLAN
     * @param homeProbe answer for this network, null = not asked yet
     * @param connectAttempted we already tried to connect on this network
     */
    fun decide(
        previousKey: String?,
        currentKey: String?,
        ssidTrusted: Boolean,
        homeMode: Boolean,
        tunnel: TunnelPhase,
        homeProbe: HomeProbe?,
        canDisconnect: Boolean,
        prepared: Boolean,
        configured: Boolean,
        enabled: Boolean = true,
        connectAttempted: Boolean = false,
    ): TrustedNetDecision {
        fun d(action: TrustedNetAction, reason: TrustedNetDecision.Reason, commit: Boolean = true) =
            TrustedNetDecision(action, commit, reason)

        if (currentKey == null) return d(TrustedNetAction.NONE, TrustedNetDecision.Reason.NO_NETWORK, commit = false)
        if (currentKey == previousKey) return d(TrustedNetAction.NONE, TrustedNetDecision.Reason.SAME_NETWORK)
        // Off or always-on: remember the network, so switching it on later
        // (or the policy lifting) does not act on the network we are on.
        if (!enabled) return d(TrustedNetAction.NONE, TrustedNetDecision.Reason.FEATURE_OFF)
        if (!canDisconnect) return d(TrustedNetAction.NONE, TrustedNetDecision.Reason.ALWAYS_ON_POLICY)

        if (ssidTrusted) {
            return if (tunnel != TunnelPhase.DOWN) d(TrustedNetAction.DISCONNECT, TrustedNetDecision.Reason.TRUSTED_SSID)
            else d(TrustedNetAction.NONE, TrustedNetDecision.Reason.ALREADY_DISCONNECTED)
        }

        if (!homeMode) {
            // Mobile data, or a WLAN that is not on the list.
            return when {
                tunnel != TunnelPhase.DOWN -> d(TrustedNetAction.NONE, TrustedNetDecision.Reason.ALREADY_CONNECTED)
                !configured -> d(TrustedNetAction.NONE, TrustedNetDecision.Reason.NOT_CONFIGURED)
                !prepared -> d(TrustedNetAction.NONE, TrustedNetDecision.Reason.NO_VPN_CONSENT)
                else -> d(TrustedNetAction.CONNECT, TrustedNetDecision.Reason.UNTRUSTED)
            }
        }

        // Home detection: untrusted until the server confirms it through the tunnel.
        return when (tunnel) {
            TunnelPhase.DOWN -> when {
                // Our connect did not bring it up (or the user stopped it): give up here.
                connectAttempted -> d(TrustedNetAction.NONE, TrustedNetDecision.Reason.TUNNEL_NOT_UP)
                !configured -> d(TrustedNetAction.NONE, TrustedNetDecision.Reason.NOT_CONFIGURED)
                !prepared -> d(TrustedNetAction.NONE, TrustedNetDecision.Reason.NO_VPN_CONSENT)
                else -> d(TrustedNetAction.CONNECT, TrustedNetDecision.Reason.CONNECT_FOR_HOME_CHECK, commit = false)
            }
            TunnelPhase.CONNECTING -> d(TrustedNetAction.NONE, TrustedNetDecision.Reason.WAITING_FOR_TUNNEL, commit = false)
            TunnelPhase.CONNECTED -> when {
                homeProbe == null || !homeProbe.throughTunnel ->
                    d(TrustedNetAction.PROBE, TrustedNetDecision.Reason.HOME_CHECK, commit = false)
                homeProbe.trusted -> d(TrustedNetAction.DISCONNECT, TrustedNetDecision.Reason.TRUSTED_HOME)
                // Not home, older server, error, timeout or "direct": stay connected.
                else -> d(TrustedNetAction.NONE, TrustedNetDecision.Reason.NOT_HOME)
            }
        }
    }
}
