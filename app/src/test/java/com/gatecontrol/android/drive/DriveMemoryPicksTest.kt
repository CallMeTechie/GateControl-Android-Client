package com.gatecontrol.android.drive

import com.gatecontrol.android.drive.DriveOverviewLogic.Section
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/** Drive 1.28: the overview's `memoryPicks` — parsed as `GET memories` answers them, when „Erinnerungen“ shows them. */
class DriveMemoryPicksTest {

    private fun json(s: String) = JsonParser.parseString(s).asJsonObject

    /** The README's example (Drive 1.28, "Overview"), a broken one and one of an unknown kind at the end. */
    private val answer = """
        {"ok":true,"memories":[],
         "memoryPicks":[
           {"id":"trip:2025-07-10:2025-07-14","kind":"trip",
            "place":{"key":"geonames:2911298","name":"Hamburg","region":"Hamburg","country":"DE"},
            "places":["Hamburg"],"days":5,"firstDay":"2025-07-10","lastDay":"2025-07-14","count":16,
            "cover":{"id":812,"areaId":"mine:7","name":"IMG_2041.jpg","mime":"image/jpeg"},
            "from":"2025-07-10T00:00:00.000Z","to":"2025-07-15T00:00:00.000Z"},
           {"id":"person:7","kind":"person","person":{"id":7,"name":"Noah"},"count":11,
            "cover":{"id":640,"areaId":"mine:7","name":"IMG_640.jpg"},"from":"2024-01-01T11:00:00.000Z","to":"2024-01-21T11:00:00.000Z"},
           {"id":"month:2025-10","kind":"month","yearsAgo":1,"month":"2025-10","count":20,
            "cover":{"id":901,"areaId":"mine:7","name":"IMG_901.jpg"},"from":"2025-10-01T00:00:00.000Z","to":"2025-11-01T00:00:00.000Z"},
           {"id":"year:2024","kind":"year","year":2024,"count":71,"cover":null,
            "from":"2024-01-01T00:00:00.000Z","to":"2025-01-01T00:00:00.000Z"},
           {"id":"place:geonames:1","kind":"place","place":{"key":"geonames:1","name":"Köln"},"count":0},
           {"id":"x:1","kind":"later","count":3}]}
    """.trimIndent()

    @Test
    fun `GET overview's memoryPicks are taken as GET memories answers them`() {
        val o = DriveOverviewJson.overview(json(answer))!!
        assertEquals(listOf("trip", "person", "month", "year"), o.memoryPicks.map { it.kind })
        val (trip, person, month, year) = o.memoryPicks
        assertEquals("trip:2025-07-10:2025-07-14", trip.id)
        assertEquals(812L, trip.cover?.id)
        assertEquals(listOf(16, 5), listOf(trip.count, trip.days))
        assertEquals("Hamburg", trip.placeName)
        assertEquals(listOf("Hamburg"), trip.places)
        assertEquals("2025-07-14", trip.lastDay)
        assertEquals(7L to "Noah", person.personId to person.personName)
        assertEquals(1 to "2025-10", month.yearsAgo to month.month)
        assertEquals(2024, year.year)
        assertNull(year.cover)
        // the same parser as the page „Erinnerungen“
        val page = DriveMemoriesJson.list(json("""{"collections":${json(answer).getAsJsonArray("memoryPicks")}}"""))!!
        assertEquals(page.collections, o.memoryPicks)
    }

    @Test
    fun `an older Drive without the field, a wrong shape and too many give no or at most four picks`() {
        assertTrue(DriveOverviewJson.overview(json("""{"memories":[]}"""))!!.memoryPicks.isEmpty())
        assertTrue(DriveOverviewJson.overview(json("""{"memoryPicks":{"id":"year:2024"}}"""))!!.memoryPicks.isEmpty())
        assertTrue(DriveOverviewJson.overview(json("""{"memoryPicks":null}"""))!!.memoryPicks.isEmpty())
        val many = (2015..2024).joinToString(",") { """{"id":"year:$it","kind":"year","year":$it,"count":60}""" }
        assertEquals(4, DriveOverviewJson.overview(json("""{"overview":{"memoryPicks":[$many]}}"""))!!.memoryPicks.size)
    }

    @Test
    fun `Erinnerungen shows the days, else the picks, else nothing`() {
        val picks = DriveOverviewJson.overview(json(answer))!!.memoryPicks
        val cover = DNode(1, "mine:7", 1, "a.jpg", false, 10, "image/jpeg", null, 1, null, null, null)
        val day = DMemoryCard(1, "2025-11-08", cover, 14, id = "day:2025-11-08")
        // days „Heute vor X Jahren“: as before, the picks stay out (the server sends none then, either)
        val withDays = DOverview(memories = listOf(day), memoryPicks = picks)
        assertTrue(DriveOverviewLogic.picks(withDays).isEmpty())
        assertTrue(DriveOverviewLogic.hasMemories(withDays))
        assertEquals(listOf(Section.SEARCH, Section.MEMORIES), DriveOverviewLogic.sections(withDays, null))
        // no day: the picks
        val onlyPicks = DOverview(memoryPicks = picks)
        assertEquals(picks, DriveOverviewLogic.picks(onlyPicks))
        assertEquals(listOf(Section.SEARCH, Section.MEMORIES), DriveOverviewLogic.sections(onlyPicks, null))
        // neither: hidden as before
        val none = DOverview()
        assertFalse(DriveOverviewLogic.hasMemories(none))
        assertEquals(listOf(Section.SEARCH), DriveOverviewLogic.sections(none, null))
        // an older Drive's overview built on the phone has no picks
        assertTrue(DriveOverviewLogic.assemble(java.time.LocalDate.of(2026, 11, 8), null, null, emptyList(), null, null, null).memoryPicks.isEmpty())
    }

    @Test
    fun `a pick is titled as the page names it`() {
        val picks = DriveOverviewJson.overview(json(answer))!!.memoryPicks
        val de = Locale.GERMAN
        val (trip, person, month, year) = picks.map { DriveMemoriesLogic.text(it, de) }
        assertEquals(DriveMemoriesLogic.Title.PLACES to "Hamburg", trip.title to trip.name)
        assertEquals(DriveMemoriesLogic.Title.PERSON to "Noah", person.title to person.name)
        assertEquals(DriveMemoriesLogic.Title.MONTH_YEARS_AGO to 1, month.title to month.number)
        assertEquals(DriveMemoriesLogic.Title.YEAR to 2024, year.title to year.number)
        val place = DriveMemoriesLogic.text(DMemoryCollection("place:geonames:1", "place", 12, null, placeName = "Köln"), de)
        assertEquals(DriveMemoriesLogic.Title.PLACE to "Köln", place.title to place.name)
    }

    @Test
    fun `a pick opens on the page - handed over once, by its id, else found in the list`() {
        val picks = DriveOverviewJson.overview(json(answer))!!.memoryPicks
        val trip = picks[0]
        DriveMemoryHandoff.put(trip)
        assertNull(DriveMemoryHandoff.take("person:7"))
        assertNull(DriveMemoryHandoff.take(null))
        assertSame(trip, DriveMemoryHandoff.take(trip.id))
        assertNull(DriveMemoryHandoff.take(trip.id))
        // after a restart of the app: the page's list by the route's id
        val list = DMemoryList("2026-11-08", null, picks)
        assertEquals(picks[1], DriveMemoriesLogic.find(list, "person:7"))
        assertNull(DriveMemoriesLogic.find(list, "person:8"))
        assertNull(DriveMemoriesLogic.find(list, null))
        assertNull(DriveMemoriesLogic.find(null, "person:7"))
    }
}
