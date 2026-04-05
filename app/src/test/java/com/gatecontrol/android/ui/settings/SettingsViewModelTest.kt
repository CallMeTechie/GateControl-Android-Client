package com.gatecontrol.android.ui.settings

import app.cash.turbine.test
import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.PingResponse
import com.gatecontrol.android.network.UpdateCheckResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var setupRepository: SetupRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var apiClientProvider: ApiClientProvider
    private lateinit var licenseRepository: LicenseRepository
    private lateinit var apiClient: ApiClient
    private lateinit var viewModel: SettingsViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        settingsRepository = mockk(relaxed = true) {
            every { getTheme() } returns flowOf("dark")
            every { getLocale() } returns flowOf("de")
            every { getAutoConnect() } returns flowOf(false)
            every { getKillSwitch() } returns flowOf(false)
            every { getSplitTunnelEnabled() } returns flowOf(false)
            every { getSplitTunnelRoutes() } returns flowOf("")
            every { getSplitTunnelApps() } returns flowOf("")
            every { getCheckInterval() } returns flowOf(30)
            every { getConfigPollInterval() } returns flowOf(300)
        }
        setupRepository = mockk {
            every { getServerUrl() } returns "https://gate.example.com"
            every { getApiToken() } returns "gc_testtoken"
            every { getPeerId() } returns 1
        }
        apiClient = mockk()
        apiClientProvider = mockk {
            every { getClient(any()) } returns apiClient
            every { invalidate() } returns Unit
        }
        licenseRepository = mockk()

        viewModel = SettingsViewModel(setupRepository, settingsRepository, apiClientProvider, licenseRepository)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `setTheme updates repository and ui state`() = runTest {
        // Drain init coroutines so they don't overwrite the state later
        testDispatcher.scheduler.advanceUntilIdle()

        coEvery { settingsRepository.setTheme(any()) } returns Unit

        viewModel.setTheme("light")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { settingsRepository.setTheme("light") }
        assertEquals("light", viewModel.uiState.value.theme)
    }

    @Test
    fun `setLocale updates repository`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        coEvery { settingsRepository.setLocale(any()) } returns Unit

        viewModel.setLocale("en")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { settingsRepository.setLocale("en") }
        assertEquals("en", viewModel.uiState.value.locale)
    }

    @Test
    fun `setAutoConnect updates repository`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        coEvery { settingsRepository.setAutoConnect(any()) } returns Unit

        viewModel.setAutoConnect(true)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { settingsRepository.setAutoConnect(true) }
        assertTrue(viewModel.uiState.value.autoConnect)
    }

    @Test
    fun `setKillSwitch updates repository`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        coEvery { settingsRepository.setKillSwitch(any()) } returns Unit

        viewModel.setKillSwitch(true)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { settingsRepository.setKillSwitch(true) }
        assertTrue(viewModel.uiState.value.killSwitch)
    }

    @Test
    fun `testConnection sets Success on ping ok`() = runTest {
        coEvery { apiClient.ping() } returns PingResponse(ok = true, version = "1.0.0", timestamp = "2026-04-05")

        viewModel.uiState.test {
            awaitItem() // initial state

            viewModel.testConnection()
            testDispatcher.scheduler.advanceUntilIdle()

            val testing = awaitItem()
            assertEquals(ConnectionTestStatus.Testing, testing.connectionTestStatus)

            val done = awaitItem()
            assertEquals(ConnectionTestStatus.Success, done.connectionTestStatus)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `testConnection sets Failure on exception`() = runTest {
        coEvery { apiClient.ping() } throws RuntimeException("Timeout")

        viewModel.testConnection()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ConnectionTestStatus.Failure, viewModel.uiState.value.connectionTestStatus)
    }

    @Test
    fun `testConnection sets Failure when server URL is blank`() = runTest {
        every { setupRepository.getServerUrl() } returns ""
        viewModel = SettingsViewModel(setupRepository, settingsRepository, apiClientProvider, licenseRepository)

        viewModel.testConnection()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ConnectionTestStatus.Failure, viewModel.uiState.value.connectionTestStatus)
    }

    @Test
    fun `saveSplitRoutes filters invalid CIDRs`() = runTest {
        coEvery { settingsRepository.setSplitTunnelRoutes(any()) } returns Unit

        // Mix of valid and invalid CIDRs
        val input = "10.0.0.0/8\nnot-a-cidr\n192.168.0.0/16\n999.999.999.999/33"
        viewModel.saveSplitRoutes(input)
        testDispatcher.scheduler.advanceUntilIdle()

        val savedRoutes = viewModel.uiState.value.splitTunnelRoutes
        val lines = savedRoutes.lines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        assertTrue(lines.contains("10.0.0.0/8"))
        assertTrue(lines.contains("192.168.0.0/16"))
    }

    @Test
    fun `saveSplitRoutes with all invalid CIDRs saves empty`() = runTest {
        coEvery { settingsRepository.setSplitTunnelRoutes(any()) } returns Unit

        viewModel.saveSplitRoutes("bad\nalso-bad\n999/33")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.splitTunnelRoutes.isBlank())
    }

    @Test
    fun `checkForUpdate sets updateInfo on success`() = runTest {
        val updateResponse = UpdateCheckResponse(
            ok = true,
            available = true,
            version = "2.0.0",
            downloadUrl = "https://example.com/release",
            fileName = "gatecontrol.apk",
            fileSize = 10_000_000L,
            releaseNotes = "New features"
        )
        coEvery { apiClient.checkUpdate(any(), any(), any()) } returns updateResponse

        viewModel.checkForUpdate("1.0.0")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.updateInfo)
        assertEquals("2.0.0", state.updateInfo?.version)
        assertTrue(state.updateInfo?.available == true)
    }

    @Test
    fun `checkForUpdate sets error on exception`() = runTest {
        coEvery { apiClient.checkUpdate(any(), any(), any()) } throws RuntimeException("Network error")

        viewModel.checkForUpdate("1.0.0")
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.updateInfo)
        assertNotNull(viewModel.uiState.value.error)
    }

    @Test
    fun `saveServer rejects invalid URL`() = runTest {
        viewModel.saveServer("not-a-url", "gc_validtoken")
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.error)
    }

    @Test
    fun `saveServer rejects invalid API token`() = runTest {
        viewModel.saveServer("https://gate.example.com", "invalidtoken")
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.error)
    }
}
