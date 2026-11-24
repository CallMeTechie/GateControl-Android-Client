package com.gatecontrol.android.chat

import com.gatecontrol.android.places.PlaceIcons
import com.gatecontrol.android.places.PlaceOption
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Messenger 1.28 (`features.profilePlaces`): the Ortsnachricht and the live arrival keep the reference of the shared
 * choice (`profile:<id>`, `home`, `place:<id>`) with a copy of the point as the fallback; a point only for the form keeps
 * none. Older Messengers (no feature) see the same bodies as before.
 */
class ChatProfilePlacesTest {

    private val work = PlaceOption("profile:12", "Arbeit", PlaceIcons.WORK, 50.110922, 8.682127, 50, "Hafenstraße 4, Musterstadt")
    private val home = PlaceOption("home", "Zuhause", PlaceIcons.HOME, 50.05, 8.6, 150, familie = true)
    private val point = PlaceOption(null, "Parkplatz", lat = 50.2, lon = 8.2, radiusM = 300)

    @Test
    fun `the feature word`() {
        val f = ChatJson.features(JsonParser.parseString("""{"profilePlaces":true,"placeMessages":true}""").asJsonObject)
        assertTrue(f.profilePlaces)
        assertFalse(ChatJson.features(JsonParser.parseString("""{"placeMessages":true}""").asJsonObject).profilePlaces)
    }

    @Test
    fun `a place of the profile keeps its reference, the radius within the Messenger's 100 to 2000 m`() {
        val p = ChatPlaceMessages.fromOption(work)!!
        assertEquals("profile:12", p.ref)
        assertNull(p.familieId)
        assertEquals(100, p.radiusM)
        val body = ChatPlaceMessages.placeJson(p)
        assertEquals("profile:12", body.get("ref").asString)
        assertFalse(body.has("familieId"))
        assertEquals(50.110922, body.get("lat").asDouble)
    }

    @Test
    fun `a Familie place keeps its id as familieId and as ref, a point only here neither`() {
        val h = ChatPlaceMessages.fromOption(home)!!
        assertEquals("home", h.familieId)
        assertEquals("home", h.ref)
        val pt = ChatPlaceMessages.fromOption(point)!!
        assertNull(pt.ref)
        assertNull(pt.familieId)
        assertFalse(ChatPlaceMessages.placeJson(pt).has("ref"))
        // no point: nothing to watch
        assertNull(ChatPlaceMessages.fromOption(work.copy(lat = null, lon = null)))
        // an older Familie id that is no reference of 1.28 stays only the familieId
        assertNull(ChatPlaceMessages.fromOption(home.copy(ref = "7"))!!.ref)
    }

    @Test
    fun `the answer's ref and missing are read, and the place is found among the choices again`() {
        val o = JsonParser.parseString("""{"ref":"profile:12","source":"profile","familieId":null,"name":"Arbeit","lat":50.110922,"lon":8.682127,"radiusM":200,"missing":true}""").asJsonObject
        val p = ChatPlaceMessages.place(o)!!
        assertEquals("profile:12", p.ref)
        assertTrue(p.missing)
        assertEquals(work, ChatPlaceMessages.optionOf(p, listOf(home, work)))
        // not among them (deleted): its own copy, still with its reference
        val alone = ChatPlaceMessages.optionOf(p, listOf(home))
        assertEquals("profile:12", alone.ref)
        assertEquals(200, alone.radiusM)
        // a Familie place of an older Ortsnachricht by its familieId
        val old = ChatPmPlace("home", "Zuhause", 50.05, 8.6, 150)
        assertEquals(home, ChatPlaceMessages.optionOf(old, listOf(home, work)))
        assertNull(ChatPlaceMessages.place(JsonParser.parseString("""{"ref":"Profile:1","name":"X","lat":1,"lon":1}""").asJsonObject)!!.ref)
    }

    @Test
    fun `the arrival sends the reference next to the copy`() {
        val dest = ChatArrival.destOf(work)
        assertEquals("profile:12", dest.ref)
        assertNull(dest.familiePlaceId)
        val body = ChatJson14.arrivalSendBody(dest, null).getAsJsonObject("dest")
        assertEquals("profile:12", body.get("ref").asString)
        assertEquals("Arbeit", body.get("label").asString)
        val fam = ChatArrival.destOf(home)
        assertEquals("home", fam.familiePlaceId)
        assertEquals("home", ChatJson14.arrivalSendBody(fam, null).getAsJsonObject("dest").get("ref").asString)
        assertFalse(ChatJson14.arrivalSendBody(ChatArrival.destOf(point), null).getAsJsonObject("dest").has("ref"))
    }
}
