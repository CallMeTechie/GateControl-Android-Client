package com.gatecontrol.android.ui.quickactions

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.quickactions.QuickActionActivity
import com.gatecontrol.android.quickactions.QuickActionItem
import com.gatecontrol.android.quickactions.QuickActionResult
import com.gatecontrol.android.quickactions.QuickActionReadingItem
import com.gatecontrol.android.quickactions.QuickActionWidgetItem
import com.gatecontrol.android.ui.theme.GateControlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A start-tab button shows its widget's badge (a car's battery) in the corner; a logic chain has none. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class QuickActionTileBadgeUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val car = QuickActionItem(
        plugin = "gatecontrol-skoda", pluginName = "Fahrzeuge", id = "lock:1", title = "Verriegeln", toggle = true, state = "on",
        deviceId = "car:1", deviceTitle = "Familienauto",
        widget = QuickActionWidgetItem(
            status = "Geparkt", power = false,
            badge = QuickActionReadingItem("badge", "Akku", value = "78", unit = "%", icon = "M3 7h15v10H3zM21 10v4", state = "warn"),
        ),
    )

    private val chain = QuickActionItem(
        plugin = "gatecontrol-smarthome", pluginName = "Smart Home", id = "chain:7", title = "Abendlicht", subtitle = "Kette · Gartenhaus",
        icon = "M6 3v12M18 9c0 7-12 5-12 9M9 18a3 3 0 1 1-6 0 3 3 0 0 1 6 0zM21 6a3 3 0 1 1-6 0 3 3 0 0 1 6 0z",
        deviceId = "chains", deviceTitle = "Logikketten",
    )

    private fun show(item: QuickActionItem) = rule.setContent {
        GateControlTheme(darkTheme = false) {
            QuickActionTile(item = item, width = 66.dp, running = false, onClick = {}, onLongPress = null)
        }
    }

    @Test
    fun `the badge shows the charge level in the tile`() {
        show(car)
        rule.onNodeWithText("78 %", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(TILE_BADGE_TAG, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Verriegeln", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a chain tile has its title and no badge`() {
        show(chain)
        rule.onNodeWithText("Abendlicht", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(TILE_BADGE_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `the toast takes the plugin's text, else one for the reason`() {
        val ctx = rule.activity
        val skipped = "„Abendlicht“ nicht gestartet – „Nur wenn“ gilt gerade nicht"
        assertEquals(skipped, QuickActionActivity.resultText(ctx, chain, QuickActionResult(false, skipped)))
        assertEquals("„Abendlicht“ gestartet", QuickActionActivity.resultText(ctx, chain, QuickActionResult(true, "„Abendlicht“ gestartet")))
        assertEquals("Abendlicht: no longer available", QuickActionActivity.resultText(ctx, chain, QuickActionResult(false, null, QuickActionResult.GONE)))
        assertEquals("Abendlicht: failed", QuickActionActivity.resultText(ctx, chain, QuickActionResult(false, null)))
        assertEquals("Abendlicht: done", QuickActionActivity.resultText(ctx, chain, QuickActionResult(true, null)))
    }
}
