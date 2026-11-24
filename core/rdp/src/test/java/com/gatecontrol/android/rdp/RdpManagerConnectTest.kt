package com.gatecontrol.android.rdp

import android.content.Context
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.RdpConnectResponse
import com.gatecontrol.android.network.RdpEndSessionRequest
import com.gatecontrol.android.network.RdpRoute
import com.gatecontrol.android.network.RdpRouteStatus
import com.gatecontrol.android.network.RdpRouteStatusResponse
import com.gatecontrol.android.network.RdpSessionResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.HttpException
import retrofit2.Response

/** A PC registered with Fernzugriff: /connect opens its firewall, so it is asked before every session. */
class RdpManagerConnectTest {

    private val context = mockk<Context>(relaxed = true)
    private val credentials = mockk<RdpCredentialHandler>(relaxed = true)
    private val external = mockk<RdpExternalClient>(relaxed = true)
    private val embedded = mockk<RdpEmbeddedClient>(relaxed = true)
    private val monitor = mockk<RdpMonitor>(relaxed = true)
    private val wol = mockk<WolClient>(relaxed = true)
    private val api = mockk<ApiClient>()
    private lateinit var manager: RdpManager

    private fun route(credentialMode: String = "none") = RdpRoute(
        id = 7, name = "Arbeits-PC", host = "10.8.0.20", port = 3389,
        externalHostname = null, externalPort = null,
        accessMode = "internal", credentialMode = credentialMode, domain = null,
        resolutionMode = null, resolutionWidth = 1280, resolutionHeight = 800,
        multiMonitor = null, colorDepth = null, redirectClipboard = null,
        redirectPrinters = null, redirectDrives = null, audioMode = null,
        networkProfile = null, sessionTimeout = null, adminSession = null,
        wolEnabled = false, maintenanceEnabled = false,
        status = RdpRouteStatus(online = true, lastCheck = null),
    )

    private fun refused(status: Int, error: String) = HttpException(
        Response.error<Any>(status, """{"ok":false,"error":"$error"}""".toResponseBody(null))
    )

    @BeforeEach
    fun setUp() {
        manager = RdpManager(context, credentials, external, embedded, monitor, wol)
        every { embedded.isAvailable() } returns true
        coEvery { api.getRdpRouteStatus(7) } returns RdpRouteStatusResponse(ok = true, status = RdpRouteStatus(true, null))
        coEvery { api.startRdpSession(7) } returns RdpSessionResponse(
            ok = true,
            session = com.gatecontrol.android.network.RdpSession(id = 42, routeId = 7, startedAt = "2026-01-01T00:00:00Z", endedAt = null),
        )
    }

    @Test
    fun `without credentials connect is still asked, before the client starts`() = runTest {
        coEvery { api.getRdpConnection(7, null) } returns mockk<RdpConnectResponse>(relaxed = true)

        val result = manager.connect(route(), api, isVpnConnected = true)

        assertTrue(result is RdpManager.ConnectResult.Success, "got $result")
        // the server session first: the window gets its id and reports the end with it
        coVerifyOrder {
            api.getRdpConnection(7, null)
            api.startRdpSession(7)
            embedded.launchSession(context, match { it.serverSessionId == 42 })
        }
        // no credentials: no key exchange
        verify(exactly = 0) { credentials.generatePublicKey() }
    }

    @Test
    fun `firewall timeout stops before the session with its failure`() = runTest {
        coEvery { api.getRdpConnection(7, null) } throws refused(504, "firewall_timeout")

        val result = manager.connect(route(), api, isVpnConnected = true)

        assertTrue(result is RdpManager.ConnectResult.Error, "got $result")
        result as RdpManager.ConnectResult.Error
        assertEquals(RdpConnectFailure.FIREWALL_TIMEOUT, result.failure)
        assertEquals(RdpProgress.HOST_ACCESS, result.step)
        verify(exactly = 0) { embedded.launchSession(any(), any()) }
        coVerify(exactly = 0) { api.startRdpSession(any()) }
    }

    @Test
    fun `waiting for the PC is its own step, credentials only with credentials`() = runTest {
        coEvery { api.getRdpConnection(7, null) } returns mockk<RdpConnectResponse>(relaxed = true)
        val steps = mutableListOf<RdpProgress>()

        manager.connect(route(), api, isVpnConnected = true, onProgress = { steps += it })

        assertEquals(
            listOf(
                RdpProgress.VPN_CHECK, RdpProgress.TCP_CHECK, RdpProgress.HOST_ACCESS,
                RdpProgress.SESSION_START, RdpProgress.CLIENT_LAUNCH, RdpProgress.COMPLETE,
            ),
            steps,
        )
        // the sheet orders the steps by number: the same order as they run
        assertEquals(steps.sortedBy { it.step }, steps)
    }

    @Test
    fun `a refused connect is reported at the step waiting for the PC`() = runTest {
        coEvery { api.getRdpConnection(7, null) } throws refused(504, "firewall_timeout")
        val result = manager.connect(route(), api, isVpnConnected = true) as RdpManager.ConnectResult.Error
        assertEquals(RdpProgress.HOST_ACCESS, result.step)
    }

    @Test
    fun `client launch fails after connect - the session ends at once`() = runTest {
        coEvery { api.getRdpConnection(7, null) } returns mockk<RdpConnectResponse>(relaxed = true)
        every { embedded.launchSession(any(), any()) } throws IllegalStateException("no activity")
        coEvery { api.endRdpSession(7, any()) } returns RdpSessionResponse(ok = true, session = null)

        val result = manager.connect(route(), api, isVpnConnected = true)

        assertTrue(result is RdpManager.ConnectResult.Error, "got $result")
        coVerify { api.endRdpSession(7, RdpEndSessionRequest(sessionId = 42, endReason = RdpManager.END_LAUNCH_FAILED)) }
        verify(exactly = 0) { monitor.startSession(any(), any(), any(), any()) }
    }

    @Test
    fun `endServerSession reports the end and forgets the session`() = runTest {
        coEvery { api.endRdpSession(7, any()) } returns RdpSessionResponse(ok = true, session = null)

        assertTrue(manager.endServerSession(api, 7, 42, RdpManager.END_CONNECT_TIMEOUT))

        coVerify { api.endRdpSession(7, RdpEndSessionRequest(sessionId = 42, endReason = "connect_timeout")) }
        verify { monitor.endSession(7) }
    }

    @Test
    fun `endServerSession without a known id lets the server pick, and survives errors`() = runTest {
        coEvery { api.endRdpSession(7, any()) } throws refused(400, "No active session found")

        assertFalse(manager.endServerSession(api, 7, 0, RdpManager.END_USER_CANCEL))

        coVerify { api.endRdpSession(7, RdpEndSessionRequest(sessionId = null, endReason = "user_cancel")) }
    }

    @Test
    fun `code required and refusal at the PC are told apart`() = runTest {
        coEvery { api.getRdpConnection(7, null) } throws refused(403, "code_required")
        val code = manager.connect(route(), api, isVpnConnected = true) as RdpManager.ConnectResult.Error
        assertEquals(RdpConnectFailure.CODE_REQUIRED, code.failure)

        coEvery { api.getRdpConnection(7, null) } throws refused(504, "firewall_failed")
        val refused = manager.connect(route(), api, isVpnConnected = true) as RdpManager.ConnectResult.Error
        assertEquals(RdpConnectFailure.FIREWALL_REFUSED, refused.failure)
    }

    @Test
    fun `an unknown refusal keeps the generic message`() = runTest {
        coEvery { api.getRdpConnection(7, null) } throws refused(403, "Not authorized for this RDP route")

        val result = manager.connect(route(), api, isVpnConnected = true) as RdpManager.ConnectResult.Error

        assertEquals(null, result.failure)
        assertTrue(result.message.contains("403"), result.message)
    }

    @Test
    fun `with credentials the key is sent along and a refusal is classified too`() = runTest {
        every { credentials.generatePublicKey() } returns "cHVibGljLWtleQ=="
        coEvery { api.getRdpConnection(7, "cHVibGljLWtleQ==") } throws refused(504, "firewall_timeout")

        val result = manager.connect(route("full"), api, isVpnConnected = true) as RdpManager.ConnectResult.Error

        assertEquals(RdpConnectFailure.FIREWALL_TIMEOUT, result.failure)
        verify { credentials.clear() }
    }
}
