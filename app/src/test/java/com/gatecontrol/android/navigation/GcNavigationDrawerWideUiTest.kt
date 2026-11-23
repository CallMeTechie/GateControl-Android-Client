package com.gatecontrol.android.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.adaptive.GcWindowLayout
import com.gatecontrol.android.ui.components.GcDrawerIcon
import com.gatecontrol.android.ui.components.GcDrawerSheet
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcNavigationDrawer
import com.gatecontrol.android.ui.drive.DriveDrawer
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

/**
 * A plugin's side menu (Drive, Notizen & Aufgaben, Mail, Chat) on wide windows: the page sits right of the navigation
 * rail as in AppNavigation. Material places the closed menu right before the page's left edge and does not clip it;
 * up to v1.139 the closed menu was drawn over the rail (unfolded foldable: half a menu at the left edge, the rail
 * hidden). [GcNavigationDrawer] keeps the menu within the page: closed it is not seen and the rail is whole and
 * tappable, opened it lies completely in the window with its buttons tappable. The page's box is not clipped here
 * (AppNavigation clips its NavHost as well), so the menu itself is tested.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class GcNavigationDrawerWideUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val railColor = Color(0xFFFF0000)
    private val tapped = mutableListOf<String>()
    private var useRail = false

    private fun label(id: Int) = rule.activity.getString(id)

    /**
     * The window: the rail (when [GcWindowLayout] wants one at this width) and the page with the Drive menu. [raw]: Material's
     * drawer as Drive had it up to v1.139; [centred]: the page in [GcContentWidth] (Mail, Notizen, Drive).
     */
    private fun window(open: Boolean, raw: Boolean = false, centred: Boolean = false) {
        rule.setContent {
            GateControlTheme(darkTheme = false) {
                val cfg = LocalConfiguration.current
                useRail = GcWindowLayout.compute(cfg.screenWidthDp, cfg.screenHeightDp).useRail
                val state = rememberDrawerState(if (open) DrawerValue.Open else DrawerValue.Closed)
                val sheet: @Composable () -> Unit = {
                    GcDrawerSheet {
                        DriveDrawer(
                            me = null, areas = emptyList(), personal = emptyList(), shared = emptyList(), selected = null,
                            onToggle = {}, onOpen = {}, vaults = null, onVault = {},
                            icons = listOf(
                                GcDrawerIcon(GcIcons.Calendar, R.string.drive_deadlines) { tapped += "deadlines" },
                                GcDrawerIcon(GcIcons.Settings, R.string.drive_settings_title) { tapped += "settings" },
                            ),
                        )
                    }
                }
                val page: @Composable () -> Unit = {
                    if (raw) {
                        ModalNavigationDrawer(drawerState = state, gesturesEnabled = state.isOpen, drawerContent = sheet) { Box(Modifier.fillMaxSize()) }
                    } else {
                        GcNavigationDrawer(drawerState = state, drawerContent = sheet) { Box(Modifier.fillMaxSize()) }
                    }
                }
                Row(Modifier.fillMaxSize().testTag("window")) {
                    if (useRail) {
                        Column(Modifier.width(GcWindowLayout.RAIL_WIDTH_DP.dp).fillMaxHeight().background(railColor).testTag("rail")) {
                            listOf("Start", "Drive").forEach { t ->
                                Box(Modifier.size(80.dp, 56.dp).clickable { tapped += t }.semantics { contentDescription = "tab $t" })
                            }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight().testTag("page")) {
                        if (centred) GcContentWidth { page() } else page()
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    /** Share of the rail's pixels that show the rail (not something drawn over it). */
    private fun railShown(): Float {
        val bmp = rule.onNodeWithTag("window").captureToImage().asAndroidBitmap()
        val railPx = (GcWindowLayout.RAIL_WIDTH_DP * rule.density.density).toInt()
        var shown = 0
        var all = 0
        for (x in 1 until railPx - 1 step 4) for (y in 1 until bmp.height - 1 step 8) {
            all++
            if (bmp.getPixel(x, y) == railColor.toArgb()) shown++
        }
        return shown.toFloat() / all
    }

    private fun assertClosed() {
        // nothing of the closed menu is on screen (its icons at the foot were left of the page, over the rail)
        rule.onNodeWithContentDescription(label(R.string.drive_settings_title)).assertIsNotDisplayed()
        rule.onNodeWithContentDescription(label(R.string.drive_deadlines)).assertIsNotDisplayed()
        if (useRail) {
            assertEquals("rail drawn over", 1f, railShown(), 0f)
            rule.onNodeWithContentDescription("tab Drive").assertIsDisplayed().performClick()
            assertEquals(listOf("Drive"), tapped)
        }
    }

    private fun assertOpen() {
        val window = bounds("window")
        val page = bounds("page")
        val title = rule.onNodeWithText(label(R.string.drive_title)).fetchSemanticsNode().boundsInRoot
        for (id in listOf(R.string.drive_deadlines, R.string.drive_settings_title)) {
            val node = rule.onNodeWithContentDescription(label(id)).assertIsDisplayed()
            val b = node.fetchSemanticsNode().boundsInRoot
            assertTrue("${label(id)} at $b, page $page", b.left >= page.left - 0.5f && b.right <= window.right + 0.5f && b.bottom <= window.bottom + 0.5f)
            node.performClick()
        }
        assertEquals(listOf("deadlines", "settings"), tapped)
        assertTrue("menu title at $title, page $page", title.left >= page.left - 0.5f && title.right <= window.right + 0.5f)
        if (useRail) {
            // the open menu lies beside the rail, not over it
            assertEquals("rail drawn over", 1f, railShown(), 0f)
            rule.onNodeWithContentDescription("tab Start").assertIsDisplayed().performClick()
            assertEquals(listOf("deadlines", "settings", "Start"), tapped)
        }
    }

    @Test
    @Config(qualifiers = "w841dp-h701dp")
    fun `foldable open (landscape) – the closed menu leaves the rail whole`() {
        window(open = false)
        assertTrue(useRail)
        assertClosed()
    }

    @Test
    @Config(qualifiers = "w841dp-h701dp")
    fun `foldable open (landscape) – the open menu is whole and tappable`() {
        window(open = true)
        assertOpen()
    }

    @Test
    @Config(qualifiers = "w673dp-h841dp")
    fun `foldable open (portrait) – the closed menu leaves the rail whole`() {
        window(open = false)
        assertTrue(useRail)
        assertClosed()
    }

    @Test
    @Config(qualifiers = "w673dp-h841dp")
    fun `foldable open (portrait) – the open menu is whole and tappable`() {
        window(open = true)
        assertOpen()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `tablet, page centred – the closed menu shows neither over the rail nor in the margin`() {
        window(open = false, centred = true)
        assertTrue(useRail)
        assertClosed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `tablet, page centred – the open menu is whole and tappable`() {
        window(open = true, centred = true)
        assertOpen()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `phone – bottom bar, the menu as before`() {
        window(open = false)
        assertFalse(useRail)
        assertClosed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `phone – the open menu is whole and tappable`() {
        window(open = true)
        assertOpen()
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp")
    fun `phone in landscape – rail, the open menu is whole and tappable`() {
        window(open = true)
        assertTrue(useRail)
        assertOpen()
    }

    /** The cause: Material's drawer as it was, closed, covers most of the rail. */
    @Test
    @Config(qualifiers = "w841dp-h701dp")
    fun `Material's drawer unclipped covered the rail (the fault)`() {
        window(open = false, raw = true)
        assertTrue("rail shown ${railShown()}", railShown() < 0.5f)
    }

    /** Every plugin menu goes through [GcNavigationDrawer]. */
    @Test
    fun `no screen uses Material's drawer directly`() {
        val src = File("src/main/java/com/gatecontrol/android")
        assertTrue(src.absolutePath, src.isDirectory)
        val hits = src.walk().filter { it.extension == "kt" && it.name != "GcDrawer.kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line -> if ("ModalNavigationDrawer(" in line.substringBefore("//")) "${f.name}:${i + 1}" else null }
        }.toList()
        assertTrue("Material's drawer in $hits", hits.isEmpty())
    }
}
