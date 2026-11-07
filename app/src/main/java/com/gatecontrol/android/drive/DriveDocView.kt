package com.gatecontrol.android.drive

import com.gatecontrol.android.drive.vault.DriveVaultView
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * The rules of the viewer of PDFs, texts and Markdown for the normal files of Drive (not of a vault) — pure,
 * unit-tested. It shows them with the pages of the vault's viewer (DriveDocPages.kt): a PDF page by page with
 * Android's PdfRenderer, a text or Markdown as text. The content is the file as the app fetches it for "Öffnen" anyway
 * (DriveTransfers.fetch: the copy in the cache, else a download); "Mit anderer App öffnen" stays in the viewer. Office
 * files and everything else open in another app as before.
 */
object DriveDocLogic {
    enum class Kind { PDF, TEXT, MARKDOWN }

    private const val MB = 1_048_576L

    /**
     * What the viewer loads at most: a PDF is rendered a page at a time (its size is about the download), a text is laid
     * out as a whole. Larger ones say so in the viewer and offer another app — before anything is loaded.
     */
    val LIMITS = mapOf(
        Kind.PDF to 100 * MB,
        Kind.TEXT to 2 * MB,
        Kind.MARKDOWN to 2 * MB,
    )

    /** Lines of a plain text per piece of the list (a long text is not laid out at once). */
    const val CHUNK_LINES = 200

    /** What the viewer shows of [n]: by its name (as in the vault), else by its MIME type; null — not a document of it. */
    fun kind(n: DNode): Kind? {
        // a picture or a video has its own viewer (a ".ts" video is not a TypeScript file)
        if (n.isDir || DriveMediaLogic.viewable(n)) return null
        when (DriveVaultView.kindOf(n.name)) {
            DriveVaultView.Kind.PDF -> return Kind.PDF
            DriveVaultView.Kind.MARKDOWN -> return Kind.MARKDOWN
            DriveVaultView.Kind.TEXT -> return Kind.TEXT
            DriveVaultView.Kind.OTHER -> Unit
            // a picture, a video, an Office file by its name: not here, whatever the server says its type is
            else -> return null
        }
        val m = n.mime.orEmpty().lowercase().substringBefore(';').trim()
        return when {
            m == "application/pdf" -> Kind.PDF
            m == "text/markdown" || m == "text/x-markdown" -> Kind.MARKDOWN
            m.startsWith("text/") || m == "application/json" || m == "application/xml" -> Kind.TEXT
            else -> null
        }
    }

    /** A PDF, a text or a Markdown file outside a vault (a vault's content is ciphertext — its own viewer shows it). */
    fun viewable(n: DNode): Boolean = !n.isDir && !n.encrypted && kind(n) != null

    /**
     * Does a tap on [n] open this viewer ("Dateien", the search, "Öffnen" on a file's page)? As for pictures and videos:
     * one the virus scan holds back keeps its old way (the fetch refuses and says why); a marked one is asked about
     * first, by the caller.
     */
    fun opensInViewer(n: DNode): Boolean = viewable(n) && !n.blocked

    /** Too large for the viewer (it says so and offers another app, without loading it). */
    fun tooLarge(n: DNode): Boolean {
        val k = kind(n) ?: return false
        return tooLarge(k, n.size)
    }

    fun tooLarge(kind: Kind, size: Long): Boolean = size > (LIMITS[kind] ?: 0L)

    /** The limit of [kind] in whole MB (for the note). */
    fun limitMb(kind: Kind): Long = (LIMITS[kind] ?: 0L) / MB

    private val WINDOWS_1252: Charset = runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)

    /**
     * The bytes of a text file as text: UTF-8 (a BOM dropped), UTF-16 with its BOM; what is not valid UTF-8 is read as
     * Windows-1252 (old German texts from Windows: ä, ö, ü, ß, €), never with replacement marks all over it.
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            String(bytes, WINDOWS_1252)
        }
    }

    /** [text] in pieces of at most [lines] lines (line ends unified), for a list that lays out only what is shown. */
    fun chunks(text: String, lines: Int = CHUNK_LINES): List<String> {
        val all = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        // a trailing line end makes no empty last line
        val body = if (all.size > 1 && all.last().isEmpty()) all.dropLast(1) else all
        return body.chunked(lines.coerceAtLeast(1)) { it.joinToString("\n") }
    }
}
