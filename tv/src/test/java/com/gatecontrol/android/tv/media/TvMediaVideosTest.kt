package com.gatecontrol.android.tv.media

import android.app.Application
import com.gatecontrol.android.media.MediaDetail
import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.MediaLibrary
import com.gatecontrol.android.media.MediaLink
import com.gatecontrol.android.media.MediaPlan
import com.gatecontrol.android.media.MediaPlay
import com.gatecontrol.android.media.MediaQualitySetting
import com.gatecontrol.android.media.MediaQueue
import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaSideSubtitle
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaSourceStore
import com.gatecontrol.android.media.MediaStream
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.MediaTrackChoice
import com.gatecontrol.android.media.MediaVersion
import com.gatecontrol.android.media.PlayMethod
import com.gatecontrol.android.media.jellyfin.JellyfinDeviceCaps
import com.gatecontrol.android.media.player.PlaybackEvent
import com.gatecontrol.android.media.player.ShareDataSource
import com.gatecontrol.android.media.share.ShareFs
import com.gatecontrol.android.media.share.ShareInput
import com.gatecontrol.android.media.share.ShareUri
import com.gatecontrol.android.tv.ui.drive.TvChoiceKind
import com.gatecontrol.android.tv.ui.drive.TvOpen
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Films and episodes in the player: quality by bandwidth, the line, the panel, the series' choice, the progress. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TvMediaVideosTest {

    private val source = MediaSource("s1", MediaSourceKind.JELLYFIN, "DS918+", "http://192.168.1.20:8096", "u1", "Anna", "tok")
    private val version = MediaVersion(
        id = "v1",
        container = "mkv",
        bitrate = 52_000_000,
        streams = listOf(
            MediaStream(0, MediaStream.Type.VIDEO, codec = "hevc", width = 3840, height = 2160, videoRange = "HDR", videoRangeType = "HDR10"),
            MediaStream(1, MediaStream.Type.AUDIO, codec = "dts", language = "ger", channels = 6, isDefault = true),
            MediaStream(2, MediaStream.Type.AUDIO, codec = "truehd", language = "eng", channels = 8),
            MediaStream(3, MediaStream.Type.SUBTITLE, codec = "subrip", language = "ger", textual = true),
            MediaStream(4, MediaStream.Type.SUBTITLE, codec = "pgssub", language = "eng"),
        ),
        defaultAudio = 1,
    )
    private val episodes = listOf(
        MediaTitle("e4", "s1", MediaTitle.Kind.EPISODE, "Der Leuchtturm", seriesId = "se", seriesName = "Station Eisvogel", season = 2, episode = 4, runtimeMs = 2_700_000),
        MediaTitle("e5", "s1", MediaTitle.Kind.EPISODE, "Nebel", seriesId = "se", seriesName = "Station Eisvogel", season = 2, episode = 5),
    )

    private val store = mockk<MediaSourceStore>(relaxed = true) {
        every { source("s1") } returns source
        every { trackChoice(any()) } returns null
    }
    private val lib = mockk<MediaLibrary>(relaxed = true)
    private val quality = MutableStateFlow(MediaQualitySetting.AUTO)
    private val media = mockk<MediaHub>(relaxed = true) {
        every { store } returns this@TvMediaVideosTest.store
        every { library(any()) } returns lib
        every { link } returns MutableStateFlow(MediaLink(atHome = false, bps = 18_000_000))
        every { quality } returns this@TvMediaVideosTest.quality
        every { caps() } returns JellyfinDeviceCaps(passthrough = setOf("dts"))
        every { later(any()) } answers { runBlocking { firstArg<suspend () -> Unit>().invoke() } }
        every { route(any()) } answers { this@TvMediaVideosTest.route }
    }
    private var route = com.gatecontrol.android.media.MediaRoute.VPN
    private val res = RuntimeEnvironment.getApplication().resources
    private val asked = mutableListOf<MediaPlay>()

    private fun answer(method: PlayMethod, side: List<MediaSideSubtitle> = emptyList()) {
        coEvery { lib.detail(any()) } returns MediaResult.Ok(MediaDetail(episodes[0], listOf(version)))
        val play = slot<MediaPlay>()
        coEvery { lib.play(capture(play)) } answers {
            asked += play.captured
            MediaResult.Ok(
                MediaPlan(
                    itemId = play.captured.itemId, url = "http://192.168.1.20:8096/stream", method = method, version = version, playSessionId = "ps",
                    hls = method == PlayMethod.TRANSCODE, audioIndex = play.captured.audioIndex ?: 1, subtitleIndex = play.captured.subtitleIndex,
                    bitrate = play.captured.maxBitrate.takeIf { method == PlayMethod.TRANSCODE }, sideSubtitles = side,
                ),
            )
        }
    }

    private fun videos(queue: MediaQueue = MediaQueue("s1", episodes, 0)) = TvMediaVideos(queue, media, res)

    @Test
    fun `away a 4K file is asked converted, played directly it says so`() = runBlocking {
        answer(PlayMethod.DIRECT_PLAY)
        val v = videos()
        val open = v.open(0) as TvOpen.Ready
        // 52 Mbit/s original over 18 Mbit/s: the NAS is asked for 8
        assertEquals(8_000_000L, asked.single().maxBitrate)
        assertEquals("Played directly · 4K HDR10 · DTS to the AV receiver", open.info)
        assertEquals(0, open.audioOrdinal)
        assertTrue(open.textOff)
        assertEquals("Station Eisvogel · S2E4 · Der Leuchtturm", v.name(0))
        assertEquals("MKV", v.typeName(0))
        assertEquals(10, v.nextCountdownSeconds)
    }

    @Test
    fun `converted - the line says how`() = runBlocking {
        answer(PlayMethod.TRANSCODE)
        val open = videos().open(0) as TvOpen.Ready
        assertEquals("Converted by the NAS: 1080p · 8 Mbit/s", open.info)
        assertNull(open.audioOrdinal)
        assertEquals("application/x-mpegURL", open.item.localConfiguration?.mimeType)
    }

    @Test
    fun `the detail page's original and choice for the first title`() = runBlocking {
        answer(PlayMethod.DIRECT_PLAY)
        videos(MediaQueue("s1", episodes, 0, audioIndex = 2, subtitleIndex = 3, original = true)).open(0)
        val p = asked.single()
        assertEquals(com.gatecontrol.android.media.MediaQuality.ORIGINAL_BPS, p.maxBitrate)
        assertEquals(2, p.audioIndex)
        assertEquals(3, p.subtitleIndex)
    }

    @Test
    fun `the series' sound and subtitle for the next episode`() = runBlocking {
        answer(PlayMethod.DIRECT_PLAY)
        every { store.trackChoice("se") } returns MediaTrackChoice("eng", "ger", false)
        val open = videos().open(1) as TvOpen.Ready
        assertEquals(2, asked.single().audioIndex)
        assertEquals(3, asked.single().subtitleIndex)
        assertEquals(1, open.audioOrdinal)
        assertEquals(0, open.textOrdinal)
    }

    @Test
    fun `the panel - sound, subtitles, quality - and a choice opens it again`() = runBlocking {
        answer(PlayMethod.DIRECT_PLAY)
        val v = videos()
        assertNull(v.choices(0))
        v.open(0)
        val c = v.choices(0)!!
        assertEquals(listOf("German · DTS 5.1", "English · TrueHD 7.1"), c.audio.map { it.label })
        assertTrue(c.audio[0].selected)
        assertEquals("Passed through to the AV receiver", c.audio[0].note)
        assertEquals("Converted (Dolby Digital 5.1)", c.audio[1].note)
        assertEquals(listOf("Off", "German", "English"), c.text.map { it.label })
        assertTrue(c.text[0].selected)
        assertEquals("Picture (PGS) · away the NAS burns it in", c.text[2].note)
        assertEquals("Automatic", c.quality0?.label)

        // the chosen one again: nothing to do
        assertFalse(v.choose(0, c.audio[0]))
        assertTrue(v.choose(0, c.text.first { it.id == "3" }))
        verify { store.setTrackChoice("se", MediaTrackChoice("ger", "ger", false)) }
        assertTrue(v.choose(0, c.quality.first { it.id == "4" }))
        verify { media.setQuality(MediaQualitySetting.MBIT_4) }
        v.open(0)
        assertEquals(4_000_000L, asked.last().maxBitrate)
        assertEquals(3, asked.last().subtitleIndex)
        assertEquals(TvChoiceKind.QUALITY, v.choices(0)!!.quality0?.kind)
    }

    @Test
    fun `a subtitle beside the video is not chosen by place`() = runBlocking {
        answer(PlayMethod.DIRECT_PLAY, side = listOf(MediaSideSubtitle(3, "http://192.168.1.20:8096/s.srt", "application/x-subrip", "ger", "Deutsch")))
        val open = videos(MediaQueue("s1", episodes, 0, subtitleIndex = 3)).open(0) as TvOpen.Ready
        assertNull(open.textOrdinal)
        assertFalse(open.textOff)
        assertEquals(1, open.item.localConfiguration?.subtitleConfigurations?.size)
    }

    @Test
    fun `away with the VPN off - started by itself, else Unterwegs nur ueber VPN`() = runBlocking {
        answer(PlayMethod.DIRECT_PLAY)
        route = com.gatecontrol.android.media.MediaRoute.BLOCKED
        coEvery { media.prepare(any()) } returns com.gatecontrol.android.media.MediaGate.BLOCKED
        assertEquals(TvOpen.Vpn, videos().open(0))
        assertTrue("nothing asked of the NAS", asked.isEmpty())
        coEvery { media.prepare(any()) } answers {
            route = com.gatecontrol.android.media.MediaRoute.VPN
            com.gatecontrol.android.media.MediaGate.OPEN
        }
        assertTrue(videos().open(0) is TvOpen.Ready)
    }

    @Test
    fun `signed out, offline, and the progress to Jellyfin`() = runBlocking {
        coEvery { lib.detail(any()) } returns MediaResult.Unauthorized
        assertEquals(TvOpen.SignedOut, videos().open(0))
        coEvery { lib.detail(any()) } returns MediaResult.Offline
        assertEquals(TvOpen.Offline, videos().open(0))

        answer(PlayMethod.DIRECT_PLAY)
        val v = videos()
        v.report(0, PlaybackEvent.START, 0, false)
        coVerify(exactly = 0) { lib.started(any()) }
        v.open(0)
        v.report(0, PlaybackEvent.START, 0, false)
        v.report(0, PlaybackEvent.PROGRESS, 42_000, true)
        v.report(0, PlaybackEvent.STOP, 60_000, false)
        coVerify { lib.started(match { it.positionMs == 0L }) }
        coVerify { lib.progress(match { it.positionMs == 42_000L && it.paused }) }
        coVerify { lib.stopped(match { it.positionMs == 60_000L }) }
    }

    @Test
    fun `a film counts down nothing, starts where Jellyfin has it`() {
        val film = MediaTitle("m1", "s1", MediaTitle.Kind.MOVIE, "Kobaltnacht", runtimeMs = 6_480_000, positionMs = 2_530_000)
        val v = videos(MediaQueue("s1", listOf(film), 0))
        assertEquals(0, v.nextCountdownSeconds)
        assertEquals(2_530_000L, v.startAt(0))
        assertEquals(0L, videos(MediaQueue("s1", listOf(film), 0, startMs = 0)).startAt(0))
    }

    private val share = MediaSource("smb1", MediaSourceKind.SMB, "nas", "smb://nas/filme", userName = "kino", password = "pw")
    private val shareFs = mockk<ShareFs>(relaxed = true) {
        every { kind } returns MediaSourceKind.SMB
        every { open("Filme/Kobaltnacht.mkv", any()) } answers {
            val bytes = ByteArray(100) { it.toByte() }
            val at = secondArg<Long>().toInt()
            ShareInput(bytes.size.toLong(), java.io.ByteArrayInputStream(bytes, at, bytes.size - at))
        }
    }

    @Test
    fun `a share's film - the original, the file's own tracks, the progress with its length`() = runBlocking {
        val film = MediaTitle("f:Filme/Kobaltnacht.mkv", "smb1", MediaTitle.Kind.MOVIE, "Kobaltnacht", fromShare = true, positionMs = 42_000, runtimeMs = 6_480_000)
        every { store.source("smb1") } returns share
        val shareVersion = MediaVersion("Filme/Kobaltnacht.mkv", container = "mkv", supportsDirectPlay = true)
        coEvery { lib.detail(film.id) } returns MediaResult.Ok(MediaDetail(film, listOf(shareVersion)))
        coEvery { lib.play(any()) } returns MediaResult.Ok(
            MediaPlan(film.id, ShareUri.of("Filme/Kobaltnacht.mkv"), PlayMethod.DIRECT_PLAY, shareVersion, null, hls = false, audioIndex = null, subtitleIndex = null),
        )
        every { lib.files() } returns shareFs
        val v = TvMediaVideos(MediaQueue("smb1", listOf(film), 0), media, res)
        val open = v.open(0) as TvOpen.Ready
        assertEquals("Directly from the share (SMB)", open.info)
        assertEquals("gcshare://share/Filme/Kobaltnacht.mkv", open.item.localConfiguration?.uri.toString())
        // the file's own sound tracks and subtitles in the player's panel, no quality
        assertNull(v.choices(0))
        assertEquals(42_000L, v.startAt(0))
        // the player's source: the share's file (through the space of „mitladen“, which follows nothing here)
        val factory = v.factory(RuntimeEnvironment.getApplication())!!
        val viaPlayer = factory.createDataSource()
        assertEquals(10L, viaPlayer.open(androidx.media3.datasource.DataSpec.Builder().setUri(open.item.localConfiguration!!.uri).setPosition(90).build()))
        viaPlayer.close()
        v.release()
        v.report(0, PlaybackEvent.PROGRESS, 50_000, false, durationMs = 6_480_000)
        coVerify { lib.progress(match { it.positionMs == 50_000L && it.durationMs == 6_480_000L }) }

        // the player reads the file through the share from its place
        val ds = ShareDataSource { shareFs }
        val length = ds.open(androidx.media3.datasource.DataSpec.Builder().setUri(open.item.localConfiguration!!.uri).setPosition(90).build())
        assertEquals(10L, length)
        val buf = ByteArray(32)
        assertEquals(10, ds.read(buf, 0, 32))
        assertEquals(90.toByte(), buf[0])
        assertEquals(androidx.media3.common.C.RESULT_END_OF_INPUT, ds.read(buf, 0, 32))
        ds.close()
        // another URL is not a share's file
        assertTrue(runCatching { ShareDataSource { shareFs }.open(androidx.media3.datasource.DataSpec(android.net.Uri.parse("https://192.168.1.20/a.mkv"))) }.isFailure)
    }
}
