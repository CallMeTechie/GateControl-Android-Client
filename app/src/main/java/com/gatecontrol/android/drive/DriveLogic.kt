package com.gatecontrol.android.drive

import java.util.Locale

/** Pure helpers of the Drive tab (unit-tested). */
internal object DriveLogic {

    /** What a file is, for its icon and its tile in "Zuletzt". */
    enum class Kind { FOLDER, IMAGE, VIDEO, AUDIO, PDF, TEXT, DOC, SHEET, SLIDES, ARCHIVE, OTHER }

    fun kind(node: DNode): Kind = if (node.isDir) Kind.FOLDER else kind(node.mime, node.name)

    fun kind(mime: String?, name: String): Kind {
        val m = mime.orEmpty().lowercase(Locale.ROOT)
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            m.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp") -> Kind.IMAGE
            m.startsWith("video/") || ext in setOf("mp4", "mov", "mkv", "webm", "avi", "3gp") -> Kind.VIDEO
            m.startsWith("audio/") || ext in setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac") -> Kind.AUDIO
            m == "application/pdf" || ext == "pdf" -> Kind.PDF
            ext in setOf("doc", "docx", "odt", "rtf", "pages") || m.contains("wordprocessing") || m == "application/msword" -> Kind.DOC
            ext in setOf("xls", "xlsx", "ods", "csv", "numbers") || m.contains("spreadsheet") -> Kind.SHEET
            ext in setOf("ppt", "pptx", "odp", "key") || m.contains("presentation") -> Kind.SLIDES
            ext in setOf("zip", "7z", "rar", "tar", "gz", "tgz") || m.contains("zip") || m.contains("compressed") -> Kind.ARCHIVE
            m.startsWith("text/") || ext in setOf("txt", "md", "json", "xml", "log") -> Kind.TEXT
            else -> Kind.OTHER
        }
    }

    /**
     * Files the server cannot make a preview of (HEIC/HEIF photos, videos): the uploader sends its own picture.
     * JPEG, PNG, WebP, GIF and AVIF the server makes itself (it refuses one sent for them).
     */
    fun needsOwnPreview(mime: String?): Boolean {
        val m = mime.orEmpty().lowercase(Locale.ROOT).substringBefore(';').trim()
        return m == "image/heic" || m == "image/heif" || m.startsWith("video/")
    }

    /** Short label of a tile ("PDF", "FOTO" …): the extension, else the kind. */
    fun badge(node: DNode, photo: String): String {
        val ext = node.name.substringAfterLast('.', "").uppercase(Locale.ROOT).take(4)
        return when (kind(node)) {
            Kind.IMAGE -> photo
            else -> ext.ifEmpty { "…" }
        }
    }

    /** 1,2 MB style, with the locale's decimal separator. */
    fun size(bytes: Long, locale: Locale = Locale.getDefault()): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = bytes / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return if (v >= 100 || i == 0) String.format(locale, "%.0f %s", v, units[i]) else String.format(locale, "%.1f %s", v, units[i])
    }

    private val FORBIDDEN = Regex("[/\\\\:*?\"<>|\\u0000-\\u001f]")

    /**
     * A name Drive takes (plugin README: no `/ \ : * ? " < > |`, no trailing dot or space, ≤ 255 characters, not
     * `.gcdrive`): forbidden characters become `_`; null when nothing is left.
     */
    fun safeName(raw: String?): String? {
        val s = raw.orEmpty().replace(FORBIDDEN, "_").trim().trimEnd('.', ' ').take(255).trimEnd('.', ' ')
        if (s.isEmpty() || s == "." || s == ".." || s.equals(".gcdrive", ignoreCase = true)) return null
        return s
    }

    /** Whether [raw] is a valid new name as typed (before any replacing). */
    fun validName(raw: String): Boolean = raw.isNotBlank() && !FORBIDDEN.containsMatchIn(raw) && safeName(raw) == raw.trim()

    /**
     * "Zuletzt": the newest files that were created or changed, newest first, each once, without the ones that are
     * gone (a later delete or a move out of the areas the viewer sees).
     */
    fun recent(changes: List<DChange>, max: Int = 6): List<DNode> {
        val gone = HashSet<Long>()
        val seen = HashSet<Long>()
        val out = ArrayList<DNode>()
        for (c in changes.sortedByDescending { it.seq }) {
            val id = c.nodeId ?: continue
            if (c.op == "delete" || c.op == "purge") {
                gone.add(id)
                continue
            }
            if (c.op !in setOf("create", "update", "restore", "move")) continue
            val n = c.node ?: continue
            if (n.isDir || id in gone || !seen.add(id)) continue
            out.add(n)
            if (out.size >= max) break
        }
        return out
    }

    /** Order of the areas: my files, the family, the children, the teams (then by label). */
    fun sortAreas(list: List<DArea>): List<DArea> {
        val rank = mapOf("mine" to 0, "family" to 1, "child" to 2, "team" to 3)
        return list.sortedWith(compareBy<DArea>({ rank[it.kind] ?: 9 }, { it.label.lowercase(Locale.ROOT) }))
    }

    /** Usage of an area: "38 GB" or "38 GB von 50 GB". */
    fun usage(storage: DStorage?, of: String, locale: Locale = Locale.getDefault()): String? {
        val bytes = storage?.bytes ?: return null
        val quota = storage.quota?.takeIf { it > 0 }
        return if (quota == null) size(bytes, locale) else "${size(bytes, locale)} $of ${size(quota, locale)}"
    }

    /** Where a picked file goes when the name is taken: a new version when it is the same file kind, else a free name. */
    /**
     * The name a scan is saved under, without extension: what the person typed (a `.pdf`/`.jpg` of their own taken
     * off, forbidden characters replaced), or [fallback] when nothing is left.
     */
    fun scanBase(typed: String, fallback: String): String =
        safeName(typed.trim().replace(SCAN_EXT, ""))?.take(200)?.trimEnd('.', ' ')?.ifEmpty { null } ?: fallback

    /** The files of a scan: one PDF, or one picture per page ("Name.jpg"; several pages: "Name 1.jpg", "Name 2.jpg" …). */
    fun scanFiles(base: String, pdf: Boolean, pages: Int): List<String> = when {
        pdf -> listOf("$base.pdf")
        pages <= 1 -> listOf("$base.jpg")
        else -> (1..pages).map { "$base $it.jpg" }
    }

    /** The text of a whole scan for the server: the pages' texts, blank ones left out; null when there is none. */
    fun scanText(pages: List<String?>): String? = pages.mapNotNull { it?.trim()?.ifEmpty { null } }.joinToString("\n\n").ifEmpty { null }

    private val SCAN_EXT = Regex("\\.(pdf|jpe?g)$", RegexOption.IGNORE_CASE)

    const val ON_CONFLICT_RENAME = "rename"
    const val ON_CONFLICT_VERSION = "version"
}
