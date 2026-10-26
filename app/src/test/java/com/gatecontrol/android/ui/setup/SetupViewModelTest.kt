package com.gatecontrol.android.ui.setup

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.lifecycle.SavedStateHandle
import com.gatecontrol.android.R
import com.gatecontrol.android.common.CompanySignin
import com.gatecontrol.android.network.SigninCodeResponse
import com.gatecontrol.android.network.SigninMethodsResponse
import com.gatecontrol.android.network.SigninMicrosoftMethod
import com.gatecontrol.android.network.SigninPasswordMethod
import com.gatecontrol.android.ui.UiText
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
    private var savedStateHandle = SavedStateHandle()
    private val fingerprint = "a".repeat(64)
    private val machineFingerprint: com.gatecontrol.android.data.MachineFingerprint = mockk {
        every { get() } returns fingerprint
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        savedStateHandle = SavedStateHandle()

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

        viewModel = SetupViewModel(setupRepository, apiClientProvider, context, com.gatecontrol.android.service.fakeClientPolicyManager(), machineFingerprint, savedStateHandle)
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

        val vm = SetupViewModel(setupRepository, apiClientProvider, context, com.gatecontrol.android.service.fakeClientPolicyManager(), machineFingerprint, savedStateHandle)

        assertTrue(vm.uiState.value.isSetupComplete)
    }

    @Test
    fun `isSetupComplete is true when repository hasWireGuardConfig`() {
        every { setupRepository.isConfigured() } returns false
        every { setupRepository.hasWireGuardConfig() } returns true

        val vm = SetupViewModel(setupRepository, apiClientProvider, context, com.gatecontrol.android.service.fakeClientPolicyManager(), machineFingerprint, savedStateHandle)

        assertTrue(vm.uiState.value.isSetupComplete)
    }

    @Test
    fun `isSetupComplete is false when repository has neither`() {
        every { setupRepository.isConfigured() } returns false
        every { setupRepository.hasWireGuardConfig() } returns false

        val vm = SetupViewModel(setupRepository, apiClientProvider, context, com.gatecontrol.android.service.fakeClientPolicyManager(), machineFingerprint, savedStateHandle)

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

    // -------------------------------------------------------------------------
    // Company sign-in ("Mit Firmenkonto anmelden", GateControl >= 1.201.0)
    // -------------------------------------------------------------------------

    private val methods = SigninMethodsResponse(
        ok = true,
        version = 1,
        password = SigninPasswordMethod(enabled = true, hint = "Firmenkonto (corp.local): mit dem Windows-Passwort anmelden"),
        microsoft = listOf(SigninMicrosoftMethod(id = 7, label = "Contoso"), SigninMicrosoftMethod(id = null, label = "kaputt")),
    )
    private val enrolled = EnrollResponse(ok = true, token = "gc_signin", peerId = 21, config = enrollConfig, hash = "sh")

    private fun loadMethods() {
        coEvery { apiClient.signinMethods() } returns methods
        viewModel.onServerUrlChanged("gate.example.com")
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun queryParam(url: String, key: String): String? =
        url.substringAfter('?').split('&').map { it.split('=', limit = 2) }
            .firstOrNull { it[0] == key }?.getOrNull(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }

    private fun message() = viewModel.uiState.value.statusMessage

    @Test
    fun `typed https server with company sign-in shows its methods`() = runTest {
        loadMethods()

        val signin = viewModel.uiState.value.signin
        assertTrue(signin.available)
        assertEquals("https://gate.example.com", signin.serverUrl)
        assertTrue(signin.passwordEnabled)
        assertEquals("Firmenkonto (corp.local): mit dem Windows-Passwort anmelden", signin.hint)
        assertEquals(listOf(MicrosoftSignin(7, "Contoso")), signin.microsoft)
        coVerify(exactly = 1) { apiClient.signinMethods() }
        verify { apiClientProvider.getClient("https://gate.example.com") }
    }

    @Test
    fun `older server without the endpoint hides the company sign-in`() = runTest {
        coEvery { apiClient.signinMethods() } throws httpError(404, "Not Found")

        viewModel.onServerUrlChanged("gate.example.com")
        testDispatcher.scheduler.advanceUntilIdle()

        val signin = viewModel.uiState.value.signin
        assertFalse(signin.available)
        assertEquals("https://gate.example.com", signin.unavailableFor)
        assertNull(message())
    }

    @Test
    fun `plain http server is never asked`() = runTest {
        coEvery { apiClient.signinMethods() } returns methods

        viewModel.onServerUrlChanged("http://gate.example.com")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { apiClient.signinMethods() }
        assertFalse(viewModel.uiState.value.signin.available)
    }

    @Test
    fun `another server drops the methods and the typed password`() = runTest {
        loadMethods()
        viewModel.onSigninUsernameChanged("anna")
        viewModel.onSigninPasswordChanged("geheim")
        coEvery { apiClient.signinMethods() } throws httpError(404, "")

        viewModel.onServerUrlChanged("other.example.com")

        assertFalse(viewModel.uiState.value.signin.available)
        assertEquals("", viewModel.uiState.value.signin.password)
        assertEquals("anna", viewModel.uiState.value.signin.username)
    }

    @Test
    fun `password sign-in redeems the setup code through the normal enroll path`() = runTest {
        loadMethods()
        coEvery { apiClient.signinPassword(any()) } returns SigninCodeResponse(ok = true, code = "ab12cd34ef567890", expiresAt = "2026-10-26T12:00:00Z")
        coEvery { apiClient.enroll(any()) } returns enrolled

        viewModel.onSigninUsernameChanged(" anna@corp.local ")
        viewModel.onSigninPasswordChanged("geheim")
        viewModel.submitCompanyPassword()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify {
            apiClient.signinPassword(match { it.username == "anna@corp.local" && it.password == "geheim" && it.totp == null && it.platform == "android" })
        }
        coVerify {
            apiClient.enroll(match { it.code == "AB12-CD34-EF56-7890" && it.platform == "android" && it.fingerprint == fingerprint })
        }
        verify { setupRepository.save("https://gate.example.com", "gc_signin", 21) }
        verify { setupRepository.saveWireGuardConfig(enrollConfig) }
        val state = viewModel.uiState.value
        assertTrue(state.completedNow)
        assertEquals("", state.signin.password)
        assertFalse(state.signin.busy)
    }

    @Test
    fun `account with 2FA asks for the code and then signs in`() = runTest {
        loadMethods()
        coEvery { apiClient.signinPassword(match { it.totp == null }) } throws
            httpError(401, "{\"ok\":false,\"error\":\"totp_required\"}")
        coEvery { apiClient.signinPassword(match { it.totp == "123456" }) } returns
            SigninCodeResponse(ok = true, code = "AB12-CD34-EF56-7890")
        coEvery { apiClient.enroll(any()) } returns enrolled

        viewModel.onSigninUsernameChanged("anna")
        viewModel.onSigninPasswordChanged("geheim")
        viewModel.submitCompanyPassword()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.signin.totpRequired)
        assertEquals(StatusType.INFO, viewModel.uiState.value.statusType)
        assertEquals(UiText.Res(R.string.signin_totp_required), message())
        coVerify(exactly = 0) { apiClient.enroll(any()) }

        // Not six digits yet: nothing is sent.
        viewModel.onSigninTotpChanged("12 34")
        viewModel.submitCompanyPassword()
        assertEquals(UiText.Res(R.string.signin_error_totp_format), message())

        viewModel.onSigninTotpChanged("123456")
        viewModel.submitCompanyPassword()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { apiClient.signinPassword(match { it.username == "anna" && it.password == "geheim" && it.totp == "123456" }) }
        coVerify { apiClient.enroll(match { it.code == "AB12-CD34-EF56-7890" }) }
        assertTrue(viewModel.uiState.value.completedNow)
        assertFalse(viewModel.uiState.value.signin.totpRequired)
    }

    @Test
    fun `wrong password shows the contract text and clears the password`() = runTest {
        loadMethods()
        coEvery { apiClient.signinPassword(any()) } throws httpError(401, "{\"ok\":false,\"error\":\"invalid_credentials\"}")

        viewModel.onSigninUsernameChanged("anna")
        viewModel.onSigninPasswordChanged("falsch")
        viewModel.submitCompanyPassword()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(StatusType.ERROR, viewModel.uiState.value.statusType)
        assertEquals(UiText.Res(R.string.signin_error_invalid_credentials), message())
        assertEquals("", viewModel.uiState.value.signin.password)
        assertFalse(viewModel.uiState.value.signin.busy)
        coVerify(exactly = 0) { apiClient.enroll(any()) }
        verify(exactly = 0) { setupRepository.save(any(), any(), any()) }
    }

    @Test
    fun `sign-in errors map to the contract texts`() = runTest {
        loadMethods()
        viewModel.onSigninUsernameChanged("anna")

        fun attempt(error: Exception): UiText? {
            coEvery { apiClient.signinPassword(any()) } throws error
            viewModel.onSigninPasswordChanged("geheim")
            viewModel.submitCompanyPassword()
            testDispatcher.scheduler.advanceUntilIdle()
            return message()
        }

        assertEquals(
            UiText.Res(R.string.signin_error_enroll_not_allowed),
            attempt(httpError(403, "{\"ok\":false,\"error\":\"enroll_not_allowed\"}")),
        )
        assertEquals(UiText.Res(R.string.signin_error_user_disabled), attempt(httpError(403, "{\"ok\":false,\"error\":\"user_disabled\"}")))
        assertEquals(UiText.Res(R.string.signin_error_limit_reached), attempt(httpError(403, "{\"ok\":false,\"error\":\"limit_reached\"}")))
        assertEquals(
            UiText.Res(R.string.signin_error_locked, 5),
            attempt(httpError(429, "{\"ok\":false,\"error\":\"locked\",\"retryAfter\":300}")),
        )
        assertEquals(
            UiText.Res(R.string.signin_error_locked_one),
            attempt(httpError(429, "{\"ok\":false,\"error\":\"rate_limited\",\"retryAfter\":42}")),
        )
        assertEquals(
            UiText.Res(R.string.signin_error_password_change_required),
            attempt(httpError(403, "{\"ok\":false,\"error\":\"password_change_required\"}")),
        )
        assertEquals(
            UiText.Res(R.string.signin_error_directory_unavailable),
            attempt(httpError(503, "{\"ok\":false,\"error\":\"directory_unavailable\"}")),
        )
        // Not a wrong password: the typed password stays for the next try.
        assertEquals("geheim", viewModel.uiState.value.signin.password)
        assertEquals(UiText.Res(R.string.setup_enroll_rate_limited), attempt(httpError(429, "")))
        assertEquals(UiText.Res(R.string.signin_error_failed), attempt(httpError(500, "oops")))
        assertEquals(
            UiText.Res(R.string.signin_error_network, "unreachable"),
            attempt(java.io.IOException("unreachable")),
        )
        coVerify(exactly = 0) { apiClient.enroll(any()) }
    }

    @Test
    fun `empty fields are not sent`() = runTest {
        loadMethods()

        viewModel.submitCompanyPassword()

        assertEquals(UiText.Res(R.string.signin_error_fields), message())
        coVerify(exactly = 0) { apiClient.signinPassword(any()) }
    }

    @Test
    fun `microsoft sign-in opens the server start page with PKCE and a fresh state`() = runTest {
        loadMethods()

        viewModel.startMicrosoftSignin(7)

        val url = viewModel.uiState.value.signin.browserUrl
        assertNotNull(url)
        assertTrue(url!!.startsWith("https://gate.example.com/auth/oidc/7/start?"))
        assertEquals("app", queryParam(url, "client"))
        assertEquals("gatecontrol://signin", queryParam(url, "redirect_uri"))
        val verifier = savedStateHandle.get<String>("company_signin_verifier")
        assertNotNull(verifier)
        assertTrue(CompanySignin.isValidVerifier(verifier!!))
        assertEquals(CompanySignin.challenge(verifier), queryParam(url, "challenge"))
        assertEquals(savedStateHandle.get<String>("company_signin_state"), queryParam(url, "state"))
        assertEquals("https://gate.example.com", savedStateHandle.get<String>("company_signin_server"))

        viewModel.onSigninBrowserOpened()
        assertNull(viewModel.uiState.value.signin.browserUrl)

        // Unknown directory: nothing starts.
        viewModel.startMicrosoftSignin(99)
        assertNull(viewModel.uiState.value.signin.browserUrl)
    }

    @Test
    fun `redirect with another state is rejected and the started sign-in stays`() = runTest {
        loadMethods()
        viewModel.startMicrosoftSignin(7)
        val state = savedStateHandle.get<String>("company_signin_state")!!

        viewModel.onSigninRedirect("gatecontrol://signin?handoff=h0ff-1234567890&state=forged-state-123")

        coVerify(exactly = 0) { apiClient.signinHandoff(any()) }
        assertEquals(StatusType.ERROR, viewModel.uiState.value.statusType)
        assertEquals(UiText.Res(R.string.signin_error_state), message())
        assertEquals(state, savedStateHandle.get<String>("company_signin_state"))
    }

    @Test
    fun `redirect without a started sign-in is rejected`() = runTest {
        viewModel.onSigninRedirect("gatecontrol://signin?handoff=h0ff-1234567890&state=whatever-1234")

        coVerify(exactly = 0) { apiClient.signinHandoff(any()) }
        assertEquals(UiText.Res(R.string.signin_error_state), message())
    }

    @Test
    fun `handoff of the started sign-in is redeemed, also after process death`() = runTest {
        loadMethods()
        viewModel.startMicrosoftSignin(7)
        val state = savedStateHandle.get<String>("company_signin_state")!!
        val verifier = savedStateHandle.get<String>("company_signin_verifier")!!
        coEvery { apiClient.signinHandoff(any()) } returns SigninCodeResponse(ok = true, code = "AB12-CD34-EF56-7890")
        coEvery { apiClient.enroll(any()) } returns enrolled

        // The process died while the browser was open: a new ViewModel with the saved state.
        val restored = SetupViewModel(
            setupRepository, apiClientProvider, context,
            com.gatecontrol.android.service.fakeClientPolicyManager(), machineFingerprint, savedStateHandle,
        )
        restored.onSigninRedirect("gatecontrol://signin?handoff=h0ff-1234567890&state=$state")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { apiClient.signinHandoff(match { it.handoff == "h0ff-1234567890" && it.verifier == verifier }) }
        coVerify { apiClient.enroll(match { it.code == "AB12-CD34-EF56-7890" && it.fingerprint == fingerprint }) }
        verify { setupRepository.save("https://gate.example.com", "gc_signin", 21) }
        assertTrue(restored.uiState.value.completedNow)
        assertNull(savedStateHandle.get<String>("company_signin_verifier"))
        assertNull(savedStateHandle.get<String>("company_signin_state"))

        // Single use: the same link again is rejected.
        restored.onSigninRedirect("gatecontrol://signin?handoff=h0ff-1234567890&state=$state")
        assertEquals(UiText.Res(R.string.signin_error_state), restored.uiState.value.statusMessage)
        coVerify(exactly = 1) { apiClient.signinHandoff(any()) }
    }

    @Test
    fun `expired handoff shows the expiry text`() = runTest {
        loadMethods()
        viewModel.startMicrosoftSignin(7)
        val state = savedStateHandle.get<String>("company_signin_state")!!
        coEvery { apiClient.signinHandoff(any()) } throws httpError(400, "{\"ok\":false,\"error\":\"invalid_or_expired\"}")

        viewModel.onSigninRedirect("gatecontrol://signin?handoff=h0ff-1234567890&state=$state")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(UiText.Res(R.string.signin_error_expired), message())
        coVerify(exactly = 0) { apiClient.enroll(any()) }
    }

    @Test
    fun `handoff for an account with a temporary password shows the change text`() = runTest {
        loadMethods()
        viewModel.startMicrosoftSignin(7)
        val state = savedStateHandle.get<String>("company_signin_state")!!
        coEvery { apiClient.signinHandoff(any()) } throws httpError(403, "{\"ok\":false,\"error\":\"password_change_required\"}")

        viewModel.onSigninRedirect("gatecontrol://signin?handoff=h0ff-1234567890&state=$state")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(UiText.Res(R.string.signin_error_password_change_required), message())
        coVerify(exactly = 0) { apiClient.enroll(any()) }
    }

    @Test
    fun `error redirect from the browser shows its text without calling the server`() = runTest {
        loadMethods()
        viewModel.startMicrosoftSignin(7)
        val state = savedStateHandle.get<String>("company_signin_state")!!

        viewModel.onSigninRedirect("gatecontrol://signin?error=not_linked&state=$state")

        assertEquals(UiText.Res(R.string.signin_error_not_linked), message())
        coVerify(exactly = 0) { apiClient.signinHandoff(any()) }
        assertNull(savedStateHandle.get<String>("company_signin_state"))
    }

    @Test
    fun `secrets never appear in the state text`() = runTest {
        loadMethods()
        viewModel.onSigninPasswordChanged("geheim-pw")
        viewModel.onSigninTotpChanged("123456")
        val text = viewModel.uiState.value.toString()
        assertFalse(text.contains("geheim-pw"))
        assertFalse(text.contains("123456"))
    }
}
