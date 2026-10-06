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
import okhttp3.ResponseBody.Companion.toResponseBody
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
    private val fingerprint = "a".repeat(64)
    private val machineFingerprint: com.gatecontrol.android.data.MachineFingerprint = mockk {
        every { get() } returns fingerprint
    }

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

        viewModel = SetupViewModel(setupRepository, apiClientProvider, context, com.gatecontrol.android.service.fakeClientPolicyManager(), machineFingerprint)
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
            assertNotNull(finalState.statusMessage)
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
    fun `testConnection with a token never writes to the repository`() = runTest {
        every { setupRepository.getServerUrl() } returns "https://working.example.com"
        every { setupRepository.getApiToken() } returns "gc_working"
        every { setupRepository.getPeerId() } returns 5
        coEvery { apiClient.pingWithToken(any()) } throws RuntimeException("401")

        viewModel.onServerUrlChanged("https://other.example.com")
        viewModel.onApiTokenChanged("gc_typo")
        viewModel.testConnection()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { apiClient.pingWithToken("gc_typo") }
        coVerify(exactly = 0) { apiClient.ping() }
        verify(exactly = 0) { setupRepository.save(any(), any(), any()) }
        verify(exactly = 0) { setupRepository.clear() }
        assertEquals(StatusType.ERROR, viewModel.uiState.value.statusType)
    }

    @Test
    fun `successful testConnection with a token is also side-effect free`() = runTest {
        coEvery { apiClient.pingWithToken(any()) } returns PingResponse(ok = true, version = "1.0", timestamp = "2024-01-01")

        viewModel.onServerUrlChanged("gate.example.com")
        viewModel.onApiTokenChanged("gc_new")
        viewModel.testConnection()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { apiClient.pingWithToken("gc_new") }
        verify { apiClientProvider.getClient("https://gate.example.com") }
        verify(exactly = 0) { setupRepository.save(any(), any(), any()) }
        assertEquals(StatusType.SUCCESS, viewModel.uiState.value.statusType)
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
    // Legacy setup link (gatecontrol://setup?url&token)
    // -------------------------------------------------------------------------

    @Test
    fun `legacy setup link waits for confirmation before touching setup or server`() {
        viewModel.handleDeepLink("https://gate.example.com", "gc_deeptoken")

        val pending = viewModel.uiState.value.pendingTokenSetup
        assertEquals(TokenSetupLink("https://gate.example.com", "gc_deeptoken"), pending)
        assertEquals("gate.example.com", pending?.displayHost)
        coVerify(exactly = 0) { apiClient.ping() }
        coVerify(exactly = 0) { apiClient.register(any()) }
        verify(exactly = 0) { setupRepository.save(any(), any(), any()) }
        verify(exactly = 0) { setupRepository.clear() }

        viewModel.cancelTokenSetup()
        assertNull(viewModel.uiState.value.pendingTokenSetup)
        coVerify(exactly = 0) { apiClient.register(any()) }
        verify(exactly = 0) { setupRepository.save(any(), any(), any()) }
    }

    @Test
    fun `legacy setup link with http or garbage server is rejected`() {
        viewModel.handleDeepLink("http://gate.example.com", "gc_deeptoken")
        assertNull(viewModel.uiState.value.pendingTokenSetup)
        assertEquals(StatusType.ERROR, viewModel.uiState.value.statusType)

        viewModel.handleDeepLink("gate.example.com", "gc_deeptoken")
        assertNull(viewModel.uiState.value.pendingTokenSetup)

        viewModel.handleDeepLink("https://user@evil.example.com", "gc_deeptoken")
        assertNull(viewModel.uiState.value.pendingTokenSetup)

        viewModel.confirmTokenSetup()
        coVerify(exactly = 0) { apiClient.register(any()) }
        verify(exactly = 0) { setupRepository.save(any(), any(), any()) }
    }

    @Test
    fun `confirmed legacy setup link registers and stores the peer`() = runTest {
        coEvery { apiClient.ping() } returns PingResponse(ok = true, version = "1.0", timestamp = "2024-01-01")
        coEvery { apiClient.register(any()) } returns RegisterResponse(
            ok = true, peerId = 99, peerName = "deep-link-device", config = null, hash = null,
        )

        viewModel.handleDeepLink("https://example.com", "gc_deeptoken")
        viewModel.confirmTokenSetup()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { apiClient.register(any()) }
        verify { setupRepository.save("https://example.com", "gc_deeptoken", 99) }
        assertNull(viewModel.uiState.value.pendingTokenSetup)
        assertTrue(viewModel.uiState.value.completedNow)
    }

    @Test
    fun `failed legacy setup restores the previous configuration instead of clearing it`() = runTest {
        every { setupRepository.getServerUrl() } returns "https://old.example.com"
        every { setupRepository.getApiToken() } returns "gc_old"
        every { setupRepository.getPeerId() } returns 3
        coEvery { apiClient.ping() } returns PingResponse(ok = true, version = "1.0", timestamp = "2024-01-01")
        coEvery { apiClient.register(any()) } throws RuntimeException("boom")

        viewModel.handleDeepLink("https://evil.example.com", "gc_foreign")
        viewModel.confirmTokenSetup()
        testDispatcher.scheduler.advanceUntilIdle()

        verify(exactly = 0) { setupRepository.clear() }
        verify { setupRepository.save("https://old.example.com", "gc_old", 3) }
        verify(exactly = 0) { setupRepository.saveWireGuardConfig(any()) }
        assertEquals(StatusType.ERROR, viewModel.uiState.value.statusType)
        assertFalse(viewModel.uiState.value.completedNow)
    }

    @Test
    fun `rejected registration restores the previous configuration`() = runTest {
        every { setupRepository.getServerUrl() } returns "https://old.example.com"
        every { setupRepository.getApiToken() } returns "gc_old"
        every { setupRepository.getPeerId() } returns 3
        coEvery { apiClient.ping() } returns PingResponse(ok = true, version = "1.0", timestamp = "2024-01-01")
        coEvery { apiClient.register(any()) } returns RegisterResponse(
            ok = false, peerId = 0, peerName = null, config = null, hash = null,
        )

        viewModel.onServerUrlChanged("https://new.example.com")
        viewModel.onApiTokenChanged("gc_new")
        viewModel.saveAndRegister()
        testDispatcher.scheduler.advanceUntilIdle()

        verify(exactly = 0) { setupRepository.clear() }
        verify { setupRepository.save("https://old.example.com", "gc_old", 3) }
        assertEquals(StatusType.ERROR, viewModel.uiState.value.statusType)
    }

    // -------------------------------------------------------------------------
    // isSetupComplete
    // -------------------------------------------------------------------------

    @Test
    fun `isSetupComplete is true when repository isConfigured`() {
        every { setupRepository.isConfigured() } returns true
        every { setupRepository.hasWireGuardConfig() } returns false

        val vm = SetupViewModel(setupRepository, apiClientProvider, context, com.gatecontrol.android.service.fakeClientPolicyManager(), machineFingerprint)

        assertTrue(vm.uiState.value.isSetupComplete)
    }

    @Test
    fun `isSetupComplete is true when repository hasWireGuardConfig`() {
        every { setupRepository.isConfigured() } returns false
        every { setupRepository.hasWireGuardConfig() } returns true

        val vm = SetupViewModel(setupRepository, apiClientProvider, context, com.gatecontrol.android.service.fakeClientPolicyManager(), machineFingerprint)

        assertTrue(vm.uiState.value.isSetupComplete)
    }

    @Test
    fun `isSetupComplete is false when repository has neither`() {
        every { setupRepository.isConfigured() } returns false
        every { setupRepository.hasWireGuardConfig() } returns false

        val vm = SetupViewModel(setupRepository, apiClientProvider, context, com.gatecontrol.android.service.fakeClientPolicyManager(), machineFingerprint)

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

        coVerify {
            apiClient.enroll(match { it.code == "AB12-CD34-EF56-7890" && it.platform == "android" && it.fingerprint == fingerprint })
        }
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
        assertEquals(com.gatecontrol.android.ui.UiText.Res(com.gatecontrol.android.R.string.setup_enroll_invalid), state.statusMessage)
        assertFalse(state.completedNow)
    }

    private fun httpError(code: Int, body: String) = retrofit2.HttpException(
        retrofit2.Response.error<Any>(
            code,
            body.toResponseBody(null),
        ),
    )

    @Test
    fun `enroll without fingerprint shows the device binding message`() = runTest {
        coEvery { apiClient.enroll(any()) } throws httpError(400, "{\"ok\":false,\"error\":\"fingerprint_required\"}")

        viewModel.onEnrollmentLink(link)
        viewModel.confirmEnrollment()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            com.gatecontrol.android.ui.UiText.Res(com.gatecontrol.android.R.string.binding_required),
            viewModel.uiState.value.statusMessage,
        )
    }

    @Test
    fun `register on a token bound to another device shows the binding mismatch`() = runTest {
        coEvery { apiClient.ping() } returns PingResponse(ok = true, version = "1.0", timestamp = "t")
        coEvery { apiClient.register(any()) } throws
            httpError(403, "{\"ok\":false,\"error\":\"Token is bound to a different machine\"}")

        viewModel.onServerUrlChanged("https://gate.example.com")
        viewModel.onApiTokenChanged("gc_bound_token_123456")
        viewModel.saveAndRegister()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(StatusType.ERROR, viewModel.uiState.value.statusType)
        assertEquals(
            com.gatecontrol.android.ui.UiText.Res(com.gatecontrol.android.R.string.binding_mismatch),
            viewModel.uiState.value.statusMessage,
        )
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

    @Test
    fun `token code without peer registers with the new token`() = runTest {
        coEvery { apiClient.enroll(any()) } returns EnrollResponse(ok = true, token = "gc_wizard", peerId = null, config = null)
        coEvery { apiClient.register(any()) } returns RegisterResponse(
            ok = true, peerId = 12, peerName = "pixel", config = enrollConfig, hash = "rh",
        )

        viewModel.onEnrollmentLink(link)
        viewModel.confirmEnrollment()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { apiClient.register(any()) }
        verify { setupRepository.save("https://gate.example.com", "gc_wizard", 12) }
        verify { setupRepository.saveWireGuardConfig(enrollConfig) }
        verify { setupRepository.saveConfigHash("rh") }
        assertTrue(viewModel.uiState.value.completedNow)
    }

    @Test
    fun `failed registration after a token code restores the previous setup`() = runTest {
        every { setupRepository.getServerUrl() } returns "https://old.example.com"
        every { setupRepository.getApiToken() } returns "gc_old"
        every { setupRepository.getPeerId() } returns 3
        coEvery { apiClient.enroll(any()) } returns EnrollResponse(ok = true, token = "gc_wizard", peerId = null)
        coEvery { apiClient.register(any()) } throws RuntimeException("boom")

        viewModel.onEnrollmentLink(link)
        viewModel.confirmEnrollment()
        testDispatcher.scheduler.advanceUntilIdle()

        verify { setupRepository.save("https://old.example.com", "gc_old", 3) }
        assertEquals(StatusType.ERROR, viewModel.uiState.value.statusType)
        assertFalse(viewModel.uiState.value.completedNow)
    }
}
