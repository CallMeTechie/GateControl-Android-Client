package com.gatecontrol.android.tv.ui.media

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.gatecontrol.android.media.MediaDetail
import com.gatecontrol.android.media.MediaQuality
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaStream
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.MediaVersion
import com.gatecontrol.android.media.jellyfin.JellyfinQuickConnect
import com.gatecontrol.android.media.jellyfin.JellyfinServerHint
import com.gatecontrol.android.media.jellyfin.JellyfinServerInfo
import com.gatecontrol.android.tv.media.TvAddStep
import com.gatecontrol.android.tv.media.TvAddUi
import com.gatecontrol.android.tv.media.TvDetailUi
import com.gatecontrol.android.tv.media.TvMediaLink
import com.gatecontrol.android.tv.media.TvMediaProblem
import com.gatecontrol.android.tv.media.TvMediaUi
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The page "Medien" with the remote: the rows, OK plays on or opens the details, the detail page, adding a source. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
class TvMediaPageTest {

    @get:Rule
    val rule = createComposeRule()

    private val source = MediaSource("s1", MediaSourceKind.JELLYFIN, "DS918+", "http://192.168.1.20:8096", "u1", "Anna", "tok")
    private val kobalt = MediaTitle("m1", "s1", MediaTitle.Kind.MOVIE, "Kobaltnacht", year = 2025, runtimeMs = 6_480_000, positionMs = 2_530_000, genres = listOf("Drama"), rating = "FSK-12", overview = "Eine Funkerin auf einer abgelegenen Wetterstation …")
    private val eisvogel = MediaTitle("e4", "s1", MediaTitle.Kind.EPISODE, "Der Leuchtturm", runtimeMs = 2_700_000, positionMs = 1_620_000, seriesId = "se", seriesName = "Station Eisvogel", season = 2, episode = 4)
    private val sternwarte = MediaTitle("m9", "s1", MediaTitle.Kind.MOVIE, "Sternwarte 9", year = 2026, runtimeMs = 6_720_000)
    private val graufeld = MediaTitle("se3", "s1", MediaTitle.Kind.SERIES, "Graufeld", childCount = 3)

    private val rows = TvMediaUi(
        sources = listOf(source),
        source = source,
        resume = listOf(kobalt, eisvogel),
        latest = listOf(sternwarte, graufeld),
        movies = listOf(
            MediaTitle("m2", "s1", MediaTitle.Kind.MOVIE, "Achterwasser", year = 2019),
            MediaTitle("m3", "s1", MediaTitle.Kind.MOVIE, "Der Pegelwart", year = 2018),
        ),
        moviesTotal = 412,
        series = listOf(MediaTitle("se5", "s1", MediaTitle.Kind.SERIES, "Hofgeflüster", childCount = 4)),
        seriesTotal = 38,
    )

    @Test
    fun `the rows - OK on continue watching plays on, OK on a poster opens it`() {
        val played = mutableListOf<Pair<String, Boolean>>()
        val opened = mutableListOf<String>()
        rule.setContent {
            GateControlTvTheme {
                TvMediaPage(
                    TvMediaScreen(rows, link = TvMediaLink(atHome = false, bps = 18_000_000)),
                    TvMediaActions(onPlay = { t, start -> played += t.id to start }, onOpen = { opened += it.id }),
                )
            }
        }
        rule.onNodeWithText("Jellyfin · DS918+").assertExists()
        rule.onNodeWithText("Away · 18 Mbit/s").assertExists()
        rule.onNodeWithText("Continue watching").assertExists()
        rule.onNodeWithText("2 titles").assertExists()
        rule.onNodeWithText("1:06 left").assertExists()
        rule.onNodeWithText("18 min left").assertExists()
        rule.onNodeWithText("from 42:10").assertExists()
        rule.onNodeWithText("Season 2 · Episode 4").assertExists()
        rule.onNodeWithText("412 · A–Z").assertExists()
        rule.onNodeWithText("Series · 3 seasons").assertExists()
        // the remote starts on the first card of "Weiterschauen"
        rule.onNodeWithContentDescription("Kobaltnacht").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf("m1" to false), played)
        // ☰ on the card: the details
        rule.onRoot().performKeyInput { pressKey(Key.Menu) }
        assertEquals(listOf("m1"), opened)
        // ▼ to "Zuletzt hinzugefügt": OK opens the poster's details
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Sternwarte 9").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf("m1", "m9"), opened)
    }

    @Test
    fun `no source yet, a problem`() {
        var ui by mutableStateOf(TvMediaUi())
        var adding = 0
        rule.setContent { GateControlTvTheme { TvMediaPage(TvMediaScreen(ui), TvMediaActions(onAdd = { adding++ })) } }
        rule.onNodeWithText("Add source").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, adding)
        ui = rows.copy(problem = TvMediaProblem.SIGNED_OUT)
        rule.waitForIdle()
        rule.onNodeWithText("The sign-in at the media server has expired.").assertExists()
        rule.onNodeWithText("Sign in again").assertExists()
    }

    private val version = MediaVersion(
        id = "v1",
        container = "mkv",
        bitrate = 52_000_000,
        streams = listOf(
            MediaStream(0, MediaStream.Type.VIDEO, codec = "hevc", width = 3840, height = 2160, videoRangeType = "HDR10"),
            MediaStream(1, MediaStream.Type.AUDIO, codec = "dts", language = "ger", channels = 6),
            MediaStream(3, MediaStream.Type.SUBTITLE, codec = "subrip", language = "ger", textual = true),
        ),
        defaultAudio = 1,
    )

    @Test
    fun `the detail page away - original or adapted, the suggestion ticked`() {
        val picked = mutableListOf<Boolean>()
        val played = mutableListOf<Pair<String, Boolean>>()
        val offer = MediaQuality.offer(atHome = false, measuredBps = 18_000_000, originalBps = 52_000_000)
        val detail = TvDetailUi(kobalt, MediaDetail(kobalt, listOf(version)), loading = false, audio = 1, subtitle = null, original = false, offer = offer)
        rule.setContent {
            GateControlTvTheme {
                TvMediaPage(
                    TvMediaScreen(rows, detail = detail, link = TvMediaLink(atHome = false, bps = 18_000_000)),
                    TvMediaActions(onOriginal = { picked += it }, onPlay = { t, s -> played += t.id to s }),
                )
            }
        }
        rule.onNodeWithText("Kobaltnacht").assertExists()
        rule.onNodeWithText("2025 · 1 h 48 min · Drama").assertExists()
        rule.onNodeWithText("4K · HDR10").assertExists()
        rule.onNodeWithText("HEVC · mkv").assertExists()
        rule.onNodeWithText("FSK 12").assertExists()
        rule.onNodeWithText("Audio: German · DTS 5.1 ▾").assertExists()
        rule.onNodeWithText("Subtitles: Off ▾").assertExists()
        rule.onNodeWithText("Original (direct)").assertExists()
        rule.onNodeWithText("4K HDR10 · about 52 Mbit/s. More than the 18 Mbit/s away, it would stall.").assertExists()
        rule.onNodeWithText("1080p · 8 Mbit/s · audio stays DTS. Suggested for this connection").assertExists()
        // the remote starts on "Weiter ab 42:10"
        rule.onNodeWithText("Continue from 42:10").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf("m1" to false), played)
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithText("From the start").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals("m1" to true, played.last())
    }

    @Test
    fun `the detail page at home says so`() {
        val offer = MediaQuality.offer(atHome = true, measuredBps = null, originalBps = 52_000_000)
        val detail = TvDetailUi(kobalt, MediaDetail(kobalt, listOf(version)), loading = false, offer = offer)
        rule.setContent { GateControlTvTheme { TvMediaPage(TvMediaScreen(rows, detail = detail, link = TvMediaLink(atHome = true)), TvMediaActions()) } }
        rule.onNodeWithText("Original (direct)").assertExists()
        rule.onNodeWithText("Adapted (the NAS converts)").assertDoesNotExist()
        // the pill above and the line of "Original (direkt)"
        assertEquals(2, rule.onAllNodesWithText("Home network · fast").fetchSemanticsNodes().size)
    }

    @Test
    fun `adding a source - found in the home network, then the Quick Connect code`() {
        var add by mutableStateOf(TvAddUi())
        val calls = mutableListOf<String>()
        rule.setContent {
            GateControlTvTheme {
                TvMediaPage(
                    TvMediaScreen(TvMediaUi(), add = add),
                    TvMediaActions(onJellyfin = { calls += "jellyfin" }, onPick = { calls += it.address }, onLogin = { calls += "login" }),
                )
            }
        }
        rule.onNodeWithText("Jellyfin server").assertExists()
        rule.onNodeWithText("RECOMMENDED").assertExists()
        rule.onNodeWithText("SMB share").assertExists()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf("jellyfin"), calls)

        val hint = JellyfinServerHint("http://192.168.1.20:8096", "srv1", "DS918+")
        add = TvAddUi(step = TvAddStep.ADDRESS, found = listOf(hint), address = hint.address, server = JellyfinServerInfo("DS918+", "10.10.3", "srv1"), serverAddress = hint.address)
        rule.waitForIdle()
        rule.onNodeWithText("192.168.1.20:8096").assertExists()
        rule.onNodeWithText("✓ Found: DS918+ (Jellyfin 10.10.3)").assertExists()
        rule.onNodeWithText("Next").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals("login", calls.last())

        add = add.copy(step = TvAddStep.LOGIN, code = JellyfinQuickConnect("482913", "sec").code, waiting = true)
        rule.waitForIdle()
        rule.onNodeWithText("482 913").assertExists()
        rule.onNodeWithText("Waiting for confirmation …").assertExists()
        rule.onNodeWithText("The access stays encrypted on this TV.").assertExists()
    }
}
