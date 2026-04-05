package com.gatecontrol.android.ui.vpn

import app.cash.turbine.test
import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.PermissionFlags
import com.gatecontrol.android.network.PermissionsResponse
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
import kotlinx.coroutines.test.advanceTimeBy
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
    private lateinit var viewModel: VpnViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        setupRepository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        licenseRepository = mockk(relaxed = true)
        apiClientProvider = mockk(relaxed = true)
        apiClient = mockk(relaxed = true)

        every { settingsRepository.getKillSwitch() } returns flowOf(false)
        every { setupRepository.getServerUrl() } returns "https://gate.example.com"
        every { setupRepository.getPeerId() } returns 42
        every { apiClientProvider.getClient(any()) } returns apiClient

        viewModel = VpnViewModel(
            setupRepository = setupRepository,
            settingsRepository = settingsRepository,
            licenseRepository = licenseRepository,
            apiClientProvider = apiClientProvider,
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
    fun `connect changes state to Connecting then Connected`() = runTest {
        viewModel.tunnelState.test {
            assertEquals(TunnelState.Disconnected, awaitItem())

            viewModel.connect()
            assertEquals(TunnelState.Connecting, awaitItem())

            advanceTimeBy(2_000)
            val connected = awaitItem()
            assertInstanceOf(TunnelState.Connected::class.java, connected)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `disconnect changes state to Disconnecting then Disconnected`() = runTest {
        // First connect
        viewModel.connect()
        advanceTimeBy(2_000)

        viewModel.tunnelState.test {
            // Skip the Connected state we're already in
            val current = awaitItem()
            assertInstanceOf(TunnelState.Connected::class.java, current)

            viewModel.disconnect()
            assertEquals(TunnelState.Disconnecting, awaitItem())

            advanceTimeBy(1_000)
            assertEquals(TunnelState.Disconnected, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggleKillSwitch saves to settings`() = runTest {
        viewModel.toggleKillSwitch(true)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { settingsRepository.setKillSwitch(true) }
    }

    @Test
    fun `toggleKillSwitch false saves false to settings`() = runTest {
        viewModel.toggleKillSwitch(false)
        testDispatcher.scheduler.advanceUntilIdle()

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
        testDispatcher.scheduler.advanceUntilIdle()

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
}
