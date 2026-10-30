package com.gatecontrol.android.drive

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The pure rules of AI in Drive (Drive 1.14): what a scan may become and how it is named (D5), the filing suggestion
 * after an upload (D4), which deadline an answer speaks of and which deadlines are new (D1, D2), the terms of a photo
 * search and the labels a photo gets (D3), and what the phone itself reads off a scan (the table of the preview).
 * Nothing here talks to the network or Android.
 */
object DriveKi {

    // ─── D5: a scan becomes a document ─────────────────────────────

    /** What a scan can be saved as, in the order of the chips. */
    enum class Format(val ext: String, val mime: String, val target: String?) {
        /** A searchable PDF, made on the phone (no AI). */
        PDF("pdf", "application/pdf", null),
        DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
        XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
        /** As before: the pictures only. */
        IMAGE("jpg", "image/jpeg", null),
    }

    const val KIND_PRINTED = "printed"
    const val KIND_HANDWRITING = "handwriting"
    const val KIND_TABLE = "table"
    const val KIND_WHITEBOARD = "whiteboard"
    const val KIND_OTHER = "other"

    /**
     * The chips for a scan of [kind]: PDF and the picture always; Word for text, handwriting, a whiteboard and a table;
     * Excel only for a table. Without the AI ([ai] false: no `scanDocs`, the area without AI, the recognition failed)
     * only PDF and the picture.
     */
    fun formats(kind: String?, ai: Boolean): List<Format> = buildList {
        add(Format.PDF)
        if (ai && kind in setOf(KIND_PRINTED, KIND_HANDWRITING, KIND_WHITEBOARD, KIND_TABLE)) add(Format.DOCX)
        if (ai && kind == KIND_TABLE) add(Format.XLSX)
        add(Format.IMAGE)
    }

    /** The chip chosen first: Excel for a table, Word for handwriting and a whiteboard, else the server's setting. */
    fun defaultFormat(kind: String?, ai: Boolean, scanFormat: String?): Format {
        val offered = formats(kind, ai)
        val wanted = when (kind) {
            KIND_TABLE -> Format.XLSX
            KIND_HANDWRITING, KIND_WHITEBOARD -> Format.DOCX
            KIND_PRINTED -> Format.PDF
            else -> if (scanFormat == "image") Format.IMAGE else Format.PDF
        }
        return wanted.takeIf { it in offered } ?: Format.PDF
    }

    private val BAD_NAME = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+")
    private val SPACES = Regex("\\s+")
    private const val NAME_MAX = 80

    /** A name without what a file name may not hold, at most [NAME_MAX] characters; null when nothing is left. */
    fun cleanName(s: String?): String? =
        s?.replace(BAD_NAME, " ")?.replace(SPACES, " ")?.trim()?.trim('.', ' ', '-')?.take(NAME_MAX)?.trim()?.ifEmpty { null }

    /**
     * The scan's title: the first line the phone read that looks like one (3…60 characters, with letters, not only a
     * date or a number); null when there is none.
     */
    fun title(lines: List<String>): String? = lines.asSequence()
        .map { it.trim() }
        .filter { it.length in 3..60 && it.count(Char::isLetter) >= 3 }
        .mapNotNull { cleanName(it) }
        .firstOrNull()

    /** "2026-11-03 Sprint-Planung KW 45" from the title, else [fallback] ("Scan 2026-11-03 09.14"). */
    fun scanName(title: String?, day: LocalDate, fallback: String): String =
        cleanName(title)?.let { "${day.format(DateTimeFormatter.ISO_LOCAL_DATE)} $it" } ?: fallback

    /** [base] with the extension of [format] (a typed extension is replaced). */
    fun fileName(base: String, format: Format): String {
        val clean = cleanName(base) ?: "Scan"
        val known = Format.entries.map { it.ext } + listOf("jpeg", "png")
        val stem = clean.substringBeforeLast('.', clean).takeIf { clean.substringAfterLast('.', "").lowercase() in known } ?: clean
        return "$stem.${format.ext}"
    }

    /** A line the phone read, with its box in the picture's pixels. */
    data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val height: Int get() = (bottom - top).coerceAtLeast(1)
        val centerY: Float get() = (top + bottom) / 2f
        val centerX: Float get() = (left + right) / 2f
    }

    /** The beginning of the text for the Word preview: at most [max] non-empty lines. */
    fun docPreview(text: String?, max: Int = 8): List<String> =
        text.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.take(max)

    /**
     * A table read off the lines of a scan, for the preview (the AI builds the real one): lines whose middles lie at
     * about the same height form a row; the row with most cells gives the columns, every other cell goes to the
     * nearest one. At most [maxRows] rows and [maxCols] columns; empty when it is no table (fewer than two columns).
     */
    fun tableGrid(lines: List<OcrLine>, maxRows: Int = 5, maxCols: Int = 5): List<List<String>> {
        val cells = lines.filter { it.text.isNotBlank() }.sortedBy { it.centerY }
        if (cells.size < 4) return emptyList()
        val typical = cells.map { it.height }.sorted()[cells.size / 2].toFloat()
        val rows = ArrayList<MutableList<OcrLine>>()
        for (c in cells) {
            val row = rows.lastOrNull()
            if (row != null && kotlin.math.abs(row.map { it.centerY }.average().toFloat() - c.centerY) <= typical * 0.6f) row += c else rows += mutableListOf(c)
        }
        val anchors = rows.maxByOrNull { it.size }?.sortedBy { it.left }?.map { it.centerX } ?: return emptyList()
        if (anchors.size < 2) return emptyList()
        val cols = anchors.size.coerceAtMost(maxCols)
        return rows.filter { it.size >= 2 || rows.size <= maxRows }.take(maxRows).map { row ->
            val out = MutableList(cols) { "" }
            row.sortedBy { it.left }.forEach { cell ->
                val i = anchors.indices.minByOrNull { kotlin.math.abs(anchors[it] - cell.centerX) } ?: return@forEach
                if (i < cols) out[i] = listOf(out[i], cell.text.trim()).filter { it.isNotEmpty() }.joinToString(" ")
            }
            out
        }
    }

    // ─── D4: filing right after the upload ─────────────────────────

    /** The suggestion bar: the new [name] and / or [folderId] (with its path), and a payment to be reminded of. */
    data class Filing(val name: String?, val folderId: Long?, val folderPath: String?, val payment: DDeadline?)

    /** The bar for [insight], when it is ready and suggests a name or a folder; the payment only when still ahead and not reminded. */
    fun filing(insight: DInsight, today: LocalDate): Filing? {
        if (insight.status != "ready") return null
        val s = insight.suggestion ?: return null
        if (s.name == null && s.folderId == null) return null
        val payment = insight.deadlines
            .filter { it.kind == "payment" && it.reminder == null && (DriveLogic.daysUntil(it.date, today) ?: -1) >= 0 }
            .minByOrNull { it.date }
        return Filing(s.name, s.folderId, s.folderPath, payment)
    }

    /** Asked again after these pauses (about a minute in all), then the bar stays away. */
    val FILING_POLL_MS = listOf(3_000L, 4_000L, 5_000L, 8_000L, 10_000L, 12_000L, 18_000L)

    /** Whether an upload is waited for: a scan, or a document (no photo, video or music, no vault). */
    fun fileable(node: DNode, scan: Boolean): Boolean {
        if (node.isDir || node.encrypted) return false
        if (scan) return true
        return when (DriveLogic.kind(node)) {
            DriveLogic.Kind.IMAGE, DriveLogic.Kind.VIDEO, DriveLogic.Kind.AUDIO, DriveLogic.Kind.ARCHIVE -> false
            else -> true
        }
    }

    // ─── D2: deadlines ─────────────────────────────────────────────

    /** The deadlines not there at the last visit ("neu"); none at the very first one (nothing to compare with). */
    fun fresh(ids: Collection<Long>, seen: Set<Long>?): Set<Long> = if (seen == null) emptySet() else ids.toSet() - seen

    /** "In den Familienkalender" makes sense for a deadline still ahead. */
    fun calendarOpen(d: DDeadline, today: LocalDate): Boolean = (DriveLogic.daysUntil(d.date, today) ?: -1) >= 0

    // ─── D1: questions ─────────────────────────────────────────────

    /** A question rather than a file name: it ends with a question mark. */
    fun isQuestion(q: String): Boolean = q.trim().let { it.length >= 3 && (it.endsWith('?') || it.endsWith('？')) }

    private val DATE_DOTTED = Regex("(?<![0-9])([0-3]?[0-9])\\.\\s?([01]?[0-9])\\.(?:\\s?((?:19|20)?[0-9]{2}))?(?![0-9])")
    private val DATE_ISO = Regex("(?<![0-9])((?:19|20)[0-9]{2})-([01][0-9])-([0-3][0-9])(?![0-9])")

    /** Where dates stand in [text] (they are set in bold). */
    fun dateSpans(text: String): List<IntRange> =
        (DATE_DOTTED.findAll(text) + DATE_ISO.findAll(text)).map { it.range }.sortedBy { it.first }.toList()

    // ─── D3: photos by what is in them ─────────────────────────────

    /** Labels the labeler is at least this sure of go to Drive. */
    const val LABEL_MIN = 0.6f

    /** The labels of a photo for Drive: the surest first, at most 20, each at most 40 characters. */
    fun labels(found: List<Pair<String, Float>>, min: Float = LABEL_MIN): List<String> = found
        .filter { it.second >= min && it.first.isNotBlank() }
        .sortedByDescending { it.second }
        .map { it.first.trim().take(DriveJson.LABEL_MAX) }
        .distinctBy { it.lowercase() }
        .take(DriveJson.LABELS_MAX)

    /** The words of a photo search ("Strand Meer", "Strand + Meer"). */
    fun words(q: String): List<String> = q.split(Regex("[\\s+,]+")).map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

    /** The search with [term] added. */
    fun withTerm(q: String, term: String): String = (words(q) + term.trim()).distinctBy { it.lowercase() }.joinToString(" ").take(DriveJson.PHOTO_Q_MAX)

    /** The search without [term]. */
    fun withoutTerm(q: String, term: String): String = words(q).filterNot { it.equals(term.trim(), ignoreCase = true) }.joinToString(" ")

    /** A chip of the photo search: a word searched for ([on]) or a frequent term of the hits to add. */
    data class Chip(val term: String, val on: Boolean)

    /** The searched words first (on), then the hits' terms not searched yet, at most [max] in all. */
    fun chips(q: String, terms: List<String>, max: Int = 8): List<Chip> {
        val ws = words(q)
        val more = terms.filter { t -> ws.none { it.equals(t, ignoreCase = true) } }
        return (ws.map { Chip(it, true) } + more.map { Chip(it, false) }).take(max)
    }
}
