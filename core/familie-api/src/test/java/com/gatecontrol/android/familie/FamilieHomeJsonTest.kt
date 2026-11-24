package com.gatecontrol.android.familie

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FamilieHomeJsonTest {

    private fun obj(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test
    fun `shopping keeps valid lists and items, lenient where sqlite leaks through`() {
        val snap = FamilieHomeJson.shopping(
            obj(
                """{"lists":[{"id":"list-0001","name":"Wocheneinkauf","sort":"2"},{"id":"../x","name":"Kaputt"}],
                   "items":[{"id":"item-0001","listId":"list-0001","text":" Milch ","qty":"2 l","checked":1},
                            {"id":"item-0002","listId":"list-0001","text":""},
                            {"id":"item-0003","listId":"list-0001","text":"","deleted":true}],
                   "serverTime":"2026-11-24T18:00:00Z"}""",
            ),
        )
        assertEquals(listOf("list-0001"), snap.lists.map { it.id })
        assertEquals(2, snap.lists.single().sort)
        assertEquals(listOf("item-0001", "item-0003"), snap.items.map { it.id })
        assertEquals("Milch", snap.items.first().text)
        assertTrue(snap.items.first().checked)
        assertEquals("2026-11-24T18:00:00Z", snap.serverTime)
    }

    @Test
    fun `dates and the alltag calendar with its waste collection`() {
        val events = FamilieHomeJson.events(
            obj("""{"dates":[{"id":"1","title":"Geburtstag","date":"2026-11-28","kind":"birthday"},{"id":"2","title":"Ohne Datum"}]}"""),
        )
        assertEquals(1, events.size)
        assertTrue(events.single().allDay)
        assertTrue(events.single().isBirthday)

        val alltag = FamilieHomeJson.alltag(
            obj(
                """{"events":[{"id":"a1","title":"Zahnarzt","date":"2026-11-25","time":"10:15:00","calendar":{"name":"Familie","color":"#3DDC97"}}],
                   "waste":[{"date":"2026-11-25","kinds":["Gelbe Tonne"],"color":"#E6C229"},{"date":"2026-11-27","kinds":[]},{"date":"bad","kinds":["Papier"]}]}""",
            ),
        )
        val e = alltag.events.single()
        assertEquals("10:15", e.time)
        assertFalse(e.allDay)
        assertEquals("#3DDC97", e.calendarColor)
        assertEquals(listOf("2026-11-25"), alltag.waste.map { it.date })
    }

    @Test
    fun `error answers`() {
        assertEquals("too_soon", FamilieHomeJson.errorCode(obj("""{"ok":false,"code":"too_soon","retryAfter":60}""")))
        assertEquals(60, FamilieHomeJson.retryAfter(obj("""{"retryAfter":60}""")))
        assertNull(FamilieHomeJson.errorCode(obj("""{"code":"not a code!"}""")))
        assertEquals("a bc", FamilieHomeJson.clean("a\u0007bc", 10))
        assertEquals("a b", FamilieHomeJson.clean("a\nb", 10))
    }
}
