package com.gatecontrol.android.push

import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.PortalLinkRequest
import com.gatecontrol.android.network.PortalLinkResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
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
        // The Familie plugin has its own tab.
        assertEquals(PushNavTarget.Familie("home", 5), PushNavTarget.forRoute("plg-gatecontrol-familie", 5))
        // The door plugin's door screen (no tab): "Ansehen" of a ring opens its detail.
        assertEquals(PushNavTarget.Door("home", 5), PushNavTarget.forRoute("plg-gatecontrol-door", 5))
        assertEquals(PushNavTarget.Door("event:120", 5), PushNavTarget.forRoute("plg-gatecontrol-door", 5, "event:120"))
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
    fun `open_portal targets go to the server as next of the login link`() = runBlocking {
        val setup = mockk<SetupRepository> {
            every { getPortalUrl() } returns "https://portal.example.com"
            every { getServerUrl() } returns "https://gc.example.com"
        }
        val api = mockk<ApiClient>()
        val sent = mutableListOf<PortalLinkRequest>()
        val body = slot<PortalLinkRequest>()
        coEvery { api.requestPortalLink(capture(body)) } answers {
            sent += body.captured
            retrofit2.Response.success(PortalLinkResponse(ok = true, url = "https://portal.example.com/auto?t=x"))
        }
        val provider = mockk<ApiClientProvider> { every { getClient(any()) } returns api }
        val r = PortalLinkResolver(setup, provider)

        assertEquals("https://portal.example.com/auto?t=x", r.resolve("/portal#mitteilungen"))
        r.resolve(PortalLinkResolver.NOTIFICATION_SETTINGS)
        r.resolve(null)
        assertEquals(listOf("/portal#mitteilungen", "/portal#benachrichtigungen", null), sent.map { it.next })
    }

    @Test
    fun `without a login link the target is opened on the plain portal`() = runBlocking {
        val setup = mockk<SetupRepository> {
            every { getPortalUrl() } returns "https://portal.example.com"
            every { getServerUrl() } returns "https://gc.example.com"
        }
        val api = mockk<ApiClient>()
        coEvery { api.requestPortalLink(any()) } returns retrofit2.Response.error(
            404, "{}".toResponseBody(null),
        )
        val provider = mockk<ApiClientProvider> { every { getClient(any()) } returns api }
        assertEquals(
            "https://portal.example.com/portal#benachrichtigungen",
            PortalLinkResolver(setup, provider).resolve(PortalLinkResolver.NOTIFICATION_SETTINGS),
        )
    }

    @Test
    fun `plugin topics get a readable fallback label`() {
        assertEquals("Skoda · Charging", PushText.fallbackLabel("plugin:skoda:charging"))
        assertEquals("devices", PushText.fallbackLabel("devices"))
    }
}
