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
}
