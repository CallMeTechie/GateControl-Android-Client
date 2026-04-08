package com.gatecontrol.android.tunnel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class TunnelMonitor(
    private val intervalMs: Long = 30_000L,
    private val maxHandshakeAgeSec: Long = 180L,
    private val maxReconnectAttempts: Int = 10,
    private val failuresBeforeDisconnect: Int = 3
) {
    private val _disconnectEvent = MutableSharedFlow<Unit>()
    val disconnectEvent: SharedFlow<Unit> = _disconnectEvent.asSharedFlow()

    private val _reconnectEvent = MutableSharedFlow<ReconnectRequest>()
    val reconnectEvent: SharedFlow<ReconnectRequest> = _reconnectEvent.asSharedFlow()

    private val _statsEvent = MutableSharedFlow<TunnelStats>()
    val statsEvent: SharedFlow<TunnelStats> = _statsEvent.asSharedFlow()

    private var monitorJob: Job? = null

    data class ReconnectRequest(val attempt: Int, val maxAttempts: Int)

    fun start(scope: CoroutineScope, statsProvider: suspend () -> TunnelStats?) {
        stop()
        monitorJob = scope.launch {
            var consecutiveFailures = 0
            while (true) {
                delay(intervalMs)
                val stats = statsProvider()
                val isFailure = stats == null || isHandshakeStale(stats.lastHandshakeEpoch, maxHandshakeAgeSec)
                if (isFailure) {
                    consecutiveFailures++
                    if (consecutiveFailures >= failuresBeforeDisconnect) {
                        _disconnectEvent.emit(Unit)
                        // Attempt reconnection with exponential backoff
                        for (attempt in 0 until maxReconnectAttempts) {
                            val backoffMs = calculateBackoffMs(attempt)
                            _reconnectEvent.emit(ReconnectRequest(attempt + 1, maxReconnectAttempts))
                            delay(backoffMs)
                            val retryStats = statsProvider()
                            if (retryStats != null && !isHandshakeStale(retryStats.lastHandshakeEpoch, maxHandshakeAgeSec)) {
                                consecutiveFailures = 0
                                _statsEvent.emit(retryStats)
                                break
                            }
                            if (attempt == maxReconnectAttempts - 1) {
                                return@launch // Give up after max attempts
                            }
                        }
                    }
                } else {
                    consecutiveFailures = 0
                    _statsEvent.emit(stats!!)
                }
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
    }
}
