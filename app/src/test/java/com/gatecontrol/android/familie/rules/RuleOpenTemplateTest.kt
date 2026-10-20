package com.gatecontrol.android.familie.rules

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek

/** Familie 1.11: open reminders of the family, the holiday mode, the „!“ on widgets and the templates. */
class RuleOpenTemplateTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val answer = """{"rules":[],"log":[],"can":{"house":true,"personal":true},
        "open":[{"ruleId":4,"title":"Auto laden","key":"sched:2026-10-19","state":"snoozed","since":"2026-10-19T16:00:00Z",
                 "snoozeUntil":"2026-10-19T17:00:00Z","userId":3,"actionIds":["gatecontrol-skoda/car1:charge"]},
                {"ruleId":5,"title":"Müll","key":"sched:2026-10-19","state":"open","since":"2026-10-19T15:00:00Z","snoozeUntil":null,"userId":4,"actionIds":["gatecontrol-smarthome/tonne"]}],
        "vacation":{"on":true,"until":"2026-10-24","since":"2026-10-18T08:00:00.000Z","paused":5}}"""

    @Test
    fun `open reminders carry the person and the snooze end, the holiday mode`() {
        val p = RuleJson.payload(json(answer))!!
        val anna = p.open.first()
        assertEquals(3, anna.userId)
        assertEquals("2026-10-19T17:00:00Z", anna.snoozeUntil)
        assertTrue(anna.isMine(3))
        assertFalse(anna.isMine(4))
        assertTrue(RuleOpen(1, "x", "k", false, null, emptyList()).isMine(9), "older plugins: always the caller's own")
        assertEquals(RuleVacation(on = true, until = "2026-10-24", since = "2026-10-18T08:00:00.000Z", paused = 5), p.vacation)
        assertNull(RuleJson.payload(json("""{"rules":[],"can":{}}"""))!!.vacation, "before 1.11: no holiday mode")
        assertNull(RuleJson.vacation(json("""{"on":true,"until":"24.10.2026","paused":-2}""")).until)
        assertEquals(0, RuleJson.vacation(json("""{"on":false,"paused":-2}""")).paused)
        val s = RuleLogic.summary(p)
        assertEquals(2, s.open)
        assertTrue(s.vacation)
        // one's own first, then by time
        assertEquals(listOf(5, 4), RuleLogic.openSorted(p.open, 4).map { it.ruleId })
        assertEquals(listOf(4, 5), RuleLogic.openSorted(p.open, 3).map { it.ruleId })
    }

    @Test
    fun `only one's own reminders mark a widget`() {
        val p = RuleJson.payload(json(answer))!!
        assertEquals(setOf("gatecontrol-skoda/car1:charge"), RuleOpenStore.alertActions(p.open, 3))
        assertEquals(setOf("gatecontrol-smarthome/tonne"), RuleOpenStore.alertActions(p.open, 4))
        assertEquals(emptySet<String>(), RuleOpenStore.alertActions(p.open, 2))
    }

    @Test
    fun `a set reminder marks the widgets of its devices - active reminders, mine or the house's`() {
        val car = "gatecontrol-skoda/car1:climate"
        fun reminder(id: Int, owner: Int?, scope: String = RuleScope.PERSONAL, active: Boolean = true, device: String = car) = Rule(
            id = id, title = "r$id", scope = scope, active = active, ownerId = owner, kind = RuleLogic.KIND_REMINDER,
            trigger = RuleTrigger(RuleTriggerType.SCHEDULE, weekdays = listOf(7), time = "18:00"),
            conditions = RuleConditions(device = listOf(DeviceCondition(device, "value", RuleOp.LT, 50.0))),
            actions = listOf(RuleAction(RuleActionType.NOTIFY, buttons = listOf(RuleButton.quick("gatecontrol-skoda/car1:charge"), RuleButton.DONE))),
        )
        assertEquals(setOf(car, "gatecontrol-skoda/car1:charge"), RuleOpenStore.plannedActions(listOf(reminder(1, 3)), 3))
        assertEquals(emptySet<String>(), RuleOpenStore.plannedActions(listOf(reminder(1, 4)), 3), "Lena's own reminder")
        assertEquals(emptySet<String>(), RuleOpenStore.plannedActions(listOf(reminder(1, 3, active = false)), 3), "paused")
        assertTrue(car in RuleOpenStore.plannedActions(listOf(reminder(1, 4, scope = RuleScope.HOUSE)), 3), "a house reminder")
        val presence = reminder(2, 3, scope = RuleScope.HOUSE).copy(kind = RuleLogic.KIND_PRESENCE, trigger = RuleTrigger(RuleTriggerType.ALL_AWAY))
        assertEquals(emptySet<String>(), RuleOpenStore.plannedActions(listOf(presence), 3), "not a reminder")
    }

    @Test
    fun `bodies of the holiday mode, done and a new place`() {
        assertEquals("""{"on":true,"until":"2026-10-24"}""", RuleJson.vacationBody(true, "2026-10-24").toString())
        assertEquals("""{"on":false,"until":null}""", RuleJson.vacationBody(false, "2026-10-24").toString())
        assertEquals("""{"key":"sched:2026-10-19"}""", RuleJson.openBody("sched:2026-10-19").toString())
        assertEquals("""{"name":"Schule","lat":52.5,"lon":13.4,"radius":200}""", RuleJson.placeBody(" Schule ", 52.5, 13.4, 200).toString())
        assertEquals(RuleOptionPlace(9, "Schule"), RuleJson.place(json("""{"id":9,"name":"Schule","lat":1,"lon":2}""")))
    }

    private val options = RuleOptions(
        triggers = RuleTriggerType.KNOWN,
        devices = listOf(
            RuleOptionDevice("gatecontrol-smarthome/flur", "Flur", null, "toggle", emptyList()),
            RuleOptionDevice("gatecontrol-skoda/car1:climate", "Elroq", null, null, listOf(RuleOptionField("value", "Ladestand", null, "32 %"))),
        ),
        actions = listOf(
            RuleOptionAction("gatecontrol-skoda/car1:climate", "Klimatisierung", null, null, "toggle", deviceId = "car1"),
            RuleOptionAction("gatecontrol-skoda/car1:charge", "Laden starten", null, null, "button", deviceId = "car1"),
            RuleOptionAction("gatecontrol-smarthome/flur", "Flurlicht", null, null, "toggle"),
            RuleOptionAction("gatecontrol-raumklima/split", "PortaSplit", "Klima", null, "toggle"),
            RuleOptionAction("gatecontrol-door/haustuer", "Haustür", null, null, "toggle"),
        ),
        places = listOf(RuleOptionPlace(1, "Sportverein"), RuleOptionPlace(2, "Grundschule Nord")),
        people = listOf(RuleOptionPerson(3, "Anna"), RuleOptionPerson(4, "Lena", child = true)),
        calendars = listOf(RuleOptionCalendar("alltag", "Alltag"), RuleOptionCalendar("alltag:7", "Müllabfuhr Berlin")),
    )

    @Test
    fun `templates only where their devices and triggers are`() {
        val parent = RuleCan(house = true, personal = true)
        assertEquals(RuleTemplate.entries.toList(), RuleLogic.templates(options, parent))
        val child = RuleCan(house = false, personal = true)
        assertEquals(listOf(RuleTemplate.TRASH, RuleTemplate.CAR_CHARGE, RuleTemplate.SCHOOL_CANCELLED), RuleLogic.templates(options, child))
        val bare = RuleOptions(triggers = listOf(RuleTriggerType.SCHEDULE))
        assertEquals(listOf(RuleTemplate.TRASH), RuleLogic.templates(bare, parent))
        val noSchool = options.copy(triggers = RuleTriggerType.ALL)
        assertFalse(RuleTemplate.SCHOOL_CANCELLED in RuleLogic.templates(noSchool, parent), "without the Schule plugin")
        assertFalse(RuleTemplate.SCHOOL_CANCELLED in RuleLogic.templates(noSchool, child))
        assertFalse(RuleTemplate.SCHOOL_CANCELLED in RuleLogic.templates(RuleOptions(), parent), "an older plugin never offers it")
        assertEquals(emptyList<RuleTemplate>(), RuleLogic.templates(options, RuleCan()))
        assertEquals(RuleTemplate.SCHOOL, RuleTemplate.of("school"))
        assertEquals(RuleTemplate.SCHOOL_CANCELLED, RuleTemplate.of("school_cancelled"))
        assertNull(RuleTemplate.of("nope"))
    }

    @Test
    fun `template rules are complete and use what is there`() {
        val can = RuleCan(house = true, personal = true)
        val trash = RuleLogic.fromTemplate(RuleTemplate.TRASH, options, can, "Müll rausbringen", DayOfWeek.WEDNESDAY)
        assertEquals(RuleScope.PERSONAL, trash.scope)
        assertEquals(CalendarCondition(1, listOf("alltag:7"), ""), trash.conditions.calendar, "the waste calendar, tomorrow")
        assertEquals((1..7).toList(), trash.trigger.weekdays)
        assertEquals(listOf(RuleButton.SNOOZE, RuleButton.DONE), trash.actions.single().buttons)
        assertTrue(RuleLogic.valid(trash))
        val plain = RuleLogic.fromTemplate(RuleTemplate.TRASH, RuleOptions(), can, "Müll", DayOfWeek.WEDNESDAY)
        assertEquals(listOf(3), plain.trigger.weekdays, "no waste calendar: weekly on today")
        assertNull(plain.conditions.calendar)

        val car = RuleLogic.fromTemplate(RuleTemplate.CAR_CHARGE, options, can, "Auto laden", DayOfWeek.WEDNESDAY)
        assertEquals(listOf(DeviceCondition("gatecontrol-skoda/car1:climate", "value", RuleOp.LT, 50.0)), car.conditions.device)
        assertEquals(listOf(RuleButton.quick("gatecontrol-skoda/car1:charge"), RuleButton.SNOOZE, RuleButton.DONE), car.actions.single().buttons)
        assertTrue(RuleLogic.valid(car))

        val away = RuleLogic.fromTemplate(RuleTemplate.ALL_AWAY_OFF, options, can, "Alle weg", DayOfWeek.WEDNESDAY)
        assertEquals(RuleScope.HOUSE, away.scope)
        assertEquals(RuleTrigger(RuleTriggerType.ALL_AWAY, graceMinutes = 10), away.trigger)
        assertEquals(listOf("gatecontrol-smarthome/flur", "gatecontrol-raumklima/split"), away.actions.map { it.actionId }, "lights and climate, not the door")
        assertTrue(away.actions.all { it.state == "off" })
        val awayBare = RuleLogic.fromTemplate(RuleTemplate.ALL_AWAY_OFF, RuleOptions(), can, "Alle weg", DayOfWeek.WEDNESDAY)
        assertEquals(RuleActionType.NOTIFY, awayBare.actions.single().type, "nothing to switch: a message")

        val alone = RuleLogic.fromTemplate(RuleTemplate.CHILD_ALONE, options, can, "Kind allein", DayOfWeek.WEDNESDAY)
        assertEquals(RuleRecipients.PARENTS, alone.actions.single().to!!.keyword)
        assertTrue(RuleLogic.valid(alone))

        val school = RuleLogic.fromTemplate(RuleTemplate.SCHOOL, options, can, "Schule", DayOfWeek.WEDNESDAY)
        assertEquals(RuleTrigger(RuleTriggerType.PLACE_ENTER, userId = 4, placeId = 2, placeName = "Grundschule Nord"), school.trigger)
        assertTrue(RuleLogic.valid(school))
        val noPlace = RuleLogic.fromTemplate(RuleTemplate.SCHOOL, options.copy(places = emptyList()), can, "Schule", DayOfWeek.WEDNESDAY)
        assertFalse(RuleLogic.valid(noPlace), "a place still to pick or create")

        val cancelled = RuleLogic.fromTemplate(RuleTemplate.SCHOOL_CANCELLED, options, can, "Stunde fällt aus", DayOfWeek.WEDNESDAY, myUserId = 3)
        assertEquals(RuleScope.HOUSE, cancelled.scope)
        assertEquals(RuleTrigger(RuleTriggerType.SCHOOL_CHANGE, userId = null, kinds = listOf(RuleSchoolKind.CANCELLED), days = RuleSchoolDays.ANY), cancelled.trigger, "any child")
        assertEquals(RuleAction(RuleActionType.NOTIFY, to = RuleRecipients(RuleRecipients.PARENTS), text = "{event}"), cancelled.actions.single(), "no buttons")
        assertTrue(RuleLogic.valid(cancelled))
        val own = RuleLogic.fromTemplate(RuleTemplate.SCHOOL_CANCELLED, options, RuleCan(house = false, personal = true), "Stunde fällt aus", DayOfWeek.WEDNESDAY, myUserId = 4)
        assertEquals(RuleScope.PERSONAL, own.scope)
        assertEquals(4, own.trigger.userId, "a child's own rule: only itself")
        assertEquals(listOf(RuleSchoolKind.CANCELLED), own.trigger.kinds)
        assertEquals(RuleRecipients(RuleRecipients.ME), own.actions.single().to)
        assertEquals("{event}", own.actions.single().text)
        assertTrue(own.actions.single().buttons.isEmpty())
        assertTrue(RuleLogic.valid(own))
        assertEquals(own, RuleLogic.forScope(own, 4))
    }
}
