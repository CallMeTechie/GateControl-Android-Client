package com.gatecontrol.android.notizen

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * „Wer ist dran“ and stars of the typed line against the plugin's own vectors — copied unchanged from
 * gatecontrol-plugins `plugins/gatecontrol-notizen/test/vectors/turnwords.json` of gatecontrol-notizen **1.6.0** (commit
 * 8edebd9 of main). Never edit them here: a new plugin version brings a new file. Each case is read by [QuickParse]
 * first (unchanged, with its own vectors in [QuickParseVectorsTest]), then by [TurnWords].
 */
class TurnWordsVectorsTest {

    private val v: JsonObject =
        JsonParser.parseString(requireNotNull(javaClass.classLoader!!.getResource("notizen/turnwords.json")).readText()).asJsonObject

    private val ctxJson = v.getAsJsonObject("ctx")
    private val people = ctxJson.getAsJsonArray("people").map { QuickParse.Ref(it.asJsonObject.get("id").asLong, it.asJsonObject.get("name").asString) }
    private val ctx = QuickParse.Ctx(today = ctxJson.get("today").asString, now = ctxJson.get("now")?.asString, people = people)

    private data class Got(val title: String, val people: List<Long>, val turn: String?, val stars: Int?)

    @Test
    fun `whose turn and stars - every vector`() {
        val cases = v.getAsJsonArray("cases")
        assertEquals(16, cases.size())
        val failed = cases.mapNotNull { c ->
            val o = c.asJsonObject
            val input = o.get("input").asString
            val stars = o.get("stars")?.takeIf { !it.isJsonNull }?.asBoolean != false
            val e = o.getAsJsonObject("expect")
            val want = Got(
                e.get("title").asString,
                e.getAsJsonArray("people").map { it.asLong },
                e.get("turn").takeIf { !it.isJsonNull }?.asString,
                e.get("stars").takeIf { !it.isJsonNull }?.asInt,
            )
            val r = TurnWords.apply(QuickParse.parse(input, ctx), people, stars)
            val got = Got(r.title, r.people.map { it.id }, r.turn, r.stars)
            if (got != want) "$input (stars=$stars)\n  want $want\n  got  $got" else null
        }
        assertEquals(emptyList<String>(), failed)
    }

    @Test
    fun `nothing taken - the title is quickparse's, untouched`() {
        val p = QuickParse.parse("Wer zuerst kommt, mahlt zuerst", ctx)
        val r = TurnWords.apply(p, people, stars = true)
        assertEquals(p.title, r.title)
        assertEquals(TurnWords.Result(p.title, emptyList(), null, null), r)
        // the person quickparse took comes first, the name of the list as the people know it
        val q = TurnWords.apply(QuickParse.parse("@jonas @mia Müll, in turns", ctx), people, stars = false)
        assertEquals(listOf("Jonas", "Mia"), q.people.map { it.name })
        assertEquals(TurnWords.ROTATE, q.turn)
        assertEquals("Müll", q.title)
        // words inside other words are not the words
        assertEquals(null, TurnWords.apply("Reihumlauf planen", people[3], people + people, stars = true).turn)
        assertEquals(null, TurnWords.apply("5 Sternebilder malen", null, people, stars = true).stars)
    }
}
