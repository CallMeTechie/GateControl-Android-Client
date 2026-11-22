package com.gatecontrol.android.tv.pair

import com.gatecontrol.android.common.EnrollmentLink

/** A display code of the server (`POST /api/v1/client/pair/start`) while the TV waits for the phone. */
data class PairCode(
    val pairingId: String,
    val pollSecret: String,
    /** `ABCD-EFGH`, as the server formats it. */
    val code: String,
    /** `gatecontrol://tv-pair?url=<server>&id=<pairingId>` for the QR code. */
    val link: String,
    /** End of the code, epoch ms (10 minutes after the start). */
    val expiresAt: Long,
    /** Poll interval in ms (the server's `interval`, at least [PairingMachine.MIN_INTERVAL_MS]). */
    val intervalMs: Long,
) {
    override fun toString(): String =
        "PairCode(pairingId=$pairingId, pollSecret=***, code=$code, expiresAt=$expiresAt, intervalMs=$intervalMs)"
}

/** Why the pairing stopped (texts in the pairing screen). */
enum class PairError {
    /** The server could not be reached (start). */
    NETWORK,

    /** The server cannot pair TVs yet (before 1.318.0: no such route). */
    NOT_SUPPORTED,

    /** Too many pairings at once, or too many starts from this address. */
    BUSY,
    RATE_LIMITED,

    /** The account that approved may not set up devices, is disabled, or the license has no room for another device. */
    NOT_ALLOWED,
    USER_DISABLED,
    LIMIT_REACHED,

    /** Redeeming the setup code failed. */
    ENROLL_FAILED,

    /** The setup code typed in ("Ohne Handy einrichten") is wrong, expired or used. */
    INVALID_SETUP_CODE,

    /** The answer was not what this app understands. */
    INVALID_ANSWER,
}

/** Where the pairing of this TV stands. */
sealed interface PairState {
    /** The server is not known yet: typed in once on the TV ([error]: why the last address was not taken). */
    data class NeedServer(val error: ServerInputError? = null) : PairState

    /** Asking the server for a display code. */
    data class Starting(val server: String) : PairState

    /** Showing [code] and the QR code; the next poll at [nextPollAt] (epoch ms). */
    data class Waiting(val server: String, val code: PairCode, val nextPollAt: Long) : PairState

    /** The phone approved (or a setup code was typed in): redeeming [setupCode] at `POST /api/v1/client/enroll`. */
    data class Enrolling(val server: String, val setupCode: String, val name: String?) : PairState {
        override fun toString(): String = "Enrolling(server=$server, setupCode=***, name=$name)"
    }

    /** Set up: token and WireGuard config are stored. */
    data object Paired : PairState

    /** Stopped with [error]; "Neuen Code holen" starts again. */
    data class Failed(val server: String, val error: PairError) : PairState
}

enum class ServerInputError { INVALID, HTTPS_ONLY }

sealed interface PairEvent {
    /** The server address typed in at the TV. */
    data class ServerEntered(val raw: String) : PairEvent

    data class Started(val code: PairCode) : PairEvent
    data class StartFailed(val error: PairError) : PairEvent

    /** Answers of `pair/poll` for the pairing [pairingId] (answers of an older pairing are ignored). */
    data class PollPending(val pairingId: String, val intervalMs: Long?) : PairEvent
    data class PollSlowDown(val pairingId: String, val intervalMs: Long?) : PairEvent
    data class PollApproved(val pairingId: String, val setupCode: String, val name: String?) : PairEvent {
        override fun toString(): String = "PollApproved(pairingId=$pairingId, setupCode=***, name=$name)"
    }

    /** `invalid_or_expired`: the pairing is gone (expired, locked after wrong codes, server restarted). */
    data class PollExpired(val pairingId: String) : PairEvent
    data class PollFailed(val pairingId: String, val error: PairError) : PairEvent

    /** No answer (network): polled again after the interval, while the code is valid. */
    data class PollUnreachable(val pairingId: String) : PairEvent

    data object Enrolled : PairEvent
    data class EnrollFailed(val error: PairError) : PairEvent

    /** Time passed (the code may have run out). */
    data object Tick : PairEvent

    /** "Neuen Code holen" after a failure. */
    data object Retry : PairEvent

    /** "Andere Serveradresse". */
    data object ChangeServer : PairEvent

    /** "Ohne Handy einrichten": the server address and the 16-character setup code from the portal. */
    data class ManualSetup(val rawServer: String, val rawCode: String) : PairEvent {
        override fun toString(): String = "ManualSetup(rawServer=$rawServer, rawCode=***)"
    }
}

/**
 * The pairing of GateControl TV as a pure state machine (unit-tested; [PairingController] runs the calls):
 *
 *  server typed in → start (display code + QR) → poll every few seconds → approved by the phone → redeem the setup
 *  code like the phone app → paired.
 *
 * A code that ran out (10 minutes, or the server forgot it) is replaced by a new one without a word; a pairing the
 * server refuses (account, license) stops with the reason. A lost answer only means the next poll.
 */
object PairingMachine {
    /** The server asks for 5 s; never faster than this, whatever an answer says. */
    const val MIN_INTERVAL_MS = 2_000L

    /** Added to the interval on `slow_down` (as RFC 8628 does). */
    const val SLOW_DOWN_STEP_MS = 5_000L

    /** At most this between two polls. */
    const val MAX_INTERVAL_MS = 60_000L

    /** The state to start with: [paired], else the server typed in earlier, else ask for it. */
    fun initial(paired: Boolean, server: String?): PairState = when {
        paired -> PairState.Paired
        server != null -> PairState.Starting(server)
        else -> PairState.NeedServer()
    }

    /** `gate.example.com` / `https://gate.example.com/` → `https://gate.example.com`; plain http is refused. */
    fun serverOf(raw: String): Pair<String?, ServerInputError?> {
        val t = raw.trim()
        if (t.isEmpty()) return null to ServerInputError.INVALID
        if (t.startsWith("http://", ignoreCase = true)) return null to ServerInputError.HTTPS_ONLY
        val withScheme = if (t.contains("://")) t else "https://$t"
        val url = EnrollmentLink.normalizeServerUrl(withScheme) ?: return null to ServerInputError.INVALID
        // a host that is only a word ("gatecontrol") is a typo more often than a name in the LAN
        val host = url.removePrefix("https://").substringBefore(':')
        if (!host.contains('.') && host != "localhost") return null to ServerInputError.INVALID
        return url to null
    }

    private fun interval(ms: Long?, fallback: Long): Long = (ms ?: fallback).coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)

    fun reduce(state: PairState, event: PairEvent, now: Long): PairState {
        // Valid in every state but the end: a new address, or the setup code from the portal.
        when (event) {
            PairEvent.ChangeServer -> if (state !is PairState.Paired && state !is PairState.Enrolling) return PairState.NeedServer()
            is PairEvent.ManualSetup -> if (state !is PairState.Paired && state !is PairState.Enrolling) {
                val server = serverOf(event.rawServer).first ?: return state
                val code = EnrollmentLink.normalizeCode(event.rawCode) ?: return state
                return PairState.Enrolling(server, code, name = null)
            }
            else -> Unit
        }
        return when (state) {
            is PairState.NeedServer -> when (event) {
                is PairEvent.ServerEntered -> {
                    val (url, error) = serverOf(event.raw)
                    if (url != null) PairState.Starting(url) else PairState.NeedServer(error)
                }
                else -> state
            }
            is PairState.Starting -> when (event) {
                is PairEvent.Started -> PairState.Waiting(state.server, event.code, nextPollAt = now + event.code.intervalMs)
                is PairEvent.StartFailed -> PairState.Failed(state.server, event.error)
                else -> state
            }
            is PairState.Waiting -> waiting(state, event, now)
            is PairState.Enrolling -> when (event) {
                PairEvent.Enrolled -> PairState.Paired
                is PairEvent.EnrollFailed -> PairState.Failed(state.server, event.error)
                else -> state
            }
            is PairState.Failed -> when (event) {
                PairEvent.Retry -> PairState.Starting(state.server)
                else -> state
            }
            PairState.Paired -> state
        }
    }

    private fun waiting(state: PairState.Waiting, event: PairEvent, now: Long): PairState {
        val code = state.code
        // an answer to an older code (the TV fetched a new one meanwhile) changes nothing
        val id = when (event) {
            is PairEvent.PollPending -> event.pairingId
            is PairEvent.PollSlowDown -> event.pairingId
            is PairEvent.PollApproved -> event.pairingId
            is PairEvent.PollExpired -> event.pairingId
            is PairEvent.PollFailed -> event.pairingId
            is PairEvent.PollUnreachable -> event.pairingId
            else -> null
        }
        if (id != null && id != code.pairingId) return state
        // approved in the last second still counts; anything else after the end means a new code
        if (event !is PairEvent.PollApproved && now >= code.expiresAt) return PairState.Starting(state.server)
        return when (event) {
            is PairEvent.PollPending -> {
                val next = interval(event.intervalMs, code.intervalMs)
                state.copy(code = code.copy(intervalMs = next), nextPollAt = now + next)
            }
            is PairEvent.PollSlowDown -> {
                val next = interval(maxOf(event.intervalMs ?: 0L, code.intervalMs) + SLOW_DOWN_STEP_MS, code.intervalMs)
                state.copy(code = code.copy(intervalMs = next), nextPollAt = now + next)
            }
            is PairEvent.PollUnreachable -> state.copy(nextPollAt = now + code.intervalMs)
            is PairEvent.PollApproved -> PairState.Enrolling(state.server, event.setupCode, event.name)
            is PairEvent.PollExpired -> PairState.Starting(state.server)
            is PairEvent.PollFailed -> PairState.Failed(state.server, event.error)
            else -> state
        }
    }
}
