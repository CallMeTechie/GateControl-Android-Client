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
 * Fernzugriff stage 6 „Android steuern“ in the card „Bildschirm freigeben“ — control not set up yet, the hint before
 * Android's accessibility settings, the question with the clear choice „Nur ansehen lassen“, the red bar while the
 * helper controls the phone with „Nur ansehen“ — light and dark. `REMOTE_SHOTS=<dir>` saves the pictures (not in CI).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w390dp-h844dp")
class ScreenControlCardUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val now = 1_763_280_000_000L
    private val content = mutableStateOf<@Composable () -> Unit>({})
    private val dark = mutableStateOf(false)
    private var setup = 0
    private var viewOnly = 0
    private var stopped = 0

    private fun page(content: @Composable () -> Unit) {
        this.content.value = content
        rule.setContent {
            GateControlTheme(darkTheme = dark.value) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize().testTag("shot")) {
                    Box(Modifier.padding(16.dp)) { this@ScreenControlCardUiTest.content.value() }
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

    private fun both(name: String, block: () -> Unit) {
        for (d in listOf(false, true)) {
            dark.value = d
            rule.waitForIdle()
            block()
            shot(name)
        }
    }

    private val actions = ScreenShareActions(onStop = { stopped++ }, onViewOnly = { viewOnly++ }, onSetupControl = { setup++ })
    private val code = ScreenCode("482-917-305", now + 6 * 60_000L)

    @Test
    fun `control not set up — the card says so and leads to the setup`() {
        page { ScreenShareCard(ScreenShareUi(available = true, enabled = true, code = code, controlSupported = true, controlReady = false), now, actions) }
        both("control-off") {
            rule.onNodeWithText("Steuerung ist noch aus").assertIsDisplayed()
            rule.onNodeWithText("Wer hilft, könnte nur zusehen. Zum Steuern braucht GateControl einen Bedienungshilfe-Dienst.").assertIsDisplayed()
        }
        rule.onNodeWithText("Steuerung einrichten").performClick()
        assertEquals(1, setup)
    }

    @Test
    fun `control set up — the card says the person decides each time`() {
        page { ScreenShareCard(ScreenShareUi(available = true, enabled = true, code = code, controlSupported = true, controlReady = true), now, actions) }
        both("control-ready") {
            rule.onNodeWithTag("screen-control-ready").assertIsDisplayed()
            rule.onNodeWithText("Steuerung ist eingerichtet").assertIsDisplayed()
        }
    }

    @Test
    fun `the Play build shows no control at all`() {
        page { ScreenShareCard(ScreenShareUi(available = true, enabled = true, code = code, controlSupported = false), now, actions) }
        rule.onNodeWithText("482 917 305").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTagCount("screen-control-off") + rule.onAllNodesWithTagCount("screen-control-ready"))
    }

    @Test
    fun `the hint before the accessibility settings — what it does, off at any time, the way for an app from GitHub`() {
        var go = 0
        var info = 0
        var later = 0
        page { ScreenControlSetupDialog(onSettings = { go++ }, onAppInfo = { info++ }, onDismiss = { later++ }) }
        both("control-disclosure") {
            rule.onNodeWithText("Steuerung erlauben").assertIsDisplayed()
            rule.onNodeWithText("Damit eine Person, der du einen Code gibst, dein Handy bedienen kann, nutzt GateControl einen Bedienungshilfe-Dienst. Er tippt und wischt nur während einer Sitzung, die du bestätigt hast. Er liest keine Inhalte und sendet nichts außerhalb einer Sitzung.").assertIsDisplayed()
            rule.onNodeWithText("Jederzeit abschaltbar in den Einstellungen von Android › Bedienungshilfen.").assertIsDisplayed()
        }
        rule.onNodeWithTag("screen-control-app-info").performClick()
        rule.onNodeWithTag("screen-control-later").performClick()
        rule.onNodeWithTag("screen-control-go").performClick()
        assertEquals(listOf(1, 1, 1), listOf(go, info, later))
    }

    @Test
    fun `a question to see and control — the clear choice Nur ansehen lassen`() {
        var answer: Boolean? = null
        val req = ScreenRequest("rq_abcdefgh12", "Tom Berger", null, now + 24_000L, control = true)
        page { ScreenRequestDialog(req, 24, onAllow = { answer = it }, onDeny = {}, canControl = true, controlSupported = true) }
        both("control-ask") {
            rule.onNodeWithText("Tom Berger möchte deinen Bildschirm sehen und steuern").assertIsDisplayed()
            rule.onNodeWithText("Nur ansehen lassen").assertIsDisplayed()
            rule.onNodeWithText("Tom Berger kann dann nichts antippen.").assertIsDisplayed()
        }
        // without the tick: control
        rule.onNodeWithTag("screen-allow").performClick()
        assertEquals(true, answer)
        // with the tick: view only
        rule.onNodeWithTag("screen-view-only-choice").performClick()
        rule.onNodeWithTag("screen-allow").performClick()
        assertEquals(false, answer)
    }

    @Test
    fun `a question to control while control is off — it stays at watching and says so`() {
        var answer: Boolean? = null
        val req = ScreenRequest("rq_abcdefgh12", "Tom Berger", null, now + 24_000L, control = true)
        page { ScreenRequestDialog(req, 24, onAllow = { answer = it }, onDeny = {}, canControl = false, controlSupported = true) }
        both("control-ask-off") {
            rule.onNodeWithText("Steuerung ist noch aus. Tom Berger kann nur zusehen – richte die Steuerung in der Karte ein, wenn du das möchtest.").assertIsDisplayed()
        }
        rule.onNodeWithTag("screen-allow").performClick()
        assertEquals(false, answer)
    }

    @Test
    fun `controlled — the red bar and the card say who controls, Nur ansehen takes it away, Beenden ends`() {
        val s = ScreenShareUi(available = true, enabled = true, controlSupported = true, controlReady = true,
            live = ScreenLive("rq_abcdefgh12", "Tom Berger", now - 4 * 60_000L, true, control = true))
        page {
            Column {
                ScreenShareLiveBar("Tom Berger", joined = true, onStop = actions.onStop, control = true, onViewOnly = actions.onViewOnly)
                ScreenShareCard(s, now, actions)
            }
        }
        both("control-live") {
            rule.onNodeWithText("Tom Berger steuert deinen Bildschirm").assertIsDisplayed()
            rule.onNodeWithText("Steuern beenden – Tom Berger sieht weiter zu.").assertIsDisplayed()
        }
        rule.onNodeWithTag("screen-bar-view-only").performClick()
        rule.onNodeWithTag("screen-view-only").performClick()
        assertEquals(2, viewOnly)
        rule.onNodeWithText("Sitzung beenden").performClick()
        assertEquals(1, stopped)
    }

    @Test
    fun `watching only after the switch — no control buttons any more`() {
        val s = ScreenShareUi(available = true, enabled = true, controlSupported = true, controlReady = true,
            live = ScreenLive("rq_abcdefgh12", "Tom Berger", now - 4 * 60_000L, true, control = false))
        page {
            Column {
                ScreenShareLiveBar("Tom Berger", joined = true, onStop = actions.onStop, control = false)
                ScreenShareCard(s, now, actions)
            }
        }
        rule.onNodeWithText("Tom Berger sieht deinen Bildschirm").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTagCount("screen-view-only") + rule.onAllNodesWithTagCount("screen-bar-view-only"))
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
