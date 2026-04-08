package com.gatecontrol.android.rdp

import com.gatecontrol.android.network.RdpRoute
import com.gatecontrol.android.network.RdpRouteStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RdpConnectionParamsTest {

    private fun buildFullRoute() = RdpRoute(
        id = 42,
        name = "office-pc",
        host = "192.168.1.100",
        port = 3389,
        externalHostname = null,
        externalPort = null,
        accessMode = "vpn",
        credentialMode = "full",
        domain = "CORP",
        resolutionMode = "fixed",
        resolutionWidth = 1920,
        resolutionHeight = 1080,
        multiMonitor = false,
        colorDepth = 24,
        redirectClipboard = true,
        redirectPrinters = true,
        redirectDrives = true,
        audioMode = "redirect",
        networkProfile = null,
        sessionTimeout = null,
        adminSession = true,
        wolEnabled = false,
        maintenanceEnabled = false,
        status = RdpRouteStatus(online = true, lastCheck = null)
    )

    private fun buildMinimalRoute() = RdpRoute(
        id = 1,
        name = "minimal",
        host = "10.0.0.1",
        port = 3389,
        externalHostname = null,
        externalPort = null,
        accessMode = "vpn",
        credentialMode = "none",
        domain = null,
        resolutionMode = null,
        resolutionWidth = null,
        resolutionHeight = null,
        multiMonitor = null,
        colorDepth = null,
        redirectClipboard = null,
        redirectPrinters = null,
        redirectDrives = null,
        audioMode = null,
        networkProfile = null,
        sessionTimeout = null,
        adminSession = null,
        wolEnabled = null,
        maintenanceEnabled = null,
        status = null
    )

    // 1. Full field mapping
    @Test
    fun `fromRoute maps all fields from a fully populated RdpRoute`() {
        val route = buildFullRoute()
        val params = RdpConnectionParams.fromRoute(
            route = route,
            username = "alice",
            password = "s3cr3t",
            domain = null
        )

        assertEquals("192.168.1.100", params.host)
        assertEquals(3389, params.port)
        assertEquals("alice", params.username)
        assertEquals("s3cr3t", params.password)
        assertEquals("CORP", params.domain)
        assertEquals(1920, params.resolutionWidth)
        assertEquals(1080, params.resolutionHeight)
        assertEquals(24, params.colorDepth)
        assertTrue(params.redirectClipboard)
        assertTrue(params.redirectPrinters)
        assertTrue(params.redirectDrives)
        assertEquals("redirect", params.audioMode)
        assertTrue(params.adminSession)
        assertEquals("office-pc", params.routeName)
    }

    // 2. Defaults for null optional fields
    @Test
    fun `fromRoute uses defaults for null optional fields`() {
        val route = buildMinimalRoute()
        val params = RdpConnectionParams.fromRoute(
            route = route,
            username = null,
            password = null,
            domain = null
        )

        assertNull(params.username)
        assertNull(params.password)
        assertNull(params.domain)
        assertEquals(0, params.resolutionWidth)
        assertEquals(0, params.resolutionHeight)
        assertEquals(32, params.colorDepth)
        assertFalse(params.redirectClipboard)
        assertFalse(params.redirectPrinters)
        assertFalse(params.redirectDrives)
        assertEquals("local", params.audioMode)
        assertFalse(params.adminSession)
        assertEquals("minimal", params.routeName)
    }

    // 3. Explicit domain param overrides route domain
    @Test
    fun `fromRoute prefers explicit domain param over route domain`() {
        val route = buildFullRoute() // route.domain = "CORP"
        val params = RdpConnectionParams.fromRoute(
            route = route,
            username = "bob",
            password = null,
            domain = "OVERRIDE"
        )

        assertEquals("OVERRIDE", params.domain)
    }

    // 4. Fallback to route domain when explicit domain is null
    @Test
    fun `fromRoute falls back to route domain when explicit domain is null`() {
        val route = buildFullRoute() // route.domain = "CORP"
        val params = RdpConnectionParams.fromRoute(
            route = route,
            username = "bob",
            password = null,
            domain = null
        )

        assertEquals("CORP", params.domain)
    }
}
