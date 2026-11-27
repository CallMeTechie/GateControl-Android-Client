package com.gatecontrol.android.ui.media

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.SavedStateHandle
import com.gatecontrol.android.media.MediaDetail
import com.gatecontrol.android.media.MediaDeviceCaps
import com.gatecontrol.android.media.MediaGate
import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.MediaLink
import com.gatecontrol.android.media.MediaNetwork
import com.gatecontrol.android.media.MediaOrder
import com.gatecontrol.android.media.MediaPhoneSettings
import com.gatecontrol.android.media.MediaPictures
import com.gatecontrol.android.media.MediaPlayRequests
import com.gatecontrol.android.media.MediaPlayback
import com.gatecontrol.android.media.MediaRowsCache
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaSourceStore
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.MediaVersion
import com.gatecontrol.android.media.server.MediaServerSources
import com.gatecontrol.android.media.server.MediaServerState
import com.gatecontrol.android.ui.theme.GateControlTheme
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
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
 * The overview „Ergänzt“ on the phone (decision 70 A): the rows in their order, each with its count and "›" to the
 * fitting list, a row without titles not there; the heart on the detail page. With `MEDIA_SHOTS=<dir>` the pages are
 * saved there, light and dark (for a look, not part of CI).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w411dp-h891dp")
class MediaOverviewUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val jellyfin = MediaSource("jf", MediaSourceKind.JELLYFIN, "NAS", "http://192.168.1.20:8096", userId = "u1", userName = "Mila", token = "tok")
    private val day = 24 * 3600_000L
    private val now = System.currentTimeMillis()

    private fun movie(id: String, name: String, year: Int, minutes: Int, added: Long? = null, position: Long = 0, genres: List<String> = emptyList()) =
        MediaTitle(id, "jf", MediaTitle.Kind.MOVIE, name, year = year, runtimeMs = minutes * 60_000L, positionMs = position, addedAt = added, genres = genres, posterId = id, posterTag = "p", backdropId = id, backdropTag = "b")

    private fun episode(id: String, series: String, seriesId: String, season: Int, n: Int, name: String, minutes: Int, position: Long = 0) =
        MediaTitle(id, "jf", MediaTitle.Kind.EPISODE, name, seriesId = seriesId, seriesName = series, season = season, episode = n, runtimeMs = minutes * 60_000L, positionMs = position, posterId = seriesId, posterTag = "p", backdropId = seriesId, backdropTag = "b")

    private fun series(id: String, name: String, seasons: Int? = null, newCount: Int? = null, favorite: Boolean = false) =
        MediaTitle(id, "jf", MediaTitle.Kind.SERIES, name, childCount = seasons, newCount = newCount, favorite = favorite, posterId = id, posterTag = "p")

    private val kobalt = movie("m1", "Kobaltnacht", 2025, 108, position = 2_530_000)
    private val eisvogel4 = episode("e24", "Station Eisvogel", "s9", 2, 4, "Der Leuchtturm", 45, position = 1_620_000)
    private val sternwarte = movie("m9", "Sternwarte 9", 2026, 112, added = now - day, genres = listOf("Science-Fiction"))
    private val kupfer = movie("m4", "Kupferstadt", 2025, 124, added = now - 3 * day, genres = listOf("Krimi"))
    private val moorlicht = movie("m2", "Moorlicht", 2017, 98, added = now - 30 * day, genres = listOf("Drama"))
    private val ankerplatz = series("s12", "Ankerplatz 12", seasons = 1, favorite = true)
    private val boxSet = MediaTitle("b1", "jf", MediaTitle.Kind.COLLECTION, "Sternwarte-Reihe", childCount = 3, posterId = "b1", posterTag = "p")

    private fun library(begun: Boolean = true) = FakeLibrary(
        jellyfin,
        resume = if (begun) listOf(kobalt.copy(playedAt = 2), eisvogel4.copy(playedAt = 1)) else emptyList(),
        latest = listOf(sternwarte, kupfer, moorlicht),
        movies = listOf(movie("m5", "Achterwasser", 2019, 101), kobalt, kupfer, moorlicht, movie("m6", "Der Pegelwart", 2018, 95), sternwarte),
        series = listOf(ankerplatz, series("s3", "Graufeld", 3), series("s7", "Hofgeflüster", 4), series("s9", "Station Eisvogel", 2)),
        nextUp = listOf(
            episode("e16", "Ankerplatz 12", "s12", 1, 6, "Die Flaute", 44),
            episode("e41", "Hofgeflüster", "s7", 4, 1, "Neue Staffel", 51),
            episode("e25", "Station Eisvogel", "s9", 2, 5, "Treibeis", 47),
        ),
        latestEpisodes = listOf(series("s3", "Graufeld", newCount = 3), series("s7", "Hofgeflüster", newCount = 1), episode("e25", "Station Eisvogel", "s9", 2, 5, "Treibeis", 47)),
        favorites = listOf(ankerplatz, sternwarte.copy(favorite = true)),
        collections = listOf(boxSet, MediaTitle("b2", "jf", MediaTitle.Kind.COLLECTION, "Kupferstadt-Trilogie", childCount = 2, posterId = "b2", posterTag = "p")),
        genres = listOf("Doku", "Drama", "Krimi", "Komödie", "Science-Fiction"),
    )

    /** Calm made-up pictures per title (no NAS in the test): a gradient of its own. */
    private val pictures = mockk<MediaPictures>(relaxed = true) {
        every { key(any(), any()) } answers { "${firstArg<MediaTitle>().id}:${secondArg<Boolean>()}" }
        every { cached(any(), any()) } answers { picture(firstArg<MediaTitle>().id, secondArg()) }
    }

    private fun picture(seed: String, wide: Boolean): Bitmap {
        val (w, h) = if (wide) 320 to 180 else 200 to 300
        val tones = listOf(0xFF1F3A5F to 0xFF8C5A3C, 0xFF24453A to 0xFF0E1A14, 0xFF3B2A5A to 0xFF7A4C8C, 0xFF5A4A2A to 0xFF1E1A10, 0xFF2E3B47 to 0xFF59666F)
        val (a, b) = tones[(seed.hashCode() and 0x7fffffff) % tones.size]
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            val c = Canvas(bmp)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, w * 0.4f, h.toFloat(), a.toInt(), b.toInt(), Shader.TileMode.CLAMP) })
            c.drawCircle(w * 0.5f, h * 0.42f, w * 0.18f, Paint().apply { color = 0x22FFFFFF })
        }
    }

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

    private fun home(lib: FakeLibrary): MediaHomeViewModel {
        val hub = hub(lib)
        val offline = fakeOffline()
        val servers = mockk<MediaServerSources>(relaxed = true) { every { state } returns MutableStateFlow(MediaServerState(known = true, tab = true)) }
        return MediaHomeViewModel(hub, MediaPlayRequests(hub, mockk<MediaPlayback>(relaxed = true), offline), pictures, offline, servers, MediaRowsCache(null))
    }

    private fun show(vm: MediaHomeViewModel, darkTheme: Boolean = false, nav: MediaHomeNav = MediaHomeNav(detail = {}, search = {}, sources = {})) {
        rule.setContent {
            GateControlTheme(darkTheme = darkTheme) {
                // as in the app: on the page's surface (its text colour)
                androidx.compose.material3.Surface(Modifier.fillMaxSize().testTag("shot"), color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                    MediaHomeScreen(nav, viewModel = vm)
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

    @Test
    fun `the rows in their order, each with its count and the way to its list`() {
        val opened = mutableListOf<String>()
        val vm = home(library())
        show(vm, nav = MediaHomeNav(detail = { opened += it.id }, search = {}, sources = {}))
        rule.onNodeWithText("Weiterschauen").assertIsDisplayed()
        rule.onNodeWithText("2 Titel").assertIsDisplayed()
        rule.onNodeWithText("Als Nächstes").assertIsDisplayed()
        rule.onNodeWithText("Die Flaute · 44 Min.").assertIsDisplayed()
        rule.onNodeWithContentDescription("Als Nächstes: Alle anzeigen (3)").assertIsDisplayed()
        val overview = rule.onNodeWithTag(OVERVIEW_TAG)
        overview.performScrollToNode(hasTestTag(ROW_LATEST_MOVIES_TAG))
        rule.onNodeWithText("Neue Filme").assertIsDisplayed()
        overview.performScrollToNode(hasTestTag(ROW_LATEST_EPISODES_TAG))
        rule.onNodeWithText("3 neu").assertIsDisplayed()
        // 3 + 1 + 1 new episodes
        rule.onNodeWithContentDescription("Neue Folgen: Alle anzeigen (5)").assertIsDisplayed()
        overview.performScrollToNode(hasTestTag(ROW_FAVORITES_TAG))
        rule.onNodeWithText("Favoriten").assertIsDisplayed()
        overview.performScrollToNode(hasTestTag(ROW_MOVIES_TAG))
        rule.onNodeWithContentDescription("Filme: Alle anzeigen (6)").assertIsDisplayed()
        overview.performScrollToNode(hasTestTag(ROW_COLLECTIONS_TAG))
        rule.onNodeWithText("Sammlungen").assertIsDisplayed()
        rule.onNodeWithText("Sternwarte-Reihe").performClick()
        assertEquals(listOf("b1"), opened)
        overview.performScrollToNode(hasTestTag(ROW_GENRES_TAG))
        // a genre tile opens "Filme" with that genre
        rule.onNodeWithText("Drama").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Drama ▾").assertIsDisplayed()
        assertEquals(MediaOrder(genre = "Drama"), vm.movies.value.order)
    }

    @Test
    fun `Neue Filme opens Filme newest first, Als Naechstes the unwatched series`() {
        val vm = home(library())
        show(vm)
        rule.onNodeWithContentDescription("Als Nächstes: Alle anzeigen (3)").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(SERIES_TAG).assertIsDisplayed()
        assertEquals(MediaOrder(unplayed = true), vm.series.value.order)
        rule.onNodeWithText("Übersicht").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(OVERVIEW_TAG).performScrollToNode(hasTestTag(ROW_LATEST_MOVIES_TAG))
        rule.onNodeWithContentDescription("Neue Filme: Alle anzeigen (3)").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(MOVIES_TAG).assertIsDisplayed()
        rule.onNodeWithText("Zuletzt hinzugefügt ▾").assertIsDisplayed()
        rule.onNodeWithText("Favoriten").assertIsDisplayed()
    }

    @Test
    fun `nothing begun - no Weiterschauen, the page still full`() {
        show(home(library(begun = false)))
        assertTrue(rule.onAllNodes(hasText("Weiterschauen")).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("Als Nächstes").assertIsDisplayed()
        rule.onNodeWithText("Neue Filme").assertIsDisplayed()
    }

    @Test
    fun `the heart on the detail page`() {
        val lib = library()
        lib.details = mapOf("m9" to MediaDetail(sternwarte, listOf(MediaVersion("v1"))))
        val hub = hub(lib)
        val offline = fakeOffline()
        val vm = MediaDetailViewModel(
            SavedStateHandle(mapOf("source" to "jf", "id" to "m9", "name" to "Sternwarte 9")),
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
            noConvert(),
        )
        rule.setContent { GateControlTheme(darkTheme = false) { androidx.compose.material3.Surface(Modifier.fillMaxSize().testTag("shot")) { MediaDetailScreen(onBack = {}, onOpen = {}, viewModel = vm) } } }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Zu den Favoriten").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Aus den Favoriten entfernen").assertIsDisplayed()
        assertEquals(listOf("m9" to true), lib.hearts)
        shot("handy-detail-favorit-hell")
    }

    private fun phoneShots(d: Boolean) {
        if (System.getenv("MEDIA_SHOTS") == null) return
        val name = if (d) "dunkel" else "hell"
        show(home(library()), darkTheme = d)
        shot("handy-uebersicht-$name")
        rule.onNodeWithTag(OVERVIEW_TAG).performScrollToNode(hasTestTag(ROW_GENRES_TAG))
        rule.waitForIdle()
        shot("handy-uebersicht-unten-$name")
    }

    @Test
    fun `shots - phone light`() = phoneShots(false)

    @Test
    fun `shots - phone dark`() = phoneShots(true)

    @Test
    @Config(qualifiers = "de-w411dp-h2700dp")
    fun `shot - the whole page at once`() {
        if (System.getenv("MEDIA_SHOTS") == null) return
        show(home(library()))
        shot("handy-uebersicht-ganz-hell")
    }

    @Test
    @Config(qualifiers = "de-w411dp-h2700dp")
    fun `shot - the whole page at once, dark`() {
        if (System.getenv("MEDIA_SHOTS") == null) return
        show(home(library()), darkTheme = true)
        shot("handy-uebersicht-ganz-dunkel")
    }

    @Test
    fun `shot - nothing begun`() {
        if (System.getenv("MEDIA_SHOTS") == null) return
        show(home(library(begun = false)))
        shot("handy-uebersicht-ohne-weiterschauen-hell")
    }
}
