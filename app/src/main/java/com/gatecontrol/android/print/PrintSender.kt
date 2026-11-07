package com.gatecontrol.android.print

import android.content.Context
import android.content.res.Configuration
import com.gatecontrol.android.R
import com.gatecontrol.android.data.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends a file to a printer at home through GateControl (upload, then `POST print`) and reads job states — the
 * one path of the print service and of "Mit GateControl drucken". The overview is shared for a few seconds, so
 * several jobs followed at once ask the server once.
 */
@Singleton
class PrintSender @Inject constructor(private val api: PrintApi) {

    private val mutex = Mutex()
    @Volatile private var cached: Pair<Long, POverview>? = null

    /** The overview, at most [maxAgeMs] old. */
    suspend fun overview(maxAgeMs: Long = 0L): PResult<POverview> = mutex.withLock {
        val c = cached
        if (c != null && maxAgeMs > 0 && System.currentTimeMillis() - c.first <= maxAgeMs) return@withLock PResult.Ok(c.second)
        val r = api.overview()
        if (r is PResult.Ok) cached = System.currentTimeMillis() to r.value
        r
    }

    /** A job of the person by its id (null: not among the last jobs any more). */
    suspend fun job(id: String): PResult<PJob?> = when (val r = overview(maxAgeMs = 3_000L)) {
        is PResult.Ok -> PResult.Ok(r.value.jobs.firstOrNull { it.id == id })
        is PResult.Failed -> r
        PResult.Offline -> PResult.Offline
        PResult.NotConfigured -> PResult.NotConfigured
    }

    /**
     * [file] ([mime] one of the plugin's media types) → a print job on [deviceId]. A file the virus scan of the
     * server still checks cannot be read for a moment (`print_failed`): asked twice more after a short wait.
     */
    suspend fun send(file: File, mime: String, deviceId: String, copies: Int, color: Boolean, duplex: Boolean, name: String): PResult<PPrinted> {
        if (file.length() > PrintLogic.MAX_BYTES) return PResult.Failed("too_large", 413)
        if (file.length() <= 0L) return PResult.Failed("read", 0)
        val mediaId = when (val u = api.upload(file, PrintLogic.uploadType(mime))) {
            is PResult.Ok -> u.value
            is PResult.Failed -> return u
            PResult.Offline -> return PResult.Offline
            PResult.NotConfigured -> return PResult.NotConfigured
        }
        var attempt = 0
        while (true) {
            val r = api.print(deviceId, mediaId, copies, color, duplex, name)
            if (r is PResult.Failed && r.code == "print_failed" && attempt < RETRIES) {
                attempt++
                delay(RETRY_MS * attempt)
                continue
            }
            if (r is PResult.Ok) cached = null
            return r
        }
    }

    private companion object {
        const val RETRIES = 2
        const val RETRY_MS = 4_000L
    }
}

/** The words of printing (the app's language setting, also in the print service). */
object PrintTexts {

    suspend fun localized(context: Context, settingsRepository: SettingsRepository): Context {
        val lang = runCatching { settingsRepository.getLocale().first() }.getOrNull()?.takeIf { it == "de" || it == "en" } ?: return context
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) }
        return context.createConfigurationContext(config)
    }

    /** Why sending did not work: the plugin's own sentence where it has one, else the app's. */
    fun failure(context: Context, r: PResult<*>): String = when (r) {
        PResult.Offline -> context.getString(R.string.print_err_offline)
        PResult.NotConfigured -> context.getString(R.string.print_err_not_configured)
        is PResult.Ok -> ""
        is PResult.Failed -> when (r.code) {
            "too_large" -> r.text ?: context.getString(R.string.print_err_too_large)
            // the plugin (1.0.3+) names the real reason: not a PDF or picture, damaged, a printer without PDF whose
            // gateway is too old / has converting off / knows no format to convert to — its own sentence
            "unsupported_type", "format", "damaged", "printer_format", "gateway_update", "convert_off" -> r.text ?: context.getString(R.string.print_err_format)
            "quota_exceeded" -> context.getString(R.string.print_err_quota)
            "infected", "quarantined" -> context.getString(R.string.print_err_infected)
            "scan_unavailable", "scan_pending" -> context.getString(R.string.print_err_scan)
            "limit" -> r.text ?: if (r.left != null && r.pages != null) context.getString(R.string.print_err_limit, r.pages, r.left) else context.getString(R.string.print_err_failed)
            "no_right", "forbidden" -> r.text ?: context.getString(R.string.print_err_no_right)
            "read" -> context.getString(R.string.print_err_read)
            "not_found" -> if (r.status == 404 && r.text == null) context.getString(R.string.print_err_unavailable) else r.text ?: context.getString(R.string.print_err_unavailable)
            else -> r.text ?: context.getString(R.string.print_err_failed)
        }
    }

    /** A job's state in words (sheet and print dialog). */
    fun state(context: Context, job: PJob): String = when (PrintLogic.phase(job.state)) {
        PrintLogic.Phase.RUNNING -> context.getString(
            when (job.state) {
                // the gateway turns the PDF into the printer's own format first (a printer without PDF)
                "sending" -> if (job.message == "converting") R.string.print_state_converting else R.string.print_state_sending
                "printing" -> R.string.print_state_printing
                else -> R.string.print_state_queued
            },
        )
        PrintLogic.Phase.WAITING -> waiting(context, job.error)
        PrintLogic.Phase.DONE -> context.getString(if (job.message == "unconfirmed") R.string.print_state_done_unconfirmed else R.string.print_state_done)
        PrintLogic.Phase.FAILED -> reason(context, PrintLogic.failReason(job))
        PrintLogic.Phase.CANCELED -> context.getString(R.string.print_state_canceled)
    }

    /** "Wartet, bis der Drucker an ist" and its other reasons (gateway docs/print.md, states → waiting). */
    fun waiting(context: Context, error: String?): String = context.getString(
        when (error) {
            "printer_busy" -> R.string.print_wait_busy
            "printer_not_accepting" -> R.string.print_wait_not_accepting
            "cert_changed" -> R.string.print_wait_cert
            else -> R.string.print_wait_printer_off
        },
    )

    /** Why a job failed (error codes of the gateway and the server). */
    fun reason(context: Context, error: String): String = context.getString(
        when (error) {
            "format_unsupported" -> R.string.print_fail_format
            "convert_failed" -> R.string.print_fail_convert
            "convert_too_large" -> R.string.print_fail_convert_large
            "printer_rejected" -> R.string.print_fail_rejected
            "canceled_at_printer", "aborted_at_printer" -> R.string.print_fail_at_printer
            "expired", "printer_offline" -> R.string.print_fail_expired
            "not_a_printer", "device_not_allowed" -> R.string.print_fail_device
            else -> R.string.print_fail_failed
        },
    )
}
