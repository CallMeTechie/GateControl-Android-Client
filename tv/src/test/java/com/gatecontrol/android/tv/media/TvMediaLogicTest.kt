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

    private val smb = MediaSource("smb1", MediaSourceKind.SMB, "nas", "smb://nas/filme", userName = "kino", password = "pw", pairWith = "dav1")
    private val dav = MediaSource("dav1", MediaSourceKind.WEBDAV, "dav", "https://192.168.1.20:5006/filme", userName = "kino", password = "pw", pairWith = "smb1")
    private val alone = MediaSource("dav2", MediaSourceKind.WEBDAV, "dav", "https://192.168.1.20:5006/serien")
    private val jelly = MediaSource("j1", MediaSourceKind.JELLYFIN, "DS918+", "http://192.168.1.20:8096", token = "t")

    @Test
    fun `a pair - at home SMB, away WebDAV, shown once`() {
        assertEquals(listOf("smb1", "dav2"), TvMediaLogic.shares(listOf(jelly, dav, smb, alone)).map { it.id })
        assertEquals("smb1", TvMediaLogic.shareFor(smb, dav, atHome = true)?.id)
        assertEquals("dav1", TvMediaLogic.shareFor(smb, dav, atHome = false)?.id)
        // not known yet: as at home
        assertEquals("smb1", TvMediaLogic.shareFor(smb, dav, atHome = null)?.id)
        // one of them alone is used anywhere
        assertEquals("smb1", TvMediaLogic.shareFor(smb, null, atHome = false)?.id)
        assertEquals("dav1", TvMediaLogic.shareFor(null, dav, atHome = true)?.id)
        assertEquals("NAS-Freigabe · \\\\nas\\filme", TvMediaLogic.sourceLabel(smb))
        assertEquals("NAS share · 192.168.1.20:5006/serien", TvMediaLogic.sourceLabel(alone, "NAS share"))
        assertEquals("Jellyfin · DS918+", TvMediaLogic.sourceLabel(jelly, "NAS share"))
    }

    private fun movie(id: String, name: String, year: Int? = null, share: Boolean = false, playedAt: Long? = null, addedAt: Long? = null) =
        MediaTitle(id, if (share) "smb1" else "j1", MediaTitle.Kind.MOVIE, name, year = year, fromShare = share, playedAt = playedAt, addedAt = addedAt)

    @Test
    fun `a title in Jellyfin and on a share shows once, Jellyfin's`() {
        val jf = listOf(movie("j-k", "Kupferstadt", 2025), MediaTitle("j-g", "j1", MediaTitle.Kind.SERIES, "Graufeld"))
        val shares = listOf(
            movie("f:Kupferstadt.2025.mkv", "kupferstadt", 2025, share = true),
            // another year: another film
            movie("f:Kupferstadt.1961.mkv", "Kupferstadt", 1961, share = true),
            // a name without a year is the same
            movie("f:Graufeld.mkv", "Graufeld", share = true),
            MediaTitle("s:graufeld", "smb1", MediaTitle.Kind.SERIES, "Graufeld", fromShare = true),
            movie("f:Moorlicht.mkv", "Moorlicht", share = true),
        )
        assertEquals(listOf("f:Kupferstadt.1961.mkv", "f:Graufeld.mkv", "f:Moorlicht.mkv"), TvMediaLogic.withoutDuplicates(shares, jf).map { it.id })
        val ep = MediaTitle("e", "j1", MediaTitle.Kind.EPISODE, "Ebbe", seriesName = "Graufeld", season = 1, episode = 2)
        assertTrue(TvMediaLogic.same(ep, ep.copy(id = "f:x", name = "Graufeld", fromShare = true)))
        assertFalse(TvMediaLogic.same(ep, ep.copy(id = "f:y", episode = 3)))
        assertEquals(shares, TvMediaLogic.withoutDuplicates(shares, emptyList()))
    }

    @Test
    fun `Filme A to Z - a share's titles in their places, later ones wait for the next page`() {
        val jf = listOf(movie("a", "Achterwasser"), movie("b", "Blaue Stunde in Tarnow"), movie("k", "Kobaltnacht"))
        val shares = listOf(movie("f:z", "Zwielicht", share = true), movie("f:d", "Der Pegelwart", share = true), movie("f:aa", "Ankerplatz", share = true))
        assertEquals(listOf("a", "f:aa", "b", "f:d", "k"), TvMediaLogic.alphabetical(jf, jellyfinComplete = false, shares).map { it.id })
        assertEquals(listOf("a", "f:aa", "b", "f:d", "k", "f:z"), TvMediaLogic.alphabetical(jf, jellyfinComplete = true, shares).map { it.id })
        // only shares
        assertEquals(listOf("f:aa", "f:d", "f:z"), TvMediaLogic.alphabetical(emptyList(), jellyfinComplete = true, shares).map { it.id })
    }

    @Test
    fun `Weiterschauen of all sources - watched last first, Zuletzt hinzugefügt newest first`() {
        val jf = listOf(movie("k", "Kobaltnacht", playedAt = 300), movie("p", "Paulas Werkstatt", playedAt = 100))
        val share = listOf(movie("f:l", "Die Lindqvist-Akten", share = true, playedAt = 200))
        assertEquals(listOf("k", "f:l", "p"), TvMediaLogic.byLastPlayed(listOf(jf, share), 12).map { it.id })
        assertEquals(listOf("k", "f:l"), TvMediaLogic.byLastPlayed(listOf(jf, share), 2).map { it.id })
        val latest = TvMediaLogic.byAdded(listOf(listOf(movie("s", "Sternwarte 9", addedAt = 50)), listOf(movie("f:g", "Graufeld", share = true, addedAt = 70))), 16)
        assertEquals(listOf("f:g", "s"), latest.map { it.id })
    }
}
