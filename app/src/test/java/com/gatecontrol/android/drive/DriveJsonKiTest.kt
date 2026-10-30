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

        val none = DriveJson.answer(json("""{"status":"not_found","answer":null,"sources":[],"terms":["Kfz"],"areas":["Meine Dateien","Familie"],"deadline":null}"""))!!
        assertFalse(none.answered)
        assertEquals(listOf("Kfz"), none.terms)
        assertEquals(listOf("Meine Dateien", "Familie"), none.areas)
        assertNull(none.deadline)
        val withDeadline = DriveJson.answer(
            json("""{"status":"answered","answer":"Bis 30.11.2026.","sources":[{"nodeId":12,"name":"a.pdf","page":null}],"deadline":{"id":9,"kind":"cancellation","date":"2026-11-30","label":"Kfz kündigen","calendar":null,"nodeId":12}}"""),
        )!!
        assertEquals(9L, withDeadline.deadline?.id)
        assertEquals(12L, withDeadline.deadline?.nodeId)
        assertNull(withDeadline.sources[0].page)
        assertNull(DriveJson.answer(json("""{"status":"maybe"}""")))
        // answered without words is no answer
        assertFalse(DriveJson.answer(json("""{"status":"answered","answer":"  "}"""))!!.answered)

        val body = DriveJson.askBody("  Wann? ", "family:3")
        assertEquals("Wann?", body.get("q").asString)
        assertEquals("family:3", body.get("area").asString)
        assertFalse(DriveJson.askBody("x".repeat(900), null).has("area"))
        assertEquals(300, DriveJson.askBody("x".repeat(900), null).get("q").asString.length)
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

        val b = DriveJson.convertBody(listOf("abcdef12", "ghijkl34"), "xlsx", "table", 40, "2026 Zählerstände.xlsx", "Datum Zähler", listOf("AAAA"))
        assertEquals(2, b.getAsJsonArray("mediaIds").size())
        assertEquals("xlsx", b.get("target").asString)
        assertEquals("table", b.get("kind").asString)
        assertEquals(40L, b.get("folderId").asLong)
        assertEquals("2026 Zählerstände.xlsx", b.get("name").asString)
        assertEquals("Datum Zähler", b.get("text").asString)
        assertEquals("AAAA", b.getAsJsonArray("images")[0].asString)
        assertFalse(b.has("keepOriginal"))
        val plain = DriveJson.convertBody(listOf("abcdef12"), "docx", "printed", null, null, null, emptyList())
        assertFalse(plain.has("folderId") || plain.has("text") || plain.has("images"))

        val data = java.util.Base64.getEncoder().encodeToString("PK-word".toByteArray())
        val conv = DriveJson.conversion(
            json("""{"kind":"table","target":"xlsx","title":"Zählerstände","name":"2026-11-03 Zählerstände.xlsx","folderId":40,"folderPath":"Haus","text":"Datum	Stand","content":{},"file":{"name":"2026-11-03 Zählerstände.xlsx","mime":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","size":7,"sha256":"x","data":"$data"},"source":"media","usage":{"costCents":0.1}}"""),
        )!!
        assertEquals("2026-11-03 Zählerstände.xlsx", conv.name)
        assertEquals(40L, conv.folderId)
        assertEquals("PK-word", String(conv.file!!.data))
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", conv.file!!.mime)
        // a PDF without pictures: no file, the app makes it
        assertNull(DriveJson.conversion(json("""{"kind":"printed","target":"pdf","text":"Hallo","file":null}"""))!!.file)
        // a file that cannot be read is no answer
        assertNull(DriveJson.conversion(json("""{"kind":"printed","target":"docx","file":{"name":"a.docx","data":"%%%"}}""")))
        assertNull(DriveJson.conversion(json("""{"kind":"printed"}""")))

        val save = DriveJson.saveScanBody("abcdef12", 40, "a.xlsx", "Text")
        assertEquals("abcdef12", save.get("mediaId").asString)
        assertEquals(40L, save.get("folderId").asLong)
        assertEquals("a.xlsx", save.get("name").asString)
        assertEquals("Text", save.get("text").asString)
        val saved = DriveJson.saved(json("""{"node":{"id":5,"areaId":"mine:7","name":"a.xlsx"},"original":null}"""))!!
        assertEquals(5L, saved.node.id)
        assertNull(saved.original)
        assertNull(DriveJson.saved(json("""{}""")))
    }

    @Test
    fun `area and me - deadlines into the calendar, keep the original scan`() {
        val me = DriveJson.me(
            json("""{"me":{"id":7},"licence":{"valid":true},"settings":{"scanKeepOriginal":false},"areas":[{"id":"mine:7","rootId":1,"role":"manage","docAi":true,"deadlinesToCalendar":true}]}"""),
        )!!
        assertEquals(false, me.scanKeepOriginal)
        assertEquals(true, me.areas[0].deadlinesToCalendar)
        assertNull(DriveJson.me(json("""{"me":{"id":7}}"""))!!.scanKeepOriginal)
        assertNull(DriveJson.area(json("""{"id":"mine:7","rootId":1}"""))!!.deadlinesToCalendar)
        assertTrue(DriveJson.deadlinesToCalendarBody(true).get("deadlinesToCalendar").asBoolean)
        assertEquals(false, DriveJson.areaDeadlinesToCalendar(json("""{"area":{"id":"mine:7","docAi":true,"deadlinesToCalendar":false}}""")))
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
        assertEquals("1", DriveJson.photosQuery(unlabeled = true)!!["unlabeled"])
        assertFalse(DriveJson.photosQuery()!!.containsKey("unlabeled"))
        assertEquals(true, DriveJson.node(json("""{"id":1,"areaId":"mine:7","name":"a.jpg","labeled":true}"""))!!.labeled)
        assertNull(DriveJson.node(json("""{"id":1,"areaId":"mine:7","name":"a.jpg"}"""))!!.labeled)
        assertEquals(DriveJson.PHOTO_Q_MAX, DriveJson.photosQuery(q = "x".repeat(300))!!["q"]!!.length)

        val p = DriveJson.photos(json("""{"items":[{"id":1,"areaId":"mine:7","name":"a.jpg"}],"next":null,"total":38,"terms":[{"term":"meer","label":"sea","count":20},"Sonnenuntergang"]}"""))!!
        assertEquals(38, p.total)
        assertEquals(listOf("meer", "Sonnenuntergang"), p.terms)
        val plain = DriveJson.photos(json("""{"items":[]}"""))!!
        assertNull(plain.total)
        assertTrue(plain.terms.isEmpty())
        assertEquals(listOf("a"), DriveJson.terms(json("""{"chips":["a"]}""")))
    }
}
