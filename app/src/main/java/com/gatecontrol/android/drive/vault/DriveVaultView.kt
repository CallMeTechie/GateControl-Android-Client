package com.gatecontrol.android.drive.vault

/**
 * What the in-app viewer of a vault file does with it (Drive plugin 1.22) — pure, unit-tested.
 *
 * The rules (README of the plugin, "Viewing vault files"): a vault file is decrypted only in memory of this app —
 * pictures and texts as bytes on the heap, a PDF in an anonymous memory file (memfd, never a path on the storage) for
 * Android's PdfRenderer, a video chunk by chunk while it plays (its ciphertext, as the server keeps it, in the cache).
 * Nothing goes to MediaStore, to the cache in plain text, to a thumbnail or to another app — unless the person chooses
 * "Mit anderer App öffnen", which says what it means first.
 */
object DriveVaultView {
    enum class Kind { IMAGE, PDF, TEXT, MARKDOWN, VIDEO, OFFICE, OTHER }

    private const val MB = 1_048_576L

    /** What is decrypted into memory at most, by the size of the ciphertext (a little larger than the file). */
    val LIMITS = mapOf(
        Kind.IMAGE to 64 * MB,
        Kind.PDF to 200 * MB,
        Kind.TEXT to 8 * MB,
        Kind.MARKDOWN to 8 * MB,
        // the ciphertext of a video waits in the cache (encrypted); the plaintext is only ever one chunk
        Kind.VIDEO to 4096 * MB,
    )

    /** "Mit anderer App öffnen": the file in an anonymous memory file for the other app — at most this large. */
    const val SHARE_LIMIT = 512 * MB

    private val IMAGE = setOf("jpg", "jpeg", "jfif", "png", "gif", "webp", "heic", "heif", "avif", "bmp")
    private val TEXT = setOf(
        "txt", "text", "log", "csv", "tsv", "json", "xml", "yml", "yaml", "ini", "conf", "cfg", "toml", "env", "sh", "ps1",
        "bat", "js", "ts", "py", "java", "kt", "c", "h", "cpp", "cs", "go", "rs", "sql", "html", "htm", "css", "vcf", "ics",
    )
    private val MARKDOWN = setOf("md", "markdown", "mdown", "mkd")
    private val VIDEO = setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp", "ts")
    private val OFFICE = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "pages", "numbers", "key")

    fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..8 && it != name.lowercase() } ?: ""

    fun kindOf(name: String): Kind {
        val e = extOf(name)
        return when (e) {
            in IMAGE -> Kind.IMAGE
            "pdf" -> Kind.PDF
            in MARKDOWN -> Kind.MARKDOWN
            in TEXT -> Kind.TEXT
            in VIDEO -> Kind.VIDEO
            in OFFICE -> Kind.OFFICE
            else -> Kind.OTHER
        }
    }

    /** Whether a ciphertext of [cipherSize] bytes is too large for the viewer of [kind]. */
    fun tooLarge(kind: Kind, cipherSize: Long): Boolean = LIMITS[kind]?.let { cipherSize > it } ?: false

    /** The limit of [kind] in whole MB (for the note). */
    fun limitMb(kind: Kind): Long = (LIMITS[kind] ?: 0L) / MB

    /** A MIME type for the other app (never the server's — a vault file's is always application/octet-stream). */
    fun mimeOf(name: String): String = when (extOf(name)) {
        "jpg", "jpeg", "jfif" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "avif" -> "image/avif"
        "bmp" -> "image/bmp"
        "pdf" -> "application/pdf"
        "md", "markdown", "mdown", "mkd" -> "text/markdown"
        "txt", "text", "log" -> "text/plain"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "xml" -> "text/xml"
        "html", "htm" -> "text/html"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "3gp" -> "video/3gpp"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "odt" -> "application/vnd.oasis.opendocument.text"
        "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
        "odp" -> "application/vnd.oasis.opendocument.presentation"
        "rtf" -> "application/rtf"
        else -> "application/octet-stream"
    }

    // ── Markdown (shown as text: headings, lists, quotes, code — never HTML) ──────

    sealed class Block {
        data class Heading(val level: Int, val text: String) : Block()
        data class Paragraph(val text: String) : Block()
        data class Item(val marker: String, val text: String) : Block()
        data class Quote(val text: String) : Block()
        data class Code(val text: String) : Block()
        data object Rule : Block()
    }

    private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val ITEM = Regex("^\\s*([-*+]|\\d{1,9}[.)])\\s+(.*)$")
    private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val FENCE = Regex("^\\s*(```|~~~)")

    fun markdown(src: String): List<Block> {
        val lines = src.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = mutableListOf<Block>()
        val para = mutableListOf<String>()
        fun flush() {
            if (para.isNotEmpty()) out += Block.Paragraph(para.joinToString(" "))
            para.clear()
        }
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            val fence = FENCE.find(l)
            if (fence != null) {
                flush()
                val mark = fence.groupValues[1]
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith(mark)) { code += lines[i]; i++ }
                i++
                out += Block.Code(code.joinToString("\n"))
                continue
            }
            val heading = HEADING.matchEntire(l)
            if (heading != null) {
                flush()
                out += Block.Heading(heading.groupValues[1].length, heading.groupValues[2])
                i++
                continue
            }
            if (RULE.matches(l)) { flush(); out += Block.Rule; i++; continue }
            val item = ITEM.matchEntire(l)
            if (item != null) {
                flush()
                val marker = item.groupValues[1].let { if (it[0].isDigit()) it else "•" }
                val text = item.groupValues[2].replace(Regex("^\\[( |x|X)]\\s+")) { if (it.groupValues[1] == " ") "☐ " else "☑ " }
                out += Block.Item(marker, text)
                i++
                continue
            }
            if (l.trimStart().startsWith(">")) {
                flush()
                val q = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) { q += lines[i].trimStart().removePrefix(">").removePrefix(" "); i++ }
                out += Block.Quote(q.joinToString(" "))
                continue
            }
            if (l.isBlank()) { flush(); i++; continue }
            para += l.trim()
            i++
        }
        flush()
        return out
    }

    /** Inline marks of a Markdown line: plain text, **bold**, *italic*, `code`. */
    sealed class Span {
        data class Plain(val text: String) : Span()
        data class Bold(val text: String) : Span()
        data class Italic(val text: String) : Span()
        data class Mono(val text: String) : Span()
    }

    private val INLINE = Regex("(`[^`]+`|\\*\\*[^*]+\\*\\*|__[^_]+__|\\*[^*\\s][^*]*\\*|_[^_\\s][^_]*_)")

    fun spans(text: String): List<Span> {
        val out = mutableListOf<Span>()
        var last = 0
        for (m in INLINE.findAll(text)) {
            if (m.range.first > last) out += Span.Plain(text.substring(last, m.range.first))
            val s = m.value
            out += when {
                s.startsWith("`") -> Span.Mono(s.substring(1, s.length - 1))
                s.startsWith("**") || s.startsWith("__") -> Span.Bold(s.substring(2, s.length - 2))
                else -> Span.Italic(s.substring(1, s.length - 1))
            }
            last = m.range.last + 1
        }
        if (last < text.length) out += Span.Plain(text.substring(last))
        return out
    }
}

/**
 * What one opened file of the viewer holds (bitmaps, a PDF renderer, a memory file, a video's ciphertext): [close]
 * releases all of it — when the viewer closes, when the vaults lock, when the vault page goes. Something added after
 * the close (a decryption that finished too late) is released at once.
 */
class DriveVaultHeld : java.io.Closeable {
    private val items = ArrayList<java.io.Closeable>()
    @Volatile var closed = false
        private set

    /** Keeps [c] until [close] → true; false (and [c] closed) when already closed. */
    @Synchronized
    fun add(c: java.io.Closeable): Boolean {
        if (closed) {
            runCatching { c.close() }
            return false
        }
        items += c
        return true
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        items.asReversed().forEach { runCatching { it.close() } }
        items.clear()
    }
}
