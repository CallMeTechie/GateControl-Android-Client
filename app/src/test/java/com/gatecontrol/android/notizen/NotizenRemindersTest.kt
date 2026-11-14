package com.gatecontrol.android.notizen

import com.gatecontrol.android.notify.model.PushMessage
import com.gatecontrol.android.notizen.ReminderGuard.Seen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The phone's own alarms for the reminders of tasks (plugin 1.3.0, contract § 6): which alarms are set — with a fixed
 * clock —, and the rule that keeps the server's push and the phone's alarm from showing one reminder twice.
 */
class NotizenRemindersTest {
    private val hour = 3_600_000L
    private val now = 1_794_675_731_000L

    private fun reminder(taskId: Long, atMs: Long) = NReminderEntry(
        tag = "task:$taskId:$atMs", taskId = taskId, listId = 3, area = "private", title = "Mülltonne rausstellen", body = "Fällig heute 09:00 · Aufgaben",
        atMs = atMs, important = false, collapseKey = "task:$taskId",
        actions = listOf(NReminderAction("done", "Erledigt"), NReminderAction("snooze_1h", "In 1 Std."), NReminderAction("snooze_eve", "Heute Abend")),
    )

    // ── planning ────────────────────────────────────────────

    @Test
    fun `alarms for what is to come, soonest first, one per task`() {
        val plan = ReminderPlan.plan(listOf(reminder(42, now + 5 * hour), reminder(41, now + hour), reminder(41, now + 9 * hour)), emptySet(), emptyMap(), now)
        assertEquals(listOf("task:41", "task:42"), plan.set.map { it.key })
        assertEquals(listOf(now + hour, now + 5 * hour), plan.set.map { it.atMs })
        assertEquals("task:41:${now + hour}", plan.set[0].tag, "the same name as the push of this reminder")
        assertTrue(plan.cancel.isEmpty())
    }

    @Test
    fun `an alarm whose reminder is gone from the list is cancelled, a moved one is set anew under the same key`() {
        // task 41 was done on another device, task 42 snoozed (a new tag, the same notification)
        val plan = ReminderPlan.plan(listOf(reminder(42, now + 2 * hour)), setOf("task:41", "task:42"), emptyMap(), now)
        assertEquals(setOf("task:41"), plan.cancel)
        assertEquals(listOf(ReminderPlan.Alarm("task:42", "task:42:${now + 2 * hour}", now + 2 * hour)), plan.set)
        // „Erinnerungen an Aufgaben“ off or an older plugin: an empty list takes every alarm away
        assertEquals(setOf("task:41", "task:42"), ReminderPlan.plan(emptyList(), setOf("task:41", "task:42"), emptyMap(), now).cancel)
    }

    @Test
    fun `a reminder missed while the phone was off rings at once, one older than a day not at all`() {
        val plan = ReminderPlan.plan(listOf(reminder(1, now - 2 * hour), reminder(2, now - 25 * hour)), emptySet(), emptyMap(), now)
        assertEquals(listOf(ReminderPlan.Alarm("task:1", "task:1:${now - 2 * hour}", now)), plan.set)
    }

    @Test
    fun `no alarm for a reminder shown or decided already, and no more than the limit`() {
        val a = reminder(1, now + hour)
        val b = reminder(2, now + 2 * hour)
        val seen = mapOf(a.tag to Seen(ReminderGuard.DECIDED, now - 1000), b.tag to Seen(ReminderGuard.PUSH, now - 1000))
        assertTrue(ReminderPlan.plan(listOf(a, b), emptySet(), seen, now).set.isEmpty())
        val many = (1L..200L).map { reminder(it, now + it * 60_000) }
        val plan = ReminderPlan.plan(many, emptySet(), emptyMap(), now)
        assertEquals(ReminderPlan.MAX_ALARMS, plan.set.size)
        assertEquals("task:1", plan.set.first().key)
        assertEquals("task:${ReminderPlan.MAX_ALARMS}", plan.set.last().key)
    }

    @Test
    fun `settled on the phone while offline - ticked off, deleted or snoozed - its alarm stays quiet`() {
        val O = NotizenOutbox
        assertFalse(ReminderPlan.settledOffline(emptyList(), 41))
        assertTrue(ReminderPlan.settledOffline(listOf(NotizenOutbox.Op(O.TASK, 41, true, 1)), 41))
        assertFalse(ReminderPlan.settledOffline(listOf(NotizenOutbox.Op(O.TASK, 41, true, 1)), 42), "another task")
        assertFalse(ReminderPlan.settledOffline(listOf(NotizenOutbox.Op(O.TASK, 41, true, 1), NotizenOutbox.Op(O.TASK, 41, false, 2)), 41), "ticked back")
        assertTrue(ReminderPlan.settledOffline(listOf(NotizenOutbox.Op(O.DELETE, 41, false, 1, O.TASK)), 41))
        assertTrue(ReminderPlan.settledOffline(listOf(NotizenOutbox.Op(O.ACTION, 41, false, 1, O.SNOOZE, NotizenJson.snoozeBody("1h", null))), 41))
        assertFalse(ReminderPlan.settledOffline(listOf(NotizenOutbox.Op(O.ACTION, 41, false, 1, O.MOVE, NotizenJson.moveBody("tomorrow"))), 41), "the server moves the reminder along")
        assertFalse(ReminderPlan.settledOffline(listOf(NotizenOutbox.Op(O.PATCH, 41, false, 1, O.TASK, com.google.gson.JsonObject())), 41))
    }

    // ── no reminder twice (contract § 6) ────────────────────

    @Test
    fun `the alarm first, then the push - the push takes its place quietly`() {
        val tag = "task:41:${now}"
        var seen = emptyMap<String, Seen>()
        assertTrue(ReminderGuard.alarmShows(seen, tag, now))
        seen = ReminderGuard.shown(seen, tag, ReminderGuard.LOCAL, now)
        assertEquals(ReminderGuard.Push.REPLACE, ReminderGuard.onPush(seen, tag, now + 20_000))
        // the phone's second alarm (the inexact pair) does not show it again
        assertFalse(ReminderGuard.alarmShows(seen, tag, now + 30_000))
    }

    @Test
    fun `the push first - the alarm stays quiet`() {
        val tag = "task:41:${now}"
        assertEquals(ReminderGuard.Push.SHOW, ReminderGuard.onPush(emptyMap(), tag, now))
        val seen = ReminderGuard.shown(emptyMap(), tag, ReminderGuard.PUSH, now)
        assertFalse(ReminderGuard.alarmShows(seen, tag, now + 60_000))
        assertEquals(ReminderGuard.Push.SHOW, ReminderGuard.onPush(seen, tag, now + 60_000), "a replay of the push itself replaces its own notification")
    }

    @Test
    fun `decided on the phone - a later push or alarm of the same reminder is not shown, for 48 hours`() {
        val tag = "task:41:${now}"
        var seen = ReminderGuard.shown(emptyMap(), tag, ReminderGuard.LOCAL, now)
        seen = ReminderGuard.decided(seen, tag, now + 5_000)
        assertEquals(ReminderGuard.Push.HIDE, ReminderGuard.onPush(seen, tag, now + hour))
        assertFalse(ReminderGuard.alarmShows(seen, tag, now + hour))
        // shown again by mistake: it stays decided
        assertEquals(ReminderGuard.DECIDED, ReminderGuard.shown(seen, tag, ReminderGuard.PUSH, now + hour)[tag]?.state)
        assertEquals(ReminderGuard.Push.HIDE, ReminderGuard.onPush(seen, tag, now + 47 * hour))
        assertEquals(ReminderGuard.Push.SHOW, ReminderGuard.onPush(seen, tag, now + 49 * hour))
        assertTrue(ReminderGuard.pruned(seen, now + 49 * hour).isEmpty())
    }

    @Test
    fun `an alarm that rang without showing gets no second alarm, and its push is shown as ever`() {
        val r = reminder(41, now - 60_000)
        // notifications off on this phone, or the task ticked off while offline: the alarm rang and showed nothing
        val seen = ReminderGuard.skipped(emptyMap(), r.tag, now)
        assertFalse(ReminderGuard.alarmShows(seen, r.tag, now + 1_000))
        assertTrue(ReminderPlan.plan(listOf(r), emptySet(), seen, now + 1_000).set.isEmpty(), "the next reload does not set it again")
        assertEquals(ReminderGuard.Push.SHOW, ReminderGuard.onPush(seen, r.tag, now + 2_000), "with its sound: nothing shows yet")
        // what is known already stays
        val decided = ReminderGuard.decided(emptyMap(), r.tag, now)
        assertEquals(ReminderGuard.DECIDED, ReminderGuard.skipped(decided, r.tag, now + 1)[r.tag]?.state)
        val push = ReminderGuard.shown(emptyMap(), r.tag, ReminderGuard.PUSH, now)
        assertEquals(ReminderGuard.PUSH, ReminderGuard.skipped(push, r.tag, now + 1)[r.tag]?.state)
    }

    @Test
    fun `after Später the reminder has a new name - the later one is wanted`() {
        val seen = ReminderGuard.decided(emptyMap(), "task:41:$now", now)
        val later = "task:41:${now + hour}"
        assertTrue(ReminderGuard.alarmShows(seen, later, now + hour))
        assertEquals(ReminderGuard.Push.SHOW, ReminderGuard.onPush(seen, later, now + hour))
    }

    @Test
    fun `the buttons of the alarm - done and the three ways of Später`() {
        assertEquals("1h", ReminderGuard.untilOf("snooze_1h"))
        assertEquals("evening", ReminderGuard.untilOf("snooze_eve"))
        assertEquals("tomorrow", ReminderGuard.untilOf("snooze_tom"))
        assertNull(ReminderGuard.untilOf("done"))
        assertNull(ReminderGuard.untilOf("open"))
    }

    @Test
    fun `quiet hours - also over midnight, in the person's time zone`() {
        // 2026-11-14 18:02:11 in Berlin
        assertFalse(ReminderGuard.inQuietHours("22:00", "07:00", "Europe/Berlin", now))
        assertTrue(ReminderGuard.inQuietHours("18:00", "19:00", "Europe/Berlin", now))
        assertTrue(ReminderGuard.inQuietHours("22:00", "07:00", "Europe/Berlin", now + 5 * hour))
        assertTrue(ReminderGuard.inQuietHours("22:00", "07:00", "Europe/Berlin", now + 12 * hour), "06:02 the next morning")
        assertFalse(ReminderGuard.inQuietHours("22:00", "07:00", "Europe/Berlin", now + 13 * hour + 60_000))
        assertFalse(ReminderGuard.inQuietHours("18:00", "19:00", "UTC", now), "17:02 there")
        assertFalse(ReminderGuard.inQuietHours("08:00", "08:00", "Europe/Berlin", now))
        assertFalse(ReminderGuard.inQuietHours("abends", "07:00", "Europe/Berlin", now))
    }

    // ── kept on the phone ───────────────────────────────────

    @Test
    fun `what the phone keeps is read again as it was, broken parts are left out`() {
        val r = reminder(41, now + hour)
        val state = ReminderState(listOf(r), setOf("task:41"), mapOf(r.tag to Seen(ReminderGuard.LOCAL, now)))
        assertEquals(state, ReminderState.fromJson(state.toJson()))
        assertEquals(ReminderState(), ReminderState.fromJson(null))
        val broken = state.toJson().apply {
            getAsJsonArray("alarms").add("../etc")
            getAsJsonObject("seen").add("nonsense", com.google.gson.JsonObject())
        }
        assertEquals(state, ReminderState.fromJson(broken))
    }

    // ── the push's name ─────────────────────────────────────

    private fun msg(topic: String, data: String?) = PushMessage(
        seq = 1, id = 1, eventId = "e", topic = topic, priority = "normal", title = "t", body = "",
        createdAt = null, expiresAt = null, collapseKey = "task:41", silent = false, route = null, actions = emptyList(), dataJson = data,
    )

    @Test
    fun `the tag of a reminder push - none for an older plugin or another message`() {
        val data = """{"notizen":{"kind":"reminder","taskId":41,"listId":3,"area":"private","task":41,"tag":"task:41:1794729600000","remindAtMs":1794729600000,"late":false,"snoozed":false}}"""
        assertEquals("task:41:1794729600000", NotizenPush.reminderTag(msg(NotizenPush.TOPIC_REMINDERS, data)))
        // plugin 1.2: no tag — the push is shown as ever
        assertNull(NotizenPush.reminderTag(msg(NotizenPush.TOPIC_REMINDERS, """{"notizen":{"kind":"reminder","taskId":41,"task":41}}""")))
        assertNull(NotizenPush.reminderTag(msg(NotizenPush.TOPIC_REMINDERS, """{"notizen":{"kind":"yesterday","day":"2026-11-18","count":2,"taskIds":[41,42]}}""")))
        assertNull(NotizenPush.reminderTag(msg(NotizenPush.TOPIC_SHARED, data)))
        assertNull(NotizenPush.reminderTag(msg(NotizenPush.TOPIC_REMINDERS, """{"notizen":{"kind":"reminder","taskId":41,"tag":"task:41:x; drop"}}""")))
        assertNull(NotizenPush.reminderTag(msg(NotizenPush.TOPIC_REMINDERS, null)))
    }
}
