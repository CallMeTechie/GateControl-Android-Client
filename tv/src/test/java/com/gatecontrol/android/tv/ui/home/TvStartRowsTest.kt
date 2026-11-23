package com.gatecontrol.android.tv.ui.home

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.gatecontrol.android.quickactions.QuickActionControlItem
import com.gatecontrol.android.quickactions.QuickActionItem
import com.gatecontrol.android.quickactions.QuickActionWidgetItem
import com.gatecontrol.android.tv.home.TvTile
import com.gatecontrol.android.tv.home.TvTiles
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The row "Schnellaktionen" with the remote: ◀ ▶ move the focus, OK runs, ◀ ▶ set a heating's target once open. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
class TvStartRowsTest {

    @get:Rule
    val rule = createComposeRule()

    private val setpoint = QuickActionControlItem("sp", QuickActionControlItem.KIND_STEPPER, label = "Ziel", number = 21.5, min = 5.0, max = 30.0, step = 0.5, unit = "°C")
    private val actions = listOf(
        QuickActionItem("gatecontrol-hue", "Licht", "kino", "Szene Kino"),
        QuickActionItem("gatecontrol-door", "Tür", "lock-1", "Haustür", toggle = true, state = "on"),
        QuickActionItem(
            "gatecontrol-raumklima", "Raumklima", "bath:power", "Heizung", toggle = true, state = "on",
            widget = QuickActionWidgetItem(value = "21,5", unit = "°", controls = listOf(setpoint)), deviceId = "bath", deviceTitle = "Heizung Bad",
        ),
    )

    private val pressed = mutableListOf<String>()
    private val sent = mutableListOf<Double>()

    private fun show() {
        val tiles = TvTiles.build(emptyList(), actions)
        rule.setContent {
            GateControlTvTheme {
                TvStartRows(
                    ui = TvStartUi(tiles, emptySet(), emptyList(), emptyMap(), null, loading = false),
                    onTile = { pressed += it.key },
                    onSetpoint = { _, _, v -> sent += v },
                    onCamera = {},
                )
            }
        }
    }

    @Test
    fun theDpadWalksTheTilesAndOkRunsTheFocusedOne() {
        show()
        rule.onNodeWithText("Szene Kino").assertExists()
        rule.onNodeWithContentDescription("Szene Kino").requestFocus().assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithContentDescription("Haustür, On").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(listOf("a:gatecontrol-door/lock-1"), pressed)
    }

    @Test
    fun aHeatingsTargetIsSetWithLeftAndRight() {
        show()
        rule.onNodeWithContentDescription("Ziel ", substring = true).requestFocus()
        // OK opens it, ▶ ▶ two steps, OK sends
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(listOf(22.5), sent)
        assertEquals(emptyList<String>(), pressed)
    }

    @Test
    fun theDeviceTileRunsItsPowerAction() {
        val tiles = TvTiles.build(emptyList(), actions)
        assertEquals("bath:power", (tiles.last() as TvTile.Device).power?.id)
    }
}
