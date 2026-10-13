package com.gatecontrol.android.diagnosis

import android.content.Context
import android.content.res.Resources
import com.gatecontrol.android.R
import com.gatecontrol.android.data.MachineFingerprint
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.SupportBundleInfo
import com.gatecontrol.android.network.SupportBundleUploadResponse
import com.gatecontrol.android.network.SupportBundleUploader
import com.gatecontrol.android.support.SupportBundleCollector
import com.gatecontrol.android.support.SupportRequestHolder
import com.gatecontrol.android.ui.UiText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosisViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val report = DiagnosisReport(
        startedAt = 1_790_000_000_000L,
        finishedAt = 1_790_000_020_000L,
        checks = CheckId.entries.map { CheckResult(it, CheckStatus.OK, DiagText(R.string.diag_skipped)) },
    )

    private val res = mockk<Resources>(relaxed = true) {
        every { getString(any()) } answers { "text#${firstArg<Int>()}" }
    }
    private val context = mockk<Context>(relaxed = true) {
        every { resources } returns res
        every { createConfigurationContext(any()) } returns mockk(relaxed = true) { every { resources } returns res }
    }
    private val runner = mockk<DiagnosisRunner>()
    private val setup = mockk<SetupRepository> {
        every { getServerUrl() } returns "https://gate.example.com"
        every { getPeerId() } returns 7
    }
    private val settings = mockk<SettingsRepository>(relaxed = true) {
        every { getLocale() } returns flowOf("de")
        every { getTheme() } returns flowOf("dark")
        every { getAutoConnect() } returns flowOf(false)
        every { getSplitTunnelNetworks() } returns flowOf("")
        every { getSplitTunnelAppsV2() } returns flowOf("")
        every { getSplitTunnelAdminLocked() } returns flowOf(false)
    }
    private val fingerprint = mockk<MachineFingerprint> { every { shortId() } returns "ab12cd34" }
    private val collector = mockk<SupportBundleCollector>()
    private val uploader = mockk<SupportBundleUploader>()

    private lateinit var vm: DiagnosisViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        SupportRequestHolder.clear()
        coEvery { runner.run(any()) } returns report
        every { collector.collect(any(), any(), any(), any(), any(), any(), any()) } returns mapOf("schema" to 1)
        vm = DiagnosisViewModel(context, runner, setup, settings, fingerprint, collector, uploader)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** collect() runs on Dispatchers.IO: wait for the upload to finish. */
    private fun awaitMessage(): UiText? {
        repeat(300) {
            dispatcher.scheduler.advanceUntilIdle()
            val s = vm.uiState.value
            if (!s.sending && s.message != null) return s.message
            Thread.sleep(10)
        }
        return vm.uiState.value.message
    }

    @Test
    fun `start runs the checks and keeps the report`() = runTest {
        assertEquals(DiagnosisPhase.IDLE, vm.uiState.value.phase)
        vm.start()
        assertEquals(DiagnosisPhase.RUNNING, vm.uiState.value.phase)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(DiagnosisPhase.DONE, vm.uiState.value.phase)
        assertEquals(report, vm.uiState.value.report)
        assertFalse(vm.uiState.value.reportDialogVisible)
    }

    @Test
    fun `report problem without a run runs the diagnosis first, then opens the dialog`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { runner.run(any()) } coAnswers { gate.await(); report }
        vm.reportProblem()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(DiagnosisPhase.RUNNING, vm.uiState.value.phase)
        assertTrue(vm.uiState.value.reportAfterRun)
        assertFalse(vm.uiState.value.reportDialogVisible)

        gate.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.reportDialogVisible)
        assertFalse(vm.uiState.value.reportAfterRun)
        coVerify(exactly = 1) { runner.run(any()) }

        // With a report the dialog opens right away, no second run.
        vm.dismissReport()
        vm.reportProblem()
        assertTrue(vm.uiState.value.reportDialogVisible)
        coVerify(exactly = 1) { runner.run(any()) }
    }

    @Test
    fun `send passes description and diagnosis to the bundle`() = runTest {
        vm.start()
        dispatcher.scheduler.advanceUntilIdle()
        val description = slot<String>()
        val diagnosis = slot<Map<String, Any?>>()
        val settingsSnapshot = slot<Map<String, Any?>>()
        every { collector.collect(any(), "de", capture(settingsSnapshot), "user", any(), capture(description), capture(diagnosis)) } returns mapOf("schema" to 1)
        coEvery { uploader.upload("https://gate.example.com", 7, any()) } returns SupportBundleUploadResponse(ok = true, bundle = SupportBundleInfo(id = 3))

        vm.reportProblem()
        vm.setDescription("  Seiten laden nicht  ")
        vm.send("1.27.0")

        assertEquals(UiText.Res(R.string.diag_report_success), awaitMessage())
        assertEquals("Seiten laden nicht", description.captured)
        assertEquals("ok", diagnosis.captured["summary"])
        assertEquals(7, (diagnosis.captured["checks"] as List<*>).size)
        assertEquals("ab12cd34", settingsSnapshot.captured["deviceId"])
        assertFalse(settingsSnapshot.captured.keys.any { it.contains("token", ignoreCase = true) })
        assertFalse(vm.uiState.value.reportDialogVisible)
        assertEquals("", vm.uiState.value.description)
        vm.consumeMessage()
        assertNull(vm.uiState.value.message)
    }

    @Test
    fun `send - rate limit maps to the support message`() = runTest {
        vm.start()
        dispatcher.scheduler.advanceUntilIdle()
        coEvery { uploader.upload(any(), any(), any()) } throws HttpException(Response.error<Any>(429, "{}".toResponseBody(null)))
        vm.send("1.27.0")
        assertEquals(UiText.Res(R.string.support_rate_limited), awaitMessage())
    }

    @Test
    fun `description is capped at 2000 chars`() {
        vm.setDescription("x".repeat(2500))
        assertEquals(2000, vm.uiState.value.description.length)
    }

    @Test
    fun `not set up - no run, message instead`() = runTest {
        every { setup.getPeerId() } returns -1
        vm.reportProblem()
        assertEquals(UiText.Res(R.string.support_not_configured), vm.uiState.value.message)
        assertEquals(DiagnosisPhase.IDLE, vm.uiState.value.phase)
    }
}
