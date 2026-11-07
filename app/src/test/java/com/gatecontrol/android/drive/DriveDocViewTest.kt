package com.gatecontrol.android.drive

import com.gatecontrol.android.scan.MediaScan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The viewer of PDFs, texts and Markdown of normal Drive files: what it shows, its limits, how a text is read. */
class DriveDocViewTest {

    private fun node(id: Long, name: String, mime: String? = null, size: Long = 10, dir: Boolean = false, vault: String? = null, scan: MediaScan? = null) = DNode(
        id = id, areaId = "u:1", parentId = 1, name = name, isDir = dir, size = size, mime = mime, mediaId = "m$id",
        rev = 1, mtime = null, updatedAt = null, updatedBy = null, vaultId = vault, scan = scan,
    )

    @Test
    fun `PDFs, texts and Markdown by name, else by type`() {
        assertEquals(DriveDocLogic.Kind.PDF, DriveDocLogic.kind(node(1, "Brief.pdf", "application/pdf")))
        assertEquals(DriveDocLogic.Kind.PDF, DriveDocLogic.kind(node(2, "Scan", "application/pdf")), "no extension: by the type")
        assertEquals(DriveDocLogic.Kind.TEXT, DriveDocLogic.kind(node(3, "notiz.txt", "text/plain")))
        assertEquals(DriveDocLogic.Kind.TEXT, DriveDocLogic.kind(node(4, "config.yaml", "application/octet-stream")))
        assertEquals(DriveDocLogic.Kind.TEXT, DriveDocLogic.kind(node(5, "README", "text/plain; charset=utf-8")))
        assertEquals(DriveDocLogic.Kind.MARKDOWN, DriveDocLogic.kind(node(6, "Plan.md", "text/markdown")))
        assertEquals(DriveDocLogic.Kind.MARKDOWN, DriveDocLogic.kind(node(7, "Notes", "text/markdown")))
    }

    @Test
    fun `Office files, pictures, videos, folders and vault files are not shown here`() {
        assertNull(DriveDocLogic.kind(node(1, "Bericht.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")))
        assertNull(DriveDocLogic.kind(node(2, "Tabelle.xlsx", "text/plain")), "an Office file by its name, whatever the type says")
        assertNull(DriveDocLogic.kind(node(3, "a.jpg", "image/jpeg")))
        assertNull(DriveDocLogic.kind(node(4, "clip.ts", "video/mp2t")), "a .ts video has the player")
        assertNull(DriveDocLogic.kind(node(5, "Ordner", dir = true)))
        assertNull(DriveDocLogic.kind(node(6, "archiv.zip", "application/zip")))
        assertFalse(DriveDocLogic.viewable(node(7, "0f3a", "application/pdf", vault = "v1")), "a vault's content is ciphertext")
        assertTrue(DriveDocLogic.viewable(node(8, "Brief.pdf", "application/pdf")))
    }

    @Test
    fun `a blocked file keeps its old way, a marked one opens (the caller asks first)`() {
        val blocked = node(1, "Rechnung.pdf", "application/pdf", scan = MediaScan(state = MediaScan.PENDING, blocked = true))
        assertFalse(DriveDocLogic.opensInViewer(blocked))
        val marked = node(2, "Rechnung.pdf", "application/pdf", scan = MediaScan(state = MediaScan.INFECTED, blocked = false, finding = "X"))
        assertTrue(DriveDocLogic.opensInViewer(marked))
        assertTrue(DriveDocLogic.opensInViewer(node(3, "Plan.md")))
        assertFalse(DriveDocLogic.opensInViewer(node(4, "Bericht.docx")))
    }

    @Test
    fun `too large ones say so instead of loading`() {
        val mb = 1_048_576L
        assertFalse(DriveDocLogic.tooLarge(node(1, "a.pdf", "application/pdf", size = 100 * mb)))
        assertTrue(DriveDocLogic.tooLarge(node(2, "a.pdf", "application/pdf", size = 100 * mb + 1)))
        assertFalse(DriveDocLogic.tooLarge(node(3, "a.txt", "text/plain", size = 2 * mb)))
        assertTrue(DriveDocLogic.tooLarge(node(4, "a.md", "text/markdown", size = 2 * mb + 1)))
        assertFalse(DriveDocLogic.tooLarge(node(5, "a.docx", size = 900 * mb)), "not a document of the viewer")
        assertEquals(100L, DriveDocLogic.limitMb(DriveDocLogic.Kind.PDF))
        assertEquals(2L, DriveDocLogic.limitMb(DriveDocLogic.Kind.TEXT))
    }

    @Test
    fun `a text is read as UTF-8, with a BOM, as UTF-16, else as Windows-1252`() {
        assertEquals("Größe: 3 €", DriveDocLogic.decode("Größe: 3 €".toByteArray(Charsets.UTF_8)))
        assertEquals("Hallo", DriveDocLogic.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Hallo".toByteArray()))
        assertEquals("Übung", DriveDocLogic.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Übung".toByteArray(Charsets.UTF_16LE)))
        assertEquals("Übung", DriveDocLogic.decode(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "Übung".toByteArray(Charsets.UTF_16BE)))
        // "Grüße 5 €" from an old Windows editor: not valid UTF-8
        val cp1252 = byteArrayOf(0x47, 0x72, 0xFC.toByte(), 0xDF.toByte(), 0x65, 0x20, 0x35, 0x20, 0x80.toByte())
        assertEquals("Grüße 5 €", DriveDocLogic.decode(cp1252))
        assertEquals("", DriveDocLogic.decode(ByteArray(0)))
    }

    @Test
    fun `a long text goes in pieces of lines, line ends unified`() {
        val text = (1..450).joinToString("\r\n") { "Zeile $it" } + "\r\n"
        val pieces = DriveDocLogic.chunks(text, lines = 200)
        assertEquals(3, pieces.size)
        assertEquals("Zeile 1", pieces[0].lines().first())
        assertEquals(200, pieces[0].lines().size)
        assertEquals(50, pieces[2].lines().size)
        assertEquals("Zeile 450", pieces[2].lines().last())
        assertEquals(listOf(""), DriveDocLogic.chunks(""))
        assertEquals(listOf("a\n\nb"), DriveDocLogic.chunks("a\r\rb"))
    }
}
