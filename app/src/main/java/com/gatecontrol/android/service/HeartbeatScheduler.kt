package com.gatecontrol.android.service

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * When the VPN heartbeat runs. The first one goes out right when the tunnel
 * is connected. After that every [FAST_MS] (60 s) — unless the push stream
 * is open to a server that pushes control events ([pushLive]): then the
 * heartbeat is only a fallback and runs every [SLOW_MS] (15 min).
 *
 * The switch is reactive: when push goes down the next heartbeat is due
 * 60 s after the last one (right away if that has passed); when push comes
 * up the pending wait stretches to 15 min. A failed or skipped heartbeat is
 * retried after 60 s. Only coroutine delays, no alarms or extra wakeups.
 */
class HeartbeatScheduler(
    private val pushLive: StateFlow<Boolean>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val FAST_MS = 60_000L
        const val SLOW_MS = 15 * 60_000L

        /** Interval after a heartbeat that [succeeded] (sent and answered). */
        fun intervalMs(pushLive: Boolean, succeeded: Boolean): Long =
            if (pushLive && succeeded) SLOW_MS else FAST_MS

        /** When the next heartbeat is due. */
        fun nextDueAt(lastAt: Long, pushLive: Boolean, succeeded: Boolean): Long =
            lastAt + intervalMs(pushLive, succeeded)
    }

    /** Runs [beat] (true = sent and answered) until cancelled. */
    suspend fun run(beat: suspend () -> Boolean) {
        while (true) {
            val ok = beat()
            awaitNext(clock(), ok)
        }
    }

    /** Suspends until the next heartbeat after one at [lastAt] is due, following push up/down. */
    internal suspend fun awaitNext(lastAt: Long, succeeded: Boolean) {
        while (true) {
            val live = pushLive.value
            val wait = nextDueAt(lastAt, live, succeeded) - clock()
            if (wait <= 0) return
            withTimeoutOrNull(wait) { pushLive.first { it != live } } ?: return
        }
    }
}
