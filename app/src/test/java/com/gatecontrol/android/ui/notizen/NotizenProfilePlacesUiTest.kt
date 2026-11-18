package com.gatecontrol.android.ui.notizen

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.notizen.NPlaces
import com.gatecontrol.android.notizen.NSpot
import com.gatecontrol.android.ui.theme.GateControlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Notizen 1.8 (`profilePlaces`): the choice of a task's place shows three of „Meine Orte“ (the chosen one, the most
 * used, …) and „Alle Orte…“ — the shared sheet; Notizen 1.7 lists all of its own places as before. Light and dark.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w411dp-h1400dp")
class NotizenProfilePlacesUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val mine = listOf(
        NSpot("Arbeit", "Hafenstraße 4, Musterstadt", 50.11, 8.68, 200, placeId = 12, named = true, uses = 1),
        NSpot("Baumarkt", "Industriestraße 20, Musterstadt", 50.2, 8.7, 120, placeId = 14, named = true, uses = 5),
        NSpot("Zuhause", "Lindenweg 12, Musterstadt", 50.05, 8.6, 150, placeId = 13, named = true),
        NSpot("Eisdiele", "Am Markt 7, Musterstadt", 50.3, 8.8, 100, placeId = 15, named = true),
    )

    private fun show(c: PlaceChoice, dark: Boolean, all: (() -> Unit)?) {
        val actions = PlaceDialogActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, all)
        rule.setContent {
            GateControlTheme(darkTheme = dark) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(20.dp)) {
                    PlaceDialogBody(c, actions)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun count(s: String) = rule.onAllNodesWithText(s).fetchSemanticsNodes().size

    private fun profile(dark: Boolean) {
        var opened = 0
        show(PlaceChoice(open = true, places = NPlaces(mine), selected = mine[3], profile = true), dark) { opened++ }
        listOf("Eisdiele", "Baumarkt", "Arbeit", "Alle Orte…").forEach { assertEquals(it, 1, count(it)) }
        assertEquals(0, count("Zuhause"))
        rule.onNodeWithTag("place-dialog-all").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test fun `plugin 1_8 - three places and „Alle Orte…“ - light`() = profile(dark = false)

    @Test fun `plugin 1_8 - three places and „Alle Orte…“ - dark`() = profile(dark = true)

    @Test
    fun `plugin 1_7 - all own places, no shared choice`() {
        show(PlaceChoice(open = true, places = NPlaces(mine), selected = mine[3]), dark = false, all = null)
        listOf("Eisdiele", "Baumarkt", "Arbeit", "Zuhause").forEach { assertEquals(it, 1, count(it)) }
        assertEquals(0, count("Alle Orte…"))
    }
}
