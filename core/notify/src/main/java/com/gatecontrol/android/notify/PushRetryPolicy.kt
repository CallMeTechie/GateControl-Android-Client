package com.gatecontrol.android.notify

import kotlin.random.Random

/** How one connection attempt of the push stream ended. */
sealed interface StreamOutcome {
    /** The stream was open and ended (server closed it, Caddy reload, network gone). */
    data class Ended(val openForMs: Long, val sawHello: Boolean) : StreamOutcome

    /** Non-2xx answer. [error] is the JSON `error` code when there was one. */
    data class HttpError(
        val code: Int,
        val error: String?,
        val retryAfterS: Long? = null,
        /** The machine binding rejected this device (403/400 with a binding message). */
        val bindingRejected: Boolean = false,
    ) : StreamOutcome

    /** No answer: DNS, TCP, TLS, reset … (class name only, never the message). */
    data class NetworkError(val kind: String) : StreamOutcome

    /** 2xx but not an event stream (captive portal, proxy page). */
    data object NotAStream : StreamOutcome

    /** No byte (not even the keepalive comment) for too long: the connection is dead. */
    data object Stale : StreamOutcome

    /** Reconnect requested (network changed). */
    data object Kicked : StreamOutcome
}

/** Why the client waits before the next attempt. */
enum class WaitReason {
    /** Network or server trouble: exponential backoff. */
    BACKOFF,

    /** 404: the server has no push yet (too old). Hourly. */
    SERVER_UNSUPPORTED,

    /** 503 push_disabled: push is off on the server. Hourly. */
    PUSH_DISABLED,

    /** 503 too_many_streams: global limit reached. Backoff. */
    SERVER_BUSY,

    /** 429: too many connection attempts. At least 15 minutes. */
    RATE_LIMITED,

    /** 403 direct_not_allowed: the admin allows push only through the tunnel. VPN up or hourly. */
    DIRECT_NOT_ALLOWED,
}

/** Why the client gave up until the setup changes (or the user retries). */
enum class BlockReason {
    /** 401, 403 token_required / scope_required or another 403: token not accepted. */
    AUTH,

    /** 403 machine binding: the token is bound to another device. */
    MACHINE_BINDING,
}

sealed interface RetryDecision {
    data class After(val delayMs: Long, val reason: WaitReason) : RetryDecision
    data class Blocked(val reason: BlockReason) : RetryDecision
}

/**
 * Reconnect rules of the contract: exponential backoff with jitter from 1 s
 * to 5 min for transient trouble; hourly while the server has no push or has
 * it switched off; at least 15 min after 429; nothing after 401/403 until the
 * setup changes.
 */
class PushRetryPolicy(private val random: Random = Random.Default) {

    companion object {
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 5 * 60_000L
        const val HOURLY_MS = 60 * 60_000L
        const val RATE_LIMIT_MS = 15 * 60_000L

        /** A stream open at least this long counts as healthy: the next failure starts the backoff anew. */
        const val STABLE_MS = 60_000L
    }

    /**
     * Backoff before attempt number [attempt] (0 = first retry): the delay
     * doubles from [MIN_BACKOFF_MS] up to [MAX_BACKOFF_MS]; "equal jitter"
     * picks it from the upper half so devices don't reconnect in lockstep.
     */
    fun backoffMs(attempt: Int): Long {
        val exp = MIN_BACKOFF_MS shl attempt.coerceIn(0, 20)
        val capped = exp.coerceAtMost(MAX_BACKOFF_MS)
        val half = capped / 2
        return half + random.nextLong(half + 1)
    }

    /** Up to +10 % so a fleet of devices spreads its hourly retries. */
    private fun jittered(baseMs: Long): Long = baseMs + random.nextLong(baseMs / 10 + 1)

    fun decide(outcome: StreamOutcome, attempt: Int): RetryDecision = when (outcome) {
        is StreamOutcome.Kicked -> RetryDecision.After(0, WaitReason.BACKOFF)
        is StreamOutcome.Ended ->
            if (outcome.openForMs >= STABLE_MS) RetryDecision.After(backoffMs(0), WaitReason.BACKOFF)
            else RetryDecision.After(backoffMs(attempt), WaitReason.BACKOFF)
        is StreamOutcome.NetworkError,
        is StreamOutcome.NotAStream,
        is StreamOutcome.Stale -> RetryDecision.After(backoffMs(attempt), WaitReason.BACKOFF)
        is StreamOutcome.HttpError -> http(outcome, attempt)
    }

    private fun http(e: StreamOutcome.HttpError, attempt: Int): RetryDecision = when {
        e.code == 401 -> RetryDecision.Blocked(BlockReason.AUTH)
        e.code == 403 && e.error == "direct_not_allowed" ->
            RetryDecision.After(jittered(HOURLY_MS), WaitReason.DIRECT_NOT_ALLOWED)
        e.bindingRejected -> RetryDecision.Blocked(BlockReason.MACHINE_BINDING)
        e.code == 403 -> RetryDecision.Blocked(BlockReason.AUTH)
        e.code == 404 -> RetryDecision.After(jittered(HOURLY_MS), WaitReason.SERVER_UNSUPPORTED)
        e.code == 429 -> {
            val serverHint = (e.retryAfterS ?: 0L) * 1000L
            RetryDecision.After(jittered(maxOf(RATE_LIMIT_MS, serverHint)), WaitReason.RATE_LIMITED)
        }
        e.code == 503 && e.error == "push_disabled" ->
            RetryDecision.After(jittered(HOURLY_MS), WaitReason.PUSH_DISABLED)
        e.code == 503 && e.error == "too_many_streams" ->
            RetryDecision.After(backoffMs(attempt), WaitReason.SERVER_BUSY)
        else -> RetryDecision.After(backoffMs(attempt), WaitReason.BACKOFF)
    }
}
