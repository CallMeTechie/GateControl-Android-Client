package com.gatecontrol.android.ui.vpn

import com.gatecontrol.android.common.SplitTunnelMode
import app.cash.turbine.test
import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.PermissionFlags
import com.gatecontrol.android.network.PermissionsResponse
import com.gatecontrol.android.network.SplitTunnelPresetResponse
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VpnViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var setupRepository: SetupRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var licenseRepository: LicenseRepository
    private lateinit var apiClientProvider: ApiClientProvider
    private lateinit var apiClient: ApiClient
    private lateinit var tunnelManager: TunnelManager
    private lateinit var viewModel: VpnViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        setupRepository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        licenseRepository = mockk(relaxed = true)
        apiClientProvider = mockk(relaxed = true)
        apiClient = mockk(relaxed = true)
        tunnelManager = mockk(relaxed = true)

        every { settingsRepository.getSplitTunnelEnabled() } returns flowOf(false)
        every { settingsRepository.getSplitTunnelRoutes() } returns flowOf("")
        every { settingsRepository.getSplitTunnelApps() } returns flowOf("")
        every { settingsRepository.getSplitTunnelMode() } returns flowOf(SplitTunnelMode.OFF)
        every { settingsRepository.getSplitTunnelNetworks() } returns flowOf("[]")
        every { settingsRepository.getSplitTunnelAppsV2() } returns flowOf("[]")
        every { setupRepository.getServerUrl() } returns "https://gate.example.com"
        every { setupRepository.getPeerId() } returns 42
        coEvery { apiClient.getSplitTunnelPreset() } returns SplitTunnelPresetResponse(
            ok = true, mode = "off", networks = emptyList(), locked = false, source = "none"
        )
        every { apiClientProvider.getClient(any()) } returns apiClient
        every { tunnelManager.state } returns kotlinx.coroutines.flow.MutableStateFlow(TunnelState.Disconnected)
        every { tunnelManager.stats } returns kotlinx.coroutines.flow.MutableStateFlow(com.gatecontrol.android.tunnel.TunnelStats())

        viewModel = VpnViewModel(
            setupRepository = setupRepository,
            settingsRepository = settingsRepository,
            licenseRepository = licenseRepository,
            apiClientProvider = apiClientProvider,
            tunnelManager = tunnelManager,
            // Real connector on the same mocks: connect() must go through the shared path.
            tunnelConnector = com.gatecontrol.android.service.TunnelConnector(
                setupRepository, settingsRepository, apiClientProvider, tunnelManager,
                com.gatecontrol.android.service.fakeClientPolicyManager(),
            ),
            clientPolicyManager = com.gatecontrol.android.service.fakeClientPolicyManager(),
            machineBindingMonitor = com.gatecontrol.android.network.MachineBindingMonitor(),
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- State tests ---

    @Test
    fun `initial state is Disconnected`() = runTest {
        viewModel.tunnelState.test {
            assertInstanceOf(TunnelState.Disconnected::class.java, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `connect calls tunnelManager with config`() = runTest {
        every { setupRepository.getWireGuardConfig() } returns "[Interface]\nPrivateKey=abc\nAddress=10.0.0.1/32\n\n[Peer]\nPublicKey=xyz\nEndpoint=1.2.3.4:51820\nAllowedIPs=0.0.0.0/0"

        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { tunnelManager.connect(any(), any<com.gatecontrol.android.tunnel.SplitTunnelConfig>()) }
    }

    @Test
    fun `connect does nothing when config is empty`() = runTest {
        every { setupRepository.getWireGuardConfig() } returns ""

        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { tunnelManager.connect(any(), any<com.gatecontrol.android.tunnel.SplitTunnelConfig>()) }
    }

    // --- validateToken ---

    private fun httpError(code: Int): retrofit2.HttpException = retrofit2.HttpException(
        retrofit2.Response.error<Any>(
            code,
            "{\"ok\":false}".toResponseBody(null),
        ),
    )

    @Test
    fun `validateToken clears setup only on 401`() = runTest {
        every { setupRepository.getApiToken() } returns "gc_token"
        coEvery { apiClient.ping() } throws httpError(401)

        viewModel.validateToken()
        testDispatcher.scheduler.advanceUntilIdle()

        verify { setupRepository.clear() }
        assertTrue(viewModel.tokenInvalid.value)
    }

    @Test
    fun `validateToken keeps setup on 403 from WAF, proxy or missing scope`() = runTest {
        every { setupRepository.getApiToken() } returns "gc_token"
        coEvery { apiClient.ping() } throws httpError(403)

        viewModel.validateToken()
        testDispatcher.scheduler.advanceUntilIdle()

        verify(exactly = 0) { setupRepository.clear() }
        assertFalse(viewModel.tokenInvalid.value)
    }

    @Test
    fun `validateToken keeps setup on network errors`() = runTest {
        every { setupRepository.getApiToken() } returns "gc_token"
        coEvery { apiClient.ping() } throws java.io.IOException("offline")

        viewModel.validateToken()
        testDispatcher.scheduler.advanceUntilIdle()

        verify(exactly = 0) { setupRepository.clear() }
        assertFalse(viewModel.tokenInvalid.value)
    }

    @Test
    fun `disconnect calls tunnelManager disconnect`() = runTest {
        viewModel.disconnect()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { tunnelManager.disconnect() }
    }

    @Test
    fun `loadPermissions updates license repository`() = runTest {
        val flags = PermissionFlags(
            services = true,
            traffic = true,
            dns = false,
            rdp = true,
            pihole = true,
            piholeControl = true,
        )
        coEvery { apiClient.getPermissions() } returns PermissionsResponse(
            ok = true,
            permissions = flags,
            scopes = listOf("services", "traffic", "rdp"),
        )

        viewModel.loadPermissions()
        testDispatcher.scheduler.advanceUntilIdle()

        verify {
            licenseRepository.updatePermissions(
                services = true,
                traffic = true,
                dns = false,
                rdp = true,
                pihole = true,
                piholeControl = true,
            )
        }
    }

    @Test
    fun `loadPermissions updates permissions state flow`() = runTest {
        val flags = PermissionFlags(
            services = true,
            traffic = false,
            dns = true,
            rdp = false,
        )
        coEvery { apiClient.getPermissions() } returns PermissionsResponse(
            ok = true,
            permissions = flags,
            scopes = listOf("services", "dns"),
        )

        viewModel.permissions.test {
            val initial = awaitItem()
            assertFalse(initial.services)

            viewModel.loadPermissions()
            testDispatcher.scheduler.advanceUntilIdle()

            val updated = awaitItem()
            assertTrue(updated.services)
            assertTrue(updated.dns)
            assertFalse(updated.traffic)
            assertFalse(updated.rdp)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadPermissions does nothing when server URL is empty`() = runTest {
        every { setupRepository.getServerUrl() } returns ""

        viewModel.loadPermissions()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { apiClient.getPermissions() }
    }

    @Test
    fun `always-on client policy refuses the in-app disconnect`() = runTest {
        val vm = VpnViewModel(
            setupRepository = setupRepository,
            settingsRepository = settingsRepository,
            licenseRepository = licenseRepository,
            apiClientProvider = apiClientProvider,
            tunnelManager = tunnelManager,
            tunnelConnector = com.gatecontrol.android.service.TunnelConnector(
                setupRepository, settingsRepository, apiClientProvider, tunnelManager,
                com.gatecontrol.android.service.fakeClientPolicyManager(),
            ),
            clientPolicyManager = com.gatecontrol.android.service.fakeClientPolicyManager(
                com.gatecontrol.android.common.ClientPolicy(autoConnect = com.gatecontrol.android.common.ClientPolicy.AutoConnect.ALWAYS_ON),
            ),
            machineBindingMonitor = com.gatecontrol.android.network.MachineBindingMonitor(),
        )
        vm.disconnect()
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) { tunnelManager.disconnect() }
    }

    // --- Portal auto-login ---

    private val portal = "https://portal.example.com"
    private val opened = mutableListOf<String>()
    private val context = mockk<android.content.Context>(relaxed = true)

    private fun enablePortal(autoOpen: Boolean = true) {
        coEvery { apiClient.getPermissions() } returns PermissionsResponse(
            ok = true,
            permissions = PermissionFlags(services = false, traffic = false, dns = false, rdp = false),
            scopes = emptyList(),
            portalUrl = portal,
            autoOpenPortal = autoOpen,
        )
        viewModel.loadPermissions()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.portalOpener = { _, url -> opened += url }
    }

    private fun portalLink(url: String?) = retrofit2.Response.success(
        com.gatecontrol.android.network.PortalLinkResponse(ok = true, url = url, expiresIn = 60),
    )

    @Test
    fun `openPortal opens the one-time login link`() = runTest {
        enablePortal()
        coEvery { apiClient.requestPortalLink() } returns portalLink("$portal/auto?t=ticket1")

        viewModel.openPortal(context)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("$portal/auto?t=ticket1"), opened)
    }

    @Test
    fun `openPortal fetches a fresh link on every tap`() = runTest {
        enablePortal()
        coEvery { apiClient.requestPortalLink() } returnsMany listOf(
            portalLink("$portal/auto?t=a"),
            portalLink("$portal/auto?t=b"),
        )

        viewModel.openPortal(context)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.openPortal(context)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("$portal/auto?t=a", "$portal/auto?t=b"), opened)
        coVerify(exactly = 2) { apiClient.requestPortalLink() }
    }

    @Test
    fun `openPortal falls back to the portal URL on 404`() = runTest {
        enablePortal()
        coEvery { apiClient.requestPortalLink() } returns retrofit2.Response.error(
            404, "{}".toResponseBody(null),
        )

        viewModel.openPortal(context)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(portal), opened)
    }

    @Test
    fun `openPortal falls back to the portal URL after the 5 s timeout`() = runTest {
        enablePortal()
        coEvery { apiClient.requestPortalLink() } coAnswers {
            kotlinx.coroutines.delay(30_000)
            portalLink("$portal/auto?t=late")
        }

        viewModel.openPortal(context)
        testDispatcher.scheduler.advanceTimeBy(4_900)
        testDispatcher.scheduler.runCurrent()
        assertTrue(opened.isEmpty())
        testDispatcher.scheduler.advanceTimeBy(200)
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf(portal), opened)
    }

    @Test
    fun `openPortal falls back to the portal URL on host mismatch`() = runTest {
        enablePortal()
        coEvery { apiClient.requestPortalLink() } returns portalLink("https://evil.example.com/auto?t=x")

        viewModel.openPortal(context)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(portal), opened)
    }

    @Test
    fun `openPortal falls back to the portal URL on network error`() = runTest {
        enablePortal()
        coEvery { apiClient.requestPortalLink() } throws java.io.IOException("offline")

        viewModel.openPortal(context)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(portal), opened)
    }

    @Test
    fun `openPortal ignores a second tap while the link is loading`() = runTest {
        enablePortal()
        coEvery { apiClient.requestPortalLink() } coAnswers {
            kotlinx.coroutines.delay(1_000)
            portalLink("$portal/auto?t=x")
        }

        viewModel.openPortal(context)
        viewModel.openPortal(context)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, opened.size)
        coVerify(exactly = 1) { apiClient.requestPortalLink() }
    }

    @Test
    fun `auto-open calls the endpoint once per tunnel session`() = runTest {
        enablePortal()
        coEvery { apiClient.requestPortalLink() } returns portalLink("$portal/auto?t=x")
        val session = TunnelState.Connected(connectedSince = 1_000L)

        viewModel.autoOpenPortalIfNeeded(context, session)
        testDispatcher.scheduler.advanceUntilIdle()
        // Recomposition / re-foreground on the same session
        viewModel.autoOpenPortalIfNeeded(context, session)
        viewModel.autoOpenPortalIfNeeded(context, TunnelState.Connected(connectedSince = 1_000L))
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { apiClient.requestPortalLink() }
        assertEquals(1, opened.size)

        // A new connect is a new session
        viewModel.autoOpenPortalIfNeeded(context, TunnelState.Connected(connectedSince = 2_000L))
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 2) { apiClient.requestPortalLink() }
    }

    @Test
    fun `auto-open does nothing when disabled or not connected`() = runTest {
        enablePortal(autoOpen = false)

        viewModel.autoOpenPortalIfNeeded(context, TunnelState.Connected(connectedSince = 1_000L))
        viewModel.autoOpenPortalIfNeeded(context, TunnelState.Disconnected)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { apiClient.requestPortalLink() }
        assertTrue(opened.isEmpty())
    }
}
