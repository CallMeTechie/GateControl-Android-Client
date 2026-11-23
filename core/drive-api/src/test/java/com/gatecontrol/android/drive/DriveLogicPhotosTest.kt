package com.gatecontrol.android.drive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.ZoneId

class DriveLogicPhotosTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    private fun node(id: Long, takenAt: String?, mtime: String? = null) =
        DNode(id, "mine:7", 1, "p$id.jpg", false, 1, "image/jpeg", null, 1, mtime, null, null, takenAt = takenAt)

    @Test
    fun `EXIF date with and without offset`() {
        assertEquals("2026-10-27T14:05:00+02:00", DriveLogic.exifTakenAt("2026:10:27 14:05:00", "+02:00", berlin))
        // no offset: the phone's zone (summer time in Berlin)
        assertEquals("2026-07-01T08:00:00+02:00", DriveLogic.exifTakenAt("2026:07:01 08:00:00", null, berlin))
        assertEquals("2026-01-01T08:00:00+01:00", DriveLogic.exifTakenAt("2026:01:01 08:00:00", "", berlin))
        assertEquals("2026-01-01T08:00:00Z", DriveLogic.exifTakenAt("2026-01-01 08:00:00", "+00:00", berlin))
        assertNull(DriveLogic.exifTakenAt("0000:00:00 00:00:00", null, berlin))
        assertNull(DriveLogic.exifTakenAt("    :  :     :  :  ", null, berlin))
        assertNull(DriveLogic.exifTakenAt(null, "+02:00", berlin))
        assertNull(DriveLogic.exifTakenAt("1850:01:01 00:00:00", null, berlin))
        // a broken offset counts as none
        assertEquals("2026-01-01T08:00:00+01:00", DriveLogic.exifTakenAt("2026:01:01 08:00:00", "+99:00", berlin))
    }

    @Test
    fun `video date and media store date`() {
        assertEquals("2026-10-27T12:05:00Z", DriveLogic.videoTakenAt("20261027T120500.000Z"))
        assertNull(DriveLogic.videoTakenAt("19040101T000000.000Z"))
        assertNull(DriveLogic.videoTakenAt("19700101T000000.000Z"))
        assertNull(DriveLogic.videoTakenAt("garbage"))
        assertEquals("2026-10-27T12:05:00Z", DriveLogic.msTakenAt(1_793_102_700_000L))
        assertNull(DriveLogic.msTakenAt(0))
        assertNull(DriveLogic.msTakenAt(null))
    }

    @Test
    fun `photos by month, in their order, the file time when no takenAt`() {
        val items = listOf(
            node(1, "2026-10-31T23:30:00Z"), // 1 November in Berlin
            node(2, "2026-10-15T10:00:00Z"),
            node(3, null, mtime = "2026-10-01T10:00:00Z"),
            node(4, "2025-03-02T10:00:00+01:00"),
            node(5, null),
        )
        val groups = DriveLogic.byMonth(items, berlin)
        assertEquals(listOf("2026-11", "2026-10", "2025-03", null), groups.map { it.first })
        assertEquals(listOf(2L, 3L), groups[1].second.map { it.id })
    }

    @Test
    fun `pages put together without twins`() {
        val a = listOf(node(1, null), node(2, null))
        assertEquals(listOf(1L, 2L, 3L), DriveLogic.appendPage(a, listOf(node(2, null), node(3, null))).map { it.id })
    }

    private fun file(id: Long, canDelete: Boolean = true, at: String? = null) = DStorageFile(id, "f$id", 10, "mine:7", 1, "f$id", at, canDelete)

    @Test
    fun `duplicates keep one copy`() {
        val g = DDuplicates("a".repeat(64), 10, 3, 20, listOf(file(1, at = "2026-01-01"), file(2, at = "2026-02-01"), file(3, at = "2026-03-01")))
        var sel = DriveLogic.toggleDuplicate(emptySet(), g, 1)
        sel = DriveLogic.toggleDuplicate(sel, g, 2)
        assertEquals(setOf(1L, 2L), sel)
        // the last copy cannot be ticked
        assertEquals(sel, DriveLogic.toggleDuplicate(sel, g, 3))
        assertEquals(setOf(2L), DriveLogic.toggleDuplicate(sel, g, 1))
        val locked = g.copy(items = listOf(file(1, canDelete = false), file(2)))
        assertEquals(emptySet<Long>(), DriveLogic.toggleDuplicate(emptySet(), locked, 1))
        // all but one: the oldest stays, or the one that may not go
        assertEquals(setOf(2L, 3L), DriveLogic.allButOne(listOf(g)))
        assertEquals(setOf(2L), DriveLogic.allButOne(listOf(locked)))
        assertEquals(20L, DriveLogic.freed(setOf(2L, 3L), listOf(g)))
    }
}
