package com.gatecontrol.android.ui.quickactions

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.gatecontrol.android.quickactions.QuickActionActivity
import com.gatecontrol.android.quickactions.QuickActionControlItem
import com.gatecontrol.android.quickactions.QuickActionControlOptionItem
import com.gatecontrol.android.quickactions.QuickActionDeviceGroup
import com.gatecontrol.android.quickactions.QuickActionDevices
import com.gatecontrol.android.quickactions.QuickActionItem
import com.gatecontrol.android.quickactions.QuickActionResult
import com.gatecontrol.android.quickactions.QuickActionTimelineItem
import com.gatecontrol.android.quickactions.QuickActionTimelineSegment
import com.gatecontrol.android.quickactions.QuickActionWidgetItem
import com.gatecontrol.android.quickactions.WidgetPart
import com.gatecontrol.android.ui.theme.GateControlTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * A heating room card (Smart Home 1.14.0, GateControl 1.318.0) on the start
 * tab: target in half degrees, presets without a selection, greyed-out
 * controls, the day bar and the boost / plan buttons. Made-up room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class QuickActionHeatingCardUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var locale: Locale = Locale.getDefault()

    @Before fun german() { locale = Locale.getDefault(); Locale.setDefault(Locale.GERMANY) }

    @After fun restore() { Locale.setDefault(locale) }

    private val presets = listOf(
        QuickActionControlOptionItem("comfort", "Komfort", "M12 4v2"),
        QuickActionControlOptionItem("eco", "Eco", "M5 19c8 0 14-6 14-14"),
        QuickActionControlOptionItem("night", "Nacht", "M20 14A8 8 0 1 1 10 4"),
        QuickActionControlOptionItem("frost", "Frost", "M12 2v20"),
        QuickActionControlOptionItem("boost", "Boost", "M12 3c2 4 6 6 6 11a6 6 0 0 1-12 0"),
    )

    private val plan = QuickActionTimelineItem(
        now = 6 + 52 / 60.0,
        segments = listOf(
            QuickActionTimelineSegment(0.0, 6.0, tone = "neutral", label = "Nacht"),
            QuickActionTimelineSegment(6.0, 8.0, color = "#f59e0b", label = "Komfort"),
            QuickActionTimelineSegment(8.0, 22.0, tone = "good", label = "Eco"),
        ),
    )

    private fun room(disabled: Boolean = false, choice: String? = null, options: List<QuickActionControlOptionItem> = presets): QuickActionDeviceGroup {
        val widget = QuickActionWidgetItem(
            value = "20,5", unit = "°C", label = "Ist", status = if (disabled) "Fenster offen – Heizung aus" else "Boost · noch 18 min",
            power = false, timeline = plan,
            controls = listOf(
                QuickActionControlItem("sp", QuickActionControlItem.KIND_STEPPER, "Soll", 21.0, 5.0, 30.0, 0.5, "°C", disabled = disabled),
                QuickActionControlItem("preset", QuickActionControlItem.KIND_CHOICE, "Preset", choice = choice, options = options, disabled = disabled),
            ),
        )
        fun action(id: String, title: String, w: QuickActionWidgetItem? = null) = QuickActionItem(
            plugin = "gatecontrol-smarthome", pluginName = "Smart Home", id = id, title = title, deviceId = "heat:4", deviceTitle = "Leseecke", widget = w,
        )
        return QuickActionDevices.group(
            listOf(action("heat:4", "Leseecke", widget), action("heat:4:boost", "Boost beenden"), action("heat:4:plan", "Zurück zum Plan")),
        ).single()
    }

    private val sent = mutableListOf<Pair<String, Any>>()

    private fun show(device: QuickActionDeviceGroup, parts: List<WidgetPart> = device.parts()) = rule.setContent {
        GateControlTheme(darkTheme = false) {
            QuickActionWidgetCard(
                device = device,
                parts = parts,
                running = null,
                adjusting = emptySet(),
                onRun = {},
                onLongPress = null,
                onControl = { _, id, value -> sent += id to value },
            )
        }
    }

    @Test
    fun `no preset lights up while none is active, the target keeps its decimal`() {
        show(room())
        presets.forEach { rule.onNodeWithContentDescription(it.label).assertIsNotSelected().assertIsEnabled() }
        rule.onNodeWithText("21,0 °C").assertIsDisplayed()
        rule.onNodeWithContentDescription("Increase Soll").performClick()
        rule.onNodeWithText("21,5 °C").assertIsDisplayed()
        rule.onNodeWithContentDescription("Eco").performClick()
        rule.onNodeWithContentDescription("Eco").assertIsSelected()
        assertEquals(listOf("sp" to 21.5, "preset" to "eco"), sent)
        rule.onNodeWithContentDescription("Boost beenden").assertIsDisplayed()
        rule.onNodeWithContentDescription("Zurück zum Plan").assertIsDisplayed()
    }

    @Test
    fun `the room's own action is no button on its card, even when a stored pin shows it`() {
        val device = room()
        val stale = device.parts() + WidgetPart(WidgetPart.action("heat:4"), WidgetPart.Kind.ACTION, "Leseecke")
        show(device, stale)
        rule.onNodeWithContentDescription("Leseecke").assertDoesNotExist()
        rule.onNodeWithContentDescription("Boost beenden").assertIsDisplayed()
        rule.onNodeWithContentDescription("Zurück zum Plan").assertIsDisplayed()
    }

    @Test
    fun `the option that applies is chosen`() {
        show(room(choice = "boost"))
        rule.onNodeWithContentDescription("Boost").assertIsSelected()
        rule.onNodeWithContentDescription("Komfort").assertIsNotSelected()
    }

    @Test
    fun `disabled controls stay visible but do nothing`() {
        show(room(disabled = true, choice = "eco"))
        rule.onNodeWithTag(controlTag("sp")).assertIsDisplayed()
        rule.onNodeWithTag(controlTag("preset")).assertIsDisplayed()
        rule.onNodeWithText("21,0 °C").assertIsDisplayed()
        rule.onNodeWithContentDescription("Increase Soll").assertIsNotEnabled().performClick()
        rule.onNodeWithContentDescription("Decrease Soll").assertIsNotEnabled()
        presets.forEach { rule.onNodeWithContentDescription(it.label).assertIsNotEnabled() }
        rule.onNodeWithContentDescription("Komfort").performClick()
        rule.onNodeWithContentDescription("Eco").assertIsSelected()
        assertTrue(sent.isEmpty())
        rule.onNodeWithText("Fenster offen – Heizung aus").assertIsDisplayed()
    }

    @Test
    fun `the day bar shows its axis and tells screen readers the plan`() {
        show(room())
        rule.onNodeWithTag(TIMELINE_TAG).assertIsDisplayed()
        val description = rule.onNodeWithTag(TIMELINE_TAG).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertTrue(description, description.contains("0:00–6:00 Nacht, 6:00–8:00 Komfort, 8:00–22:00 Eco"))
        assertTrue(description, description.contains("6:52"))
        rule.onNodeWithTag(TIMELINE_TAG).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
    }

    @Test
    fun `eight options wrap into two even rows`() {
        val eight = (1..8).map { QuickActionControlOptionItem("o$it", "Stufe $it") }
        show(room(options = eight))
        eight.forEach { rule.onNodeWithContentDescription(it.label).assertIsDisplayed() }
        val first = rule.onNodeWithContentDescription("Stufe 1").fetchSemanticsNode().boundsInRoot
        val fifth = rule.onNodeWithContentDescription("Stufe 5").fetchSemanticsNode().boundsInRoot
        val fourth = rule.onNodeWithContentDescription("Stufe 4").fetchSemanticsNode().boundsInRoot
        assertTrue(fifth.top > first.bottom - 1f)
        assertEquals(first.left, fifth.left, 0.5f)
        assertEquals(first.width, fourth.width, 1.5f)
        assertEquals(listOf(5), (1..5).toList().chunked(choicePerRow(5)).map { it.size })
        assertEquals(listOf(3, 3), (1..6).toList().chunked(choicePerRow(6)).map { it.size })
        assertEquals(listOf(4, 3), (1..7).toList().chunked(choicePerRow(7)).map { it.size })
        assertEquals(listOf(4, 4), (1..8).toList().chunked(choicePerRow(8)).map { it.size })
    }

    @Test
    fun `seven options keep the width in the shorter row`() {
        val seven = (1..7).map { QuickActionControlOptionItem("o$it", "Stufe $it") }
        show(room(options = seven))
        val first = rule.onNodeWithContentDescription("Stufe 1").fetchSemanticsNode().boundsInRoot
        val last = rule.onNodeWithContentDescription("Stufe 7").fetchSemanticsNode().boundsInRoot
        assertEquals(first.width, last.width, 1.5f)
    }

    @Test
    fun `a refused run of a disabled control has its own text`() {
        val item = room().carrier!!
        val text = QuickActionActivity.resultText(rule.activity, item, QuickActionResult(false, null, QuickActionResult.CONTROL_DISABLED))
        assertEquals("Leseecke: can’t be changed right now", text)
    }
}
