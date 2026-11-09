package com.gatecontrol.android.ui.components

import android.app.Application
import android.graphics.Bitmap
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.drive.DriveDrawer
import com.gatecontrol.android.ui.drive.TabBar
import com.gatecontrol.android.notizen.NArea
import com.gatecontrol.android.ui.notizen.NotizenDrawer
import com.gatecontrol.android.ui.notizen.NotizenDrawerActions
import com.gatecontrol.android.ui.notizen.NotizenHomeHead
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
 * The side menus of Drive and Notizen & Aufgaben are drawn inside the app's Scaffold (as in AppNavigation), which
 * already keeps them below the status bar: the menu's head starts right below the status bar, at the height the page's
 * head starts, the status bar counted once (v1.102 counted it twice). Likewise the keyboard's room on a page counts the
 * navigation bar once. With `DRAWER_SHOTS=<dir>` the drawn menus are saved there (for a look, not part of CI).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class GcDrawerInsetUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val statusBar = 40.dp
    private val navBar = 24.dp

    /** GcDrawerHead's own top padding above the title. */
    private val headTop = 22.dp

    /** The page shown by [page]; switched without a second setContent. */
    private val content = mutableStateOf<@Composable () -> Unit>({})

    /** [content] as a page of the app: in a Scaffold like AppNavigation's, with a status bar and a navigation bar. */
    private fun page(ime: Dp = 0.dp, content: @Composable () -> Unit) {
        this.content.value = content
        var view: View? = null
        rule.setContent {
            view = LocalView.current
            GateControlTheme(darkTheme = false) {
                Scaffold(containerColor = MaterialTheme.colorScheme.background, modifier = Modifier.testTag("shot")) { inner ->
                    Box(Modifier.padding(inner).fillMaxSize()) { this@GcDrawerInsetUiTest.content.value() }
                }
            }
        }
        rule.waitForIdle()
        host = view
        insets(ime)
    }

    private var host: View? = null

    /** The system bars and, with [ime] above 0, the keyboard. */
    private fun insets(ime: Dp) {
        val view = host
        val d = rule.activity.resources.displayMetrics.density
        val px = { dp: Dp -> (dp.value * d).toInt() }
        rule.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, px(statusBar), 0, 0))
                .setVisible(WindowInsetsCompat.Type.statusBars(), true)
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, px(navBar)))
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
            // the keyboard's inset counts from the screen's bottom edge, the navigation bar included
            if (ime > 0.dp) insets.setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, px(ime))).setVisible(WindowInsetsCompat.Type.ime(), true)
            ViewCompat.dispatchApplyWindowInsets(view!!, insets.build())
        }
        rule.waitForIdle()
    }

    /** The menu (open) over [head], its sheet [sheet]. */
    @Composable
    private fun Menu(sheet: @Composable (@Composable ColumnScope.() -> Unit) -> Unit, head: @Composable () -> Unit, menu: @Composable () -> Unit) {
        ModalNavigationDrawer(drawerState = rememberDrawerState(DrawerValue.Open), drawerContent = { sheet { menu() } }) { head() }
    }

    private val gcSheet: @Composable (@Composable ColumnScope.() -> Unit) -> Unit = { GcDrawerSheet(content = it) }

    /** The sheet as Drive and Notizen had it up to v1.102.1: Material's default insets. */
    private val defaultSheet: @Composable (@Composable ColumnScope.() -> Unit) -> Unit = {
        ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.background, content = it)
    }

    @Composable
    private fun Drive() = DriveDrawer(
        me = null, areas = emptyList(), personal = emptyList(), shared = emptyList(), selected = null,
        onToggle = {}, onOpen = {}, vaults = null, onVault = {}, icons = emptyList(),
    )

    @Composable
    private fun Notizen() = NotizenDrawer(
        me = null, nav = null, area = NArea.PRIVATE, selected = "myday", collapsed = emptySet(), notebookName = { null },
        actions = NotizenDrawerActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, null, {}),
    )

    /** The top of the topmost node showing [text] (the menu's title; the page's head lies below it or elsewhere). */
    private fun top(text: String): Dp = rule.onAllNodesWithText(text).fetchSemanticsNodes().minOf { n ->
        with(rule.density) { n.boundsInRoot.top.toDp() }
    }

    private fun shot(name: String) {
        val dir = System.getenv("DRAWER_SHOTS") ?: return
        val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The menu's head starts right below the status bar (once); [before] the default sheet's, a status bar lower. */
    private fun assertSingle(title: String, after: Dp, before: Dp) {
        assertEquals("$title: menu title at $after", (statusBar + headTop).value, after.value, 1f)
        assertEquals("$title: default sheet pads the status bar twice", (statusBar * 2 + headTop).value, before.value, 1f)
    }

    @Test
    fun `Drive's menu starts below the status bar, counted once`() {
        val title = rule.activity.getString(R.string.drive_title)
        page { Menu(defaultSheet, { TabBar("Fotos", onMenu = {}, onSearch = {}) }) { Drive() } }
        shot("drive-menu-before")
        val before = top(title)
        content.value = { Menu(gcSheet, { TabBar("Fotos", onMenu = {}, onSearch = {}) }) { Drive() } }
        rule.waitForIdle()
        shot("drive-menu-after")
        assertSingle("Drive", top(title), before)
    }

    @Test
    fun `Notizen's menu starts below the status bar, counted once, as Drive's`() {
        val title = rule.activity.getString(R.string.notizen_title)
        page { Menu(defaultSheet, { NotizenHomeHead("Mein Tag", null, onMenu = {}) }) { Notizen() } }
        shot("notizen-menu-before")
        val before = top(title)
        content.value = { Menu(gcSheet, { NotizenHomeHead("Mein Tag", null, onMenu = {}) }) { Notizen() } }
        rule.waitForIdle()
        shot("notizen-menu-after")
        val notizen = top(title)
        assertSingle("Notizen", notizen, before)
        content.value = { Menu(gcSheet, { Box(Modifier) }) { Drive() } }
        rule.waitForIdle()
        assertEquals(top(rule.activity.getString(R.string.drive_title)).value, notizen.value, 0.5f)
    }

    @Test
    fun `the menu's sheet fills its height below the status bar`() {
        page { Menu(gcSheet, {}) { Box(Modifier.fillMaxSize().testTag("menu")) } }
        val menu = rule.onNodeWithTag("menu").getBoundsInRoot()
        val root = rule.onNodeWithTag("shot").getBoundsInRoot()
        assertEquals(statusBar.value, menu.top.value, 0.5f)
        assertEquals((root.bottom - navBar).value, menu.bottom.value, 0.5f)
    }

    @Test
    fun `the keyboard's room counts the navigation bar once`() {
        val ime = 300.dp
        page(ime) { Box(Modifier.fillMaxSize().keyboardPadding().testTag("page")) }
        val root = rule.onNodeWithTag("shot").getBoundsInRoot()
        assertEquals((root.bottom - ime).value, rule.onNodeWithTag("page").getBoundsInRoot().bottom.value, 0.5f)
        // imePadding() there: the navigation bar's height of empty space above the keyboard
        content.value = { Box(Modifier.fillMaxSize().imePadding().testTag("page")) }
        rule.waitForIdle()
        assertEquals((root.bottom - ime - navBar).value, rule.onNodeWithTag("page").getBoundsInRoot().bottom.value, 0.5f)
        // no keyboard: no room of its own
        content.value = { Box(Modifier.fillMaxSize().keyboardPadding().testTag("page")) }
        insets(0.dp)
        assertEquals((root.bottom - navBar).value, rule.onNodeWithTag("page").getBoundsInRoot().bottom.value, 0.5f)
    }

    /** The plugins' menus use [GcDrawerSheet] (no insets of their own), the Notizen pages [keyboardPadding]. */
    @Test
    fun `no menu sheet or Notizen page adds a system bar a second time`() {
        val src = File("src/main/java/com/gatecontrol/android")
        assertTrue(src.absolutePath, src.isDirectory)
        fun hits(dir: File, re: Regex, skip: Set<String> = emptySet()) = dir.walk().filter { it.extension == "kt" && it.name !in skip }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line -> if (re.containsMatchIn(line.substringBefore("//"))) "${f.name}:${i + 1}" else null }
        }.toList()
        val sheets = hits(src, Regex("""ModalDrawerSheet\("""), skip = setOf("GcDrawer.kt"))
        assertTrue("menu sheet with default insets in $sheets", sheets.isEmpty())
        val ime = hits(File(src, "ui/notizen"), Regex("""imePadding\(\)"""))
        assertTrue("imePadding in $ime", ime.isEmpty())
    }
}
