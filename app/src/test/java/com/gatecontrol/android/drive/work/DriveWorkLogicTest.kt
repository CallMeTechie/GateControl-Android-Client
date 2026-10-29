package com.gatecontrol.android.drive.work

import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DReview
import com.gatecontrol.android.drive.work.DriveWorkLogic.Dest
import com.gatecontrol.android.drive.work.DriveWorkLogic.Why
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId

class DriveWorkLogicTest {

    private val utc = ZoneId.of("UTC")

    private fun photo(id: Long, takenAt: String? = "2026-10-29T09:12:00Z", work: Boolean = false, review: DReview? = null, parent: Long = 3) =
        DNode(id, "mine:7", parent, "IMG_$id.jpg", false, 10, "image/jpeg", null, 1, null, null, null, takenAt = takenAt, work = work, review = review)

    // ─── where a new photo goes ─────────────────────────────────────

    @Test
    fun `company WLAN means work, home WLAN private — both teach the AI`() {
        val w = DriveWorkLogic.place(WifiZone.WORK, score = 0.1, ready = true, autoSort = true)
        assertEquals(Dest.WORK, w.dest)
        assertFalse(w.review)
        assertEquals(true, w.learn)
        assertEquals(Why.WIFI_WORK, w.why)
        val h = DriveWorkLogic.place(WifiZone.HOME, score = 0.99, ready = true, autoSort = true)
        assertEquals(Dest.PRIVATE, h.dest)
        assertFalse(h.review)
        assertEquals(false, h.learn)
    }

    @Test
    fun `elsewhere in the learning phase always private with the review mark and the hint`() {
        val p = DriveWorkLogic.place(null, score = 0.97, ready = false, autoSort = true)
        assertEquals(Dest.PRIVATE, p.dest)
        assertTrue(p.review)
        assertEquals(97, p.hint)
        assertNull(p.learn)
        assertEquals(Why.REVIEW, p.why)
        // "Weder noch" is the same as an unknown WLAN
        assertTrue(DriveWorkLogic.place(WifiZone.NEITHER, score = 0.97, ready = false, autoSort = true).review)
    }

    @Test
    fun `after learning only a sure AI decides alone, and only when allowed`() {
        val work = DriveWorkLogic.place(null, score = 0.85, ready = true, autoSort = true)
        assertEquals(Dest.WORK, work.dest)
        assertFalse(work.review)
        assertEquals(Why.AI_WORK, work.why)
        assertNull(work.learn, "its own guesses are no examples")
        val priv = DriveWorkLogic.place(WifiZone.NEITHER, score = 0.15, ready = true, autoSort = true)
        assertEquals(Dest.PRIVATE, priv.dest)
        assertFalse(priv.review)
        assertEquals(Why.AI_PRIVATE, priv.why)
        // unsure in between
        val unsure = DriveWorkLogic.place(null, score = 0.84, ready = true, autoSort = true)
        assertTrue(unsure.review)
        assertEquals(84, unsure.hint)
        assertTrue(DriveWorkLogic.place(null, score = 0.16, ready = true, autoSort = true).review)
        // "Selbst einsortieren" off
        val off = DriveWorkLogic.place(null, score = 0.99, ready = true, autoSort = false)
        assertEquals(Dest.PRIVATE, off.dest)
        assertTrue(off.review)
        // no answer of the AI (a video, no Play services)
        val none = DriveWorkLogic.place(null, score = null, ready = true, autoSort = true)
        assertTrue(none.review)
        assertNull(none.hint)
    }

    @Test
    fun `learning phase ends with 30 examples of both kinds`() {
        assertFalse(DriveWorkLogic.Learned(total = 29, work = 10, private = 19).ready)
        assertTrue(DriveWorkLogic.Learned(total = 30, work = 10, private = 20).ready)
        assertFalse(DriveWorkLogic.Learned(total = 40, work = 2, private = 38).ready)
        assertEquals(23, DriveWorkLogic.Learned(total = 23).progress)
        assertEquals(30, DriveWorkLogic.Learned(total = 80).progress)
        assertTrue(DriveWorkLogic.Learned(total = 6, work = 3, private = 3).usable)
        assertFalse(DriveWorkLogic.Learned(total = 6, work = 1, private = 5).usable)
    }

    @Test
    fun `wlan zones`() {
        val z = WifiZones().with("Firma-Corp", WifiZone.WORK).with("FRITZ!Box 7590", WifiZone.HOME).with("Café", WifiZone.NEITHER)
        assertEquals(WifiZone.WORK, z.zoneOf("Firma-Corp"))
        assertEquals(WifiZone.HOME, z.zoneOf("FRITZ!Box 7590"))
        assertEquals(WifiZone.NEITHER, z.zoneOf("Café"))
        assertNull(z.zoneOf(null))
        assertTrue(z.unknown("Kunde-Mueller-Gast"))
        assertFalse(z.unknown("Café"))
        assertFalse(z.unknown(null))
        // moved to another zone: out of the old one
        val moved = z.with("Firma-Corp", WifiZone.HOME)
        assertEquals(WifiZone.HOME, moved.zoneOf("Firma-Corp"))
        assertFalse("Firma-Corp" in moved.work)
        assertTrue(moved.with("Firma-Corp", null).unknown("Firma-Corp"))
    }

    // ─── the selection's bar ────────────────────────────────────────

    @Test
    fun `the big buttons only when every ticked photo is to be reviewed`() {
        val r1 = photo(1, review = DReview(72))
        val r2 = photo(2, review = DReview(null))
        val plain = photo(3)
        assertTrue(DriveWorkLogic.reviewBar(listOf(r1, r2), emptyMap()))
        assertFalse(DriveWorkLogic.reviewBar(listOf(r1, plain), emptyMap()))
        assertFalse(DriveWorkLogic.reviewBar(emptyList(), emptyMap()))
        // without Drive 1.13: this phone's marks
        assertTrue(DriveWorkLogic.reviewBar(listOf(r1, plain), mapOf(3L to 40)))
        assertEquals(40, DriveWorkLogic.hintOf(plain, mapOf(3L to 40)))
        assertEquals(72, DriveWorkLogic.hintOf(r1, mapOf(1L to 10)))
    }

    @Test
    fun `the AI's line is the mean of the hints`() {
        assertEquals(DriveWorkLogic.Hint(true, 72), DriveWorkLogic.hint(listOf(72)))
        assertEquals(DriveWorkLogic.Hint(true, 60), DriveWorkLogic.hint(listOf(80, 40, null)))
        assertEquals(DriveWorkLogic.Hint(false, 62), DriveWorkLogic.hint(listOf(38)))
        assertEquals(DriveWorkLogic.Hint(true, 50), DriveWorkLogic.hint(listOf(50)))
        assertNull(DriveWorkLogic.hint(listOf(null, null)))
        assertNull(DriveWorkLogic.hint(emptyList()))
    }

    @Test
    fun `more offers what changes something`() {
        val work = photo(1, work = true)
        val priv = photo(2)
        val marked = photo(3, review = DReview(50))
        assertEquals(DriveWorkLogic.Offer(work = false, private = true, review = true), DriveWorkLogic.offer(listOf(work), emptyMap()))
        assertEquals(DriveWorkLogic.Offer(work = true, private = false, review = true), DriveWorkLogic.offer(listOf(priv), emptyMap()))
        assertEquals(DriveWorkLogic.Offer(work = true, private = true, review = false), DriveWorkLogic.offer(listOf(marked), emptyMap()))
        assertEquals(DriveWorkLogic.Offer(work = true, private = true, review = true), DriveWorkLogic.offer(listOf(work, priv), emptyMap()))
    }

    // ─── moving ─────────────────────────────────────────────────────

    @Test
    fun `work moves the private ones into the work folder by month`() {
        val a = photo(1, takenAt = "2026-10-29T09:00:00Z")
        val b = photo(2, takenAt = "2026-09-01T09:00:00Z")
        val c = photo(3, takenAt = "2026-10-01T09:00:00Z", work = true)
        val d = photo(4, takenAt = "2026-10-03T09:00:00Z", review = DReview(80))
        val m = DriveWorkLogic.moves(listOf(a, b, c, d), Dest.WORK, subfolders = true, zone = utc)
        assertEquals(listOf(listOf("2026", "10"), listOf("2026", "09")), m.keys.toList())
        assertEquals(listOf(1L, 4L), m[listOf("2026", "10")]!!.map { it.id })
        assertEquals(listOf(2L), m[listOf("2026", "09")]!!.map { it.id })
        val flat = DriveWorkLogic.moves(listOf(a, b), Dest.WORK, subfolders = false, zone = utc)
        assertEquals(mapOf(emptyList<String>() to listOf(a, b)), flat)
    }

    @Test
    fun `private moves only what is under the work folder, into Kamera-Uploads by month`() {
        val w = photo(1, takenAt = "2026-10-29T09:00:00Z", work = true)
        val p = photo(2)
        val markedInWork = photo(3, takenAt = "2025-01-31T23:30:00Z", review = DReview(70))
        val m = DriveWorkLogic.moves(listOf(w, p, markedInWork), Dest.PRIVATE, subfolders = false, zone = utc) { it.work || it.id == 3L }
        assertEquals(listOf(listOf("2026", "10"), listOf("2025", "01")), m.keys.toList())
        assertEquals(listOf(1L), m[listOf("2026", "10")]!!.map { it.id })
        assertTrue(DriveWorkLogic.moves(listOf(p), Dest.PRIVATE, subfolders = true, zone = utc).isEmpty())
        // without a date: the folder itself
        assertEquals(emptyList<String>(), DriveWorkLogic.monthPath(photo(9, takenAt = null), utc))
    }

    // ─── Aufräumen ──────────────────────────────────────────────────

    @Test
    fun `suggestions from 50 percent, ticked from 70, by month and surest first`() {
        val list = DriveWorkLogic.suggestions(
            listOf(
                photo(1, takenAt = "2026-10-02T09:00:00Z") to 0.96,
                photo(2, takenAt = "2026-10-03T09:00:00Z") to 0.58,
                photo(3, takenAt = "2026-09-03T09:00:00Z") to 0.70,
                photo(4, takenAt = "2026-10-04T09:00:00Z") to 0.49,
                photo(5, takenAt = "2026-10-04T09:00:00Z", work = true) to 0.99,
                photo(6, takenAt = "2026-10-04T09:00:00Z", review = DReview(90)) to 0.99,
            ),
            utc,
        )
        assertEquals(listOf(1L, 2L, 3L), list.map { it.node.id })
        assertEquals(listOf(96, 58, 70), list.map { it.percent })
        assertEquals(listOf(true, false, true), list.map { it.preselected })
        assertEquals(listOf("2026-10", "2026-09"), DriveWorkLogic.byMonth(list).map { it.first })
    }
}
