package com.gatecontrol.android.familie

import com.gatecontrol.android.familie.places.GeofenceRegistrar
import com.gatecontrol.android.familie.places.RuleFence
import com.gatecontrol.android.familie.places.StoredFence
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Places as triggers of the presence rules (Familie 1.5): the watch list, consent fields, geofence ids. */
class RulePlacesTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    @Test
    fun `the watch list keeps usable places with their transitions`() {
        val w = FamilieJson12.ruleWatch(
            json(
                """{"places":[
                {"id":3,"name":"Schule","lat":52.5,"lon":13.4,"radius":200,"transitions":["enter"]},
                {"id":4,"name":"Zuhause (500 m)","lat":52.51,"lon":13.41,"radius":500,"transitions":["enter","exit"]},
                {"id":5,"name":"no transition","lat":1,"lon":1,"radius":100,"transitions":[]},
                {"id":6,"name":"bad lat","lat":95,"lon":1,"radius":100,"transitions":["exit"]},
                {"id":0,"name":"bad id","lat":1,"lon":1,"radius":100,"transitions":["exit"]},
                {"id":7,"name":"huge","lat":1,"lon":1,"radius":99999,"transitions":["exit"]},
                {"id":3,"name":"dup","lat":1,"lon":1,"radius":100,"transitions":["exit"]}
                ],"version":"a1b2c3d4e5f6"}""",
            ),
        )
        assertEquals(listOf(3, 4, 7), w.places.map { it.placeId })
        assertEquals("a1b2c3d4e5f6", w.version)
        val school = w.places[0]
        assertTrue(school.enter)
        assertFalse(school.exit)
        assertTrue(school.watches(RuleFence.ENTER))
        assertFalse(school.watches(RuleFence.EXIT))
        assertTrue(w.places[1].watches(RuleFence.EXIT))
        assertEquals(RuleFence.MAX_RADIUS, w.places[2].radius)
        assertEquals(RuleWatchEmpty, FamilieJson12.ruleWatch(json("""{"places":[]}""")))
    }

    @Test
    fun `presence answers carry the place consent and the watch version from 1_5 on`() {
        val new = json("""{"members":[],"me":{"share":true,"places":false},"watchVersion":"0f0f0f0f0f0f"}""")
        assertEquals(false, FamilieJson12.myPlacesConsent(new))
        assertEquals("0f0f0f0f0f0f", FamilieJson12.watchVersion(new))
        val old = json("""{"members":[],"me":{"share":true}}""")
        assertNull(FamilieJson12.myPlacesConsent(old))
        assertNull(FamilieJson12.watchVersion(old))
        assertEquals(true, FamilieJson12.myPlacesConsent(json("""{"consent":{"share":false,"places":true}}""")))
    }

    @Test
    fun `reminders and rule places share the geofence budget, reminders first`() {
        assertEquals(9, GeofenceRegistrar.rulePlaceIdOf(GeofenceRegistrar.ruleRequestId(9)))
        assertNull(GeofenceRegistrar.rulePlaceIdOf(GeofenceRegistrar.requestId(9)))
        assertNull(GeofenceRegistrar.placeIdOf(GeofenceRegistrar.ruleRequestId(9)))

        val reminders = (1..88).map { reminder(it) }
        val rules = (1..5).map { RuleFence(it, "R$it", 1.0, 1.0, 200, enter = true, exit = false) }
        val ids = GeofenceRegistrar.combinedIds(reminders, rules)
        assertEquals(GeofenceRegistrar.MAX_FENCES, ids.size)
        assertEquals(GeofenceRegistrar.requestId(1), ids.first())
        assertEquals(listOf(GeofenceRegistrar.ruleRequestId(1), GeofenceRegistrar.ruleRequestId(2)), ids.takeLast(2))

        val none = RuleFence(8, "none", 1.0, 1.0, 200, enter = false, exit = false)
        assertEquals(listOf(GeofenceRegistrar.ruleRequestId(1)), GeofenceRegistrar.combinedIds(emptyList(), listOf(rules[0], none)))
    }

    private fun reminder(id: Int) = StoredFence(id, "P$id", "", 1.0, 1.0, 150, "enter", "note", null, "always")

    private companion object {
        val RuleWatchEmpty = com.gatecontrol.android.familie.places.RuleWatch(emptyList(), null)
    }
}
