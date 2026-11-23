package com.gatecontrol.android.tv.drive

import com.gatecontrol.android.drive.DArea
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.scan.MediaScan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TvDriveLogicTest {

    private fun node(id: Long, name: String, mime: String? = null, dir: Boolean = false, preview: Boolean = false, vault: String? = null, scan: MediaScan? = null) =
        DNode(
            id = id, areaId = "a", parentId = 1, name = name, isDir = dir, size = 10, mime = mime, mediaId = if (dir) null else "media$id-abcdef",
            rev = 1, mtime = null, updatedAt = null, updatedBy = null, preview = preview, vaultId = vault, scan = scan,
        )

    @Test
    fun `the stack goes down, back up and leaves at the areas`() {
        val s0 = TvDriveStack()
        assertTrue(s0.atAreas)
        assertNull(s0.back())
        val s1 = s0.open(10, "Familie")
        val s2 = s1.open(11, "Fotos").open(12, "2026")
        assertEquals(listOf("Familie", "Fotos", "2026"), s2.path())
        assertEquals(12L, s2.top?.id)
        assertEquals(listOf("Familie", "Fotos"), s2.back()?.path())
        assertEquals(TvDriveStack(), s1.back())
        // a folder already on the way: back to it, not twice on the stack
        assertEquals(listOf("Familie", "Fotos"), s2.open(11, "Fotos").path())
    }

    @Test
    fun `what OK opens`() {
        assertEquals(TvDriveOpen.FOLDER, TvDriveLogic.openOf(node(1, "Ordner", dir = true)))
        assertEquals(TvDriveOpen.PHOTO, TvDriveLogic.openOf(node(2, "IMG_1.jpg", "image/jpeg")))
        assertEquals(TvDriveOpen.VIDEO, TvDriveLogic.openOf(node(3, "VID_1.mp4", "video/mp4")))
        assertEquals(TvDriveOpen.PREVIEW, TvDriveLogic.openOf(node(4, "Rezept.pdf", "application/pdf", preview = true)))
        assertEquals(TvDriveOpen.NONE, TvDriveLogic.openOf(node(5, "Rezept.pdf", "application/pdf")))
        assertEquals(TvDriveOpen.NONE, TvDriveLogic.openOf(node(6, "Archiv.zip", "application/zip", preview = true).copy(mediaId = null)))
        // a vault's file is ciphertext: never shown on the TV
        assertEquals(TvDriveOpen.NONE, TvDriveLogic.openOf(node(7, "0123456789abcdef0123456789abcdef", "image/jpeg", vault = "v1")))
        val blocked = MediaScan(state = MediaScan.INFECTED, blocked = true)
        assertEquals(TvDriveOpen.BLOCKED, TvDriveLogic.openOf(node(8, "IMG_2.jpg", "image/jpeg", scan = blocked)))
    }

    @Test
    fun `the viewer goes through pictures or videos, never both`() {
        val items = listOf(
            node(1, "Ordner", dir = true),
            node(2, "a.jpg", "image/jpeg"),
            node(3, "b.mp4", "video/mp4"),
            node(4, "c.jpg", "image/jpeg"),
            node(5, "d.pdf", "application/pdf", preview = true),
            node(6, "e.mov", "video/quicktime"),
        )
        val (photos, at) = TvDriveLogic.viewerList(items, items[3])
        assertEquals(listOf(2L, 4L), photos.map { it.id })
        assertEquals(1, at)
        val (videos, vat) = TvDriveLogic.viewerList(items, items[5])
        assertEquals(listOf(3L, 6L), videos.map { it.id })
        assertEquals(1, vat)
        val alone = node(9, "x.jpg", "image/jpeg")
        assertEquals(listOf(alone) to 0, TvDriveLogic.viewerList(items, alone))
    }

    @Test
    fun `folders first, then media, then the rest`() {
        val items = listOf(node(1, "z.pdf", "application/pdf"), node(2, "a.jpg", "image/jpeg"), node(3, "Ordner", dir = true), node(4, "b.mp4", "video/mp4"))
        assertEquals(listOf(3L, 2L, 4L, 1L), TvDriveLogic.ordered(items).map { it.id })
    }

    @Test
    fun `steps stay inside the list`() {
        assertEquals(1, TvDriveLogic.step(0, 1, 3))
        assertEquals(2, TvDriveLogic.step(2, 1, 3))
        assertEquals(0, TvDriveLogic.step(0, -1, 3))
        assertEquals(0, TvDriveLogic.step(0, 1, 0))
    }

    @Test
    fun `archived areas stay away`() {
        val a = DArea("a", "mine", "Meine Dateien", "manage", "owner", 1, archived = false)
        val b = DArea("b", "team", "Alt", "read", "member", 2, archived = true)
        assertEquals(listOf("a"), TvDriveLogic.areas(listOf(a, b)).map { it.id })
        assertFalse(TvDriveLogic.areas(listOf(b)).isNotEmpty())
    }
}
