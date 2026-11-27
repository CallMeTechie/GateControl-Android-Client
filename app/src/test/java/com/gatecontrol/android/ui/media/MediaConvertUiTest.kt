package com.gatecontrol.android.ui.media

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import com.gatecontrol.android.media.MediaDetail
import com.gatecontrol.android.media.MediaDeviceCaps
import com.gatecontrol.android.media.MediaGate
import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.MediaLink
import com.gatecontrol.android.media.MediaNet
import com.gatecontrol.android.media.MediaNetwork
import com.gatecontrol.android.media.MediaPhoneSettings
import com.gatecontrol.android.media.MediaPictures
import com.gatecontrol.android.media.MediaPlayLine
import com.gatecontrol.android.media.MediaPlayRequests
import com.gatecontrol.android.media.MediaPlayback
import com.gatecontrol.android.media.MediaPlayerTrouble
import com.gatecontrol.android.media.MediaPlayerUi
import com.gatecontrol.android.media.MediaQueue
import com.gatecontrol.android.media.MediaRowsCache
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaSourceStore
import com.gatecontrol.android.media.MediaStream
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.MediaVersion
import com.gatecontrol.android.media.server.MediaConvert
import com.gatecontrol.android.media.server.MediaConvertHint
import com.gatecontrol.android.media.server.MediaConvertItem
import com.gatecontrol.android.media.server.MediaConvertState
import com.gatecontrol.android.media.server.MediaConvertStatus
import com.gatecontrol.android.ui.theme.GateControlTheme
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * „Videos umwandeln“ on the phone (mockup pictures 5 and 6): the hint on the detail page of a file the phone does not
 * play directly, by the plugin's state; „Für heute Nacht vormerken“; nothing without the plugin; the player's message
 * when every way failed. With `MEDIA_SHOTS=<dir>` the pages are saved there, light and dark (not part of CI).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w411dp-h891dp")
class MediaConvertUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val jellyfin = MediaSource("jf", MediaSourceKind.JELLYFIN, "NAS", "http://192.168.1.20:8096", userId = "u1", userName = "Mila", token = "tok")
    private val id = "0123456789abcdef0123456789abcdef"
    private val werft = MediaTitle(
        id, "jf", MediaTitle.Kind.EPISODE, "Die stille Werft",
        seriesId = "s1", seriesName = "Sternenhafen", season = 1, episode = 5, runtimeMs = 1_320_000, year = 2026,
    )
    private val tenBit = MediaVersion(
        "v1",
        container = "mkv",
        streams = listOf(
            MediaStream(0, MediaStream.Type.VIDEO, codec = "h264", profile = "High 10", bitDepth = 10, width = 1920, height = 1080),
            MediaStream(1, MediaStream.Type.AUDIO, codec = "aac", language = "ger", channels = 2),
        ),
    )
    private val eightBit = tenBit.copy(streams = listOf(MediaStream(0, MediaStream.Type.VIDEO, codec = "h264", profile = "High", bitDepth = 8, width = 1920, height = 1080)))

    private fun hub(lib: FakeLibrary) = mockk<MediaHub>(relaxed = true) {
        val store = mockk<MediaSourceStore>(relaxed = true) {
            every { sources } returns MutableStateFlow(listOf(jellyfin))
            every { source(any()) } returns jellyfin
            every { trackChoice(any()) } returns null
        }
        every { this@mockk.store } returns store
        every { currentSource() } returns jellyfin
        every { shares() } returns emptyList()
        every { link } returns MutableStateFlow(MediaLink(atHome = true))
        every { gate } returns MutableStateFlow(MediaGate.OPEN)
        every { library(any()) } returns lib
        every { caps() } returns MediaDeviceCaps.fromDecoders(listOf("audio/mp4a-latm"), hdrScreen = false, name = "GateControl")
    }

    /** A calm made-up picture (no NAS in the test). */
    private val pictures = mockk<MediaPictures>(relaxed = true) {
        every { key(any(), any()) } answers { "${firstArg<MediaTitle>().id}:${secondArg<Boolean>()}" }
        every { cached(any(), any()) } answers {
            val wide = secondArg<Boolean>()
            val (w, h) = if (wide) 320 to 180 else 200 to 300
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                android.graphics.Canvas(bmp).drawRect(
                    0f, 0f, w.toFloat(), h.toFloat(),
                    android.graphics.Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, w * 0.4f, h.toFloat(), 0xFF1F3A5F.toInt(), 0xFF8C5A3C.toInt(), android.graphics.Shader.TileMode.CLAMP) },
                )
            }
        }
    }

    private fun detail(api: FakeConvertApi, version: MediaVersion = tenBit): MediaDetailViewModel {
        val lib = FakeLibrary(jellyfin)
        lib.details = mapOf(id to MediaDetail(werft, listOf(version)))
        val hub = hub(lib)
        val offline = fakeOffline()
        return MediaDetailViewModel(
            SavedStateHandle(mapOf("source" to "jf", "id" to id, "name" to "Die stille Werft")),
            hub,
            MediaPlayRequests(hub, mockk<MediaPlayback>(relaxed = true), offline),
            mockk<MediaPhoneSettings>(relaxed = true) {
                every { mobileCap } returns MutableStateFlow(3_000_000L)
                every { offlineQuality } returns MutableStateFlow(com.gatecontrol.android.media.offline.OfflineQuality.P1080)
            },
            mockk<MediaNetwork> { every { cellular() } returns false },
            pictures,
            offline,
            MediaRowsCache(null),
            MediaConvert(api),
        )
    }

    private fun show(vm: MediaDetailViewModel, dark: Boolean = false) {
        rule.setContent {
            GateControlTheme(darkTheme = dark) {
                androidx.compose.material3.Surface(Modifier.fillMaxSize().testTag("shot"), color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                    MediaDetailScreen(onBack = {}, onOpen = {}, viewModel = vm)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        val dir = System.getenv("MEDIA_SHOTS") ?: return
        val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun status(item: MediaConvertItem, available: Boolean = true) = MediaConvertStatus(available, mapOf(id to item))

    private fun queued(dark: Boolean) {
        val api = FakeConvertApi(status(MediaConvertItem(id, MediaConvertState.QUEUED, position = 2, startsAt = System.currentTimeMillis() + 3 * 3_600_000L, level = "convert")))
        show(detail(api), dark)
        rule.onNodeWithTag(CONVERT_TAG).assertIsDisplayed()
        rule.onNodeWithText("Wird heute Nacht umgewandelt").assertIsDisplayed()
        rule.onNodeWithText("Platz 2, ab", substring = true).assertIsDisplayed()
        // playing stays possible
        rule.onNodeWithTag(PLAY_TAG).assertIsDisplayed()
        rule.onNodeWithTag(CONVERT_REQUEST_TAG).assertDoesNotExist()
        assertEquals(listOf(listOf(id)), api.asked)
        shot("handy-nacht-" + if (dark) "dunkel" else "hell")
    }

    @Test
    fun `tonight - place and start, playing stays`() = queued(false)

    @Test
    fun `tonight - dark`() = queued(true)

    private fun running(dark: Boolean) {
        show(detail(FakeConvertApi(status(MediaConvertItem(id, MediaConvertState.RUNNING, percent = 42, level = "convert")))), dark)
        rule.onNodeWithText("Wird gerade umgewandelt – 42 %").assertIsDisplayed()
        rule.onNodeWithTag(PLAY_TAG).assertIsDisplayed()
        shot("handy-laeuft-" + if (dark) "dunkel" else "hell")
    }

    @Test
    fun `being converted - with the share done`() = running(false)

    @Test
    fun `being converted - dark`() = running(true)

    private fun offer(dark: Boolean) {
        val api = FakeConvertApi(status(MediaConvertItem(id, level = "convert")), request = MediaConvertItem(id, requested = true))
        show(detail(api), dark)
        rule.onNodeWithText("Wird beim Abspielen umgewandelt").assertIsDisplayed()
        rule.onNodeWithText("Dein Gerät kann dieses Video (H.264 10-Bit (High 10)) nicht selbst abspielen. Jetzt kann es ruckeln. Über Nacht dauerhaft umwandeln?").assertIsDisplayed()
        shot("handy-vormerken-" + if (dark) "dunkel" else "hell")
        rule.onNodeWithTag(CONVERT_REQUEST_TAG).performClick()
        rule.waitForIdle()
        assertEquals(listOf(id), api.requested)
        rule.onNodeWithText("Zum Umwandeln vorgemerkt").assertIsDisplayed()
        rule.onNodeWithTag(CONVERT_REQUEST_TAG).assertDoesNotExist()
        shot("handy-vorgemerkt-" + if (dark) "dunkel" else "hell")
    }

    @Test
    fun `Fuer heute Nacht vormerken - onto the list, then said so`() = offer(false)

    @Test
    fun `Fuer heute Nacht vormerken - dark`() = offer(true)

    @Test
    fun `refused (too many at once) - a short line, the button stays`() {
        show(detail(FakeConvertApi(status(MediaConvertItem(id, level = "convert")))))
        rule.onNodeWithTag(CONVERT_REQUEST_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Das hat nicht geklappt. Bitte später noch einmal versuchen.").assertIsDisplayed()
        rule.onNodeWithTag(CONVERT_REQUEST_TAG).assertIsDisplayed()
    }

    @Test
    fun `converted, without the plugin, no gateway or a file that plays - no hint`() {
        show(detail(FakeConvertApi(status(MediaConvertItem(id, MediaConvertState.DONE, level = "convert")))))
        rule.onNodeWithTag(CONVERT_TAG).assertDoesNotExist()
    }

    @Test
    fun `without the plugin route - no hint, the page as before`() {
        val api = FakeConvertApi()
        show(detail(api))
        rule.onNodeWithTag(CONVERT_TAG).assertDoesNotExist()
        rule.onNodeWithTag(PLAY_TAG).assertIsDisplayed()
    }

    @Test
    fun `no gateway converts - no button to ask`() {
        show(detail(FakeConvertApi(status(MediaConvertItem(id, level = "convert"), available = false))))
        rule.onNodeWithTag(CONVERT_TAG).assertDoesNotExist()
    }

    @Test
    fun `an 8-bit file plays here - the plugin is not even asked`() {
        val api = FakeConvertApi(status(MediaConvertItem(id, MediaConvertState.QUEUED, position = 1)))
        show(detail(api, eightBit))
        rule.onNodeWithTag(CONVERT_TAG).assertDoesNotExist()
        assertEquals(emptyList<List<String>>(), api.asked)
    }

    private val player = MediaPlayerUi(
        queue = MediaQueue("jf", listOf(werft), 0),
        at = 0,
        loading = false,
        line = MediaPlayLine(MediaNet.HOME, video = "1080p"),
        trouble = MediaPlayerTrouble.UNPLAYABLE,
        problem = "H.264 10-Bit (High 10)",
        codecs = "H264 High 10 10 Bit · AAC Stereo · MKV",
    )

    @Test
    fun `the player's message when nothing plays - Fuer heute Nacht vormerken instead of the bare tip`() {
        var asked = 0
        rule.setContent {
            GateControlTheme(darkTheme = true) {
                androidx.compose.material3.Surface(Modifier.fillMaxSize().testTag("shot")) {
                    MediaPlayerScreen(player.copy(convert = MediaConvertHint.Offer), player = null, landscape = false, pip = false, mobileCap = null, actions = MediaPlayerActions(onConvertRequest = { asked++ }))
                }
            }
        }
        rule.onNodeWithText("Dieses Video ist H.264 10-Bit (High 10).", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(PLAYER_CONVERT_TAG).assertIsDisplayed()
        rule.onNodeWithText("Tipp:", substring = true).assertDoesNotExist()
        rule.onNodeWithTag(PLAYER_CONVERT_REQUEST_TAG).performClick()
        assertEquals(1, asked)
        shot("player-vormerken-dunkel")
    }

    @Test
    fun `the player's message - already tonight, no button`() {
        rule.setContent {
            GateControlTheme(darkTheme = true) {
                MediaPlayerScreen(player.copy(convert = MediaConvertHint.Queued(3, null)), player = null, landscape = false, pip = false, mobileCap = null, actions = MediaPlayerActions())
            }
        }
        rule.onNodeWithText("Wird heute Nacht umgewandelt").assertIsDisplayed()
        rule.onNodeWithText("Platz 3. Danach läuft es hier flüssig.").assertIsDisplayed()
        rule.onNodeWithTag(PLAYER_CONVERT_REQUEST_TAG).assertDoesNotExist()
    }

    @Test
    fun `the player's message without the plugin - the tip as before`() {
        rule.setContent {
            GateControlTheme(darkTheme = true) {
                MediaPlayerScreen(player, player = null, landscape = false, pip = false, mobileCap = null, actions = MediaPlayerActions())
            }
        }
        rule.onNodeWithText("Tipp:", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(PLAYER_CONVERT_TAG).assertDoesNotExist()
    }
}
