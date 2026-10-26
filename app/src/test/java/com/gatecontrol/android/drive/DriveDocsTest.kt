package com.gatecontrol.android.drive

import android.provider.DocumentsContract.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DriveDocsTest {

    private val mimes = mapOf("pdf" to "application/pdf", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "txt" to "text/plain")
    private val mimeOf: (String) -> String? = { mimes[it] }
    private val extOf: (String) -> String? = { m -> mapOf("application/pdf" to "pdf", "image/jpeg" to "jpg", "text/plain" to "txt")[m] }

    private fun node(id: Long, name: String, dir: Boolean = false, mime: String? = null, mtime: String? = null) = DNode(
        id = id, areaId = "f:1", parentId = 3, name = name, isDir = dir, size = 42, mime = mime, mediaId = null,
        rev = 1, mtime = mtime, updatedAt = "2026-10-26T10:00:00Z", updatedBy = null,
    )

    private fun area(role: String = "write", archived: Boolean = false) = DArea("f:1", "family", "Familie", role, "member", 3, archived)

    @Test
    fun `ids round trip and reject junk`() {
        assertEquals(DriveDocs.Id.Root, DriveDocs.parse("root"))
        assertEquals(DriveDocs.Id.Node(12), DriveDocs.parse(DriveDocs.node(12)))
        assertEquals(DriveDocs.Id.Pending(3), DriveDocs.parse(DriveDocs.pending(3)))
        listOf(null, "", "n", "n0", "n-1", "nx", "x12", "p", "root2").forEach { assertNull(DriveDocs.parse(it), "$it") }
    }

    @Test
    fun `rows follow the writability of the area`() {
        val f = DriveDocs.doc(node(10, "a.pdf"), area(), mimeOf)
        assertEquals("n10", f.id)
        assertEquals("application/pdf", f.mime)
        assertEquals(42L, f.size)
        assertEquals(
            Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_MOVE,
            f.flags,
        )
        val dir = DriveDocs.doc(node(11, "Fotos", dir = true), area(), mimeOf)
        assertEquals(DriveDocs.DIR, dir.mime)
        assertNull(dir.size)
        assertEquals(Document.FLAG_DIR_SUPPORTS_CREATE, dir.flags and Document.FLAG_DIR_SUPPORTS_CREATE)
        assertEquals(0, DriveDocs.doc(node(10, "a.pdf"), area(role = "read"), mimeOf).flags)
        assertEquals(0, DriveDocs.doc(node(10, "a.pdf"), area(archived = true), mimeOf).flags)
        assertEquals(0, DriveDocs.doc(node(10, "a.pdf"), area(), mimeOf, writable = false).flags, "the folder's role wins")
    }

    @Test
    fun `an area's top folder carries its label and cannot be renamed or deleted`() {
        val top = DriveDocs.doc(node(3, "", dir = true), area(), mimeOf)
        assertEquals("Familie", top.name)
        assertEquals(Document.FLAG_DIR_SUPPORTS_CREATE, top.flags)
        val asRoot = DriveDocs.doc(area())
        assertEquals("n3", asRoot.id)
        assertEquals(Document.FLAG_DIR_SUPPORTS_CREATE, asRoot.flags)
        assertEquals(0, DriveDocs.doc(area(role = "read")).flags)
    }

    @Test
    fun `mime from the server, else from the extension`() {
        assertEquals("image/png", DriveDocs.mime("image/PNG", "x.jpg", mimeOf))
        assertEquals("image/jpeg", DriveDocs.mime("application/octet-stream", "x.JPG", mimeOf))
        assertEquals("image/jpeg", DriveDocs.mime(null, "x.jpg", mimeOf))
        assertEquals(DriveDocs.OCTET, DriveDocs.mime(null, "noext", mimeOf))
    }

    @Test
    fun `times in ISO 8601`() {
        assertEquals(1793008800000L, DriveDocs.millis("2026-10-26T10:00:00Z"))
        assertEquals(1793008800000L, DriveDocs.millis("2026-10-26T11:00:00+01:00"))
        assertNull(DriveDocs.millis("gestern"))
        assertNull(DriveDocs.millis(null))
        assertEquals(1793008800000L, DriveDocs.doc(node(1, "a", mtime = "bad"), area(), mimeOf).modified, "falls back to updatedAt")
    }

    @Test
    fun `names for save as get a fitting extension`() {
        assertEquals("Rechnung.pdf", DriveDocs.createName("Rechnung", "application/pdf", extOf, mimeOf))
        assertEquals("Rechnung.pdf", DriveDocs.createName("Rechnung.pdf", "application/pdf", extOf, mimeOf))
        assertEquals("Foto.jpeg", DriveDocs.createName("Foto.jpeg", "image/jpeg", extOf, mimeOf))
        assertEquals("Notiz.md.txt", DriveDocs.createName("Notiz.md", "text/plain", extOf, mimeOf))
        assertEquals("a_b.bin", DriveDocs.createName("a/b.bin", "application/octet-stream", extOf, mimeOf))
        assertEquals("Ordner", DriveDocs.createName("Ordner", DriveDocs.DIR, extOf, mimeOf))
        assertNull(DriveDocs.createName("  ", "text/plain", extOf, mimeOf))
    }

    @Test
    fun `folders first, then by name`() {
        val d = { id: String, name: String, mime: String -> DriveDocs.Doc(id, name, mime, null, null, 0) }
        val sorted = DriveDocs.sorted(listOf(d("1", "b.txt", "text/plain"), d("2", "Zeta", DriveDocs.DIR), d("3", "A.pdf", "application/pdf"), d("4", "alpha", DriveDocs.DIR)))
        assertEquals(listOf("4", "2", "3", "1"), sorted.map { it.id })
    }
}
