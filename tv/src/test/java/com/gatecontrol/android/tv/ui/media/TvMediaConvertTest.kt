package com.gatecontrol.android.tv.ui.media

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.media.MediaDetail
import com.gatecontrol.android.media.MediaLink
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaStream
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.MediaVersion
import com.gatecontrol.android.media.server.MediaConvertHint
import com.gatecontrol.android.tv.media.TvDetailUi
import com.gatecontrol.android.tv.media.TvMediaUi
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * „Videos umwandeln“ on GateControl TV (mockup picture 7): the hint above the buttons of a file the TV does not play
 * directly, „Für heute Nacht vormerken“ reached with the remote, playing still possible. With `MEDIA_SHOTS=<dir>` the
 * page is saved there (the TV has only its dark look).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], application = Application::class, qualifiers = "de-w960dp-h540dp-land-xhdpi")
class TvMediaConvertTest {

    @get:Rule
    val rule = createComposeRule()

    private val source = MediaSource("s1", MediaSourceKind.JELLYFIN, "NAS", "http://192.168.1.20:8096", "u1", "Mila", "tok")
    private val werft = MediaTitle(
        "0123456789abcdef0123456789abcdef", "s1", MediaTitle.Kind.EPISODE, "Die stille Werft",
        seriesId = "se", seriesName = "Sternenhafen", season = 1, episode = 5, runtimeMs = 1_320_000, year = 2026,
    )
    private val version = MediaVersion(
        "v1",
        container = "mkv",
        streams = listOf(MediaStream(0, MediaStream.Type.VIDEO, codec = "h264", profile = "High 10", bitDepth = 10, width = 1920, height = 1080)),
    )

    private fun detail(hint: MediaConvertHint?, failed: Boolean = false) =
        TvDetailUi(werft, MediaDetail(werft, listOf(version)), loading = false, convert = hint, convertProblem = "H.264 10-Bit (High 10)", convertFailed = failed)

    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        GateControlTvTheme {
            Box(Modifier.fillMaxSize().background(GateControlTvTheme.colors.bg).testTag("shot").padding(start = 30.dp, top = 24.dp)) { content() }
        }
    }

    private fun show(hint: MediaConvertHint?, actions: TvMediaActions = TvMediaActions()) {
        rule.setContent { Frame { TvMediaPage(TvMediaScreen(TvMediaUi(sources = listOf(source), source = source), detail = detail(hint), link = MediaLink(atHome = true)), actions) } }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        val dir = System.getenv("MEDIA_SHOTS") ?: return
        val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `tonight with place and start - the focus on Andere Folge, playing still below`() {
        val played = mutableListOf<String>()
        var others = 0
        show(MediaConvertHint.Queued(2, null), TvMediaActions(onPlay = { t, _ -> played += t.id }, onOther = { others++ }))
        rule.onNodeWithTag(TV_CONVERT_TAG).assertExists()
        rule.onNodeWithText("Wird heute Nacht umgewandelt").assertExists()
        rule.onNodeWithText("Platz 2. Danach läuft es hier flüssig.").assertExists()
        rule.onNodeWithTag(TV_CONVERT_REQUEST_TAG).assertDoesNotExist()
        // OK never starts a stuttering playback: the focus is on „Andere Folge“
        rule.onNodeWithText("Andere Folge").assertIsFocused()
        shot("tv-nacht-dunkel")
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, others)
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithText("Abspielen").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf(werft.id), played)
    }

    @Test
    fun `a film - Anderer Film`() {
        rule.setContent {
            Frame {
                val film = werft.copy(kind = MediaTitle.Kind.MOVIE, seriesId = null, seriesName = null, season = null, episode = null)
                TvMediaPage(
                    TvMediaScreen(TvMediaUi(sources = listOf(source), source = source), detail = detail(MediaConvertHint.Running(10)).copy(title = film), link = MediaLink(atHome = true)),
                    TvMediaActions(),
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Anderer Film").assertIsFocused()
    }

    @Test
    fun `being converted with the share done`() {
        show(MediaConvertHint.Running(42))
        rule.onNodeWithText("Wird gerade umgewandelt – 42 %").assertExists()
        shot("tv-laeuft-dunkel")
    }

    @Test
    fun `Fuer heute Nacht vormerken is reached with the remote`() {
        var asked = 0
        show(MediaConvertHint.Offer, TvMediaActions(onConvertRequest = { asked++ }))
        rule.onNodeWithText("Wird beim Abspielen umgewandelt").assertExists()
        rule.onNodeWithText("Der Fernseher kann dieses Video (H.264 10-Bit (High 10)) nicht selbst abspielen. Jetzt kann es ruckeln. Über Nacht dauerhaft umwandeln?").assertExists()
        rule.onNodeWithText("Andere Folge").assertIsFocused()
        shot("tv-vormerken-dunkel")
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithText("Für heute Nacht vormerken").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, asked)
    }

    @Test
    fun `asked for - said so, no button`() {
        show(MediaConvertHint.Requested)
        rule.onNodeWithText("Zum Umwandeln vorgemerkt").assertExists()
        rule.onNodeWithTag(TV_CONVERT_REQUEST_TAG).assertDoesNotExist()
    }

    @Test
    fun `no hint without the plugin`() {
        show(null)
        rule.onNodeWithTag(TV_CONVERT_TAG).assertDoesNotExist()
        rule.onNodeWithText("Abspielen").assertIsFocused()
    }
}
