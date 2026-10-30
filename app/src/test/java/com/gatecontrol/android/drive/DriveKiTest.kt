package com.gatecontrol.android.drive

import com.gatecontrol.android.drive.DriveKi.Format
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Drive 1.14 (AI in Drive): the pure rules of D1–D5. */
class DriveKiTest {

    private val today = LocalDate.of(2026, 11, 3)

    private fun node(id: Long = 1, name: String = "a.pdf", mime: String? = "application/pdf", dir: Boolean = false, vault: String? = null) =
        DNode(id, "mine:7", 2, name, dir, 10, mime, null, 1, null, null, null, vaultId = vault)

    private fun deadline(id: Long, kind: String, date: String, reminder: DReminder? = null, calendar: DCalendarEntry? = null) =
        DDeadline(id, kind, date, null, reminder, calendar = calendar)

    // ─── D5 ─────────────────────────────────────────────────────────

    @Test
    fun `formats follow the kind - Excel only for a table, nothing of the AI without it`() {
        assertEquals(listOf(Format.PDF, Format.DOCX, Format.XLSX, Format.IMAGE), DriveKi.formats("table", ai = true))
        assertEquals(listOf(Format.PDF, Format.DOCX, Format.IMAGE), DriveKi.formats("whiteboard", ai = true))
        assertEquals(listOf(Format.PDF, Format.DOCX, Format.IMAGE), DriveKi.formats("handwriting", ai = true))
        assertEquals(listOf(Format.PDF, Format.DOCX, Format.IMAGE), DriveKi.formats("printed", ai = true))
        assertEquals(listOf(Format.PDF, Format.IMAGE), DriveKi.formats("other", ai = true))
        assertEquals(listOf(Format.PDF, Format.IMAGE), DriveKi.formats("table", ai = false))
        assertEquals(listOf(Format.PDF, Format.IMAGE), DriveKi.formats(null, ai = true))
    }

    @Test
    fun `the chip chosen first`() {
        assertEquals(Format.XLSX, DriveKi.defaultFormat("table", true, "pdf"))
        assertEquals(Format.DOCX, DriveKi.defaultFormat("whiteboard", true, "pdf"))
        assertEquals(Format.DOCX, DriveKi.defaultFormat("handwriting", true, "image"))
        assertEquals(Format.PDF, DriveKi.defaultFormat("printed", true, "image"))
        assertEquals(Format.IMAGE, DriveKi.defaultFormat("other", true, "image"))
        assertEquals(Format.PDF, DriveKi.defaultFormat("other", true, null))
        // without the AI a table is a PDF
        assertEquals(Format.PDF, DriveKi.defaultFormat("table", false, "pdf"))
    }

    @Test
    fun `names - cleaned, the title from the first fitting line, the extension of the format`() {
        assertEquals("Rechnung 2026", DriveKi.cleanName("  Rechnung/2026 "))
        assertEquals("a b", DriveKi.cleanName("a:*?b"))
        assertNull(DriveKi.cleanName(" /?: "))
        assertEquals(80, DriveKi.cleanName("x".repeat(200))!!.length)

        assertEquals("Sprint-Planung KW 45", DriveKi.title(listOf("12", "03.11.2026", "Sprint-Planung KW 45", "Ziele")))
        assertNull(DriveKi.title(listOf("12", "Ab", "1.208,40")))

        assertEquals("2026-11-03 Sprint-Planung KW 45", DriveKi.scanName("Sprint-Planung KW 45", today, "Scan x"))
        assertEquals("Scan x", DriveKi.scanName(null, today, "Scan x"))

        assertEquals("2026 Zählerstände.xlsx", DriveKi.fileName("2026 Zählerstände", Format.XLSX))
        assertEquals("Brief.docx", DriveKi.fileName("Brief.pdf", Format.DOCX))
        assertEquals("v1.2 Plan.pdf", DriveKi.fileName("v1.2 Plan", Format.PDF))
        assertEquals("Scan.jpg", DriveKi.fileName("  ", Format.IMAGE))
    }

    @Test
    fun `a table read off the lines, a text is none`() {
        fun l(t: String, x: Int, y: Int) = DriveKi.OcrLine(t, x, y, x + 80, y + 20)
        val lines = listOf(
            l("Datum", 10, 10), l("Zähler", 200, 12), l("Stand (kWh)", 400, 9),
            l("01.01.2026", 10, 50), l("Strom", 205, 51), l("41.208", 410, 49),
            l("01.02.2026", 12, 90), l("Strom", 200, 92), l("41.497", 405, 90),
            l("Summe", 10, 130), l("=C13-C2", 400, 131),
        )
        val grid = DriveKi.tableGrid(lines.shuffled(java.util.Random(4)))
        assertEquals(listOf("Datum", "Zähler", "Stand (kWh)"), grid[0])
        assertEquals(listOf("01.02.2026", "Strom", "41.497"), grid[2])
        assertEquals(listOf("Summe", "", "=C13-C2"), grid[3])
        assertEquals(2, DriveKi.tableGrid(lines, maxRows = 2).size)

        val text = (0 until 6).map { l("Eine Zeile Text $it", 10, 10 + it * 30) }
        assertTrue(DriveKi.tableGrid(text).isEmpty())
        assertEquals(listOf("a", "b"), DriveKi.docPreview("a\n\n  b \n", 8))
        assertEquals(1, DriveKi.docPreview("a\nb", 1).size)
    }

    // ─── D4 ─────────────────────────────────────────────────────────

    @Test
    fun `the filing bar - ready with a suggestion, the payment still ahead and not reminded`() {
        val pay = deadline(5, "payment", "2026-11-28")
        val i = DInsight("ready", "invoice", null, null, null, null, null, null, null, DSuggestion(40, "Finanzen/Strom", "2026-11-03 Stadtwerke Rechnung.pdf"), listOf(pay, deadline(6, "payment", "2026-10-01")))
        val f = DriveKi.filing(i, today)!!
        assertEquals("2026-11-03 Stadtwerke Rechnung.pdf", f.name)
        assertEquals(40L, f.folderId)
        assertEquals(5L, f.payment?.id)

        assertNull(DriveKi.filing(i.copy(status = "pending"), today))
        assertNull(DriveKi.filing(i.copy(suggestion = null), today))
        assertNull(DriveKi.filing(i.copy(suggestion = DSuggestion(null, null, null)), today))
        // reminded already: no payment line
        assertNull(DriveKi.filing(i.copy(deadlines = listOf(pay.copy(reminder = DReminder(null, 3, false)))), today)!!.payment)
        assertTrue(DriveKi.FILING_POLL_MS.sum() in 55_000L..65_000L)
    }

    @Test
    fun `only documents and scans are waited for, never a vault`() {
        assertTrue(DriveKi.fileable(node(), scan = false))
        assertFalse(DriveKi.fileable(node(name = "x.jpg", mime = "image/jpeg"), scan = false))
        assertTrue(DriveKi.fileable(node(name = "Scan.jpg", mime = "image/jpeg"), scan = true))
        assertFalse(DriveKi.fileable(node(dir = true), scan = true))
        assertFalse(DriveKi.fileable(node(vault = "v"), scan = true))
        assertFalse(DriveKi.fileable(node(name = "a.mp4", mime = "video/mp4"), scan = false))
    }

    // ─── D2 ─────────────────────────────────────────────────────────

    @Test
    fun `new deadlines and the calendar`() {
        assertEquals(emptySet<Long>(), DriveKi.fresh(listOf(1, 2), null))
        assertEquals(setOf(3L), DriveKi.fresh(listOf(1, 2, 3), setOf(1, 2, 9)))
        assertTrue(DriveKi.calendarOpen(deadline(1, "renewal", "2026-11-03"), today))
        assertFalse(DriveKi.calendarOpen(deadline(1, "renewal", "2026-11-02"), today))
    }

    // ─── D1 ─────────────────────────────────────────────────────────

    @Test
    fun `a question ends with a question mark`() {
        assertTrue(DriveKi.isQuestion("Wann läuft die Kfz-Versicherung ab? "))
        assertFalse(DriveKi.isQuestion("Rechnung"))
        assertFalse(DriveKi.isQuestion("?"))
    }

    @Test
    fun `dates in an answer and the deadline it names`() {
        val a = "Die Kfz-Versicherung läuft bis 31.12.2026 und verlängert sich jeweils um ein Jahr. Kündigen kannst du bis 30.11.2026."
        assertEquals(setOf(LocalDate.of(2026, 12, 31), LocalDate.of(2026, 11, 30)), DriveKi.datesIn(a, today))
        assertEquals(2, DriveKi.dateSpans(a).size)
        assertEquals("31.12.2026", a.substring(DriveKi.dateSpans(a)[0]))
        // without a year: this year, or the next when over
        assertEquals(setOf(LocalDate.of(2026, 11, 28), LocalDate.of(2027, 1, 5)), DriveKi.datesIn("am 28.11. und am 5.1.", today))
        assertEquals(setOf(LocalDate.of(2026, 12, 1)), DriveKi.datesIn("bis 2026-12-01", today))
        assertTrue(DriveKi.datesIn("Version 1.2 von 3 Seiten, 41.208 kWh", today).none { it.year == 2026 && it.monthValue == 2 && it.dayOfMonth == 41 })

        val end = deadline(1, "payment", "2026-12-31")
        val cancel = deadline(2, "cancellation", "2026-11-30")
        val other = deadline(3, "cancellation", "2027-03-01")
        assertEquals(2L, DriveKi.matchDeadline(a, listOf(end, cancel, other), today)?.id)
        assertEquals(1L, DriveKi.matchDeadline(a, listOf(end, other), today)?.id)
        assertNull(DriveKi.matchDeadline(a, listOf(other), today))
        assertNull(DriveKi.matchDeadline("Keine Daten", listOf(end), today))
    }

    // ─── D3 ─────────────────────────────────────────────────────────

    @Test
    fun `the labels of a photo - sure ones, surest first, at most 20`() {
        val found = listOf("Sky" to 0.9f, "Beach" to 0.95f, "Cat" to 0.3f, "sky" to 0.7f, "" to 1f) + (1..30).map { "L$it" to 0.8f }
        val labels = DriveKi.labels(found)
        assertEquals("Beach", labels[0])
        assertEquals("Sky", labels[1])
        assertFalse("Cat" in labels)
        assertEquals(20, labels.size)
        assertEquals(1, labels.count { it.equals("sky", ignoreCase = true) })
        assertEquals(40, DriveKi.labels(listOf("x".repeat(60) to 1f))[0].length)
    }

    @Test
    fun `the photo search - words, adding and removing a term, the chips`() {
        assertEquals(listOf("Strand", "Meer"), DriveKi.words(" Strand + Meer, strand"))
        assertEquals("Strand Meer", DriveKi.withTerm("Strand", "Meer"))
        assertEquals("Strand", DriveKi.withTerm("Strand", "strand"))
        assertEquals("Meer", DriveKi.withoutTerm("Strand Meer", "strand"))
        val chips = DriveKi.chips("Strand", listOf("strand", "Meer", "Sonnenuntergang", "2025"))
        assertEquals(listOf(DriveKi.Chip("Strand", true), DriveKi.Chip("Meer", false), DriveKi.Chip("Sonnenuntergang", false), DriveKi.Chip("2025", false)), chips)
        assertEquals(2, DriveKi.chips("a b c", listOf("d"), max = 2).size)
        assertNotNull(DriveKi.chips("", emptyList()))
    }
}
