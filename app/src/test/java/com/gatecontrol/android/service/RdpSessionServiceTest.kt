package com.gatecontrol.android.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RdpSessionServiceTest {

    @Test
    fun `CHANNEL_ID is rdp_session`() {
        assertEquals("rdp_session", RdpSessionService.CHANNEL_ID)
    }

    @Test
    fun `NOTIF_ID is distinct from VPN notification`() {
        val rdpNotifId = RdpSessionService.NOTIF_ID
        val vpnNotifId = VpnForegroundService.NOTIF_ID
        assert(rdpNotifId != vpnNotifId) { "RDP and VPN notification IDs must be different" }
    }

    @Test
    fun `ACTION_DISCONNECT is namespaced`() {
        assertEquals(
            "com.gatecontrol.android.ACTION_RDP_DISCONNECT",
            RdpSessionService.ACTION_DISCONNECT
        )
    }
}
