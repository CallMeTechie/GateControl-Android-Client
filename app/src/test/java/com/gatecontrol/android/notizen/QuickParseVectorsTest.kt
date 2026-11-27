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
 * `plugins/gatecontrol-notizen/test/vectors/{quickparse,repeat}.json`: `quickparse.json` of gatecontrol-notizen
 * **1.9.0** (commit 6e347b5 of main; a weekday next to a calendar date belongs to it, with the optional `marks` of a
 * case; `eventCases` in event mode and `signalCases` for „looks like an event“), `repeat.json` of **1.0.0** (commit af53ac1). Never edit them here: a new plugin version brings new files.
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

    private fun marks(a: JsonArray): List<QuickParse.Mark> = a.map { m ->
        val o = m.asJsonObject
        QuickParse.Mark(o.get("start").asInt, o.get("end").asInt, o.get("kind").asString)
    }

    @Test
    fun `quick entry - every vector`() {
        val cases = qv.getAsJsonArray("cases")
        assertTrue(cases.size() >= 73)
        val failed = cases.mapNotNull { c ->
            val o = c.asJsonObject
            val input = o.get("input").asString
            val p = QuickParse.parse(input, ctx)
            val got = asJson(p)
            // the marks of a case are optional: where they are given, they are what the parser must mark
            val wantMarks = o.getAsJsonArray("marks")?.let { marks(it) }
            when {
                got != o.getAsJsonObject("expect") -> "$input\n  want ${o.get("expect")}\n  got  $got"
                wantMarks != null && wantMarks != p.marks -> "$input (marks)\n  want $wantMarks\n  got  ${p.marks}"
                else -> null
            }
        }
        assertEquals(emptyList<String>(), failed)
        // a weekday next to a calendar date belongs to it (the cases with marks): there are enough of them
        assertTrue(cases.count { it.asJsonObject.has("marks") } >= 20)
    }

    @Test
    fun `quick entry - event mode, every vector`() {
        val cases = qv.getAsJsonArray("eventCases")
        assertTrue(cases.size() >= 30)
        val eventCtx = ctx.copy(mode = "event")
        val failed = cases.mapNotNull { c ->
            val o = c.asJsonObject
            val input = o.get("input").asString
            val p = QuickParse.parse(input, eventCtx)
            val got = JsonObject().apply {
                add("title", str(p.title))
                add("date", str(p.date))
                add("time", str(p.time))
                add("endTime", str(p.endTime))
                addProperty("allDay", p.allDay)
                add("location", str(p.location))
                add("repeat", p.repeat?.let { NRepeat.toJson(it) } ?: JsonNull.INSTANCE)
            }
            val want = o.getAsJsonObject("expect")
            val wantMarks = o.getAsJsonArray("marks")?.let { marks(it) }
            // only the fields the case names (the file may add more later)
            val gotSome = JsonObject().apply { want.keySet().forEach { k -> add(k, got.get(k)) } }
            when {
                gotSome != want -> "$input\n  want $want\n  got  $gotSome"
                wantMarks != null && wantMarks != p.marks -> "$input (marks)\n  want $wantMarks\n  got  ${p.marks}"
                else -> null
            }
        }
        assertEquals(emptyList<String>(), failed)
    }

    @Test
    fun `quick entry - looks like an event, every vector`() {
        val cases = qv.getAsJsonArray("signalCases")
        assertTrue(cases.size() >= 10)
        val failed = cases.mapNotNull { c ->
            val o = c.asJsonObject
            val input = o.get("input").asString
            val p = QuickParse.parse(input, ctx)
            if (p.looksLikeEvent != o.get("looksLikeEvent").asBoolean) "$input → ${p.looksLikeEvent}" else null
        }
        assertEquals(emptyList<String>(), failed)
    }

    @Test
    fun `quick entry - task mode keeps the new fields empty`() {
        val p = QuickParse.parse("Sa 14–16 Uhr Flohmarkt bei Oma", ctx)
        assertEquals(listOf(null, false, null), listOf(p.endTime, p.allDay, p.location))
        // a span is no rule of a task: „16 Uhr“ is the time there
        assertEquals("16:00", p.time)
    }

    @Test
    fun `quick entry - marks of the first case`() {
        val first = qv.getAsJsonArray("cases")[0].asJsonObject.get("input").asString
        val want = marks(qv.getAsJsonArray("marksOfFirst"))
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
