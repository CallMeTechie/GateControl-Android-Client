package com.gatecontrol.android.drive

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Locale

/** Drive 1.26: the page „Erinnerungen“ — `GET memories`, the sections, the words of a card, older servers' cards. */
class DriveMemoriesTest {

    private fun json(s: String) = JsonParser.parseString(s).asJsonObject

    private fun node(id: Long, area: String = "mine:7", work: Boolean = false, review: Boolean = false) =
        DNode(id, area, 1, "IMG_$id.jpg", false, 10, "image/jpeg", null, 1, null, null, null, review = if (review) DReview(null) else null, work = work)

    private val answer = """
        {"ok":true,"date":"2026-11-08","home":{"key":"geonames:2879185","name":"Langenlonsheim"},
         "collections":[
           {"id":"day:2025-11-08","kind":"day","yearsAgo":1,"date":"2025-11-08","count":14,"cover":{"id":1,"areaId":"mine:7","name":"a.jpg"}},
           {"id":"trip:2025-07-10:2025-07-14","kind":"trip","place":{"key":"geonames:2911298","name":"Hamburg"},"places":["Hamburg","Lübeck"],"days":5,"firstDay":"2025-07-10","lastDay":"2025-07-14","count":87},
           {"id":"person:12","kind":"person","person":{"id":12,"name":"Noah"},"count":120},
           {"id":"month:2024-11","kind":"month","yearsAgo":2,"month":"2024-11","count":20},
           {"id":"year:2025","kind":"year","year":2025,"count":1234},
           {"id":"place:geonames:2879185","kind":"place","place":{"key":"geonames:2879185","name":"Langenlonsheim"},"count":12},
           {"id":"x:1","kind":"unknown","count":3},
           {"id":"day:2020-01-01","kind":"day","count":0},
           {"kind":"day","count":4}]}
    """.trimIndent()

    @Test
    fun `GET memories is taken as the contract says, the rest is dropped`() {
        val l = DriveMemoriesJson.list(json(answer))!!
        assertEquals("2026-11-08", l.date)
        assertEquals("Langenlonsheim", l.home)
        assertEquals(listOf("day", "trip", "person", "month", "year", "place"), l.collections.map { it.kind })
        val (day, trip, person, month, year, place) = l.collections
        assertEquals(1L, day.cover?.id)
        assertEquals(listOf(1, 14), listOf(day.yearsAgo, day.count))
        assertEquals(listOf("Hamburg", "Lübeck"), trip.places)
        assertEquals(5, trip.days)
        assertEquals(12L, person.personId)
        assertEquals("Noah", person.personName)
        assertEquals("2024-11", month.month)
        assertEquals(2025, year.year)
        assertEquals("Langenlonsheim", place.placeName)
        assertNull(DriveMemoriesJson.list(json("""{"ok":true}""")))
    }

    private operator fun <T> List<T>.component6(): T = this[5]

    @Test
    fun `sections in the page's order, each kind once`() {
        val l = DriveMemoriesJson.list(json(answer))!!
        val shuffled = l.collections.reversed()
        val s = DriveMemoriesLogic.sections(shuffled)
        assertEquals(DriveMemoriesLogic.KINDS, s.map { it.kind })
        assertTrue(s.all { it.items.size == 1 })
        assertTrue(DriveMemoriesLogic.sections(emptyList()).isEmpty())
    }

    @Test
    fun `the words of a card`() {
        val l = DriveMemoriesJson.list(json(answer))!!.collections
        val de = Locale.GERMAN
        val day = DriveMemoriesLogic.text(l[0], de)
        assertEquals(DriveMemoriesLogic.Title.YEARS_AGO, day.title)
        assertEquals(1, day.number)
        assertEquals("8. November 2025", day.`when`)
        val trip = DriveMemoriesLogic.text(l[1], de)
        assertEquals(DriveMemoriesLogic.Title.PLACES, trip.title)
        assertEquals("Hamburg, Lübeck", trip.name)
        assertEquals("Juli 2025", trip.`when`)
        assertEquals(5, trip.days)
        assertEquals("Noah", DriveMemoriesLogic.text(l[2], de).name)
        assertEquals("November 2024", DriveMemoriesLogic.text(l[3], de).`when`)
        assertEquals(2, DriveMemoriesLogic.text(l[3], de).number)
        assertEquals(2025, DriveMemoriesLogic.text(l[4], de).number)
        assertEquals("Langenlonsheim", DriveMemoriesLogic.text(l[5], de).name)
        assertEquals("July 2025", DriveMemoriesLogic.monthYear("2025-07", Locale.ENGLISH))
        assertNull(DriveMemoriesLogic.monthYear("2025-13", de))
        assertNull(DriveMemoriesLogic.fullDate("kaputt", de))
    }

    @Test
    fun `an older Drive's cards keep only private photos and only a day with more than 10`() {
        val private11 = (1L..11L).map { node(it) }
        val mixed = (20L..29L).map { node(it) } + node(30, work = true) + node(31, review = true) + node(32, area = "team:1")
        val groups = listOf(
            DMemories(1, "2025-11-08", private11),
            DMemories(2, "2024-11-08", mixed),
            DMemories(3, "2023-11-08", (40L..49L).map { node(it) }),
        )
        val days = DriveMemoriesLogic.privateDays(groups)
        assertEquals(listOf(1), days.map { it.yearsAgo })
        assertEquals(11, days[0].items.size)
        assertFalse(DriveMemoriesLogic.isPrivate(node(1, work = true)))
        assertFalse(DriveMemoriesLogic.isPrivate(node(1, review = true)))
        assertFalse(DriveMemoriesLogic.isPrivate(node(1, area = "team:3")))
        assertTrue(DriveMemoriesLogic.isPrivate(node(1, area = "family:1")))
        // the overview built from them shows the same
        val o = DriveOverviewLogic.assemble(LocalDate.of(2026, 11, 8), null, null, groups, null, null, null)
        assertEquals(listOf(1 to 11), o.memories.map { it.yearsAgo to it.count })
    }

    @Test
    fun `the overview's card carries the collection's id`() {
        val o = DriveOverviewJson.overview(json("""{"memories":[{"yearsAgo":1,"date":"2025-11-08","count":14,"id":"day:2025-11-08","cover":{"id":1,"areaId":"mine:7","name":"a.jpg"}}]}"""))!!
        assertEquals("day:2025-11-08", o.memories.single().id)
        val old = DriveOverviewJson.overview(json("""{"memories":[{"yearsAgo":1,"count":14,"cover":{"id":1,"areaId":"mine:7","name":"a.jpg"}}]}"""))!!
        assertNull(old.memories.single().id)
    }
}
