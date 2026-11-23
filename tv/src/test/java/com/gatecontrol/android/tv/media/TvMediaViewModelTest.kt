package com.gatecontrol.android.tv.media

import com.gatecontrol.android.media.MediaDetail
import com.gatecontrol.android.media.MediaLibrary
import com.gatecontrol.android.media.MediaPage
import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaSourceStore
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.MediaVersion
import com.gatecontrol.android.media.jellyfin.JellyfinAuth
import com.gatecontrol.android.media.jellyfin.JellyfinQuickConnect
import com.gatecontrol.android.media.jellyfin.JellyfinServerInfo
import com.gatecontrol.android.media.jellyfin.JellyfinSignIn
import com.gatecontrol.android.tv.drive.TvMediaSelection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
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
    private val media = mockk<TvMedia>(relaxed = true) {
        every { store } returns this@TvMediaViewModelTest.store
        every { currentSource() } answers { shown }
        every { library(any()) } returns lib
        every { link } returns MutableStateFlow(TvMediaLink(atHome = false, bps = 18_000_000))
        every { signIn(any()) } returns signIn
        every { shares() } answers { shownShares }
    }
    private var shownShares: List<MediaSource> = emptyList()
    private val selection = TvMediaSelection()

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
        val vm = TvMediaViewModel(media, selection)
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
        val vm = TvMediaViewModel(media, selection)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(TvMediaProblem.SIGNED_OUT, vm.ui.value.problem)
        verify { store.dropToken("s1") }
    }

    @Test
    fun `the detail page away suggests the adapted quality, play hands the queue over`() = runTest(dispatcher) {
        coEvery { lib.detail("m1") } returns MediaResult.Ok(MediaDetail(kobalt, listOf(MediaVersion("v1", bitrate = 52_000_000))))
        val vm = TvMediaViewModel(media, selection)
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

    @Test
    fun `adding Jellyfin - the address, Quick Connect, saved`() = runTest(dispatcher) {
        shown = null
        coEvery { media.discover() } returns emptyList()
        coEvery { signIn.server() } returns MediaResult.Ok(JellyfinServerInfo("DS918+", "10.10.3", "srv1"))
        coEvery { signIn.quickConnectEnabled() } returns true
        val q = JellyfinQuickConnect("482913", "sec")
        coEvery { signIn.quickConnect() } returns MediaResult.Ok(q)
        coEvery { signIn.confirmed(q) } returnsMany listOf(MediaResult.Ok(false), MediaResult.Ok(true))
        coEvery { signIn.signIn(q) } returns MediaResult.Ok(JellyfinAuth("tok2", "u1", "Anna", "srv1"))
        val saved = slot<MediaSource>()
        every { store.save(capture(saved)) } answers { saved.captured }

        val vm = TvMediaViewModel(media, selection)
        vm.startAdd()
        vm.chooseJellyfin()
        advanceUntilIdle()
        assertEquals(TvAddStep.ADDRESS, vm.add.value?.step)
        vm.setAddress("nonsense address")
        vm.check()
        assertEquals(TvAddProblem.BAD_ADDRESS, vm.add.value?.problem)
        vm.setAddress("192.168.1.20")
        vm.check()
        advanceUntilIdle()
        assertEquals("http://192.168.1.20:8096", vm.add.value?.serverAddress)
        assertEquals("DS918+", vm.add.value?.server?.name)
        vm.toLogin()
        advanceTimeBy(1_000)
        assertEquals("482913", vm.add.value?.code)
        assertTrue(vm.add.value!!.waiting)
        advanceUntilIdle()
        assertTrue(vm.add.value!!.done)
        assertEquals("tok2", saved.captured.token)
        assertEquals("http://192.168.1.20:8096", saved.captured.address)
        assertEquals("srv1", saved.captured.serverId)
        verify { media.select(saved.captured.id) }
    }

    @Test
    fun `an old Jellyfin and a wrong password`() = runTest(dispatcher) {
        coEvery { signIn.server() } returns MediaResult.Ok(JellyfinServerInfo("Alt", "10.8.13", "srv2"))
        val vm = TvMediaViewModel(media, selection)
        vm.startAdd()
        vm.setAddress("192.168.1.21:8096")
        vm.check()
        advanceUntilIdle()
        assertEquals(TvAddProblem.OLD_SERVER, vm.add.value?.problem)
        assertNull(vm.add.value?.server)

        coEvery { signIn.server() } returns MediaResult.Ok(JellyfinServerInfo("DS918+", "10.10.3", "srv1"))
        coEvery { signIn.signIn("anna", "falsch") } returns MediaResult.Unauthorized
        vm.check()
        advanceUntilIdle()
        vm.chooseQuickConnect(false)
        vm.toLogin()
        vm.signIn(" anna ", "falsch")
        advanceUntilIdle()
        assertEquals(TvAddProblem.WRONG_LOGIN, vm.add.value?.problem)
        assertFalse(vm.add.value!!.done)
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
        val vm = TvMediaViewModel(media, selection)
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
        val vm = TvMediaViewModel(media, selection)
        vm.refreshResume()
        advanceUntilIdle()
        assertEquals(listOf("f:l.mkv"), vm.ui.value.resume.map { it.id })
        coVerify { media.check(share, false) }
    }

    @Test
    fun `adding a share - the address checked, the login tried, saved and paired`() = runTest(dispatcher) {
        val saved = slot<MediaSource>()
        val partner = MediaSource("dav1", MediaSourceKind.WEBDAV, "dav", "https://192.168.1.20:5006/filme", pairWith = "smb9")
        every { store.save(capture(saved)) } answers { saved.captured.copy(pairWith = "dav1") }
        every { store.source("dav1") } returns partner
        coEvery { media.tryShare(MediaSourceKind.SMB, "smb://nas/filme", "kino", "falsch") } returns MediaResult.Unauthorized
        coEvery { media.tryShare(MediaSourceKind.SMB, "smb://nas/filme", "kino", "geheim") } returns MediaResult.Ok(Unit)
        val vm = TvMediaViewModel(media, selection)
        vm.startAdd()
        vm.chooseShare(MediaSourceKind.SMB)
        assertEquals(TvAddStep.ADDRESS, vm.add.value?.step)
        assertEquals(MediaSourceKind.SMB, vm.add.value?.kind)
        // a host on the internet is no SMB share
        vm.setAddress("smb://media.example.test/filme")
        vm.connectShare("kino", "geheim")
        assertEquals(TvAddProblem.BAD_ADDRESS, vm.add.value?.problem)
        vm.setAddress("\\\\nas\\filme")
        vm.connectShare(" kino ", "falsch")
        advanceUntilIdle()
        assertEquals(TvAddProblem.WRONG_LOGIN, vm.add.value?.problem)
        vm.connectShare("kino", "geheim")
        advanceUntilIdle()
        assertTrue(vm.add.value!!.done)
        assertEquals(partner, vm.add.value!!.pairedWith)
        assertEquals("smb://nas/filme", saved.captured.address)
        assertEquals("\\\\nas\\filme", saved.captured.name)
        assertEquals("kino", saved.captured.userName)
        assertEquals("geheim", saved.captured.password)
        assertEquals(MediaSourceKind.SMB, saved.captured.kind)
    }
}
