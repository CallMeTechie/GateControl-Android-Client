package com.gatecontrol.android.ui.notizen

import android.app.Application
import android.graphics.Bitmap
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.gatecontrol.android.ui.drive.TabBar
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
 * The head of the tab „Aufgaben“ sits where Drive's head sits: in the app's Scaffold (as in AppNavigation) with a
 * status bar, the title „Mein Tag“ starts at the height of Drive's title, the status bar counted once. With
 * `NOTIZEN_SHOTS=<dir>` the drawn pages are saved there (for a look, not part of CI).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class NotizenHeadUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val statusBar = 40.dp

    /** The page shown by [page]; switched without a second setContent. */
    private val content = mutableStateOf<@Composable () -> Unit>({})

    /** [content] as a page of the app: in a Scaffold like AppNavigation's, with a status bar of [statusBar]. */
    private fun page(content: @Composable () -> Unit) {
        this.content.value = content
        var view: View? = null
        rule.setContent {
            view = LocalView.current
            GateControlTheme(darkTheme = false) {
                Scaffold(containerColor = MaterialTheme.colorScheme.background, modifier = Modifier.testTag("shot")) { inner ->
                    Box(Modifier.padding(inner).fillMaxSize()) { this@NotizenHeadUiTest.content.value() }
                }
            }
        }
        rule.waitForIdle()
        val px = (statusBar.value * rule.activity.resources.displayMetrics.density).toInt()
        rule.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(
                view!!,
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, px, 0, 0))
                    .setVisible(WindowInsetsCompat.Type.statusBars(), true)
                    .build(),
            )
        }
        rule.waitForIdle()
    }

    private fun titleTop(text: String): Dp = rule.onNodeWithText(text).getBoundsInRoot().top

    private fun shot(name: String) {
        val dir = System.getenv("NOTIZEN_SHOTS") ?: return
        val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `Mein Tag sits at the height of Drive's title, the status bar counted once`() {
        page { TabBar("Drive", onMenu = {}, onSearch = {}) }
        shot("head-drive")
        val drive = titleTop("Drive")
        content.value = { NotizenHomeHead("Mein Tag", "Montag, 9. November", onMenu = {}) { NAreaPill("work") {} } }
        rule.waitForIdle()
        shot("head-notizen")
        val notizen = titleTop("Mein Tag")
        assertEquals(drive.value, notizen.value, 0.5f)
        // the status bar is there (the Scaffold pads) — and only once: the head's own top is 12 dp, the row 48 dp
        assertTrue("title at $notizen", notizen >= statusBar + 12.dp && notizen < statusBar + 12.dp + 24.dp)
    }

    @Test
    fun `the date sits below the title`() {
        page { NotizenHomeHead("Mein Tag", "Montag, 9. November", onMenu = {}) }
        val title = rule.onNodeWithText("Mein Tag").getBoundsInRoot()
        val sub = rule.onNodeWithText("Montag, 9. November").getBoundsInRoot()
        assertTrue(sub.top >= title.bottom - 1.dp)
        assertEquals(title.left.value, sub.left.value, 0.5f)
    }

    /**
     * The pages of Notizen are routes of the NavHost inside the app's Scaffold, which already pads them with the
     * system bars; an inset of their own puts the head a status bar too low (v1.100–1.102).
     */
    @Test
    fun `no Notizen page adds the status bar a second time`() {
        val dir = File("src/main/java/com/gatecontrol/android/ui/notizen")
        assertTrue(dir.absolutePath, dir.isDirectory)
        val doubled = Regex("""statusBarsPadding\(\)|systemBarsPadding\(\)|safeDrawingPadding\(\)|safeContentPadding\(\)|WindowInsets\.(statusBars|systemBars|safeDrawing)""")
        val hits = dir.walk().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line -> if (doubled.containsMatchIn(line.substringBefore("//"))) "${f.name}:${i + 1}" else null }
        }.toList()
        assertTrue("top inset of its own in $hits", hits.isEmpty())
    }
}
