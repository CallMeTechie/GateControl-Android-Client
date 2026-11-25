package com.gatecontrol.android.media

import com.gatecontrol.android.media.player.PlaybackEvent
import com.gatecontrol.android.ui.media.FakeLibrary
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The phone's player asking the NAS (stage 4c): the quality with the limit on mobile data, the series' sound, a new
 * choice asked again at the same place and remembered for the series, the progress to Jellyfin and to the share.
 */
class PhoneMediaVideosTest {

    private val jellyfin = MediaSource("jf", MediaSourceKind.JELLYFIN, "DS918+", "http://192.168.1.20:8096", userId = "u1", token = "tok")
    private val share = MediaSource("nas", MediaSourceKind.SMB, "nas", "smb://nas/filme")

    private val video = MediaStream(0, MediaStream.Type.VIDEO, codec = "hevc", width = 1920, height = 1080)
    private val deDts = MediaStream(1, MediaStream.Type.AUDIO, codec = "dts", language = "ger", channels = 6)
    private val enAac = MediaStream(2, MediaStream.Type.AUDIO, codec = "aac", language = "eng", channels = 2)
    private val deSrt = MediaStream(3, MediaStream.Type.SUBTITLE, codec = "srt", language = "ger", textual = true)
    private val version = MediaVersion("v1", container = "mkv", bitrate = 20_000_000, streams = listOf(video, deDts, enAac, deSrt), defaultAudio = 1)

    private fun episode(n: Int, source: String = "jf") = MediaTitle(
        "e$n", source, MediaTitle.Kind.EPISODE, "Folge $n", seriesId = "s1", seriesName = "Station Eisvogel", season = 2, episode = n,
        positionMs = if (n == 4) 1_601_000 else 0, runtimeMs = 2_640_000,
    )

    private val choices = mutableMapOf<String, MediaTrackChoice>()
    private val sources = MutableStateFlow(listOf(jellyfin, share))
    private val store = mockk<MediaSourceStore>(relaxed = true) {
        every { this@mockk.sources } returns this@PhoneMediaVideosTest.sources
        every { source(any()) } answers { this@PhoneMediaVideosTest.sources.value.firstOrNull { it.id == firstArg() } }
        every { trackChoice(any()) } answers { choices[firstArg()] }
        val id = slot<String>()
        val c = slot<MediaTrackChoice>()
        every { setTrackChoice(capture(id), capture(c)) } answers { choices[id.captured] = c.captured }
    }
    private val link = MutableStateFlow(MediaLink(atHome = false, bps = 18_000_000))
    private val jfLib = FakeLibrary(jellyfin).apply {
        details = (4..6).associate { "e$it" to MediaDetail(episode(it), listOf(version)) }
        plan = { p ->
            val converting = p.maxBitrate < MediaQuality.ORIGINAL_BPS
            MediaPlan(
                p.itemId, "http://192.168.1.20:8096/v/${p.itemId}", if (converting) PlayMethod.TRANSCODE else PlayMethod.DIRECT_PLAY,
                version.copy(transcodeReasons = if (converting) listOf("ContainerBitrateExceedsLimit") else emptyList()),
                "ps", hls = converting, audioIndex = p.audioIndex, subtitleIndex = p.subtitleIndex, bitrate = p.maxBitrate.takeIf { converting },
            )
        }
    }
    private val nasLib = FakeLibrary(share).apply {
        details = mapOf("f1" to MediaDetail(MediaTitle("f1", "nas", MediaTitle.Kind.VIDEO, "Kupferstadt", fromShare = true), listOf(version)))
        plan = { p -> MediaPlan(p.itemId, "gcshare://share/kupferstadt.mkv", PlayMethod.DIRECT_PLAY, version, null, hls = false, audioIndex = null, subtitleIndex = null) }
    }
    private val hub = mockk<MediaHub>(relaxed = true) {
        every { this@mockk.store } returns this@PhoneMediaVideosTest.store
        every { this@mockk.link } returns this@PhoneMediaVideosTest.link
        every { library(jellyfin) } returns jfLib
        every { library(share) } returns nasLib
        every { caps() } returns MediaDeviceCaps.fromDecoders(listOf("audio/mp4a-latm"), hdrScreen = false, name = "GateControl")
        coEvery { check(any(), any()) } returns Unit
        every { later(any()) } answers { runBlocking { firstArg<suspend () -> Unit>().invoke() } }
    }

    private var quality = MediaQualitySetting.AUTO
    private var cap: Long? = 3_000_000
    private var cellular = true

    private fun videos(queue: MediaQueue) = PhoneMediaVideos(queue, hub, { quality }, { quality = it }, { cap }, { cellular })

    private val series = MediaQueue("jf", listOf(episode(4), episode(5), episode(6)), 0)

    @Test
    fun `on mobile data at most the limit, the line says so, from where it was left`() = runTest {
        val v = videos(series)
        val r = v.open(0) as PhoneOpen.Ready
        assertEquals(3_000_000, jfLib.plays.last().maxBitrate)
        assertEquals(1_601_000L, r.startMs)
        assertEquals(MediaNet.CELLULAR, r.line.net)
        assertEquals(3_000_000L, r.line.convertedBps)
        assertEquals(3_000_000L, r.line.limitBps)
        // the measured line alone would give 8 Mbit/s
        cap = 1_000_000
        v.open(1)
        assertEquals(1_000_000, jfLib.plays.last().maxBitrate)
    }

    @Test
    fun `over Wi-Fi away the measured line decides, at home the original, no limit`() = runTest {
        cellular = false
        link.value = MediaLink(atHome = false, bps = 18_000_000)
        val v = videos(series)
        v.open(0)
        assertEquals(MediaQuality.step(18_000_000), jfLib.plays.last().maxBitrate)
        link.value = MediaLink(atHome = true)
        cellular = true
        val r = v.open(1) as PhoneOpen.Ready
        assertEquals(MediaQuality.ORIGINAL_BPS, jfLib.plays.last().maxBitrate)
        assertEquals(MediaNet.HOME, r.line.net)
        assertNull(r.line.limitBps)
        assertFalse(r.line.converted)
    }

    @Test
    fun `Immer Original is not limited`() = runTest {
        sources.value = listOf(jellyfin.copy(alwaysOriginal = true), share)
        every { hub.library(match { it.id == "jf" }) } returns jfLib
        videos(series).open(0)
        assertEquals(MediaQuality.ORIGINAL_BPS, jfLib.plays.last().maxBitrate)
    }

    @Test
    fun `another sound is asked again and kept for the series, the next episode takes it`() = runTest {
        val v = videos(series)
        v.open(0)
        assertEquals(1, jfLib.plays.last().audioIndex)
        assertFalse(v.chooseAudio(0, 1), "the same sound: nothing to ask")
        assertTrue(v.chooseAudio(0, 2))
        assertEquals("eng", choices["s1"]?.audioLanguage)
        val again = v.open(0, from = 900_000) as PhoneOpen.Ready
        assertEquals(2, jfLib.plays.last().audioIndex)
        assertEquals(900_000L, again.startMs)
        v.open(1)
        assertEquals(2, jfLib.plays.last().audioIndex, "the series' choice for the next episode")
        assertTrue(v.chooseSubtitle(1, 3))
        assertEquals("ger", choices["s1"]?.subtitleLanguage)
        assertTrue(v.chooseSubtitle(1, MediaPlay.SUBTITLES_OFF))
        assertEquals(true, choices["s1"]?.subtitlesOff)
    }

    @Test
    fun `the sheet's choices and a quality kept as the setting`() = runTest {
        val v = videos(series)
        v.open(0)
        val c = v.choices(0)!!
        assertEquals(listOf(1, 2), c.audio.map { it.index })
        assertEquals(listOf(3), c.subtitles.map { it.index })
        assertEquals(MediaQualitySetting.entries.toList(), c.qualities)
        assertTrue(v.chooseQuality(0, MediaQualitySetting.MBIT_2))
        assertEquals(MediaQualitySetting.MBIT_2, quality)
        v.open(0)
        assertEquals(2_000_000, jfLib.plays.last().maxBitrate)
    }

    @Test
    fun `the detail page's original for the first title only`() = runTest {
        cellular = false
        link.value = MediaLink(atHome = false, bps = 18_000_000)
        val v = videos(series.copy(original = true))
        v.open(0)
        assertEquals(MediaQuality.ORIGINAL_BPS, jfLib.plays.last().maxBitrate)
        v.open(1)
        assertEquals(MediaQuality.step(18_000_000), jfLib.plays.last().maxBitrate)
    }

    @Test
    fun `a share's file plays the original over SMB, its series' languages go along, progress on the phone`() = runTest {
        choices["s9"] = MediaTrackChoice("eng", null, subtitlesOff = true)
        val t = MediaTitle("f1", "nas", MediaTitle.Kind.VIDEO, "Kupferstadt", seriesId = "s9", fromShare = true)
        val v = videos(MediaQueue("nas", listOf(t), 0, startMs = 0))
        val r = v.open(0) as PhoneOpen.Ready
        assertEquals(MediaQuality.ORIGINAL_BPS, nasLib.plays.last().maxBitrate)
        assertEquals("SMB", r.line.share)
        assertEquals("eng", r.shareChoice?.audioLanguage)
        assertNull(v.choices(0))
        v.report(0, PlaybackEvent.START, 0, false, 5_000_000)
        v.report(0, PlaybackEvent.STOP, 120_000, true, 5_000_000)
        assertEquals(listOf("start" to 0L, "stop" to 120_000L), nasLib.reports)
        v.rememberShare(0, "ger", null, subtitlesOff = true)
        verify { store.setTrackChoice("s9", MediaTrackChoice("ger", null, true)) }
    }

    @Test
    fun `the progress goes to Jellyfin, a source gone or offline says so`() = runTest {
        val v = videos(series)
        v.open(0)
        v.report(0, PlaybackEvent.PROGRESS, 1_700_000, false, 2_640_000)
        assertEquals(listOf("progress" to 1_700_000L), jfLib.reports)
        jfLib.details = emptyMap()
        assertEquals(PhoneOpen.Failed, v.open(1))
        assertEquals(PhoneOpen.Failed, videos(MediaQueue("gone", listOf(episode(4)), 0)).open(0))
    }
}
