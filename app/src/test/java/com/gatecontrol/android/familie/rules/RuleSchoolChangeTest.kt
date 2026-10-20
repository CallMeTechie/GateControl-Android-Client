package com.gatecontrol.android.familie.rules

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Familie 1.12: the trigger „Stunde fällt aus“ (`school_change`) of the Schule plugin's timetable. */
class RuleSchoolChangeTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private fun ruleJson(trigger: String) = json(
        """{"id":9,"title":"Ausfall","scope":"house","active":true,"trigger":$trigger,
           "actions":[{"type":"notify","to":"parents","text":"{event}"}]}""",
    )

    @Test
    fun `a school_change trigger parses and goes back exactly as the contract says`() {
        val r = RuleJson.rule(ruleJson("""{"type":"school_change","userId":12,"kinds":["cancelled","extra"],"days":"tomorrow"}"""))!!
        assertEquals(RuleTrigger(RuleTriggerType.SCHOOL_CHANGE, userId = 12, kinds = listOf("cancelled", "extra"), days = "tomorrow"), r.trigger)
        val body = RuleJson.body(r)
        assertEquals("""{"type":"school_change","userId":12,"kinds":["cancelled","extra"],"days":"tomorrow"}""", body["trigger"].toString())
        assertEquals(r, RuleJson.rule(body.apply { addProperty("id", 9) }))

        val any = RuleJson.rule(ruleJson("""{"type":"school_change","userId":null,"kinds":["restored","changed"],"days":"any"}"""))!!
        assertNull(any.trigger.userId, "null: any child")
        assertEquals(listOf("changed", "restored"), any.trigger.kinds, "in the order of the contract")
        assertEquals("""{"type":"school_change","userId":null,"kinds":["changed","restored"],"days":"any"}""", RuleJson.body(any)["trigger"].toString())
    }

    @Test
    fun `a school_change trigger is read defensively`() {
        val odd = RuleJson.rule(ruleJson("""{"type":"school_change","kinds":["cancelled","exam",3,"cancelled"],"days":"yesterday"}"""))!!.trigger
        assertEquals(listOf(RuleSchoolKind.CANCELLED), odd.kinds, "unknown kinds dropped")
        assertEquals(RuleSchoolDays.ANY, odd.days, "unknown days: any")
        assertNull(odd.userId)
        val bare = RuleJson.rule(ruleJson("""{"type":"school_change"}"""))!!.trigger
        assertEquals(listOf(RuleSchoolKind.CANCELLED), bare.kinds, "none: cancelled")
        assertEquals(RuleSchoolDays.ANY, bare.days)
        val unknownOnly = RuleJson.rule(ruleJson("""{"type":"school_change","kinds":["exam"],"days":"today"}"""))!!.trigger
        assertEquals(listOf(RuleSchoolKind.CANCELLED), unknownOnly.kinds)
        assertEquals(RuleSchoolDays.TODAY, unknownOnly.days)
        // other triggers carry neither kinds nor days
        val place = RuleJson.rule(ruleJson("""{"type":"place_enter","placeId":2,"kinds":["cancelled"],"days":"today"}"""))!!.trigger
        assertEquals(RuleTrigger(RuleTriggerType.PLACE_ENTER, placeId = 2), place)
        assertFalse(RuleJson.body(Rule(id = null, title = "x", trigger = place))["trigger"].asJsonObject.has("kinds"))
    }

    @Test
    fun `options carry the timetable's children only with the Schule plugin`() {
        val o = RuleJson.options(
            json(
                """{"triggers":["schedule","school_change"],
                   "school":{"students":[{"userId":12,"name":"Lena"},{"userId":12,"name":"dup"},{"name":"no id"},{"userId":14,"name":" "}]}}""",
            ),
        )
        assertEquals(listOf("schedule", "school_change"), o.triggers)
        assertEquals(listOf(RuleOptionStudent(12, "Lena"), RuleOptionStudent(14, "#14")), o.school!!.students)
        val older = RuleJson.options(json("""{"triggers":["schedule"]}"""))
        assertNull(older.school)
        assertFalse(RuleTriggerType.SCHOOL_CHANGE in RuleJson.options(json("{}")).triggers, "an older plugin never offers it")
        assertTrue(RuleTriggerType.SCHOOL_CHANGE in RuleTriggerType.KNOWN)
    }

    @Test
    fun `at least one kind is needed`() {
        val r = Rule(
            id = null,
            title = "Ausfall",
            trigger = RuleLogic.schoolTrigger(childOnly = false, myUserId = 3),
            actions = listOf(RuleAction(RuleActionType.NOTIFY, to = RuleRecipients(RuleRecipients.PARENTS), text = "{event}")),
        )
        assertNull(r.trigger.userId, "parents: any child")
        assertTrue(RuleLogic.valid(r))
        val none = r.copy(trigger = RuleLogic.toggleSchoolKind(r.trigger, RuleSchoolKind.CANCELLED))
        assertEquals(emptyList<String>(), none.trigger.kinds)
        assertFalse(RuleLogic.valid(none))
        val two = RuleLogic.toggleSchoolKind(RuleLogic.toggleSchoolKind(none.trigger, RuleSchoolKind.RESTORED), RuleSchoolKind.CHANGED)
        assertEquals(listOf(RuleSchoolKind.CHANGED, RuleSchoolKind.RESTORED), two.kinds)
        assertEquals(7, RuleLogic.schoolTrigger(childOnly = true, myUserId = 7).userId, "a child: only itself")
    }

    @Test
    fun `the summary names the one kind and the day`() {
        val t = RuleLogic.schoolTrigger(childOnly = false, myUserId = null)
        assertEquals(RuleLogic.SchoolSummary(RuleSchoolKind.CANCELLED, null), RuleLogic.schoolSummary(t), "eine Stunde fällt aus, any day")
        assertEquals(RuleLogic.SchoolSummary(RuleSchoolKind.CANCELLED, RuleSchoolDays.TOMORROW), RuleLogic.schoolSummary(t.copy(days = RuleSchoolDays.TOMORROW)))
        assertEquals(
            RuleLogic.SchoolSummary(null, RuleSchoolDays.TODAY),
            RuleLogic.schoolSummary(t.copy(kinds = listOf(RuleSchoolKind.CANCELLED, RuleSchoolKind.CHANGED), days = RuleSchoolDays.TODAY)),
            "several kinds: der Stundenplan ändert sich",
        )
    }

    @Test
    fun `a child's own rule has its child fixed`() {
        val own = Rule(id = null, title = "x", scope = RuleScope.PERSONAL, trigger = RuleLogic.schoolTrigger(true, 4))
        assertTrue(RuleLogic.schoolChildFixed(own, canHouse = false))
        assertFalse(RuleLogic.schoolChildFixed(own, canHouse = true), "a parent's own rule may pick")
        assertFalse(RuleLogic.schoolChildFixed(own.copy(scope = RuleScope.HOUSE), canHouse = true))
    }
}
