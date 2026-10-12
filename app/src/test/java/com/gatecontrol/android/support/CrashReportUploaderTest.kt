package com.gatecontrol.android.support

import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.SupportBundleUploadResponse
import com.gatecontrol.android.network.SupportBundleUploader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import retrofit2.HttpException
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.util.Date

class CrashReportUploaderTest {

    @TempDir
    lateinit var tmp: File

    private val setup = mockk<SetupRepository> {
        every { getServerUrl() } returns "https://gc.example.com"
        every { getPeerId() } returns 7
    }
    private val collector = mockk<SupportBundleCollector> {
        every { collect(any(), any(), any(), any(), any()) } returns mapOf("schema" to 1)
    }
    private val uploader = mockk<SupportBundleUploader>()
    private val crashDir get() = File(tmp, "crash")

    @BeforeEach
    fun setUp() {
        CrashReports.init(crashDir)
    }

    @AfterEach
    fun tearDown() {
        CrashReports.init(null as File?)
    }

    private fun crash(tag: String = "uncaught_main", at: Long = 0L) =
        CrashReports.write(tag, IllegalStateException("boom $tag"), Date(1_760_000_000_000L + at))

    @Test
    fun `write records the stack trace and keeps at most MAX_FILES reports`() {
        repeat(CrashReports.MAX_FILES + 3) { crash(at = it * 1000L) }

        val files = CrashReports.pending()
        assertEquals(CrashReports.MAX_FILES, files.size)
        assertTrue(files.first().readText().contains("IllegalStateException: boom uncaught_main"))
    }

    @Test
    fun `same second does not overwrite a report`() {
        crash()
        crash()
        assertEquals(2, CrashReports.pending().size)
    }

    @Test
    fun `sends pending reports as one bundle and deletes them`() = runTest {
        crash("uncaught_main")
        crash("background", at = 1000L)
        val sent = slot<Map<String, Any?>>()
        coEvery { uploader.upload("https://gc.example.com", 7, capture(sent)) } returns SupportBundleUploadResponse(ok = true)

        CrashReportUploader(setup, collector, uploader).uploadPending("1.21.1", "de")

        @Suppress("UNCHECKED_CAST")
        val section = sent.captured["crash"] as Map<String, Any?>
        assertEquals(2, section["count"])
        @Suppress("UNCHECKED_CAST")
        val texts = (section["reports"] as List<Map<String, Any?>>).map { it["text"] as String }
        assertTrue(texts.any { it.contains("boom background") })
        assertEquals(1, sent.captured["schema"])
        assertTrue(CrashReports.pending().isEmpty())
    }

    @Test
    fun `keeps reports when the server is unreachable`() = runTest {
        crash()
        coEvery { uploader.upload(any(), any(), any()) } throws IOException("offline")

        CrashReportUploader(setup, collector, uploader).uploadPending("1.21.1", "de")

        assertEquals(1, CrashReports.pending().size)
    }

    @Test
    fun `gives up on a server without support bundles`() = runTest {
        crash()
        coEvery { uploader.upload(any(), any(), any()) } throws
            HttpException(Response.error<Any>(404, "".toResponseBody(null)))

        CrashReportUploader(setup, collector, uploader).uploadPending("1.21.1", "de")

        assertTrue(CrashReports.pending().isEmpty())
    }

    @Test
    fun `waits for the setup before sending`() = runTest {
        crash()
        every { setup.getPeerId() } returns -1

        CrashReportUploader(setup, collector, uploader).uploadPending("1.21.1", "de")

        coVerify(exactly = 0) { uploader.upload(any(), any(), any()) }
        assertEquals(1, CrashReports.pending().size)
    }

    @Test
    fun `long reports are truncated`() {
        val f = File(tmp, "big.txt").apply { writeText("x".repeat(CrashReportUploader.MAX_REPORT_CHARS + 500)) }
        @Suppress("UNCHECKED_CAST")
        val report = (CrashReportUploader.crashSection(listOf(f))["reports"] as List<Map<String, Any?>>).single()
        assertTrue((report["text"] as String).endsWith("… [truncated]"))
    }
}
