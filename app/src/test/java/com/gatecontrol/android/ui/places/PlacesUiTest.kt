package com.gatecontrol.android.ui.places

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.places.AddressHit
import com.gatecontrol.android.places.MyPlace
import com.gatecontrol.android.places.MyPlaces
import com.gatecontrol.android.places.PlaceDraft
import com.gatecontrol.android.places.PlaceIcons
import com.gatecontrol.android.places.PlaceOption
import com.gatecontrol.android.places.PlaceUse
import com.gatecontrol.android.places.PlacesList
import com.gatecontrol.android.places.PlacesState
import com.gatecontrol.android.places.PlacesSupport
import com.gatecontrol.android.ui.theme.GateControlTheme
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
 * „Meine Orte“ drawn in German, light and dark: the list (own places, one without a point with „Setzen“, the Familie
 * places read only), a place with „Genutzt von“, the editor (symbol, radius, who sees it; no saving without a point),
 * the chips of a form and the sheet „Alle Orte…“ (groups, the search with addresses, „Neu“). The maps are left out
 * (osmdroid draws no tiles here). With `PLACES_SHOTS=<dir>` the drawn pages are saved there (not part of CI).
 * Only made-up names and addresses.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w411dp-h1600dp")
class PlacesUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val here = 50.0 to 8.0
    private fun at(km: Double) = 50.0 + km / 111.2

    private val home = MyPlace(13, "Zuhause", PlaceIcons.HOME, at(0.05), 8.0, 150, "Lindenweg 12, Musterstadt", visibility = "family",
        usedBy = listOf(PlaceUse("gatecontrol-messenger", "Messenger", "location_message", 1), PlaceUse("gatecontrol-notizen", "Notizen", "task", 2)))
    private val work = MyPlace(12, "Arbeit", PlaceIcons.WORK, at(12.0), 8.0, 200, "Hafenstraße 4, Musterstadt",
        usedBy = listOf(PlaceUse("gatecontrol-messenger", "Messenger", "location_message", 1)), lastUsedAt = "2026-11-18 07:31:00")
    private val gym = MyPlace(14, "Fitnessstudio", PlaceIcons.GYM, at(3.4), 8.0, 100, "Am Markt 7, Musterstadt")
    private val noPoint = MyPlace(15, "Baumarkt", PlaceIcons.SHOP, null, null, 150, source = "notizen",
        usedBy = listOf(PlaceUse("gatecontrol-notizen", "Notizen", "task", 2)))
    private val school = PlaceOption("place:3", "Grundschule", PlaceIcons.PIN, at(1.2), 8.0, 150, familie = true)
    private val grandma = PlaceOption("place:4", "Oma & Opa", PlaceIcons.PIN, at(41.0), 8.0, 300, familie = true)

    private val state = PlacesState(PlacesList(listOf(home, work, gym, noPoint), family = true, search = "osm"), PlacesSupport.SUPPORTED)

    private fun show(dark: Boolean, body: @Composable () -> Unit) {
        rule.setContent {
            GateControlTheme(darkTheme = dark) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize().testTag("shot")) { body() }
            }
        }
        rule.waitForIdle()
    }

    private fun shot(name: String, dark: Boolean) {
        val dir = System.getenv("PLACES_SHOTS") ?: return
        val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
        File(dir).mkdirs()
        File(dir, "$name-${if (dark) "dunkel" else "hell"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun searchField() = rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("place-search")))

    private fun count(s: String) = rule.onAllNodesWithText(s, substring = true).fetchSemanticsNodes().size

    // ─── the list (Bild 1, 4) ───────────────────────────────────────

    private fun list(dark: Boolean) {
        val opened = mutableListOf<String>()
        show(dark) {
            MyPlacesContent(state, listOf(school, grandma), here, refreshing = false, onOpen = { opened += "open:${it.id}" }, onSet = { opened += "set:${it.id}" })
        }
        shot("orte-liste", dark)
        assertEquals(1, count("MEINE ORTE · 4"))
        assertEquals(1, count("FAMILIE-ORTE · GEMEINSAM"))
        // the one without a point: the warning, its tag and „Setzen“
        assertEquals(1, count("1 Ort hat keinen Standort"))
        assertEquals(1, count("kein Standort"))
        assertEquals(1, count("2 Aufgaben warten"))
        // where the phone is: „hier“ inside the radius, else the distance
        assertEquals(1, count("hier"))
        assertEquals(1, count("12 km"))
        assertEquals(1, count("3,4 km"))
        assertEquals(1, count("1,2 km"))
        // what uses a place; who sees it
        assertEquals(1, count("Lindenweg 12, Musterstadt · 150 m · 2 Plugins"))
        assertEquals(1, count("Hafenstraße 4, Musterstadt · 200 m · 1 Ortsnachricht"))
        assertEquals(1, count("Am Markt 7, Musterstadt · 100 m · nicht genutzt"))
        assertEquals(1, count("Familie"))
        assertEquals(2, count("gepflegt von Eltern"))
        rule.onNodeWithTag("place-set-15").performClick()
        rule.onNodeWithText("Arbeit").performClick()
        assertEquals(listOf("set:15", "open:12"), opened)
    }

    @Test fun `the list - light`() = list(dark = false)

    @Test fun `the list - dark`() = list(dark = true)

    @Test
    fun `an older server says so`() {
        show(false) { MyPlacesContent(PlacesState(support = PlacesSupport.UNSUPPORTED), emptyList(), null, false, {}, {}) }
        assertEquals(1, count("ab GateControl 1.299"))
    }

    @Test
    fun `the row on the profile page`() {
        var opened = false
        show(false) { ProfilePlacesRowContent(listOf(home, work, noPoint), onOpen = { opened = true }) }
        assertEquals(1, count("3 Orte · 1 ohne Standort"))
        rule.onNodeWithTag("profile-places").performClick()
        assertTrue(opened)
    }

    // ─── a place (Bild 3) ───────────────────────────────────────────

    private fun detail(dark: Boolean) {
        var edit = 0
        show(dark) { PlaceDetailContent(home, here, onEdit = { edit++ }, showMap = false) }
        shot("ort-ansehen", dark)
        assertEquals(1, count("GENUTZT VON"))
        assertEquals(1, count("1 Ortsnachricht"))
        assertEquals(1, count("2 Aufgaben"))
        assertEquals(1, count("Radius 150 m · Familie · hier"))
        rule.onNodeWithTag("place-edit").performClick()
        assertEquals(1, edit)
    }

    @Test fun `a place - light`() = detail(dark = false)

    @Test fun `a place - dark`() = detail(dark = true)

    @Test
    fun `deleting a place in use names who uses it and offers to delete anyway`() {
        var forced = false
        show(false) {
            PlaceInUseDialog("Arbeit", listOf(PlaceUse("gatecontrol-messenger", "Messenger", "location_message", 2), PlaceUse("gatecontrol-familie", null, "rule", 1)), onForce = { forced = true }, onDismiss = {})
        }
        assertEquals(1, count("„Arbeit“ wird noch genutzt"))
        assertEquals(1, count("Messenger: 2 Ortsnachrichten"))
        assertEquals(1, count("Familie: 1 Regel"))
        rule.onNodeWithText("Trotzdem löschen").performClick()
        assertTrue(forced)
    }

    // ─── the editor (Bild 2) ────────────────────────────────────────

    private fun editor(dark: Boolean) {
        var d by mutableStateOf(PlaceDraft())
        val searched = mutableListOf<String>()
        var finder by mutableStateOf(FinderState())
        show(dark) {
            Column {
                PlaceEditorContent(
                    draft = d,
                    onDraft = { d = it },
                    finder = finder,
                    family = true,
                    nameTaken = false,
                    searchOn = true,
                    onSearch = { searched += it },
                    onClearHits = { finder = FinderState() },
                    onHere = {},
                    onTap = { lat, lon -> d = d.copy(lat = lat, lon = lon) },
                    showMap = false,
                )
            }
        }
        // no point yet: nothing to save
        assertTrue(!MyPlaces.canSave(d))
        // the search goes only on Enter / „Suchen“, from three characters
        searchField().performTextInput("Ha")
        searchField().performImeAction()
        assertTrue(searched.isEmpty())
        searchField().performTextInput("fenstraße 4")
        searchField().performImeAction()
        assertEquals(listOf("Hafenstraße 4"), searched)
        finder = FinderState(hits = listOf(AddressHit("Hafenstraße 4", "Hafenstraße 4, Musterstadt", 50.11, 8.68)), attribution = "© OpenStreetMap")
        rule.waitForIdle()
        rule.onNodeWithText("Hafenstraße 4, Musterstadt").performClick()
        assertEquals(50.11, d.lat!!, 0.0)
        assertEquals("Hafenstraße 4", d.name)
        assertEquals("Hafenstraße 4, Musterstadt", d.address)
        // the symbol, who sees it
        rule.onNodeWithContentDescription("Arbeit").performClick()
        assertEquals("work", d.icon)
        rule.onNodeWithText("Familie").performScrollTo().performClick()
        assertEquals("family", d.visibility)
        assertEquals(1, count("RADIUS · 150 M"))
        assertTrue(MyPlaces.canSave(d))
        shot("ort-anlegen", dark)
    }

    @Test fun `the editor - light`() = editor(dark = false)

    @Test fun `the editor - dark`() = editor(dark = true)

    @Test
    fun `without a family there is no „Familie“ to choose`() {
        show(false) {
            PlaceEditorContent(PlaceDraft(), {}, FinderState(), family = false, nameTaken = true, searchOn = false, onSearch = {}, onClearHits = {}, onHere = {}, onTap = { _, _ -> }, showMap = false)
        }
        assertEquals(0, rule.onAllNodesWithText("Familie").fetchSemanticsNodes().size)
        assertEquals(1, count("Diesen Namen gibt es schon."))
        assertEquals(1, count("Adresssuche des Geräts"))
    }

    // ─── the choice in a form (Bilder 5–8) ──────────────────────────

    private val options = listOf(
        PlaceOption("profile:12", "Arbeit", PlaceIcons.WORK, at(12.0), 8.0, 200, "Hafenstraße 4, Musterstadt", usage = 3, lastUsedMs = 9_000),
        PlaceOption("profile:13", "Zuhause", PlaceIcons.HOME, at(0.05), 8.0, 150, "Lindenweg 12, Musterstadt", lastUsedMs = 3_000),
        school,
        PlaceOption("profile:14", "Fitnessstudio", PlaceIcons.GYM, at(3.4), 8.0, 100),
        PlaceOption("profile:16", "Baumarkt", PlaceIcons.SHOP, at(5.1), 8.0, 120),
        grandma,
        PlaceOption("profile:17", "Eisdiele", PlaceIcons.PIN, at(25.0), 8.0, 100),
    )

    private fun chips(dark: Boolean) {
        var selected by mutableStateOf<PlaceOption?>(options[0])
        var all = 0
        show(dark) {
            Column(Modifier.padding(16.dp)) {
                PlaceChips(com.gatecontrol.android.places.PlacePicking.chips(options, selected, here), selected?.key, onPick = { selected = it }, onAll = { all++ })
                selected?.let { PlaceSummary(it) }
            }
        }
        shot("ort-chips", dark)
        // three places and „Alle Orte…“: the chosen one, the most used (the same), then the nearest
        listOf("Arbeit", "Zuhause", "Grundschule", "Alle Orte…").forEach { rule.onNodeWithText(it).assertIsEnabled() }
        assertEquals(0, count("Fitnessstudio"))
        assertEquals(1, count("200 m · aus „Meine Orte“ · Hafenstraße 4, Musterstadt"))
        rule.onNodeWithText("Grundschule").performClick()
        assertEquals("place:3", selected?.ref)
        assertEquals(1, count("150 m · Familie-Ort"))
        rule.onNodeWithText("Alle Orte…").performClick()
        assertEquals(1, all)
    }

    @Test fun `the chips - light`() = chips(dark = false)

    @Test fun `the chips - dark`() = chips(dark = true)

    private fun sheet(dark: Boolean) {
        var query by mutableStateOf("")
        var finder by mutableStateOf(FinderState())
        val picked = mutableListOf<String>()
        val searched = mutableListOf<String>()
        var newHere = 0
        show(dark) {
            PlaceSheetContent(
                options = options,
                selectedKey = "profile:12",
                here = here,
                query = query,
                finder = finder,
                canSave = true,
                oneOff = true,
                actions = PlaceSheetActions(
                    query = { query = it },
                    search = { searched += query },
                    pick = { picked += it.key },
                    hit = { picked += "hit:${it.name}" },
                    newHere = { newHere++ },
                    newMap = {},
                ),
            )
        }
        shot("ort-blatt", dark)
        // Bild 6: recently used (2), nearby (≤ 5 km, by distance), A–Z, new
        listOf("ZULETZT GENUTZT", "IN DER NÄHE", "B", "E", "O", "NEU").forEach { assertTrue(it, count(it) >= 1) }
        assertEquals(1, count("Familie · 150 m"))
        assertEquals(1, rule.onAllNodesWithContentDescription("gewählt").fetchSemanticsNodes().size)
        rule.onNodeWithText("Grundschule").performClick()
        rule.onNodeWithTag("place-new-here").performScrollTo().performClick()
        assertEquals(1, newHere)
        // Bild 7: the own places at once, addresses on „Suchen“
        searchField().performTextInput("bau")
        rule.waitForIdle()
        assertEquals(1, count("MEINE ORTE"))
        assertEquals(1, count("ADRESSEN"))
        assertEquals(1, count("Baumarkt"))
        rule.onNodeWithTag("place-search-addresses").performClick()
        assertEquals(listOf("bau"), searched)
        finder = FinderState(hits = listOf(AddressHit("Bauhaus", "Industriestraße 20, Musterstadt", at(6.0), 8.0), AddressHit("Baustoffhandel Nord", "Kanalweg 3, Musterstadt", at(8.7), 8.0)), attribution = "© OpenStreetMap")
        rule.waitForIdle()
        shot("ort-suche", dark)
        assertEquals(1, count("Industriestraße 20, Musterstadt · OpenStreetMap"))
        assertEquals(1, count("6,0 km"))
        assertEquals(1, count("© OpenStreetMap"))
        rule.onNodeWithText("Bauhaus").performClick()
        assertEquals(listOf("place:3", "hit:Bauhaus"), picked)
    }

    @Test fun `the sheet - light`() = sheet(dark = false)

    @Test fun `the sheet - dark`() = sheet(dark = true)

    @Test
    fun `an address hit asks whether to save it`() {
        var saved: String? = null
        var only = false
        show(false) {
            var name by androidx.compose.runtime.remember { mutableStateOf("Bauhaus") }
            Column {
                SavePlaceBody(AddressHit("Bauhaus", "Industriestraße 20, Musterstadt", 50.1, 8.7), oneOff = true, name = name) { name = it }
                SavePlaceButtons(oneOff = true, canSave = name.isNotBlank(), onSave = { saved = name }, onOnlyHere = { only = true }, onDismiss = {})
            }
        }
        assertEquals(1, count("Ja legt ihn in „Meine Orte“ an"))
        assertEquals(1, count("Industriestraße 20, Musterstadt"))
        rule.onNodeWithText("Nur hier").performClick()
        assertTrue(only)
        rule.onNodeWithText("Speichern").performClick()
        assertEquals("Bauhaus", saved)
    }

    @Test
    fun `a form that needs a place offers no point only for itself`() {
        show(false) {
            Column {
                SavePlaceBody(AddressHit("", null, 50.1, 8.7), oneOff = false, name = "") {}
                SavePlaceButtons(oneOff = false, canSave = MyPlaces.cleanName("").isNotEmpty(), onSave = {}, onOnlyHere = {}, onDismiss = {})
            }
        }
        assertEquals(0, count("Nur hier"))
        assertEquals(1, count("Abbrechen"))
        rule.onNodeWithText("Speichern").assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = "en-w411dp-h1600dp")
    fun `the sheet in English`() {
        show(false) {
            PlaceSheetContent(options, null, here, "", FinderState(), canSave = true, oneOff = true, actions = PlaceSheetActions({}, {}, {}, {}, {}, {}))
        }
        assertEquals(1, count("RECENTLY USED"))
        assertEquals(1, count("NEARBY"))
        assertEquals(1, count("Current location as a place"))
        assertEquals(1, count("3.4 km"))
    }
}
