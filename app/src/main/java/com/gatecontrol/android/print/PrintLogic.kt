package com.gatecontrol.android.print

/**
 * The rules of printing through GateControl, without Android: which files go up as they are, what the
 * printer and the person's rights allow, how a job's state maps to Android's print framework, how often a
 * job is asked for. Pure, unit-tested.
 */
object PrintLogic {

    /** The plugin's `media.types` (plugin.json of gatecontrol-print 1.0): uploads of other types are refused (415). */
    val UPLOAD_TYPES: Set<String> = setOf("application/pdf", "image/jpeg", "image/png", "image/webp", "image/heic", "image/heif")

    /** The plugin's `media.maxBytes` (50 MB). */
    const val MAX_BYTES = 50L * 1024 * 1024

    /** At most this many files from one share (each becomes a job; the server allows 20 open jobs a person). */
    const val MAX_SHARED = 20

    /** How a shared file goes up. */
    enum class Upload { AS_IS, AS_JPEG }

    /**
     * [mime] of a shared file → how it goes up, or null when it cannot be printed (Office, text …: the plugin
     * prints PDF files and pictures only). Other pictures (GIF, BMP …) are turned into a JPEG on the device: the
     * server would print them, but the plugin's media types do not take them.
     */
    fun upload(mime: String?): Upload? {
        val m = mime?.lowercase()?.substringBefore(';')?.trim() ?: return null
        return when {
            m in UPLOAD_TYPES -> Upload.AS_IS
            m == "image/jpg" -> Upload.AS_IS
            m.startsWith("image/") && m != "image/svg+xml" -> Upload.AS_JPEG
            else -> null
        }
    }

    /**
     * The type of a file by its first bytes (up to 1024): a PDF a provider hands over as
     * `application/octet-stream` or under a name without ".pdf" is still a PDF. null when nothing is recognised.
     */
    fun sniff(head: ByteArray?): String? {
        if (head == null || head.size < 4) return null
        fun at(text: String, offset: Int = 0): Boolean =
            head.size >= offset + text.length && text.indices.all { head[offset + it] == text[it].code.toByte() }
        val pdf = "%PDF-"
        val limit = minOf(head.size, 1024) - pdf.length
        for (i in 0..limit) if (at(pdf, i)) return "application/pdf"
        val b = { i: Int -> head[i].toInt() and 0xff }
        if (b(0) == 0xff && b(1) == 0xd8 && b(2) == 0xff) return "image/jpeg"
        if (head.size >= 8 && b(0) == 0x89 && at("PNG\r\n", 1) && b(6) == 0x1a && b(7) == 0x0a) return "image/png"
        if (at("GIF87a") || at("GIF89a")) return "image/gif"
        if (at("RIFF") && at("WEBP", 8)) return "image/webp"
        if (at("ftyp", 4) && head.size >= 12) {
            val brand = String(head, 8, 4, Charsets.ISO_8859_1)
            if (brand in setOf("heic", "heix", "hevc", "hevx")) return "image/heic"
            if (brand in setOf("mif1", "msf1")) return "image/heif"
        }
        return null
    }

    /**
     * The type a shared file goes up as: the one its content shows when the declared one ([declared]: provider,
     * share, extension) is missing, not printable, or claims PDF for something else — else the declared one.
     */
    fun effectiveType(declared: String?, head: ByteArray?): String? {
        val seen = sniff(head)
        val d = declared?.lowercase()?.substringBefore(';')?.trim()
        return when {
            seen == null -> declared
            d == null || upload(d) == null -> seen
            d == "application/pdf" && seen != "application/pdf" -> seen
            seen == "application/pdf" && d != "application/pdf" -> seen
            else -> declared
        }
    }

    /** The type sent with an upload of [mime] (the core checks it against the plugin's types). */
    fun uploadType(mime: String?): String {
        val m = mime?.lowercase()?.substringBefore(';')?.trim().orEmpty()
        return when {
            m == "image/jpg" -> "image/jpeg"
            m in UPLOAD_TYPES -> m
            else -> "image/jpeg"
        }
    }

    /** Colour can be chosen: the printer prints colour and the person may (not "nur schwarz-weiß"). */
    fun colorAllowed(printer: PPrinter, rights: PRights): Boolean = printer.color && !rights.bwOnly

    /** Copies the person may pick for [printer]. */
    fun copiesRange(printer: PPrinter): IntRange = 1..printer.copiesMax.coerceIn(1, 99)

    /** How the printer appears in Android's print dialog. */
    enum class Availability { IDLE, BUSY }

    /**
     * A printer that is off still takes jobs: they wait up to 24 hours. So it is never "unavailable" for the
     * print dialog — only "busy" while it prints, stopped, or is switched off.
     */
    fun availability(printer: PPrinter): Availability =
        if (printer.online && printer.state == "idle" && printer.problem == null) Availability.IDLE else Availability.BUSY

    /** Why a printer is not ready right now, for its line in the print dialog (null: ready). */
    enum class Note { OFF, BUSY, STOPPED, CERT }

    fun note(printer: PPrinter): Note? = when {
        printer.problem == "cert_changed" -> Note.CERT
        !printer.online || printer.state == "offline" -> Note.OFF
        printer.state == "stopped" -> Note.STOPPED
        printer.state == "processing" -> Note.BUSY
        else -> null
    }

    /** A job's state in the print framework's words. */
    enum class Phase { RUNNING, WAITING, DONE, FAILED, CANCELED }

    /**
     * Job states (docs/plugins.md `gc.print`): `queued` → `sending` → `printing` → `done`; `waiting` while the
     * printer is off or busy (tried again every 2 minutes); `failed`, `canceled`, `expired` (not printed within
     * 24 h) are final. Unknown states count as running.
     */
    fun phase(state: String): Phase = when (state) {
        "done" -> Phase.DONE
        "failed", "expired" -> Phase.FAILED
        "canceled" -> Phase.CANCELED
        "waiting" -> Phase.WAITING
        else -> Phase.RUNNING
    }

    fun isFinal(phase: Phase): Boolean = phase == Phase.DONE || phase == Phase.FAILED || phase == Phase.CANCELED

    /** The reason of a failed job (`expired` has none of its own). */
    fun failReason(job: PJob): String = job.error ?: if (job.state == "expired") "expired" else "failed"

    /**
     * How long until a job is asked for again: often right after sending (it usually prints within a minute),
     * then less often; a waiting job (printer off) every 2 minutes — the gateway tries no more often.
     */
    fun pollDelayMs(elapsedMs: Long, phase: Phase): Long = when {
        phase == Phase.WAITING -> 120_000L
        elapsedMs < 2 * 60_000L -> 5_000L
        elapsedMs < 15 * 60_000L -> 20_000L
        else -> 60_000L
    }

    /** A job is followed at most this long (24 h on the server, plus a margin). */
    const val TRACK_MAX_MS = 25L * 60 * 60_000L

    /** A job that no longer shows in `overview.jobs` (only the last ten) for this many answers counts as done. */
    const val MISSING_MAX = 3

    /** The job's name on the printer and in the history: the document's own name, else "GateControl". */
    fun jobName(label: String?): String =
        PrintJson.text(label, 120) ?: "GateControl"
}
