package com.gatecontrol.android.drive

import com.gatecontrol.android.drive.DriveLogic.Kind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class DriveLogicTest {

    private fun node(id: Long, name: String, dir: Boolean = false, mime: String? = null) = DNode(
        id = id, areaId = "u:1", parentId = 1, name = name, isDir = dir, size = 0, mime = mime, mediaId = null,
        rev = 1, mtime = null, updatedAt = null, updatedBy = null,
    )

    private fun change(seq: Long, op: String, n: DNode?, id: Long? = n?.id) = DChange(seq, op, "u:1", id, null, n)

    @Test
    fun `own previews only for what the server cannot read`() {
        assertTrue(DriveLogic.needsOwnPreview("image/heic"))
        assertTrue(DriveLogic.needsOwnPreview("IMAGE/HEIF"))
        assertTrue(DriveLogic.needsOwnPreview("video/mp4"))
        assertTrue(DriveLogic.needsOwnPreview("video/quicktime; codecs=x"))
        assertFalse(DriveLogic.needsOwnPreview("image/jpeg"))
        assertFalse(DriveLogic.needsOwnPreview("image/avif"))
        assertFalse(DriveLogic.needsOwnPreview("application/pdf"))
        assertFalse(DriveLogic.needsOwnPreview(null))
    }

    @Test
    fun `names Drive refuses are made safe`() {
        assertEquals("a_b_c", DriveLogic.safeName("a/b\\c"))
        assertEquals("Bericht_ Q1_", DriveLogic.safeName("Bericht: Q1?"))
        assertEquals("notiz", DriveLogic.safeName("  notiz. . "))
        assertEquals("tab_x", DriveLogic.safeName("tab\tx"))
        assertNull(DriveLogic.safeName("   "))
        assertNull(DriveLogic.safeName(null))
        assertNull(DriveLogic.safeName(".."))
        assertNull(DriveLogic.safeName(".GCDRIVE"))
        assertEquals(255, DriveLogic.safeName("x".repeat(400))!!.length)
    }

    @Test
    fun `a typed name is valid only as it is`() {
        assertTrue(DriveLogic.validName("Urlaub 2026"))
        assertTrue(DriveLogic.validName("rechnung.pdf"))
        assertFalse(DriveLogic.validName("a/b"))
        assertFalse(DriveLogic.validName("ende."))
        assertFalse(DriveLogic.validName("<x>"))
        assertFalse(DriveLogic.validName("  "))
    }

    @Test
    fun `the kind comes from the mime type or the extension`() {
        assertEquals(Kind.FOLDER, DriveLogic.kind(node(1, "Fotos", dir = true)))
        assertEquals(Kind.IMAGE, DriveLogic.kind("image/jpeg", "x"))
        assertEquals(Kind.IMAGE, DriveLogic.kind(null, "IMG_1.HEIC"))
        assertEquals(Kind.VIDEO, DriveLogic.kind(null, "clip.mp4"))
        assertEquals(Kind.AUDIO, DriveLogic.kind("audio/mpeg", "x"))
        assertEquals(Kind.PDF, DriveLogic.kind(null, "a.pdf"))
        assertEquals(Kind.DOC, DriveLogic.kind(null, "brief.docx"))
        assertEquals(Kind.SHEET, DriveLogic.kind(null, "budget.xlsx"))
        assertEquals(Kind.SLIDES, DriveLogic.kind(null, "talk.pptx"))
        assertEquals(Kind.ARCHIVE, DriveLogic.kind("application/zip", "x"))
        assertEquals(Kind.TEXT, DriveLogic.kind(null, "readme.md"))
        assertEquals(Kind.OTHER, DriveLogic.kind(null, "noext"))
    }

    @Test
    fun `badges show the extension, photos a word`() {
        assertEquals("PDF", DriveLogic.badge(node(1, "a.pdf"), "FOTO"))
        assertEquals("FOTO", DriveLogic.badge(node(1, "a.jpg"), "FOTO"))
        assertEquals("…", DriveLogic.badge(node(1, "noext"), "FOTO"))
    }

    @Test
    fun `sizes read like 1,5 MB`() {
        assertEquals("500 B", DriveLogic.size(500, Locale.GERMANY))
        assertEquals("2 KB", DriveLogic.size(2048, Locale.GERMANY))
        assertEquals("1,5 MB", DriveLogic.size(1536L * 1024, Locale.GERMANY))
        assertEquals("1.5 MB", DriveLogic.size(1536L * 1024, Locale.US))
        assertEquals("200 MB", DriveLogic.size(200L * 1024 * 1024, Locale.GERMANY))
        assertEquals("2,0 TB", DriveLogic.size(2L * 1024 * 1024 * 1024 * 1024, Locale.GERMANY))
    }

    @Test
    fun `recent keeps the newest files once and drops deleted ones`() {
        val a = node(10, "a.pdf")
        val b = node(11, "b.jpg")
        val c = node(12, "c.txt")
        val dir = node(13, "Ordner", dir = true)
        val list = listOf(
            change(1, "create", a),
            change(2, "create", b),
            change(3, "update", a.copy(rev = 2)),
            change(4, "create", dir),
            change(5, "create", c),
            change(6, "delete", null, id = 12),
            change(7, "share", b),
        )
        val r = DriveLogic.recent(list)
        assertEquals(listOf(10L, 11L), r.map { it.id })
        assertEquals(2, r.first().rev, "the newest state of a file")
        assertEquals(1, DriveLogic.recent(list, max = 1).size)
    }

    @Test
    fun `areas sort mine, family, child, team`() {
        fun area(id: String, kind: String, label: String) = DArea(id, kind, label, "write", null, 1, false)
        val sorted = DriveLogic.sortAreas(
            listOf(area("t:1", "team", "B-Team"), area("c:1", "child", "Lena"), area("t:2", "team", "a-Team"), area("f:1", "family", "Familie"), area("u:1", "mine", "Meine Dateien")),
        )
        assertEquals(listOf("u:1", "f:1", "c:1", "t:2", "t:1"), sorted.map { it.id })
    }

    @Test
    fun `usage with and without a quota`() {
        assertNull(DriveLogic.usage(null, "von"))
        assertNull(DriveLogic.usage(DStorage(null, 10, null, null, null), "von"))
        assertEquals("1,0 GB", DriveLogic.usage(DStorage(1L shl 30, null, null, null, null), "von", Locale.GERMANY))
        assertEquals("1,0 GB von 50,0 GB", DriveLogic.usage(DStorage(1L shl 30, 50L shl 30, null, null, null), "von", Locale.GERMANY))
    }

    @Test
    fun `an archived or read-only area takes no writes`() {
        assertTrue(DArea("u:1", "mine", "x", "manage", "owner", 1, false).writable)
        assertFalse(DArea("t:1", "team", "x", "read", "member", 1, false).writable)
        assertFalse(DArea("f:1", "family", "x", "write", "member", 1, true).writable)
    }
}
