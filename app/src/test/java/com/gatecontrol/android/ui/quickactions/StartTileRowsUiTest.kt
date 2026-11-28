package com.gatecontrol.android.ui.quickactions

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.quickactions.QuickActionItem
import com.gatecontrol.android.quickactions.QuickActionLookItem
import com.gatecontrol.android.quickactions.QuickActionLookStateItem
import com.gatecontrol.android.quickactions.QuickActionReadingItem
import com.gatecontrol.android.quickactions.QuickActionWidgetItem
import com.gatecontrol.android.ui.theme.GateControlTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * The start tab's pinned buttons: five in one row on a normal (412 dp) and a
 * narrow (360 dp) phone — smaller, not wrapped — more on a tablet; titles
 * broken only between words („Haustür / öffnen“, „Aufgesperrt“ whole), the
 * battery badge inside the smaller tile. Screenshots with START_TILES_SHOTS=<dir>.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StartTileRowsUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val lockIcon = "M7 11V7a5 5 0 0 1 10 0v4M5 11h14v10H5z"
    private val unlockIcon = "M7 11V7a5 5 0 0 1 9.9-1M5 11h14v10H5z"

    private val open = QuickActionItem(
        plugin = "gatecontrol-door", pluginName = "Tür & Kamera", id = "1:open", title = "Haustür öffnen", confirm = true,
        icon = "M5 21V4a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1v17M3 21h18M15 12h.01", deviceId = "lock:1", deviceTitle = "Haustür",
    )
    private val home = QuickActionItem(
        plugin = "gatecontrol-smarthome", pluginName = "Smart Home", id = "scene:home", title = "Zuhause", toggle = true, state = "off",
        icon = "M3 10l9-7 9 7v11H3z", deviceId = "scenes", deviceTitle = "Szenen",
    )
    private val heater = QuickActionItem(
        plugin = "gatecontrol-smarthome", pluginName = "Smart Home", id = "switch:4", title = "Heizlüfter", toggle = true, state = "off",
        icon = "M4 4h16v12H4zM8 8v4M12 8v4M16 8v4", deviceId = "switch:4", deviceTitle = "Heizlüfter",
    )
    private val car = QuickActionItem(
        plugin = "gatecontrol-skoda", pluginName = "Fahrzeuge", id = "lock:1", title = "Verriegelt", toggle = true, state = "on",
        icon = lockIcon, deviceId = "car:1", deviceTitle = "Familienauto",
        look = QuickActionLookItem(QuickActionLookStateItem(lockIcon, "good"), QuickActionLookStateItem(unlockIcon, "warn")),
    )
    private val unlocked = QuickActionItem(
        plugin = "gatecontrol-door", pluginName = "Tür & Kamera", id = "1:toggle", title = "Aufgesperrt", subtitle = "Haustür · Tür zu",
        toggle = true, state = "off", confirm = true, confirmWhen = "off", icon = unlockIcon,
        deviceId = "lock:1", deviceTitle = "Haustür",
        look = QuickActionLookItem(QuickActionLookStateItem(lockIcon, "good"), QuickActionLookStateItem(unlockIcon, "warn")),
        widget = QuickActionWidgetItem(
            value = "Aufgesperrt", status = "Tür zu", state = "warn", power = false,
            badge = QuickActionReadingItem("badge", "Batterie", value = "42", unit = "%", icon = "M2 7h16v10H2zM21 10v4M6 10v4M10 10v4", state = "good"),
        ),
        verbOn = "Zusperren", verbOff = "Aufsperren",
    )
    private val phoneTiles = listOf(open, home, heater, car, unlocked)

    private fun show(items: List<QuickActionItem>, dark: Boolean = false) = rule.setContent {
        GateControlTheme(darkTheme = dark) {
            // As on Start: the screen's width less 16 dp on either side.
            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).testTag("shot")) {
                StartTileRows(
                    items.map { item -> @Composable { w: Dp -> QuickActionTile(item = item, width = w, running = false, onClick = {}, onLongPress = null) } },
                    Modifier.padding(16.dp),
                )
            }
        }
    }

    private fun layout(text: String): TextLayoutResult {
        val node = rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single()
    }

    private fun top(text: String): Float =
        rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y

    /** All [titles] stand in one row, inside the row's width. */
    private fun assertOneRow(titles: List<String>) {
        val first = top(titles.first())
        for (title in titles) {
            assertTrue("„$title“ wrapped into another row", abs(top(title) - first) < 1f)
        }
        val row = rule.onNodeWithTag(START_TILES_TAG).fetchSemanticsNode().boundsInRoot
        val last = rule.onNodeWithText(titles.last(), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("the last tile sticks out of the row", last.right <= row.right + 0.5f)
    }

    /** [title] is broken only between words, never inside one (no hyphenation). */
    private fun assertWholeWords(title: String) {
        val l = layout(title)
        for (line in 0 until l.lineCount - 1) {
            assertFalse("„$title“ split inside a word after line $line", TileLabelFit.breaksInsideWord(title, l.getLineEnd(line)))
        }
        assertFalse("„$title“ cut", l.hasVisualOverflow)
    }

    private fun checkPhone(shotName: String) {
        show(phoneTiles)
        assertOneRow(phoneTiles.map { it.title })
        assertEquals("„Aufgesperrt“ on one line, whole", 1, layout("Aufgesperrt").lineCount)
        assertEquals("„Haustür öffnen“ on two lines, broken at the space", 2, layout("Haustür öffnen").lineCount)
        assertEquals("Haustür ".length, layout("Haustür öffnen").getLineEnd(0))
        phoneTiles.forEach { assertWholeWords(it.title) }
        // the battery badge inside the (smaller) tile
        rule.onNodeWithText("42 %", useUnmergedTree = true).assertIsDisplayed()
        val badge = rule.onNodeWithTag(TILE_BADGE_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val tileLabel = rule.onNodeWithText("Aufgesperrt", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("badge wider than the tile", badge.left >= tileLabel.left && badge.right <= tileLabel.right)
        shot(shotName)
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun `five in one row on a 412 dp phone`() = checkPhone("start-tiles-412dp")

    @Test
    @Config(qualifiers = "w360dp-h780dp")
    fun `five in one row on a 360 dp phone - smaller, not wrapped`() = checkPhone("start-tiles-360dp")

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun `dark theme`() {
        show(phoneTiles, dark = true)
        assertOneRow(phoneTiles.map { it.title })
        shot("start-tiles-412dp-dark")
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp")
    fun `a sixth wraps into the next row`() {
        val sixth = heater.copy(id = "switch:5", title = "Licht")
        show(phoneTiles + sixth)
        assertOneRow(phoneTiles.map { it.title })
        assertTrue(top("Licht") > top("Haustür öffnen") + 10f)
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp")
    fun `a word too long for the tile is never split`() {
        val long = heater.copy(id = "switch:6", title = "Scheibenheizungsanlage")
        val tiles = listOf(open, home, heater, car, long)
        show(tiles)
        assertOneRow(tiles.map { it.title })
        val l = layout("Scheibenheizungsanlage")
        assertEquals("a single word: one line, cut with „…“ rather than split", 1, l.lineCount)
        shot("start-tiles-360dp-long-word")
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun `more than five in a row on a tablet`() {
        val more = phoneTiles + listOf("Licht", "Garage", "Markise").mapIndexed { i, t -> heater.copy(id = "switch:${10 + i}", title = t) }
        show(more)
        assertOneRow(more.map { it.title })
        shot("start-tiles-800dp")
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        System.getenv("START_TILES_SHOTS")?.let { dir ->
            val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
            File(dir).mkdirs()
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
