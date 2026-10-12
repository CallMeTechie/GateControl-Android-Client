package com.gatecontrol.android.support

import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.SupportBundleUploader
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends the crash reports of earlier runs ([CrashReports]) to the server as
 * one support bundle (System › Support-Pakete), with the usual redaction.
 * Runs at app start; a report is deleted once the server has it, or when
 * the server can never take it (no support bundles, too large, invalid).
 */
@Singleton
class CrashReportUploader @Inject constructor(
    private val setupRepository: SetupRepository,
    private val collector: SupportBundleCollector,
    private val uploader: SupportBundleUploader,
) {
    private val running = AtomicBoolean(false)

    suspend fun uploadPending(appVersion: String, locale: String) {
        if (!running.compareAndSet(false, true)) return
        try {
            val files = CrashReports.pending()
            if (files.isEmpty()) return
            val serverUrl = setupRepository.getServerUrl()
            val peerId = setupRepository.getPeerId()
            if (serverUrl.isBlank() || peerId <= 0) return
            val bundle = collector.collect(
                appVersion = appVersion,
                locale = locale,
                settings = mapOf("trigger" to "crash_report"),
            ) + ("crash" to crashSection(files))
            val done = try {
                val response = uploader.upload(serverUrl, peerId, bundle)
                if (!response.ok) Timber.w("Crash report not accepted: %s", response.error)
                response.ok
            } catch (e: HttpException) {
                Timber.w("Crash report upload rejected: HTTP %d", e.code())
                e.code() in GIVE_UP_CODES
            }
            if (done) {
                files.forEach { it.delete() }
                Timber.i("Crash report sent (%d report(s))", files.size)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Crash report upload failed: %s", e.javaClass.simpleName)
        } finally {
            running.set(false)
        }
    }

    companion object {
        const val MAX_REPORT_CHARS = 32 * 1024

        /** Never accepted on a later try either: no endpoint, too large, invalid. */
        private val GIVE_UP_CODES = setOf(400, 404, 413)

        /** `crash` section of the bundle: newest report first. */
        fun crashSection(files: List<File>): Map<String, Any?> = mapOf(
            "count" to files.size,
            "reports" to files.map { f ->
                val text = runCatching { f.readText() }.getOrElse { "unreadable: ${it.javaClass.simpleName}" }
                mapOf(
                    "file" to f.name,
                    "text" to if (text.length > MAX_REPORT_CHARS) text.take(MAX_REPORT_CHARS) + "… [truncated]" else text,
                )
            },
        )
    }
}
