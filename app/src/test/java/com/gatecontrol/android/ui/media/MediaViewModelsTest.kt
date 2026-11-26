package com.gatecontrol.android.ui.media

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.media.MediaDetail
import com.gatecontrol.android.media.MediaDeviceCaps
import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.MediaLink
import com.gatecontrol.android.media.MediaNetwork
import com.gatecontrol.android.media.MediaOrder
import com.gatecontrol.android.media.MediaPhoneSettings
import com.gatecontrol.android.media.MediaPictures
import com.gatecontrol.android.media.MediaPlayRequests
import com.gatecontrol.android.media.MediaPlayback
import com.gatecontrol.android.media.MediaQueue
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaSourceStore
import com.gatecontrol.android.media.MediaStream
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.MediaVersion
import com.gatecontrol.android.media.MediaGate
import com.gatecontrol.android.media.server.MediaMigration
import com.gatecontrol.android.media.server.MediaServerSources
import com.gatecontrol.android.media.server.MediaServerState
import com.gatecontrol.android.media.server.MediaSignIn
import com.gatecontrol.android.media.MediaProblem
import com.gatecontrol.android.media.MediaRowsCache
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The phone's media pages (stage 4b): the tab's rows and grids over the Jellyfin server and a share, the chips' order
 * reaching the library, the detail page's sound and "Gesehen", "Gerät anmelden", and every "Abspielen" going through
 * [MediaPlayRequests] to the player of stage 4c.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaViewModelsTest {

    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<androidx.lifecycle.ViewModel>()

    @BeforeEach fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private val jellyfin = MediaSource("jf", MediaSourceKind.JELLYFIN, "DS918+", "http://192.168.1.20:8096", userId = "u1", userName = "Mila", token = "tok")
    private val share = MediaSource("nas", MediaSourceKind.SMB, "\\\\nas\\filme", "smb://nas/filme")

    private fun film(id: String, name: String, source: String = "jf", position: Long = 0, year: Int? = null) =
        MediaTitle(id, source, MediaTitle.Kind.MOVIE, name, year = year, runtimeMs = 6_480_000, positionMs = position, fromShare = source == "nas", playedAt = position)

    private val kobalt = film("m1", "Kobaltnacht", position = 2_530_000, year = 2025)
    private val sternwarte = film("m2", "Sternwarte 9", year = 2026)
    private val kupfer = film("n1", "Kupferstadt", source = "nas", year = 2025)
    // the same film on the share: shown once, Jellyfin's
    private val kobaltOnShare = film("n2", "Kobaltnacht", source = "nas", year = 2025)

    private val jfLib = FakeLibrary(jellyfin, resume = listOf(kobalt), latest = listOf(sternwarte, kobalt), movies = listOf(kobalt, sternwarte))
    private val nasLib = FakeLibrary(share, movies = listOf(kupfer, kobaltOnShare))

    private val sources = MutableStateFlow(listOf(jellyfin, share))
    private val store = mockk<MediaSourceStore>(relaxed = true) {
        every { this@mockk.sources } returns this@MediaViewModelsTest.sources
        every { source(any()) } answers { this@MediaViewModelsTest.sources.value.firstOrNull { it.id == firstArg() } }
        every { trackChoice(any()) } returns null
    }
    private val hub = mockk<MediaHub>(relaxed = true) {
        every { store } returns this@MediaViewModelsTest.store
        every { currentSource() } returns jellyfin
        every { shares() } returns listOf(share)
        every { link } returns MutableStateFlow(MediaLink(atHome = true))
        every { library(jellyfin) } returns jfLib
        every { library(share) } returns nasLib
        every { caps() } returns MediaDeviceCaps.fromDecoders(listOf("audio/mp4a-latm"), hdrScreen = false, name = "GateControl")
    }
    private val playback = mockk<MediaPlayback>(relaxed = true)
    private val offline = fakeOffline()
    private val requests = MediaPlayRequests(hub, playback, offline)
    private val pictures = mockk<MediaPictures>(relaxed = true)
    private val serverState = MutableStateFlow(MediaServerState(known = true, tab = true))
    private val servers = mockk<MediaServerSources>(relaxed = true) { every { state } returns serverState }
    private var clock = 1_800_000_000_000L
    private val rowsCache = MediaRowsCache(null, now = { clock })

    @Test
    fun `the overview over Jellyfin and the share, a film in both once`() = runTest(dispatcher) {
        val vm = MediaHomeViewModel(hub, requests, pictures, offline, servers, rowsCache).also { models += it }
        vm.refresh()
        advanceUntilIdle()
        val ui = vm.ui.value
        assertEquals(listOf("m1"), ui.resume.map { it.id })
        // "Neue Filme" without what is in "Weiterschauen"
        assertEquals(listOf("m2"), ui.rows.latestMovies.map { it.id })
        assertEquals(listOf(jellyfin), ui.servers)
        assertFalse(ui.loading)
        // opening Medien: the server's list, home or away (the VPN started away when needed)
        verify { servers.refreshSoon() }
        coVerify { hub.prepare(jellyfin, false) }
    }

    @Test
    fun `the grid of films with the share's titles in their places, the chips' order to the library`() = runTest(dispatcher) {
        val vm = MediaHomeViewModel(hub, requests, pictures, offline, servers, rowsCache).also { models += it }
        vm.openGrid(movies = true)
        advanceUntilIdle()
        assertEquals(listOf("Kobaltnacht", "Kupferstadt", "Sternwarte 9"), vm.movies.value.items.map { it.name })
        assertEquals(3, vm.movies.value.total)
        assertFalse(vm.movies.value.hasMore)

        vm.setOrder(movies = true, order = MediaOrder(MediaOrder.Sort.YEAR))
        advanceUntilIdle()
        assertEquals(MediaOrder(MediaOrder.Sort.YEAR), jfLib.orders.last())
        assertEquals(listOf("m2", "m1", "n1"), vm.movies.value.items.map { it.id })
    }

    @Test
    fun `a card of Weiterschauen plays through the requests, the player is stage 4c`() = runTest(dispatcher) {
        val vm = MediaHomeViewModel(hub, requests, pictures, offline, servers, rowsCache).also { models += it }
        val got = mutableListOf<MediaQueue>()
        val collecting = launch { requests.queues.collect { got += it } }
        advanceUntilIdle()
        vm.play(kobalt)
        advanceUntilIdle()
        assertEquals(listOf("m1"), got.single().items.map { it.id })
        assertNull(got.single().startMs)
        collecting.cancel()
    }

    @Test
    fun `the detail page, DTS converted on the phone, Von vorn with the chosen sound, Gesehen`() = runTest(dispatcher) {
        val version = MediaVersion(
            "v1",
            container = "mkv",
            streams = listOf(
                MediaStream(0, MediaStream.Type.VIDEO, codec = "hevc", width = 3840, height = 2160, videoRangeType = "HDR10"),
                MediaStream(1, MediaStream.Type.AUDIO, codec = "dts", language = "ger", channels = 6, isDefault = true),
                MediaStream(2, MediaStream.Type.AUDIO, codec = "aac", language = "ger", channels = 2),
            ),
            defaultAudio = 1,
        )
        jfLib.details = mapOf("m1" to MediaDetail(kobalt, listOf(version)))
        val settings = mockk<MediaPhoneSettings> {
            every { mobileCap } returns MutableStateFlow(3_000_000L)
            every { offlineQuality } returns MutableStateFlow(com.gatecontrol.android.media.offline.OfflineQuality.P1080)
        }
        val network = mockk<MediaNetwork> { every { cellular() } returns false }
        val vm = MediaDetailViewModel(SavedStateHandle(mapOf("source" to "jf", "id" to "m1", "name" to "Kobaltnacht")), hub, requests, settings, network, pictures, offline, com.gatecontrol.android.media.MediaRowsCache(null))
            .also { models += it }
        advanceUntilIdle()
        val ui = vm.ui.value
        assertEquals("Kobaltnacht", ui.title.name)
        assertEquals(1, ui.audio)
        assertNull(ui.mobileLimit)
        assertTrue(com.gatecontrol.android.media.MediaPhoneLogic.audioNote(version.audio.first(), ui.caps).converted)

        vm.chooseAudio(2)
        val got = mutableListOf<MediaQueue>()
        val collecting = launch { requests.queues.collect { got += it } }
        advanceUntilIdle()
        vm.play(fromStart = true)
        advanceUntilIdle()
        assertEquals(0L, got.single().startMs)
        assertEquals(2, got.single().audioIndex)
        collecting.cancel()

        vm.togglePlayed()
        advanceUntilIdle()
        assertEquals(listOf("m1" to true), jfLib.played)
        assertTrue(vm.ui.value.title.played)
    }

    @Test
    fun `Quellen is a display - reload asks the server, the settings stay on the phone`() = runTest(dispatcher) {
        val settings = mockk<MediaPhoneSettings>(relaxed = true)
        val vm = MediaSourcesViewModel(hub, settings, offline, servers).also { models += it }
        vm.reload()
        advanceUntilIdle()
        coVerify { servers.refresh(true) }
        verify { servers.repair() }
        vm.setAlwaysOriginal(jellyfin, true)
        verify { store.setAlwaysOriginal("jf", true) }
        vm.setMobileCap(null)
        verify { settings.setMobileCap(null) }
    }

    @Test
    fun `away without the VPN, the consent, the takeover sheet`() = runTest(dispatcher) {
        every { hub.gate } returns MutableStateFlow(MediaGate.CONSENT)
        val vm = MediaHomeViewModel(hub, requests, pictures, offline, servers, rowsCache).also { models += it }
        assertEquals(MediaGate.CONSENT, vm.gate.value)
        vm.consent(true)
        advanceUntilIdle()
        coVerify { hub.consent(true) }
        coVerify { hub.prepare(jellyfin, false) }
        vm.consent(false)
        advanceUntilIdle()
        coVerify { hub.consent(false) }

        serverState.value = serverState.value.copy(migration = listOf(MediaMigration("uuid-3", MediaSourceKind.WEBDAV, "192.168.1.30/privat", null)))
        val got = launch { vm.migration.collect { } }
        advanceUntilIdle()
        assertEquals(1, vm.migration.value.size)
        vm.acknowledgeMigration()
        verify { servers.acknowledgeMigration() }
        got.cancel()

    }

    // --- „Neu anmelden“ ---

    /** The Jellyfin server signed out: the overview shows the card. */
    private fun signedOut() {
        sources.value = listOf(jellyfin.copy(token = null), share)
        every { hub.currentSource() } answers { sources.value.first() }
    }

    @Test
    fun `Neu anmelden - a source of the server, waited for with Anmelden, then the overview and the open grid anew`() = runTest(dispatcher) {
        val answer = CompletableDeferred<MediaSignIn>()
        coEvery { servers.signIn("jf") } coAnswers { answer.await() }
        val vm = MediaHomeViewModel(hub, requests, pictures, offline, servers, rowsCache).also { models += it }
        vm.refresh()
        vm.openGrid(movies = true)
        advanceUntilIdle()
        val loads = jfLib.resumeCalls
        val grids = jfLib.orders.size

        vm.repair()
        advanceUntilIdle()
        assertTrue(vm.ui.value.signIn.running, "„Anmelden …“ while it runs")
        // tapped again meanwhile: nothing more
        vm.repair()
        answer.complete(MediaSignIn.Done)
        advanceUntilIdle()
        assertFalse(vm.ui.value.signIn.running)
        assertNull(vm.ui.value.signIn.problem)
        assertEquals(loads + 1, jfLib.resumeCalls, "the overview loaded anew")
        assertEquals(grids + 1, jfLib.orders.size, "the grid opened loaded anew")
        coVerify(exactly = 1) { servers.signIn("jf") }
        coVerify { hub.prepare(jellyfin) }
        verify(exactly = 0) { servers.repair() }
    }

    @Test
    fun `Neu anmelden - it fails, the card keeps the reason until signed in`() = runTest(dispatcher) {
        signedOut()
        coEvery { servers.signIn("jf") } returns MediaSignIn.Problem("quick_connect_off")
        val vm = MediaHomeViewModel(hub, requests, pictures, offline, servers, rowsCache).also { models += it }
        vm.refresh()
        advanceUntilIdle()
        assertEquals(MediaProblem.SIGNED_OUT, vm.ui.value.problem)

        vm.repair()
        advanceUntilIdle()
        assertFalse(vm.ui.value.signIn.running)
        assertEquals("quick_connect_off", vm.ui.value.signIn.problem)
        assertEquals("jf", vm.ui.value.signIn.source?.id)
        assertEquals(MediaProblem.SIGNED_OUT, vm.ui.value.problem)
        // the page loads again (back to the front): still signed out, the reason stays
        vm.refresh()
        advanceUntilIdle()
        assertEquals("quick_connect_off", vm.ui.value.signIn.problem)
    }

    @Test
    fun `Neu anmelden - a source set up by hand leads to Medien - Quellen, once`() = runTest(dispatcher) {
        signedOut()
        coEvery { servers.signIn("jf") } returns MediaSignIn.Manual
        val vm = MediaHomeViewModel(hub, requests, pictures, offline, servers, rowsCache).also { models += it }
        vm.repair()
        advanceUntilIdle()
        assertEquals("jf", vm.ui.value.openSource)
        assertFalse(vm.ui.value.signIn.running)
        vm.sourceOpened()
        assertNull(vm.ui.value.openSource)
    }

    @Test
    fun `signed in again in the background (after a revocation) - the page loads anew by itself`() = runTest(dispatcher) {
        signedOut()
        val vm = MediaHomeViewModel(hub, requests, pictures, offline, servers, rowsCache).also { models += it }
        vm.refresh()
        advanceUntilIdle()
        assertEquals(MediaProblem.SIGNED_OUT, vm.ui.value.problem)
        val loads = jfLib.resumeCalls
        sources.value = listOf(jellyfin, share)
        advanceUntilIdle()
        assertNull(vm.ui.value.problem)
        assertEquals(loads + 1, jfLib.resumeCalls)
        assertEquals(listOf("m1"), vm.ui.value.resume.map { it.id })
    }

    // --- the rows kept (stale-while-revalidate) ---

    @Test
    fun `opened again within a minute asks nothing, later quietly in the background`() = runTest(dispatcher) {
        val vm = MediaHomeViewModel(hub, requests, pictures, offline, servers, rowsCache).also { models += it }
        vm.open()
        advanceUntilIdle()
        assertEquals(1, jfLib.resumeCalls)
        assertEquals(listOf("m1"), vm.ui.value.resume.map { it.id })

        // back from a title, another tab and back
        clock += 30_000
        vm.open()
        advanceUntilIdle()
        assertEquals(1, jfLib.resumeCalls, "loaded just now: no request")

        clock += 60_000
        val seen = mutableListOf<MediaHomeUi>()
        val watch = launch { vm.ui.collect { seen += it } }
        vm.open()
        advanceUntilIdle()
        assertEquals(2, jfLib.resumeCalls, "older than a minute: asked again")
        assertTrue(seen.none { it.loading }, "quietly: no spinner while the rows are shown")
        assertTrue(seen.all { it.resume.map { t -> t.id } == listOf("m1") }, "the rows stay while they are asked again")
        watch.cancel()

        // "Erneut versuchen": always asked
        vm.refresh()
        advanceUntilIdle()
        assertEquals(3, jfLib.resumeCalls)
    }

    @Test
    fun `a new page shows the rows kept at once, before the server answers`() = runTest(dispatcher) {
        MediaHomeViewModel(hub, requests, pictures, offline, servers, rowsCache).also { models += it }.apply { open() }
        advanceUntilIdle()
        clock += 5 * 60_000

        // the page made anew (another tab before, the app restarted): the way to the NAS is slow
        val way = CompletableDeferred<MediaGate>()
        coEvery { hub.prepare(any(), any()) } coAnswers { way.await() }
        jfLib.resume = listOf(kobalt, sternwarte.copy(positionMs = 600_000))
        val vm = MediaHomeViewModel(hub, requests, pictures, offline, servers, rowsCache).also { models += it }
        vm.open()
        advanceUntilIdle()
        assertEquals(listOf("m1"), vm.ui.value.resume.map { it.id }, "the rows kept, at once")
        assertEquals(listOf("m2"), vm.ui.value.rows.latestMovies.map { it.id })
        assertFalse(vm.ui.value.loading)

        way.complete(MediaGate.OPEN)
        advanceUntilIdle()
        assertEquals(listOf("m1", "m2"), vm.ui.value.resume.map { it.id }, "then the server's rows")
    }

    @Test
    fun `not reached - the rows kept stay under the card, signed out they go`() = runTest(dispatcher) {
        val vm = MediaHomeViewModel(hub, requests, pictures, offline, servers, rowsCache).also { models += it }
        vm.open()
        advanceUntilIdle()
        jfLib.resumeAnswer = com.gatecontrol.android.media.MediaResult.Offline
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.ui.value.problem != null && vm.ui.value.problem != MediaProblem.SIGNED_OUT)
        assertEquals(listOf("m1"), vm.ui.value.resume.map { it.id })

        jfLib.resumeAnswer = com.gatecontrol.android.media.MediaResult.Unauthorized
        vm.refresh()
        advanceUntilIdle()
        assertEquals(MediaProblem.SIGNED_OUT, vm.ui.value.problem)
        assertTrue(vm.ui.value.resume.isEmpty())
    }

    // --- the overview „Ergänzt“ (decisions 70 A, 72–76) ---

    private val ankerplatz = MediaTitle("e16", "jf", MediaTitle.Kind.EPISODE, "Die Flaute", seriesId = "s12", seriesName = "Ankerplatz 12", season = 1, episode = 6)
    private val graufeld = MediaTitle("s3", "jf", MediaTitle.Kind.SERIES, "Graufeld", newCount = 3)
    private val boxSet = MediaTitle("b1", "jf", MediaTitle.Kind.COLLECTION, "Sternwarte-Reihe", childCount = 2)

    private fun detail(source: String, id: String, name: String) = MediaDetailViewModel(
        SavedStateHandle(mapOf("source" to source, "id" to id, "name" to name)),
        hub,
        requests,
        mockk {
            every { mobileCap } returns MutableStateFlow(3_000_000L)
            every { offlineQuality } returns MutableStateFlow(com.gatecontrol.android.media.offline.OfflineQuality.P1080)
        },
        mockk { every { cellular() } returns false },
        pictures,
        offline,
        rowsCache,
    ).also { models += it }

    private fun fullLibrary() {
        jfLib.nextUp = listOf(ankerplatz, kobalt)
        jfLib.latestEpisodes = listOf(graufeld)
        jfLib.favorites = listOf(sternwarte.copy(favorite = true))
        jfLib.collections = listOf(boxSet)
        jfLib.collectionItems = mapOf("b1" to listOf(sternwarte, kobalt))
        jfLib.genres = listOf("Krimi", "Science-Fiction")
        jfLib.series = listOf(MediaTitle("s12", "jf", MediaTitle.Kind.SERIES, "Ankerplatz 12"))
        nasLib.movies = listOf(kupfer.copy(genres = listOf("Doku")))
    }

    @Test
    fun `all rows at once, an empty one not shown, cached for the next opening`() = runTest(dispatcher) {
        fullLibrary()
        val vm = MediaHomeViewModel(hub, requests, pictures, offline, servers, rowsCache).also { models += it }
        vm.open()
        advanceUntilIdle()
        val r = vm.ui.value.rows
        assertEquals(listOf("m1"), r.resume.map { it.id })
        // the film begun is in "Weiterschauen", not again in "Als Nächstes"
        assertEquals(listOf("e16"), r.nextUp.map { it.id })
        assertEquals(listOf("m2"), r.latestMovies.map { it.id })
        assertEquals(listOf("s3"), r.latestEpisodes.map { it.id })
        assertEquals(listOf("m2"), r.favorites.map { it.id })
        assertEquals(listOf("m1", "n1", "m2"), r.movies.map { it.id }, "the row Filme: Jellyfin's and the share's A–Z")
        assertEquals(listOf("b1"), r.collections.map { it.id })
        assertEquals(listOf("Doku", "Krimi", "Science-Fiction"), r.genres, "the genres of all sources")
        assertFalse(vm.ui.value.empty)
        assertTrue(jfLib.asked.containsAll(listOf("nextUp", "latestEpisodes", "favorites", "collections")))

        // the rows kept: a new page shows them all before the server answers
        clock += 5 * 60_000
        val way = CompletableDeferred<MediaGate>()
        coEvery { hub.prepare(any(), any()) } coAnswers { way.await() }
        val again = MediaHomeViewModel(hub, requests, pictures, offline, servers, rowsCache).also { models += it }
        again.open()
        advanceUntilIdle()
        assertEquals(listOf("e16"), again.ui.value.rows.nextUp.map { it.id })
        assertEquals(listOf("b1"), again.ui.value.rows.collections.map { it.id })
        assertEquals(listOf("Krimi", "Science-Fiction"), again.ui.value.rows.genres.filter { it != "Doku" })
        way.complete(MediaGate.OPEN)
        advanceUntilIdle()
    }

    @Test
    fun `nothing begun and no downloads - the other rows still fill the page`() = runTest(dispatcher) {
        fullLibrary()
        jfLib.resume = emptyList()
        val vm = MediaHomeViewModel(hub, requests, pictures, offline, servers, rowsCache).also { models += it }
        vm.open()
        advanceUntilIdle()
        val r = vm.ui.value.rows
        assertTrue(r.resume.isEmpty())
        assertEquals(listOf("e16", "m1"), r.nextUp.map { it.id })
        assertEquals(listOf("m2", "m1"), r.latestMovies.map { it.id })
    }

    @Test
    fun `Alle anzeigen - a row opens its list in the fitting order`() = runTest(dispatcher) {
        val vm = MediaHomeViewModel(hub, requests, pictures, offline, servers, rowsCache).also { models += it }
        vm.showList(movies = true, order = MediaOrder(sort = MediaOrder.Sort.ADDED))
        advanceUntilIdle()
        assertEquals(MediaOrder(sort = MediaOrder.Sort.ADDED), vm.movies.value.order)
        assertTrue(MediaOrder(sort = MediaOrder.Sort.ADDED) in jfLib.orders, "the library sorts newest first")
        vm.showList(movies = true, order = MediaOrder(genre = "Krimi"))
        advanceUntilIdle()
        assertEquals("Krimi", vm.movies.value.order.genre)
        vm.showList(movies = false, order = null)
        advanceUntilIdle()
        assertTrue(vm.series.value.loaded)
        assertEquals(MediaOrder(), vm.series.value.order)
    }

    @Test
    fun `the heart on a detail page - kept in Jellyfin, the overview asked again at the next opening`() = runTest(dispatcher) {
        val home = MediaHomeViewModel(hub, requests, pictures, offline, servers, rowsCache).also { models += it }
        home.open()
        advanceUntilIdle()
        assertTrue(rowsCache.fresh(jellyfin))
        jfLib.details = mapOf("m2" to MediaDetail(sternwarte, listOf(MediaVersion("v1"))))
        val vm = detail("jf", "m2", "Sternwarte 9")
        advanceUntilIdle()
        assertTrue(vm.ui.value.canFavorite)
        vm.toggleFavorite()
        assertTrue(vm.ui.value.title.favorite, "shown at once")
        advanceUntilIdle()
        assertEquals(listOf("m2" to true), jfLib.hearts)
        assertFalse(rowsCache.fresh(jellyfin), "the row Favoriten is asked again")
        val calls = jfLib.resumeCalls
        home.open()
        advanceUntilIdle()
        assertEquals(calls + 1, jfLib.resumeCalls)
    }

    @Test
    fun `a share's title has no heart, a collection lists its films`() = runTest(dispatcher) {
        nasLib.details = mapOf("n1" to MediaDetail(kupfer, emptyList()))
        val onShare = detail("nas", "n1", "Kupferstadt")
        advanceUntilIdle()
        assertFalse(onShare.ui.value.canFavorite)

        fullLibrary()
        jfLib.details = mapOf("b1" to MediaDetail(boxSet, emptyList()))
        val set = detail("jf", "b1", "Sternwarte-Reihe")
        advanceUntilIdle()
        assertEquals(listOf("m2", "m1"), set.ui.value.items.map { it.id })
    }
}
