package com.gatecontrol.android.notizen

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The quick entry and the repeat rules against the plugin's own vectors — copied unchanged from gatecontrol-plugins
 * `plugins/gatecontrol-notizen/test/vectors/{quickparse,repeat}.json` of gatecontrol-notizen **1.0.0** (commit
 * af53ac1 of main). Never edit them here: a new plugin version brings new files.
 */
class QuickParseVectorsTest {

    private fun res(name: String): JsonObject =
        JsonParser.parseString(requireNotNull(javaClass.classLoader!!.getResource("notizen/$name")).readText()).asJsonObject

    private val qv = res("quickparse.json")
    private val rv = res("repeat.json")

    private fun refs(a: JsonArray?): List<QuickParse.Ref> =
        a?.map { QuickParse.Ref(it.asJsonObject.get("id").asLong, it.asJsonObject.get("name").asString) }.orEmpty()

    private val ctxJson = qv.getAsJsonObject("ctx")
    private val ctx = QuickParse.Ctx(
        today = ctxJson.get("today").asString,
        now = ctxJson.get("now")?.asString,
        lists = refs(ctxJson.getAsJsonArray("lists")),
        people = refs(ctxJson.getAsJsonArray("people")),
    )

    private fun ref(r: QuickParse.Ref?): JsonElement = r?.let { JsonObject().apply { addProperty("id", it.id); addProperty("name", it.name) } } ?: JsonNull.INSTANCE

    private fun str(s: String?): JsonElement = s?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE

    private fun asJson(p: QuickParse.Parsed): JsonObject = JsonObject().apply {
        add("title", str(p.title))
        add("date", str(p.date))
        add("time", str(p.time))
        add("remind", p.remind?.let { JsonObject().apply { addProperty("date", it.date); addProperty("time", it.time) } } ?: JsonNull.INSTANCE)
        addProperty("important", p.important)
        add("list", ref(p.list))
        add("listName", str(p.listName))
        add("assignee", ref(p.assignee))
        add("assigneeName", str(p.assigneeName))
        add("repeat", p.repeat?.let { NRepeat.toJson(it) } ?: JsonNull.INSTANCE)
    }

    @Test
    fun `quick entry - every vector`() {
        val cases = qv.getAsJsonArray("cases")
        assertTrue(cases.size() >= 50)
        val failed = cases.mapNotNull { c ->
            val o = c.asJsonObject
            val input = o.get("input").asString
            val got = asJson(QuickParse.parse(input, ctx))
            if (got != o.getAsJsonObject("expect")) "$input\n  want ${o.get("expect")}\n  got  $got" else null
        }
        assertEquals(emptyList<String>(), failed)
    }

    @Test
    fun `quick entry - marks of the first case`() {
        val first = qv.getAsJsonArray("cases")[0].asJsonObject.get("input").asString
        val want = qv.getAsJsonArray("marksOfFirst").map { m ->
            val o = m.asJsonObject
            QuickParse.Mark(o.get("start").asInt, o.get("end").asInt, o.get("kind").asString)
        }
        assertEquals(want, QuickParse.parse(first, ctx).marks)
    }

    @Test
    fun `quick entry - the mockup line and what must stay text`() {
        val p = QuickParse.parse("Morgen 9 Uhr Zahnarzt anrufen, wichtig , #gesundheit", QuickParse.Ctx("2026-11-08", lists = listOf(QuickParse.Ref(7, "Gesundheit"))))
        assertEquals(listOf("Zahnarzt anrufen", "2026-11-09", "09:00", "true", "Gesundheit"), listOf(p.title, p.date, p.time, p.important.toString(), p.list?.name))
        assertEquals(QuickParse.Remind("2026-11-09", "09:00"), p.remind)
        for (s in listOf("So geht das nicht", "Do it now", "Buch S. 42, Nr. 3–6", "Mail an a@b.de")) {
            assertEquals(s, QuickParse.parse(s, QuickParse.Ctx("2026-11-08")).title, s)
        }
        assertThrows(IllegalArgumentException::class.java) { QuickParse.parse("x", QuickParse.Ctx("")) }
        // a time already over today → tomorrow; still to come → today
        assertEquals("2026-11-09", QuickParse.parse("um 8 Bäcker", QuickParse.Ctx("2026-11-08", now = "10:00")).date)
        assertEquals("2026-11-08", QuickParse.parse("um 18 Sport", QuickParse.Ctx("2026-11-08", now = "10:00")).date)
        // the person's own words for the times of day
        assertEquals("06:30", QuickParse.parse("morgen früh laufen", QuickParse.Ctx("2026-11-08", times = mapOf("morning" to "06:30"))).time)
    }

    @Test
    fun `repeat rules - every vector`() {
        for (c in rv.getAsJsonArray("next")) {
            val o = c.asJsonObject
            assertEquals(o.get("expect").asString, NRepeat.next(NRepeat.fromJson(o.get("rule")), o.get("due")?.takeIf { !it.isJsonNull }?.asString, o.get("done").asString), o.toString())
        }
        for (c in rv.getAsJsonArray("first")) {
            val o = c.asJsonObject
            assertEquals(o.get("expect").asString, NRepeat.first(NRepeat.fromJson(o.get("rule")), o.get("today").asString), o.toString())
        }
        for (c in rv.getAsJsonArray("describe")) {
            val o = c.asJsonObject
            val r = NRepeat.fromJson(o.get("rule"))
            assertEquals(o.get("de").asString, NRepeat.describe(r, "de"))
            assertEquals(o.get("en").asString, NRepeat.describe(r, "en"))
        }
        for (r in rv.getAsJsonArray("invalid")) assertEquals(null, NRepeat.fromJson(r), r.toString())
        // a week rule never jumps into the past, whatever day it is completed on
        for (d in 0L until 60L) {
            val done = NRepeat.addDays("2026-11-01", d)!!
            val n = NRepeat.next(NRepeat.Rule("weekly", 3, listOf(2, 6)), "2026-11-03", done)!!
            assertTrue(n > done && NRepeat.weekday(n) in listOf(2, 6), "$done → $n")
        }
    }

    @Test
    fun `repeat rules - the display of the detail screen`() {
        assertEquals("Nie", NRepeat.describe(null, "de"))
        assertEquals("Never", NRepeat.describe(null, "en"))
        assertEquals("Jeden Montag, Mittwoch und Freitag", NRepeat.describe(NRepeat.Rule("weekly", 1, listOf(5, 1, 3, 3)), "de"))
        assertEquals("Every Monday, Wednesday and Friday", NRepeat.describe(NRepeat.Rule("weekly", 1, listOf(1, 3, 5)), "en"))
        assertEquals("Alle 3 Tage", NRepeat.describe(NRepeat.Rule("daily", 3), "de"))
        assertEquals("Every 2 months", NRepeat.describe(NRepeat.Rule("monthly", 2), "en"))
        assertEquals("Jährlich", NRepeat.describe(NRepeat.Rule("yearly", 1, month = 4, day = 1), "de"))
        // a rule the server sends with a field of another type keeps working
        assertEquals("Täglich", NRepeat.describe(NRepeat.fromJson(JsonParser.parseString("""{"type":"daily","interval":1,"days":[9]}""")), "de"))
    }
}
