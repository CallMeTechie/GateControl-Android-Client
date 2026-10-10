package com.gatecontrol.android.ui.settings

import app.cash.turbine.test
import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.PingResponse
import com.gatecontrol.android.network.UpdateCheckResponse
import com.gatecontrol.android.R
import com.gatecontrol.android.network.SupportBundleInfo
import com.gatecontrol.android.network.SupportBundleUploadResponse
import com.gatecontrol.android.network.SupportBundleUploader
import com.gatecontrol.android.support.SupportBundleCollector
import com.gatecontrol.android.support.SupportRequestHolder
import com.gatecontrol.android.ui.UiText
import io.mockk.slot
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
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
import org.junit.jupiter.api.Assertions.assertFalse
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
    private lateinit var supportBundleCollector: SupportBundleCollector
    private lateinit var supportBundleUploader: SupportBundleUploader
    private val machineFingerprint: com.gatecontrol.android.data.MachineFingerprint = mockk {
        every { shortId() } returns "ab12cd34"
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        settingsRepository = mockk(relaxed = true) {
            every { getTheme() } returns flowOf("dark")
            every { getLocale() } returns flowOf("de")
            every { getAutoConnect() } returns flowOf(false)
            every { getSplitTunnelEnabled() } returns flowOf(false)
            every { getSplitTunnelRoutes() } returns flowOf("")
            every { getSplitTunnelApps() } returns flowOf("")
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

        supportBundleCollector = mockk {
            every { collect(any(), any(), any(), any(), any()) } returns mapOf("schema" to 1)
        }
        supportBundleUploader = mockk()
        SupportRequestHolder.clear()

        viewModel = SettingsViewModel(
            setupRepository, settingsRepository, apiClientProvider, licenseRepository,
            supportBundleCollector, supportBundleUploader,
            com.gatecontrol.android.service.fakeClientPolicyManager(),
            machineFingerprint,
        )
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
    fun `testConnection sets Success on ping ok`() = runTest {
        coEvery { apiClient.ping() } returns PingResponse(ok = true, version = "1.0.0", timestamp = "2026-04-05")

        viewModel.uiState.test {
            awaitItem() // initial state

            viewModel.testConnection("https://gate.example.com", "gc_test")
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

        viewModel.testConnection("https://gate.example.com", "gc_test")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ConnectionTestStatus.Failure, viewModel.uiState.value.connectionTestStatus)
    }

    @Test
    fun `testConnection sets Failure when URL is blank`() = runTest {
        viewModel.testConnection("", "gc_test")
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

    @Test
    fun `client policy locks auto-connect, split mode and server change`() = runTest {
        val policy = com.gatecontrol.android.common.ClientPolicy(
            autoConnect = com.gatecontrol.android.common.ClientPolicy.AutoConnect.REQUIRED,
            splitTunnelModes = setOf(com.gatecontrol.android.common.SplitTunnelMode.OFF),
            lockServer = true,
        )
        val vm = SettingsViewModel(
            setupRepository, settingsRepository, apiClientProvider, licenseRepository,
            supportBundleCollector, supportBundleUploader,
            com.gatecontrol.android.service.fakeClientPolicyManager(policy),
            machineFingerprint,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setAutoConnect(false)
        vm.setSplitTunnelMode(com.gatecontrol.android.common.SplitTunnelMode.INCLUDE)
        vm.saveServer("https://other.example.com", "gc_othertoken123456")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { settingsRepository.setAutoConnect(any()) }
        coVerify(exactly = 0) { settingsRepository.setSplitTunnelMode(any()) }
        io.mockk.verify(exactly = 0) { setupRepository.save(any(), any(), any()) }
        org.junit.jupiter.api.Assertions.assertEquals(policy, vm.uiState.value.policy)
    }

    // ── Support bundle ───────────────────────────────────────────────

    private suspend fun awaitSupportResult(): UiText? {
        repeat(200) {
            testDispatcher.scheduler.advanceUntilIdle()
            val state = viewModel.uiState.value
            if (!state.supportSending && state.supportMessage != null) return state.supportMessage
            Thread.sleep(10) // collect() runs on Dispatchers.IO
        }
        return viewModel.uiState.value.supportMessage
    }

    @Test
    fun `support bundle - dialog first, upload only after confirm`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.requestSupportBundle()
        assertTrue(viewModel.uiState.value.supportDialogVisible)
        coVerify(exactly = 0) { supportBundleUploader.upload(any(), any(), any()) }

        viewModel.dismissSupportDialog()
        assertFalse(viewModel.uiState.value.supportDialogVisible)
        coVerify(exactly = 0) { supportBundleUploader.upload(any(), any(), any()) }
    }

    @Test
    fun `support bundle - sends redacted settings without the token`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        val settings = slot<Map<String, Any?>>()
        every { supportBundleCollector.collect(any(), any(), capture(settings), any(), any()) } returns mapOf("schema" to 1)
        coEvery { supportBundleUploader.upload("https://gate.example.com", 1, any()) } returns
            SupportBundleUploadResponse(ok = true, bundle = SupportBundleInfo(id = 5))

        viewModel.requestSupportBundle()
        viewModel.sendSupportBundle("1.5.0")
        val msg = awaitSupportResult()

        assertEquals(UiText.Res(R.string.support_success), msg)
        assertFalse(viewModel.uiState.value.supportDialogVisible)
        assertFalse(settings.captured.values.any { it == "gc_testtoken" })
        assertFalse(settings.captured.keys.any { it.contains("token", ignoreCase = true) })
        assertEquals("https://gate.example.com", settings.captured["serverUrl"])
        assertEquals("ab12cd34", settings.captured["deviceId"])

        viewModel.consumeSupportMessage()
        assertNull(viewModel.uiState.value.supportMessage)
    }

    @Test
    fun `device id short form is shown`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("ab12cd34", viewModel.uiState.value.deviceIdShort)
    }

    @Test
    fun `support bundle - binding mismatch maps to the device binding message`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        coEvery { supportBundleUploader.upload(any(), any(), any()) } throws HttpException(
            Response.error<Any>(403, "{\"ok\":false,\"error\":\"Token ist an eine andere Maschine gebunden\"}".toResponseBody(null)),
        )
        viewModel.sendSupportBundle("1.5.0")
        assertEquals(UiText.Res(R.string.binding_mismatch), awaitSupportResult())
    }

    @Test
    fun `support bundle - 429 maps to rate limited message`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        coEvery { supportBundleUploader.upload(any(), any(), any()) } throws
            HttpException(Response.error<Any>(429, "{\"ok\":false}".toResponseBody(null)))
        viewModel.sendSupportBundle("1.5.0")
        assertEquals(UiText.Res(R.string.support_rate_limited), awaitSupportResult())
    }

    @Test
    fun `support bundle - admin request is shown and cleared by a successful upload`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        SupportRequestHolder.update(true, "2026-10-02 10:00:00")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.supportRequested)

        coEvery { supportBundleUploader.upload(any(), any(), any()) } returns SupportBundleUploadResponse(ok = true)
        viewModel.sendSupportBundle("1.5.0")
        awaitSupportResult()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.supportRequested)
        io.mockk.verify { supportBundleCollector.collect(any(), any(), any(), "admin_request", any()) }
    }

    @Test
    fun `support bundle - not configured shows a message, no dialog`() = runTest {
        every { setupRepository.getPeerId() } returns -1
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.requestSupportBundle()
        assertFalse(viewModel.uiState.value.supportDialogVisible)
        assertEquals(UiText.Res(R.string.support_not_configured), viewModel.uiState.value.supportMessage)
    }
}
