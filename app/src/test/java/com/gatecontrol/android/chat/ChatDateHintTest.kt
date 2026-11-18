package com.gatecontrol.android.chat

import com.gatecontrol.android.mail.MCalPrefs
import com.gatecontrol.android.mail.MCalendar
import com.gatecontrol.android.mail.MCalendars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * „Termin anlegen“ reads the numeric day itself: „30.11 um 08:00“ (no dot after the month) is the 30th, not today, and
 * the title keeps no date words. Sent on Wednesday 18.11.2026, 10:00 in Berlin.
 */
class ChatDateHintTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val sent = LocalDateTime.of(2026, 11, 18, 10, 0).atZone(zone).toInstant().toEpochMilli()

    /** What the plugin answers today for „30.11 um 08:00 …“: only the time, today, and „30 11“ in the title. */
    private val serverToday = ChatSuggestedDate("2026-11-18T07:00:00.000Z", "Mi., 18.11. 08:00", "30 11 kommt der Schornsteinfeger")

    private fun at(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun read(text: String, server: ChatSuggestedDate? = null) = ChatDateHint.prefill(text, sent, zone, server)

    @Test
    fun `day without the last dot and a time`() {
        val p = read("30.11 um 08:00 kommt der Schornsteinfeger", serverToday)!!
        assertEquals(at(2026, 11, 30, 8), p.startMs)
        assertEquals(false, p.allDay)
        assertEquals("kommt der Schornsteinfeger", p.title)
        assertEquals("Mo., 30.11. 08:00", ChatDateHint.label(p, zone, Locale.GERMANY))
    }

    @Test
    fun `day with the last dot is all day`() {
        val p = read("30.11. Elternabend in der Aula")!!
        assertEquals(at(2026, 11, 30), p.startMs)
        assertTrue(p.allDay)
        assertEquals("Elternabend in der Aula", p.title)
        assertEquals("Mo., 30.11.", ChatDateHint.label(p, zone, Locale.GERMANY))
    }

    @Test
    fun `day with the year`() {
        val p = read("Zahnarzt 30.11.2026 um 9 Uhr")!!
        assertEquals(at(2026, 11, 30, 9), p.startMs)
        assertEquals("Zahnarzt", p.title)
        assertEquals(at(2026, 11, 30), read("Lesung 30.11.26")!!.startMs)
    }

    @Test
    fun `am day um hour`() {
        val p = read("Probe am 30.11 um 8")!!
        assertEquals(at(2026, 11, 30, 8), p.startMs)
        assertEquals("Probe", p.title)
    }

    @Test
    fun `day and a clock time without um`() {
        val p = read("30.11 8:00 Lieferung Brennholz")!!
        assertEquals(at(2026, 11, 30, 8), p.startMs)
        assertEquals("Lieferung Brennholz", p.title)
    }

    @Test
    fun `a past day without a year is next year's`() {
        val p = read("Sommerfest 05.07")!!
        assertEquals(at(2027, 7, 5), p.startMs)
        assertTrue(p.allDay)
        // today stays this year
        assertEquals(at(2026, 11, 18), read("18.11 Hausputz")!!.startMs)
    }

    @Test
    fun `a time is not a day`() {
        // „um 10.15“ and „10.15 Uhr“ are times: the server's date stays
        val server = ChatSuggestedDate("2026-11-19T09:15:00.000Z", "Do., 19.11. 10:15", "Morgen Training")
        assertEquals(at(2026, 11, 19, 10, 15), read("Morgen um 10.15 Training", server)!!.startMs)
        assertEquals(at(2026, 11, 19, 10, 15), read("Morgen 10.15 Uhr Training", server)!!.startMs)
        assertNull(read("Nur so ein Text 15.30"))
    }

    @Test
    fun `the server's title loses what is left of a numeric date`() {
        assertEquals("kommt der Schornsteinfeger", ChatDateHint.cleanTitle("30 11 kommt der Schornsteinfeger"))
        val p = read("Bis bald!", serverToday)!!
        assertEquals(at(2026, 11, 18, 8), p.startMs)
        assertEquals("kommt der Schornsteinfeger", p.title)
    }

    // ─── calendars ──────────────────────────────────────────────────

    private fun cal(id: Long, name: String, writable: Boolean = true, source: String = "local", isDefault: Boolean = false) =
        MCalendar(id, source, null, name, "#336699", "private", writable, true, true, isDefault, true, false, 3, "ok", null, null, 1)

    @Test
    fun `mail calendars first, the default one leading, then Familie`() {
        val mail = MCalendars(
            listOf(cal(1, "Arbeit"), cal(2, "Privat", isDefault = true), cal(3, "Feiertage", writable = false), cal(4, "Abo", source = "ics")),
            emptyList(), MCalPrefs.DEFAULT, emptyList(),
        )
        val t = ChatCalendarChoice.targets(mail, familie = true, familieName = "Familie-Kalender")
        assertEquals(listOf("mail:2", "mail:1", "familie"), t.map { it.key })
        // first time: Mail; later: what was picked last
        assertEquals("mail:2", ChatCalendarChoice.default(t, null)?.key)
        assertEquals("familie", ChatCalendarChoice.default(t, "familie")?.key)
        assertEquals("mail:1", ChatCalendarChoice.default(t, "mail:1")?.key)
        // a calendar that is gone: Mail again
        assertEquals("mail:2", ChatCalendarChoice.default(t, "mail:9")?.key)
    }

    @Test
    fun `without Mail it is Familie`() {
        val t = ChatCalendarChoice.targets(null, familie = true, familieName = "Familie-Kalender")
        assertEquals(listOf("familie"), t.map { it.key })
        assertEquals("familie", ChatCalendarChoice.default(t, "mail:2")?.key)
        assertTrue(ChatCalendarChoice.targets(null, familie = false, familieName = "x").isEmpty())
    }

    @Test
    fun `the mail event of a draft`() {
        val timed = ChatCalendarChoice.mailInput(ChatSuggestedEventDraft("Probe", at(2026, 11, 30, 8), false, "mail:2"), 2, zone)
        assertEquals(2L, timed.calendarId)
        assertEquals(at(2026, 11, 30, 8), timed.start)
        assertEquals(at(2026, 11, 30, 9), timed.end)
        assertNull(timed.startDate)
        val day = ChatCalendarChoice.mailInput(ChatSuggestedEventDraft("Fest", at(2026, 11, 30), true, "mail:2"), 2, zone)
        assertEquals("2026-11-30", day.startDate)
        assertEquals("2026-11-30", day.endDate)
        assertNull(day.start)
    }
}
