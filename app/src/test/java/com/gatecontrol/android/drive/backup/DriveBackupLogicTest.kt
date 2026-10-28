package com.gatecontrol.android.drive.backup

import com.gatecontrol.android.drive.DArea
import com.gatecontrol.android.drive.backup.DriveBackupLogic.Next
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

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

    // ─── Kamera-Uploads ─────────────────────────────────────────────

    private val berlin = ZoneId.of("Europe/Berlin")
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `the year and month of a photo - its own date first`() {
        // EXIF with its offset: the date where it was taken (the phone may be elsewhere now)
        assertEquals(listOf("2026", "10"), DriveBackupLogic.cameraPath("2026-10-31T23:30:00-05:00", ms("2026-11-01T04:30:00Z"), null, 0, berlin))
        assertEquals(listOf("2026", "11"), DriveBackupLogic.cameraPath("2026-11-01T00:10:00+01:00", null, null, 0, berlin))
        // a video's container date (UTC): in the phone's zone
        assertEquals(listOf("2026", "11"), DriveBackupLogic.cameraPath("2026-10-31T23:30:00Z", null, null, 0, berlin))
        assertEquals(listOf("2026", "10"), DriveBackupLogic.cameraPath("2026-10-31T23:30:00Z", null, null, 0, ZoneId.of("America/New_York")))
        // two-digit months
        assertEquals(listOf("2027", "01"), DriveBackupLogic.cameraPath("2027-01-05T10:00:00+01:00", null, null, 0, berlin))
    }

    @Test
    fun `the year and month without a date of its own`() {
        // the media store's date taken, in the phone's zone
        assertEquals(listOf("2026", "03"), DriveBackupLogic.cameraPath(null, ms("2026-02-28T23:30:00Z"), ms("2025-01-01T00:00:00Z"), 0, berlin))
        assertEquals(listOf("2026", "02"), DriveBackupLogic.cameraPath(null, ms("2026-02-28T23:30:00Z"), null, 0, ZoneId.of("UTC")))
        // a broken own date: the next one
        assertEquals(listOf("2026", "03"), DriveBackupLogic.cameraPath("0000:00:00", ms("2026-03-10T12:00:00Z"), null, 0, berlin))
        // no date taken: when it was changed
        assertEquals(listOf("2024", "12"), DriveBackupLogic.cameraPath(null, null, ms("2024-12-24T18:00:00Z"), ms("2026-10-28T10:00:00Z"), berlin))
        assertEquals(listOf("2024", "12"), DriveBackupLogic.cameraPath(null, 0, ms("2024-12-24T18:00:00Z"), ms("2026-10-28T10:00:00Z"), berlin))
        // nothing at all: when it came onto the phone
        assertEquals(listOf("2026", "10"), DriveBackupLogic.cameraPath(null, null, null, ms("2026-10-28T10:00:00Z"), berlin))
        assertEquals(listOf("2026", "10"), DriveBackupLogic.cameraPath(null, -1, 0, ms("2026-10-28T10:00:00Z"), berlin))
        // the same photo always the same folder
        val a = DriveBackupLogic.cameraPath("2026-10-27T14:05:00+02:00", ms("2026-10-27T12:05:00Z"), null, ms("2026-10-28T10:00:00Z"), berlin)
        assertEquals(a, DriveBackupLogic.cameraPath("2026-10-27T14:05:00+02:00", ms("2026-10-27T12:05:00Z"), null, ms("2026-10-29T10:00:00Z"), berlin))
    }

    @Test
    fun `Kamera-Uploads in my own files`() {
        assertEquals(listOf("Bilder", "Kamera-Uploads"), DriveBackupLogic.CAMERA_BASE)
        fun area(id: String, kind: String, role: String = "manage", via: String? = "owner") = DArea(id, kind, id, role, via, 1, false)
        val family = area("family:1", "family")
        val mine = area("mine:7", "mine")
        assertEquals(mine, DriveBackupLogic.cameraArea(listOf(family, mine), 7))
        // a child's folder a parent sees is not mine
        assertNull(DriveBackupLogic.cameraArea(listOf(family, area("child:9", "child", via = "parent")), 7))
        assertEquals(mine, DriveBackupLogic.cameraArea(listOf(mine), null))
        assertNull(DriveBackupLogic.cameraArea(listOf(area("mine:7", "mine", role = "read")), 7))
    }

    @Test
    fun `no photo goes up twice when the target changes`() {
        // a login that backed up into its own folder up to (date added, id) = (1700000000, 42)
        val mark = DriveBackupMark(1700000000, 42)
        // switched on again (now into Kamera-Uploads): it goes on from there, whatever the person answers
        assertEquals(mark, DriveBackupLogic.startMark(mark, all = true, now = DriveBackupMark(1800000000, Long.MAX_VALUE)))
        assertEquals(mark, DriveBackupLogic.startMark(mark, all = false, now = DriveBackupMark(1800000000, Long.MAX_VALUE)))
        // only the first time it asks
        assertEquals(DriveBackupMark.ALL, DriveBackupLogic.startMark(null, all = true, now = DriveBackupMark(1800000000, 1)))
        assertEquals(DriveBackupMark(1800000000, 1), DriveBackupLogic.startMark(null, all = false, now = DriveBackupMark(1800000000, 1)))
        // the query has no target: the photos up to the mark are never listed again
        val q = DriveBackupLogic.query(DriveBackupConfig(enabled = true), mark)
        assertEquals(listOf("1700000000", "1700000000", "42"), q.args.takeLast(3))
        // the old folder: a notice once, nothing moved
        assertTrue(DriveBackupLogic.targetNotice(legacyFolder = 815))
        assertFalse(DriveBackupLogic.targetNotice(legacyFolder = 0))
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
