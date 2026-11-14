package com.gatecontrol.android.ui.notizen

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.familie.locate.FamiliePermissions
import com.gatecontrol.android.notizen.NAttached
import com.gatecontrol.android.notizen.NAttribution
import com.gatecontrol.android.notizen.NDue
import com.gatecontrol.android.notizen.NFence
import com.gatecontrol.android.notizen.NFenceTask
import com.gatecontrol.android.notizen.NNearNow
import com.gatecontrol.android.notizen.NPlace
import com.gatecontrol.android.notizen.NPlaceKey
import com.gatecontrol.android.notizen.NPlaces
import com.gatecontrol.android.notizen.NSpot
import com.gatecontrol.android.notizen.NTask
import com.gatecontrol.android.notizen.NotizenJson
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A task at a place (plugin 1.5.0, mockup „Orte aus dem getippten Text“ / „Vergessen hinzufahren“): the choice „Welcher
 * Baumarkt?“ with its hits, „Jeder …“, „Meine Orte“, the trigger and „merken“; the place in a task's row; the hints under
 * a task; the card „Gerade in der Nähe“. With `NOTIZEN_SHOTS=<dir>` the drawn pages are saved there (for a look, not
 * part of CI).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w411dp-h1400dp")
class NotizenPlaceUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val hits = listOf(
        NSpot("Baumarkt Nord", "Industriestraße 4, Berlin", 52.539, 13.424, ref = "osm:W101", distanceM = 2400, minutes = 6, uses = 1),
        NSpot("Baumarkt am Ring", "Ringstraße 21, Berlin", 52.501, 13.362, ref = "osm:W102", distanceM = 4100, minutes = 9),
        NSpot("Baumarkt Süd", "Am Hafen 2, Berlin", 52.46, 13.40, ref = "osm:W103", distanceM = 7800, minutes = 14),
    )
    private val places = NPlaces(
        places = listOf(NSpot("Oma", "Gartenweg 5", 52.4, 13.2, placeId = 2, named = true), NSpot("Bäckerei am Markt", "Markt 1", 52.5, 13.4, placeId = 4, favFor = listOf(NPlaceKey("kind:bakery", "Bäcker")))),
        family = listOf(NSpot("Zuhause", lat = 52.52, lon = 13.40, familyId = "home"), NSpot("Schule", lat = 52.53, lon = 13.41, familyId = "place:3")),
    )
    private val choice = PlaceChoice(
        open = true, label = "Baumarkt", key = "kind:diy", query = "Baumarkt", searchState = "on", places = places, results = hits, anyOffered = true,
        attribution = NAttribution("© OpenStreetMap", "https://www.openstreetmap.org/copyright"), selected = hits[0], lang = "de",
    )

    private val calls = mutableListOf<String>()
    private val actions = PlaceDialogActions(
        query = { calls += "query:$it" },
        search = { calls += "search" },
        select = { calls += "select:${it.name}" },
        selectAny = { calls += "any" },
        trigger = { calls += "trigger:$it" },
        remember = { calls += "remember:$it" },
        map = { calls += "map" },
        save = { calls += "save" },
        remove = { calls += "remove" },
        dismiss = { calls += "dismiss" },
        openUrl = { calls += "url:$it" },
    )

    private fun task(id: Long, title: String, place: NPlace?, due: NDue? = null, list: NAttached? = null) =
        NTask(id, 3, null, title, false, false, false, null, due, false, null, null, null, null, false, null, 0, null, null, 0, 0, attachedList = list, place = place)

    private val place = NPlace(NPlace.PLACE, 1, "Baumarkt Nord", "Industriestraße 4, Berlin", 52.539, 13.424, 150, "osm:W101", false, NPlace.ENTER, "kind:diy", "Baumarkt", 1, NPlace.ACCEPTED, true, true, 0, null)

    private fun shot(name: String, dark: Boolean) {
        rule.waitForIdle()
        System.getenv("NOTIZEN_SHOTS")?.let { dir ->
            val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
            File(dir).mkdirs()
            File(dir, "$name-${if (dark) "dark" else "light"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** The choice shown; a test may set another one after [showDialog]. */
    private val state = mutableStateOf(choice)

    private fun showDialog(first: PlaceChoice = choice, dark: Boolean = false, name: String = "place-dialog") {
        state.value = first
        rule.setContent {
            GateControlTheme(darkTheme = dark) {
                val c = state.value
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag("shot").verticalScroll(rememberScrollState()).padding(20.dp)) {
                    Text("Welcher ${c.label ?: "Ort"}?", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 12.dp))
                    PlaceDialogBody(c, actions)
                }
            }
        }
        shot(name, dark)
    }

    private fun showRows(dark: Boolean) {
        rule.setContent {
            GateControlTheme(darkTheme = dark) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("shot")) {
                    val fence = NFence("osm:W102", "osm:W102", 52.501, 13.362, 300, "Baumarkt am Ring", "Ringstraße 21, Berlin", enter = true, exit = false, tasks = emptyList())
                    val near = NFenceTask(1, 3, "private", "Schrauben kaufen", NFenceTask.NEARBY, NPlace.ENTER, "Baumarkt Nord", "place:1:osm:W102", false, "Baumarkt am Ring ist gleich hier", "", "task:1", emptyList(), null)
                    NearNowCard(listOf(NNearNow(fence, near, 300)), "de", canWrite = true, onOpen = { calls += "open:$it" }, onNotHere = { calls += "nothere:${it.fence.id}" })
                    NSectionHeader("Heute", 3)
                    val rc = rowCtx("2026-11-18", "de", { "Privat › Aufgaben" }, showDay = false)
                    NCard {
                        NEntryRow(task(1, "Schrauben kaufen", place), rc, {}, {})
                        NEntryRow(task(2, "Leiter zurückgeben", place.copy(name = "Oma", trigger = NPlace.EXIT, key = null, label = null, nearby = false, own = true), NDue("2026-11-18", "17:00")), rc, {}, {})
                        NEntryRow(
                            task(3, "Pfand wegbringen", place.copy(mode = NPlace.ANY, name = "Jeder Lidl", lat = null, lon = null, consent = NPlace.PENDING, watch = false), list = NAttached(12, "6f1c2a90-1111-4c1d-9a00-000000000001", "Einkauf", "cart", 8, 8, 0, true)),
                            rc, {}, {}, divider = false,
                        )
                    }
                    PlaceNotes(place.copy(consent = NPlace.PENDING, watch = false, by = 2), me(), false, FamiliePermissions.GeofenceReadiness.NEED_BACKGROUND, { calls += "consent:$it" }, {})
                    PlaceNotes(place, me(), false, FamiliePermissions.GeofenceReadiness.NEED_BACKGROUND, {}, {})
                }
            }
        }
        shot("place-rows", dark)
    }

    private fun me(features: String = """"places","placeSearch"""", state: String = "on") = NotizenJson.me(
        JsonParser.parseString(
            """{"ok":true,"user":{"id":1,"name":"Anna Berg"},"lang":"de","today":"2026-11-18","time":"10:14","features":[$features],"prefs":{},
            "places":{"search":{"state":"$state","enabled":true,"mine":true,"radiusKm":15,"decimals":2}}}""",
        ).asJsonObject,
    )!!

    @Test
    fun `the choice - hits with estimated minutes and distance, the map data's name, Jeder, Meine Orte`() {
        showDialog()
        rule.onNodeWithText("Baumarkt Nord").assertExists()
        rule.onNodeWithText("6 Min.").assertExists()
        rule.onNodeWithText("2,4 km").assertExists()
        rule.onNodeWithText("Industriestraße 4, Berlin · 1-mal gewählt").assertExists()
        rule.onNodeWithText("Jeder Baumarkt").assertExists()
        rule.onNodeWithText("Erinnert an der ersten Filiale, an der du ankommst").assertExists()
        // the estimate is called an estimate, and the map data's name stands where the hits stand
        rule.onNodeWithText("Fahrzeit geschätzt aus der Entfernung. Kartendaten:").assertExists()
        rule.onNodeWithText("© OpenStreetMap").assertExists()
        rule.onNodeWithText("Meine Orte").assertExists()
        rule.onAllNodesWithText("Ort der Familie").assertCountEquals(2)
        rule.onNodeWithText("Gartenweg 5 · gemerkt").assertExists()
        rule.onNodeWithText("Markt 1 · gemerkt für „Bäcker“").assertExists()
        rule.onNodeWithText("Als meinen Ort für „Baumarkt“ merken").assertExists()
        // the sentences of the contract, word for word
        rule.onNodeWithText("Für die Suche geht nur das Suchwort und deine auf etwa einen Kilometer gerundete Position an deinen GateControl-Server, der einen Kartendienst von OpenStreetMap fragt. Dein Name, dein Gerät und dein genauer Standort gehen nicht mit.").assertExists()
        rule.onNodeWithText("Erinnert wird auf deinem Handy, sobald es am Ort ankommt oder ihn verlässt. Der Server erfährt nicht, wo du bist.").assertExists()

        rule.onNodeWithText("Baumarkt Nord").assertIsSelected()
        rule.onNodeWithText("Baumarkt am Ring").performScrollTo().performClick()
        rule.onNodeWithText("Jeder Baumarkt").performScrollTo().performClick()
        rule.onNodeWithText("© OpenStreetMap").performScrollTo().performClick()
        rule.onNodeWithText("Oma").performScrollTo().performClick()
        rule.onNodeWithText("Ort auf der Karte wählen").performScrollTo().performClick()
        rule.onNodeWithText("Beim Verlassen").performScrollTo().performClick()
        rule.onNodeWithText("Als meinen Ort für „Baumarkt“ merken").performScrollTo().performClick()
        rule.onNodeWithText("Suchen", useUnmergedTree = false).performScrollTo().performClick()
        assertEquals(
            listOf("select:Baumarkt am Ring", "any", "url:https://www.openstreetmap.org/copyright", "select:Oma", "map", "trigger:exit", "remember:true", "search"),
            calls,
        )
    }

    @Test
    fun `the choice in the dark`() {
        showDialog(dark = true)
        rule.onNodeWithText("Jeder Baumarkt").assertExists()
    }

    @Test
    fun `without the place search - no search, the sentence of the contract, the own places and the map`() {
        showDialog(choice.copy(searchState = "off", results = null, anyOffered = false, selected = null), name = "place-dialog-off")
        rule.onAllNodesWithText("Suchen").assertCountEquals(0)
        rule.onAllNodesWithText("© OpenStreetMap").assertCountEquals(0)
        rule.onNodeWithText("Die Ortssuche im Internet ist ausgeschaltet. Gemerkte Orte und die Karte funktionieren weiter.").assertExists()
        rule.onNodeWithText("Zuhause").assertExists()
        rule.onNodeWithText("Ort auf der Karte wählen").assertExists()
        // nothing chosen: nothing to remember
        rule.onAllNodesWithText("Als meinen Ort für „Baumarkt“ merken").assertCountEquals(0)
    }

    @Test
    fun `nothing found, the map service down, searching`() {
        showDialog(choice.copy(results = emptyList(), anyOffered = false, selected = null, problem = "none", label = "Tankstelle", query = "Tankstelle"), name = "place-dialog-none")
        rule.onNodeWithText("Kein Treffer für „Tankstelle“ im Umkreis von 15 km").assertExists()
        rule.onNodeWithText("Ort auf der Karte wählen").assertExists()
        rule.onNodeWithText("Suchen").assertIsEnabled()
        state.value = choice.copy(results = emptyList(), selected = null, problem = "down")
        rule.waitForIdle()
        rule.onNodeWithText("Der Kartendienst ist gerade nicht erreichbar. Gemerkte Orte und die Karte funktionieren weiter.").assertExists()
        state.value = choice.copy(searching = true)
        rule.waitForIdle()
        rule.onNodeWithText("Suche läuft …").assertExists()
    }

    @Test
    fun `the place in a task's row, the hints under a task, Gerade in der Nähe`() {
        showRows(dark = false)
        rule.onNodeWithText("Gerade in der Nähe").assertExists()
        rule.onNodeWithText("Baumarkt am Ring · 300 m · geplant: Baumarkt Nord").assertExists()
        rule.onNodeWithText("Die Karte entsteht auf dem Handy. Der Server erfährt nicht, wo du bist.").assertExists()
        rule.onNodeWithText("Baumarkt Nord · beim Ankommen").assertExists()
        rule.onNodeWithText("Oma · beim Verlassen").assertExists()
        rule.onNodeWithText("Jeder Lidl · beim Ankommen").assertExists()
        // the list a task carries stands beside its place
        rule.onNodeWithText("Einkauf 0/8").assertExists()
        // someone else set the place: my phone waits for my consent
        rule.onNodeWithText("Der Ort wurde von jemand anderem gesetzt. Dein Handy achtet erst darauf, wenn du zustimmst.").assertExists()
        rule.onNodeWithText("Annehmen").performClick()
        rule.onNodeWithText("Ablehnen").performClick()
        rule.onAllNodesWithText("Kommt dein Handy an einem anderen Ort der Art „Baumarkt“ vorbei, meldet sich die Aufgabe auch dort.").assertCountEquals(2)
        // a hint, not a ban — and only where this phone is the one to watch the place
        rule.onAllNodesWithText("Standort erlauben").assertCountEquals(1)
        rule.onNodeWithText("Nicht hier").performClick()
        assertEquals(listOf("consent:true", "consent:false", "nothere:osm:W102"), calls)
    }

    @Test
    fun `rows and hints in the dark`() {
        showRows(dark = true)
        assertTrue(rule.onAllNodesWithText("Baumarkt Nord · beim Ankommen").fetchSemanticsNodes().isNotEmpty())
    }
}
