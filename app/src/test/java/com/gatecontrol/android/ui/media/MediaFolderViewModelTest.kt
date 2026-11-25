package com.gatecontrol.android.ui.media

import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.media.MediaDeviceCaps
import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.MediaLink
import com.gatecontrol.android.media.MediaPictures
import com.gatecontrol.android.media.MediaPlayRequests
import com.gatecontrol.android.media.MediaPlayback
import com.gatecontrol.android.media.MediaProblem
import com.gatecontrol.android.media.MediaQueue
import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaShareProbes
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaSourceStore
import com.gatecontrol.android.media.ShareProgress
import com.gatecontrol.android.media.share.ShareFolderCount
import com.gatecontrol.android.media.share.ShareFormatProblem
import com.gatecontrol.android.media.share.ShareLibrary
import io.mockk.every
import io.mockk.mockk
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
 * The page "Ordner" (stage 4d): a share's folders and videos, the way (SMB at home, WebDAV away for a pair, read again
 * when the network changes), the length and the mark of what the phone cannot play, the progress on the phone, the
 * search below the folder and playing the folder from the video tapped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaFolderViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<androidx.lifecycle.ViewModel>()

    @BeforeEach fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private val smb = MediaSource("nas", MediaSourceKind.SMB, "\\\\nas\\filme", "smb://nas/filme", pairWith = "dav")
    private val dav = MediaSource("dav", MediaSourceKind.WEBDAV, "nas", "https://192.168.1.20:5006/filme", pairWith = "nas")
    private val files = mapOf(
        "Familie/Urlaub 2025/Strand.mp4" to ByteArray(10),
        "Familie/Urlaub 2025/Hafen.mp4" to ByteArray(10),
        "Familie/Schulfeste/Sommerfest.mkv" to ByteArray(10),
        "Familie/Geburtstag Oma.mkv" to ByteArray(1_800),
        "Familie/Geburtstag Oma.de.srt" to ByteArray(10),
        "Familie/Fußballturnier Jonas.mp4" to ByteArray(3_400),
        "Familie/Silvester am See.DTS.mkv" to ByteArray(2_200),
        "Serien/Graufeld.S01E01.mkv" to ByteArray(10),
    )
    private val smbFs = MemFs(files, MediaSourceKind.SMB)
    private val davFs = MemFs(files, MediaSourceKind.WEBDAV)
    private val progress = MapProgress().apply {
        // 23 of 42 minutes of "Geburtstag Oma" seen: "noch 19 Min."
        setProgress("nas|Familie/Geburtstag Oma.mkv", ShareProgress(1_380_000, 2_520_000, false, 5))
    }
    private val smbLib = ShareLibrary(smb, smbFs, progress, "nas")
    private val davLib = ShareLibrary(smb, davFs, progress, "nas")
    private val link = MutableStateFlow(MediaLink(atHome = true))

    private val store = mockk<MediaSourceStore>(relaxed = true) {
        every { source("nas") } returns smb
        every { source("dav") } returns dav
    }
    private val hub = mockk<MediaHub>(relaxed = true) {
        every { store } returns this@MediaFolderViewModelTest.store
        every { shares() } returns listOf(smb)
        every { link } returns this@MediaFolderViewModelTest.link
        every { library(smb) } answers { if (this@MediaFolderViewModelTest.link.value.atHome == false) davLib else smbLib }
        // no AC3 decoder, no HEVC
        every { caps() } returns MediaDeviceCaps.fromDecoders(listOf("video/avc", "audio/mp4a-latm"), hdrScreen = false, name = "GateControl")
    }
    private val requests = MediaPlayRequests(hub, mockk<MediaPlayback>(relaxed = true))
    private val pictures = mockk<MediaPictures>(relaxed = true)

    private fun vm() = MediaFolderViewModel(hub, requests, MediaShareProbes(dispatcher), pictures).also { models += it }

    @Test
    fun `one share opens at once, its folders and videos with size, length, time left and the mark`() = runTest(dispatcher) {
        val vm = vm()
        vm.refresh()
        advanceUntilIdle()
        assertEquals(smb, vm.ui.value.share)
        assertFalse(vm.ui.value.canGoUp)
        assertEquals(MediaSourceKind.SMB, vm.ui.value.via)
        assertTrue(vm.ui.value.paired)
        assertEquals(listOf("Familie", "Serien"), vm.ui.value.folders.map { it.entry.name })

        vm.openFolder("Familie")
        advanceUntilIdle()
        val u = vm.ui.value
        assertTrue(u.canGoUp)
        assertEquals(listOf("Schulfeste", "Urlaub 2025"), u.folders.map { it.entry.name })
        assertEquals(ShareFolderCount(0, 2), u.folders[1].count)
        assertEquals(listOf("Fußballturnier Jonas.mp4", "Geburtstag Oma.mkv", "Silvester am See.DTS.mkv"), u.files.map { it.entry.name })
        val oma = u.files[1]
        assertEquals(1_800, oma.entry.size)
        assertEquals(2_520_000, oma.durationMs)
        assertEquals(1_140_000, oma.leftMs)
        assertEquals(0.547f, oma.progress, 0.01f)
        assertEquals(listOf("de"), oma.video.subtitles.map { it.language })
        // DTS by its name (the bytes are no Matroska): "Ton DTS: am Handy kein Ton", played anyway
        assertEquals(ShareFormatProblem(audio = "DTS"), u.files[2].problem)
        assertNull(u.files[0].problem)

        assertTrue(vm.up())
        advanceUntilIdle()
        assertEquals("", vm.ui.value.path)
        assertFalse(vm.up())
    }

    @Test
    fun `away the pair is read by WebDAV, at the same place`() = runTest(dispatcher) {
        val vm = vm()
        vm.refresh()
        advanceUntilIdle()
        vm.openFolder("Familie")
        advanceUntilIdle()
        link.value = MediaLink(atHome = false, bps = 18_000_000)
        advanceUntilIdle()
        assertEquals(MediaSourceKind.WEBDAV, vm.ui.value.via)
        assertEquals(false, vm.ui.value.atHome)
        assertEquals("Familie", vm.ui.value.path)
        assertEquals(3, vm.ui.value.files.size)
        // the same progress by both ways
        assertEquals(1_140_000, vm.ui.value.files[1].leftMs)

        davFs.offline = true
        vm.retry()
        advanceUntilIdle()
        assertEquals(MediaProblem.OFFLINE, vm.ui.value.problem)
    }

    @Test
    fun `a tap plays the folder from that video, the search finds below the folder`() = runTest(dispatcher) {
        val vm = vm()
        vm.refresh()
        advanceUntilIdle()
        vm.openFolder("Familie")
        advanceUntilIdle()
        val got = mutableListOf<MediaQueue>()
        val collecting = launch { requests.queues.collect { got += it } }
        advanceUntilIdle()
        vm.play(vm.ui.value.files[1])
        advanceUntilIdle()
        val q = got.single()
        assertEquals("nas", q.sourceId)
        assertEquals(1, q.at)
        assertEquals(3, q.items.size)
        assertNull(q.startMs)
        vm.play(vm.ui.value.files[1], fromStart = true)
        advanceUntilIdle()
        assertEquals(0L, got.last().startMs)
        collecting.cancel()

        vm.setQuery("hafen")
        advanceUntilIdle()
        assertEquals(listOf("Familie/Urlaub 2025/Hafen.mp4"), vm.ui.value.found!!.map { it.entry.path })
        vm.setQuery("urlaub")
        advanceUntilIdle()
        assertEquals(listOf("Urlaub 2025"), vm.ui.value.shownFolders.map { it.entry.name })
        vm.setQuery("")
        advanceUntilIdle()
        assertNull(vm.ui.value.found)

        // "Als gesehen markieren": kept on the phone
        vm.setPlayed(vm.ui.value.files[0], true)
        advanceUntilIdle()
        assertTrue(vm.ui.value.files[0].title.played)
        assertTrue(progress.map.getValue("nas|Familie/Fußballturnier Jonas.mp4").played)
    }

    @Test
    fun `several shares - the list first, back to it from a share's root`() = runTest(dispatcher) {
        val other = MediaSource("pc", MediaSourceKind.SMB, "\\\\pc\\videos", "smb://pc/videos")
        every { hub.shares() } returns listOf(smb, other)
        val vm = vm()
        vm.refresh()
        advanceUntilIdle()
        assertNull(vm.ui.value.share)
        vm.openShare(smb)
        advanceUntilIdle()
        assertTrue(vm.ui.value.canGoUp)
        assertTrue(vm.up())
        assertNull(vm.ui.value.share)
    }
}
