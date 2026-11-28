package com.gatecontrol.android.ui.quickactions

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.quickactions.QuickActionItem
import com.gatecontrol.android.quickactions.QuickActionLookItem
import com.gatecontrol.android.quickactions.QuickActionLookStateItem
import com.gatecontrol.android.quickactions.QuickActionReadingItem
import com.gatecontrol.android.quickactions.QuickActionWidgetItem
import com.gatecontrol.android.ui.theme.GateControlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tür & Kamera 1.11: the lock is ONE start-tab switch — „Zugesperrt“ (highlighted like
 * „Verriegelt“ of a car), „Aufgesperrt“ (open lock), „Sperrt zu …“ without a side — with
 * the battery in its corner. Screenshots with LOCK_SWITCH_SHOTS=<dir>.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class QuickActionLockSwitchUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val lockIcon = "M7 11V7a5 5 0 0 1 10 0v4M5 11h14v10H5z"
    private val unlockIcon = "M7 11V7a5 5 0 0 1 9.9-1M5 11h14v10H5z"
    private val battery = QuickActionReadingItem("badge", "Batterie", value = "44", unit = "%", icon = "M2 7h16v10H2zM21 10v4M6 10v4M10 10v4", state = "good")

    private fun switch(title: String, state: String?, icon: String, widgetState: String?) = QuickActionItem(
        plugin = "gatecontrol-door", pluginName = "Tür & Kamera", id = "1:toggle", title = title, subtitle = "Haustür · Tür zu",
        toggle = true, state = state, confirm = true, confirmWhen = "off", icon = icon,
        deviceId = "lock:1", deviceTitle = "Haustür",
        look = QuickActionLookItem(QuickActionLookStateItem(lockIcon, "good"), QuickActionLookStateItem(unlockIcon, "warn")),
        widget = QuickActionWidgetItem(value = title, status = "Tür zu", state = widgetState, power = false, badge = battery),
        verbOn = "Zusperren", verbOff = "Aufsperren",
    )

    private val locked = switch("Zugesperrt", "on", lockIcon, "good")
    private val unlocked = switch("Aufgesperrt", "off", unlockIcon, "warn")
    private val moving = switch("Sperrt zu …", null, lockIcon, null)
    private val open = QuickActionItem(
        plugin = "gatecontrol-door", pluginName = "Tür & Kamera", id = "1:open", title = "Haustür öffnen", confirm = true,
        icon = "M5 21V4a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1v17M3 21h18M15 12h.01", deviceId = "lock:1", deviceTitle = "Haustür",
    )

    private fun show(dark: Boolean) = rule.setContent {
        GateControlTheme(darkTheme = dark) {
            Row(
                Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp).testTag("shot"),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (item in listOf(open, locked, unlocked, moving)) {
                    QuickActionTile(item = item, width = 66.dp, running = false, onClick = {}, onLongPress = null)
                }
            }
        }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        System.getenv("LOCK_SWITCH_SHOTS")?.let { dir ->
            val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
            File(dir).mkdirs()
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun `one switch per lock with its state as title and the battery inside`() {
        show(dark = false)
        for (title in listOf("Zugesperrt", "Aufgesperrt", "Sperrt zu …", "Haustür öffnen")) {
            rule.onNodeWithText(title, useUnmergedTree = true).assertIsDisplayed()
        }
        // the battery in each switch's corner, none on „öffnen“
        rule.onAllNodesWithTag(TILE_BADGE_TAG, useUnmergedTree = true).assertCountEquals(3)
        shot("lock-switch-light")
    }

    @Test
    fun `dark theme`() {
        show(dark = true)
        rule.onNodeWithText("Zugesperrt", useUnmergedTree = true).assertIsDisplayed()
        shot("lock-switch-dark")
    }

    @Test
    fun `the switch asks only before aufsperren and names that command`() {
        assertEquals(true, locked.needsConfirm)
        assertEquals("Aufsperren", locked.confirmLabel)
        assertEquals(false, unlocked.needsConfirm)
        assertEquals("moving: a tap locks, nothing to ask", false, moving.needsConfirm)
        assertEquals(lockIcon, locked.displayIcon)
        assertEquals(unlockIcon, unlocked.displayIcon)
        assertEquals(lockIcon, moving.displayIcon)
    }
}
