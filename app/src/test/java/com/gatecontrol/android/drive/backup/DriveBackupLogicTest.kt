package com.gatecontrol.android.drive.backup

import com.gatecontrol.android.drive.backup.DriveBackupLogic.Next
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DriveBackupLogicTest {

    @Test
    fun `photos after the mark, oldest first, camera only by default`() {
        val q = DriveBackupLogic.query(DriveBackupConfig(), DriveBackupMark(1700000000, 42))
        assertEquals("media_type = 1 AND relative_path LIKE ? AND relative_path NOT LIKE ? AND (date_added > ? OR (date_added = ? AND _id > ?))", q.selection)
        assertEquals(listOf("DCIM/%", "%Screenshots%", "1700000000", "1700000000", "42"), q.args.toList())
        assertEquals("date_added ASC, _id ASC", q.sort)
    }

    @Test
    fun `videos and every folder when asked`() {
        val q = DriveBackupLogic.query(DriveBackupConfig(videos = true, cameraOnly = false), DriveBackupMark.ALL)
        assertEquals("media_type IN (1, 3) AND (date_added > ? OR (date_added = ? AND _id > ?))", q.selection)
        assertEquals(listOf("0", "0", "0"), q.args.toList())
    }

    @Test
    fun `names and times`() {
        assertEquals("IMG_20261026.jpg", DriveBackupLogic.name("IMG_20261026.jpg", 1, video = false))
        assertEquals("a_b.jpg", DriveBackupLogic.name("a:b.jpg", 1, video = false))
        assertEquals("IMG_7.jpg", DriveBackupLogic.name(null, 7, video = false))
        assertEquals("VID_7.mp4", DriveBackupLogic.name("  ", 7, video = true))
        assertEquals(1700000000123L, DriveBackupLogic.mtime(1700000000123L, 1600000000))
        assertEquals(1600000000000L, DriveBackupLogic.mtime(0, 1600000000))
        assertNull(DriveBackupLogic.mtime(null, null))
    }

    @Test
    fun `what a failure means for the run`() {
        listOf("offline", "busy", "http_503", "http_429", "timeout").forEach { assertEquals(Next.RETRY, DriveBackupLogic.next(it), it) }
        listOf("not_licensed", "no_seat", "forbidden", "not_found", "quota", "no_permission", "no_folder", "device_locked").forEach { assertEquals(Next.STOP, DriveBackupLogic.next(it), it) }
        listOf("too_large", "read", "empty").forEach { assertEquals(Next.SKIP, DriveBackupLogic.next(it), it) }
        assertEquals(Next.TRY, DriveBackupLogic.next("invalid_answer"))
        assertEquals(Next.TRY, DriveBackupLogic.next("something_new"))
    }

    @Test
    fun `default folder`() {
        assertEquals(listOf("Fotos", "Marc"), DriveBackupLogic.defaultPath("Marc", family = true, photos = "Fotos"))
        assertEquals(listOf("Fotos"), DriveBackupLogic.defaultPath("Marc", family = false, photos = "Fotos"))
        assertEquals(listOf("Fotos"), DriveBackupLogic.defaultPath(null, family = true, photos = "Fotos"))
        assertEquals(listOf("Fotos", "A_B"), DriveBackupLogic.defaultPath("A/B", family = true, photos = "Fotos"))
    }

    @Test
    fun `the default config is careful`() {
        val c = DriveBackupConfig()
        assertFalse(c.enabled)
        assertTrue(c.wifiOnly)
        assertFalse(c.videos)
        assertTrue(c.cameraOnly)
    }
}
