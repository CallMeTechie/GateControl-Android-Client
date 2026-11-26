package com.gatecontrol.android.media

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Posters on the disk: by a key with the picture's tag, the least used go first. */
class MediaPictureDiskTest {

    private var clock = 1_800_000_000_000L

    private fun poster(id: String, tag: String? = "t1") =
        MediaTitle(id, "jf", MediaTitle.Kind.MOVIE, "Film $id", posterId = id, posterTag = tag, backdropId = id, backdropTag = "b-$tag")

    @Test
    fun `the key holds source, item, tag and kind, nothing else`() {
        val t = poster("m1")
        assertEquals("jf:m1:t1:p", MediaPictures.keyOf(t, backdrop = false))
        assertEquals("jf:m1:b-t1:b", MediaPictures.keyOf(t, backdrop = true))
        assertNotEquals(MediaPictures.keyOf(t, false), MediaPictures.keyOf(poster("m1", tag = "t2"), false), "a new picture on the server")
        assertNull(MediaPictures.keyOf(t.copy(posterId = null), false))
        // away smaller than at home
        assertTrue(MediaPictures.heightOf(backdrop = false, away = true) < MediaPictures.heightOf(backdrop = false, away = false))
    }

    @Test
    fun `kept across a restart`(@TempDir dir: File) {
        MediaPictureDisk(dir, now = { clock }).put("jf:m1:t1:p", byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), MediaPictureDisk(dir, now = { clock }).get("jf:m1:t1:p"))
        assertNull(MediaPictureDisk(dir).get("jf:m1:t2:p"))
        // the file name tells nothing of the title
        assertTrue(dir.listFiles()!!.none { it.name.contains("m1") })
    }

    @Test
    fun `over the limit the least used go first`(@TempDir dir: File) {
        val disk = MediaPictureDisk(dir, maxBytes = 400, now = { clock })
        for (i in 1..4) {
            disk.put("p$i", ByteArray(100) { i.toByte() })
            clock += 1_000
        }
        // p1 used just now: it stays, p2 (the oldest then) goes
        assertNotNull(disk.get("p1"))
        clock += 1_000
        disk.put("p5", ByteArray(100))
        disk.trim()
        assertNotNull(disk.get("p1"))
        assertNull(disk.get("p2"))
        assertNotNull(disk.get("p5"))
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= 400)
    }

    @Test
    fun `nothing empty or too large, clear takes all`(@TempDir dir: File) {
        val disk = MediaPictureDisk(dir, maxBytes = 400, now = { clock })
        disk.put("e", ByteArray(0))
        disk.put("big", ByteArray(200))
        assertNull(disk.get("e"))
        assertNull(disk.get("big"))
        disk.put("ok", ByteArray(50))
        disk.clear()
        assertNull(disk.get("ok"))
    }
}
