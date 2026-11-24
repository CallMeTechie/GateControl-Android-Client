package com.gatecontrol.android.rdp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RdpConnectFailureTest {

    private fun body(error: String) = """{"ok":false,"error":"$error"}"""

    @Test
    fun `firewall timeout of a registered PC`() {
        assertEquals(RdpConnectFailure.FIREWALL_TIMEOUT, RdpConnectFailure.from(504, body("firewall_timeout")))
    }

    @Test
    fun `the PC refused or failed to open the firewall`() {
        assertEquals(RdpConnectFailure.FIREWALL_REFUSED, RdpConnectFailure.from(504, body("firewall_failed")))
    }

    @Test
    fun `a PC shared only with a code`() {
        assertEquals(RdpConnectFailure.CODE_REQUIRED, RdpConnectFailure.from(403, body("code_required")))
    }

    @Test
    fun `host offline and no vpn address`() {
        assertEquals(RdpConnectFailure.HOST_OFFLINE, RdpConnectFailure.from(503, body("Host is offline")))
        assertEquals(RdpConnectFailure.NO_VPN, RdpConnectFailure.from(400, body("no_vpn")))
    }

    @Test
    fun `other refusals keep the generic message`() {
        assertNull(RdpConnectFailure.from(403, body("Not authorized for this RDP route")))
        assertNull(RdpConnectFailure.from(503, body("Route is in maintenance window")))
        assertNull(RdpConnectFailure.from(500, "<html>Bad Gateway</html>"))
        assertNull(RdpConnectFailure.from(504, null))
        assertNull(RdpConnectFailure.from(504, ""))
    }

    @Test
    fun `error field is read with spaces and other fields around it`() {
        assertEquals("code_required", RdpConnectFailure.errorOf("""{ "ok" : false, "error" : "code_required", "x": 1 }"""))
        assertNull(RdpConnectFailure.errorOf("""{"ok":false}"""))
    }
}
