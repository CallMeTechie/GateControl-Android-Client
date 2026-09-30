package com.gatecontrol.android.tunnel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TunnelMonitorTest {

    private fun nowSec() = System.currentTimeMillis() / 1000

    @Test
    fun `calculateBackoffMs returns 2000ms for attempt 0`() {
        assertEquals(2000L, TunnelMonitor.calculateBackoffMs(0))
    }

    @Test
    fun `calculateBackoffMs returns 3000ms for attempt 1`() {
        assertEquals(3000L, TunnelMonitor.calculateBackoffMs(1))
    }

    @Test
    fun `calculateBackoffMs caps at 60000ms for high attempts`() {
        val result = TunnelMonitor.calculateBackoffMs(100)
        assertEquals(60_000L, result)
    }

    @Test
    fun `calculateBackoffMs increases exponentially`() {
        val attempt0 = TunnelMonitor.calculateBackoffMs(0)
        val attempt1 = TunnelMonitor.calculateBackoffMs(1)
        val attempt2 = TunnelMonitor.calculateBackoffMs(2)

        assertTrue(attempt1 > attempt0, "attempt 1 should be greater than attempt 0")
        assertTrue(attempt2 > attempt1, "attempt 2 should be greater than attempt 1")
        // Verify the 1.5x growth factor roughly holds
        assertEquals(3000L, attempt1) // 2000 * 1.5^1
        assertEquals(4500L, attempt2) // 2000 * 1.5^2
    }

    @Test
    fun `shouldReconnect returns true when attempt is within max`() {
        assertTrue(TunnelMonitor.shouldReconnect(0, 10))
        assertTrue(TunnelMonitor.shouldReconnect(9, 10))
    }

    @Test
    fun `shouldReconnect returns false at or beyond max attempts`() {
        assertFalse(TunnelMonitor.shouldReconnect(10, 10))
        assertFalse(TunnelMonitor.shouldReconnect(11, 10))
    }

    @Test
    fun `isHandshakeStale detects old handshake beyond maxAgeSec`() {
        val staleEpoch = (System.currentTimeMillis() / 1000) - 200
        assertTrue(TunnelMonitor.isHandshakeStale(staleEpoch, 180L))
    }

    @Test
    fun `isHandshakeStale accepts fresh handshake within 10 seconds`() {
        val freshEpoch = (System.currentTimeMillis() / 1000) - 5
        assertFalse(TunnelMonitor.isHandshakeStale(freshEpoch, 180L))
    }

    @Test
    fun `isHandshakeStale treats zero epoch as stale`() {
        assertTrue(TunnelMonitor.isHandshakeStale(0L, 180L))
    }

    @Test
    fun `missing statistics count as failure`() {
        assertTrue(TunnelMonitor.isFailure(null, null, 180L))
    }

    @Test
    fun `fresh handshake is healthy`() {
        val cur = TunnelStats(txBytes = 500, lastHandshakeEpoch = nowSec() - 10)
        assertFalse(TunnelMonitor.isFailure(TunnelStats(txBytes = 100), cur, 180L))
    }

    @Test
    fun `stale handshake on an idle tunnel is not a failure`() {
        val prev = TunnelStats(txBytes = 100, lastHandshakeEpoch = nowSec() - 600)
        val cur = prev.copy()
        assertFalse(TunnelMonitor.isFailure(prev, cur, 180L))
    }

    @Test
    fun `stale handshake while sending is a failure`() {
        val prev = TunnelStats(txBytes = 100, lastHandshakeEpoch = nowSec() - 600)
        val cur = prev.copy(txBytes = 400)
        assertTrue(TunnelMonitor.isFailure(prev, cur, 180L))
    }

    @Test
    fun `stale handshake without previous sample is not judged yet`() {
        val cur = TunnelStats(txBytes = 400, lastHandshakeEpoch = nowSec() - 600)
        assertFalse(TunnelMonitor.isFailure(null, cur, 180L))
    }

    @Test
    fun `reconnects after consecutive failures and resumes`() = runTest {
        val monitor = TunnelMonitor(intervalMs = 1_000L, failuresBeforeReconnect = 3, maxReconnectAttempts = 5)
        val attempts = mutableListOf<Int>()
        monitor.start(
            scope = backgroundScope,
            statsProvider = { null },
            reconnect = { attempt, _ -> attempts += attempt; attempt == 2 },
        )
        advanceTimeBy(3_500L)
        // Third failed check triggers attempt 1 (fails), backoff 2 s, attempt 2 (succeeds).
        advanceTimeBy(2_100L)
        assertEquals(listOf(1, 2), attempts)
        assertTrue(monitor.isRunning)
        monitor.stop()
        assertFalse(monitor.isRunning)
    }

    @Test
    fun `gives up after max reconnect attempts`() = runTest {
        val monitor = TunnelMonitor(intervalMs = 1_000L, failuresBeforeReconnect = 1, maxReconnectAttempts = 3)
        var calls = 0
        monitor.start(backgroundScope, statsProvider = { null }, reconnect = { _, _ -> calls++; false })
        advanceTimeBy(60_000L)
        assertEquals(3, calls)
        assertFalse(monitor.isRunning)
    }

    @Test
    fun `healthy tunnel never reconnects`() = runTest {
        val monitor = TunnelMonitor(intervalMs = 1_000L, failuresBeforeReconnect = 1)
        var calls = 0
        monitor.start(
            backgroundScope,
            statsProvider = { TunnelStats(lastHandshakeEpoch = nowSec()) },
            reconnect = { _, _ -> calls++; true },
        )
        advanceTimeBy(30_000L)
        assertEquals(0, calls)
        monitor.stop()
    }
}
