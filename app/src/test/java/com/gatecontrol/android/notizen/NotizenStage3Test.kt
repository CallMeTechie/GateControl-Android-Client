package com.gatecontrol.android.notizen

import com.gatecontrol.android.familie.places.GeofenceBudget
import com.gatecontrol.android.familie.places.GeofenceRegistrar
import com.gatecontrol.android.notify.model.PushMessage
import com.gatecontrol.android.ui.notizen.PlaceChoice
import com.gatecontrol.android.ui.notizen.PlaceChoices
import com.gatecontrol.android.ui.notizen.PlacePicked
import com.gatecontrol.android.ui.notizen.QuickPlace
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * „Aufgaben und Erinnerungen“, stage 3 (plugin 1.5.0 / Familie 1.22.0, the apps' contract): the words in `features`, the
 * place of a task and how it is written, the place of the typed line, the search (the position rounded on the phone,
 * the hits measured and sorted again), the geofences and their budget beside the Familie plugin's, what the phone
 * decides itself when a place fires, the consent — and that all stays as it was with an older plugin.
 */
class NotizenStage3Test {
    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private fun me(features: String, extra: String = "", prefs: String = """{"area":"private"}""") = NotizenJson.me(json("""{"ok":true,"user":{"id":1,"name":"Anna Berg"},"lang":"de","today":"2026-11-14","time":"10:14",
        "timezone":"Europe/Berlin","features":[$features],"prefs":$prefs,"sharing":"all","vaults":false,"imports":{},"attachMB":25,"readOnly":false$extra}"""))!!

    private val placesOn = ""","places":{"search":{"state":"on","enabled":true,"mine":true,"service":"osm","radiusKm":15,"decimals":2,
        "attribution":{"text":"© OpenStreetMap","url":"https://www.openstreetmap.org/copyright"}},
        "nearby":true,"quiet":{"from":"21:00","to":"07:00"},"perTask":5,"radius":{"min":50,"max":2000,"default":150},"family":true}"""

    private val placeJson = """{"mode":"place","id":1,"name":"Baumarkt Nord","address":"Industriestraße 4, Berlin","lat":52.539,"lon":13.424,"radius":150,
        "ref":"osm:W101","own":false,"trigger":"enter","key":"kind:diy","label":"Baumarkt","by":1,"consent":"accepted","watch":true,"nearby":true,"skipped":0,"later":null}"""

    private fun taskJson(place: String?) = """{"id":1,"listId":1,"title":"Schrauben kaufen","done":false,"position":1,"updatedAt":5${place?.let { ""","place":$it""" } ?: ""}}"""

    // ── recognising the plugin ──────────────────────────────

    @Test
    fun `places and the search are words in features, never a version`() {
        val old = me(""""tasks","shoppingLists","taskList","reminderActions"""")
        assertFalse(old.taskPlaces)
        assertFalse(old.placeSearch)
        assertNull(old.places)
        val new = me(""""tasks","places","placeSearch"""", placesOn)
        assertTrue(new.taskPlaces)
        assertTrue(new.placeSearch)
        assertEquals(2, new.places!!.decimals)
        assertEquals(15, new.places!!.radiusKm)
        assertEquals(NQuiet("21:00", "07:00"), new.places!!.quiet)
        assertEquals("© OpenStreetMap", new.places!!.attribution!!.text)
        assertTrue(new.places!!.family)
        // the route alone is not the search: the administrator's or my own switch, or a service not set up
        for (state in listOf("off", "unavailable")) {
            assertFalse(me(""""places","placeSearch"""", placesOn.replace(""""state":"on"""", """"state":"$state"""")).placeSearch, state)
        }
        assertFalse(me(""""places"""", placesOn).placeSearch, "no route")
        assertFalse(me(""""placeSearch"""", placesOn).placeSearch, "the search needs the places")
        assertFalse(me(""""places","placeSearch"""").placeSearch, "no me.places")
    }

    @Test
    fun `an older plugin - no place at the task, no prefs, nothing to register`() {
        val t = NotizenJson.task(json(taskJson(null)))!!
        assertNull(t.place)
        assertEquals(NPlacePrefs(), me(""""tasks"""").prefs.places)
        assertNull(NotizenPlacesJson.suggest(null))
        assertNull(NotizenPlacesJson.parsed(json("""{"ok":true,"parsed":{"title":"Milch"},"attach":null}""")).place)
        // an answer without fences (or one that is not an answer at all) gives nothing to watch
        assertTrue(NotizenPlacesJson.watch(json("""{"ok":false,"code":"not_found"}""")).fences.isEmpty())
        assertEquals(PlaceState(), PlaceState.fromJson(null))
    }

    // ── the place of a task ─────────────────────────────────

    @Test
    fun `the place of a task - every field of the contract`() {
        val p = NotizenJson.task(json(taskJson(placeJson)))!!.place!!
        assertEquals(NPlace.PLACE, p.mode)
        assertFalse(p.any)
        assertEquals(1L, p.id)
        assertEquals("Baumarkt Nord", p.name)
        assertEquals("Industriestraße 4, Berlin", p.address)
        assertEquals(52.539, p.lat)
        assertEquals(13.424, p.lon)
        assertEquals(150, p.radius)
        assertEquals("osm:W101", p.ref)
        assertFalse(p.own)
        assertEquals(NPlace.ENTER, p.trigger)
        assertEquals("kind:diy", p.key)
        assertEquals("Baumarkt", p.label)
        assertEquals(1L, p.by)
        assertEquals(NPlace.ACCEPTED, p.consent)
        assertTrue(p.watch)
        assertTrue(p.nearby)
        assertEquals(0, p.skipped)
        assertNull(p.later)
    }

    @Test
    fun `Jeder Lidl has no coordinates, a place without them is none`() {
        val any = NotizenPlacesJson.place(json("""{"mode":"any","id":null,"name":"Jeder Lidl","lat":null,"lon":null,"radius":null,"ref":null,"own":false,
            "trigger":"enter","key":"name:lidl","label":"Lidl","by":1,"consent":"pending","watch":false,"nearby":false,"skipped":2,"later":"2026-11-08T13:00:00+01:00"}"""))!!
        assertTrue(any.any)
        assertNull(any.lat)
        assertEquals(NPlace.PENDING, any.consent)
        assertEquals(2, any.skipped)
        assertEquals("2026-11-08T13:00:00+01:00", any.later)
        assertNull(NotizenPlacesJson.place(json("""{"mode":"place","name":"Oma"}""")))
        assertNull(NotizenPlacesJson.place(json("""{"mode":"place","lat":52.4,"lon":13.2}""")), "no name")
        // an unknown trigger or consent falls back to what is safe to show
        val odd = NotizenPlacesJson.place(json("""{"mode":"place","name":"Oma","lat":52.4,"lon":13.2,"trigger":"x","consent":"y","ref":"familie:home"}"""))!!
        assertEquals(NPlace.ENTER, odd.trigger)
        assertEquals(NPlace.ACCEPTED, odd.consent)
        assertEquals("familie:home", odd.ref)
        assertFalse(odd.watch, "without the word the phone does not watch")
    }

    @Test
    fun `writing the place - each form the server takes`() {
        val hit = NSpot("Baumarkt Nord", "Industriestraße 4, Berlin", 52.539, 13.424, ref = "osm:W101")
        assertEquals(
            json("""{"ref":"osm:W101","name":"Baumarkt Nord","address":"Industriestraße 4, Berlin","lat":52.539,"lon":13.424,"key":"kind:diy","label":"Baumarkt","trigger":"enter","remember":true}"""),
            NotizenPlacesJson.placeArg(hit, false, "kind:diy", "Baumarkt", "enter", remember = true),
        )
        assertEquals(json("""{"placeId":2,"trigger":"exit"}"""), NotizenPlacesJson.placeArg(NSpot("Oma", lat = 52.4, lon = 13.2, placeId = 2), false, null, null, "exit"))
        // a place of the family: no key, nothing to remember
        assertEquals(json("""{"familyId":"home","trigger":"enter"}"""), NotizenPlacesJson.placeArg(NSpot("Zuhause", lat = 52.5, lon = 13.4, familyId = "home"), false, null, null, "enter", remember = true))
        // a point of the map: name and coordinates, no ref
        assertEquals(json("""{"name":"Garten","lat":52.1,"lon":13.1,"trigger":"enter"}"""), NotizenPlacesJson.placeArg(NSpot("Garten", lat = 52.1, lon = 13.1), false, null, null, "enter"))
        assertEquals(json("""{"any":true,"key":"name:lidl","label":"Lidl","trigger":"enter"}"""), NotizenPlacesJson.placeArg(null, true, "name:lidl", "Lidl", "enter", remember = true))
        assertNull(NotizenPlacesJson.placeArg(null, true, null, "Lidl", "enter"), "Jeder … needs a key")
        assertNull(NotizenPlacesJson.placeArg(null, false, "kind:diy", "Baumarkt", "enter"))
        assertEquals(json("""{"trigger":"exit"}"""), NotizenPlacesJson.triggerArg("exit"))
        assertEquals(json("""{"trigger":"enter"}"""), NotizenPlacesJson.triggerArg("anything"))
    }

    // ── own and remembered places ───────────────────────────

    @Test
    fun `Meine Orte, the places of the family and what is remembered`() {
        val p = NotizenPlacesJson.places(json("""{"ok":true,
            "places":[{"id":1,"name":"Baumarkt Nord","address":"Industriestraße 4, Berlin","lat":52.539,"lon":13.424,"radius":150,"named":false,"ref":"osm:W101","family":false,
                       "favFor":[{"key":"kind:diy","label":"Baumarkt"}],"updatedAt":1794128400000},
                      {"id":2,"name":"Oma","address":"Gartenweg 5","lat":52.4,"lon":13.2,"radius":150,"named":true,"ref":null,"family":false,"favFor":[],"updatedAt":1794128400000},
                      {"name":"ohne Nummer","lat":1,"lon":1}],
            "family":[{"familyId":"home","name":"Zuhause","lat":52.5203,"lon":13.4051,"radius":150},{"familyId":"place:3","name":"Schule","lat":52.5301,"lon":13.4101,"radius":300}],
            "favs":[{"key":"kind:diy","label":"Baumarkt","placeId":1,"uses":1}]}"""))
        assertEquals(listOf(1L, 2L), p.places.map { it.placeId })
        assertEquals(listOf(NPlaceKey("kind:diy", "Baumarkt")), p.places[0].favFor)
        assertTrue(p.places[1].named)
        assertEquals(listOf("home", "place:3"), p.family.map { it.familyId })
        assertTrue(p.family.all { it.family && it.placeId == null })
        assertEquals(listOf(NFav("kind:diy", "Baumarkt", 1, 1)), p.favs)
        // an older Familie: no places of the family
        assertTrue(NotizenPlacesJson.places(json("""{"ok":true,"places":[],"family":[],"favs":[]}""")).family.isEmpty())
        assertEquals(json("""{"key":"kind:diy","placeId":null}"""), NotizenPlacesJson.favBody("kind:diy", null))
        assertEquals(json("""{"name":"Garten","lat":52.1,"lon":13.1}"""), NotizenPlacesJson.newPlaceBody(" Garten ", 52.1, 13.1, null, null, null))
    }

    // ── the place of the typed line ─────────────────────────

    private val kindSuggest = """{"via":"kind","word":"beim Baumarkt","query":"Baumarkt","key":"kind:diy","label":"Baumarkt","title":"Schrauben kaufen","certain":true,
        "trigger":"enter","place":null,"uses":0,"options":[],"search":"on"}"""

    @Test
    fun `POST parse - the place beside the list, every field`() {
        val v = NotizenPlacesJson.parsed(json("""{"ok":true,"parsed":{"title":"beim Baumarkt Schrauben kaufen"},"attach":null,"place":$kindSuggest}"""))
        assertEquals("beim Baumarkt Schrauben kaufen", v.title)
        val s = v.place!!
        assertEquals("kind", s.via)
        assertEquals("beim Baumarkt", s.word)
        assertEquals("Baumarkt", s.query)
        assertEquals("kind:diy", s.key)
        assertEquals("Baumarkt", s.label)
        assertEquals("Schrauben kaufen", s.title)
        assertTrue(s.certain)
        assertEquals(NPlace.ENTER, s.trigger)
        assertNull(s.place)
        assertEquals("on", s.search)
        // the remembered place is decided; a place of the family has no id of its own
        val remembered = NotizenPlacesJson.suggest(json("""{"via":"kind","key":"kind:diy","label":"Baumarkt","title":"Dübel holen","certain":true,
            "place":{"id":1,"name":"Baumarkt Nord","address":"Industriestraße 4, Berlin","lat":52.539,"lon":13.424,"radius":150,"named":false,"ref":"osm:W101","family":false,"uses":1},
            "uses":1,"options":[{"id":1,"name":"Baumarkt Nord","lat":52.539,"lon":13.424}],"search":"on"}"""))!!
        assertEquals(1L, remembered.place!!.placeId)
        assertEquals(1, remembered.uses)
        assertEquals(1, remembered.options.size)
        val home = NotizenPlacesJson.suggest(json("""{"via":"family","word":"zu Hause","label":"Zuhause","title":"Blumen gießen","certain":true,
            "place":{"familyId":"home","name":"Zuhause","address":"","lat":52.5203,"lon":13.4051,"radius":150,"family":true},"search":"off"}"""))!!
        assertEquals("home", home.place!!.familyId)
        assertNull(home.place!!.placeId)
        assertNull(home.key)
        assertEquals("off", home.search)
        assertNull(NotizenPlacesJson.suggest(json("""{"via":"something","label":"x"}""")), "an unknown kind of suggestion is none")
    }

    @Test
    fun `the quick entry - a decided place is taken, a kind asks, an uncertain one only suggests`() {
        val kind = NotizenPlacesJson.suggest(json(kindSuggest))!!
        assertNull(QuickPlace.effective(kind, null, false, null), "to choose first")
        assertTrue(QuickPlace.asks(kind, null, false))
        val oma = NotizenPlacesJson.suggest(json("""{"via":"own","word":"bei Oma","query":"Oma","key":null,"label":"Oma","title":"Leiter zurückgeben","certain":true,"trigger":"enter",
            "place":{"id":2,"name":"Oma","address":"Gartenweg 5","lat":52.4,"lon":13.2,"named":true},"uses":0,"options":[],"search":"on"}"""))!!
        val taken = QuickPlace.effective(oma, null, false, null)!!
        assertEquals("Oma", taken.name)
        assertEquals(json("""{"placeId":2,"trigger":"enter"}"""), taken.arg)
        assertFalse(QuickPlace.asks(oma, null, false))
        // the chip „Beim Ankommen“ turned over
        assertEquals(json("""{"placeId":2,"trigger":"exit"}"""), QuickPlace.effective(oma, null, false, NPlace.EXIT)!!.arg)
        // the remembered place of a kind goes with its key — and says how often it was chosen
        val baker = oma.copy(via = "kind", key = "kind:bakery", label = "Bäcker", uses = 6, place = oma.place!!.copy(name = "Bäckerei am Markt"))
        assertEquals(json("""{"placeId":2,"key":"kind:bakery","label":"Bäcker","trigger":"enter"}"""), QuickPlace.effective(baker, null, false, null)!!.arg)
        assertEquals("Bäcker" to 6, QuickPlace.usual(baker, null, false))
        assertNull(QuickPlace.usual(oma, null, false), "an own place is not a usual one of a kind")
        // „zum Bauhaus“: not surely a place — shown as a suggestion, never put in
        val unsure = baker.copy(certain = false)
        assertNull(QuickPlace.effective(unsure, null, false, null))
        assertTrue(QuickPlace.asks(unsure, null, false))
        // taken away: no place, whatever was chosen; and a choice does not outlive the place in the line
        val pick = PlacePicked(json("""{"ref":"osm:W101","name":"Baumarkt Nord","lat":52.539,"lon":13.424,"key":"kind:diy","trigger":"enter"}"""), "Baumarkt Nord", NPlace.ENTER)
        assertEquals(pick, QuickPlace.effective(kind, pick, false, null))
        assertEquals("exit", QuickPlace.effective(kind, pick, false, NPlace.EXIT)!!.arg.get("trigger").asString)
        assertEquals("enter", pick.arg.get("trigger").asString, "the choice itself is not changed")
        assertNull(QuickPlace.effective(kind, pick, true, null))
        assertNull(QuickPlace.effective(null, pick, false, null))
        assertFalse(QuickPlace.asks(null, null, false))
    }

    @Test
    fun `the title leaves the words of the place - but never a single word`() {
        val s = NotizenPlacesJson.suggest(json(kindSuggest))!!
        // the server read the same line: its title
        assertEquals("Schrauben kaufen", PlaceLogic.titleWithout("beim Baumarkt Schrauben kaufen", s, "beim Baumarkt Schrauben kaufen"))
        // the phone read a date out of the line the server did not (or the other way round): the words are cut out here
        assertEquals("Schrauben kaufen", PlaceLogic.titleWithout("beim Baumarkt Schrauben kaufen", s, "beim Baumarkt Schrauben kaufen morgen"))
        assertEquals("Schrauben kaufen", PlaceLogic.titleWithout("Schrauben beim Baumarkt kaufen", s, null))
        val lidl = s.copy(word = "bei Lidl", title = "bei Lidl einkaufen")
        assertEquals("bei Lidl einkaufen", PlaceLogic.titleWithout("bei Lidl einkaufen", lidl, null), "one word left: the title stays whole")
        assertEquals("Milch holen", PlaceLogic.titleWithout("Milch holen", s, null), "the words are not in the title")
        assertEquals("Milch holen", PlaceLogic.titleWithout("Milch holen", null, null))
        assertEquals(0..12, PlaceLogic.wordRange("beim Baumarkt Schrauben kaufen", "beim Baumarkt"))
        assertEquals(10..22, PlaceLogic.wordRange("Schrauben BEIM BAUMARKT kaufen", "beim Baumarkt"))
        assertNull(PlaceLogic.wordRange("Schrauben kaufen", "beim Baumarkt"))
        assertNull(PlaceLogic.wordRange("Schrauben kaufen", null))
    }

    // ── the search ──────────────────────────────────────────

    @Test
    fun `a position leaves the phone only rounded`() {
        assertEquals(52.52 to 13.41, PlaceLogic.near(52.5200066, 13.4094, 2))
        assertEquals(52.52 to 13.4, PlaceLogic.near(52.5249, 13.3951, 2))
        assertEquals(-33.87 to 151.21, PlaceLogic.near(-33.8688, 151.2093, 2))
        assertEquals(53.0 to 13.0, PlaceLogic.near(52.5200066, 13.4094, 0))
        assertEquals(52.5 to 13.4, PlaceLogic.near(52.5200066, 13.4094, 1))
        assertNull(PlaceLogic.near(null, 13.4, 2))
        assertNull(PlaceLogic.near(91.0, 13.4, 2))
        // what is sent is the rounded pair and nothing finer
        val body = NotizenPlacesJson.searchBody("kind:diy", null, PlaceLogic.near(52.5200066, 13.4094, 2))
        assertEquals(json("""{"key":"kind:diy","near":{"lat":52.52,"lon":13.41}}"""), body)
        assertEquals(json("""{"q":"Ringstraße 21"}"""), NotizenPlacesJson.searchBody(null, " Ringstraße 21 ", null))
        // no position: near one of my places, as the portal searches
        assertEquals(json("""{"key":"kind:diy","nearFamilyId":"home"}"""), NotizenPlacesJson.searchBody("kind:diy", null, null, NSpot("Zuhause", lat = 52.5, lon = 13.4, familyId = "home")))
        assertEquals(json("""{"key":"kind:diy","nearPlaceId":2}"""), NotizenPlacesJson.searchBody("kind:diy", null, null, NSpot("Oma", lat = 52.4, lon = 13.2, placeId = 2)))
        assertEquals(json("""{"near":{"lat":52.52,"lon":13.41},"budget":40}"""), NotizenPlacesJson.watchBody(52.52 to 13.41, 40))
        assertEquals(json("""{"budget":0}"""), NotizenPlacesJson.watchBody(null, -3))
    }

    private val searchJson = """{"ok":true,"key":"kind:diy","label":"Baumarkt","kind":"kind","radiusKm":15,"cell":{"lat":52.52,"lon":13.41},
        "results":[
          {"ref":"osm:W103","name":"Baumarkt Süd","address":"Am Hafen 2, Berlin","lat":52.46,"lon":13.40,"distanceM":6700,"minutes":17,"placeId":7,"fav":true,"uses":4},
          {"ref":"osm:W101","name":"Baumarkt Nord","address":"Industriestraße 4, Berlin","lat":52.539,"lon":13.424,"distanceM":2315,"minutes":6,"placeId":null,"fav":false,"uses":2},
          {"ref":"osm:W102","name":"Baumarkt am Ring","address":"Ringstraße 21, Berlin","lat":52.501,"lon":13.362,"distanceM":3875,"minutes":10,"placeId":null,"fav":false,"uses":0}],
        "any":true,"approx":true,"attribution":{"text":"© OpenStreetMap","url":"https://www.openstreetmap.org/copyright"}}"""

    @Test
    fun `the hits - parsed, measured from the exact position on the phone and sorted again`() {
        val r = NotizenPlacesJson.search(json(searchJson))
        assertEquals("kind:diy", r.key)
        assertEquals("Baumarkt", r.label)
        assertEquals(15, r.radiusKm)
        assertTrue(r.any)
        assertEquals("https://www.openstreetmap.org/copyright", r.attribution!!.url)
        assertEquals(listOf(7L, null, null), r.results.map { it.placeId })
        assertEquals(listOf(6700, 2315, 3875), r.results.map { it.distanceM })
        // without a position: as the server sent them
        assertEquals(r.results, PlaceLogic.measured(r.results, null, null))
        // the phone stands right at „am Ring“: the remembered one stays first, the others by the real distance
        val m = PlaceLogic.measured(r.results, 52.5012, 13.3625)
        assertEquals(listOf("Baumarkt Süd", "Baumarkt am Ring", "Baumarkt Nord"), m.map { it.name })
        assertTrue(m[1].distanceM!! < 100)
        assertEquals(1, m[1].minutes, "never under a minute")
        assertEquals(PlaceLogic.minutes(m[2].distanceM!!), m[2].minutes)
        // 30 km/h over 1.3 times the distance
        assertEquals(6, PlaceLogic.minutes(2315))
        assertEquals(10, PlaceLogic.minutes(3875))
        assertEquals(1, PlaceLogic.minutes(0))
        assertEquals("300 m", PlaceLogic.distanceText(300, "de"))
        assertEquals("2,4 km", PlaceLogic.distanceText(2400, "de"))
        assertEquals("2.4 km", PlaceLogic.distanceText(2400, "en"))
        // Brandenburger Tor → Alexanderplatz, about two and a half kilometres
        assertTrue(PlaceLogic.distanceM(52.5163, 13.3777, 52.5219, 13.4132) in 2400..2500)
        // an address has neither distance nor key; nothing found is an empty list, not a failure
        val address = NotizenPlacesJson.search(json("""{"ok":true,"key":null,"kind":"address","results":[{"ref":"osm:N5","name":"Ringstraße 21","lat":52.5,"lon":13.3,"distanceM":null,"minutes":null}],"any":false}"""))
        assertNull(address.key)
        assertNull(address.results[0].distanceM)
        assertFalse(address.any)
        assertTrue(NotizenPlacesJson.search(json("""{"ok":true,"results":[]}""")).results.isEmpty())
    }

    @Test
    fun `the choice - what is offered, what is sent`() {
        val r = NotizenPlacesJson.search(json(searchJson))
        val own = NSpot("Oma", "Gartenweg 5", 52.4, 13.2, placeId = 2, named = true)
        val south = NSpot("Baumarkt Süd", lat = 52.46, lon = 13.40, placeId = 7, favFor = listOf(NPlaceKey("kind:diy", "Baumarkt")))
        val home = NSpot("Zuhause", lat = 52.52, lon = 13.40, familyId = "home")
        val c = PlaceChoice(open = true, label = "Baumarkt", key = "kind:diy", query = "Baumarkt", searchState = "on", results = r.results, anyOffered = true, places = NPlaces(listOf(south, own), listOf(home)))
        // „Meine Orte“: the family's and my own — not the one that is among the hits already
        assertEquals(listOf("Zuhause", "Oma"), PlaceChoices.mine(c).map { it.name })
        assertNull(PlaceChoices.picked(c) { "Jeder $it" }, "nothing chosen")
        // a hit: its fields unchanged, the key, and remembered when asked
        val hit = c.copy(selected = r.results[1], remember = true)
        assertTrue(PlaceChoices.canRemember(hit))
        assertEquals(
            json("""{"ref":"osm:W101","name":"Baumarkt Nord","address":"Industriestraße 4, Berlin","lat":52.539,"lon":13.424,"key":"kind:diy","label":"Baumarkt","trigger":"enter","remember":true}"""),
            PlaceChoices.picked(hit) { "" }!!.arg,
        )
        // a hit that is one of my places already goes by its number; the remembered one cannot be remembered again
        val mine = c.copy(selected = r.results[0], trigger = NPlace.EXIT, remember = true)
        assertFalse(PlaceChoices.canRemember(mine))
        assertEquals(json("""{"placeId":7,"key":"kind:diy","label":"Baumarkt","trigger":"exit"}"""), PlaceChoices.picked(mine) { "" }!!.arg)
        // „Oma“ picked for „Baumarkt“ is not a Baumarkt: no key, so no hint at other DIY stores
        assertEquals(json("""{"placeId":2,"trigger":"enter"}"""), PlaceChoices.picked(c.copy(selected = own)) { "" }!!.arg)
        assertEquals(json("""{"familyId":"home","trigger":"enter"}"""), PlaceChoices.picked(c.copy(selected = home, remember = true)) { "" }!!.arg)
        assertFalse(PlaceChoices.canRemember(c.copy(selected = home)))
        // „Jeder Baumarkt“
        val any = PlaceChoices.picked(c.copy(any = true)) { "Jeder $it" }!!
        assertEquals("Jeder Baumarkt", any.name)
        assertEquals(json("""{"any":true,"key":"kind:diy","label":"Baumarkt","trigger":"enter"}"""), any.arg)
        // the place the task has, only its trigger changed: only the trigger is sent
        val cur = PlaceChoices.currentSpot(NotizenPlacesJson.place(json(placeJson)))!!
        val kept = c.copy(point = cur, selected = cur, trigger = NPlace.EXIT, results = null)
        assertEquals(json("""{"trigger":"exit"}"""), PlaceChoices.picked(kept) { "" }!!.arg)
        assertEquals("Baumarkt Nord", PlaceChoices.mine(kept).first().name)
        assertNull(PlaceChoices.currentSpot(NotizenPlacesJson.place(json("""{"mode":"any","name":"Jeder Lidl","key":"name:lidl"}"""))))
        // the search asks by the key while the word is the kind's own, else by what was typed
        assertEquals("kind:diy", PlaceChoices.byKey(c))
        assertEquals("kind:diy", PlaceChoices.byKey(c.copy(query = " baumarkt ")))
        assertNull(PlaceChoices.byKey(c.copy(query = "Hornbach")))
        // without a position: near „Zuhause“, else another place of mine
        assertEquals("home", PlaceChoices.nearSpot(c.places)!!.familyId)
        assertEquals(2L, PlaceChoices.nearSpot(NPlaces(listOf(south, own)))!!.placeId)
        assertNull(PlaceChoices.nearSpot(NPlaces()))
    }

    // ── geofences ───────────────────────────────────────────

    private val watchJson = """{"ok":true,"enabled":true,"max":40,"perTask":5,"refreshM":3000,"cell":{"lat":52.52,"lon":13.41},
        "nearby":true,"quiet":{"from":"21:00","to":"07:00"},"search":"on","dropped":0,
        "fences":[
          {"id":"osm:W101","ref":"osm:W101","lat":52.539,"lon":13.424,"radius":150,"name":"Baumarkt Nord","address":"Industriestraße 4, Berlin","transitions":["enter"],
           "tasks":[{"taskId":1,"listId":1,"area":"private","title":"Schrauben kaufen","kind":"place","trigger":"enter","planned":null,"tag":"place:1:osm:W101","important":false,"due":null,
                     "notification":{"title":"Schrauben kaufen","body":"Angekommen: Baumarkt Nord · Aufgaben","collapseKey":"task:1",
                                     "actions":[{"id":"done","label":"Erledigt"},{"id":"later","label":"Später"}]},"attachedList":null}]},
          {"id":"osm:W102","ref":"osm:W102","lat":52.501,"lon":13.362,"radius":300,"name":"Baumarkt am Ring","address":"Ringstraße 21, Berlin","transitions":["enter"],
           "tasks":[{"taskId":1,"listId":1,"area":"private","title":"Schrauben kaufen","kind":"nearby","trigger":"enter","planned":"Baumarkt Nord","tag":"place:1:osm:W102","important":false,"due":null,
                     "notification":{"title":"Baumarkt am Ring ist gleich hier","body":"Schrauben kaufen · geplant war Baumarkt Nord","collapseKey":"task:1",
                                     "actions":[{"id":"done","label":"Erledigt"},{"id":"not_here","label":"Nicht hier"},{"id":"later","label":"Später"}]},"attachedList":null}]},
          {"id":"p:2","ref":null,"lat":52.4,"lon":13.2,"radius":150,"name":"Oma","address":"Gartenweg 5","transitions":["exit"],
           "tasks":[{"taskId":5,"listId":1,"area":"private","title":"Leiter mitnehmen","kind":"place","trigger":"exit","planned":null,"tag":"place:5:p:2","important":true,"due":null,
                     "notification":{"title":"Leiter mitnehmen","body":"Verlassen: Oma · Einkauf · 8 Punkte","collapseKey":"task:5",
                                     "actions":[{"id":"open_list","label":"Liste öffnen"},{"id":"done","label":"Erledigt"},{"id":"later","label":"Später"}]},
                     "attachedList":{"id":12,"uid":"6f1c2a90-1111-4c1d-9a00-000000000001","name":"Einkauf","open":8,"total":8}}]},
          {"id":"not a fence","lat":1,"lon":1,"radius":100,"transitions":["enter"],"tasks":[]}]}"""

    private val watch get() = NotizenPlacesJson.watch(json(watchJson))

    @Test
    fun `places-watch - one fence per place, the reminder ready to show`() {
        val w = watch
        assertTrue(w.enabled)
        assertEquals(40, w.max)
        assertEquals(3000, w.refreshM)
        assertEquals(52.52, w.cellLat)
        assertEquals(13.41, w.cellLon)
        assertTrue(w.nearby)
        assertEquals(NQuiet("21:00", "07:00"), w.quiet)
        assertEquals("on", w.search)
        assertEquals(listOf("osm:W101", "osm:W102", "p:2"), w.fences.map { it.id }, "what is not a fence is left out, the order stays")
        val near = w.fences[1].tasks.single()
        assertTrue(near.nearby)
        assertEquals("Baumarkt Nord", near.planned)
        assertEquals("Baumarkt am Ring ist gleich hier", near.notifyTitle)
        assertEquals("Schrauben kaufen · geplant war Baumarkt Nord", near.notifyBody)
        assertEquals("task:1", near.collapseKey)
        assertEquals("place:1:osm:W102", near.tag)
        assertEquals(listOf("done", "not_here", "later"), PlaceGuard.buttons(near).map { it.id })
        assertEquals(listOf("Erledigt", "Nicht hier", "Später"), PlaceGuard.buttons(near).map { it.label })
        val oma = w.fences[2]
        assertFalse(oma.enter)
        assertTrue(oma.exit)
        // a list at the task: „Liste öffnen“ first — Android shows three buttons
        assertEquals(listOf("open_list", "done", "later"), PlaceGuard.buttons(oma.tasks.single()).map { it.id })
        assertEquals("Einkauf", oma.tasks.single().attachedList!!.name)
        // the button for a list only while the reminder names the list; what the phone cannot do is left out
        assertEquals(listOf("done"), PlaceGuard.buttons(oma.tasks.single().copy(attachedList = null, actions = listOf(NReminderAction("open_list", "Liste öffnen"), NReminderAction("done", "Erledigt"), NReminderAction("magic", "?")))).map { it.id })
        assertEquals(json("""{"taskId":1,"fence":"osm:W102","transition":"enter"}"""), NotizenPlacesJson.firedBody(1, "osm:W102", "enter"))
        assertEquals(NFired(false, "recent"), NotizenPlacesJson.fired(json("""{"ok":true,"notify":false,"reason":"recent"}""")))
        assertEquals(NFired(true, null), NotizenPlacesJson.fired(json("""{"ok":true,"notify":true,"kind":"nearby"}""")))
        assertEquals("osm:W102", NotizenPlacesJson.fenceId("osm:W102"))
        assertEquals("p:17", NotizenPlacesJson.fenceId("p:17"))
        assertNull(NotizenPlacesJson.fenceId("familie-place-3"))
        assertEquals(listOf("osm:W101", "cell"), NotizenGeofences.fenceIdsOf(listOf("nz:osm:W101", "familie-place-3", "nz:cell", "familie-rule-1")), "the Familie plugin's fences are not the hub's")
    }

    @Test
    fun `the budget - Android's 100, the Familie plugin first and never pushed out`() {
        // nothing of the family: the server's own limit
        assertEquals(60, GeofenceBudget.notizen(0))
        assertEquals(60, GeofenceBudget.notizen(35))
        // the family's fences count in full; a reserve and the hub's own fence stay free
        assertEquals(45, GeofenceBudget.notizen(50))
        assertEquals(25, GeofenceBudget.notizen(70))
        assertEquals(5, GeofenceBudget.notizen(90))
        assertEquals(5, GeofenceBudget.notizen(500), "the family never registers more than its own limit")
        assertEquals(60, GeofenceBudget.notizen(-1))
        for (family in 0..GeofenceBudget.FAMILY_MAX) {
            val hub = GeofenceBudget.notizen(family)
            assertTrue(family + hub + GeofenceBudget.MOVE_FENCE + GeofenceBudget.RESERVE <= GeofenceBudget.ANDROID_MAX, "family $family")
            assertTrue(hub >= 5, "the family at its limit still leaves the hub a few")
        }
        assertEquals(GeofenceBudget.FAMILY_MAX, GeofenceRegistrar.MAX_FENCES, "one number, kept in one place")
        // the server's order is the importance: the phone registers from the top as far as the room goes
        val w = watch
        assertEquals(listOf("osm:W101", "osm:W102", "p:2"), PlaceLogic.select(w.fences, 60).map { it.id })
        assertEquals(listOf("osm:W101"), PlaceLogic.select(w.fences, 1).map { it.id })
        assertTrue(PlaceLogic.select(w.fences, 0).isEmpty())
        assertTrue(PlaceLogic.select(w.fences, -4).isEmpty())
        assertEquals(1, PlaceLogic.select(w.fences + w.fences[0], 60).count { it.id == "osm:W101" }, "one geofence per place")
    }

    @Test
    fun `asking again - only after moving farther than the server says`() {
        val w = watch
        assertFalse(PlaceLogic.moved(w, 52.52, 13.41))
        assertFalse(PlaceLogic.moved(w, 52.54, 13.42))
        assertTrue(PlaceLogic.moved(w, 52.56, 13.41), "more than three kilometres")
        assertTrue(PlaceLogic.moved(w.copy(cellLat = null), 52.52, 13.41), "never asked with a position")
    }

    // ── a place fires without a connection ──────────────────

    private val now = 1_794_139_200_000L // 2026-11-08 13:00 in Berlin
    private val zone = "Europe/Berlin"

    @Test
    fun `without a connection - once per place and task in three hours`() {
        val w = watch
        val primary = w.fences[0].tasks.single()
        assertNull(PlaceGuard.offline(primary, "osm:W101", "enter", w, PlaceState(), zone, now))
        assertEquals("transition", PlaceGuard.offline(primary, "osm:W101", "exit", w, PlaceState(), zone, now))
        val shown = PlaceGuard.shown(PlaceState(), primary, "osm:W101", now)
        assertEquals("recent", PlaceGuard.offline(primary, "osm:W101", "enter", w, shown, zone, now + 60_000))
        assertEquals("recent", PlaceGuard.offline(primary, "osm:W101", "enter", w, shown, zone, now + PlaceGuard.REPEAT_MS - 1))
        assertNull(PlaceGuard.offline(primary, "osm:W101", "enter", w, shown, zone, now + PlaceGuard.REPEAT_MS))
        // another place of the same task is another place — the chosen one always reminds
        assertTrue(shown.hinted.isEmpty())
        // a clock set back does not silence a place for good
        assertNull(PlaceGuard.offline(primary, "osm:W101", "enter", w, shown, zone, now - 60_000))
    }

    @Test
    fun `without a connection - in der Nähe once per task, not in the quiet hours, not when switched off`() {
        val w = watch
        val near = w.fences[1].tasks.single()
        assertNull(PlaceGuard.offline(near, "osm:W102", "enter", w, PlaceState(), zone, now))
        assertEquals("off", PlaceGuard.offline(near, "osm:W102", "enter", w.copy(nearby = false), PlaceState(), zone, now))
        // 22:30 in Berlin: quiet — but only for the hint on the way
        val night = now + 9 * 3_600_000L + 30 * 60_000L
        assertEquals("quiet", PlaceGuard.offline(near, "osm:W102", "enter", w, PlaceState(), zone, night))
        assertNull(PlaceGuard.offline(w.fences[0].tasks.single(), "osm:W101", "enter", w, PlaceState(), zone, night), "the chosen place always reminds")
        assertNull(PlaceGuard.offline(near, "osm:W102", "enter", w.copy(quiet = null), PlaceState(), zone, night))
        // a hint at one DIY store silences the hint at every other one for this task — not the chosen place
        val shown = PlaceGuard.shown(PlaceState(), near, "osm:W102", now)
        assertEquals(now, shown.hinted[1L])
        assertEquals("recent", PlaceGuard.offline(near.copy(tag = "place:1:osm:W103"), "osm:W103", "enter", w, shown, zone, now + 600_000))
        assertNull(PlaceGuard.offline(w.fences[0].tasks.single(), "osm:W101", "enter", w, shown, zone, now + 600_000))
        assertNull(PlaceGuard.offline(near, "osm:W103", "enter", w, shown, zone, now + PlaceGuard.REPEAT_MS))
    }

    @Test
    fun `Später and Nicht hier work on the phone at once`() {
        val w = watch
        val primary = w.fences[0].tasks.single()
        val near = w.fences[1].tasks.single()
        val later = PlaceGuard.later(PlaceState(), 1, now)
        assertEquals("later", PlaceGuard.muted(later, 1, "osm:W101", now + 1))
        assertEquals("later", PlaceGuard.offline(near, "osm:W102", "enter", w, later, zone, now + 1))
        assertNull(PlaceGuard.muted(later, 5, "p:2", now + 1), "another task")
        assertNull(PlaceGuard.muted(later, 1, "osm:W101", now + PlaceGuard.LATER_MS), "three hours")
        val skipped = PlaceGuard.skipped(PlaceState(), 1, "osm:W102")
        assertEquals("skipped", PlaceGuard.offline(near, "osm:W102", "enter", w, skipped, zone, now))
        assertNull(PlaceGuard.offline(primary, "osm:W101", "enter", w, skipped, zone, now), "only this one place")
        // what is over or belongs to no task any more goes
        val old = PlaceGuard.shown(skipped.copy(later = mapOf(1L to now - 1, 9L to now + 1000)), primary, "osm:W101", now - 9 * 86_400_000L)
        val pruned = PlaceGuard.pruned(old, now, setOf(1L))
        assertTrue(pruned.fired.isEmpty())
        assertTrue(pruned.later.isEmpty())
        assertEquals(setOf("1|osm:W102"), pruned.skipped)
        assertTrue(PlaceGuard.pruned(skipped, now, setOf(5L)).skipped.isEmpty())
    }

    @Test
    fun `Gerade in der Nähe - the fences the phone is inside, made on the phone`() {
        val w = watch
        assertTrue(PlaceLogic.nearNow(w, PlaceState(), 52.5, 13.36, now).isEmpty())
        val inside = PlaceGuard.moved(PlaceState(), listOf("osm:W102", "p:2"), entered = true)
        val n = PlaceLogic.nearNow(w, inside, 52.5012, 13.3625, now).single()
        assertEquals("Baumarkt am Ring", n.fence.name)
        assertEquals("Baumarkt Nord", n.task.planned)
        assertTrue(n.distanceM!! < 100)
        // a task that waits for leaving is not „in der Nähe“; without a position there is no distance
        assertNull(PlaceLogic.nearNow(w, inside, null, null, now).single().distanceM)
        // „Nicht hier“, „Später“ and leaving take it away
        assertTrue(PlaceLogic.nearNow(w, PlaceGuard.skipped(inside, 1, "osm:W102"), null, null, now).isEmpty())
        assertTrue(PlaceLogic.nearNow(w, PlaceGuard.later(inside, 1, now), null, null, now + 1).isEmpty())
        assertTrue(PlaceLogic.nearNow(w, PlaceGuard.moved(inside, listOf("osm:W102"), entered = false), null, null, now).isEmpty())
        // the chosen place and another one of its kind at once: the task shows once
        val both = PlaceGuard.moved(PlaceState(), listOf("osm:W101", "osm:W102"), entered = true)
        assertEquals(1, PlaceLogic.nearNow(w, both, null, null, now).size)
    }

    @Test
    fun `what the phone remembers survives a restart`() {
        val w = watch
        var s = PlaceGuard.shown(PlaceState(), w.fences[1].tasks.single(), "osm:W102", now)
        s = PlaceGuard.later(s, 5, now)
        s = PlaceGuard.skipped(s, 1, "osm:W103")
        s = PlaceGuard.moved(s, listOf("p:2"), entered = true).copy(registered = listOf("osm:W101", "p:2"))
        assertEquals(s, PlaceState.fromJson(JsonParser.parseString(s.toJson().toString()).asJsonObject))
        // tolerant: what cannot be read is left out
        val odd = PlaceState.fromJson(json("""{"fired":{"x":1,"1|osm:W1":5},"hinted":{"a":1,"2":7},"later":"no","skipped":["1|p:2","zz"],"inside":["p:2","??"],"registered":[1,"osm:N4"]}"""))
        assertEquals(mapOf("1|osm:W1" to 5L), odd.fired)
        assertEquals(mapOf(2L to 7L), odd.hinted)
        assertTrue(odd.later.isEmpty())
        assertEquals(setOf("1|p:2"), odd.skipped)
        assertEquals(setOf("p:2"), odd.inside)
        assertEquals(listOf("osm:N4"), odd.registered)
    }

    // ── what waits for a connection ─────────────────────────

    @Test
    fun `Später and Nicht hier wait in the outbox beside each other`() {
        val later = NotizenOutbox.Op(NotizenOutbox.ACTION, 1, false, 1, NotizenOutbox.PLACE_LATER, JsonObject(), uid = "a")
        val skip1 = NotizenOutbox.Op(NotizenOutbox.ACTION, 1, false, 2, NotizenOutbox.PLACE_SKIP, NotizenPlacesJson.skipBody("osm:W102"), uid = "b")
        val skip2 = NotizenOutbox.Op(NotizenOutbox.ACTION, 1, false, 3, NotizenOutbox.PLACE_SKIP, NotizenPlacesJson.skipBody("osm:W103"), uid = "c")
        val snooze = NotizenOutbox.Op(NotizenOutbox.ACTION, 1, false, 4, NotizenOutbox.SNOOZE, NotizenJson.snoozeBody("1h", "task:1:5"), uid = "d")
        var ops = listOf<NotizenOutbox.Op>()
        for (o in listOf(later, skip1, skip2, snooze)) ops = NotizenOutbox.add(ops, o)
        assertEquals(listOf("a", "b", "c", "d"), ops.map { it.uid }, "two places skipped are two things to send; a reminder moved is another")
        // the same again takes the place of the one waiting
        ops = NotizenOutbox.add(ops, skip1.copy(uid = "e", at = 9))
        ops = NotizenOutbox.add(ops, later.copy(uid = "f", at = 9))
        assertEquals(listOf("c", "d", "e", "f"), ops.map { it.uid })
        // kept and read again
        val kept = NotizenOutbox.stateFromJson(NotizenOutbox.stateToJson(NotizenOutbox.State(ops)))
        assertEquals(listOf(NotizenOutbox.PLACE_SKIP, NotizenOutbox.SNOOZE, NotizenOutbox.PLACE_SKIP, NotizenOutbox.PLACE_LATER), kept.ops.map { it.what })
        assertEquals("osm:W103", kept.ops[0].body!!.get("fence").asString)
    }

    // ── consent ─────────────────────────────────────────────

    @Test
    fun `the push of a place set by someone else opens the task`() {
        val data = """{"notizen":{"kind":"place_consent","taskId":41,"listId":3,"area":"private","task":41},
            "actions":[{"id":"place_accept","label":"Annehmen","type":"callback"},{"id":"place_decline","label":"Ablehnen","type":"callback"}]}"""
        val m = PushMessage(
            seq = 1, id = 7, eventId = "e", topic = NotizenPush.TOPIC_SHARED, priority = "normal", title = "Anna Berg möchte dich erinnern",
            body = "Milch mitbringen · beim Ankommen an Lidl Marktstraße", createdAt = null, expiresAt = null, collapseKey = "place:41", silent = false, route = null,
            actions = emptyList(), dataJson = data,
        )
        assertEquals("task:41@private", NotizenPush.destination(m))
        assertEquals(NotizenPush.Task(41, "private"), NotizenPush.decode(NotizenPush.destination(m)))
        assertNull(NotizenPush.reminderTag(m), "it is no reminder: the phone's alarm has nothing to say about it")
        assertEquals(json("""{"accept":false}"""), NotizenPlacesJson.consentBody(false))
        // until I agree the place shows, but this phone does not watch it
        val pending = NotizenPlacesJson.place(json(placeJson.replace(""""consent":"accepted","watch":true""", """"consent":"pending","watch":false""").replace(""""by":1""", """"by":2""")))!!
        assertEquals(NPlace.PENDING, pending.consent)
        assertFalse(pending.watch)
        assertEquals(2L, pending.by)
    }

    // ── settings ────────────────────────────────────────────

    @Test
    fun `prefs-places - the search for me, the hint and its quiet hours`() {
        assertEquals(NPlacePrefs(search = false, nearby = false, quiet = NQuiet("22:00", "06:30")), me(""""places"""", prefs = """{"places":{"search":false,"nearby":false,"quiet":{"from":"22:00","to":"06:30"}}}""").prefs.places)
        // `quiet: null` — no quiet hours; a time twice the same or not a time: none either
        assertNull(me(""""places"""", prefs = """{"places":{"search":true,"nearby":true,"quiet":null}}""").prefs.places.quiet)
        assertNull(me(""""places"""", prefs = """{"places":{"quiet":{"from":"21:00","to":"21:00"}}}""").prefs.places.quiet)
        assertNull(me(""""places"""", prefs = """{"places":{"quiet":{"from":"abends","to":"07:00"}}}""").prefs.places.quiet)
        assertEquals(json("""{"places":{"search":false}}"""), NotizenPlacesJson.placePrefsBody(search = false))
        assertEquals(json("""{"places":{"nearby":true}}"""), NotizenPlacesJson.placePrefsBody(nearby = true))
        assertEquals(json("""{"places":{"quiet":{"from":"22:00","to":"06:00"}}}"""), NotizenPlacesJson.placePrefsBody(quiet = NQuiet("22:00", "06:00")))
        val none = NotizenPlacesJson.placePrefsBody(noQuiet = true)
        assertTrue(none.getAsJsonObject("places").get("quiet").isJsonNull)
        assertNotNull(none.getAsJsonObject("places"))
    }
}
