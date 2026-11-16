package com.gatecontrol.android.ui.remote

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.remote.ScreenCode
import com.gatecontrol.android.remote.ScreenEnded
import com.gatecontrol.android.remote.ScreenLive
import com.gatecontrol.android.remote.ScreenRequest
import com.gatecontrol.android.remote.ScreenShareUi
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
 * The card „Bildschirm freigeben“ (Fernzugriff, stage 5) in its states — off, code, a question, live, after a
 * session — drawn light and dark (design section 5). With `REMOTE_SHOTS=<dir>` the pictures are saved there (for a
 * look, not part of CI); the assertions check what the person must always see.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w390dp-h844dp")
class ScreenShareCardUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val now = 1_763_280_000_000L
    private val content = mutableStateOf<@Composable () -> Unit>({})
    private val dark = mutableStateOf(false)
    private var stopped = 0
    private var toggled: Boolean? = null

    private fun page(content: @Composable () -> Unit) {
        this.content.value = content
        rule.setContent {
            GateControlTheme(darkTheme = dark.value) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize().testTag("shot")) {
                    Box(Modifier.padding(16.dp)) { this@ScreenShareCardUiTest.content.value() }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
        assertTrue("drawn", bmp.width > 0 && bmp.height > 0)
        val dir = System.getenv("REMOTE_SHOTS") ?: return
        File(dir).mkdirs()
        File(dir, "$name-${if (dark.value) "dark" else "light"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val actions = ScreenShareActions(onToggle = { toggled = it }, onStop = { stopped++ })

    private fun both(name: String, block: () -> Unit) {
        for (d in listOf(false, true)) {
            dark.value = d
            rule.waitForIdle()
            block()
            shot(name)
        }
    }

    @Test
    fun `off — only the switch, it turns sharing on`() {
        page { ScreenShareCard(ScreenShareUi(available = true), now, actions) }
        both("off") {
            rule.onNodeWithText("Bildschirm freigeben").assertIsDisplayed()
            rule.onNodeWithText("Für Hilfe von einer Person aus GateControl").assertIsDisplayed()
        }
        rule.onNodeWithTag("screen-share").assertIsDisplayed()
    }

    @Test
    fun `on — the code in three groups, the time left, the switch for no code`() {
        val s = ScreenShareUi(available = true, enabled = true, code = ScreenCode("482-917-305", now + 6 * 60_000L))
        page { ScreenShareCard(s, now, actions) }
        both("code") {
            rule.onNodeWithText("482 917 305").assertIsDisplayed()
            rule.onNodeWithText("Nenne diesen Code der Person, die dir helfen soll. Er gilt noch 6 Minuten und nur einmal.").assertIsDisplayed()
            rule.onNodeWithText("Ohne Code für Berechtigte").assertIsDisplayed()
        }
    }

    @Test
    fun `a question — name, view only, the seconds left, Allow and Decline`() {
        var answer: Boolean? = null
        page {
            Column {
                ScreenShareCard(ScreenShareUi(available = true, enabled = true, code = ScreenCode("482-917-305", now + 6 * 60_000L)), now, actions)
                ScreenRequestDialog(ScreenRequest("rq_abcdefgh12", "Tom Berger", null, now + 24_000L), 24, onAllow = { answer = true }, onDeny = { answer = false })
            }
        }
        both("ask") {
            rule.onNodeWithText("Tom Berger möchte deinen Bildschirm sehen").assertIsDisplayed()
            rule.onNodeWithText("Nur ansehen · noch 24 s").assertIsDisplayed()
        }
        rule.onNodeWithTag("screen-allow").performClick()
        assertEquals(true, answer)
        rule.onNodeWithTag("screen-deny").performClick()
        assertEquals(false, answer)
    }

    @Test
    fun `live — the red bar and the card say who watches, both stop it`() {
        val s = ScreenShareUi(available = true, enabled = true, live = ScreenLive("rq_abcdefgh12", "Tom Berger", now - 4 * 60_000L, true))
        page {
            Column {
                ScreenShareLiveBar("Tom Berger", joined = true, onStop = actions.onStop)
                ScreenShareCard(s, now, actions)
            }
        }
        both("live") {
            rule.onNodeWithText("Tom Berger sieht deinen Bildschirm").assertIsDisplayed()
            rule.onNodeWithText("Sitzung beenden").assertIsDisplayed()
        }
        rule.onNodeWithText("Beenden").performClick()
        rule.onNodeWithText("Sitzung beenden").performClick()
        assertEquals(2, stopped)
    }

    @Test
    fun `after a session — a new code at once and who was connected`() {
        val s = ScreenShareUi(
            available = true, enabled = true, code = ScreenCode("736-205-148", now + 10 * 60_000L),
            ended = ScreenEnded("Tom Berger", 12, now),
        )
        page { ScreenShareCard(s, now, actions) }
        both("after") {
            rule.onNodeWithText("Sitzung beendet. Neuer Code bereit. Tom Berger war 12 Minuten verbunden.").assertIsDisplayed()
            rule.onNodeWithText("736 205 148").assertIsDisplayed()
        }
    }

    @Test
    fun `an error is said in words`() {
        page { ScreenShareCard(ScreenShareUi(available = true, error = "no_share_right"), now, actions) }
        rule.onNodeWithText("Du darfst keine eigenen Geräte freigeben. Frag einen Administrator.").assertIsDisplayed()
        assertEquals(null, toggled)
    }
}
