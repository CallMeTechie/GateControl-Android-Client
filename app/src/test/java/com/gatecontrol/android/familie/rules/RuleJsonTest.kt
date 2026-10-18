package com.gatecontrol.android.familie.rules

import com.gatecontrol.android.familie.FamilieJson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek

/** Familie rules and reminders (B1): parsers, bodies and the editor's decisions. */
class RuleJsonTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val reminderJson = """{"rules":[{"id":4,"title":"Auto laden","scope":"personal","active":true,
        "trigger":{"type":"schedule","weekdays":[7,7,9],"time":"18:00"},
        "conditions":{"device":[{"actionId":"gatecontrol-skoda/charge","field":"reading:soc","op":"<","value":50},{"actionId":"bad id","op":"<","value":1}],
          "calendar":{"dayOffset":1,"sources":["alltag:3","familie"],"match":"Arbeit"}},
        "actions":[{"type":"notify","to":[3],"facts":true,"buttons":["quick_action:gatecontrol-skoda/charge","snooze_1h","done"]}],
        "lastRunAt":"2026-10-19T16:00:00Z","lastResult":{"at":"2026-10-19T16:00:00Z","ok":false},"ownerId":3}],
      "log":[{"ruleId":4,"ruleName":"Auto laden","at":"2026-10-19T16:00:00Z","ok":true,"fired":false,"text":"Auto laden · nicht ausgelöst (71 %)"}],
      "can":{"house":false,"personal":true}}"""

    @Test
    fun `a reminder with schedule, device and calendar`() {
        val p = RuleJson.payload(json(reminderJson))!!
        val r = p.rules.single()
        assertEquals("Auto laden", r.title)
        assertEquals(RuleScope.PERSONAL, r.scope)
        assertEquals(RuleTrigger(RuleTriggerType.SCHEDULE, weekdays = listOf(7), time = "18:00"), r.trigger)
        assertEquals(listOf(DeviceCondition("gatecontrol-skoda/charge", "reading:soc", "<", 50.0)), r.conditions.device)
        assertEquals(CalendarCondition(1, listOf("alltag:3", "familie"), "Arbeit"), r.conditions.calendar)
        val n = r.actions.single()
        assertTrue(n.facts)
        assertEquals(listOf(3), n.to!!.userIds)
        assertEquals(3, n.buttons.size)
        assertEquals(false, r.lastOk)
        assertFalse(p.can.house)
        assertTrue(p.can.personal)
        assertFalse(p.log.single().fired)
        assertEquals("Auto laden · nicht ausgelöst (71 %)", p.log.single().text)
    }

    @Test
    fun `an older presence rule - name, trigger as a word, fields beside it`() {
        val r = RuleJson.rule(
            json(
                """{"id":2,"name":"Lena an der Schule","trigger":"place_enter","userId":5,"placeId":8,"placeName":"Schule","graceMinutes":10,
                   "conditions":{"time":{"from":"07:00","to":"09:00"},"afterSunset":false},
                   "actions":[{"type":"notify","to":"parents"},{"type":"quick_action","plugin":"gatecontrol-smarthome","id":"flur","state":"on","available":false},
                              {"type":"messenger","to":"family","text":"{name} ist da"},{"type":"teleport","text":"x"}],"active":false}""",
            ),
        )!!
        assertEquals("Lena an der Schule", r.title)
        assertEquals(RuleScope.HOUSE, r.scope)
        assertFalse(r.active)
        assertEquals(RuleTriggerType.PLACE_ENTER, r.trigger.type)
        assertEquals(5, r.trigger.userId)
        assertEquals(8, r.trigger.placeId)
        assertEquals("Schule", r.trigger.placeName)
        assertEquals(RuleTimeWindow("07:00", "09:00"), r.conditions.time)
        assertEquals(RuleRecipients.PARENTS, r.actions[0].to!!.keyword)
        assertEquals("gatecontrol-smarthome/flur", r.actions[1].actionId)
        assertEquals(false, r.actions[1].available)
        assertEquals(RuleRecipients.FAMILY, r.actions[2].to!!.keyword)
        assertEquals("teleport", r.actions[3].type, "a newer action type is kept")
        assertNull(r.lastOk)
    }

    @Test
    fun `malformed rules and answers are dropped, not thrown`() {
        assertNull(RuleJson.payload(json("""{"ok":true}""")))
        val p = RuleJson.payload(json("""{"rules":[{"title":"no id"},{"id":1},{"id":2,"trigger":{"type":"first_home"}},"x"]}"""))!!
        assertEquals(listOf(2), p.rules.map { it.id })
        assertFalse(p.can.any)
        assertNull(RuleJson.testResult(json("""{"details":[]}""")))
    }

    @Test
    fun `body carries the whole rule and parses back the same`() {
        val r = RuleJson.payload(json(reminderJson))!!.rules.single().copy(lastRunAt = null, lastOk = null, ownerId = null)
        val body = RuleJson.body(r)
        assertEquals("Auto laden", body["title"].asString)
        assertFalse(body.has("name"))
        assertFalse(RuleJson.body(r, withScope = false).has("scope"), "a change keeps the scope")
        assertEquals("schedule", body["trigger"].asJsonObject["type"].asString)
        assertTrue(body["conditions"].asJsonObject["time"].isJsonNull)
        assertEquals(r.copy(id = 4), RuleJson.rule(body.apply { addProperty("id", 4) }))
    }

    @Test
    fun `a place trigger sends anyone as null, a button state as null`() {
        val r = Rule(
            id = null,
            title = "Ankommen",
            trigger = RuleTrigger(RuleTriggerType.PLACE_EXIT, placeId = 3),
            actions = listOf(RuleAction(RuleActionType.QUICK_ACTION, plugin = "gatecontrol-door", id = "garage")),
        )
        val body = RuleJson.body(r)
        assertTrue(body["trigger"].asJsonObject["userId"].isJsonNull)
        val qa = body["actions"].asJsonArray[0].asJsonObject
        assertEquals("gatecontrol-door/garage", qa["actionId"].asString, "Familie 1.9: the quick action as actionId")
        assertFalse(qa.has("state"), "a button has no target state")
    }

    @Test
    fun `options - devices with fields, calendars, places, people`() {
        val o = RuleJson.options(
            json(
                """{"triggers":["schedule",{"type":"first_home"},"bad trigger!"],
                   "devices":[{"actionId":"gatecontrol-skoda/charge","label":"Elroq","kind":"toggle","fields":[{"id":"reading:soc","label":"Ladestand","unit":"%","current":"32 %"},{"id":"nope"}]},
                              {"actionId":"no-slash","label":"x"}],
                   "calendars":[{"source":"alltag:3","label":"Arbeit"}],"places":[{"id":8,"name":"Schule"}],
                   "people":[{"userId":5,"name":"Lena","role":"child"}],"can":{"house":true,"personal":true},"messenger":{"available":false}}""",
            ),
        )
        assertEquals(listOf("schedule", "first_home"), o.triggers)
        val d = o.device("gatecontrol-skoda/charge")!!
        assertEquals("gatecontrol-skoda", d.plugin)
        assertEquals("charge", d.id)
        assertEquals(listOf("reading:soc"), d.fields.map { it.id })
        assertEquals(1, o.devices.size)
        assertTrue(o.people.single().child)
        assertTrue(o.can.house)
        assertFalse(o.messenger)
        assertEquals(RuleTriggerType.ALL, RuleJson.options(json("{}")).triggers, "an older plugin: every trigger")
    }

    @Test
    fun `dry run`() {
        val t = RuleJson.testResult(json("""{"wouldFire":true,"details":[{"kind":"device","ok":true,"text":"32 %"},{"kind":"calendar","ok":true,"text":"Mo 07:30 „Arbeit“"},{"text":""}]}"""))!!
        assertTrue(t.wouldFire)
        assertEquals(listOf("32 %", "Mo 07:30 „Arbeit“"), t.details.map { it.text })
    }

    // ─── logic ───────────────────────────────────────────────────────

    @Test
    fun `weekdays as ranges`() {
        val names = mapOf(1 to "Mo", 2 to "Di", 3 to "Mi", 4 to "Do", 5 to "Fr", 6 to "Sa", 7 to "So")
        assertEquals("Mo–Fr", RuleLogic.weekdays(listOf(5, 1, 2, 3, 4), { names.getValue(it) }, "täglich"))
        assertEquals("Sa, So", RuleLogic.weekdays(listOf(6, 7), { names.getValue(it) }, "täglich"))
        assertEquals("Mo, Mi–Fr, So", RuleLogic.weekdays(listOf(1, 3, 4, 5, 7), { names.getValue(it) }, "täglich"))
        assertEquals("täglich", RuleLogic.weekdays((1..7).toList(), { names.getValue(it) }, "täglich"))
    }

    @Test
    fun `numbers in widget values`() {
        assertEquals(32.0, RuleLogic.number("32 %"))
        assertEquals(10.5, RuleLogic.number("aus · 10,5°"))
        assertEquals(-3.0, RuleLogic.number("-3 °C"))
        assertNull(RuleLogic.number("an"))
        assertEquals("50", RuleLogic.format(50.0))
        assertEquals("10,5", RuleLogic.format(10.5))
    }

    @Test
    fun `filters`() {
        val p = RuleJson.payload(json(reminderJson))!!
        val presence = Rule(id = 9, title = "Erster kommt heim", trigger = RuleTrigger(RuleTriggerType.FIRST_HOME), actions = p.rules[0].actions)
        val place = presence.copy(id = 10, trigger = RuleTrigger(RuleTriggerType.PLACE_ENTER, placeId = 1), active = false)
        val all = p.rules + presence + place
        assertEquals(listOf(4), RuleLogic.filter(all, RuleFilter.REMINDERS).map { it.id })
        assertEquals(listOf(9), RuleLogic.filter(all, RuleFilter.PRESENCE).map { it.id })
        assertEquals(listOf(10), RuleLogic.filter(all, RuleFilter.PLACES).map { it.id })
        assertEquals(listOf(4, 9, 10), RuleLogic.filter(all, RuleFilter.ALL).map { it.id }, "active first, then by title")
        val s = RuleLogic.summary(p.copy(rules = all))
        assertEquals(2, s.active)
        assertEquals(1, s.paused)
        assertEquals("2026-10-19T16:00:00Z", s.lastRunAt)
    }

    @Test
    fun `a reminder for a device - personal, schedule, below 50 percent, its buttons`() {
        val device = RuleOptionDevice("gatecontrol-skoda/charge", "Elroq", null, "toggle", listOf(RuleOptionField("reading:soc", "Ladestand", "%", "32 %")))
        val r = RuleLogic.reminderFor(device, device.actionId, 3, DayOfWeek.SUNDAY)
        assertEquals("Elroq", r.title)
        assertEquals(RuleScope.PERSONAL, r.scope)
        assertEquals(listOf(7), r.trigger.weekdays)
        assertEquals("18:00", r.trigger.time)
        assertEquals(DeviceCondition("gatecontrol-skoda/charge", "reading:soc", RuleOp.LT, 50.0), r.conditions.device.single())
        val n = r.actions.single()
        assertEquals(RuleRecipients.ME, n.to!!.keyword)
        assertEquals("me", RuleJson.body(r)["actions"].asJsonArray[0].asJsonObject["to"].asString)
        assertEquals(listOf("quick_action:gatecontrol-skoda/charge", RuleButton.SNOOZE, RuleButton.DONE), n.buttons)
        assertTrue(RuleLogic.valid(r))
        // Without the device in the options (older plugin): still a reminder, no condition.
        val bare = RuleLogic.reminderFor(null, "gatecontrol-skoda/charge", 3, DayOfWeek.MONDAY)
        assertTrue(bare.conditions.device.isEmpty())
        assertFalse(RuleLogic.valid(bare), "it needs a title first")
    }

    @Test
    fun `validity of the trigger and the actions`() {
        val base = Rule(id = null, title = "T", trigger = RuleTrigger(RuleTriggerType.SCHEDULE, weekdays = listOf(1), time = "07:00"), actions = listOf(RuleAction(RuleActionType.NOTIFY)))
        assertTrue(RuleLogic.valid(base))
        assertFalse(RuleLogic.valid(base.copy(actions = emptyList())))
        assertFalse(RuleLogic.valid(base.copy(trigger = base.trigger.copy(weekdays = emptyList()))))
        assertFalse(RuleLogic.valid(base.copy(trigger = RuleTrigger(RuleTriggerType.PERSON_HOME))))
        assertTrue(RuleLogic.valid(base.copy(trigger = RuleTrigger(RuleTriggerType.PLACE_ENTER, placeId = 2))))
    }

    @Test
    fun `a personal rule reaches only its creator`() {
        val r = RuleLogic.newRule(personal = false, myUserId = 3).copy(title = "x")
        assertEquals(RuleRecipients.PARENTS, r.actions[0].to!!.keyword)
        val mine = RuleLogic.forScope(r.copy(scope = RuleScope.PERSONAL, actions = r.actions + RuleAction(RuleActionType.MESSENGER, to = RuleRecipients(RuleRecipients.FAMILY))), 3)
        assertTrue(mine.actions.all { it.to == RuleRecipients(RuleRecipients.ME) })
        assertEquals(r, RuleLogic.forScope(r, 3), "house rules stay as they are")
    }

    @Test
    fun `notify buttons keep their order and at most three`() {
        var a = RuleAction(RuleActionType.NOTIFY)
        a = RuleLogic.toggleButton(a, RuleButton.DONE, true)
        a = RuleLogic.toggleButton(a, RuleButton.quick("p/x"), true)
        a = RuleLogic.toggleButton(a, RuleButton.SNOOZE, true)
        assertEquals(listOf("quick_action:p/x", RuleButton.SNOOZE, RuleButton.DONE), a.buttons)
        assertEquals(listOf("quick_action:p/x", RuleButton.DONE), RuleLogic.toggleButton(a, RuleButton.SNOOZE, false).buttons)
    }

    @Test
    fun `the rules module comes from me`() {
        val p = FamilieJson.profile(json("""{"me":{"userId":3,"name":"Marc","role":"parent"},"modules":{"rules":true,"presence":false},"members":[]}"""))!!
        assertTrue(p.modules.rules)
        assertFalse(FamilieJson.profile(json("""{"me":{"userId":3,"name":"Marc","role":"parent"},"modules":{},"members":[]}"""))!!.modules.rules)
    }

    @Test
    fun `Familie 1-9 answer - kind, editable, actionId, skipped log, open reminders`() {
        val p = RuleJson.payload(
            json(
                """{"rules":[{"id":7,"title":"Flur","scope":"house","kind":"presence","editable":false,"pausable":false,
                   "trigger":{"type":"schedule","weekdays":[1],"time":"07:00"},
                   "conditions":{"device":[{"actionId":"gatecontrol-midea/klima","field":"value","op":">=","value":21,"label":"Innen","unit":"°C"}]},
                   "actions":[{"type":"quick_action","actionId":"gatecontrol-smarthome/flur","plugin":"x","id":"y","state":"on","kind":"toggle","verb":"Einschalten"}],
                   "lastResult":{"at":"2026-10-19T05:00:00Z","ok":true,"skipped":true}}],
                   "log":[{"id":1,"ruleId":7,"ruleName":"alt","ruleTitle":"Flur","at":"2026-10-19T05:00:00Z","ok":true,"skipped":true}],
                   "can":{"house":true,"personal":true},
                   "open":[{"ruleId":4,"title":"Auto laden","key":"sched:2026-10-19","state":"snoozed","since":"2026-10-19T16:00:00Z","actionIds":["gatecontrol-skoda/charge","bad"]}]}""",
            ),
        )!!
        val r = p.rules.single()
        assertEquals("presence", r.kind)
        assertFalse(r.editable)
        assertFalse(r.pausable)
        assertEquals("gatecontrol-smarthome/flur", r.actions.single().actionId, "actionId wins over plugin / id")
        assertEquals("toggle", r.actions.single().kind)
        assertEquals("°C", r.conditions.device.single().unit)
        // kind decides the filter, not the trigger.
        assertTrue(RuleLogic.matches(r, RuleFilter.PRESENCE))
        assertFalse(RuleLogic.matches(r, RuleFilter.REMINDERS))
        val log = p.log.single()
        assertEquals("Flur", log.ruleName)
        assertFalse(log.fired, "skipped: not fired")
        val open = p.open.single()
        assertTrue(open.snoozed)
        assertEquals(listOf("gatecontrol-skoda/charge"), open.actionIds)
    }

    @Test
    fun `Familie 1-9 options - unavailable triggers out, quick actions, features`() {
        val o = RuleJson.options(
            json(
                """{"triggers":[{"type":"schedule","group":"time","available":true},{"type":"place_enter","group":"place","available":false}],
                   "actions":[{"actionId":"gatecontrol-smarthome/flur","plugin":"gatecontrol-smarthome","id":"flur","pluginName":"Smart Home","title":"Flurlicht","kind":"toggle"}],
                   "actionsAvailable":true,"features":{"deviceValues":false,"buttons":true,"alltag":true}}""",
            ),
        )
        assertEquals(listOf("schedule"), o.triggers)
        assertEquals("Flurlicht", o.labelOf("gatecontrol-smarthome/flur"))
        assertEquals("toggle", o.action("gatecontrol-smarthome/flur")!!.kind)
        assertFalse(o.deviceValues)
        assertTrue(RuleJson.options(json("""{"actions":[{"actionId":"a/b","title":"x"}],"actionsAvailable":false}""")).actions.isEmpty())
    }

    @Test
    fun `dry run details may leave ok open`() {
        val t = RuleJson.testResult(json("""{"wouldFire":false,"details":[{"kind":"trigger","ok":null,"text":"So 18:00"},{"kind":"device","ok":false,"text":"71 %"}]}"""))!!
        assertNull(t.details[0].ok)
        assertEquals(false, t.details[1].ok)
    }
}
