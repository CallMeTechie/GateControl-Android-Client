package com.gatecontrol.android.push

import com.gatecontrol.android.data.SetupRepository
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PushNavTargetTest {

    @Test
    fun `app routes of the contract map to screens`() {
        assertEquals(PushNavTarget.Vpn, PushNavTarget.forRoute("vpn", 5))
        assertEquals(PushNavTarget.Services, PushNavTarget.forRoute("services", 5))
        assertEquals(PushNavTarget.Inbox, PushNavTarget.forRoute("inbox", 5))
        // No screen for gateways and plugin tabs in the app: the message opens.
        assertEquals(PushNavTarget.Detail(5), PushNavTarget.forRoute("gateways", 5))
        assertEquals(PushNavTarget.Detail(5), PushNavTarget.forRoute("plg-skoda", 5))
        assertEquals(PushNavTarget.Inbox, PushNavTarget.forRoute(null, null))
    }

    @Test
    fun `navigation hand-over is consumed once`() {
        PushNavigation.request(PushNavTarget.Detail(9))
        assertEquals(PushNavTarget.Detail(9), PushNavigation.pending.value)
        PushNavigation.consume(PushNavTarget.Inbox)
        assertEquals(PushNavTarget.Detail(9), PushNavigation.pending.value)
        PushNavigation.consume(PushNavTarget.Detail(9))
        assertEquals(null, PushNavigation.pending.value)
    }

    @Test
    fun `portal paths stay on the portal's own origin`() {
        val r = PortalLinkResolver(mockk<SetupRepository>(relaxed = true), mockk(relaxed = true))
        val portal = "https://portal.example.com"
        assertEquals("https://portal.example.com/portal/notify", r.withPath(portal, "/portal/notify"))
        assertEquals(portal, r.withPath(portal, "//evil.example/x"))
        assertEquals(portal, r.withPath(portal, "https://evil.example"))
        assertEquals(portal, r.withPath(portal, null))
        assertEquals("https://portal.example.com:8443/a", r.withPath("https://portal.example.com:8443", "/a"))
    }

    @Test
    fun `plugin topics get a readable fallback label`() {
        assertEquals("Skoda · Charging", PushText.fallbackLabel("plugin:skoda:charging"))
        assertEquals("devices", PushText.fallbackLabel("devices"))
    }
}
