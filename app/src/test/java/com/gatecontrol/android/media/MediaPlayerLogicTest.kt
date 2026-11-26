package com.gatecontrol.android.media

import com.gatecontrol.android.media.jellyfin.JellyfinDeviceCaps
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The rules of the phone's player (stage 4c): gestures, the mobile data limit, the info line, the next episode. */
class MediaPlayerLogicTest {

    private val jellyfin = MediaSource("jf", MediaSourceKind.JELLYFIN, "DS918+", "http://192.168.1.20:8096", token = "tok")
    private val share = MediaSource("nas", MediaSourceKind.SMB, "nas", "smb://nas/filme")

    /** A phone: AAC and AC3 itself, no DTS. */
    private val caps = JellyfinDeviceCaps(passthrough = emptySet(), maxAudioChannels = 6, name = "GateControl")

    private val video4k = MediaStream(0, MediaStream.Type.VIDEO, codec = "hevc", width = 3840, height = 2160, videoRange = "HDR", videoRangeType = "HDR10")
    private val dts = MediaStream(1, MediaStream.Type.AUDIO, codec = "dts", language = "ger", channels = 6)
    private val aac = MediaStream(2, MediaStream.Type.AUDIO, codec = "aac", language = "ger", channels = 2)

    private fun plan(method: PlayMethod, audio: Int = 1, bitrate: Long? = null, reasons: List<String> = emptyList()) = MediaPlan(
        itemId = "m1",
        url = "http://192.168.1.20:8096/v",
        method = method,
        version = MediaVersion("v1", container = "mkv", bitrate = 40_000_000, streams = listOf(video4k, dts, aac), transcodeReasons = reasons),
        playSessionId = "ps",
        hls = method == PlayMethod.TRANSCODE,
        audioIndex = audio,
        subtitleIndex = null,
        bitrate = bitrate,
    )

    @Test
    fun `double tap jumps 10 s inside the video, by the third of the picture`() {
        assertEquals(52_000, MediaPlayerLogic.seekTarget(42_000, 10_000, 100_000))
        assertEquals(0, MediaPlayerLogic.seekTarget(4_000, -10_000, 100_000))
        // not past the end (the player would end it)
        assertEquals(99_500, MediaPlayerLogic.seekTarget(95_000, 10_000, 100_000))
        assertEquals(105_000, MediaPlayerLogic.seekTarget(95_000, 10_000, 0))
        assertEquals(-1, MediaPlayerLogic.zone(10f, 900f))
        assertEquals(0, MediaPlayerLogic.zone(450f, 900f))
        assertEquals(1, MediaPlayerLogic.zone(890f, 900f))
    }

    @Test
    fun `a swipe up brightens and louder, never quite dark, the steps of the sound`() {
        assertEquals(0.75f, MediaPlayerLogic.swipe(0.5f, -250f, 1000f), 0.001f)
        assertEquals(1f, MediaPlayerLogic.swipe(0.9f, -500f, 1000f))
        assertEquals(0f, MediaPlayerLogic.swipe(0.1f, 500f, 1000f))
        // the system's brightness (negative) counts as half
        assertEquals(0.6f, MediaPlayerLogic.brightness(-1f, -100f, 1000f), 0.001f)
        assertEquals(0.02f, MediaPlayerLogic.brightness(0.1f, 900f, 1000f))
        assertEquals(8, MediaPlayerLogic.volumeStep(0.5f, 15))
        assertEquals(15, MediaPlayerLogic.volumeStep(1f, 15))
        assertEquals(0, MediaPlayerLogic.volumeStep(0f, 15))
    }

    @Test
    fun `picture-in-picture in the video's ratio, inside what Android takes`() {
        assertEquals(1920 to 1080, MediaPlayerLogic.pipRatio(1920, 1080))
        assertEquals(239 to 100, MediaPlayerLogic.pipRatio(3840, 1200))
        assertEquals(100 to 239, MediaPlayerLogic.pipRatio(300, 1200))
        assertEquals(16 to 9, MediaPlayerLogic.pipRatio(0, 0))
    }

    @Test
    fun `the mobile data limit holds Jellyfin down away, not at home, not a share, not Immer Original`() {
        assertEquals(3_000_000L, MediaPlayerLogic.limit(jellyfin, cellular = true, atHome = false, cap = 3_000_000))
        assertEquals(3_000_000L, MediaPlayerLogic.limit(jellyfin, cellular = true, atHome = null, cap = 3_000_000))
        assertNull(MediaPlayerLogic.limit(jellyfin, cellular = true, atHome = true, cap = 3_000_000))
        assertNull(MediaPlayerLogic.limit(jellyfin, cellular = false, atHome = false, cap = 3_000_000))
        assertNull(MediaPlayerLogic.limit(jellyfin, cellular = true, atHome = false, cap = null))
        assertNull(MediaPlayerLogic.limit(share, cellular = true, atHome = false, cap = 3_000_000))
        assertNull(MediaPlayerLogic.limit(jellyfin.copy(alwaysOriginal = true), cellular = true, atHome = false, cap = 3_000_000))
        assertEquals(3_000_000, MediaPlayerLogic.capped(8_000_000, 3_000_000))
        assertEquals(2_000_000, MediaPlayerLogic.capped(2_000_000, 3_000_000))
        assertEquals(MediaQuality.ORIGINAL_BPS, MediaPlayerLogic.capped(MediaQuality.ORIGINAL_BPS, null))
    }

    @Test
    fun `the network of the line`() {
        assertEquals(MediaNet.HOME, MediaPlayerLogic.net(atHome = true, cellular = false))
        assertEquals(MediaNet.CELLULAR, MediaPlayerLogic.net(atHome = false, cellular = true))
        assertEquals(MediaNet.AWAY, MediaPlayerLogic.net(atHome = false, cellular = false))
        assertNull(MediaPlayerLogic.net(atHome = null, cellular = false))
    }

    @Test
    fun `at home direct in 4K HDR, the DTS sound converted to AAC 5 point 1`() {
        val p = plan(PlayMethod.TRANSCODE, reasons = listOf("AudioCodecNotSupported"))
        assertFalse(MediaPlayerLogic.pictureConverted(p))
        val line = MediaPlayerLogic.line(p, MediaNet.HOME, null, caps, canConvert = true, limitBps = null, askedBps = MediaQuality.ORIGINAL_BPS)
        assertEquals(MediaPlayLine(MediaNet.HOME, video = "4K HDR10", audioTo = "AAC 5.1"), line)
    }

    @Test
    fun `on mobile data the NAS converts the picture to the limit`() {
        val p = plan(PlayMethod.TRANSCODE, bitrate = 3_000_000, reasons = listOf("ContainerBitrateExceedsLimit", "AudioCodecNotSupported"))
        assertTrue(MediaPlayerLogic.pictureConverted(p))
        val line = MediaPlayerLogic.line(p, MediaNet.CELLULAR, 6_000_000, caps, canConvert = true, limitBps = 3_000_000, askedBps = 3_000_000)
        assertEquals(MediaPlayLine(MediaNet.CELLULAR, measuredBps = 6_000_000, convertedBps = 3_000_000, converted = true, limitBps = 3_000_000), line)
        // converted without reasons: the picture too; the bit rate asked when the server names none
        val plain = MediaPlayerLogic.line(plan(PlayMethod.TRANSCODE), MediaNet.AWAY, 18_000_000, caps, true, null, askedBps = 8_000_000)
        assertEquals(8_000_000L, plain.convertedBps)
        assertNull(plain.limitBps)
    }

    @Test
    fun `played directly with the AAC sound nothing is converted, a share says SMB`() {
        val direct = MediaPlayerLogic.line(plan(PlayMethod.DIRECT_PLAY, audio = 2), MediaNet.AWAY, 50_000_000, caps, true, null, MediaQuality.ORIGINAL_BPS)
        assertEquals(MediaPlayLine(MediaNet.AWAY, measuredBps = 50_000_000, video = "4K HDR10"), direct)
        val s = MediaPlayerLogic.line(plan(PlayMethod.DIRECT_PLAY), MediaNet.HOME, 9_000_000, caps, false, null, MediaQuality.ORIGINAL_BPS, shareVia = "SMB")
        assertEquals("SMB", s.share)
        assertNull(s.measuredBps)
        assertNull(s.audioTo)
    }

    private fun episode(n: Int) = MediaTitle("e$n", "jf", MediaTitle.Kind.EPISODE, "Folge $n", seriesName = "Station Eisvogel", season = 2, episode = n)

    @Test
    fun `the next episodes, the countdown after one and not after a film or the last`() {
        val q = MediaQueue("jf", listOf(episode(4), episode(5), episode(6)), 0)
        assertEquals(listOf(1, 2), MediaPlayerLogic.nextUp(q, 0).map { it.first })
        assertEquals(MediaPlayerLogic.NEXT_SECONDS, MediaPlayerLogic.countdownAfter(q, 0))
        assertNull(MediaPlayerLogic.countdownAfter(q, 2))
        val film = MediaQueue("jf", listOf(MediaTitle("m1", "jf", MediaTitle.Kind.MOVIE, "Kobaltnacht")), 0)
        assertNull(MediaPlayerLogic.countdownAfter(film, 0))
        assertEquals("Station Eisvogel · S2 E4 · Folge 4", MediaPlayerLogic.name(episode(4)))
        assertEquals("Kobaltnacht", MediaPlayerLogic.name(film.items[0]))
    }

    @Test
    fun `a video that did not start - the next way, a lost connection is no reason`() {
        assertEquals(MediaFallback.REMUX, MediaPlayerLogic.nextFallback(plan(PlayMethod.DIRECT_PLAY), MediaFallback.NONE))
        assertEquals(MediaFallback.REMUX, MediaPlayerLogic.nextFallback(plan(PlayMethod.DIRECT_STREAM), MediaFallback.NONE))
        // the server converted already (the sound, say): everything converted
        assertEquals(MediaFallback.CONVERT, MediaPlayerLogic.nextFallback(plan(PlayMethod.TRANSCODE), MediaFallback.NONE))
        assertEquals(MediaFallback.CONVERT, MediaPlayerLogic.nextFallback(plan(PlayMethod.TRANSCODE), MediaFallback.REMUX))
        assertNull(MediaPlayerLogic.nextFallback(plan(PlayMethod.TRANSCODE), MediaFallback.CONVERT))
        assertTrue(MediaPlayerLogic.fallsBackOn(MediaPlayerTrouble.CANNOT_PLAY))
        assertTrue(MediaPlayerLogic.fallsBackOn(MediaPlayerTrouble.FAILED))
        assertFalse(MediaPlayerLogic.fallsBackOn(MediaPlayerTrouble.NETWORK))
    }

    private fun MediaStallWatch.run(seconds: Int, playing: Boolean = true, loading: Boolean = true, ready: Boolean = false, pictured: Boolean = false, net: MediaNet? = MediaNet.HOME): Boolean =
        (1..seconds).map { step(1_000, playing, loading, ready, pictured, net) }.last()

    @Test
    fun `no first picture - at home after 8 s, away after 15 s, paused does not count`() {
        assertEquals(8_000L, MediaPlayerLogic.FIRST_PICTURE_HOME_MS)
        assertEquals(15_000L, MediaPlayerLogic.FIRST_PICTURE_AWAY_MS)
        assertFalse(MediaStallWatch().run(7))
        assertTrue(MediaStallWatch().run(8))
        assertFalse(MediaStallWatch().run(14, net = MediaNet.AWAY))
        assertTrue(MediaStallWatch().run(15, net = MediaNet.AWAY))
        assertTrue(MediaStallWatch().run(15, net = MediaNet.CELLULAR))
        // the sound runs without a picture: counts too
        assertTrue(MediaStallWatch().run(8, loading = false, ready = true))
        // waiting with play off (the note on the data volume, loading ahead): never
        assertFalse(MediaStallWatch().run(60, playing = false))
    }

    @Test
    fun `after the start - three times longer than 10 s loading, short ones do not count`() {
        assertEquals(10_000L, MediaPlayerLogic.STALL_MS)
        assertEquals(3, MediaPlayerLogic.STALLS)
        val w = MediaStallWatch()
        // played well, then often loading for 9 s: fine
        repeat(5) {
            assertFalse(w.run(9, pictured = true))
            assertFalse(w.run(5, loading = false, ready = true, pictured = true))
        }
        assertEquals(0, w.stalls)
        // hanging twice: one count each, however long
        assertFalse(w.run(30, pictured = true))
        assertFalse(w.run(3, loading = false, ready = true, pictured = true))
        assertFalse(w.run(10, pictured = true))
        assertFalse(w.run(3, loading = false, ready = true, pictured = true))
        assertEquals(2, w.stalls)
        // paused while loading does not count
        assertFalse(w.run(20, playing = false, pictured = true))
        assertTrue(w.run(10, pictured = true))
        assertEquals(3, w.stalls)
    }

    @Test
    fun `converted on the NAS - a longer wait for the first picture, hanging three times still counts`() {
        val w = MediaStallWatch(last = true)
        assertFalse(w.run(15))
        assertTrue(w.run(1))
        assertEquals(30_000L, MediaPlayerLogic.firstPictureMs(MediaNet.AWAY, last = true))
        val started = MediaStallWatch(last = true)
        repeat(2) {
            assertFalse(started.run(11, pictured = true))
            assertFalse(started.run(2, loading = false, ready = true, pictured = true))
        }
        assertTrue(started.run(10, pictured = true))
    }

    private val pgs = MediaStream(4, MediaStream.Type.SUBTITLE, codec = "pgssub", language = "ger", textual = false)
    private val srt = MediaStream(5, MediaStream.Type.SUBTITLE, codec = "subrip", language = "ger", textual = true)

    @Test
    fun `asked another way no picture subtitle is burnt in, played directly Media3 shows it`() {
        val v = MediaVersion("v1", container = "mkv", streams = listOf(video4k, dts, pgs, srt), defaultSubtitle = 4)
        assertEquals(4, MediaPlayerLogic.subtitleFor(v, 4, MediaFallback.NONE))
        assertNull(MediaPlayerLogic.subtitleFor(v, null, MediaFallback.NONE))
        assertEquals(MediaPlay.SUBTITLES_OFF, MediaPlayerLogic.subtitleFor(v, 4, MediaFallback.REMUX))
        // the server's default is the picture subtitle: off too
        assertEquals(MediaPlay.SUBTITLES_OFF, MediaPlayerLogic.subtitleFor(v, null, MediaFallback.CONVERT))
        assertEquals(5, MediaPlayerLogic.subtitleFor(v, 5, MediaFallback.CONVERT))
    }

    @Test
    fun `the codecs said when nothing plays`() {
        val v = MediaVersion("v1", container = "mkv", streams = listOf(video4k.copy(profile = "Main 10", bitDepth = 10), dts, aac), defaultAudio = 1)
        assertEquals("HEVC Main 10 10 Bit · DTS 5.1 · MKV", MediaPlayerLogic.codecs(v, null))
        assertEquals("HEVC Main 10 10 Bit · AAC Stereo · MKV", MediaPlayerLogic.codecs(v, 2))
    }
}
