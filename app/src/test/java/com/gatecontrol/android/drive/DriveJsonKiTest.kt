package com.gatecontrol.android.drive

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Drive 1.14 (AI in Drive): answers and bodies of `ask`, `scans/classify`, `scans/convert`, `labels`, the photo search and the family calendar. */
class DriveJsonKiTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    @Test
    fun `me reports the new features`() {
        val me = DriveJson.me(json("""{"me":{"id":7,"seat":"family"},"licence":{"valid":true},"features":["docAsk","scanDocs","photoSearch","deadlineCalendar"]}"""))!!
        listOf(DMe.FEATURE_DOC_ASK, DMe.FEATURE_SCAN_DOCS, DMe.FEATURE_PHOTO_SEARCH, DMe.FEATURE_DEADLINE_CALENDAR).forEach { assertTrue(me.has(it), it) }
    }

    @Test
    fun `deadlines know warranty and renewal and the calendar entry`() {
        val list = DriveJson.deadlines(
            json(
                """{"deadlines":[
                {"id":1,"kind":"warranty","date":"2026-12-12","label":"Geschirrspüler"},
                {"id":2,"kind":"renewal","date":"2027-01-31","label":"Fitnessstudio","calendar":{"eventId":"ev_9","at":"2026-11-03T09:00:00Z"}},
                {"id":3,"kind":"weird","date":"2027-01-31"}]}""",
            ),
        )!!
        assertEquals(listOf(1L, 2L), list.map { it.id })
        assertNull(list[0].calendar)
        assertEquals(DCalendarEntry("ev_9", "2026-11-03T09:00:00Z"), list[1].calendar)
        // a numeric event id is taken as text
        val d = DriveJson.deadlineAnswer(json("""{"deadline":{"id":4,"kind":"cancellation","date":"2026-11-30","calendar":{"eventId":42}}}"""))!!
        assertEquals("42", d.calendar?.eventId)
    }

    @Test
    fun `the answer of ask`() {
        val a = DriveJson.answer(
            json(
                """{"status":"answered","answer":" Kündigen bis 30.11.2026. ","terms":["Kfz","Versicherung",{"term":"Laufzeit"},"kfz"],
                "sources":[{"nodeId":12,"name":"Versicherungsschein Kfz 2026.pdf","path":"Versicherungen","page":1,"quote":"Vertragslaufzeit"},
                {"nodeId":0,"name":"x"},{"nodeId":13},{"nodeId":12,"name":"dup","page":1}]}""",
            ),
        )!!
        assertTrue(a.answered)
        assertEquals("Kündigen bis 30.11.2026.", a.answer)
        assertEquals(listOf("Kfz", "Versicherung", "Laufzeit"), a.terms)
        assertEquals(listOf(12L), a.sources.map { it.nodeId })
        assertEquals(DSource(12, "Versicherungsschein Kfz 2026.pdf", "Versicherungen", 1, "Vertragslaufzeit"), a.sources[0])

        val none = DriveJson.answer(json("""{"status":"not_found","answer":null,"sources":[],"terms":["Kfz"]}"""))!!
        assertFalse(none.answered)
        assertEquals(listOf("Kfz"), none.terms)
        assertNull(DriveJson.answer(json("""{"status":"maybe"}""")))
        // answered without words is no answer
        assertFalse(DriveJson.answer(json("""{"status":"answered","answer":"  "}"""))!!.answered)

        val body = DriveJson.askBody("  Wann? ", "family:3")
        assertEquals("Wann?", body.get("q").asString)
        assertEquals("family:3", body.get("area").asString)
        assertFalse(DriveJson.askBody("x".repeat(900), null).has("area"))
        assertEquals(DriveJson.ASK_MAX, DriveJson.askBody("x".repeat(900), null).get("q").asString.length)
    }

    @Test
    fun `classify and convert`() {
        assertEquals(DScanKind("table", 0.92, "3 Spalten, 12 Zeilen, Summenzeile"), DriveJson.scanKind(json("""{"kind":"table","confidence":0.92,"summary":"3 Spalten, 12 Zeilen, Summenzeile"}""")))
        assertEquals("other", DriveJson.scanKind(json("""{"kind":"poem","confidence":7}"""))!!.kind)
        assertEquals(1.0, DriveJson.scanKind(json("""{"kind":"poem","confidence":7}"""))!!.confidence)
        assertNull(DriveJson.scanKind(json("""{"confidence":0.5}""")))

        val c = DriveJson.classifyBody(listOf("abcdef12"), "Text")
        assertEquals("abcdef12", c.getAsJsonArray("mediaIds")[0].asString)
        assertEquals("Text", c.get("text").asString)
        assertFalse(DriveJson.classifyBody(listOf("abcdef12"), " ").has("text"))

        val b = DriveJson.convertBody(listOf("abcdef12", "ghijkl34"), "xlsx", "table", 40, "2026 Zählerstände.xlsx", keepOriginal = true)
        assertEquals(2, b.getAsJsonArray("mediaIds").size())
        assertEquals("xlsx", b.get("target").asString)
        assertEquals("table", b.get("kind").asString)
        assertEquals(40L, b.get("folderId").asLong)
        assertEquals("2026 Zählerstände.xlsx", b.get("name").asString)
        assertTrue(b.get("keepOriginal").asBoolean)
        assertFalse(DriveJson.convertBody(listOf("abcdef12"), "docx", "printed", null, null, false).has("folderId"))

        val r = DriveJson.converted(json("""{"node":{"id":5,"areaId":"mine:7","name":"a.xlsx"},"original":{"id":6,"areaId":"mine:7","name":"a.jpg"}}"""))!!
        assertEquals(5L, r.node.id)
        assertEquals(6L, r.original?.id)
        assertNull(DriveJson.converted(json("""{"node":{"id":5,"areaId":"mine:7","name":"a.xlsx"}}"""))!!.original)
        assertNull(DriveJson.converted(json("""{}""")))
    }

    @Test
    fun `labels and the photo search`() {
        val b = DriveJson.labelsBody(listOf(" Beach ", "beach", "", "x".repeat(50)) + (1..30).map { "L$it" })
        val labels = b.getAsJsonArray("labels").map { it.asString }
        assertEquals("Beach", labels[0])
        assertEquals(40, labels[1].length)
        assertEquals(DriveJson.LABELS_MAX, labels.size)
        assertEquals("device", b.get("source").asString)

        assertEquals("Strand Meer", DriveJson.photosQuery(q = "  Strand Meer ")!!["q"])
        assertFalse(DriveJson.photosQuery(q = " ")!!.containsKey("q"))
        assertEquals(DriveJson.PHOTO_Q_MAX, DriveJson.photosQuery(q = "x".repeat(300))!!["q"]!!.length)

        val p = DriveJson.photos(json("""{"items":[{"id":1,"areaId":"mine:7","name":"a.jpg"}],"next":null,"total":38,"terms":[{"label":"Meer","count":20},"Sonnenuntergang"]}"""))!!
        assertEquals(38, p.total)
        assertEquals(listOf("Meer", "Sonnenuntergang"), p.terms)
        val plain = DriveJson.photos(json("""{"items":[]}"""))!!
        assertNull(plain.total)
        assertTrue(plain.terms.isEmpty())
        assertEquals(listOf("a"), DriveJson.terms(json("""{"chips":["a"]}""")))
    }
}
