package com.gatecontrol.android.mail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class MailPimLogicTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0, zone: ZoneId = berlin) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun slot(key: String, start: Long, end: Long, allDay: Boolean = false, sd: String? = null, ed: String? = null) =
        MailPimLogic.Slot(key, key, start, end, allDay, sd, ed, "#46E0A0")

    // ─── views ──────────────────────────────────────────────────────

    @Test
    fun `views - day, three days, (work) week from Monday or Sunday, the month's full weeks`() {
        val tue = LocalDate.of(2026, 11, 3)
        assertEquals(listOf(tue), MailPimLogic.days("day", tue, 1))
        assertEquals(listOf(tue, tue.plusDays(1), tue.plusDays(2)), MailPimLogic.days("3day", tue, 1))
        val week = MailPimLogic.days("week", tue, 1)
        assertEquals(LocalDate.of(2026, 11, 2), week.first())
        assertEquals(7, week.size)
        assertEquals(LocalDate.of(2026, 11, 1), MailPimLogic.days("week", tue, 0).first())
        assertEquals(5, MailPimLogic.days("week", tue, 1, workWeek = true).size)
        val month = MailPimLogic.monthGrid(tue, 1)
        assertEquals(LocalDate.of(2026, 10, 26), month.first().first())
        assertTrue(month.all { it.size == 7 })
        assertTrue(month.last().contains(LocalDate.of(2026, 11, 30)))
        assertEquals(LocalDate.of(2026, 12, 1), MailPimLogic.step("month", tue, 1))
        assertEquals(LocalDate.of(2026, 10, 31), MailPimLogic.step("3day", tue, -1))
    }

    @Test
    fun `ranges - midnight to midnight across the summer time, at most 62 days a call`() {
        // the night of 25 Oct 2026 has 25 hours in Berlin
        val (a, b) = MailPimLogic.range(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 25), berlin)
        assertEquals(25L * 3600_000L, b - a)
        val chunks = MailPimLogic.chunks(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 30))
        assertEquals(2, chunks.size)
        assertEquals(LocalDate.of(2026, 3, 3), chunks[0].second)
        assertEquals(LocalDate.of(2026, 3, 4), chunks[1].first)
    }

    // ─── the grid ───────────────────────────────────────────────────

    @Test
    fun `grid - overlapping events side by side, the others full width`() {
        val day = LocalDate.of(2026, 11, 4)
        val slots = listOf(
            slot("a", ms(2026, 11, 4, 10), ms(2026, 11, 4, 11)),
            slot("b", ms(2026, 11, 4, 10, 30), ms(2026, 11, 4, 12)),
            slot("c", ms(2026, 11, 4, 11), ms(2026, 11, 4, 11, 30)),
            slot("d", ms(2026, 11, 4, 14), ms(2026, 11, 4, 15)),
        )
        val placed = MailPimLogic.layoutDay(slots, day, berlin).associateBy { it.slot.key }
        assertEquals(2, placed.getValue("a").cols)
        assertEquals(0, placed.getValue("a").col)
        assertEquals(1, placed.getValue("b").col)
        // c starts when a ends: it takes a's column again within the same group
        assertEquals(0, placed.getValue("c").col)
        assertEquals(2, placed.getValue("c").cols)
        assertEquals(1, placed.getValue("d").cols)
        assertEquals(600, placed.getValue("a").top)
        assertEquals(660, placed.getValue("a").bottom)
    }

    @Test
    fun `grid - over midnight on both days, short ones still readable, all-day apart`() {
        val night = slot("n", ms(2026, 11, 4, 22), ms(2026, 11, 5, 2))
        val short = slot("s", ms(2026, 11, 4, 9), ms(2026, 11, 4, 9, 5))
        val allDay = slot("x", ms(2026, 11, 4, 0), ms(2026, 11, 6, 0), allDay = true, sd = "2026-11-04", ed = "2026-11-05")
        val first = MailPimLogic.layoutDay(listOf(night, short, allDay), LocalDate.of(2026, 11, 4), berlin).associateBy { it.slot.key }
        assertEquals(22 * 60, first.getValue("n").top)
        assertEquals(24 * 60, first.getValue("n").bottom)
        assertEquals(MailPimLogic.MIN_MINUTES, first.getValue("s").bottom - first.getValue("s").top)
        assertNull(first["x"])
        val second = MailPimLogic.layoutDay(listOf(night), LocalDate.of(2026, 11, 5), berlin).single()
        assertEquals(0, second.top)
        assertEquals(120, second.bottom)
        // all-day: the end date counts (inclusive), the day after does not
        assertTrue(MailPimLogic.coversAllDay(allDay, LocalDate.of(2026, 11, 5), berlin))
        assertFalse(MailPimLogic.coversAllDay(allDay, LocalDate.of(2026, 11, 6), berlin))
        assertEquals(listOf("x"), MailPimLogic.allDayOn(listOf(night, allDay), LocalDate.of(2026, 11, 4), berlin).map { it.key })
    }

    @Test
    fun `agenda - by days, all-day first, empty days left out`() {
        val list = listOf(
            slot("late", ms(2026, 11, 4, 18), ms(2026, 11, 4, 19)),
            slot("early", ms(2026, 11, 4, 8), ms(2026, 11, 4, 9)),
            slot("day", ms(2026, 11, 4, 0), ms(2026, 11, 5, 0), allDay = true, sd = "2026-11-04", ed = "2026-11-04"),
            slot("fri", ms(2026, 11, 6, 8), ms(2026, 11, 6, 9)),
        )
        val agenda = MailPimLogic.agenda(list, LocalDate.of(2026, 11, 4), 5, berlin)
        assertEquals(listOf(LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 6)), agenda.map { it.first })
        assertEquals(listOf("day", "early", "late"), agenda[0].second.map { it.key })
    }

    @Test
    fun `new events - the tapped half hour, or the next full hour`() {
        val (a, b) = MailPimLogic.newAt(LocalDate.of(2026, 11, 4), 14 * 60 + 47, berlin)
        assertEquals(ms(2026, 11, 4, 14, 30), a)
        assertEquals(3600_000L, b - a)
        val (n, _) = MailPimLogic.nextHour(ms(2026, 11, 4, 9, 12), berlin)
        assertEquals(ms(2026, 11, 4, 10), n)
    }

    // ─── time zones ─────────────────────────────────────────────────

    @Test
    fun `zones - another offset is shown, the same rules under another name are not`() {
        val at = ms(2026, 7, 1, 12)
        assertNull(MailPimLogic.otherZone("Europe/Berlin", berlin, at))
        assertNull(MailPimLogic.otherZone("Europe/Paris", berlin, at))
        assertEquals(ZoneId.of("Europe/London"), MailPimLogic.otherZone("Europe/London", berlin, at))
        assertNull(MailPimLogic.otherZone("Mars/Base", berlin, at))
        assertEquals("GMT+2", MailPimLogic.offsetLabel(berlin, at))
        assertEquals("GMT+5:30", MailPimLogic.offsetLabel(ZoneId.of("Asia/Kolkata"), at))
        // a wall time in the spring gap moves forward
        val gap = MailPimLogic.ms(LocalDate.of(2026, 3, 29), LocalTime.of(2, 30), berlin)
        assertEquals(LocalTime.of(3, 30), MailPimLogic.local(gap, berlin).toLocalTime())
    }

    private fun occ(start: Long, end: Long, allDay: Boolean = false, sd: String? = null, ed: String? = null) =
        MOccurrence("1:x", 1, 1, "x", "x", start, end, allDay, sd, ed, "Europe/Berlin", null, null, "confirmed", "busy", null, true, 0, false, null)

    @Test
    fun `series - moving all events keeps the series' own day and its hour across summer time`() {
        // the series started on a winter Monday at 10:00; a summer occurrence moves to Tuesday 11:00, 90 minutes
        val series = MSeries(ms(2026, 1, 5, 10), ms(2026, 1, 5, 11), false)
        val o = occ(ms(2026, 7, 6, 10), ms(2026, 7, 6, 11))
        val x = MailPimLogic.shiftSeries(series, o, false, ms(2026, 7, 7, 11), ms(2026, 7, 7, 12, 30), null, null, berlin)
        assertEquals(ms(2026, 1, 6, 11), x.start)
        assertEquals(ms(2026, 1, 6, 12, 30), x.end)
        // unchanged: the series' own times
        val same = MailPimLogic.shiftSeries(series, o, false, o.start, o.end, null, null, berlin)
        assertEquals(series.start, same.start)
        assertEquals(series.end, same.end)
    }

    @Test
    fun `series - all-day series move by days, timed from an all-day one from its midnight`() {
        val series = MSeries(ms(2026, 1, 5, 0), ms(2026, 1, 6, 0), true, "2026-01-05", "2026-01-05")
        val o = occ(ms(2026, 7, 6, 0), ms(2026, 7, 7, 0), true, "2026-07-06", "2026-07-06")
        val x = MailPimLogic.shiftSeries(series, o, true, 0, 0, "2026-07-07", "2026-07-08", berlin)
        assertEquals("2026-01-06", x.startDate)
        assertEquals("2026-01-07", x.endDate)
        val t = MailPimLogic.shiftSeries(series, o, false, ms(2026, 7, 6, 9), ms(2026, 7, 6, 10), null, null, berlin)
        assertEquals(ms(2026, 1, 5, 9), t.start)
        assertEquals(ms(2026, 1, 5, 10), t.end)
    }

    @Test
    fun `all-day - dates inclusive, a reversed end becomes the start`() {
        assertEquals("2026-11-04" to "2026-11-04", MailPimLogic.allDayDates(LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 2)))
        val o = MOccurrence("1", 1, 1, null, "x", ms(2026, 11, 4, 0), ms(2026, 11, 6, 0), true, "2026-11-04", "2026-11-05", null, null, null, "confirmed", "busy", null, false, 0, false, null)
        assertEquals(LocalDate.of(2026, 11, 4) to LocalDate.of(2026, 11, 5), MailPimLogic.datesOf(o, berlin))
        // without dates: from the ms, the end exclusive
        assertEquals(LocalDate.of(2026, 11, 4) to LocalDate.of(2026, 11, 5), MailPimLogic.datesOf(o.copy(startDate = null, endDate = null), berlin))
    }

    // ─── rules ──────────────────────────────────────────────────────

    @Test
    fun `rules - read from RRULE and shown in words`() {
        val weekly = MailPimLogic.parseRule("RRULE:FREQ=WEEKLY;BYDAY=TU,TH;UNTIL=20261231T000000Z")!!
        assertEquals(MRule.WEEKLY, weekly.freq)
        assertEquals(listOf("TU", "TH"), weekly.byDay)
        assertEquals("2026-12-31", weekly.until)
        assertEquals("Wöchentlich am Di, Do, bis 31.12.2026", MailPimLogic.ruleText(weekly, "de"))
        assertEquals("Weekly on Tue, Thu, until 12/31/2026", MailPimLogic.ruleText(weekly, "en"))
        val nth = MailPimLogic.parseRule("FREQ=MONTHLY;BYDAY=2TU")!!
        assertEquals(2, nth.bySetPos)
        assertEquals("Monatlich am 2. Dienstag", MailPimLogic.ruleText(nth, "de"))
        assertEquals("Monthly on the 2nd Tuesday", MailPimLogic.ruleText(nth, "en"))
        assertEquals("Alle 2 Wochen am Mo, 10-mal", MailPimLogic.ruleText(MRule(MRule.WEEKLY, 2, listOf("MO"), count = 10), "de"))
        assertEquals("Jährlich am 14. März", MailPimLogic.ruleText(MRule(MRule.YEARLY), "de", LocalDate.of(2026, 3, 14)))
        assertEquals("Monatlich am 4.", MailPimLogic.ruleText(MRule(MRule.MONTHLY), "de", LocalDate.of(2026, 11, 4)))
        assertEquals("Monatlich am letzten Freitag", MailPimLogic.ruleText(MRule(MRule.MONTHLY, byDay = listOf("FR"), bySetPos = -1), "de"))
        assertNull(MailPimLogic.parseRule("FREQ=SECONDLY"))
        assertNull(MailPimLogic.parseRule("garbage"))
    }

    @Test
    fun `rules - the form's choices and back`() {
        val d = LocalDate.of(2026, 11, 24) // the 4th and last Tuesday
        assertEquals(-1 to "TU", MailPimLogic.nthWeekday(d))
        assertEquals(2 to "TU", MailPimLogic.nthWeekday(LocalDate.of(2026, 11, 10)))
        val r = MailPimLogic.ruleFor(MailPimLogic.RULE_MONTHLY_WEEKDAY, d)!!
        assertEquals(listOf("TU"), r.byDay)
        assertEquals(-1, r.bySetPos)
        assertEquals(MailPimLogic.RULE_MONTHLY_WEEKDAY, MailPimLogic.kindOf(r))
        assertEquals(listOf("TU"), MailPimLogic.ruleFor(MRule.WEEKLY, d)!!.byDay)
        assertEquals(listOf(24), MailPimLogic.ruleFor(MailPimLogic.RULE_MONTHLY_DAY, d)!!.byMonthDay)
        assertNull(MailPimLogic.ruleFor(MailPimLogic.RULE_NONE, d))
        assertEquals(MailPimLogic.RULE_NONE, MailPimLogic.kindOf(null))
    }

    @Test
    fun `reminders - units, and all-day ones against the midnight that starts the day`() {
        assertEquals(15 to "min", MailPimLogic.reminderParts(15))
        assertEquals(2 to "h", MailPimLogic.reminderParts(120))
        assertEquals(1 to "d", MailPimLogic.reminderParts(1440))
        assertEquals(1 to "w", MailPimLogic.reminderParts(10080))
        assertEquals(0 to null, MailPimLogic.reminderParts(0))
        assertEquals(1 to 18 * 60, MailPimLogic.allDayReminder(360))
        // the server's default 1080: 06:00 the day before
        assertEquals(1 to 6 * 60, MailPimLogic.allDayReminder(1080))
        assertEquals(0 to 0, MailPimLogic.allDayReminder(0))
        assertEquals(2 to 9 * 60, MailPimLogic.allDayReminder(2340))
    }

    @Test
    fun `answers - accepted of the people, resources left out`() {
        val list = listOf(
            MAttendee("A", "a@x.de", "required", "accepted", false, null),
            MAttendee("B", "b@x.de", "optional", "declined", false, null),
            MAttendee("R", "r@x.de", "resource", "accepted", false, null),
        )
        assertEquals(1 to 2, MailPimLogic.acceptedOf(list))
    }

    // ─── contacts ───────────────────────────────────────────────────

    private fun sum(id: Long, name: String, key: String = name.uppercase(), book: Long = 1, phone: String? = null, emails: List<String> = emptyList()) =
        MCardSummary(id, book, name, key, MailPimLogic.letterOf(key), null, null, emails, phone, false, null, emptyList(), emptyList(), null, null)

    @Test
    fun `letters - umlauts fold, the rest is a hash, hash last`() {
        assertEquals("A", MailPimLogic.letterOf("ÄRZTE"))
        assertEquals("O", MailPimLogic.letterOf("öztürk"))
        assertEquals("U", MailPimLogic.letterOf("Ülker"))
        assertEquals("S", MailPimLogic.letterOf("ßtraße"))
        assertEquals("#", MailPimLogic.letterOf("1&1"))
        assertEquals("#", MailPimLogic.letterOf(""))
        val sections = MailPimLogic.sections(listOf(sum(1, "Zoe"), sum(2, "#hash", "#HASH"), sum(3, "Anna")))
        assertEquals(listOf("A", "Z", "#"), sections.map { it.first })
    }

    @Test
    fun `search on the device - name, address and digits of the number`() {
        val list = listOf(sum(1, "Anna Lang", emails = listOf("anna@keller.de")), sum(2, "Tim Brandt", phone = "0171 234 56 78"))
        assertEquals(listOf(1L), MailPimLogic.search(list, "keller").map { it.id })
        assertEquals(listOf(2L), MailPimLogic.search(list, "234-56").map { it.id })
        assertEquals(2, MailPimLogic.search(list, " ").size)
    }

    @Test
    fun `since sync - changes replace, deletions go, sorted by key`() {
        val old = listOf(sum(1, "Anna"), sum(2, "Bert"), sum(3, "Carl"))
        val out = MailPimLogic.applyCards(old, listOf(sum(2, "Bernd"), sum(4, "Aaron")), setOf(3))
        assertEquals(listOf(4L, 1L, 2L), out.map { it.id })
        assertEquals("Bernd", out.last().name)
    }

    @Test
    fun `numbers - +49 and 0 the same, tel links`() {
        assertEquals(MailPimLogic.phoneKey("+49 171 2345678"), MailPimLogic.phoneKey("0171 / 234 56 78"))
        assertEquals("tel:+491712345678", MailPimLogic.telUri("+49 171 2345678"))
        assertEquals("tel:02213004012", MailPimLogic.telUri("0221 300 40-12"))
        assertNull(MailPimLogic.telUri("ab"))
    }

    private fun card(id: Long, book: Long, given: String, emails: List<String>, phones: List<String> = emptyList(), company: String? = null, note: String = "", birthday: String? = null) =
        MCard(id = id, addressbookId = book, name = MCardName(given = given, family = "Lang"), emails = emails.map { MCardEmail(it) }, phones = phones.map { MCardPhone(it) },
            company = company, note = note, birthday = birthday, groups = listOf(id))

    @Test
    fun `merge - addresses and numbers without doubles, single fields picked, notes joined`() {
        val a = card(1, 1, "Anna", listOf("anna@x.de"), listOf("+49 171 2345678"), company = "Keller GmbH", note = "Einkauf")
        val b = card(2, 1, "Anna-Maria", listOf("ANNA@x.de", "anna@gmx.de"), listOf("0171 2345678"), company = "Keller", note = "Mittwochs", birthday = "1981-03-14")
        val m = MailPimLogic.merged(listOf(a, b), primary = 1)
        assertEquals(listOf("anna@x.de", "anna@gmx.de"), m.emails.map { it.value })
        assertTrue(m.emails.first().primary)
        assertEquals(1, m.phones.size)
        assertEquals("Keller GmbH", m.company)
        assertEquals("1981-03-14", m.birthday)
        assertEquals("Einkauf\n\nMittwochs", m.note)
        assertEquals(listOf(1L, 2L), m.groups)
        assertEquals("Keller", MailPimLogic.merged(listOf(a, b), 1, MMergePick(company = 2)).company)
        assertEquals("Anna-Maria", MailPimLogic.merged(listOf(a, b), 1, MMergePick(name = 2)).name.given)
        assertEquals(listOf("name", "company"), MailPimLogic.conflicts(listOf(a, b)))
        assertTrue(MailPimLogic.links(listOf(sum(1, "A", book = 1), sum(2, "A", book = 2))))
        assertFalse(MailPimLogic.links(listOf(sum(1, "A"), sum(2, "A"))))
    }

    @Test
    fun `birthdays - typed, without a year, the age to come`() {
        assertEquals("1981-03-14", MailPimLogic.birthdayOf("14.03.", "1981"))
        assertEquals("--03-14", MailPimLogic.birthdayOf("14.3", ""))
        assertNull(MailPimLogic.birthdayOf("31.02.", "2026"))
        assertNull(MailPimLogic.birthdayOf("14.03.", "81"))
        assertEquals("--02-29", MailPimLogic.birthdayOf("29.02.", ""))
        assertEquals(Triple(3, 14, 1981), MailPimLogic.birthdayParts("1981-03-14"))
        assertEquals(Triple(3, 14, null), MailPimLogic.birthdayParts("--03-14"))
        assertEquals(46, MailPimLogic.turns("1981-03-14", LocalDate.of(2026, 10, 31)))
        assertEquals(45, MailPimLogic.turns("1981-03-14", LocalDate.of(2026, 3, 1)))
        assertNull(MailPimLogic.turns("--03-14", LocalDate.of(2026, 3, 1)))
        assertNotNull(MailPimLogic.turns("2000-02-29", LocalDate.of(2026, 3, 1)))
    }

    @Test
    fun `offline copy - ranges near today, file names`() {
        val now = ms(2026, 11, 1, 12)
        assertEquals(1L to 2L, MailPimCacheLogic.rangeOf("1-2.json"))
        assertNull(MailPimCacheLogic.rangeOf("2-1.json"))
        assertNull(MailPimCacheLogic.rangeOf("x.json"))
        assertTrue(MailPimCacheLogic.keep(now - 10L * MailPimLogic.DAY_MS, now, now))
        assertFalse(MailPimCacheLogic.keep(now - 90L * MailPimLogic.DAY_MS, now - 40L * MailPimLogic.DAY_MS, now))
        assertFalse(MailPimCacheLogic.keep(now + 100L * MailPimLogic.DAY_MS, now + 120L * MailPimLogic.DAY_MS, now))
        val json = MailPimCacheLogic.cardsJson(listOf(sum(5, "Anna", emails = listOf("a@x.de"))), 42)
        val back = MailPimJson.cardPage(json)!!
        assertEquals(42L, back.version)
        assertEquals("Anna", back.items.single().name)
        assertEquals(listOf("a@x.de"), back.items.single().emails)
    }
}
