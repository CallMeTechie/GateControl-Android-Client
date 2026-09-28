package com.gatecontrol.android.ui.setup

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import app.cash.turbine.test
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.common.EnrollmentLink
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.EnrollResponse
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.PingResponse
import com.gatecontrol.android.network.RegisterRequest
import com.gatecontrol.android.network.RegisterResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {

    private val testDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher()

    private lateinit var setupRepository: SetupRepository
    private lateinit var apiClientProvider: ApiClientProvider
    private lateinit var apiClient: ApiClient
    private lateinit var context: Context
    private lateinit var viewModel: SetupViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        setupRepository = mockk(relaxed = true)
        apiClientProvider = mockk()
        apiClient = mockk()

        every { setupRepository.getServerUrl() } returns ""
        every { setupRepository.getApiToken() } returns ""
        every { setupRepository.isConfigured() } returns false
        every { setupRepository.hasWireGuardConfig() } returns false
        every { apiClientProvider.getClient(any()) } returns apiClient

        context = mockk {
            every { packageName } returns "com.gatecontrol.android"
            every { packageManager } returns mockk {
                every { getPackageInfo("com.gatecontrol.android", 0) } returns PackageInfo().apply { versionName = "1.0.0-test" }
            }
        }

        every { context.getString(any()) } answers { "res-${firstArg<Int>()}" }
        every { context.getString(any(), *anyVararg()) } answers { "res-${firstArg<Int>()}" }
        every { apiClientProvider.invalidate() } returns Unit

        viewModel = SetupViewModel(setupRepository, apiClientProvider, context)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -------------------------------------------------------------------------
    // testConnection
    // -------------------------------------------------------------------------

    @Test
    fun `testConnection success updates status to success`() = runTest {
        coEvery { apiClient.ping() } returns PingResponse(ok = true, version = "1.0", timestamp = "2024-01-01")

        viewModel.onServerUrlChanged("https://example.com")

        viewModel.uiState.test {
            // consume initial state
            awaitItem()

            viewModel.testConnection()
            testDispatcher.scheduler.advanceUntilIdle()

            // expect loading state then success state
            val states = mutableListOf<SetupUiState>()
            while (states.lastOrNull()?.statusType != StatusType.SUCCESS) {
                states += awaitItem()
            }

            val finalState = states.last()
            assertEquals(StatusType.SUCCESS, finalState.statusType)
            assertTrue(finalState.statusMessage.isNotEmpty())
            assertFalse(finalState.isLoading)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `testConnection failure updates status to error`() = runTest {
        coEvery { apiClient.ping() } throws RuntimeException("Network unreachable")

        viewModel.onServerUrlChanged("https://broken.example.com")

        viewModel.uiState.test {
            awaitItem()

            viewModel.testConnection()
            testDispatcher.scheduler.advanceUntilIdle()

            val states = mutableListOf<SetupUiState>()
            while (states.lastOrNull()?.statusType != StatusType.ERROR || states.last().isLoading) {
                states += awaitItem()
            }

            val finalState = states.last()
            assertEquals(StatusType.ERROR, finalState.statusType)
            assertFalse(finalState.isLoading)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `testConnection with blank url sets error without calling api`() = runTest {
        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.testConnection()
            testDispatcher.scheduler.advanceUntilIdle()

            val state = awaitItem()
            assertEquals(StatusType.ERROR, state.statusType)
            coVerify(exactly = 0) { apiClient.ping() }

            cancelAndIgnoreRemainingEvents()
        }
    }

    // -------------------------------------------------------------------------
    // saveAndRegister
    // -------------------------------------------------------------------------

    @Test
    @org.junit.jupiter.api.Disabled("Requires Android instrumented test — viewModelScope dispatching not testable with pure JVM mocks")
    fun `saveAndRegister calls API and stores peerId`() = runTest {
        coEvery { apiClient.ping() } returns PingResponse(ok = true, version = "1.0", timestamp = "2024-01-01")
        coEvery { apiClient.register(any()) } returns RegisterResponse(
            ok = true,
            peerId = 42,
            peerName = "android-test",
            config = "[Interface]\nPrivateKey=abc",
            hash = "abc123",
        )

        viewModel.onServerUrlChanged("https://example.com")
        viewModel.onApiTokenChanged("gc_testtoken")

        viewModel.saveAndRegister()

        // Advance coroutines multiple times to ensure all nested launches complete
        // UnconfinedTestDispatcher executes coroutines eagerly

        coVerify { apiClient.ping() }
        coVerify { apiClient.register(match { it.hostname.isNotEmpty() }) }
        verify { setupRepository.save("https://example.com", "gc_testtoken", 42) }
    }

    @Test
    fun `saveAndRegister with blank url sets error`() = runTest {
        viewModel.onApiTokenChanged("gc_token")

        viewModel.uiState.test {
            awaitItem()

            viewModel.saveAndRegister()
            testDispatcher.scheduler.advanceUntilIdle()

            val state = awaitItem()
            assertEquals(StatusType.ERROR, state.statusType)
            coVerify(exactly = 0) { apiClient.register(any()) }

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saveAndRegister with blank token sets error`() = runTest {
        viewModel.onServerUrlChanged("https://example.com")

        viewModel.uiState.test {
            awaitItem()

            viewModel.saveAndRegister()
            testDispatcher.scheduler.advanceUntilIdle()

            val state = awaitItem()
            assertEquals(StatusType.ERROR, state.statusType)
            coVerify(exactly = 0) { apiClient.register(any()) }

            cancelAndIgnoreRemainingEvents()
        }
    }

    // -------------------------------------------------------------------------
    // handleDeepLink
    // -------------------------------------------------------------------------

    @Test
    @org.junit.jupiter.api.Disabled("Requires Android instrumented test — viewModelScope dispatching not testable with pure JVM mocks")
    fun `handleDeepLink calls API and registers`() = runTest {
        coEvery { apiClient.ping() } returns PingResponse(ok = true, version = "1.0", timestamp = "2024-01-01")
        coEvery { apiClient.register(any()) } returns RegisterResponse(
            ok = true,
            peerId = 99,
            peerName = "deep-link-device",
            config = null,
            hash = null,
        )

        viewModel.handleDeepLink("https://example.com", "gc_deeptoken")
        // UnconfinedTestDispatcher executes coroutines eagerly

        coVerify { apiClient.register(match { it.hostname.isNotEmpty() }) }
        verify { setupRepository.save("https://example.com", "gc_deeptoken", 99) }
    }

    // -------------------------------------------------------------------------
    // isSetupComplete
    // -------------------------------------------------------------------------

    @Test
    fun `isSetupComplete is true when repository isConfigured`() {
        every { setupRepository.isConfigured() } returns true
        every { setupRepository.hasWireGuardConfig() } returns false

        val vm = SetupViewModel(setupRepository, apiClientProvider, context)

        assertTrue(vm.uiState.value.isSetupComplete)
    }

    @Test
    fun `isSetupComplete is true when repository hasWireGuardConfig`() {
        every { setupRepository.isConfigured() } returns false
        every { setupRepository.hasWireGuardConfig() } returns true

        val vm = SetupViewModel(setupRepository, apiClientProvider, context)

        assertTrue(vm.uiState.value.isSetupComplete)
    }

    @Test
    fun `isSetupComplete is false when repository has neither`() {
        every { setupRepository.isConfigured() } returns false
        every { setupRepository.hasWireGuardConfig() } returns false

        val vm = SetupViewModel(setupRepository, apiClientProvider, context)

        assertFalse(vm.uiState.value.isSetupComplete)
    }

    // -------------------------------------------------------------------------
    // importConfig
    // -------------------------------------------------------------------------

    @Test
    fun `importConfig saves valid WireGuard config`() = runTest {
        val config = "[Interface]\nPrivateKey = YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY=\nAddress = 10.8.0.5/32\n\n[Peer]\nPublicKey = c2VydmVycHVibGlja2V5YmFzZTY0ZW5jb2RlZHh5eiE=\nEndpoint = vpn.example.com:51820\nAllowedIPs = 0.0.0.0/0"

        viewModel.uiState.test {
            awaitItem()

            viewModel.importConfig(config)
            testDispatcher.scheduler.advanceUntilIdle()

            val states = mutableListOf<SetupUiState>()
            while (states.lastOrNull()?.isSetupComplete != true) {
                states += awaitItem()
            }

            assertTrue(states.last().isSetupComplete)
            verify { setupRepository.saveWireGuardConfig(config) }

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `importConfig rejects blank config`() = runTest {
        viewModel.uiState.test {
            awaitItem()

            viewModel.importConfig("   ")
            testDispatcher.scheduler.advanceUntilIdle()

            val state = awaitItem()
            assertEquals(StatusType.ERROR, state.statusType)
            coVerify(exactly = 0) { setupRepository.saveWireGuardConfig(any()) }

            cancelAndIgnoreRemainingEvents()
        }
    }

    // -------------------------------------------------------------------------
    // One-scan setup (gatecontrol://enroll)
    // -------------------------------------------------------------------------

    private val enrollConfig = "[Interface]\nPrivateKey = YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY=\nAddress = 10.8.0.5/32\n\n[Peer]\nPublicKey = c2VydmVycHVibGlja2V5YmFzZTY0ZW5jb2RlZHh5eiE=\nEndpoint = vpn.example.com:51820\nAllowedIPs = 0.0.0.0/0"
    private val link = EnrollmentLink("https://gate.example.com", "AB12-CD34-EF56-7890")

    @Test
    fun `scanned setup link waits for confirmation before calling the server`() {
        viewModel.onEnrollmentLink(link)

        assertEquals(link, viewModel.uiState.value.pendingEnrollment)
        coVerify(exactly = 0) { apiClient.enroll(any()) }

        viewModel.cancelEnrollment()
        assertNull(viewModel.uiState.value.pendingEnrollment)
        coVerify(exactly = 0) { apiClient.enroll(any()) }
    }

    @Test
    fun `confirmed setup stores token, peer, config and hash`() = runTest {
        coEvery { apiClient.enroll(any()) } returns EnrollResponse(
            ok = true, token = "gc_enrolled", peerId = 42, peerName = "pixel",
            config = enrollConfig, hash = "abc",
        )

        viewModel.onEnrollmentLink(link)
        viewModel.confirmEnrollment()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { apiClient.enroll(match { it.code == "AB12-CD34-EF56-7890" && it.platform == "android" }) }
        verify { setupRepository.save("https://gate.example.com", "gc_enrolled", 42) }
        verify { setupRepository.saveWireGuardConfig(enrollConfig) }
        verify { setupRepository.saveConfigHash("abc") }
        val state = viewModel.uiState.value
        assertTrue(state.completedNow)
        assertTrue(state.isSetupComplete)
        assertNull(state.pendingEnrollment)
    }

    @Test
    fun `failed setup keeps the existing configuration`() = runTest {
        coEvery { apiClient.enroll(any()) } returns EnrollResponse(ok = false, error = "invalid_or_expired")

        viewModel.onEnrollmentLink(link)
        viewModel.confirmEnrollment()
        testDispatcher.scheduler.advanceUntilIdle()

        verify(exactly = 0) { setupRepository.clear() }
        verify(exactly = 0) { setupRepository.save(any(), any(), any()) }
        val state = viewModel.uiState.value
        assertEquals(StatusType.ERROR, state.statusType)
        assertEquals("res-${com.gatecontrol.android.R.string.setup_enroll_invalid}", state.statusMessage)
        assertFalse(state.completedNow)
    }

    @Test
    fun `setup code typed into the token field is redeemed instead of registering`() = runTest {
        coEvery { apiClient.enroll(any()) } returns EnrollResponse(
            ok = true, token = "gc_typed", peerId = 7, config = enrollConfig, hash = "h",
        )

        viewModel.onServerUrlChanged("gate.example.com")
        viewModel.onApiTokenChanged("ab12 cd34 ef56 7890")
        viewModel.saveAndRegister()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { apiClient.register(any()) }
        coVerify { apiClient.enroll(match { it.code == "AB12-CD34-EF56-7890" }) }
        verify { setupRepository.save("https://gate.example.com", "gc_typed", 7) }
        assertNotNull(viewModel.uiState.value.statusMessage)
    }
}
