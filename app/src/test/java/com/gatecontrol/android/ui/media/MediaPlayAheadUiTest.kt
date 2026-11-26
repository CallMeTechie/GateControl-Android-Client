package com.gatecontrol.android.ui.media

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.gatecontrol.android.media.MediaAwayFit
import com.gatecontrol.android.media.MediaAwayOriginal
import com.gatecontrol.android.media.MediaNet
import com.gatecontrol.android.media.MediaPlayAheadState
import com.gatecontrol.android.media.MediaPlayLine
import com.gatecontrol.android.media.MediaPlayerUi
import com.gatecontrol.android.media.MediaQueue
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.theme.GateControlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * „Unterwegs mitladen“ on the phone (part 2, stage 1) in German: „Wie abspielen?“ when the original does not go through
 * away (decision 89 b), the card „Auf dem Gerät mitgeladen“ in the player, the waiting of „Original mit Mitladen“ and
 * the settings. With `MEDIA_SHOTS=<dir>` the drawn parts are saved there (for a look, not part of CI).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w411dp-h891dp")
class MediaPlayAheadUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val film = MediaTitle("m1", "jf", MediaTitle.Kind.MOVIE, "Kobaltnacht", year = 2025, runtimeMs = 124 * 60_000L)
    private val ui = MediaPlayerUi(
        queue = MediaQueue("jf", listOf(film), 0),
        at = 0,
        sourceName = "NAS",
        loading = false,
        line = MediaPlayLine(MediaNet.AWAY, video = "1080p", measuredBps = 9_000_000),
    )
    private val fit = MediaAwayFit(originalBps = 10_500_000, lineBps = 9_000_000, waitMs = 18 * 60_000L, convertedBps = 4_000_000)

    private fun shot(name: String) {
        val dir = System.getenv("MEDIA_SHOTS") ?: return
        val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun Frame(dark: Boolean, content: @Composable () -> Unit) {
        GateControlTheme(darkTheme = dark) {
            Box(Modifier.background(MaterialTheme.colorScheme.surface).testTag("shot")) { content() }
        }
    }

    private fun away(dark: Boolean, answers: MutableList<MediaAwayOriginal?>) {
        rule.setContent {
            Frame(dark) {
                var chosen by remember { mutableStateOf(MediaAwayOriginal.WAIT) }
                AwayChoices(ui, fit, chosen, onChoose = { chosen = it }, onAway = { answers += it })
            }
        }
    }

    @Test
    fun `Wie abspielen - converted, with Mitladen and the wait, or at once`() {
        val answers = mutableListOf<MediaAwayOriginal?>()
        away(dark = false, answers)
        rule.onNodeWithText("Wie abspielen?").assertIsDisplayed()
        rule.onNodeWithText("Unterwegs · Heimanschluss schafft ca. 9 Mbit/s").assertIsDisplayed()
        rule.onNodeWithText("Sofort, umgewandelt").assertIsDisplayed()
        rule.onNodeWithText("720p, ca. 4 Mbit/s · die NAS wandelt beim Abspielen um").assertIsDisplayed()
        rule.onNode(hasText("Start in ca. 18 Min., dann ohne Ruckeln", substring = true)).assertIsDisplayed()
        rule.onNodeWithText("Mitladen starten").assertIsDisplayed()
        shot("handy-abspielen-wahl-hell")
        rule.onNodeWithText("Original sofort").performClick()
        rule.onNodeWithText("Abspielen").performClick()
        rule.onNodeWithText("Abbrechen").performClick()
        assertEquals(listOf(MediaAwayOriginal.NOW, null), answers)
    }

    @Test
    fun `Wie abspielen - dark`() {
        away(dark = true, mutableListOf())
        rule.onNodeWithTag(AWAY_WAIT_TAG).assertIsDisplayed()
        shot("handy-abspielen-wahl-dunkel")
    }

    @Test
    fun `too much for the space - no waiting offered`() {
        val answers = mutableListOf<MediaAwayOriginal?>()
        rule.setContent {
            Frame(false) { AwayChoices(ui, fit.copy(waitMs = null), MediaAwayOriginal.CONVERTED, onChoose = {}, onAway = { answers += it }) }
        }
        rule.onNodeWithText("Original mit Mitladen").assertDoesNotExist()
        rule.onNodeWithTag(AWAY_GO_TAG).performClick()
        assertEquals(listOf(MediaAwayOriginal.CONVERTED), answers)
    }

    private val state = MediaPlayAheadState(aheadBytes = 2_300_000_000, totalBytes = 9_800_000_000, headBytes = 2_700_000_000, rateBps = 9_000_000)

    private fun card(dark: Boolean) {
        rule.setContent { Frame(dark) { Column { AheadCard(state, bitrate = 10_500_000) } } }
        rule.onNodeWithText("Auf dem Gerät mitgeladen").assertIsDisplayed()
        rule.onNodeWithText("läuft").assertIsDisplayed()
        rule.onNode(hasText("GB voraus", substring = true)).assertIsDisplayed()
        // 2.3 GB at 10.5 Mbit/s: about 29 min
        rule.onNodeWithText("lädt mit 9 Mbit/s · reicht für ca. 30 Min. · wird nach dem Ansehen gelöscht").assertIsDisplayed()
        shot(if (dark) "handy-player-mitgeladen-dunkel" else "handy-player-mitgeladen-hell")
    }

    @Test
    fun `the card Auf dem Geraet mitgeladen`() = card(dark = false)

    @Test
    fun `the card Auf dem Geraet mitgeladen - dark`() = card(dark = true)

    @Test
    fun `the player with the card and the waiting of Original mit Mitladen`() {
        var now = 0
        rule.setContent {
            GateControlTheme(darkTheme = false) {
                Box(Modifier.testTag("shot")) {
                    MediaPlayerScreen(
                        ui.copy(ahead = state, aheadBitrate = 10_500_000, aheadWait = true, aheadWaitMs = 12 * 60_000L),
                        player = null,
                        landscape = false,
                        pip = false,
                        mobileCap = null,
                        actions = MediaPlayerActions(onStartNow = { now++ }),
                    )
                }
            }
        }
        rule.onNodeWithText("Lädt mit … Start in ca. 12 Min.").assertIsDisplayed()
        rule.onNodeWithTag(AHEAD_CARD_TAG).performScrollTo().assertIsDisplayed()
        shot("handy-player-wartet-hell")
        rule.onNodeWithText("Jetzt abspielen").performClick()
        assertEquals(1, now)
    }

    @Test
    fun `the settings - Unterwegs mitladen and what plays when the original does not fit`() {
        val gbs = mutableListOf<Int>()
        val choices = mutableListOf<MediaAwayOriginal>()
        rule.setContent {
            Frame(false) {
                GcCard {
                    AheadRow(4) { gbs += it }
                    AwayOriginalRow(MediaAwayOriginal.ASK) { choices += it }
                }
            }
        }
        rule.onNodeWithText("Unterwegs mitladen").assertIsDisplayed()
        rule.onNodeWithText("4 GB").assertIsDisplayed()
        rule.onNodeWithText("Original passt unterwegs nicht").assertIsDisplayed()
        rule.onNodeWithText("Fragen").assertIsDisplayed()
        shot("handy-einstellungen-mitladen-hell")
        rule.onNodeWithTag(AHEAD_SETTING_TAG).performClick()
        rule.onNodeWithText("Aus").performClick()
        assertEquals(listOf(0), gbs)
        rule.onNodeWithText("Fragen").performClick()
        rule.onNodeWithText("Original sofort").performClick()
        assertEquals(listOf(MediaAwayOriginal.NOW), choices)
    }
}
