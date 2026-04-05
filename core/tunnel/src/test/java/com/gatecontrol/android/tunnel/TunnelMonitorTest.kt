package com.gatecontrol.android.tunnel

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TunnelMonitorTest {

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
}
