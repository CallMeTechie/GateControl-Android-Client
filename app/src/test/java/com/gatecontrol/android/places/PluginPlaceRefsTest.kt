package com.gatecontrol.android.places

import com.gatecontrol.android.familie.FamilieJson
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.PlaceDraft
import com.gatecontrol.android.familie.rules.Rule
import com.gatecontrol.android.familie.rules.RuleJson
import com.gatecontrol.android.familie.rules.RuleLogic
import com.gatecontrol.android.familie.rules.RuleOptionPlace
import com.gatecontrol.android.familie.rules.RuleOptions
import com.gatecontrol.android.familie.rules.RulePlaceChoices
import com.gatecontrol.android.familie.rules.RuleProfilePlace
import com.gatecontrol.android.familie.rules.RuleTrigger
import com.gatecontrol.android.familie.rules.RuleTriggerType
import com.gatecontrol.android.notizen.NPlaces
import com.gatecontrol.android.notizen.NSpot
import com.gatecontrol.android.ui.familie.refRefused
import com.gatecontrol.android.ui.notizen.PlaceChoice
import com.gatecontrol.android.ui.notizen.PlaceChoices
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The places of „Meine Orte“ in the plugins' forms: Familie 1.30 (reminders `ref`, rules `placeId: "profile:<id>"`),
 * Notizen 1.8 (`profilePlaces`: the ids are the profile's) — and the way back to the old behaviour where a plugin does
 * not know the reference yet. Made-up places only.
 */
class PluginPlaceRefsTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    // ─── Familie reminders ──────────────────────────────────────────

    private val draft = PlaceDraft(title = "Brot holen", lat = 50.11, lon = 8.68, radius = 200, ref = "profile:12")

    @Test
    fun `a reminder follows a place of the profile and keeps the copy of its point`() {
        val body = FamilieJson.placeBody(draft)
        assertEquals("profile:12", body.get("ref").asString)
        assertEquals(50.11, body.get("lat").asDouble)
        // a point by hand unlinks it
        assertTrue(FamilieJson.placeBody(draft.copy(ref = null, unlink = true)).get("ref").isJsonNull)
        // nothing to do with a reference: no key (as an older plugin knows it)
        assertFalse(FamilieJson.placeBody(draft.copy(ref = null)).has("ref"))
        assertFalse(FamilieJson.placeBody(FamilieJson.withoutRef(draft)).has("ref"))
    }

    @Test
    fun `the answer says whether the plugin knows references and whether the place is gone`() {
        val base = """"id":5,"ownerId":1,"targetUserId":1,"title":"Brot holen","lat":50.11,"lon":8.68,"radius":200,"trigger":"enter","action":"note""""
        val p = FamilieJson.place(json("{$base,\"ref\":\"profile:12\",\"missing\":true}"))!!
        assertEquals("profile:12", p.ref)
        assertTrue(p.missing)
        assertTrue(p.refKnown)
        val own = FamilieJson.place(json("{$base,\"ref\":null}"))!!
        assertNull(own.ref)
        assertTrue(own.refKnown)
        val older = FamilieJson.place(json("{$base}"))!!
        assertFalse(older.refKnown)
        assertNull(FamilieJson.place(json("{$base,\"ref\":\"home\"}"))!!.ref)
    }

    @Test
    fun `an older Familie that refuses the reference gets the reminder without it`() {
        assertTrue(refRefused(FamilieResult.Failed("invalid", 400, field = "ref"), draft))
        assertTrue(refRefused(FamilieResult.Failed("invalid", 400), draft))
        // another field is wrong: not the reference's fault
        assertFalse(refRefused(FamilieResult.Failed("invalid", 400, field = "title"), draft))
        assertFalse(refRefused(FamilieResult.Failed("forbidden", 403), draft))
        assertFalse(refRefused(FamilieResult.Failed("invalid", 400, field = "ref"), draft.copy(ref = null)))
    }

    // ─── Familie rules ──────────────────────────────────────────────

    private val options = RuleJson.options(
        json(
            """{"triggers":["place_enter"],"places":[{"id":3,"name":"Grundschule"}],
               "profilePlaces":[{"ref":"profile:12","name":"Arbeit","icon":"work","radius":200,"ownerId":7,"ownerName":"Kim","own":false},
                                {"ref":"profile:13","name":"Zuhause","icon":"home","radius":150,"ownerId":1,"ownerName":"Ich","own":true},
                                {"ref":"home","name":"Falsch"}]}""",
        ),
    )

    @Test
    fun `rule options name the shared places of the profile, an older plugin none`() {
        assertEquals(listOf("profile:12", "profile:13"), options.profilePlaces?.map { it.ref })
        assertEquals("Kim", options.profilePlaces?.first()?.ownerName)
        assertNull(RuleJson.options(json("""{"places":[{"id":3,"name":"Grundschule"}]}""")).profilePlaces)
    }

    @Test
    fun `the choice of a rule - rule places blue, shared places with their owner, all without a point on the phone`() {
        val choices = RulePlaceChoices.options(options)
        assertEquals(listOf("place:3", "profile:12", "profile:13"), choices.map { it.ref })
        assertTrue(choices[0].familie && !choices[1].familie)
        assertEquals("Kim", choices[1].address)
        assertNull(choices[2].address) // mine
        assertTrue(choices.all { it.pickable && !it.hasPoint })
        // they are chips although the phone knows no point
        assertEquals(3, PlacePicking.chips(choices, null, null).size)
    }

    @Test
    fun `a rule at a place of the profile sends the reference as placeId, a rule place its id`() {
        val choices = RulePlaceChoices.options(options)
        val t0 = RuleTrigger(RuleTriggerType.PLACE_ENTER, placeId = 3, placeName = "Grundschule")
        assertEquals("place:3", RulePlaceChoices.selected(t0, choices)?.ref)
        val t1 = RulePlaceChoices.apply(t0, choices[1])
        assertEquals("profile:12", t1.placeRef)
        assertNull(t1.placeId)
        assertTrue(RuleLogic.valid(Rule(id = null, title = "Kim ist da", trigger = t1, actions = listOf(com.gatecontrol.android.familie.rules.RuleAction("notify")))))
        assertEquals("profile:12", RuleJson.body(Rule(id = null, title = "Kim ist da", trigger = t1))["trigger"].asJsonObject.get("placeId").asString)
        val t2 = RulePlaceChoices.apply(t1, choices[0])
        assertEquals(3, t2.placeId)
        assertNull(t2.placeRef)
        assertEquals(3, RuleJson.body(Rule(id = null, title = "x", trigger = t2))["trigger"].asJsonObject.get("placeId").asInt)
    }

    @Test
    fun `a rule's answer carries placeRef and placeMissing - editing keeps the reference`() {
        val r = RuleJson.rule(
            json("""{"id":9,"title":"Kim ist da","scope":"house","active":true,"trigger":{"type":"place_enter","placeId":41,"placeRef":"profile:12","placeMissing":true,"userId":null},"actions":[]}"""),
        )!!
        assertEquals("profile:12", r.trigger.placeRef)
        assertTrue(r.trigger.placeMissing)
        assertEquals("profile:12", RulePlaceChoices.selected(r.trigger, RulePlaceChoices.options(options))?.ref)
        assertEquals("profile:12", RuleJson.body(r)["trigger"].asJsonObject.get("placeId").asString)
    }

    // ─── Notizen ────────────────────────────────────────────────────

    @Test
    fun `Notizen 1_8 deletes a place of the profile at once only when nothing but its tasks use it`() {
        val tasks = PlaceUse("gatecontrol-notizen", "Notizen", "task", 2)
        val message = PlaceUse("gatecontrol-messenger", "Messenger", "location_message", 1)
        assertTrue(MyPlaces.onlyBy(listOf(tasks), "gatecontrol-notizen"))
        assertFalse(MyPlaces.onlyBy(listOf(tasks, message), "gatecontrol-notizen"))
        assertFalse(MyPlaces.onlyBy(emptyList(), "gatecontrol-notizen"))
    }

    private val mine = listOf(
        NSpot("Arbeit", "Hafenstraße 4, Musterstadt", 50.11, 8.68, 200, placeId = 12, named = true, uses = 1),
        NSpot("Baumarkt", "Industriestraße 20, Musterstadt", 50.2, 8.7, 120, placeId = 14, named = true, uses = 5),
        NSpot("Zuhause", "Lindenweg 12, Musterstadt", 50.05, 8.6, 150, placeId = 13, named = true),
        NSpot("Eisdiele", null, 50.3, 8.8, 100, placeId = 15, named = true),
    )
    private val family = listOf(NSpot("Zuhause (Familie)", null, 50.05, 8.6, 150, familyId = "home"))

    @Test
    fun `Notizen 1_8 - the dialog shows three of my places and „Alle Orte…“, 1_7 all of them`() {
        val c = PlaceChoice(open = true, places = NPlaces(mine, family), selected = mine[3], profile = true)
        assertEquals(listOf("Eisdiele", "Baumarkt", "Arbeit"), PlaceChoices.mineShort(c).map { it.name })
        assertEquals(5, PlaceChoices.mineShort(c.copy(profile = false)).size)
    }

    @Test
    fun `Notizen 1_8 - a place of the shared choice becomes a place of the dialog`() {
        val c = PlaceChoice(open = true, places = NPlaces(mine, family), profile = true)
        val options = PlaceChoices.sheetOptions(c)
        assertEquals(listOf("nfam:home"), options.map { it.ref })
        assertTrue(options.single().familie)
        // one of „Meine Orte“ by its profile id — what the plugin 1.8 takes as `placeId`
        val spot = PlaceChoices.spotOf(PlaceOption("profile:20", "Schule", PlaceIcons.SCHOOL, 50.4, 8.9, 150), c)!!
        assertEquals(20L, spot.placeId)
        // a family place as the family's
        assertEquals("home", PlaceChoices.spotOf(options.single(), c)?.familyId)
        // a point only for this task: no id
        val point = PlaceChoices.spotOf(PlaceOption(null, "Parkplatz", lat = 50.5, lon = 8.1, radiusM = 150), c)!!
        assertNull(point.placeId)
        // plugin 1.7: never a profile id as its own place id
        assertNull(PlaceChoices.spotOf(PlaceOption("profile:20", "Schule", lat = 50.4, lon = 8.9), c.copy(profile = false))?.placeId)
    }
}
