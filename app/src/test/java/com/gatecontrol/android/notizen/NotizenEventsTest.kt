package com.gatecontrol.android.notizen

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

/**
 * „Termin“ in the quick entry (plugin 1.9.0, `calendarEvents`; contract „Termine in Schnell hinzufügen“): the body of
 * `POST events`, the JSON of the calendars and of `Item.event`, why no event can be made, the waiting event in the
 * outbox and on „Mein Tag“, the link into Mail's form.
 */
class NotizenEventsTest {

    private fun obj(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val draft = NotizenEvents.Draft(
        title = "Fashion Outlet", date = "2026-12-04", time = "14:00", endTime = "15:00", location = null,
        calendarId = 7, calendarChosen = false, reminder = null, reminderChosen = false, repeat = null,
    )

    private val calendarsJson = """
        { "ok": true, "state": "ok", "reason": null,
          "calendars": [
            { "id": 7, "key": "cal:7", "name": "Privat", "color": "#4F8DF5", "kind": "own", "writable": true, "target": true, "isDefault": true, "area": "private", "shown": true },
            { "id": 9, "key": "cal:9", "name": "Feiertage", "color": "#999999", "kind": "subscribed", "writable": false, "target": false, "isDefault": false, "area": "private", "shown": false },
            { "id": 11, "key": "cal:11", "name": "Arbeit", "color": "red", "kind": "own", "writable": true, "target": true, "isDefault": false, "area": "work", "shown": true },
            { "id": null, "key": "overlay:familie", "name": "Familie", "color": "#F5B544", "kind": "family", "writable": false, "target": false, "isDefault": false, "area": "private", "shown": true } ],
          "defaultId": 7, "reminders": { "timed": 15, "allDay": 360 }, "timeZone": "Europe/Berlin" }
    """.trimIndent()

    // ── the body of POST events ─────────────────────────────

    @Test
    fun `body - the calendar and the reminder only when chosen`() {
        val b = NotizenEvents.body(draft, "0b6c1f0e-7c1d-4a55-9a70-3f5d2c1a9e10")
        assertEquals(obj("""{"title":"Fashion Outlet","date":"2026-12-04","time":"14:00","endTime":"15:00","clientId":"0b6c1f0e-7c1d-4a55-9a70-3f5d2c1a9e10"}"""), b)
        val chosen = NotizenEvents.body(draft.copy(calendarId = 11, calendarChosen = true, reminder = 30, reminderChosen = true, location = " Halle 2 "), null)
        assertEquals(11L, chosen.get("calendarId").asLong)
        assertEquals(30, chosen.get("reminder").asInt)
        assertEquals("Halle 2", chosen.get("location").asString)
        assertFalse(chosen.has("clientId"))
        // „Keine Erinnerung“ chosen: false
        assertFalse(NotizenEvents.body(draft.copy(reminderChosen = true, reminder = null), null).get("reminder").asBoolean)
    }

    @Test
    fun `body - all day without a time, a rule, never after completion`() {
        val b = NotizenEvents.body(draft.copy(time = null, endTime = "15:00", repeat = NRepeat.Rule("weekly", 1, listOf(5))), null)
        assertFalse(b.has("time"))
        assertFalse(b.has("endTime"))
        assertEquals(obj("""{"type":"weekly","interval":1,"days":[5]}"""), b.get("repeat"))
        assertFalse(NotizenEvents.body(draft.copy(repeat = NRepeat.Rule("after", 3)), null).has("repeat"))
        // one line, at most 255
        assertEquals("a b", NotizenEvents.body(draft.copy(title = "a\nb"), null).get("title").asString)
        assertEquals(255, NotizenEvents.body(draft.copy(title = "x".repeat(400)), null).get("title").asString.length)
        // a clientId the server would refuse is left out
        assertFalse(NotizenEvents.body(draft, "kurz").has("clientId"))
    }

    @Test
    fun `an hour later wraps past midnight`() {
        assertEquals("15:30", NotizenEvents.plusHour("14:30"))
        assertEquals("00:30", NotizenEvents.plusHour("23:30"))
    }

    // ── JSON ────────────────────────────────────────────────

    @Test
    fun `calendars - targets, the default, the reminders`() {
        val c = NotizenEventsJson.calendars(obj(calendarsJson))!!
        assertTrue(c.ok)
        assertEquals(listOf(7L, 11L), c.targets.map { it.id })
        assertEquals(7L, c.default?.id)
        assertEquals(15, c.remindTimed)
        assertEquals(360, c.remindAllDay)
        assertEquals("Europe/Berlin", c.timeZone)
        // a colour that is no #RRGGBB is none; the family's calendar has no id
        assertNull(c.calendars.first { it.key == "cal:11" }.color)
        assertNull(c.calendars.first { it.kind == "family" }.id)
        val none = NotizenEventsJson.calendars(obj("""{"ok":true,"state":"none","reason":"no_mail","calendars":[]}"""))!!
        assertFalse(none.ok)
        assertEquals("no_mail", none.reason)
        assertNull(NotizenEventsJson.calendars(obj("""{"ok":true}""")))
    }

    @Test
    fun `an item of the overview with its event, me with events`() {
        val item = NotizenJson.item(
            obj(
                """{ "uid": "s:318", "type": "source", "id": 318, "kind": "event", "title": "Zahnarzt", "subtitle": "Praxis am Markt",
                  "due": { "date": "2026-11-27", "time": "16:30" }, "show": { "date": "2026-11-27", "time": "16:30" },
                  "allDay": false, "overdue": false, "done": false, "canComplete": false, "area": "private",
                  "source": { "plugin": "gatecontrol-mail", "label": "Mail", "open": { "plugin": "gatecontrol-mail", "route": "event/912" } },
                  "group": { "id": "cal:7", "label": "Privat" },
                  "event": { "end": { "date": "2026-11-27", "time": "17:15" }, "location": "Praxis am Markt",
                             "calendar": { "key": "cal:7", "name": "Privat", "color": "#4F8DF5", "kind": "own" },
                             "eventId": 912, "recurring": false, "writable": true } }""",
            ),
        )!!
        val ev = item.event!!
        assertEquals(NDue("2026-11-27", "17:15"), ev.end)
        assertEquals("Praxis am Markt", ev.location)
        assertEquals("#4F8DF5", ev.color)
        assertEquals(912L, ev.eventId)
        assertEquals("Privat", ev.calendarName)
        // Schule's and Alltag's events stay as they are: no event
        assertNull(NotizenJson.item(obj("""{"id":5,"kind":"event","title":"Elternabend","source":{"plugin":"gatecontrol-schule"}}"""))!!.event)
        val me = NotizenJson.me(obj("""{"user":{"id":3},"today":"2026-11-27","features":["calendarEvents"],"events":{"available":false,"reason":"no_mail"}}"""))!!
        assertTrue(me.calendarEvents)
        assertEquals(NEventsInfo(false, "no_mail"), me.events)
        // an older plugin: no word, no field
        val old = NotizenJson.me(obj("""{"user":{"id":3},"today":"2026-11-27","features":[]}"""))!!
        assertFalse(old.calendarEvents)
        assertNull(old.events)
    }

    @Test
    fun `the answer of POST events`() {
        val c = NotizenEventsJson.created(obj("""{"ok":true,"event":{"id":912,"calendarId":7},"items":[],"clientId":"0b6c1f0e","replayed":true}"""))!!
        assertEquals(912L, c.eventId)
        assertEquals(7L, c.calendarId)
        assertTrue(c.replayed)
        assertNull(NotizenEventsJson.created(obj("""{"ok":true}""")))
    }

    // ── why no event can be made ────────────────────────────

    @Test
    fun `reasons - the card, lasting or passing`() {
        assertEquals(NotizenEvents.WHY_MAIL, NotizenEvents.why("no_mail"))
        assertEquals(NotizenEvents.WHY_MAIL, NotizenEvents.why("mail_old"))
        for (r in listOf("feature_off", "not_allowed", "not_licensed", "no_seat")) assertEquals(NotizenEvents.WHY_OFF, NotizenEvents.why(r))
        assertEquals(NotizenEvents.WHY_NONE, NotizenEvents.why("no_calendar"))
        assertEquals(NotizenEvents.WHY_UNREACHABLE, NotizenEvents.why("unreachable"))
        assertTrue(NotizenEvents.permanent("no_mail"))
        assertFalse(NotizenEvents.permanent("unreachable"))
        assertFalse(NotizenEvents.permanent("auth_failed"))
    }

    @Test
    fun `blocked - the calendars first, me otherwise, unreachable waits`() {
        val me = NotizenJson.me(obj("""{"user":{"id":3},"today":"2026-11-27","features":["calendarEvents"],"events":{"available":false,"reason":"no_mail"}}"""))!!
        val ok = NotizenEventsJson.calendars(obj(calendarsJson))!!
        assertEquals("no_mail", NotizenEvents.blocked(me, null))
        assertNull(NotizenEvents.blocked(me, ok), "the newer word of the calendars counts")
        val off = NotizenEventsJson.calendars(obj("""{"state":"none","reason":"feature_off","calendars":[]}"""))!!
        assertEquals("feature_off", NotizenEvents.blocked(null, off))
        // a calendar list without one to write in
        assertEquals("no_calendar", NotizenEvents.blocked(null, ok.copy(calendars = ok.calendars.filter { !it.target })))
        // Mail does not answer: no card, the event waits
        val away = NotizenEventsJson.calendars(obj("""{"state":"none","reason":"unreachable","calendars":[]}"""))!!
        assertNull(NotizenEvents.blocked(null, away))
        assertTrue(NotizenEvents.waits(null, away))
        assertFalse(NotizenEvents.waits(null, ok))
        assertEquals(15, NotizenEvents.defaultReminder(ok, allDay = false))
        assertEquals(360, NotizenEvents.defaultReminder(ok, allDay = true))
    }

    // ── the outbox ──────────────────────────────────────────

    private fun eventOp(body: JsonObject, id: Long = -3) = NotizenOutbox.Op(NotizenOutbox.CREATE, id, false, 1000, NotizenOutbox.EVENT, body, body.get("clientId")?.asString, uid = "u1")

    @Test
    fun `a waiting event is kept on the phone and shown on My Day`() {
        val body = NotizenEvents.body(draft.copy(location = "Halle 2"), "0b6c1f0e-7c1d-4a55-9a70-3f5d2c1a9e10")
        val op = eventOp(body)
        val back = NotizenOutbox.fromJson(NotizenOutbox.toJson(op))!!
        assertEquals(op, back)
        val item = NotizenEvents.itemOf(op)!!
        assertTrue(NotizenEvents.isPending(item))
        assertEquals("Fashion Outlet", item.title)
        assertEquals(NDue("2026-12-04", "14:00"), item.show)
        assertEquals("Halle 2", item.event?.location)
        assertFalse(item.canComplete)
        // a task's overlay does not take it for a task
        assertNull(NotizenOutbox.taskOf(op))
        val today = NotizenEvents.itemOf(eventOp(NotizenEvents.body(draft.copy(date = "2026-11-27", time = "16:00"), "aaaaaaaa-1"), id = -4))!!
        val view = NView("myday", "Mein Tag", listOf(NSection("today", "Heute", 0, emptyList(), false)))
        val ops = listOf(op, eventOp(NotizenEvents.body(draft.copy(date = "2026-11-27", time = "16:00"), "aaaaaaaa-1"), id = -4))
        val shown = NotizenOutbox.overlay(view, ops, "2026-11-27")
        assertEquals(listOf("events", "today", "upcoming"), shown.sections.map { it.id })
        assertEquals(listOf(today.uid), shown.sections.first { it.id == "events" }.items.map { it.uid })
        assertEquals(listOf(op.id), shown.sections.first { it.id == "upcoming" }.items.map { (it as NItem).id })
        // more than 14 days ahead: not under „Demnächst“; another view: untouched
        assertEquals(view, NotizenEvents.overlay(view, listOf(eventOp(NotizenEvents.body(draft.copy(date = "2027-01-20"), "bbbbbbbb-2"))), "2026-11-27"))
        val planned = NView("planned", "Geplant", emptyList())
        assertEquals(planned, NotizenEvents.overlay(planned, ops, "2026-11-27"))
    }

    @Test
    fun `refused - waits, without its calendar, as a task, given up`() {
        val body = NotizenEvents.body(draft.copy(calendarId = 11, calendarChosen = true, location = "Halle 2"), "0b6c1f0e-7c1d-4a55-9a70-3f5d2c1a9e10")
        val op = eventOp(body)
        val cid = { "11111111-2222-3333-4444-555555555555" }
        // Mail does not answer: it stays as it is
        assertEquals(op, NotizenEvents.refused(op, 503, "unreachable", null, cid))
        assertEquals(op, NotizenEvents.refused(op, 503, null, null, cid))
        // the calendar is gone: the default one, a new clientId (another body)
        val noCal = NotizenEvents.refused(op, 404, "not_found", "calendarId", cid)!!
        assertFalse(noCal.body!!.has("calendarId"))
        assertEquals(cid(), noCal.clientId)
        assertEquals(cid(), noCal.body!!.get("clientId").asString)
        assertEquals(NotizenOutbox.EVENT, noCal.what)
        // no event for good: a task with its day, time, reminder — the place in the note
        val task = NotizenEvents.refused(op, 503, "no_mail", null, cid)!!
        assertEquals(NotizenOutbox.TASK, task.what)
        assertEquals(obj("""{"title":"Fashion Outlet","due":{"date":"2026-12-04","time":"14:00"},"remindAt":{"date":"2026-12-04","time":"14:00"},"note":"Halle 2","clientId":"${cid()}"}"""), task.body)
        assertNotNull(NotizenOutbox.taskOf(task))
        // a wrong field: given up
        assertNull(NotizenEvents.refused(op, 400, null, "date", cid))
    }

    // ── Mail's form, filled in ──────────────────────────────

    @Test
    fun `form arguments - the hub's wall clock, over midnight, all day`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val a = NotizenEvents.formArgs(draft.copy(location = "Halle 2"), berlin, ZoneId.of("UTC"), 7, 15)
        assertEquals(Instant.parse("2026-12-04T13:00:00Z").toEpochMilli(), a.start)
        assertEquals(Instant.parse("2026-12-04T14:00:00Z").toEpochMilli(), a.end)
        assertEquals("15", a.reminder)
        assertEquals("Halle 2", a.location)
        assertEquals(7L, a.calendarId)
        val night = NotizenEvents.formArgs(draft.copy(time = "22:00", endTime = "01:00"), berlin, berlin, 7, null)
        assertEquals(3 * 3_600_000L, night.end - night.start)
        assertEquals("none", night.reminder)
        val day = NotizenEvents.formArgs(draft.copy(time = null, endTime = null, reminderChosen = true, reminder = 900), berlin, berlin, 7, 360)
        assertTrue(day.allDay)
        assertEquals(day.start, day.end)
        assertEquals("900", day.reminder)
    }

    @Test
    fun `the rule for Mail's form and back`() {
        assertEquals("weekly:2:MO,FR", NotizenEvents.repeatArg(NRepeat.Rule("weekly", 2, listOf(1, 5))))
        assertEquals("monthly:1", NotizenEvents.repeatArg(NRepeat.Rule("monthly", 1)))
        assertNull(NotizenEvents.repeatArg(NRepeat.Rule("after", 3)))
        assertNull(NotizenEvents.repeatArg(null))
        assertEquals(Triple("weekly", 2, listOf("MO", "FR")), NotizenEvents.parseRepeatArg("weekly:2:MO,FR"))
        assertEquals(Triple("yearly", 1, emptyList<String>()), NotizenEvents.parseRepeatArg("yearly"))
        assertNull(NotizenEvents.parseRepeatArg("hourly:1"))
        assertNull(NotizenEvents.parseRepeatArg(null))
    }

    @Test
    fun `a tap on an event opens its page in Mail`() {
        val have = NotizenOpen.Have(mail = true, drive = true, familie = true, schule = true)
        assertEquals(NotizenOpen.MailEvent(912, null), NotizenOpen.target(NOpen("gatecontrol-mail", "event/912"), have))
        assertEquals(NotizenOpen.MailEvent(912, "20261204"), NotizenOpen.target(NOpen("gatecontrol-mail", "event/912/20261204"), have))
        assertEquals(NotizenOpen.Tab("gatecontrol-mail"), NotizenOpen.target(NOpen("gatecontrol-mail", "calendar/2026-12-04"), have))
        assertEquals(NotizenOpen.Tab("gatecontrol-mail"), NotizenOpen.target(NOpen("gatecontrol-mail", "event/x"), have))
        assertNull(NotizenOpen.target(NOpen("gatecontrol-mail", "event/912"), have.copy(mail = false)))
    }
}
