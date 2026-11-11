package com.gatecontrol.android.familie.rules

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The rule action „Internet zu Hause“ (Familie 1.20): reading, changing and sending it back. */
class RuleInternetTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private fun rule(actions: String): Rule = RuleJson.rule(
        json("""{"id":12,"title":"Abendessen","scope":"house","trigger":{"type":"schedule","weekdays":[1,2,3,4,5,6,7],"time":"18:00"},"ownerId":2,"ownerName":"Anna","actions":$actions}"""),
    )!!

    private fun sent(r: Rule): List<JsonObject> = RuleJson.body(r).getAsJsonArray("actions").map { it.asJsonObject }

    @Test
    fun `pause for minutes, until a time and resume`() {
        val r = rule(
            """[{"type":"internet","op":"pause","userId":5,"name":"Tim","minutes":30,"until":null,"available":true,"problem":null},
                {"type":"internet","op":"pause","userId":4,"name":"Lena","minutes":null,"until":"18:30","available":true,"problem":null},
                {"type":"internet","op":"resume","userId":6,"name":"Mia","minutes":null,"until":null,"available":true,"problem":null},
                {"type":"notify","to":[4,5],"text":"Abendessen ist fertig"}]""",
        )
        val (tim, lena, mia) = r.actions
        assertTrue(tim.isInternet)
        assertEquals(listOf(RuleInternetOp.PAUSE, 5, "Tim", 30, null), listOf(tim.op, tim.userId, tim.title, tim.minutes, tim.until))
        assertEquals(listOf<Any?>(null, "18:30"), listOf(lena.minutes, lena.until))
        assertEquals(RuleInternetOp.RESUME, mia.op)
        assertTrue(RuleLogic.valid(r))

        // Saved untouched: what the plugin takes, without the fields only answers carry.
        val out = sent(r)
        assertEquals(json("""{"type":"internet","op":"pause","userId":5,"minutes":30}"""), out[0])
        assertEquals(json("""{"type":"internet","op":"pause","userId":4,"until":"18:30"}"""), out[1])
        assertEquals(json("""{"type":"internet","op":"resume","userId":6}"""), out[2])
        assertEquals("notify", out[3].get("type").asString)
    }

    @Test
    fun `fields the app does not know go back unchanged`() {
        val r = rule("""[{"type":"internet","op":"pause","userId":5,"name":"Tim","minutes":30,"until":null,"available":true,"problem":null,"scope":"devices","only":[41,42],"note":{"x":1}}]""")
        assertEquals(json("""{"type":"internet","op":"pause","userId":5,"minutes":30,"scope":"devices","only":[41,42],"note":{"x":1}}"""), sent(r).single())

        // Changed in the dialog: another child, until a time — the unknown fields stay.
        val edited = RuleLogic.internetAction(r.actions[0], userId = 4, name = "Lena", pause = true, minutes = 30, until = "19:00")
        assertEquals(
            json("""{"type":"internet","op":"pause","userId":4,"until":"19:00","scope":"devices","only":[41,42],"note":{"x":1}}"""),
            sent(r.copy(actions = listOf(edited))).single(),
        )
        // Turned into "fortsetzen": no duration left over.
        val resume = RuleLogic.internetAction(r.actions[0], userId = 5, name = "Tim", pause = false, minutes = 30, until = null)
        assertEquals(json("""{"type":"internet","op":"resume","userId":5,"scope":"devices","only":[41,42],"note":{"x":1}}"""), sent(r.copy(actions = listOf(resume))).single())
        // The rule read from the server is not changed by building the body.
        assertEquals(30, r.actions[0].raw!!.get("minutes").asInt)
        assertTrue(r.actions[0].raw!!.has("name"))
    }

    @Test
    fun `a newer op is kept as it came and cannot be edited`() {
        val r = rule("""[{"type":"internet","op":"limit","userId":5,"name":"Tim","mbit":2,"available":true,"problem":null}]""")
        val a = r.actions.single()
        assertEquals(RuleActionType.INTERNET, a.type)
        assertFalse(a.isInternet)
        assertEquals(json("""{"type":"internet","op":"limit","userId":5,"mbit":2}"""), sent(r).single())
        assertTrue(RuleLogic.valid(r), "the rule can still be saved (title, trigger and the other parts)")
    }

    @Test
    fun `a new action from the dialog`() {
        val fresh = RuleLogic.internetAction(null, userId = 5, name = "Tim", pause = true, minutes = 120, until = null)
        assertTrue(fresh.isInternet && RuleLogic.internetComplete(fresh))
        assertEquals(json("""{"type":"internet","op":"pause","userId":5,"minutes":120}"""), sent(rule("[]").copy(actions = listOf(fresh))).single())
        val until = RuleLogic.internetAction(null, 5, "Tim", pause = true, minutes = 0, until = "16:00")
        assertEquals(json("""{"type":"internet","op":"pause","userId":5,"until":"16:00"}"""), sent(rule("[]").copy(actions = listOf(until))).single())
        // More than a day is not possible in a rule.
        assertEquals(1440, RuleLogic.internetAction(null, 5, "Tim", pause = true, minutes = 5000, until = null).minutes)
        // A pause without a duration is not complete: the rule is not saved.
        val broken = RuleAction(RuleActionType.INTERNET, op = RuleInternetOp.PAUSE, userId = 5)
        assertFalse(RuleLogic.internetComplete(broken))
        assertFalse(RuleLogic.valid(rule("[]").copy(actions = listOf(broken))))
    }

    @Test
    fun `an action that will not run says why`() {
        val r = rule(
            """[{"type":"internet","op":"pause","userId":9,"name":"Jonas","minutes":30,"until":null,"available":false,"problem":"not_found"},
                {"type":"internet","op":"pause","userId":5,"name":"Tim","minutes":2000,"until":"25:00","available":false,"problem":"not_allowed"}]""",
        )
        assertEquals(false, r.actions[0].available)
        assertEquals(RuleInternetProblem.NOT_FOUND, r.actions[0].problem)
        assertEquals(RuleInternetProblem.NOT_ALLOWED, r.actions[1].problem)
        // Values out of range are not shown as if they were fine.
        assertNull(r.actions[1].minutes)
        assertNull(r.actions[1].until)
    }

    @Test
    fun `the editor offers the action to parents on servers that have it`() {
        val parent = RuleJson.options(json("""{"internet":{"available":true,"reason":null,"children":[{"userId":4,"name":"Lena","colorHex":"#EAB308"},{"userId":5,"name":"Tim"},{"userId":5,"name":"again"},{"name":"no id"}]}}"""))
        assertTrue(parent.internet.usable)
        assertEquals(listOf(RuleOptionChild(4, "Lena"), RuleOptionChild(5, "Tim")), parent.internet.children)

        val child = RuleJson.options(json("""{"internet":{"available":false,"reason":"parents_only","children":[]}}"""))
        assertFalse(child.internet.usable)
        assertEquals("parents_only", child.internet.reason)
        assertFalse(RuleJson.options(json("""{"internet":{"available":false,"reason":"unavailable","children":[]}}""")).internet.usable)
        // A plugin before 1.20 has no `internet`; parents without a minor child have nobody to pick.
        assertFalse(RuleJson.options(json("""{"triggers":["schedule"]}""")).internet.usable)
        assertFalse(RuleJson.options(json("""{"internet":{"available":true,"children":[]}}""")).internet.usable)
    }

    @Test
    fun `the step an error names`() {
        assertEquals(2, RuleLogic.actionIndex("actions.2"))
        assertEquals(0, RuleLogic.actionIndex("actions.0.userId"))
        assertNull(RuleLogic.actionIndex("trigger.userId"))
        assertNull(RuleLogic.actionIndex(null))
    }
}
