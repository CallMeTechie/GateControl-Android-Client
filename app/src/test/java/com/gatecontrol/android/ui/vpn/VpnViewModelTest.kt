package com.gatecontrol.android.ui.vpn

import app.cash.turbine.test
import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.PermissionFlags
import com.gatecontrol.android.network.PermissionsResponse
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

    private val testDispatcher = UnconfinedTestDispatcher()

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

        every { settingsRepository.getKillSwitch() } returns flowOf(false)
        every { setupRepository.getServerUrl() } returns "https://gate.example.com"
        every { setupRepository.getPeerId() } returns 42
        every { apiClientProvider.getClient(any()) } returns apiClient
        every { tunnelManager.state } returns kotlinx.coroutines.flow.MutableStateFlow(TunnelState.Disconnected)
        every { tunnelManager.stats } returns kotlinx.coroutines.flow.MutableStateFlow(com.gatecontrol.android.tunnel.TunnelStats())

        viewModel = VpnViewModel(
            setupRepository = setupRepository,
            settingsRepository = settingsRepository,
            licenseRepository = licenseRepository,
            apiClientProvider = apiClientProvider,
            tunnelManager = tunnelManager,
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
        kotlinx.coroutines.yield()

        coVerify { tunnelManager.connect(any(), any(), any()) }
    }

    @Test
    fun `connect does nothing when config is empty`() = runTest {
        every { setupRepository.getWireGuardConfig() } returns ""

        viewModel.connect()
        kotlinx.coroutines.yield()

        coVerify(exactly = 0) { tunnelManager.connect(any(), any(), any()) }
    }

    @Test
    fun `disconnect calls tunnelManager disconnect`() = runTest {
        viewModel.disconnect()
        kotlinx.coroutines.yield()

        coVerify { tunnelManager.disconnect() }
    }

    @Test
    fun `toggleKillSwitch saves to settings`() = runTest {
        viewModel.toggleKillSwitch(true)
        kotlinx.coroutines.yield()

        coVerify { settingsRepository.setKillSwitch(true) }
    }

    @Test
    fun `toggleKillSwitch false saves false to settings`() = runTest {
        viewModel.toggleKillSwitch(false)
        kotlinx.coroutines.yield()

        coVerify { settingsRepository.setKillSwitch(false) }
    }

    @Test
    fun `loadPermissions updates license repository`() = runTest {
        val flags = PermissionFlags(
            services = true,
            traffic = true,
            dns = false,
            rdp = true,
        )
        coEvery { apiClient.getPermissions() } returns PermissionsResponse(
            ok = true,
            permissions = flags,
            scopes = listOf("services", "traffic", "rdp"),
        )

        viewModel.loadPermissions()
        kotlinx.coroutines.yield()

        verify {
            licenseRepository.updatePermissions(
                services = true,
                traffic = true,
                dns = false,
                rdp = true,
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
            kotlinx.coroutines.yield()

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
        kotlinx.coroutines.yield()

        coVerify(exactly = 0) { apiClient.getPermissions() }
    }
}
