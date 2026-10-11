package com.gatecontrol.android.service

import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.HeartbeatResponse
import com.gatecontrol.android.notify.model.ServerControl
import com.gatecontrol.android.notify.model.ServerState
import com.gatecontrol.android.support.SupportRequestHolder
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerControlHandlerTest {

    private val policy: ClientPolicyManager = mockk(relaxed = true)
    private val tunnelState = MutableStateFlow<TunnelState>(TunnelState.Connected(connectedSince = 1L))
    private val tunnelManager: TunnelManager = mockk(relaxed = true) {
        every { state } returns tunnelState
        coEvery { disconnect() } answers { tunnelState.value = TunnelState.Disconnected }
    }
    private val connector: TunnelConnector = mockk(relaxed = true) {
        coEvery { syncConfigFromServer() } returns true
    }
    private val setup: SetupRepository = mockk(relaxed = true) {
        every { getConfigHash() } returns "hash-1"
        every { getPeerId() } returns 42
    }
    private val api: ApiClientProvider = mockk(relaxed = true)

    private fun TestScope.handler() = ServerControlHandler(policy, tunnelManager, connector, setup, api).also { it.scope = this }

    @BeforeEach
    fun setUp() {
        SupportRequestHolder.clear()
        PeerStateHolder.clear()
    }

    @AfterEach
    fun tearDown() {
        SupportRequestHolder.clear()
        PeerStateHolder.clear()
    }

    // ─── control events ─────────────────────────────────────────────────

    @Test
    fun `policy_changed refreshes the client policy right away unless already cached`() = runTest {
        handler().onControl(ServerControl.PolicyChanged("3f2a9c0b7d1e4a65"))
        verify(exactly = 1) { policy.refreshUnlessCurrentAsync("3f2a9c0b7d1e4a65") }
        handler().onControl(ServerControl.PolicyChanged(null))
        verify(exactly = 1) { policy.refreshUnlessCurrentAsync(null) }
    }

    @Test
    fun `support_bundle_requested shows the request in Settings`() = runTest {
        handler().onControl(ServerControl.SupportBundleRequested("2026-10-11 08:15:00"))
        assertEquals("2026-10-11 08:15:00", SupportRequestHolder.request.value)
        handler().onControl(ServerControl.SupportBundleRequested(null))
        assertEquals("requested", SupportRequestHolder.request.value)
    }

    @Test
    fun `peer_state disabled closes the tunnel and shows the notice`() = runTest {
        handler().onControl(ServerControl.PeerState(false))
        advanceUntilIdle()
        coVerify(exactly = 1) { tunnelManager.disconnect() }
        verify(exactly = 1) { api.clearDnsCache() }
        assertTrue(PeerStateHolder.disabled.value)
    }

    @Test
    fun `peer_state enabled or a tunnel that is already down change nothing`() = runTest {
        val h = handler()
        h.onControl(ServerControl.PeerState(true))
        advanceUntilIdle()
        tunnelState.value = TunnelState.Disconnected
        h.onControl(ServerControl.PeerState(false))
        advanceUntilIdle()
        coVerify(exactly = 0) { tunnelManager.disconnect() }
        assertFalse(PeerStateHolder.disabled.value)
    }

    @Test
    fun `config_changed runs the config check`() = runTest {
        handler().onControl(ServerControl.ConfigChanged)
        advanceUntilIdle()
        coVerify(exactly = 1) { connector.syncConfigFromServer() }
    }

    // ─── hello.state reconcile ──────────────────────────────────────────

    @Test
    fun `hello state reconciles every field`() = runTest {
        val h = handler()
        h.onServerState(
            ServerState(
                policyVersion = "00000000000000a5",
                peerEnabled = true,
                supportBundleRequested = true,
                supportBundleRequestedAt = "2026-10-11 08:15:00",
                configHash = "hash-2",
            ),
        )
        advanceUntilIdle()
        verify(exactly = 1) { policy.refreshUnlessCurrentAsync("00000000000000a5") }
        assertEquals("2026-10-11 08:15:00", SupportRequestHolder.request.value)
        coVerify(exactly = 1) { connector.syncConfigFromServer() }
        coVerify(exactly = 0) { tunnelManager.disconnect() }
    }

    @Test
    fun `hello state with the known config hash and policy version does nothing again`() = runTest {
        val h = handler()
        h.onServerState(ServerState(policyVersion = "00000000000000a5", configHash = "hash-1"))
        h.onServerState(ServerState(policyVersion = "00000000000000a5", configHash = "hash-1"))
        advanceUntilIdle()
        verify(exactly = 1) { policy.refreshUnlessCurrentAsync("00000000000000a5") }
        coVerify(exactly = 0) { connector.syncConfigFromServer() }
        // A pushed policy_changed counts as known as well.
        h.onControl(ServerControl.PolicyChanged("00000000000000a6"))
        h.onServerState(ServerState(policyVersion = "00000000000000a6"))
        verify(exactly = 1) { policy.refreshUnlessCurrentAsync("00000000000000a6") }
    }

    @Test
    fun `hello state clears an answered support request and closes a disabled peer`() = runTest {
        SupportRequestHolder.update(true, "2026-10-01 10:00:00")
        handler().onServerState(ServerState(peerEnabled = false, supportBundleRequested = false, configHash = "hash-2"))
        advanceUntilIdle()
        assertNull(SupportRequestHolder.request.value)
        coVerify(exactly = 1) { tunnelManager.disconnect() }
        assertTrue(PeerStateHolder.disabled.value)
        // No config check for a peer that was just switched off.
        coVerify(exactly = 0) { connector.syncConfigFromServer() }
    }

    @Test
    fun `hello state with missing fields (older or partial server) changes nothing`() = runTest {
        SupportRequestHolder.update(true, "2026-10-01 10:00:00")
        handler().onServerState(ServerState())
        advanceUntilIdle()
        verify(exactly = 0) { policy.refreshUnlessCurrentAsync(any()) }
        verify(exactly = 0) { policy.refreshAsync() }
        assertEquals("2026-10-01 10:00:00", SupportRequestHolder.request.value)
        coVerify(exactly = 0) { tunnelManager.disconnect() }
        coVerify(exactly = 0) { connector.syncConfigFromServer() }
    }

    @Test
    fun `token without a bound peer ignores peer_enabled false`() = runTest {
        every { setup.getPeerId() } returns 0
        val h = handler()
        h.onServerState(
            ServerState(policyVersion = "0123456789abcdef", peerEnabled = false, supportBundleRequested = false, supportBundleRequestedAt = null, configHash = null),
        )
        h.onControl(ServerControl.PeerState(false))
        advanceUntilIdle()
        coVerify(exactly = 0) { tunnelManager.disconnect() }
        assertFalse(PeerStateHolder.disabled.value)
        coVerify(exactly = 0) { connector.syncConfigFromServer() }
    }

    // ─── heartbeat (fallback) uses the same paths ───────────────────────

    @Test
    fun `heartbeat answer drives policy, support request and peer state`() = runTest {
        val h = handler()
        h.onHeartbeat(
            HeartbeatResponse(ok = true, peerEnabled = false, policyVersion = "abc", supportBundleRequested = true, supportBundleRequestedAt = "2026-10-11 08:15:00"),
        )
        verify(exactly = 1) { policy.noteVersionAsync("abc") }
        assertEquals("2026-10-11 08:15:00", SupportRequestHolder.request.value)
        coVerify(exactly = 1) { tunnelManager.disconnect() }
        assertTrue(PeerStateHolder.disabled.value)
    }

    @Test
    fun `failed heartbeat answer only notes the policy version`() = runTest {
        handler().onHeartbeat(HeartbeatResponse(ok = false, peerEnabled = false, policyVersion = "abc"))
        verify(exactly = 1) { policy.noteVersionAsync("abc") }
        coVerify(exactly = 0) { tunnelManager.disconnect() }
    }
}
