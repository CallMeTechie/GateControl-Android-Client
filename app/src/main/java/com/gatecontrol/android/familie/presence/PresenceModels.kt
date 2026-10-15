package com.gatecontrol.android.familie.presence

import com.gatecontrol.android.network.HomeNetworkStatus
import com.gatecontrol.android.trustednet.TrustedNetSnapshot

/*
 * Familie 1.2 Anwesenheit (`presence`): each person decides whether the app
 * reports "zuhause" / "unterwegs" to the family's own server. The phone
 * asks the server itself (`GET api/v1/client/home-network` with its private
 * LAN addresses): the server's own LAN or, from core 1.167 on, the LAN
 * behind one of its gateways counts as home — no GPS, and independent of
 * "Vertrauenswürdige WLANs" and of the VPN.
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

/** How the last home check went, for the explanation on the switch. */
enum class PresenceCheck {
    /** Not asked yet (or no network). */
    NOT_YET,
    /** A clear answer. */
    OK,
    /** Server before 1.167: home only in its own LAN and through the tunnel; a direct "no" means nothing. */
    OLD_SERVER,
    /** Server without the home-network check. */
    UNSUPPORTED,
    /** Error, timeout, no answer: the last state is kept. */
    FAILED,
}

/** One home check, read for presence. */
data class PresenceObservation(val sense: PresenceSense, val check: PresenceCheck)

/**
 * Decisions of the presence report, pure (unit-tested): what an answer of
 * the home check means, when no question is needed, and whether a stable
 * state is worth a report.
 */
object PresenceLogic {

    /** A state must hold this long before it is reported (contract: 2 minutes). */
    const val DEBOUNCE_MS = 2 * 60_000L

    /**
     * Before asking: no network → nothing can be told ([PresenceSense.UNKNOWN]);
     * mobile data only (no Wi-Fi, no LAN address) → away, the home network
     * can't be reached that way; otherwise null = ask the server.
     */
    fun senseWithoutAsking(network: TrustedNetSnapshot.Kind, hasLanAddress: Boolean): PresenceSense? = when {
        network == TrustedNetSnapshot.Kind.NONE -> PresenceSense.UNKNOWN
        network == TrustedNetSnapshot.Kind.OTHER && !hasLanAddress -> PresenceSense.AWAY
        else -> null
    }

    /**
     * The server's answer: `same_lan` true → home (through the tunnel or,
     * core 1.167+, directly at a gateway site). False → away when it came
     * through the tunnel or from a server that knows gateway sites; a
     * direct "no" from an older server says nothing (it answers every
     * direct request with "no"). Errors and older servers without the
     * check → unknown.
     */
    fun interpret(status: HomeNetworkStatus): PresenceObservation = when (status) {
        is HomeNetworkStatus.Home -> PresenceObservation(PresenceSense.HOME, PresenceCheck.OK)
        is HomeNetworkStatus.NotHome -> when {
            status.siteAware -> PresenceObservation(PresenceSense.AWAY, PresenceCheck.OK)
            status.viaTunnel -> PresenceObservation(PresenceSense.AWAY, PresenceCheck.OLD_SERVER)
            else -> PresenceObservation(PresenceSense.UNKNOWN, PresenceCheck.OLD_SERVER)
        }
        HomeNetworkStatus.Unsupported -> PresenceObservation(PresenceSense.UNKNOWN, PresenceCheck.UNSUPPORTED)
        HomeNetworkStatus.Unknown -> PresenceObservation(PresenceSense.UNKNOWN, PresenceCheck.FAILED)
    }

    /** A clear answer replaces the state; "can't tell" keeps the last one (no flapping). */
    fun next(current: PresenceSense, observed: PresenceSense): PresenceSense =
        if (observed == PresenceSense.UNKNOWN) current else observed

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

    /**
     * Right after "Anwesenheit teilen" went on (nothing reported since,
     * [lastReported] null): the first clear answer of one check is reported
     * at once, without [DEBOUNCE_MS], so the family does not see "unknown"
     * for minutes. Every later change keeps the debounce.
     */
    fun reportFirstNow(observed: PresenceSense, firstPending: Boolean, consent: Boolean, moduleOn: Boolean, lastReported: Boolean?): Boolean =
        firstPending && lastReported == null && shouldReport(observed, consent, moduleOn, lastReported)
}
