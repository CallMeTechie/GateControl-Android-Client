package com.gatecontrol.android.media.offline

import com.gatecontrol.android.media.MediaStream
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.MediaVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/** The rules of the downloads (question 22 a): sizes, the limit, the progress watched offline, the rows of the page. */
class MediaOfflineLogicTest {
    private val video = MediaStream(0, MediaStream.Type.VIDEO, codec = "hevc", width = 3840, height = 2160)
    private val dts = MediaStream(1, MediaStream.Type.AUDIO, codec = "dts", language = "ger", channels = 6)
    private val srtExt = MediaStream(3, MediaStream.Type.SUBTITLE, codec = "srt", language = "ger", textual = true, external = true)
    private val srtIn = MediaStream(4, MediaStream.Type.SUBTITLE, codec = "subrip", language = "eng", textual = true)
    private val pgs = MediaStream(5, MediaStream.Type.SUBTITLE, codec = "pgssub", language = "ger")
    private val version = MediaVersion("v1", container = "mkv", bitrate = 40_000_000, runtimeMs = 6_480_000, streams = listOf(video, dts, srtExt, srtIn, pgs), size = 32_400_000_000)

    private fun item(id: String, kind: MediaTitle.Kind = MediaTitle.Kind.MOVIE, state: OfflineState = OfflineState.READY, ep: Int? = null, added: Long = 0) = OfflineItem(
        id = id.padEnd(32, '0'),
        sourceId = "jf",
        title = MediaTitle(id, "jf", kind, "T $id", seriesId = if (kind == MediaTitle.Kind.EPISODE) "s1" else null, seriesName = "Station Eisvogel", season = 2, episode = ep),
        quality = OfflineQuality.P720,
        state = state,
        addedAt = added,
        size = 950_000_000,
        loaded = 450_000_000,
        expected = 1_000_000_000,
    )

    @Test
    fun `sizes - the original's file, a step by its bit rate and the length`() {
        assertEquals(32_400_000_000, MediaOfflineLogic.estimate(OfflineQuality.ORIGINAL, 6_480_000, version))
        // 1080p: (8 Mbit/s + 192 kbit/s) × 1:48 h, 5 % on top → about 7 GB
        val p1080 = MediaOfflineLogic.estimate(OfflineQuality.P1080, 6_480_000, version)!!
        assertTrue(p1080 in 6_900_000_000..7_100_000_000, "$p1080")
        assertEquals(null, MediaOfflineLogic.estimate(OfflineQuality.P720, null, null))
        // the original without a size: bit rate × length
        assertEquals(32_400_000_000, MediaOfflineLogic.estimate(OfflineQuality.ORIGINAL, 6_480_000, version.copy(size = null)))
        assertTrue(MediaOfflineLogic.convertGains(OfflineQuality.P1080, version))
        assertFalse(MediaOfflineLogic.convertGains(OfflineQuality.P1080, version.copy(bitrate = 5_000_000)))
    }

    @Test
    fun `the storage limit and the room on the phone`() {
        val gb = 1_000_000_000L
        assertNull(MediaOfflineLogic.fits(3 * gb, 7 * gb, 20 * gb, 100 * gb))
        assertEquals(OfflineFailure.LIMIT, MediaOfflineLogic.fits(14 * gb, 7 * gb, 20 * gb, 100 * gb))
        assertEquals(OfflineFailure.SPACE, MediaOfflineLogic.fits(3 * gb, 0, 20 * gb, 3 * gb))
        // the free room unknown (-1): only the limit
        assertNull(MediaOfflineLogic.fits(3 * gb, 0, 20 * gb, -1))
        assertEquals("7.4 / 20 GB", MediaOfflineLogic.usedText(7_400_000_000, 20 * gb, Locale.ROOT))
        assertEquals("3,1 GB", MediaOfflineLogic.sizeText(3_100_000_000, Locale.GERMANY))
        assertEquals("850 MB", MediaOfflineLogic.sizeText(850_000_000, Locale.GERMANY))
    }

    @Test
    fun `watched offline - the place, seen from 90 percent, nothing kept in the first minutes`() {
        assertEquals(2_530_000L to false, MediaOfflineLogic.watched(2_530_000, 6_480_000))
        assertEquals(0L to true, MediaOfflineLogic.watched(6_000_000, 6_480_000))
        assertEquals(0L to false, MediaOfflineLogic.watched(100_000, 6_480_000))
        val i = item("m1").copy(dirty = true, playedAt = 2_000)
        assertTrue(MediaOfflineLogic.shouldSend(i, null))
        assertTrue(MediaOfflineLogic.shouldSend(i, 1_000))
        // watched on the TV after it: the server's is newer
        assertFalse(MediaOfflineLogic.shouldSend(i, 3_000))
        assertFalse(MediaOfflineLogic.shouldSend(i.copy(dirty = false), null))
    }

    @Test
    fun `the player's URIs of a download, nothing else taken for one`() {
        val id = MediaOfflineLogic.idOf("jf", "m1")
        assertEquals(32, id.length)
        assertEquals(id to "video", MediaOfflineLogic.parse(MediaOfflineLogic.uri(id, MediaOfflineLogic.VIDEO)))
        assertEquals(id to "sub-3", MediaOfflineLogic.parse(MediaOfflineLogic.uri(id, MediaOfflineLogic.subPart(3))))
        assertNull(MediaOfflineLogic.parse("gcoffline://$id/../index.bin"))
        assertNull(MediaOfflineLogic.parse("gcoffline://abc/video"))
        assertNull(MediaOfflineLogic.parse("http://192.168.1.20:8096/Videos/m1/stream"))
    }

    @Test
    fun `subtitles beside the file and the streams of a converted one`() {
        // the original: only the file of its own (the others are in the mkv)
        assertEquals(listOf(3), MediaOfflineLogic.sideSubtitles(version, OfflineQuality.ORIGINAL, null).map { it.index })
        // converted: all text subtitles (pictures are burnt in or left out)
        val subs = MediaOfflineLogic.sideSubtitles(version, OfflineQuality.P1080, burnt = 5)
        assertEquals(listOf(3, 4), subs.map { it.index })
        val v = MediaOfflineLogic.convertedVersion(version, OfflineQuality.P720, dts, subs, 6_480_000)
        assertEquals(1280, v.video?.width)
        assertEquals(720, v.video?.height)
        assertEquals("aac", v.audio.single().codec)
        assertEquals("ger", v.audio.single().language)
        assertEquals(listOf(3, 4), v.subtitles.map { it.index })
        assertEquals("ts", v.container)
    }

    @Test
    fun `the rows - a film alone, the episodes of a season together`() {
        val list = listOf(
            item("m1", added = 3),
            item("e6", MediaTitle.Kind.EPISODE, OfflineState.LOADING, ep = 6, added = 2),
            item("e5", MediaTitle.Kind.EPISODE, ep = 5, added = 2),
            item("m2", state = OfflineState.QUEUED, added = 1),
        )
        val groups = MediaOfflineLogic.groups(list)
        assertEquals(3, groups.size)
        assertEquals(listOf("m1"), groups[0].items.map { it.itemId })
        val eps = groups[1]
        assertEquals(listOf("e5", "e6"), eps.items.map { it.itemId })
        assertEquals("S2 F5–F6", MediaOfflineLogic.episodeRange(eps))
        assertEquals("e6", eps.loading?.itemId)
        assertFalse(eps.ready)
        assertTrue(groups[2].waiting)
        assertNull(MediaOfflineLogic.episodeRange(groups[0]))
    }
}
