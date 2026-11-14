package com.gatecontrol.android.notizen

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * „Aufgaben und Erinnerungen“, stage 1 (plugin 1.3.0, the apps' contract): the words in `features`, the new fields of a
 * task, `GET reminders`, „Gestern offen geblieben“ — and that everything stays as it was with a plugin without them.
 */
class NotizenStage1Test {
    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private fun me(features: String, extra: String = "") = NotizenJson.me(json("""{"ok":true,"user":{"id":12,"name":"Jenny"},"lang":"de","today":"2026-11-19","time":"07:40",
        "timezone":"Europe/Berlin","features":[$features],"prefs":{"area":"private"},"sharing":"all","vaults":false,"imports":{},"attachMB":25,"readOnly":false$extra}"""))!!

    // ── recognising the plugin ──────────────────────────────

    @Test
    fun `the abilities are the words in features, never a version`() {
        val old = me(""""tasks","notes","listDays","shareRoles"""")
        assertFalse(old.reminderActions)
        assertFalse(old.yesterdayOpen)
        assertFalse(old.calendarShown)
        assertNull(old.calendar)
        val new = me(""""tasks","listDays","reminderActions","yesterdayOpen","taskCalendar"""", ""","calendar":{"available":true,"enabled":true,"mine":true,"name":"Aufgaben"},"yesterday":{"push":true,"at":"07:30"}""")
        assertTrue(new.reminderActions)
        assertTrue(new.yesterdayOpen)
        assertTrue(new.calendarShown)
        assertEquals("Aufgaben", new.calendar?.name)
        // each word by itself
        assertTrue(me(""""reminderActions"""").reminderActions)
        assertFalse(me(""""reminderActions"""").yesterdayOpen)
    }

    @Test
    fun `the calendar shows only with the feature and all three switches`() {
        fun shown(features: String, a: Boolean, e: Boolean, m: Boolean) = me(features, ""","calendar":{"available":$a,"enabled":$e,"mine":$m,"name":"Aufgaben"}""").calendarShown
        assertTrue(shown(""""taskCalendar"""", true, true, true))
        assertFalse(shown(""""taskCalendar"""", false, true, true), "Mail is not installed")
        assertFalse(shown(""""taskCalendar"""", true, false, true), "the administrator's switch")
        assertFalse(shown(""""taskCalendar"""", true, true, false), "my own switch")
        assertFalse(shown(""""tasks"""", true, true, true), "the word is missing")
        assertFalse(me(""""taskCalendar"""").calendarShown, "no calendar in me")
    }

    @Test
    fun `prefs - the morning hint and the calendar switch, the defaults of an older plugin`() {
        val p = NotizenJson.prefs(json("""{"notify":{"reminders":true,"shared":true,"listday":true,"yesterday":false},"yesterdayAt":"06:45","calendar":{"tasks":false}}"""))
        assertFalse(p.notify.yesterday)
        assertEquals("06:45", p.yesterdayAt)
        assertFalse(p.calendarTasks)
        val old = NotizenJson.prefs(json("""{"notify":{"reminders":false}}"""))
        assertTrue(old.notify.yesterday)
        assertEquals("07:30", old.yesterdayAt)
        assertTrue(old.calendarTasks)
        assertEquals("07:30", NotizenJson.prefs(json("""{"yesterdayAt":"halb acht"}""")).yesterdayAt)
        assertEquals("""{"yesterdayAt":"06:45","calendar":{"tasks":true},"notify":{"yesterday":false}}""", NotizenJson.prefsBody(notify = mapOf("yesterday" to false), yesterdayAt = "06:45", calendarTasks = true).toString())
        assertEquals("{}", NotizenJson.prefsBody(yesterdayAt = "bald").toString())
    }

    // ── a task's new fields ─────────────────────────────────

    private val task = """{"id":41,"listId":3,"title":"Mülltonne rausstellen","due":{"date":"2026-11-15","time":"09:00"},"remindAt":"2026-11-15T09:00:00+01:00",
        "reminder":{"tag":"task:41:1794729600000","at":"2026-11-15T09:00:00+01:00","atMs":1794729600000,"mine":true,"snoozed":false},
        "snoozed":false,"missedReminder":false,"calendar":{"state":"synced","eventId":912,"calendarId":7,"mine":true}}"""

    @Test
    fun `reminder, snoozed, missedReminder and calendar of a task`() {
        val t = NotizenJson.task(json(task))!!
        assertEquals(NReminder("task:41:1794729600000", "2026-11-15T09:00:00+01:00", 1794729600000, mine = true, snoozed = false), t.reminder)
        assertFalse(t.snoozed)
        assertFalse(t.missedReminder)
        assertEquals(NTaskCalendar("synced", 912, 7, mine = true), t.calendar)
        assertTrue(t.calendar!!.synced)
        val pending = NotizenJson.task(json("""{"id":41,"title":"A","snoozed":true,"missedReminder":true,"calendar":{"state":"pending","mine":false}}"""))!!
        assertTrue(pending.snoozed)
        assertTrue(pending.missedReminder)
        assertFalse(pending.calendar!!.synced)
        assertNull(pending.calendar!!.eventId)
    }

    @Test
    fun `a task of an older plugin, and fields that cannot be read`() {
        val old = NotizenJson.task(json("""{"id":41,"title":"A","remindAt":"2026-11-15T09:00:00+01:00"}"""))!!
        assertNull(old.reminder)
        assertNull(old.calendar)
        assertFalse(old.snoozed)
        assertEquals("2026-11-15T09:00:00+01:00", old.remindAt, "as before")
        val odd = NotizenJson.task(json("""{"id":41,"title":"A","reminder":{"tag":"nope","atMs":5},"calendar":{"state":"elsewhere"},"snoozed":"yes"}"""))!!
        assertNull(odd.reminder)
        assertNull(odd.calendar)
        assertFalse(odd.snoozed)
        assertNull(NotizenJson.task(json("""{"id":41,"title":"A","reminder":null,"calendar":null}"""))!!.reminder)
    }

    // ── GET reminders ───────────────────────────────────────

    private val feed = """{"ok":true,"now":"2026-11-14T18:02:11+01:00","nowMs":1794675731000,"enabled":true,
        "reminders":[{"tag":"task:41:1794729600000","taskId":41,"listId":3,"area":"private","title":"Mülltonne rausstellen","body":"Fällig heute 09:00 · Aufgaben",
          "at":"2026-11-15T09:00:00+01:00","atMs":1794729600000,"important":false,"snoozed":false,"collapseKey":"task:41",
          "actions":[{"id":"done","label":"Erledigt"},{"id":"snooze_1h","label":"In 1 Std."},{"id":"snooze_eve","label":"Heute Abend"}]},
          {"tag":"task:42:1","taskId":43,"title":"the tag names another task","atMs":1},
          {"tag":"task:44:1794729600000","taskId":44,"atMs":1794729600000},
          {"tag":"task:45:1794729600000","taskId":45,"title":"B","atMs":1794729600000,"collapseKey":"../x","actions":[{"id":"DROP TABLE","label":"x"},{"id":"done"}]}],
        "yesterday":{"push":true,"at":"07:30"}}"""

    @Test
    fun `the reminders the phone rings for - a bad one is dropped, not the list`() {
        val f = NotizenJson.reminders(json(feed))!!
        assertEquals(1794675731000, f.nowMs)
        assertTrue(f.enabled)
        assertEquals(listOf(41L, 45L), f.reminders.map { it.taskId })
        val r = f.reminders[0]
        assertEquals("task:41:1794729600000", r.tag)
        assertEquals("task:41", r.collapseKey)
        assertEquals("Fällig heute 09:00 · Aufgaben", r.body)
        assertEquals(listOf("done", "snooze_1h", "snooze_eve"), r.actions.map { it.id })
        assertEquals("In 1 Std.", r.actions[1].label)
        assertEquals("task:45", f.reminders[1].collapseKey, "a key that is not one: the task's")
        assertTrue(f.reminders[1].actions.isEmpty())
        // „Erinnerungen an Aufgaben“ off: the list is empty, no alarms
        val off = NotizenJson.reminders(json("""{"ok":true,"nowMs":1,"enabled":false,"reminders":[]}"""))!!
        assertFalse(off.enabled)
        assertNull(NotizenJson.reminders(json("""{"ok":true}""")), "not the answer of GET reminders")
    }

    // ── „Gestern offen geblieben“ ───────────────────────────

    private fun t(id: Long, title: String = "A", done: Boolean = false) = """{"uid":"t:$id","type":"task","id":$id,"listId":3,"title":"$title","done":$done,"due":{"date":"2026-11-18"},"overdue":true}"""

    private fun myDay(yesterday: String?) = NotizenJson.view(json("""{"ok":true,"view":"myday","title":"Mein Tag","sections":[
        {"id":"overdue","title":"Überfällig","count":3,"items":[${t(41, "Schrauben kaufen")},${t(42, "Rezept abholen")},${t(50, "Steuer")}]},
        {"id":"today","title":"Heute","count":1,"items":[${t(60, "Mülltonne rausstellen")}]}]${yesterday?.let { ""","yesterday":$it""" }.orEmpty()}}"""))!!

    private val yesterday = """{"day":"2026-11-18","title":"Gestern offen geblieben","count":2,"items":[${t(41, "Schrauben kaufen")},${t(42, "Rezept abholen")}],
        "moveOptions":[{"to":"tomorrow","date":"2026-11-20","label":"Morgen"},{"to":"weekend","date":"2026-11-21","label":"Am Wochenende"},{"to":"never","label":"Nie"}]}"""

    @Test
    fun `the card of yesterday is read with its tasks and the choices of days`() {
        val y = myDay(yesterday).yesterday!!
        assertEquals("2026-11-18", y.day)
        assertEquals("Gestern offen geblieben", y.title)
        assertEquals(listOf(41L, 42L), y.items.map { it.id })
        assertEquals(listOf(NMoveOption("tomorrow", "2026-11-20", "Morgen"), NMoveOption("weekend", "2026-11-21", "Am Wochenende")), y.moveOptions)
        assertNull(myDay(null).yesterday, "an older plugin has no such field")
        assertEquals(y, NotizenJson.yesterday(json("""{"ok":true,${yesterday.trim().removePrefix("{")}""")), "GET yesterday is the same object")
    }

    @Test
    fun `the card stands above Heute and its tasks are not under Überfällig as well`() {
        val view = myDay(yesterday)
        val card = NotizenYesterday.card(view, enabled = true)!!
        assertEquals(2, card.count)
        val sections = NotizenYesterday.sections(view, card)
        assertEquals(listOf("t:50"), sections.first { it.id == "overdue" }.items.map { it.uid }, "the card's uids are hidden there")
        assertEquals(listOf("t:60"), sections.first { it.id == "today" }.items.map { it.uid })
    }

    @Test
    fun `no card without the feature, with an older plugin or with nothing left - Überfällig stays whole`() {
        val view = myDay(yesterday)
        assertNull(NotizenYesterday.card(view, enabled = false))
        assertEquals(3, NotizenYesterday.sections(view, null).first { it.id == "overdue" }.items.size)
        assertNull(NotizenYesterday.card(myDay(null), enabled = true))
        assertNull(NotizenYesterday.card(myDay("""{"day":"2026-11-18","title":"Gestern offen geblieben","count":0,"items":[],"moveOptions":[]}"""), enabled = true))
        assertNull(NotizenYesterday.card(null, enabled = true))
        // everything ticked off already
        assertNull(NotizenYesterday.card(myDay("""{"title":"x","items":[${t(41, done = true)}]}"""), enabled = true))
    }

    @Test
    fun `a row decided leaves the card and Überfällig at once`() {
        val after = NotizenYesterday.without(myDay(yesterday), setOf(41L))
        assertEquals(listOf(42L), after.yesterday!!.items.map { it.id })
        assertEquals(listOf("t:42", "t:50"), after.sections.first { it.id == "overdue" }.items.map { it.uid })
        assertEquals(1, after.sections.first { it.id == "today" }.items.size)
        val none = NotizenYesterday.without(after, setOf(42L))
        assertNull(NotizenYesterday.card(none, enabled = true), "every row decided: the card goes")
    }

    @Test
    fun `the tasks a button of the morning hint means`() {
        assertEquals(listOf(41L), NotizenYesterday.asked(listOf(41, 42), open = listOf(41, 50)), "only those of the hint still open")
        assertEquals(listOf(41L, 42L), NotizenYesterday.asked(listOf(41, 42), open = null), "offline: what the hint named")
        assertEquals(listOf(41L, 50L), NotizenYesterday.asked(emptyList(), open = listOf(41, 50)))
        assertTrue(NotizenYesterday.asked(listOf(41), open = emptyList()).isEmpty())
    }

    @Test
    fun `the answer of POST yesterday and the bodies sent`() {
        val r = NotizenJson.yesterdayResult(json("""{"ok":true,"changed":2,"failed":[{"id":43,"code":"forbidden"}],"yesterday":{"day":"2026-11-18","title":"Gestern offen geblieben","count":0,"items":[],"moveOptions":[]}}"""))
        assertEquals(2, r.changed)
        assertEquals(listOf(43L), r.failed)
        assertNotNull(r.yesterday)
        assertEquals("""{"action":"done","ids":[41,42]}""", NotizenJson.yesterdayBody("done", ids = listOf(41, 42)).toString())
        assertEquals("""{"action":"move","to":"weekend","ids":[41]}""", NotizenJson.yesterdayBody("move", "weekend", listOf(41)).toString())
        assertEquals("""{"to":"today"}""", NotizenJson.moveBody("today").toString())
        assertEquals("2026-11-25", NotizenJson.moveTarget("2026-11-25"))
        assertNull(NotizenJson.moveTarget("someday"))
        assertEquals("""{"until":"1h","tag":"task:41:1794729600000"}""", NotizenJson.snoozeBody("1h", "task:41:1794729600000").toString())
        assertEquals("""{"until":"evening"}""", NotizenJson.snoozeBody("evening", "not a tag").toString())
        assertEquals(false, NotizenJson.snoozeAnswer(json("""{"ok":true,"snoozed":false,"task":{"id":41,"title":"A"}}""")))
    }

    // ── offline: the outbox ─────────────────────────────────

    @Test
    fun `Später and Verschieben wait in the outbox and are read again`() {
        val O = NotizenOutbox
        val snooze = NotizenOutbox.Op(O.ACTION, 41, false, 5, O.SNOOZE, NotizenJson.snoozeBody("1h", "task:41:1794729600000"), uid = "a")
        val move = NotizenOutbox.Op(O.ACTION, 41, false, 6, O.MOVE, NotizenJson.moveBody("tomorrow"), uid = "b")
        assertEquals(snooze, O.fromJson(O.toJson(snooze)))
        assertEquals(move, O.fromJson(O.toJson(move)))
        assertNull(O.fromJson(json("""{"kind":"action","id":41,"what":"format_disk","body":{}}""")))
        assertNull(O.fromJson(json("""{"kind":"action","id":41,"what":"move"}""")), "nothing to send")
        // a later move of the same task takes the place of the one waiting; the snooze is another thing
        var ops = O.add(O.add(emptyList(), snooze), move)
        ops = O.add(ops, move.copy(body = NotizenJson.moveBody("weekend"), uid = "c"))
        assertEquals(listOf("a", "c"), ops.map { it.uid })
        assertEquals(41L, O.target(ops[1], emptyMap()))
        assertEquals("""{"to":"weekend"}""", O.prepared(ops[1], emptyMap()).toString())
    }

    @Test
    fun `decided without a connection - the card and Überfällig show it at once`() {
        val O = NotizenOutbox
        val ops = listOf(
            NotizenOutbox.Op(O.TASK, 41, true, 1, uid = "t"),
            NotizenOutbox.Op(O.ACTION, 42, false, 2, O.MOVE, NotizenJson.moveBody("today"), uid = "m"),
        )
        val view = O.overlay(myDay(yesterday), ops, "2026-11-19")
        assertNull(NotizenYesterday.card(view, enabled = true), "ticked off and moved: nothing left to decide")
        val overdue = view.sections.first { it.id == "overdue" }.items
        assertEquals(listOf("t:41", "t:50"), overdue.map { it.uid }, "the moved one is not overdue any more")
        assertTrue(overdue.first { it.uid == "t:41" }.done, "the tick shows")
    }
}
