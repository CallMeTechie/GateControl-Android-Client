package com.gatecontrol.android.drive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Drive 1.31 (`framePhotos`): when the "Mehr" sheet offers „Für Fotorahmen freigeben“ and „Aus Fotorahmen entfernen“. */
class DriveFrameLogicTest {

    private fun me(vararg features: String) =
        DMe(1, "Anna", "family", true, null, emptyList(), 0, null, null, emptyMap(), features = features.toSet())

    private fun node(id: Long, name: String, mime: String?, dir: Boolean = false, vault: String? = null) =
        DNode(id, "a1", 2L, name, dir, 100, mime, null, 1, null, null, null, vaultId = vault)

    private val photo1 = node(1, "a.jpg", "image/jpeg")
    private val photo2 = node(2, "b.heic", null)
    private val video = node(3, "c.mp4", "video/mp4")
    private val on = me(DMe.FEATURE_PHOTOS, DMe.FEATURE_FRAME_PHOTOS)

    @Test
    fun `nothing without the feature or without a picture`() {
        assertFalse(DriveFrameLogic.offer(me(DMe.FEATURE_PHOTOS), listOf(photo1), emptySet()).any)
        assertFalse(DriveFrameLogic.offer(null, listOf(photo1), emptySet()).any)
        assertFalse(DriveFrameLogic.offer(on, listOf(video), emptySet()).any)
        assertFalse(DriveFrameLogic.offer(on, listOf(node(4, "x", null, dir = true)), emptySet()).any)
        assertFalse(DriveFrameLogic.offer(on, listOf(node(5, "y.jpg", "image/jpeg", vault = "v".repeat(32))), emptySet()).any)
    }

    @Test
    fun `while the state is unknown only sharing`() {
        assertEquals(DriveFrameLogic.Offer(add = true, remove = false), DriveFrameLogic.offer(on, listOf(photo1, video), null))
    }

    @Test
    fun `none shared - share, some - both, all - only remove`() {
        val sel = listOf(photo1, photo2, video)
        assertEquals(DriveFrameLogic.Offer(add = true, remove = false), DriveFrameLogic.offer(on, sel, emptySet()))
        assertEquals(DriveFrameLogic.Offer(add = true, remove = true), DriveFrameLogic.offer(on, sel, setOf(1L)))
        // the video counts for nothing: both pictures shared are "all"
        assertEquals(DriveFrameLogic.Offer(add = false, remove = true), DriveFrameLogic.offer(on, sel, setOf(1L, 2L)))
    }

    @Test
    fun `only the pictures are sent`() {
        assertEquals(listOf(1L, 2L), DriveFrameLogic.ids(listOf(photo1, video, photo2, photo1)))
    }

    @Test
    fun `the reasons, a video as video`() {
        assertEquals(DriveFrameLogic.Skip.VIDEO, DriveFrameLogic.skip("not_image", video))
        assertEquals(DriveFrameLogic.Skip.NOT_IMAGE, DriveFrameLogic.skip("not_image", node(6, "d.pdf", "application/pdf")))
        assertEquals(DriveFrameLogic.Skip.NOT_IMAGE, DriveFrameLogic.skip("not_image"))
        assertEquals(DriveFrameLogic.Skip.VAULT, DriveFrameLogic.skip("vault"))
        assertEquals(DriveFrameLogic.Skip.BLOCKED, DriveFrameLogic.skip("blocked"))
        assertEquals(DriveFrameLogic.Skip.CHILD, DriveFrameLogic.skip("child"))
        assertEquals(DriveFrameLogic.Skip.NOT_FOUND, DriveFrameLogic.skip("not_found"))
        assertEquals(DriveFrameLogic.Skip.OTHER, DriveFrameLogic.skip("something_new"))
    }

    @Test
    fun `skipped counts the ones not sent and the ones the server left out, in a fixed order`() {
        val sel = listOf(photo1, photo2, video, node(7, "e.mov", "video/quicktime"), node(8, "f.jpg", "image/jpeg"))
        val out = DriveFrameLogic.skipped(sel, listOf(1L, 2L, 8L), listOf(DFrameSkip(8, "blocked"), DFrameSkip(2, "child"), DFrameSkip(2, "child")))
        assertEquals(
            listOf(DriveFrameLogic.Skip.VIDEO to 2, DriveFrameLogic.Skip.BLOCKED to 1, DriveFrameLogic.Skip.CHILD to 1),
            out.toList(),
        )
        assertTrue(DriveFrameLogic.skipped(listOf(photo1), listOf(1L), emptyList()).isEmpty())
    }

    @Test
    fun `forbidden only for the code forbidden`() {
        assertTrue(DriveFrameLogic.forbidden(DResult.Failed("forbidden", 403)))
        assertFalse(DriveFrameLogic.forbidden(DResult.Failed("no_seat", 403)))
        assertFalse(DriveFrameLogic.forbidden(DResult.Offline))
    }
}
