package com.gatecontrol.android.notizen

import com.gatecontrol.android.drive.DReminder
import com.gatecontrol.android.drive.DriveJson
import com.gatecontrol.android.drive.DrivePush
import com.gatecontrol.android.navigation.NotizenRoutes
import com.gatecontrol.android.notify.model.PushAction
import com.gatecontrol.android.notify.model.PushActionType
import com.gatecontrol.android.notify.model.PushMessage
import com.gatecontrol.android.push.ButtonKind
import com.gatecontrol.android.push.NotificationQuickActions
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * „Aufgaben und Erinnerungen“, stage 5 (plugin 1.7.0 / Drive 1.30.0, the apps' contract): the reminders of Drive's
 * deadlines ring from the hub — `Task.deadline`, „Datei öffnen“ at the phone's alarm and at the push (three buttons of
 * four), `Deadline.reminder.by` in Drive, and one message instead of two. With older plugins all stays as it was.
 */
class NotizenStage5Test {
    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val deadlineJson = """{"date":"2026-11-30","kind":"cancellation","file":"Versicherungsschein.pdf","daysBefore":3,"open":{"plugin":"gatecontrol-drive","route":"node/41"}}"""
    private val deadline = NDeadline("2026-11-30", "cancellation", "Versicherungsschein.pdf", 3, NOpen("gatecontrol-drive", "node/41"))

    private fun me(features: String, extra: String = "") =
        NotizenJson.me(json("""{"ok":true,"user":{"id":2,"name":"Papa"},"lang":"de","today":"2026-11-27","time":"08:00","features":[$features],"prefs":{}$extra}"""))!!

    // ── recognising the plugins ─────────────────────────────

    @Test
    fun `the ability is the word driveDeadlines, never a version`() {
        val old = me(""""tasks","reminderActions","familyTasks"""")
        assertFalse(old.driveDeadlines)
        assertFalse(old.driveDeadlinesActive)
        assertNull(old.driveDeadlineInfo)
        val ready = me(""""tasks","driveDeadlines"""", ""","driveDeadlines":{"available":true,"active":false}""")
        assertTrue(ready.driveDeadlines)
        assertFalse(ready.driveDeadlinesActive, "Drive reminds itself (the default)")
        assertTrue(me(""""driveDeadlines"""", ""","driveDeadlines":{"available":true,"active":true}""").driveDeadlinesActive)
        // the object without the word: not believed
        assertFalse(me(""""tasks"""", ""","driveDeadlines":{"available":true,"active":true}""").driveDeadlinesActive)
    }

    // ── the task ────────────────────────────────────────────

    @Test
    fun `deadline of a task, null and an older plugin`() {
        val t = NotizenJson.task(
            json("""{"id":77,"type":"task","title":"Kfz-Versicherung kündigen","due":{"date":"2026-11-30","time":null},
                "source":{"plugin":"gatecontrol-drive","id":"deadline:12"},"deadline":$deadlineJson}"""),
        )!!
        assertEquals(deadline, t.deadline)
        assertEquals(NSourceRef("gatecontrol-drive", "deadline:12"), t.source)
        assertNull(NotizenJson.task(json("""{"id":77,"title":"Altglas","deadline":null}"""))!!.deadline)
        assertNull(NotizenJson.task(json("""{"id":77,"title":"Altglas"}"""))!!.deadline, "a plugin without the field")
        assertNull(NotizenJson.task(json("""{"id":77,"title":"Altglas","deadline":"x"}"""))!!.deadline)
    }

    @Test
    fun `what of a deadline cannot be read is left out, not the deadline`() {
        val d = NotizenJson.deadline(json("""{"date":"30.11.","kind":"other","daysBefore":-2,"open":{"plugin":"evil","route":"node/41"}}"""))!!
        assertEquals(NDeadline(null, null, null, 0, null), d)
        assertEquals(0, NotizenJson.deadline(json("""{"date":"2026-11-30"}"""))!!.daysBefore)
        assertNull(NotizenJson.deadline(null))
    }

    @Test
    fun `the file of a deadline is a file's page in Drive`() {
        assertEquals(41L, NotizenPush.fileNode(deadline))
        assertNull(NotizenPush.fileNode(null))
        assertNull(NotizenPush.fileNode(deadline.copy(open = null)))
        assertNull(NotizenPush.fileNode(deadline.copy(open = NOpen("gatecontrol-drive", ""))), "no node: nothing to open")
        assertNull(NotizenPush.fileNode(deadline.copy(open = NOpen("gatecontrol-mail", "message/41"))))
        // in the app: the same target a carried item of Drive opens
        val have = NotizenOpen.Have(mail = true, drive = true, familie = true, schule = true)
        assertEquals(NotizenOpen.DriveFile(41), NotizenOpen.target(deadline.open, have))
        assertNull(NotizenOpen.target(deadline.open, have.copy(drive = false)), "without the Drive tab: a hint")
    }

    // ── the phone's own alarm ───────────────────────────────

    private val three = """[{"id":"done","label":"Erledigt"},{"id":"snooze_1h","label":"In 1 Std."},{"id":"snooze_eve","label":"Heute Abend"}]"""

    private fun feed(extra: String) = NotizenJson.reminders(
        json("""{"ok":true,"nowMs":1,"enabled":true,"reminders":[{"tag":"task:77:1795762800000","taskId":77,"listId":5,"area":"private",
            "title":"Frist in 3 Tagen: Kfz-Versicherung kündigen","body":"Kündigung bis 30.11.2026 · Versicherungsschein.pdf",
            "at":"2026-11-27T08:00:00+01:00","atMs":1795762800000,"important":false,"snoozed":false,"collapseKey":"task:77",
            "actions":$three,"attachedList":null$extra}]}"""),
    )!!.reminders.single()

    @Test
    fun `GET reminders - the deadline of an entry, kept on the phone`() {
        val r = feed(""","deadline":$deadlineJson""")
        assertEquals(deadline, r.deadline)
        assertNull(feed("").deadline)
        // the phone's copy (it rings after a boot and without a connection)
        val kept = ReminderState.fromJson(ReminderState(listOf(r), setOf("task:77"), deadlines = mapOf(12L to 500L)).toJson())
        assertEquals(deadline, kept.reminders.single().deadline)
        assertEquals(mapOf(12L to 500L), kept.deadlines)
        assertNull(ReminderState.fromJson(ReminderState(listOf(feed(""))).toJson()).reminders.single().deadline)
        assertEquals(emptyMap<Long, Long>(), ReminderState.fromJson(json("""{"reminders":[],"deadlines":{"x":1,"-3":2,"7":"y"}}""")).deadlines)
    }

    @Test
    fun `the alarm of a deadline - Erledigt, the first Spaeter, Datei oeffnen`() {
        val r = feed(""","deadline":$deadlineJson""")
        assertEquals(listOf("done", "snooze_1h", "open_file"), ReminderPlan.buttons(r, "Datei öffnen").map { it.id })
        assertEquals("Datei öffnen", ReminderPlan.buttons(r, "Datei öffnen").last().label)
        assertEquals(ReminderPlan.MAX_BUTTONS, ReminderPlan.buttons(r, "Datei öffnen").size)
    }

    @Test
    fun `the alarm stays as it was - an older plugin, another task, a deadline without a file`() {
        val ids = listOf("done", "snooze_1h", "snooze_eve")
        // the plugin does not say `driveDeadlines`: no text, no button
        assertEquals(ids, ReminderPlan.buttons(feed(""","deadline":$deadlineJson"""), null).map { it.id })
        assertEquals(ids, ReminderPlan.buttons(feed(""","deadline":$deadlineJson""")).map { it.id })
        assertEquals(ids, ReminderPlan.buttons(feed(""), "Datei öffnen").map { it.id })
        assertEquals(ids, ReminderPlan.buttons(feed(""","deadline":{"date":"2026-11-30","daysBefore":3,"open":null}"""), "Datei öffnen").map { it.id })
        // a button named `open_file` by the server is not taken for a callback
        val odd = feed("").copy(actions = listOf(NReminderAction("done", "Erledigt"), NReminderAction("open_file", "Datei öffnen")))
        assertEquals(listOf("done"), ReminderPlan.buttons(odd, "Datei öffnen").map { it.id })
    }

    // ── the push ────────────────────────────────────────────

    private val done = PushAction("done", "Erledigt", PushActionType.CALLBACK)
    private val hour = PushAction("snooze_1h", "In 1 Std.", PushActionType.CALLBACK)
    private val evening = PushAction("snooze_eve", "Heute Abend", PushActionType.CALLBACK)
    private val openFile = PushAction("open_file", "Datei öffnen", PushActionType.OPEN_PORTAL, target = "/portal?drive=41#plg-gatecontrol-drive")
    private val seen = PushAction("seen", "Gesehen", PushActionType.ACK)

    private fun push(notizen: String?, actions: List<PushAction> = listOf(done, hour, evening, openFile), topic: String = NotizenPush.TOPIC_REMINDERS) = PushMessage(
        seq = 1, id = 9, eventId = "e", topic = topic, priority = "normal", title = "Frist in 3 Tagen: Kfz-Versicherung kündigen", body = "Kündigung bis 30.11.2026 · Versicherungsschein.pdf",
        createdAt = null, expiresAt = null, collapseKey = "task:77", silent = false, route = null, actions = actions, dataJson = notizen?.let { """{"notizen":$it}""" },
    )

    private val reminder = """{"kind":"reminder","taskId":77,"listId":5,"area":"private","tag":"task:77:1795762800000","remindAtMs":1795762800000,"deadline":$deadlineJson}"""

    @Test
    fun `the push of a deadline - three of its four buttons, Datei oeffnen among them`() {
        val m = push(reminder)
        assertEquals(listOf(done, hour, openFile), NotizenPush.buttons(m))
        val plan = NotificationQuickActions.plan(NotizenPush.buttons(m), seen)
        assertEquals(listOf("done", "snooze_1h", "open_file"), plan.map { it.action.id })
        assertEquals(listOf(ButtonKind.BACKGROUND, ButtonKind.BACKGROUND, ButtonKind.OPEN), plan.map { it.kind })
        // … and it opens the file's page in the app, not the portal
        assertEquals("file:41:77@private", NotizenPush.fileDestination(m))
        assertEquals("file:41:77@private", NotizenPush.fileButton(NotizenPush.fileDestination(m), "open_file"))
        assertNull(NotizenPush.fileButton(NotizenPush.fileDestination(m), "done"))
        assertNull(NotizenPush.fileButton(NotizenPush.fileDestination(m), null), "a tap on the message: the task")
        assertEquals("task:77@private", NotizenPush.destination(m))
        assertEquals("task:77:1795762800000", NotizenPush.reminderTag(m))
    }

    @Test
    fun `every other push keeps its buttons`() {
        // a reminder of an ordinary task, an older plugin
        val plain = push("""{"kind":"reminder","taskId":77,"tag":"task:77:1795762800000"}""", listOf(done, hour, evening))
        assertEquals(listOf(done, hour, evening), NotizenPush.buttons(plain))
        assertNull(NotizenPush.fileDestination(plain))
        // four buttons, but no file the app can open: as GateControl sent them (the first three show)
        val noFile = push("""{"kind":"reminder","taskId":77,"deadline":{"date":"2026-11-30","daysBefore":3,"open":null}}""")
        assertEquals(listOf(done, hour, evening, openFile), NotizenPush.buttons(noFile))
        // not a reminder, another topic, another plugin
        assertNull(NotizenPush.fileDestination(push("""{"kind":"assigned","taskId":77,"deadline":$deadlineJson}""")))
        assertNull(NotizenPush.fileDestination(push(reminder, topic = NotizenPush.TOPIC_SHARED)))
        assertNull(NotizenPush.fileDestination(push(reminder, topic = "plugin:gatecontrol-drive:deadline")))
        assertNull(NotizenPush.fileDestination(push(null)))
        // already among the first three: nothing to move
        val short = push(reminder, listOf(done, openFile))
        assertEquals(listOf(done, openFile), NotizenPush.buttons(short))
    }

    @Test
    fun `Datei oeffnen - the file in Drive, without its tab the task`() {
        val dest = "file:41:77@private"
        assertEquals(NotizenPush.File(41, 77, "private"), NotizenPush.decode(dest))
        assertEquals(dest, NotizenPush.encode(NotizenPush.File(41, 77, "private")))
        assertEquals("file:41:77", NotizenPush.encode(NotizenPush.File(41, 77)))
        assertEquals(41L, NotizenPush.driveFile(dest))
        assertNull(NotizenPush.driveFile("task:77"))
        assertNull(NotizenPush.driveFile(null))
        // no Drive tab in the app: the task, which names the file
        assertEquals(listOf(NotizenPush.Tab, NotizenPush.Route(NotizenRoutes.task(77))), NotizenPush.steps(dest, listed = true))
        assertEquals(listOf(NotizenPush.Tab), NotizenPush.steps(dest, listed = false))
        for (bad in listOf("file:41", "file:0:77", "file:41:0", "file:x:77", "file:41:77:3")) assertNull(NotizenPush.decode(bad), bad)
    }

    // ── Drive: who rings, and one message instead of two ────

    @Test
    fun `Drive - reminder by, an older Drive is Drive`() {
        fun reminder(r: String) = DriveJson.deadline(json("""{"id":12,"kind":"cancellation","date":"2026-11-30","label":"Kfz","reminder":$r}"""))!!.reminder
        val hub = reminder("""{"at":"2026-11-27T07:00:00.000Z","daysBefore":3,"sent":false,"by":"notizen"}""")!!
        assertEquals(DReminder("2026-11-27T07:00:00.000Z", 3, false, DReminder.BY_HUB), hub)
        assertTrue(hub.byHub)
        assertFalse(reminder("""{"at":"2026-11-27T07:00:00.000Z","daysBefore":3,"sent":false,"by":"drive"}""")!!.byHub)
        assertFalse(reminder("""{"at":"2026-11-27T07:00:00.000Z","daysBefore":3,"sent":false}""")!!.byHub, "Drive before 1.30.0")
        assertEquals(DReminder.BY_DRIVE, reminder("""{"daysBefore":3,"sent":true,"by":"someone"}""")!!.by)
        assertNull(reminder("null"))
    }

    private fun drivePush(data: String?, topic: String = "plugin:gatecontrol-drive:deadline") = PushMessage(
        seq = 2, id = 10, eventId = "d", topic = topic, priority = "normal", title = "Frist", body = "",
        createdAt = null, expiresAt = null, collapseKey = "drive-deadline:12", silent = false, route = null, actions = emptyList(), dataJson = data,
    )

    @Test
    fun `Drive's own message names its deadline`() {
        assertEquals(12L, DrivePush.deadlineOf(drivePush("""{"drive":{"node":41,"area":"private","deadline":12}}""")))
        assertNull(DrivePush.deadlineOf(drivePush("""{"drive":{"node":41}}""")))
        assertNull(DrivePush.deadlineOf(drivePush("""{"drive":{"node":41,"deadline":12}}""", "plugin:gatecontrol-drive:vault")))
        assertNull(DrivePush.deadlineOf(drivePush("""{"drive":{"deadline":12}}""", NotizenPush.TOPIC_REMINDERS)))
        assertNull(DrivePush.deadlineOf(drivePush(null)))
    }

    @Test
    fun `no second message - Drive's own after the phone's alarm of the hub`() {
        val hour = 3_600_000L
        val t0 = 1_795_762_800_000L
        assertEquals(12L, ReminderGuard.deadlineId(NSourceRef("gatecontrol-drive", "deadline:12")))
        assertNull(ReminderGuard.deadlineId(NSourceRef("gatecontrol-mail", "deadline:12")))
        assertNull(ReminderGuard.deadlineId(NSourceRef("gatecontrol-drive", "node:12")))
        assertNull(ReminderGuard.deadlineId(null))

        // nothing shown by the phone: Drive's message shows (Drive reminds itself, or the hub's push came instead)
        assertFalse(ReminderGuard.driveTwin(emptyMap(), 12, t0))
        val shown = ReminderGuard.deadlineShown(emptyMap(), 12, t0)
        assertTrue(ReminderGuard.driveTwin(shown, 12, t0 + 5_000))
        assertTrue(ReminderGuard.driveTwin(shown, 12, t0 + 23 * hour))
        // another deadline, a message without one, a day later: shown
        assertFalse(ReminderGuard.driveTwin(shown, 13, t0 + 5_000))
        assertFalse(ReminderGuard.driveTwin(shown, null, t0 + 5_000))
        assertFalse(ReminderGuard.driveTwin(shown, 12, t0 + 24 * hour))
        // what is older than a day is forgotten
        assertEquals(mapOf(13L to t0 + 25 * hour), ReminderGuard.deadlineShown(shown, 13, t0 + 25 * hour))
        assertEquals(setOf(12L, 13L), ReminderGuard.deadlineShown(shown, 13, t0 + hour).keys)
    }
}
