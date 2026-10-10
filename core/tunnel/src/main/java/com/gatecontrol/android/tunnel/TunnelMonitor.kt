package com.gatecontrol.android.tunnel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Watches a connected tunnel and brings it up again when the peer stops
 * answering (server restarted, endpoint moved to a new IP, network switch
 * the backend did not recover from).
 *
 * A sample counts as a failure when no statistics are available, or when we
 * keep sending while the last handshake is older than [maxHandshakeAgeSec].
 * An idle tunnel without PersistentKeepalive never re-handshakes, so an old
 * handshake alone is not a failure — only one while traffic goes out.
 */
class TunnelMonitor(
    private val intervalMs: Long = 30_000L,
    private val maxHandshakeAgeSec: Long = 180L,
    private val maxReconnectAttempts: Int = 10,
    private val failuresBeforeReconnect: Int = 3,
) {
    private var monitorJob: Job? = null

    val isRunning: Boolean get() = monitorJob?.isActive == true

    /**
     * @param statsProvider current tunnel statistics, null when unavailable.
     * @param reconnect one reconnect attempt; returns true when the tunnel is up again.
     */
    fun start(
        scope: CoroutineScope,
        statsProvider: suspend () -> TunnelStats?,
        reconnect: suspend (attempt: Int, maxAttempts: Int) -> Boolean,
    ) {
        stop()
        monitorJob = scope.launch {
            var consecutiveFailures = 0
            var previous: TunnelStats? = null
            while (true) {
                delay(intervalMs)
                val stats = statsProvider()
                if (!isFailure(previous, stats, maxHandshakeAgeSec)) {
                    consecutiveFailures = 0
                    previous = stats
                    continue
                }
                consecutiveFailures++
                previous = stats
                if (consecutiveFailures < failuresBeforeReconnect) continue

                Timber.w("Tunnel unresponsive ($consecutiveFailures checks) — reconnecting")
                var recovered = false
                for (attempt in 0 until maxReconnectAttempts) {
                    if (reconnect(attempt + 1, maxReconnectAttempts)) {
                        recovered = true
                        break
                    }
                    delay(calculateBackoffMs(attempt))
                }
                if (!recovered) {
                    Timber.e("Tunnel did not recover after $maxReconnectAttempts attempts — giving up")
                    return@launch
                }
                Timber.i("Tunnel recovered")
                consecutiveFailures = 0
                previous = null
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    companion object {
        fun calculateBackoffMs(attempt: Int): Long {
            val base = 2000L
            val factor = 1.5
            val computed = base * Math.pow(factor, attempt.toDouble())
            return minOf(computed.toLong(), 60_000L)
        }

        fun shouldReconnect(attempt: Int, maxAttempts: Int): Boolean = attempt < maxAttempts

        fun isHandshakeStale(epochSeconds: Long, maxAgeSec: Long): Boolean {
            if (epochSeconds == 0L) return true
            val nowSeconds = System.currentTimeMillis() / 1000
            return (nowSeconds - epochSeconds) > maxAgeSec
        }

        /**
         * True when [current] shows a dead peer: no statistics at all, or a
         * stale handshake while bytes were sent since [previous]. Without a
         * previous sample the send direction is unknown, so a stale handshake
         * is not yet judged.
         */
        fun isFailure(previous: TunnelStats?, current: TunnelStats?, maxHandshakeAgeSec: Long): Boolean {
            if (current == null) return true
            if (!isHandshakeStale(current.lastHandshakeEpoch, maxHandshakeAgeSec)) return false
            val sending = previous != null && current.txBytes > previous.txBytes
            return sending
        }
    }
}
