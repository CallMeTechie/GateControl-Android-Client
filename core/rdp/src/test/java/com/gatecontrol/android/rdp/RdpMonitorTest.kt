package com.gatecontrol.android.rdp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RdpMonitorTest {

    private lateinit var monitor: RdpMonitor

    @BeforeEach
    fun setUp() {
        monitor = RdpMonitor()
    }

    // -------------------------------------------------------------------------
    // startSession
    // -------------------------------------------------------------------------

    @Test
    fun `startSession returns session with correct fields`() {
        val session = monitor.startSession(
            routeId = 1,
            sessionId = 42,
            host = "192.168.1.10",
            isExternal = true
        )

        assertEquals(1, session.routeId)
        assertEquals(42, session.sessionId)
        assertEquals("192.168.1.10", session.host)
        assertTrue(session.isExternal)
        assertTrue(session.active)
        assertTrue(session.startTime > 0, "startTime should be a positive epoch millis value")
    }

    @Test
    fun `startSession makes session retrievable`() {
        monitor.startSession(routeId = 1, sessionId = 10, host = "host1", isExternal = false)

        val retrieved = monitor.getActiveSession(routeId = 1)
        assertNotNull(retrieved)
        assertEquals(10, retrieved!!.sessionId)
    }

    @Test
    fun `startSession overwrites existing session for same routeId`() {
        monitor.startSession(routeId = 1, sessionId = 10, host = "host1", isExternal = false)
        monitor.startSession(routeId = 1, sessionId = 20, host = "host1", isExternal = false)

        val retrieved = monitor.getActiveSession(routeId = 1)
        assertNotNull(retrieved)
        assertEquals(20, retrieved!!.sessionId)
    }

    // -------------------------------------------------------------------------
    // endSession
    // -------------------------------------------------------------------------

    @Test
    fun `endSession returns the session with active=false`() {
        monitor.startSession(routeId = 1, sessionId = 42, host = "host1", isExternal = true)

        val ended = monitor.endSession(routeId = 1)

        assertNotNull(ended)
        assertEquals(42, ended!!.sessionId)
        assertFalse(ended.active, "Ended session should have active=false")
    }

    @Test
    fun `endSession removes session from active map`() {
        monitor.startSession(routeId = 1, sessionId = 42, host = "host1", isExternal = false)
        monitor.endSession(routeId = 1)

        assertNull(monitor.getActiveSession(routeId = 1))
    }

    @Test
    fun `endSession returns null for unknown routeId`() {
        val result = monitor.endSession(routeId = 999)
        assertNull(result)
    }

    // -------------------------------------------------------------------------
    // getActiveSession
    // -------------------------------------------------------------------------

    @Test
    fun `getActiveSession returns null when no session registered`() {
        assertNull(monitor.getActiveSession(routeId = 1))
    }

    @Test
    fun `getActiveSession returns correct session among multiple`() {
        monitor.startSession(routeId = 1, sessionId = 11, host = "host1", isExternal = false)
        monitor.startSession(routeId = 2, sessionId = 22, host = "host2", isExternal = true)

        assertEquals(11, monitor.getActiveSession(1)!!.sessionId)
        assertEquals(22, monitor.getActiveSession(2)!!.sessionId)
    }

    // -------------------------------------------------------------------------
    // getAllActiveSessions
    // -------------------------------------------------------------------------

    @Test
    fun `getAllActiveSessions is empty initially`() {
        assertTrue(monitor.getAllActiveSessions().isEmpty())
    }

    @Test
    fun `getAllActiveSessions returns all registered sessions`() {
        monitor.startSession(routeId = 1, sessionId = 11, host = "host1", isExternal = false)
        monitor.startSession(routeId = 2, sessionId = 22, host = "host2", isExternal = true)
        monitor.startSession(routeId = 3, sessionId = 33, host = "host3", isExternal = false)

        val all = monitor.getAllActiveSessions()
        assertEquals(3, all.size)
        val routeIds = all.map { it.routeId }.toSet()
        assertTrue(routeIds.containsAll(listOf(1, 2, 3)))
    }

    @Test
    fun `getAllActiveSessions excludes ended sessions`() {
        monitor.startSession(routeId = 1, sessionId = 11, host = "host1", isExternal = false)
        monitor.startSession(routeId = 2, sessionId = 22, host = "host2", isExternal = true)
        monitor.endSession(routeId = 1)

        val all = monitor.getAllActiveSessions()
        assertEquals(1, all.size)
        assertEquals(2, all.first().routeId)
    }

    // -------------------------------------------------------------------------
    // Full lifecycle
    // -------------------------------------------------------------------------

    @Test
    fun `full session lifecycle start then end cleans up correctly`() {
        assertTrue(monitor.getAllActiveSessions().isEmpty())

        monitor.startSession(routeId = 5, sessionId = 99, host = "rdp.corp.local", isExternal = true)
        assertEquals(1, monitor.getAllActiveSessions().size)

        val ended = monitor.endSession(routeId = 5)
        assertNotNull(ended)
        assertFalse(ended!!.active)
        assertTrue(monitor.getAllActiveSessions().isEmpty())
        assertNull(monitor.getActiveSession(5))
    }
}
