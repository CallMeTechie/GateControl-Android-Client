package com.gatecontrol.android.places

import com.gatecontrol.android.chat.ChatPlace
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/** The shared choice of a place: chips, groups, the search, distances, the plugins' lists. Made-up places only. */
class PlacePickingTest {

    // Musterstadt: the phone stands at the station
    private val here = 50.0 to 8.0
    private fun at(km: Double) = 50.0 + km / 111.2

    private val home = PlaceOption("profile:1", "Zuhause", PlaceIcons.HOME, at(0.1), 8.0, 150, lastUsedMs = 3_000)
    private val work = PlaceOption("profile:2", "Arbeit", PlaceIcons.WORK, at(12.0), 8.0, 200, usage = 5, lastUsedMs = 9_000)
    private val gym = PlaceOption("profile:3", "Fitnessstudio", PlaceIcons.GYM, at(3.4), 8.0, 100)
    private val shop = PlaceOption("profile:4", "Baumarkt", PlaceIcons.SHOP, at(5.1), 8.0, 120, usage = 2, lastUsedMs = 1_000)
    private val school = PlaceOption("place:3", "Grundschule", PlaceIcons.PIN, at(1.2), 8.0, 150, familie = true)
    private val grandma = PlaceOption("place:4", "Oma & Opa", PlaceIcons.PIN, at(41.0), 8.0, 300, familie = true)
    private val oak = PlaceOption("profile:5", "Ökohof", PlaceIcons.SHOP, at(30.0), 8.0, 100)
    private val noPoint = PlaceOption("profile:6", "Ohne Punkt", PlaceIcons.PIN, null, null, 150, usage = 9)
    private val all = listOf(home, work, gym, shop, school, grandma, oak, noPoint)

    @Test
    fun `distances`() {
        val d = PlacePicking.distanceM(50.0, 8.0, 51.0, 8.0)
        assertTrue(d in 111_000..111_400, "$d")
        assertEquals("850 m", PlacePicking.distanceText(853, Locale.GERMANY))
        assertEquals("3,4 km", PlacePicking.distanceText(3_412, Locale.GERMANY))
        assertEquals("3.4 km", PlacePicking.distanceText(3_412, Locale.ENGLISH))
        assertEquals("12 km", PlacePicking.distanceText(12_300, Locale.GERMANY))
        assertNull(PlacePicking.distanceOf(noPoint, here))
        assertNull(PlacePicking.distanceOf(home, null))
    }

    @Test
    fun `chips - the chosen one, then the most used, then the nearest, three at most`() {
        assertEquals(listOf(grandma, work, shop), PlacePicking.chips(all, grandma, here))
        // nothing chosen: the two used ones, then the nearest
        assertEquals(listOf(work, shop, home), PlacePicking.chips(all, null, here))
        // the chosen one is also the most used: once
        assertEquals(listOf(work, shop, home), PlacePicking.chips(all, work, here))
    }

    @Test
    fun `chips - without a position the rest follows the use and the plugin's order`() {
        val chips = PlacePicking.chips(all, null, null)
        assertEquals(listOf(work, shop, home), chips)
        // a place without a point is never a chip, even when it was used most
        assertTrue(noPoint !in PlacePicking.chips(all, null, here, max = 8))
        // a point picked only for this form is the first chip while chosen
        val point = PlaceOption(null, "Parkplatz", lat = 50.3, lon = 8.3, radiusM = 150)
        assertEquals(point, PlacePicking.chips(all, point, here).first())
    }

    @Test
    fun `chips follow the rank of a plugin that sorts itself`() {
        val a = PlaceOption("profile:7", "A", lat = at(9.0), lon = 8.0, rank = 1)
        val b = PlaceOption("profile:8", "B", lat = at(8.0), lon = 8.0, rank = 0)
        assertEquals(listOf(b, a), PlacePicking.chips(listOf(a, b), null, null))
    }

    @Test
    fun `the sheet - recently used, nearby by distance, then A to Z with letters`() {
        val groups = PlacePicking.groups(all, here)
        assertEquals(PlaceGroup.Kind.RECENT, groups[0].kind)
        assertEquals(listOf(work, home, shop), groups[0].items)
        assertEquals(PlaceGroup.Kind.NEAR, groups[1].kind)
        assertEquals(listOf(school, gym), groups[1].items)
        val letters = groups.drop(2)
        assertTrue(letters.all { it.kind == PlaceGroup.Kind.LETTER })
        // „Ökohof“ under O with „Ohne Punkt“ and „Oma & Opa“ — sorted as German does
        assertEquals(listOf("O"), letters.map { it.letter })
        assertEquals(listOf("Ohne Punkt", "Ökohof", "Oma & Opa"), letters[0].items.map { it.name })
        // every place once
        assertEquals(all.size, groups.sumOf { it.items.size })
    }

    @Test
    fun `the sheet without a position has no nearby group`() {
        val groups = PlacePicking.groups(all, null)
        assertTrue(groups.none { it.kind == PlaceGroup.Kind.NEAR })
        assertEquals(listOf("F", "G", "O"), groups.filter { it.kind == PlaceGroup.Kind.LETTER }.map { it.letter })
    }

    @Test
    fun `letters`() {
        assertEquals("A", PlacePicking.letterOf("Ärztehaus"))
        assertEquals("S", PlacePicking.letterOf("ßtraße"))
        assertEquals("#", PlacePicking.letterOf("3. Etage"))
    }

    @Test
    fun `the search - own and Familie places first by name, then nearest`() {
        assertEquals(listOf(shop), PlacePicking.search(all, "bau", here))
        // without accents and case
        assertEquals(listOf(oak), PlacePicking.search(all, "OKO", here))
        // the address counts too
        val addr = home.copy(address = "Lindenweg 12, Musterstadt")
        assertEquals(listOf(addr), PlacePicking.search(listOf(addr, work), "linden", here))
        // a name that starts with it comes before one that holds it
        val a = PlaceOption("profile:9", "Großer Park", lat = at(1.0), lon = 8.0)
        val b = PlaceOption("profile:10", "Parkplatz", lat = at(9.0), lon = 8.0)
        assertEquals(listOf(b, a), PlacePicking.search(listOf(a, b), "park", here))
        assertTrue(PlacePicking.search(all, "  ", here).isEmpty())
    }

    @Test
    fun `addresses are asked only from three characters and only new points are listed`() {
        assertTrue(!PlacePicking.searchable("ba"))
        assertTrue(PlacePicking.searchable(" bau "))
        val hits = listOf(AddressHit("Baumarkt", null, at(5.1), 8.0001), AddressHit("Bauhaus", null, at(6.0), 8.0))
        assertEquals(listOf("Bauhaus"), PlacePicking.newHits(hits, all).map { it.name })
    }

    @Test
    fun `times of the server and of the plugins`() {
        assertEquals(1_763_451_060_000L, PlacePicking.time("2025-11-18 07:31:00"))
        assertEquals(1_763_451_060_000L, PlacePicking.time("2025-11-18T07:31:00.000Z"))
        assertNull(PlacePicking.time("gestern"))
        assertNull(PlacePicking.time(null))
    }

    @Test
    fun `the Messenger's list keeps its order and only valid references`() {
        val o = JsonParser.parseString("""{"ok":true,"places":[
            {"ref":"profile:12","source":"profile","id":12,"name":"Arbeit","icon":"work","lat":50.110922,"lon":8.682127,"radiusM":200,
             "address":"Hafenstraße 4, Musterstadt","visibility":"self","lastUsedAt":"2026-11-18T07:31:00.000Z","usageCount":3},
            {"ref":"home","source":"familie","id":"home","name":"Zuhause","icon":"home","lat":50.05,"lon":8.6,"radiusM":150,"usageCount":0},
            {"ref":"Profile:1","name":"Falsch","lat":1,"lon":1},
            {"ref":"place:3","source":"familie","name":"Schule","lat":null,"lon":null}
        ],"sources":{"profile":true,"familie":true}}""").asJsonObject
        val list = PlaceSources.messenger(o)!!
        assertEquals(listOf("profile:12", "home", "place:3"), list.map { it.ref })
        assertEquals(listOf(0, 1, 3), list.map { it.rank })
        assertEquals(3, list[0].usage)
        assertTrue(!list[0].familie && list[1].familie && list[2].familie)
        assertEquals(12L, list[0].profileId)
        assertTrue(!list[2].hasPoint)
        assertNull(PlaceSources.messenger(JsonParser.parseString("{}").asJsonObject))
    }

    @Test
    fun `the own places fill in what a plugin does not list yet`() {
        val mine = listOf(MyPlace(id = 2, name = "Arbeit", lat = 50.1, lon = 8.6), MyPlace(id = 30, name = "Neu", lat = 50.2, lon = 8.7, lastUsedAt = "2026-11-18 07:31:00"))
        val merged = PlaceSources.merged(listOf(work), mine)
        assertEquals(listOf("profile:2", "profile:30"), merged.map { it.ref })
        assertEquals(5, merged[0].usage) // the plugin's entry wins
        assertEquals(PlacePicking.time("2026-11-18 07:31:00"), merged[1].lastUsedMs)
    }

    @Test
    fun `the Familie places of an older Messenger keep their ids`() {
        val list = PlaceSources.familie(listOf(ChatPlace("home", "Zuhause", 50.05, 8.6, 150), ChatPlace("7", "Schule", null, null, null)))
        assertEquals(listOf("home", "7"), list.map { it.ref })
        assertEquals(PlaceIcons.HOME, list[0].icon)
        assertTrue(list.all { it.familie })
        assertTrue(!list[1].hasPoint)
    }
}
