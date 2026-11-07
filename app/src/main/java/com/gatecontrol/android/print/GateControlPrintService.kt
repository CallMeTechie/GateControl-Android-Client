package com.gatecontrol.android.print

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintJobId
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import com.gatecontrol.android.R
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.support.CrashReports
import com.gatecontrol.android.ui.print.PrintInfoActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.Locale
import javax.inject.Inject

/**
 * "GateControl" in Android's print dialog (Settings › Druckdienste): the printers at home the person may use
 * through the print plugin, each as "GateControl – <name>". The dialog's PDF goes to the server (the app's normal
 * connection, with or without the VPN) and the gateway at home prints it — so this works away from home, where
 * the printer's own Wi-Fi service (mDNS in the local network) cannot find it. A printer that is off still takes
 * the job: it waits up to 24 hours ("Wartet, bis der Drucker an ist" as the job's blocked reason).
 *
 * No print right, no plugin (not installed, not licensed, not offered to the apps) or no connection: no printers;
 * [PrintInfoActivity] (this service's settings) says why.
 */
@AndroidEntryPoint
class GateControlPrintService : PrintService() {

    @Inject lateinit var sender: PrintSender
    @Inject lateinit var api: PrintApi
    @Inject lateinit var settingsRepository: SettingsRepository

    /** Print framework calls come on the main thread and PrintJob must be used there: everything runs on Main. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CrashReports.coroutineHandler)

    /** Jobs being sent or followed (main thread only). */
    private val running = mutableMapOf<PrintJobId, Job>()

    /** The last printers seen: shown again when the server is not reachable for a moment. */
    private var lastOverview: POverview? = null

    override fun onConnected() {
        super.onConnected()
        // after a restart of this process: follow what was already handed to the server, send what was not
        for (job in activePrintJobs) {
            val tag = job.tag
            if (PrintJson.validJobId(tag)) follow(job, tag!!) else if (job.isQueued || job.isStarted || job.isBlocked) send(job)
        }
    }

    override fun onDisconnected() {
        scope.coroutineContext.cancelChildren()
        running.clear()
        super.onDisconnected()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = Discovery()

    override fun onPrintJobQueued(printJob: PrintJob) = send(printJob)

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        val tag = printJob.tag
        if (!PrintJson.validJobId(tag)) {
            // not at the server yet: stop sending
            running.remove(printJob.id)?.cancel()
            if (!printJob.isCancelled) printJob.cancel()
            return
        }
        scope.launch {
            val r = api.cancel(tag!!)
            if (r is PResult.Ok && r.value) {
                running.remove(printJob.id)?.cancel()
                if (!printJob.isCancelled && !printJob.isCompleted && !printJob.isFailed) printJob.cancel()
            } else {
                // already on its way to the printer (only queued or waiting jobs can be stopped): it is followed on
                Timber.d("Print: job not canceled at the server")
            }
        }
    }

    private suspend fun texts(): Context = PrintTexts.localized(this, settingsRepository)

    // ─── sending ────────────────────────────────────────────────────

    /** Runs [block] for [job] unless something runs for it already; forgets it when done. */
    private fun launchFor(job: PrintJob, block: suspend CoroutineScope.() -> Unit) {
        val key = job.id
        if (running[key]?.isActive == true) return
        lateinit var self: Job
        self = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                if (running[key] === self) running.remove(key)
            }
        }
        running[key] = self
        self.start()
    }

    private fun send(job: PrintJob) = launchFor(job) { sendNow(job) }

    private suspend fun sendNow(job: PrintJob) {
        val t = texts()
        if (job.isQueued && !job.start()) return
        val info = job.info
        val deviceId = info.printerId?.localId?.takeIf { PrintJson.validDeviceId(it) }
            ?: return failJob(job, t.getString(R.string.print_err_unavailable))
        job.setStatus(t.getString(R.string.print_status_uploading))
        val pfd = runCatching { job.document.data }.getOrNull() ?: return failJob(job, t.getString(R.string.print_err_read))
        val file = withContext(Dispatchers.IO) { copy(pfd) } ?: return failJob(job, t.getString(R.string.print_err_read))
        val r = try {
            val attrs = info.attributes
            val color = attrs.colorMode == PrintAttributes.COLOR_MODE_COLOR
            val duplex = attrs.duplexMode == PrintAttributes.DUPLEX_MODE_LONG_EDGE || attrs.duplexMode == PrintAttributes.DUPLEX_MODE_SHORT_EDGE
            val copies = info.copies.coerceIn(1, 99)
            val name = PrintLogic.jobName(info.label.ifBlank { job.document.info.name })
            val since = System.currentTimeMillis()
            // the paper chosen in the dialog, when the printer names it (plugin 1.1.0) – else its own default
            val printer = lastOverview?.printers?.firstOrNull { it.id == deviceId }
            val media = printer?.let { PrintLogic.mediaFor(attrs.mediaSize?.id, it) }
            var sent = sender.send(file, "application/pdf", deviceId, copies, color, duplex, name, media)
            // no connection: the job stays in Android's queue and is tried again for a while
            while (sent is PResult.Offline && System.currentTimeMillis() - since < OFFLINE_RETRY_MAX_MS) {
                if (job.isCancelled) return
                job.block(t.getString(R.string.print_wait_connection))
                delay(OFFLINE_RETRY_MS)
                sent = sender.send(file, "application/pdf", deviceId, copies, color, duplex, name, media)
            }
            sent
        } finally {
            file.delete()
        }
        if (job.isCancelled) return
        when (r) {
            is PResult.Ok -> {
                if (job.isBlocked) job.start()
                job.setTag(r.value.job.id)
                apply(job, r.value.job, t)
                if (!PrintLogic.isFinal(PrintLogic.phase(r.value.job))) followNow(job, r.value.job.id)
            }
            else -> failJob(job, PrintTexts.failure(t, r))
        }
    }

    private fun failJob(job: PrintJob, reason: String) {
        if (!job.isCancelled && !job.isCompleted && !job.isFailed) job.fail(reason)
    }

    /** The framework's PDF into the cache (the descriptor is read once and closed). */
    private fun copy(pfd: ParcelFileDescriptor): File? {
        val dir = File(cacheDir, "print").apply { mkdirs() }
        val target = File.createTempFile("job", ".pdf", dir)
        return try {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > PrintLogic.MAX_BYTES + 1) break
                        out.write(buf, 0, n)
                    }
                }
            }
            target
        } catch (e: Exception) {
            Timber.d("Print: document not read (%s)", e.javaClass.simpleName)
            target.delete()
            null
        }
    }

    // ─── following ──────────────────────────────────────────────────

    /** Asks the server for [serverId] until it is done, failed or canceled (at most [PrintLogic.TRACK_MAX_MS]). */
    private fun follow(job: PrintJob, serverId: String) = launchFor(job) { followNow(job, serverId) }

    private suspend fun followNow(job: PrintJob, serverId: String) {
        val created = job.info.creationTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        val started = System.currentTimeMillis()
        var missing = 0
        var phase = PrintLogic.Phase.RUNNING
        while (currentCoroutineContext().isActive) {
            if (job.isCancelled || job.isCompleted || job.isFailed) return
            if (System.currentTimeMillis() - created > PrintLogic.TRACK_MAX_MS) {
                failJob(job, texts().getString(R.string.print_fail_expired))
                return
            }
            when (val r = sender.job(serverId)) {
                is PResult.Ok -> {
                    val j = r.value
                    if (j == null) {
                        // no longer among the last jobs: long done (the server's notice says how it went)
                        if (++missing >= PrintLogic.MISSING_MAX) {
                            if (job.isBlocked) job.start()
                            job.complete()
                            return
                        }
                    } else {
                        missing = 0
                        phase = PrintLogic.phase(j)
                        apply(job, j, texts())
                        if (PrintLogic.isFinal(phase)) return
                    }
                }
                // offline or a hiccup: ask again later, the job is safe at the server
                else -> Unit
            }
            delay(PrintLogic.pollDelayMs(System.currentTimeMillis() - started, phase))
        }
    }

    /** A server state onto the framework's job. */
    private fun apply(job: PrintJob, j: PJob, t: Context) {
        if (job.isCancelled || job.isCompleted || job.isFailed) return
        when (PrintLogic.phase(j)) {
            // the printer stopped while printing (paper out, cover open …): blocked with the reason until it goes on
            PrintLogic.Phase.STOPPED -> {
                if (job.isQueued) job.start()
                job.block(PrintTexts.stopped(t, PrintLogic.stopReason(j)))
            }
            PrintLogic.Phase.RUNNING -> {
                if (job.isBlocked || job.isQueued) job.start()
                job.setStatus(PrintTexts.state(t, j))
            }
            PrintLogic.Phase.WAITING -> {
                if (job.isQueued) job.start()
                job.block(PrintTexts.waiting(t, j.error))
            }
            PrintLogic.Phase.DONE -> {
                if (job.isBlocked || job.isQueued) job.start()
                job.complete()
            }
            PrintLogic.Phase.FAILED -> job.fail(PrintTexts.reason(t, PrintLogic.failReason(j)))
            PrintLogic.Phase.CANCELED -> job.cancel()
        }
    }

    // ─── printers ───────────────────────────────────────────────────

    private inner class Discovery : PrinterDiscoverySession() {
        private var load: Job? = null

        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) = refresh()

        override fun onStopPrinterDiscovery() {
            load?.cancel()
        }

        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) = refresh()

        override fun onStartPrinterStateTracking(printerId: PrinterId) = refresh()

        override fun onStopPrinterStateTracking(printerId: PrinterId) = Unit

        override fun onDestroy() {
            load?.cancel()
        }

        private fun refresh() {
            load?.cancel()
            load = scope.launch {
                val t = texts()
                val overview = when (val r = sender.overview(maxAgeMs = 5_000L)) {
                    is PResult.Ok -> r.value.also { lastOverview = it }
                    // a moment without connection: the printers seen last (jobs then wait in Android's queue)
                    PResult.Offline -> lastOverview
                    // no plugin for the apps, no right, not set up: no printers
                    else -> null.also { lastOverview = null }
                }
                if (isDestroyed) return@launch
                val infos = overview?.printers.orEmpty().map { printerInfo(it, overview!!, t) }
                val keep = infos.map { it.id }.toSet()
                val gone = printers.map { it.id }.filter { it !in keep }
                if (gone.isNotEmpty()) removePrinters(gone)
                if (infos.isNotEmpty()) addPrinters(infos)
            }
        }
    }

    private fun printerInfo(p: PPrinter, overview: POverview, t: Context): PrinterInfo {
        val id = generatePrinterId(p.id)
        val status = when (PrintLogic.availability(p)) {
            PrintLogic.Availability.IDLE -> PrinterInfo.STATUS_IDLE
            PrintLogic.Availability.BUSY -> PrinterInfo.STATUS_BUSY
        }
        val color = PrintLogic.colorAllowed(p, overview.rights)
        val letter = Locale.getDefault().country.uppercase() in LETTER_COUNTRIES
        val builder = PrinterCapabilitiesInfo.Builder(id)
        // the paper the printer names (plugin 1.1.0, gateway 1.33.0) – else A4 and Letter as before
        val named = PrintLogic.dialogMedia(p)
        if (named.isNotEmpty()) {
            val dflt = p.mediaDefault?.takeIf { it in named } ?: named.first()
            named.forEach { m -> mediaSize(m, t)?.let { builder.addMediaSize(it, m == dflt) } }
        } else {
            builder.addMediaSize(PrintAttributes.MediaSize.ISO_A4, !letter)
            builder.addMediaSize(PrintAttributes.MediaSize.NA_LETTER, letter)
        }
        val caps = builder
            .addResolution(PrintAttributes.Resolution("r300", t.getString(R.string.print_resolution_300), 300, 300), true)
            .setColorModes(
                if (color) PrintAttributes.COLOR_MODE_COLOR or PrintAttributes.COLOR_MODE_MONOCHROME else PrintAttributes.COLOR_MODE_MONOCHROME,
                // the plugin's own default is black and white (portal: "Farbe" off)
                PrintAttributes.COLOR_MODE_MONOCHROME,
            )
            .setDuplexModes(
                if (p.duplex) PrintAttributes.DUPLEX_MODE_NONE or PrintAttributes.DUPLEX_MODE_LONG_EDGE else PrintAttributes.DUPLEX_MODE_NONE,
                PrintAttributes.DUPLEX_MODE_NONE,
            )
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
        val notes = buildList {
            p.location?.let(::add)
            when (PrintLogic.note(p)) {
                PrintLogic.Note.OFF -> add(t.getString(R.string.print_printer_off))
                PrintLogic.Note.BUSY -> add(t.getString(R.string.print_printer_busy))
                PrintLogic.Note.STOPPED -> add(t.getString(R.string.print_printer_stopped))
                PrintLogic.Note.CERT -> add(t.getString(R.string.print_printer_cert))
                null -> Unit
            }
            overview.pagesLeft?.let { add(t.resources.getQuantityString(R.plurals.print_pages_left, it, it)) }
            if (p.color && overview.rights.bwOnly) add(t.getString(R.string.print_bw_only))
        }
        val infoIntent = PendingIntent.getActivity(
            this, 0, Intent(this, PrintInfoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return PrinterInfo.Builder(id, t.getString(R.string.print_printer_name, p.name), status)
            .setDescription(notes.joinToString(" · ").ifEmpty { t.getString(R.string.print_printer_via) })
            .setCapabilities(caps)
            .setInfoIntent(infoIntent)
            .build()
    }

    /** Android's paper for a PWG name ([PrintLogic.ANDROID_MEDIA]); photo sizes Android has no constant for are built here. */
    private fun mediaSize(pwg: String, t: Context): PrintAttributes.MediaSize? = when (pwg) {
        "iso_a4_210x297mm" -> PrintAttributes.MediaSize.ISO_A4
        "iso_a5_148x210mm" -> PrintAttributes.MediaSize.ISO_A5
        "iso_a6_105x148mm" -> PrintAttributes.MediaSize.ISO_A6
        "na_letter_8.5x11in" -> PrintAttributes.MediaSize.NA_LETTER
        "na_legal_8.5x14in" -> PrintAttributes.MediaSize.NA_LEGAL
        "na_index-4x6_4x6in" -> PrintAttributes.MediaSize.NA_INDEX_4X6
        "na_5x7_5x7in" -> PrintAttributes.MediaSize(pwg, t.getString(R.string.print_paper_5x7), 5000, 7000)
        "oe_photo-l_3.5x5in" -> PrintAttributes.MediaSize(pwg, t.getString(R.string.print_paper_3_5x5), 3500, 5000)
        else -> null
    }

    private companion object {
        const val OFFLINE_RETRY_MS = 30_000L
        const val OFFLINE_RETRY_MAX_MS = 30 * 60_000L
        /** Where Letter is the paper of choice (else A4). */
        val LETTER_COUNTRIES = setOf("US", "CA", "MX", "PH", "CL", "CO", "VE", "GT", "CR", "PA", "DO", "SV", "PR")
    }
}
