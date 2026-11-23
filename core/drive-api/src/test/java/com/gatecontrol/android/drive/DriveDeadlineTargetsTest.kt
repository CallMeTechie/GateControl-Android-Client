package com.gatecontrol.android.drive

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Drive 1.17 (`deadlineMailCalendar`): deadlines into the family calendar or a calendar of the mail plugin. */
class DriveDeadlineTargetsTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val outlook = DMailAccount("acc_1", "Outlook", "microsoft")
    private val posteo = DMailAccount("acc_2", "Posteo", "caldav")
    private val work = DMailCalendar("cal_a", "Kalender", outlook, "#3366ff")
    private val birthdays = DMailCalendar("cal_b", "Geburtstage", outlook, null)
    private val home = DMailCalendar("cal_c", "Privat", posteo, null)

    @Test
    fun `me reports the mail calendar feature`() {
        val me = DriveJson.me(json("""{"me":{"id":7},"licence":{"valid":true},"features":["deadlineCalendar","deadlineMailCalendar"]}"""))!!
        assertTrue(me.has(DMe.FEATURE_DEADLINE_MAIL_CALENDAR))
    }

    @Test
    fun `calendars and targets are read`() {
        val c = DriveJson.deadlineCalendars(
            json(
                """{"family":true,"mail":[
                {"id":"cal_a","name":"Kalender","account":{"id":"acc_1","label":"Outlook","kind":"microsoft"},"color":"#3366ff"},
                {"id":"cal_a","name":"doppelt","account":{"id":"acc_1","label":"Outlook"}},
                {"id":7,"name":"Privat","account":{"id":2,"label":"Posteo"},"color":"blue"},
                {"name":"ohne Id"}]}""",
            ),
        )!!
        assertTrue(c.family)
        assertEquals(listOf("cal_a", "7"), c.mail.map { it.id })
        assertEquals(work, c.mail[0])
        assertNull(c.mail[1].color)
        assertEquals("2", c.mail[1].account.id)
        assertEquals(DDeadlineCalendars(false, emptyList()), DriveJson.deadlineCalendars(json("{}")))

        val rows = DriveJson.deadlineTargets(
            json(
                """{"targets":[
                {"areaId":"mine:7","label":"Meine Dateien","target":{"kind":"mail","calendarId":"cal_a","label":"Outlook · Kalender"}},
                {"areaId":"family:1","label":"Familie","target":{"kind":"family"}},
                {"areaId":"family:2","label":"Andere","target":null},
                {"areaId":"bad id","target":{"kind":"family"}},
                {"areaId":"team:3","label":"Team","target":{"kind":"weird"}}]}""",
            ),
        )!!
        assertEquals(listOf("mine:7", "family:1", "family:2", "team:3"), rows.map { it.areaId })
        assertEquals(DDeadlineTarget("mail", "cal_a", "Outlook · Kalender"), rows[0].target)
        assertTrue(rows[1].target!!.family)
        assertNull(rows[2].target)
        assertNull(rows[3].target)
    }

    @Test
    fun `bodies of the target and of a single deadline`() {
        val t = DriveDeadlineTargets.mailTarget(work)
        assertEquals(
            """{"targets":[{"areaId":"mine:7","target":{"kind":"mail","calendarId":"cal_a","label":"Outlook · Kalender"}}]}""",
            DriveJson.deadlineTargetsBody("mine:7", t).toString(),
        )
        assertEquals("""{"targets":[{"areaId":"family:1","target":{"kind":"family"}}]}""", DriveJson.deadlineTargetsBody("family:1", DDeadlineTarget.FAMILY).toString())
        assertEquals("""{"targets":[{"areaId":"family:1","target":null}]}""", DriveJson.deadlineTargetsBody("family:1", null).toString())
        // the family calendar as before without a target
        assertEquals("{}", DriveJson.deadlineCalendarBody(null).toString())
        assertEquals("{}", DriveJson.deadlineCalendarBody(DDeadlineTarget.FAMILY).toString())
        assertEquals("""{"target":{"kind":"mail","calendarId":"cal_a","label":"Outlook · Kalender"}}""", DriveJson.deadlineCalendarBody(t).toString())
    }

    @Test
    fun `a deadline's entry names its target`() {
        val d = DriveJson.deadlineAnswer(json("""{"deadline":{"id":4,"kind":"cancellation","date":"2026-11-30","calendar":{"eventId":"e1","at":"2026-11-01T10:00:00Z","target":{"kind":"mail","label":"Outlook · Kalender"}}}}"""))!!
        assertEquals(DDeadlineTarget("mail", null, "Outlook · Kalender"), d.calendar?.target)
        assertEquals("Outlook", DriveDeadlineTargets.entryText(d.calendar))
        // the family calendar and older servers: the app's own words
        assertNull(DriveDeadlineTargets.entryText(DCalendarEntry("e1", null, DDeadlineTarget.FAMILY)))
        assertNull(DriveDeadlineTargets.entryText(DCalendarEntry("e1", null)))
        assertEquals("", DriveDeadlineTargets.entryText(DCalendarEntry("e1", null, DDeadlineTarget("mail"))))
    }

    @Test
    fun `the choice - off, the family calendar, then the mail calendars by account`() {
        val c = DDeadlineCalendars(family = true, mail = listOf(work, home, birthdays))
        val g = DriveDeadlineTargets.groups(c, mailFeature = true)
        assertEquals(listOf(null, outlook, posteo), g.map { it.account })
        assertEquals(listOf(null, DDeadlineTarget.FAMILY), g[0].choices.map { it.target })
        assertEquals(listOf("cal_a", "cal_b"), g[1].choices.map { it.target?.calendarId })
        assertEquals("Outlook · Geburtstage", g[1].choices[1].target?.label)
        assertEquals(listOf("cal_c"), g[2].choices.map { it.calendar?.id })

        // without Familie: no family calendar; without the feature: no mail calendars
        assertEquals(listOf<DDeadlineTarget?>(null), DriveDeadlineTargets.groups(c.copy(family = false), mailFeature = false).flatMap { it.choices }.map { it.target })
        // a single deadline: no "off"
        assertEquals(4, DriveDeadlineTargets.targets(c, mailFeature = true).size)
        assertTrue(DriveDeadlineTargets.groups(DDeadlineCalendars.NONE, mailFeature = true, off = false).isEmpty())
    }

    @Test
    fun `a single deadline asks only with more than one target, the area's target first`() {
        val all = DriveDeadlineTargets.targets(DDeadlineCalendars(true, listOf(work, home)), mailFeature = true)
        assertTrue(DriveDeadlineTargets.ask(all))
        assertFalse(DriveDeadlineTargets.ask(DriveDeadlineTargets.targets(DDeadlineCalendars(true, emptyList()), mailFeature = true)))
        assertFalse(DriveDeadlineTargets.ask(DriveDeadlineTargets.targets(DDeadlineCalendars(false, listOf(work)), mailFeature = true)))
        assertFalse(DriveDeadlineTargets.ask(emptyList()))

        val rows = listOf(
            DDeadlineTargetRow("mine:7", "Meine Dateien", DDeadlineTarget("mail", "cal_c", "alt")),
            DDeadlineTargetRow("family:1", "Familie", null),
            DDeadlineTargetRow("team:3", "Team", DDeadlineTarget("mail", "gone")),
        )
        assertEquals("cal_c", DriveDeadlineTargets.preselect(rows, "mine:7", all)?.calendarId)
        // off, a calendar no longer there or an unknown area: the family calendar, else the first
        assertEquals(DDeadlineTarget.FAMILY, DriveDeadlineTargets.preselect(rows, "family:1", all))
        assertEquals(DDeadlineTarget.FAMILY, DriveDeadlineTargets.preselect(rows, "team:3", all))
        assertEquals(DDeadlineTarget.FAMILY, DriveDeadlineTargets.preselect(rows, null, all))
        assertEquals("cal_a", DriveDeadlineTargets.preselect(rows, "family:1", all.filter { it.mail })?.calendarId)
        assertNull(DriveDeadlineTargets.preselect(rows, "mine:7", emptyList()))
    }

    @Test
    fun `the value of a target and setting it`() {
        val c = DDeadlineCalendars(true, listOf(work))
        // the calendar as it is now, else the server's label
        assertEquals("Outlook · Kalender", DriveDeadlineTargets.mailText(DDeadlineTarget("mail", "cal_a", "alt"), c))
        assertEquals("alt", DriveDeadlineTargets.mailText(DDeadlineTarget("mail", "gone", "alt"), c))
        assertNull(DriveDeadlineTargets.mailText(DDeadlineTarget("mail", "gone"), c))
        assertNull(DriveDeadlineTargets.mailText(DDeadlineTarget.FAMILY, c))
        assertNull(DriveDeadlineTargets.mailText(null, c))
        assertEquals("Kalender", DriveDeadlineTargets.mailLabel(DMailCalendar("x", "Kalender", DMailAccount("a", "", null), null)))

        val rows = listOf(DDeadlineTargetRow("mine:7", "Meine Dateien", null), DDeadlineTargetRow("family:1", "Familie", DDeadlineTarget.FAMILY))
        val now = DriveDeadlineTargets.put(rows, "mine:7", DriveDeadlineTargets.mailTarget(work))
        assertEquals("cal_a", now[0].target?.calendarId)
        assertEquals(DDeadlineTarget.FAMILY, now[1].target)
        assertNull(DriveDeadlineTargets.put(now, "mine:7", null)[0].target)
        assertTrue(DDeadlineTarget.FAMILY.sameAs(DDeadlineTarget("family", label = "Familienkalender")))
        assertFalse(DriveDeadlineTargets.mailTarget(work).sameAs(DriveDeadlineTargets.mailTarget(home)))
    }
}
