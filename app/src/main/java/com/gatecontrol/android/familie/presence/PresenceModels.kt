package com.gatecontrol.android.familie.presence

import com.gatecontrol.android.trustednet.HomeCheck
import com.gatecontrol.android.trustednet.TrustedNetSnapshot

/*
 * Familie 1.2 Anwesenheit (`presence`): each person decides whether the app
 * reports "zuhause" / "unterwegs" to the family's own server. The phone
 * knows it from the existing home-network check of "Vertrauenswürdige
 * WLANs" (the server confirms that the phone is in its LAN) — no GPS.
 */

/** home | away | unknown | not_shared (`GET presence` → members[].state). */
enum class PresenceState(val wire: String) {
    HOME("home"),
    AWAY("away"),
    UNKNOWN("unknown"),
    NOT_SHARED("not_shared");

    companion object {
        fun fromWire(value: String?): PresenceState? = when (value?.lowercase()?.replace(' ', '_')?.replace("-", "_")) {
            "home", "zuhause" -> HOME
            "away", "unterwegs" -> AWAY
            "unknown" -> UNKNOWN
            "not_shared", "notshared", "hidden", "off" -> NOT_SHARED
            else -> null
        }
    }
}

data class PresenceMember(
    val userId: Int,
    val state: PresenceState,
    /** Since when (ISO), when known. */
    val since: String?,
    /** The person agreed to share (consent). */
    val shares: Boolean,
)

/** `GET presence`. */
data class PresencePayload(val members: List<PresenceMember>) {
    fun of(userId: Int): PresenceMember? = members.firstOrNull { it.userId == userId }
    val home: List<PresenceMember> get() = members.filter { it.state == PresenceState.HOME }
}

/** What the phone can tell right now. */
enum class PresenceSense { HOME, AWAY, UNKNOWN }

/**
 * Decisions of the presence report, pure (unit-tested): what the phone
 * senses and whether a stable state is worth a report.
 */
object PresenceLogic {

    /** A state must hold this long before it is reported (contract: 2 minutes). */
    const val DEBOUNCE_MS = 2 * 60_000L

    /**
     * The home-network check runs only with "Vertrauenswürdige WLANs" and
     * its "Heimnetz des Servers" option on (and a policy that lets the app
     * disconnect); without it the phone cannot tell home from away.
     */
    fun detectionAvailable(trustedEnabled: Boolean, homeEnabled: Boolean, canDisconnect: Boolean): Boolean =
        trustedEnabled && homeEnabled && canDisconnect

    /**
     * Home: the server confirmed (through the tunnel) that this WLAN is its
     * LAN. Away: mobile data / no WLAN, or a WLAN the server says is not its
     * LAN. Anything in between (checking, failed, no network) is unknown and
     * never reported.
     */
    fun sense(available: Boolean, network: TrustedNetSnapshot.Kind, home: HomeCheck): PresenceSense = when {
        !available -> PresenceSense.UNKNOWN
        network == TrustedNetSnapshot.Kind.NONE -> PresenceSense.UNKNOWN
        network == TrustedNetSnapshot.Kind.OTHER -> PresenceSense.AWAY
        home == HomeCheck.HOME -> PresenceSense.HOME
        home == HomeCheck.NOT_HOME -> PresenceSense.AWAY
        else -> PresenceSense.UNKNOWN
    }

    /**
     * Report [stable] (a state that held for [DEBOUNCE_MS])? Only with
     * consent and the module on, only home / away, only when it differs
     * from what was reported last ([lastReported]: true home, false away,
     * null nothing yet).
     */
    fun shouldReport(stable: PresenceSense, consent: Boolean, moduleOn: Boolean, lastReported: Boolean?): Boolean {
        if (!consent || !moduleOn) return false
        val home = when (stable) {
            PresenceSense.HOME -> true
            PresenceSense.AWAY -> false
            PresenceSense.UNKNOWN -> return false
        }
        return home != lastReported
    }
}
