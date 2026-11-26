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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.gatecontrol.android.media.MediaDetail
import com.gatecontrol.android.media.MediaGate
import com.gatecontrol.android.media.MediaLink
import com.gatecontrol.android.media.MediaRoute
import com.gatecontrol.android.media.server.MediaMigration
import com.gatecontrol.android.media.server.MediaServerState
import com.gatecontrol.android.media.server.MediaSourcesLogic
import com.gatecontrol.android.media.MediaProblem
import com.gatecontrol.android.media.MediaQuality
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaStream
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.MediaVersion
import com.gatecontrol.android.tv.media.TvDetailUi
import com.gatecontrol.android.tv.media.TvMediaUi
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The page "Medien" with the remote: the rows, OK plays on or opens the details, the detail page, the server's sources. */
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
        latestMovies = listOf(sternwarte),
        latestEpisodes = listOf(graufeld.copy(newCount = 3)),
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
                    TvMediaScreen(rows, link = MediaLink(atHome = false, bps = 18_000_000)),
                    TvMediaActions(onPlay = { t, start -> played += t.id to start }, onOpen = { opened += it.id }),
                )
            }
        }
        // the kind only, never the server's name
        rule.onNodeWithText("Jellyfin").assertExists()
        rule.onNodeWithText("Jellyfin · DS918+").assertDoesNotExist()
        rule.onNodeWithText("Away · 18 Mbit/s").assertExists()
        rule.onNodeWithText("Continue watching").assertExists()
        rule.onNodeWithText("2 titles").assertExists()
        rule.onNodeWithText("1:06 left").assertExists()
        rule.onNodeWithText("18 min left").assertExists()
        rule.onNodeWithText("from 42:10").assertExists()
        rule.onNodeWithText("Season 2 · Episode 4").assertExists()
        rule.onNodeWithText("412 · A–Z").assertExists()
        rule.onNodeWithText("Series · 3 seasons").assertExists()
        rule.onNodeWithText("New films").assertExists()
        rule.onNodeWithText("New episodes").assertExists()
        rule.onNodeWithText("3 new").assertExists()
        // the remote starts on the first card of "Weiterschauen"
        rule.onNodeWithContentDescription("Kobaltnacht").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf("m1" to false), played)
        // ☰ on the card: the details
        rule.onRoot().performKeyInput { pressKey(Key.Menu) }
        assertEquals(listOf("m1"), opened)
        // ▼ to "Neue Filme": OK opens the poster's details
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Sternwarte 9").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf("m1", "m9"), opened)
    }

    @Test
    fun `no source yet, a problem`() {
        var ui by mutableStateOf(TvMediaUi())
        var repaired = 0
        rule.setContent { GateControlTvTheme { TvMediaPage(TvMediaScreen(ui), TvMediaActions(onRepair = { repaired++ })) } }
        // nothing to set up at the TV: the hint and the sources, no „Quelle hinzufügen“
        rule.onNodeWithText("Sources are set up by the admin in the GateControl server. As soon as one is shared with you it shows here by itself.").assertExists()
        assertEquals(0, rule.onAllNodesWithText("Add source").fetchSemanticsNodes().size)
        ui = rows.copy(problem = MediaProblem.SIGNED_OUT)
        rule.waitForIdle()
        rule.onNodeWithText("The sign-in at the media server has expired.").assertExists()
        rule.onNodeWithText("Sign in again").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, repaired)
        // running: „Anmelden …“; failed: the reason in the words of Medien › Quellen, then „Erneut versuchen“
        ui = rows.copy(problem = MediaProblem.SIGNED_OUT, signingIn = true)
        rule.waitForIdle()
        rule.onNodeWithText("Signing in …").assertExists()
        ui = rows.copy(problem = MediaProblem.SIGNED_OUT, signInProblem = "quick_connect_off")
        rule.waitForIdle()
        rule.onNodeWithText("Quick Connect is off in Jellyfin. The admin must switch it on.").assertExists()
        rule.onNodeWithText("Try again").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(2, repaired)
    }

    @Test
    fun `Neu anmelden of a source set up by hand - Medien - Quellen opens, once`() {
        var ui by mutableStateOf(rows.copy(problem = MediaProblem.SIGNED_OUT))
        var opened = 0
        rule.setContent {
            GateControlTvTheme { TvMediaPage(TvMediaScreen(ui), TvMediaActions(onSourcesOpened = { opened++; ui = ui.copy(openSources = false) })) }
        }
        rule.onNodeWithText("Sign in again").assertExists()
        ui = ui.copy(openSources = true)
        rule.waitForIdle()
        assertEquals(1, opened)
        rule.onNodeWithText("Media › Sources").assertExists()
    }

    @Test
    fun `away without the VPN - VPN einschalten instead of the rows`() {
        var vpn = 0
        rule.setContent {
            GateControlTvTheme { TvMediaPage(TvMediaScreen(TvMediaUi(sources = listOf(source), source = source), gate = MediaGate.BLOCKED), TvMediaActions(onVpn = { vpn++ })) }
        }
        rule.onNodeWithText("Away, media go only through your GateControl VPN.").assertExists()
        rule.onNodeWithText("Switch VPN on").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, vpn)
    }

    @Test
    fun `once after the update - Media come from the server now`() {
        var done = 0
        val server = MediaServerState(migration = listOf(MediaMigration("old", MediaSourceKind.JELLYFIN, "Jellyfin · DS918+", "DS918+"), MediaMigration("x", MediaSourceKind.WEBDAV, "192.168.1.30/privat", null)))
        rule.setContent { GateControlTvTheme { TvMediaPage(TvMediaScreen(rows, server = server), TvMediaActions(onMigrationDone = { done++ })) } }
        rule.onNodeWithText("Media come from the server now").assertExists()
        rule.onNodeWithText("taken over, same server").assertExists()
        rule.onNodeWithText("not in the server · will be removed").assertExists()
        rule.onNodeWithText("Got it").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, done)
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
                    TvMediaScreen(rows, detail = detail, link = MediaLink(atHome = false, bps = 18_000_000)),
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
        rule.setContent { GateControlTvTheme { TvMediaPage(TvMediaScreen(rows, detail = detail, link = MediaLink(atHome = true)), TvMediaActions()) } }
        rule.onNodeWithText("Original (direct)").assertExists()
        rule.onNodeWithText("Adapted (the NAS converts)").assertDoesNotExist()
        // the pill above and the line of "Original (direkt)"
        assertEquals(2, rule.onAllNodesWithText("Home network · fast").fetchSemanticsNodes().size)
    }

    private val share = MediaSource("smb1", MediaSourceKind.SMB, "nas", "smb://nas/filme", userName = "kino", password = "pw")
    private val lindqvist = MediaTitle("f:l.mkv", "smb1", MediaTitle.Kind.EPISODE, "Folge 7", seriesName = "Die Lindqvist-Akten", season = 1, episode = 7, positionMs = 60_000, runtimeMs = 2_520_000, fromShare = true)
    private val kupfer = MediaTitle("f:k.mkv", "smb1", MediaTitle.Kind.MOVIE, "Kupferstadt", year = 2025, fromShare = true)

    @Test
    fun `titles of a share - NAS on the picture, the share in the header`() {
        val ui = rows.copy(
            shares = listOf(share),
            resume = listOf(kobalt, lindqvist),
            movies = rows.movies + kupfer,
        )
        val opened = mutableListOf<String>()
        rule.setContent { GateControlTvTheme { TvMediaPage(TvMediaScreen(ui, link = MediaLink(atHome = true)), TvMediaActions(onOpen = { opened += it.id })) } }
        rule.onNodeWithText("Jellyfin").assertExists()
        rule.onNodeWithText("NAS share").assertExists()
        rule.onNodeWithText("NAS share · \\\\nas\\filme").assertDoesNotExist()
        // the share's two titles carry the mark, Jellyfin's none
        assertEquals(2, rule.onAllNodesWithText("NAS").fetchSemanticsNodes().size)
        rule.onNodeWithText("Kupferstadt").assertExists()
    }

    @Test
    fun `a share that cannot be read says so in the header`() {
        val ui = TvMediaUi(sources = listOf(share), shares = listOf(share), shareProblems = setOf("smb1"))
        rule.setContent { GateControlTvTheme { TvMediaPage(TvMediaScreen(ui), TvMediaActions()) } }
        rule.onNodeWithText("NAS share not reachable").assertExists()
        rule.onNodeWithText("Nothing in this library yet.").assertExists()
    }

    @Test
    fun `the detail page of a share's film - only the original`() {
        val version = MediaVersion("f:k.mkv", container = "mkv", supportsDirectPlay = true)
        val offer = MediaQuality.offer(atHome = false, measuredBps = 18_000_000, originalBps = null, canConvert = false)
        val detail = TvDetailUi(kupfer, MediaDetail(kupfer, listOf(version)), loading = false, offer = offer)
        rule.setContent { GateControlTvTheme { TvMediaPage(TvMediaScreen(rows, detail = detail, link = MediaLink(atHome = false, bps = 18_000_000)), TvMediaActions()) } }
        rule.onNodeWithText("Kupferstadt").assertExists()
        rule.onNodeWithText("NAS").assertExists()
        rule.onNodeWithText("Original (direct)").assertExists()
        rule.onNodeWithText("Adapted (the NAS converts)").assertDoesNotExist()
        // no sound or subtitle list of a server: the player shows the file's own
        rule.onNodeWithText("Subtitles: Off ▾").assertDoesNotExist()
        rule.onNodeWithText("Play").assertIsFocused()
    }

    @Test
    fun `the sources - a display with their status, Always original only for Jellyfin`() {
        val managed = source.copy(managed = true, remoteId = "jf-1", userName = "mila", deviceName = "GateControl · Wohnzimmer-TV")
        val smb = MediaSource("sh-1", MediaSourceKind.SMB, "Filme", "smb://192.168.1.20/filme", userName = "medien-lesen", password = "pw", managed = true, remoteId = "sh-1", pairWith = "sh-1~webdav")
        val dav = MediaSource("sh-1~webdav", MediaSourceKind.WEBDAV, "Filme", "https://192.168.1.20:5006/filme", userName = "medien-lesen", password = "pw", managed = true, remoteId = "sh-1", pairWith = "sh-1")
        val list = listOf(managed, smb, dav)
        val rows = MediaSourcesLogic.rows(list, MediaServerState(known = true, tab = true), route = { MediaRoute.HOME })
        var reloaded = 0
        rule.setContent { GateControlTvTheme { TvSourcesPage(rows, MediaServerState(known = true, tab = true), managed, TvMediaActions(onReload = { reloaded++ }), onClose = {}) } }
        rule.onNodeWithText("Sources are set up by the admin in the GateControl server.").assertExists()
        rule.onNodeWithText("Jellyfin · DS918+").assertExists()
        rule.onNodeWithText("signed in as mila · at home, straight in the home network").assertExists()
        rule.onNodeWithText("Filme (NAS)").assertExists()
        rule.onNodeWithText("at home SMB, away WebDAV · at home, straight in the home network").assertExists()
        assertEquals(2, rule.onAllNodesWithText("ready").fetchSemanticsNodes().size)
        assertEquals(1, rule.onAllNodesWithText("Always original: off").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Remove").fetchSemanticsNodes().size)
        rule.onNodeWithText("In Jellyfin this TV is called “GateControl · Wohnzimmer-TV”.").assertExists()
        rule.onNodeWithText("Reload").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, reloaded)
    }

    @Test
    fun `the rows of Ergaenzt - next up plays, favourites and collections open, the first row shown takes the focus`() {
        val next = MediaTitle("e16", "s1", MediaTitle.Kind.EPISODE, "Die Flaute", runtimeMs = 2_640_000, seriesId = "s12", seriesName = "Ankerplatz 12", season = 1, episode = 6)
        val set = MediaTitle("b1", "s1", MediaTitle.Kind.COLLECTION, "Sternwarte-Reihe", childCount = 3)
        val ui = TvMediaUi(
            sources = listOf(source),
            source = source,
            nextUp = listOf(next),
            nextUpTotal = 4,
            favorites = listOf(sternwarte.copy(favorite = true)),
            favoritesTotal = 7,
            collections = listOf(set),
            collectionsTotal = 1,
        )
        val played = mutableListOf<Pair<String, Boolean>>()
        val opened = mutableListOf<String>()
        rule.setContent {
            GateControlTvTheme { TvMediaPage(TvMediaScreen(ui), TvMediaActions(onPlay = { t, start -> played += t.id to start }, onOpen = { opened += it.id })) }
        }
        // without "Weiterschauen" the row is not there, "Als Nächstes" comes first
        assertEquals(0, rule.onAllNodesWithText("Continue watching").fetchSemanticsNodes().size)
        rule.onNodeWithText("Next up").assertExists()
        rule.onNodeWithText("4").assertExists()
        rule.onNodeWithText("Favourites").assertExists()
        rule.onNodeWithText("7").assertExists()
        rule.onNodeWithText("Collections").assertExists()
        rule.onNodeWithText("3 films").assertExists()
        rule.onNodeWithContentDescription("Ankerplatz 12").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf("e16" to false), played)
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Sternwarte 9").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Sternwarte-Reihe").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf("b1"), opened)
    }

    @Test
    fun `a collection's page lists its films, the heart toggles`() {
        val set = MediaTitle("b1", "s1", MediaTitle.Kind.COLLECTION, "Sternwarte-Reihe", childCount = 2)
        val detail = TvDetailUi(set, MediaDetail(set, emptyList()), loading = false, items = listOf(sternwarte, kobalt))
        var hearts = 0
        val opened = mutableListOf<String>()
        rule.setContent {
            GateControlTvTheme { TvMediaPage(TvMediaScreen(rows, detail = detail), TvMediaActions(onFavorite = { hearts++ }, onOpen = { opened += it.id })) }
        }
        rule.onNodeWithText("♡ Favourite").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, hearts)
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Sternwarte 9").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf("m9"), opened)
    }
}
