package com.gatecontrol.android.rdp

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RdpManagerVpnGateTest {

    @Test
    fun `gateway route does not require VPN`() {
        assertFalse(RdpManager.requiresVpn("gateway"))
    }

    @Test
    fun `gateway is matched case-insensitively`() {
        assertFalse(RdpManager.requiresVpn("Gateway"))
        assertFalse(RdpManager.requiresVpn("GATEWAY"))
    }

    @Test
    fun `non-gateway routes require VPN`() {
        assertAll(
            { assertTrue(RdpManager.requiresVpn("internal")) },
            { assertTrue(RdpManager.requiresVpn("external")) },
            { assertTrue(RdpManager.requiresVpn("both")) },
            { assertTrue(RdpManager.requiresVpn("vpn")) }
        )
    }

    @Test
    fun `null access mode requires VPN (safe default)`() {
        assertTrue(RdpManager.requiresVpn(null))
    }
}
