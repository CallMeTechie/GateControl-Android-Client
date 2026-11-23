package com.gatecontrol.android.tv.frame

import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.scan.MediaScan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class TvFrameScheduleTest {

    private fun node(id: Long, name: String = "IMG_$id.jpg", mime: String = "image/jpeg", size: Long = 1024, preview: Boolean = true) = DNode(
        id = id, areaId = "fam", parentId = 1, name = name, isDir = false, size = size, mime = mime, mediaId = "media$id-abcdef",
        rev = 1, mtime = null, updatedAt = null, updatedBy = null, preview = preview,
    )

    @Test
    fun `the choices cycle and odd values fall back`() {
        assertEquals(30, TvFrameSchedule.cycle(TvFrameSchedule.SECONDS, 20))
        assertEquals(10, TvFrameSchedule.cycle(TvFrameSchedule.SECONDS, 300))
        assertEquals(10, TvFrameSchedule.cycle(TvFrameSchedule.SECONDS, 7))
        assertEquals(5, TvFrameSchedule.cycle(TvFrameSchedule.IDLE_MINUTES, 0))
        assertEquals(0, TvFrameSchedule.cycle(TvFrameSchedule.IDLE_MINUTES, 30))
        assertEquals(TvFrameSchedule.DEFAULT_SECONDS, TvFrameSchedule.seconds(13))
        assertEquals(60, TvFrameSchedule.seconds(60))
        assertEquals(0, TvFrameSchedule.idleMinutes(-4))
    }

    @Test
    fun `the idle start needs a folder, the setting and the time without a key`() {
        val s = TvFrameSettings(folderId = 7, folderName = "Urlaub", idleMinutes = 10)
        val last = 1_000_000L
        assertFalse(TvFrameSchedule.idleDue(s, last, last + 9 * 60_000))
        assertTrue(TvFrameSchedule.idleDue(s, last, last + 10 * 60_000))
        assertFalse(TvFrameSchedule.idleDue(s.copy(idleMinutes = 0), last, last + 60 * 60_000))
        assertFalse(TvFrameSchedule.idleDue(s.copy(folderId = null), last, last + 60 * 60_000))
    }

    @Test
    fun `the folder is read again every half hour`() {
        assertFalse(TvFrameSchedule.refreshDue(0, 29 * 60_000))
        assertTrue(TvFrameSchedule.refreshDue(0, 30 * 60_000))
    }

    @Test
    fun `only pictures the frame may show`() {
        val items = listOf(
            node(1),
            node(2, "VID.mp4", "video/mp4"),
            node(3).copy(vaultId = "v1"),
            node(4).copy(scan = MediaScan(state = MediaScan.INFECTED, blocked = true)),
            node(5, size = 90L * 1024 * 1024, preview = false),
            node(6, size = 90L * 1024 * 1024, preview = true),
            node(7).copy(isDir = true),
        )
        assertEquals(listOf(1L, 6L), TvFrameSchedule.pictures(items).map { it.id })
    }

    @Test
    fun `by name every picture once per round, in order`() {
        val p = TvFramePlaylist(listOf(node(1), node(2), node(3)), shuffle = false)
        assertEquals(listOf(1L, 2L, 3L, 1L, 2L), List(5) { p.next()!!.id })
        assertEquals(1L, p.previous()!!.id)
        assertNull(TvFramePlaylist(emptyList(), shuffle = true).next())
    }

    @Test
    fun `shuffled every picture once per round, never twice in a row`() {
        val items = (1L..6L).map { node(it) }
        repeat(20) { seed ->
            val p = TvFramePlaylist(items, shuffle = true, random = Random(seed))
            val shown = List(18) { p.next()!!.id }
            for (round in shown.chunked(6)) assertEquals(items.map { it.id }.toSet(), round.toSet())
            for (i in 1 until shown.size) assertNotEquals(shown[i - 1], shown[i])
        }
    }

    @Test
    fun `a new reading keeps the round going`() {
        val p = TvFramePlaylist(listOf(node(1), node(2), node(3), node(4)), shuffle = false)
        assertEquals(1L, p.next()!!.id)
        assertEquals(2L, p.next()!!.id)
        // 2 was deleted, 5 is new: the round goes on with 3, 4; 5 comes in the next round
        p.update(listOf(node(1), node(3), node(4), node(5)))
        assertEquals(listOf(3L, 4L, 1L, 3L, 4L, 5L), List(6) { p.next()!!.id })
    }
}
