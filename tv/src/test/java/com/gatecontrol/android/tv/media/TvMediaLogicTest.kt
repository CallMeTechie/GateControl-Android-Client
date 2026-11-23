package com.gatecontrol.android.tv.media

import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaTitle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TvMediaLogicTest {

    private fun episode(id: String, n: Int) =
        MediaTitle(id, "s1", MediaTitle.Kind.EPISODE, "Folge $n", seriesId = "se", seriesName = "Station Eisvogel", season = 2, episode = n)

    @Test
    fun `time left`() {
        assertEquals("1:06", TvMediaLogic.hoursMinutes(66 * 60_000L))
        assertNull(TvMediaLogic.hoursMinutes(18 * 60_000L))
        assertEquals(19L, TvMediaLogic.minutes(18 * 60_000L + 30_000))
        assertEquals("1:06", TvMediaLogic.hoursMinutes(65 * 60_000L + 50_000))
        assertEquals(1L, TvMediaLogic.minutes(10_000))
    }

    @Test
    fun `an episode goes on with the rest of its series, a film alone`() {
        val all = listOf(episode("e1", 1), episode("e2", 2), episode("e3", 3))
        val q = TvMediaLogic.queue("s1", all[1], all, startMs = 0)
        assertEquals(listOf("e1", "e2", "e3"), q.items.map { it.id })
        assertEquals(1, q.at)
        assertEquals(0L, q.startMs)
        val film = MediaTitle("m1", "s1", MediaTitle.Kind.MOVIE, "Kobaltnacht")
        val single = TvMediaLogic.queue("s1", film, emptyList())
        assertEquals(listOf("m1"), single.items.map { it.id })
        assertNull(single.startMs)
        // an episode the list does not know: alone
        assertEquals(listOf("e9"), TvMediaLogic.queue("s1", episode("e9", 9), all).items.map { it.id })
    }

    @Test
    fun `labels of the source and the line`() {
        assertEquals("Jellyfin · DS918+", TvMediaLogic.sourceLabel(MediaSource("a", MediaSourceKind.JELLYFIN, "DS918+", "http://192.168.1.20:8096")))
        assertEquals("18 Mbit/s", TvMediaLogic.bandwidth(TvMediaLink(atHome = false, bps = 18_000_000)))
        assertNull(TvMediaLogic.bandwidth(TvMediaLink(atHome = true, bps = 18_000_000)))
        assertNull(TvMediaLogic.bandwidth(TvMediaLink(atHome = false)))
    }

    @Test
    fun `the next page near the end of a row`() {
        assertTrue(TvMediaLogic.wantsMore(35, 40, 40))
        assertFalse(TvMediaLogic.wantsMore(10, 40, 40))
        assertFalse(TvMediaLogic.wantsMore(39, 40, null))
    }

    @Test
    fun `recently added without what is in continue watching`() {
        val film = MediaTitle("m1", "s1", MediaTitle.Kind.MOVIE, "Kobaltnacht")
        val series = MediaTitle("se", "s1", MediaTitle.Kind.SERIES, "Station Eisvogel")
        val other = MediaTitle("m2", "s1", MediaTitle.Kind.MOVIE, "Sternwarte 9")
        assertEquals(listOf("m2"), TvMediaLogic.latestWithout(listOf(film, series, other), listOf(film, episode("e4", 4))).map { it.id })
    }
}
