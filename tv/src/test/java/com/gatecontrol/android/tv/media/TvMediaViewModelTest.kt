package com.gatecontrol.android.tv.media

import com.gatecontrol.android.media.MediaDetail
import com.gatecontrol.android.media.MediaGate
import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.MediaRoute
import com.gatecontrol.android.media.server.MediaServerSources
import com.gatecontrol.android.media.server.MediaServerState
import com.gatecontrol.android.media.server.MediaSignIn
import com.gatecontrol.android.media.MediaLibrary
import com.gatecontrol.android.media.MediaLink
import com.gatecontrol.android.media.MediaPage
import com.gatecontrol.android.media.MediaProblem
import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaSourceStore
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.MediaVersion
import com.gatecontrol.android.media.jellyfin.JellyfinSignIn
import com.gatecontrol.android.tv.drive.TvMediaSelection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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

@OptIn(ExperimentalCoroutinesApi::class)
class TvMediaViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val source = MediaSource("s1", MediaSourceKind.JELLYFIN, "DS918+", "http://192.168.1.20:8096", "u1", "Anna", "tok", "srv1")
    private val sources = MutableStateFlow(listOf(source))
    private val store = mockk<MediaSourceStore>(relaxed = true) {
        every { this@mockk.sources } returns this@TvMediaViewModelTest.sources
        every { source("s1") } returns source
        every { trackChoice(any()) } returns null
    }
    private val lib = mockk<MediaLibrary>(relaxed = true)
    private val signIn = mockk<JellyfinSignIn>()
    private var shown: MediaSource? = source
    private val media = mockk<MediaHub>(relaxed = true) {
        every { store } returns this@TvMediaViewModelTest.store
        every { currentSource() } answers { shown }
        every { library(any()) } returns lib
        every { link } returns MutableStateFlow(MediaLink(atHome = false, bps = 18_000_000))
        every { signIn(any()) } returns signIn
        every { shares() } answers { shownShares }
    }
    private var shownShares: List<MediaSource> = emptyList()
    private val selection = TvMediaSelection()
    private val servers = mockk<MediaServerSources>(relaxed = true) { every { state } returns MutableStateFlow(MediaServerState(known = true, tab = true)) }

    private val kobalt = MediaTitle("m1", "s1", MediaTitle.Kind.MOVIE, "Kobaltnacht", runtimeMs = 6_480_000, positionMs = 2_530_000, seriesId = null)
    private val sternwarte = MediaTitle("m9", "s1", MediaTitle.Kind.MOVIE, "Sternwarte 9")

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the rows of the source`() = runTest(dispatcher) {
        coEvery { lib.resume(any()) } returns MediaResult.Ok(listOf(kobalt))
        coEvery { lib.latest(any()) } returns MediaResult.Ok(listOf(kobalt, sternwarte))
        coEvery { lib.movies(0, any()) } returns MediaResult.Ok(MediaPage(listOf(kobalt, sternwarte), total = 412, start = 0))
        coEvery { lib.series(0, any()) } returns MediaResult.Ok(MediaPage(emptyList(), total = 0, start = 0))
        coEvery { lib.movies(2, any()) } returns MediaResult.Ok(MediaPage(listOf(MediaTitle("m3", "s1", MediaTitle.Kind.MOVIE, "Moorlicht")), total = 412, start = 2))
        val vm = TvMediaViewModel(media, selection, servers)
        vm.refresh()
        advanceUntilIdle()
        val ui = vm.ui.value
        assertEquals(listOf("m1"), ui.resume.map { it.id })
        // "Zuletzt hinzugefügt" without what is in "Weiterschauen"
        assertEquals(listOf("m9"), ui.latest.map { it.id })
        assertEquals(412, ui.moviesTotal)
        assertEquals(2, ui.moviesNext)
        assertFalse(ui.loading)
        vm.more(movies = true)
        advanceUntilIdle()
        assertEquals(listOf("m1", "m9", "m3"), vm.ui.value.movies.map { it.id })
    }

    @Test
    fun `signed out by the server - the access is dropped`() = runTest(dispatcher) {
        coEvery { lib.resume(any()) } returns MediaResult.Unauthorized
        val vm = TvMediaViewModel(media, selection, servers)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(MediaProblem.SIGNED_OUT, vm.ui.value.problem)
        verify { store.dropToken("s1") }
    }

    @Test
    fun `the detail page away suggests the adapted quality, play hands the queue over`() = runTest(dispatcher) {
        coEvery { lib.detail("m1") } returns MediaResult.Ok(MediaDetail(kobalt, listOf(MediaVersion("v1", bitrate = 52_000_000))))
        val vm = TvMediaViewModel(media, selection, servers)
        vm.open(kobalt)
        advanceUntilIdle()
        val d = vm.details.value.single()
        assertFalse(d.loading)
        assertEquals(8_000_000L, d.offer?.adaptedBps)
        assertFalse(d.original)
        var opened = false
        vm.play(kobalt) { opened = true }
        advanceUntilIdle()
        assertTrue(opened)
        val q = selection.media!!
        assertEquals(listOf("m1"), q.items.map { it.id })
        assertEquals(false, q.original)
        assertNull(q.startMs)
        assertTrue(vm.back())
        assertFalse(vm.back())
    }

    private val share = MediaSource("smb1", MediaSourceKind.SMB, "\\\\nas\\filme", "smb://nas/filme", userName = "kino", password = "pw")
    private val shareLib = mockk<MediaLibrary>(relaxed = true)

    @Test
    fun `Jellyfin and a share in one - NAS titles in their places, a title in both once`() = runTest(dispatcher) {
        shownShares = listOf(share)
        every { media.library(share) } returns shareLib
        every { media.library(source) } returns lib
        val lindqvist = MediaTitle("f:l.mkv", "smb1", MediaTitle.Kind.EPISODE, "Folge 7", seriesName = "Die Lindqvist-Akten", seriesId = "s:l", season = 1, episode = 7, positionMs = 60_000, runtimeMs = 3_000_000, playedAt = 200, fromShare = true)
        val kupfer = MediaTitle("f:k.mkv", "smb1", MediaTitle.Kind.MOVIE, "Kupferstadt", year = 2025, fromShare = true)
        val achter = MediaTitle("f:a.mkv", "smb1", MediaTitle.Kind.MOVIE, "Ankerplatz", fromShare = true)
        coEvery { lib.resume(any()) } returns MediaResult.Ok(listOf(kobalt.copy(playedAt = 300)))
        coEvery { lib.latest(any()) } returns MediaResult.Ok(emptyList())
        coEvery { lib.movies(0, any()) } returns MediaResult.Ok(MediaPage(listOf(MediaTitle("m0", "s1", MediaTitle.Kind.MOVIE, "Achterwasser"), kobalt, MediaTitle("mk", "s1", MediaTitle.Kind.MOVIE, "Kupferstadt", year = 2025)), total = 3, start = 0))
        coEvery { lib.series(0, any()) } returns MediaResult.Ok(MediaPage(emptyList(), total = 0, start = 0))
        coEvery { shareLib.resume(any()) } returns MediaResult.Ok(listOf(lindqvist))
        coEvery { shareLib.latest(any()) } returns MediaResult.Ok(listOf(kupfer))
        coEvery { shareLib.movies(0, any()) } returns MediaResult.Ok(MediaPage(listOf(achter, kupfer), total = 2, start = 0))
        coEvery { shareLib.series(0, any()) } returns MediaResult.Ok(MediaPage(emptyList(), total = 0, start = 0))
        val vm = TvMediaViewModel(media, selection, servers)
        vm.refresh()
        advanceUntilIdle()
        val ui = vm.ui.value
        assertEquals(listOf(share), ui.shares)
        assertEquals(listOf("m1", "f:l.mkv"), ui.resume.map { it.id })
        // Kupferstadt is in Jellyfin too: Jellyfin's
        assertEquals(listOf("m0", "f:a.mkv", "m1", "mk"), ui.movies.map { it.id })
        assertEquals(4, ui.moviesTotal)
        assertTrue(ui.latest.isEmpty())

        // the share cannot be read: its pill says so, Jellyfin's rows stay
        coEvery { shareLib.resume(any()) } returns MediaResult.Offline
        vm.refresh()
        advanceUntilIdle()
        assertEquals(setOf("smb1"), vm.ui.value.shareProblems)
        assertEquals(listOf("m0", "m1", "mk"), vm.ui.value.movies.map { it.id })
        assertNull(vm.ui.value.problem)
    }

    @Test
    fun `only shares - the rows without Jellyfin, the start page's Weiterschauen`() = runTest(dispatcher) {
        shown = null
        shownShares = listOf(share)
        every { media.library(share) } returns shareLib
        val lindqvist = MediaTitle("f:l.mkv", "smb1", MediaTitle.Kind.MOVIE, "Die Lindqvist-Akten", positionMs = 60_000, fromShare = true)
        coEvery { shareLib.resume(any()) } returns MediaResult.Ok(listOf(lindqvist))
        val vm = TvMediaViewModel(media, selection, servers)
        vm.refreshResume()
        advanceUntilIdle()
        assertEquals(listOf("f:l.mkv"), vm.ui.value.resume.map { it.id })
        coVerify { media.check(share, false) }
    }

    @Test
    fun `the sources come from the server - opening asks it, away the VPN is prepared, no set-up on the TV`() = runTest(dispatcher) {
        coEvery { lib.resume(any()) } returns MediaResult.Ok(emptyList())
        coEvery { lib.latest(any()) } returns MediaResult.Ok(emptyList())
        coEvery { lib.movies(0, any()) } returns MediaResult.Ok(MediaPage(emptyList(), total = 0, start = 0))
        coEvery { lib.series(0, any()) } returns MediaResult.Ok(MediaPage(emptyList(), total = 0, start = 0))
        val vm = TvMediaViewModel(media, selection, servers)
        vm.refresh()
        advanceUntilIdle()
        verify { servers.refreshSoon() }
        coVerify { media.prepare(source) }
        vm.onResumed()
        verify { servers.refreshIfStale() }
        vm.reload()
        advanceUntilIdle()
        coVerify { servers.refresh(true) }
        vm.consent(true)
        advanceUntilIdle()
        coVerify { media.consent(true) }
        vm.acknowledgeMigration()
        verify { servers.acknowledgeMigration() }
        vm.setAlwaysOriginal(source, true)
        verify { store.setAlwaysOriginal("s1", true) }
    }

    // --- „Neu anmelden“ ---

    private fun emptyLibrary() {
        coEvery { lib.resume(any()) } returns MediaResult.Ok(listOf(kobalt))
        coEvery { lib.latest(any()) } returns MediaResult.Ok(emptyList())
        coEvery { lib.movies(0, any()) } returns MediaResult.Ok(MediaPage(emptyList(), total = 0, start = 0))
        coEvery { lib.series(0, any()) } returns MediaResult.Ok(MediaPage(emptyList(), total = 0, start = 0))
    }

    /** The TV signed out at Jellyfin: the page shows „Neu anmelden“. */
    private fun signedOut(): MediaSource {
        val out = source.copy(token = null)
        sources.value = listOf(out)
        shown = out
        return out
    }

    @Test
    fun `Neu anmelden - waited for with Anmelden, then the rows anew`() = runTest(dispatcher) {
        emptyLibrary()
        signedOut()
        val answer = CompletableDeferred<MediaSignIn>()
        coEvery { servers.signIn("s1") } coAnswers { answer.await() }
        val vm = TvMediaViewModel(media, selection, servers)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(MediaProblem.SIGNED_OUT, vm.ui.value.problem)
        vm.repair()
        advanceUntilIdle()
        assertTrue(vm.ui.value.signingIn)
        vm.repair()
        shown = source
        answer.complete(MediaSignIn.Done)
        advanceUntilIdle()
        assertFalse(vm.ui.value.signingIn)
        assertNull(vm.ui.value.problem)
        assertEquals(listOf("m1"), vm.ui.value.resume.map { it.id })
        coVerify(exactly = 1) { servers.signIn("s1") }
        verify(exactly = 0) { servers.repair() }
    }

    @Test
    fun `Neu anmelden - it fails, the reason is shown, a source by hand opens Quellen`() = runTest(dispatcher) {
        signedOut()
        coEvery { servers.signIn("s1") } returns MediaSignIn.Problem("no_mapping")
        val vm = TvMediaViewModel(media, selection, servers)
        vm.refresh()
        advanceUntilIdle()
        vm.repair()
        advanceUntilIdle()
        assertEquals("no_mapping", vm.ui.value.signInProblem)
        assertFalse(vm.ui.value.signingIn)
        assertEquals(MediaProblem.SIGNED_OUT, vm.ui.value.problem)

        coEvery { servers.signIn("s1") } returns MediaSignIn.Manual
        vm.repair()
        advanceUntilIdle()
        assertTrue(vm.ui.value.openSources)
        vm.sourcesOpened()
        assertFalse(vm.ui.value.openSources)
    }

    @Test
    fun `signed in again in the background (after a revocation) - the rows load by themselves`() = runTest(dispatcher) {
        emptyLibrary()
        signedOut()
        val vm = TvMediaViewModel(media, selection, servers)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(MediaProblem.SIGNED_OUT, vm.ui.value.problem)
        shown = source
        sources.value = listOf(source)
        advanceUntilIdle()
        assertNull(vm.ui.value.problem)
        assertEquals(listOf("m1"), vm.ui.value.resume.map { it.id })
    }

    @Test
    fun `away without the VPN - the page says so instead of going out`() = runTest(dispatcher) {
        every { media.gate } returns MutableStateFlow(MediaGate.BLOCKED)
        every { media.route(any()) } returns MediaRoute.BLOCKED
        coEvery { lib.resume(any()) } returns MediaResult.Offline
        val vm = TvMediaViewModel(media, selection, servers)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(MediaProblem.VPN, vm.ui.value.problem)
        assertEquals(MediaGate.BLOCKED, vm.gate.value)
    }
}
