package com.gatecontrol.android.places

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** „Meine Orte“: the answers, the bodies of a change and the copy on the phone. Only made-up names and addresses. */
class MyPlacesTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val answer = """{"ok":true,"family":true,"search":"osm",
        "limits":{"places":100,"name":60,"address":200,"radius":{"min":50,"max":2000,"default":150}},
        "options":{"icons":["home","work","school","gym","shop","family","pin"],"visibility":["self","family","all"]},
        "places":[
          {"id":12,"ref":"profile:12","name":"Arbeit","icon":"work","lat":50.110922,"lon":8.682127,"radiusM":200,"address":"Hafenstraße 4, Musterstadt",
           "visibility":"self","source":"manual","usedBy":[{"plugin":"gatecontrol-messenger","pluginName":"Messenger","kind":"location_message","count":1}],
           "createdAt":"2026-11-18 09:12:44","updatedAt":"2026-11-18 09:12:44","lastUsedAt":"2026-11-18 07:31:00"},
          {"id":13,"name":"Zuhause","icon":"home","lat":"50.05","lon":"8.6","radiusM":150,"visibility":"family"},
          {"id":14,"name":"Baumarkt","icon":"spaceship","lat":0,"lon":0,"radiusM":5,"source":"notizen","usedBy":[{"plugin":"gatecontrol-notizen","kind":"task","count":2},{"plugin":"x","kind":"BAD","count":1}]},
          {"id":0,"name":"kaputt","lat":1,"lon":1},
          {"id":15,"lat":1,"lon":1}
        ]}"""

    @Test
    fun `the list is parsed with points, symbols, uses, limits and the search`() {
        val l = MyPlaces.list(json(answer))!!
        assertEquals(listOf(12L, 13L, 14L), l.places.map { it.id })
        assertTrue(l.family)
        assertEquals("osm", l.search)
        assertEquals(150, l.limits.radiusDefault)
        val work = l.places[0]
        assertEquals("profile:12", work.ref)
        assertEquals("work", work.icon)
        assertEquals(200, work.radiusM)
        assertEquals("Hafenstraße 4, Musterstadt", work.address)
        assertEquals(1, work.usedBy.single().count)
        assertEquals("location_message", work.usedBy.single().kind)
        // a point as a decimal string counts
        assertEquals(50.05, l.places[1].lat)
        assertEquals("family", l.places[1].visibility)
        // 0/0 is no point (Bild 4: „kein Standort“), an unknown symbol is the pin, the radius is kept within 50–2000 m
        val shop = l.places[2]
        assertFalse(shop.hasPoint)
        assertNull(shop.lat)
        assertEquals("pin", shop.icon)
        assertEquals(50, shop.radiusM)
        assertEquals(listOf("task"), shop.usedBy.map { it.kind })
        assertEquals(2, shop.usedCount)
    }

    @Test
    fun `an answer without places is none, an empty one is an empty list`() {
        assertNull(MyPlaces.list(json("""{"ok":true}""")))
        val l = MyPlaces.list(json("""{"ok":true,"places":[]}"""))!!
        assertTrue(l.places.isEmpty())
        assertEquals("off", l.search)
    }

    @Test
    fun `references`() {
        assertEquals(12L, MyPlaces.idOf("profile:12"))
        assertNull(MyPlaces.idOf("profile:0"))
        assertNull(MyPlaces.idOf("home"))
        assertNull(MyPlaces.idOf("Profile:1"))
        assertTrue(MyPlaces.isRef("home"))
        assertTrue(MyPlaces.isRef("place:3"))
        assertFalse(MyPlaces.isRef("profile: 1"))
        assertFalse(MyPlaces.isRef("profile:1:2"))
    }

    @Test
    fun `a new place needs a name and a point, and sends only the known keys`() {
        assertNull(MyPlaces.createBody(PlaceDraft(name = "Arbeit")))
        assertNull(MyPlaces.createBody(PlaceDraft(name = "  ", lat = 50.0, lon = 8.0)))
        assertNull(MyPlaces.createBody(PlaceDraft(name = "Null", lat = 0.0, lon = 0.0)))
        val b = MyPlaces.createBody(PlaceDraft(name = " Arbeit\n  Nord ", icon = "work", lat = 50.1109224, lon = 8.6821268, radiusM = 5000, address = "Hafenstraße 4", visibility = "family"))!!
        assertEquals(setOf("name", "lat", "lon", "icon", "radiusM", "address", "visibility"), b.keySet())
        assertEquals("Arbeit Nord", b.get("name").asString)
        assertEquals(50.110922, b.get("lat").asDouble)
        assertEquals(2000, b.get("radiusM").asInt)
        assertEquals("family", b.get("visibility").asString)
    }

    @Test
    fun `a change sends only what changed, the point always as a pair, an emptied address as null`() {
        val old = MyPlaces.list(json(answer))!!.places[0]
        assertEquals(0, MyPlaces.patchBody(old, MyPlaces.draftOf(old))!!.size())
        val moved = MyPlaces.patchBody(old, MyPlaces.draftOf(old).copy(lat = 50.2))!!
        assertEquals(setOf("lat", "lon"), moved.keySet())
        val cleared = MyPlaces.patchBody(old, MyPlaces.draftOf(old).copy(address = " ", name = "Büro"))!!
        assertTrue(cleared.get("address").isJsonNull)
        assertEquals("Büro", cleared.get("name").asString)
        // a place without a point gets one
        val noPoint = MyPlaces.list(json(answer))!!.places[2]
        val set = MyPlaces.patchBody(noPoint, MyPlaces.draftOf(noPoint).copy(lat = 50.3, lon = 8.4))!!
        assertEquals(setOf("lat", "lon"), set.keySet())
        assertNull(MyPlaces.patchBody(noPoint, MyPlaces.draftOf(noPoint)))
    }

    @Test
    fun `the name is taken without regard to case`() {
        val places = MyPlaces.list(json(answer))!!.places
        assertTrue(MyPlaces.nameTaken("arbeit", places))
        assertFalse(MyPlaces.nameTaken("arbeit", places, exceptId = 12))
        assertFalse(MyPlaces.nameTaken("Schule", places))
    }

    @Test
    fun `the slider snaps to 50 m within the limits`() {
        assertEquals(50, MyPlaces.snapRadius(10f))
        assertEquals(200, MyPlaces.snapRadius(212f))
        assertEquals(250, MyPlaces.snapRadius(230f))
        assertEquals(2000, MyPlaces.snapRadius(4000f))
    }

    @Test
    fun `the copy on the phone belongs to one login and survives a round trip`() {
        val l = MyPlaces.list(json(answer))!!
        val raw = MyPlaces.encodeCache(l, "owner-a")
        val back = MyPlaces.decodeCache(raw, "owner-a")
        assertNotNull(back)
        assertEquals(l.places, back!!.places)
        assertEquals(l.limits, back.limits)
        assertEquals(l.search, back.search)
        assertTrue(back.family)
        assertNull(MyPlaces.decodeCache(raw, "owner-b"))
        assertNull(MyPlaces.decodeCache("{broken", "owner-a"))
        assertNull(MyPlaces.decodeCache("""{"v":0,"owner":"owner-a","list":{"places":[]}}""", "owner-a"))
        assertNull(MyPlaces.decodeCache(null, "owner-a"))
    }

    @Test
    fun `a change replaces the place in the copy, a deletion removes it`() {
        val l = MyPlaces.list(json(answer))!!
        val renamed = l.places[0].copy(name = "Büro")
        assertEquals("Büro", MyPlaces.upsert(l, renamed).places[0].name)
        val added = MyPlaces.upsert(l, MyPlace(id = 99, name = "Schule", lat = 50.0, lon = 8.0))
        assertEquals(99L, added.places.last().id)
        assertEquals(listOf(13L, 14L), MyPlaces.remove(l, 12).places.map { it.id })
    }

    @Test
    fun `the list shows places without a point first, then by name`() {
        val l = MyPlaces.list(json(answer))!!
        assertEquals(listOf("Baumarkt", "Arbeit", "Zuhause"), MyPlaces.sorted(l.places).map { it.name })
    }

    @Test
    fun `the address hits keep only real points`() {
        val hits = MyPlaces.hits(json("""{"ok":true,"results":[
            {"name":"Bauhaus","address":"Industriestraße 20, Musterstadt","lat":50.1,"lon":8.7,"source":"osm","distanceM":6000},
            {"name":"Nirgends","lat":0,"lon":0},
            {"address":"Kanalweg 3, Musterstadt","lat":50.2,"lon":8.8}
        ]}"""))
        assertEquals(listOf("Bauhaus", "Kanalweg 3"), hits.map { it.name })
        assertEquals(6000, hits[0].distanceM)
    }

    @Test
    fun `near is rounded to about a kilometre`() {
        assertEquals("50.11,8.68", MyPlaces.near(50.110922, 8.682127))
        assertNull(MyPlaces.near(null, 8.0))
        assertNull(MyPlaces.near(0.0, 0.0))
    }
}
