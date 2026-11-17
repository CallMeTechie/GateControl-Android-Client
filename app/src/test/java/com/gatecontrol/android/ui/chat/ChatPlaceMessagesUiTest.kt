package com.gatecontrol.android.ui.chat

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.gatecontrol.android.chat.ChatApi
import com.gatecontrol.android.chat.ChatPersonWay
import com.gatecontrol.android.ui.theme.GateControlTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The form of an Ortsnachricht, drawn in German, light and dark, on a narrow phone: „An“ with the three ways as icon
 * buttons of the same width (no text that breaks, a name for TalkBack and in the tooltip, the chosen one marked), and
 * the toast after a failed „Speichern“ names the reason instead of only „Das hat nicht geklappt.“.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w320dp-h640dp")
class ChatPlaceMessagesUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val ways = listOf(
        ChatPersonWay("gatecontrol", "GateControl", 40, "Kim Muster"),
        ChatPersonWay("whatsapp", "WhatsApp", 41, "Kim M."),
    )

    private fun picker(dark: Boolean) {
        var way by mutableStateOf("auto")
        rule.setContent {
            GateControlTheme(darkTheme = dark) {
                // the row of the form inside its card on a 320 dp phone
                Box(Modifier.width(288.dp).background(MaterialTheme.colorScheme.surface)) {
                    PlaceWayPicker(ways = ways, selected = way, onPick = { way = it })
                }
            }
        }
        // no text in the buttons (nothing breaks into „WhatsAp/p“), each has its name for TalkBack
        rule.onAllNodesWithText("WhatsApp").fetchSemanticsNodes().let { assertTrue("no visible text", it.isEmpty()) }
        rule.onAllNodesWithText("Automatisch").fetchSemanticsNodes().let { assertTrue("no visible text", it.isEmpty()) }
        rule.onNodeWithContentDescription("Automatisch").assertIsDisplayed().assertIsSelected()
        rule.onNodeWithContentDescription("GateControl").assertIsDisplayed().assertIsNotSelected()
        rule.onNodeWithContentDescription("WhatsApp").assertIsDisplayed().assertIsNotSelected()

        // the same width (to a pixel of rounding), side by side in one row
        val bounds = listOf("auto", "gatecontrol", "whatsapp").map { rule.onNodeWithTag("place-way-$it").getBoundsInRoot() }
        val widths = bounds.map { it.right - it.left }
        widths.forEach { assertEquals(widths[0].value, it.value, 1.01f) }
        widths.forEach { assertTrue("a third of the row each", it.value > 80f) }
        assertEquals(bounds[0].top.value, bounds[2].top.value, 0.5f)

        // a tap chooses the way, the choice stays marked
        rule.onNodeWithTag("place-way-whatsapp").performClick()
        assertEquals("whatsapp", way)
        rule.onNodeWithContentDescription("WhatsApp").assertIsSelected()
        rule.onNodeWithContentDescription("Automatisch").assertIsNotSelected()
        val role = rule.onNodeWithTag("place-way-whatsapp").fetchSemanticsNode().config[SemanticsProperties.Role]
        assertEquals(androidx.compose.ui.semantics.Role.RadioButton, role)

        // a long press shows the name
        rule.onNodeWithTag("place-way-gatecontrol").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("GateControl").assertIsDisplayed()
    }

    @Test fun `ways as icons light`() = picker(dark = false)

    @Test fun `ways as icons dark`() = picker(dark = true)

    @Test
    fun `the toast names the reason`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        // the case of the report: the server refused the body (400 invalid) with its own sentence
        assertEquals(
            "Der Server hat die Ortsnachricht nicht angenommen: Ungültige Eingabe.",
            placeErrorText(ctx, "invalid", "Ungültige Eingabe."),
        )
        assertEquals("Der Server hat die Ortsnachricht nicht angenommen: invalid", placeErrorText(ctx, "invalid", null))
        assertEquals("Schon 10 aktive Ortsnachrichten. Schalte eine aus, um eine weitere zu aktivieren.", placeErrorText(ctx, "limit", "x"))
        assertEquals("Diesen Weg hat der Chat nicht.", placeErrorText(ctx, "bad_request", null))
        assertEquals("Diesen Chat oder diese Ortsnachricht gibt es nicht mehr.", placeErrorText(ctx, "not_found", "Nicht gefunden."))
        // a code the app knows from elsewhere keeps its text, an unknown one shows the server's sentence or the code
        assertEquals("Das hat nicht geklappt: Interner Fehler.", placeErrorText(ctx, "internal", "Interner Fehler."))
        assertEquals("Das hat nicht geklappt: http_502", placeErrorText(ctx, "http_502", null))
        assertEquals(
            "Gespeichert? Die Antwort des Servers war nicht lesbar. Sieh in der Liste nach.",
            placeErrorText(ctx, ChatApi.CODE_INVALID, null),
        )
    }

    @Test
    fun `the toast in English`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val en = ctx.createConfigurationContext(android.content.res.Configuration(ctx.resources.configuration).apply { setLocale(java.util.Locale.ENGLISH) })
        assertEquals("The server did not accept the place message: Invalid input.", placeErrorText(en, "invalid", "Invalid input."))
        assertEquals("That did not work: internal", placeErrorText(en, "internal", null))
    }
}
