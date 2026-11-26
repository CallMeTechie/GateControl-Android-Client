package com.gatecontrol.android.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MediaLogicTest {

    private fun episode(id: String, n: Int) =
        MediaTitle(id, "s1", MediaTitle.Kind.EPISODE, "Folge $n", seriesId = "se", seriesName = "Station Eisvogel", season = 2, episode = n)

    @Test
    fun `time left`() {
        assertEquals("1:06", MediaLogic.hoursMinutes(66 * 60_000L))
        assertNull(MediaLogic.hoursMinutes(18 * 60_000L))
        assertEquals(19L, MediaLogic.minutes(18 * 60_000L + 30_000))
        assertEquals("1:06", MediaLogic.hoursMinutes(65 * 60_000L + 50_000))
        assertEquals(1L, MediaLogic.minutes(10_000))
    }

    @Test
    fun `an episode goes on with the rest of its series, a film alone`() {
        val all = listOf(episode("e1", 1), episode("e2", 2), episode("e3", 3))
        val q = MediaLogic.queue("s1", all[1], all, startMs = 0)
        assertEquals(listOf("e1", "e2", "e3"), q.items.map { it.id })
        assertEquals(1, q.at)
        assertEquals(0L, q.startMs)
        val film = MediaTitle("m1", "s1", MediaTitle.Kind.MOVIE, "Kobaltnacht")
        val single = MediaLogic.queue("s1", film, emptyList())
        assertEquals(listOf("m1"), single.items.map { it.id })
        assertNull(single.startMs)
        // an episode the list does not know: alone
        assertEquals(listOf("e9"), MediaLogic.queue("s1", episode("e9", 9), all).items.map { it.id })
    }

    @Test
    fun `labels of the source and the line`() {
        assertEquals("Jellyfin · DS918+", MediaLogic.sourceLabel(MediaSource("a", MediaSourceKind.JELLYFIN, "DS918+", "http://192.168.1.20:8096")))
        assertEquals("18 Mbit/s", MediaLogic.bandwidth(MediaLink(atHome = false, bps = 18_000_000)))
        assertNull(MediaLogic.bandwidth(MediaLink(atHome = true, bps = 18_000_000)))
        assertNull(MediaLogic.bandwidth(MediaLink(atHome = false)))
    }

    @Test
    fun `the next page near the end of a row`() {
        assertTrue(MediaLogic.wantsMore(35, 40, 40))
        assertFalse(MediaLogic.wantsMore(10, 40, 40))
        assertFalse(MediaLogic.wantsMore(39, 40, null))
    }

    @Test
    fun `recently added without what is in continue watching`() {
        val film = MediaTitle("m1", "s1", MediaTitle.Kind.MOVIE, "Kobaltnacht")
        val series = MediaTitle("se", "s1", MediaTitle.Kind.SERIES, "Station Eisvogel")
        val other = MediaTitle("m2", "s1", MediaTitle.Kind.MOVIE, "Sternwarte 9")
        assertEquals(listOf("m2"), MediaLogic.latestWithout(listOf(film, series, other), listOf(film, episode("e4", 4))).map { it.id })
    }

    @Test
    fun `next up without the episode begun, new episodes counted, genres of all sources once`() {
        val begun = episode("e4", 4)
        assertEquals(listOf("e5"), MediaLogic.nextUpWithout(listOf(begun, episode("e5", 5)), listOf(begun)).map { it.id })
        val graufeld = MediaTitle("s3", "s1", MediaTitle.Kind.SERIES, "Graufeld", newCount = 3)
        assertEquals(4, MediaLogic.newEpisodes(listOf(graufeld, episode("e7", 7))))
        assertEquals(listOf("Doku", "Krimi", "Science-Fiction"), MediaLogic.mergeGenres(listOf("Krimi", "Science-Fiction"), listOf(" krimi", "Doku", "")))
    }

    @Test
    fun `only favourites - a share has none`() {
        val fav = MediaTitle("m1", "s1", MediaTitle.Kind.MOVIE, "Sternwarte 9", favorite = true)
        val other = MediaTitle("m2", "s1", MediaTitle.Kind.MOVIE, "Moorlicht")
        assertEquals(listOf("m1"), MediaLogic.filtered(listOf(fav, other), MediaOrder(favorite = true)).map { it.id })
        assertFalse(MediaOrder(favorite = true).plain)
    }

    private val smb = MediaSource("smb1", MediaSourceKind.SMB, "nas", "smb://nas/filme", userName = "kino", password = "pw", pairWith = "dav1")
    private val dav = MediaSource("dav1", MediaSourceKind.WEBDAV, "dav", "https://192.168.1.20:5006/filme", userName = "kino", password = "pw", pairWith = "smb1")
    private val alone = MediaSource("dav2", MediaSourceKind.WEBDAV, "dav", "https://192.168.1.20:5006/serien")
    private val jelly = MediaSource("j1", MediaSourceKind.JELLYFIN, "DS918+", "http://192.168.1.20:8096", token = "t")

    @Test
    fun `a pair - at home SMB, away WebDAV, shown once`() {
        assertEquals(listOf("smb1", "dav2"), MediaLogic.shares(listOf(jelly, dav, smb, alone)).map { it.id })
        assertEquals("smb1", MediaLogic.shareFor(smb, dav, atHome = true)?.id)
        assertEquals("dav1", MediaLogic.shareFor(smb, dav, atHome = false)?.id)
        // not known yet: as at home
        assertEquals("smb1", MediaLogic.shareFor(smb, dav, atHome = null)?.id)
        // one of them alone is used anywhere
        assertEquals("smb1", MediaLogic.shareFor(smb, null, atHome = false)?.id)
        assertEquals("dav1", MediaLogic.shareFor(null, dav, atHome = true)?.id)
        assertEquals("NAS-Freigabe · \\\\nas\\filme", MediaLogic.sourceLabel(smb))
        assertEquals("NAS share · 192.168.1.20:5006/serien", MediaLogic.sourceLabel(alone, "NAS share"))
        assertEquals("Jellyfin · DS918+", MediaLogic.sourceLabel(jelly, "NAS share"))
        // the head of the page: only the kind, never the host or the server's name
        assertEquals("Jellyfin", MediaLogic.kindLabel(jelly, "NAS share"))
        assertEquals("NAS share", MediaLogic.kindLabel(alone, "NAS share"))
        assertEquals("NAS-Freigabe", MediaLogic.kindLabel(smb))
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
        assertEquals(listOf("f:Kupferstadt.1961.mkv", "f:Graufeld.mkv", "f:Moorlicht.mkv"), MediaLogic.withoutDuplicates(shares, jf).map { it.id })
        val ep = MediaTitle("e", "j1", MediaTitle.Kind.EPISODE, "Ebbe", seriesName = "Graufeld", season = 1, episode = 2)
        assertTrue(MediaLogic.same(ep, ep.copy(id = "f:x", name = "Graufeld", fromShare = true)))
        assertFalse(MediaLogic.same(ep, ep.copy(id = "f:y", episode = 3)))
        assertEquals(shares, MediaLogic.withoutDuplicates(shares, emptyList()))
    }

    @Test
    fun `Filme A to Z - a share's titles in their places, later ones wait for the next page`() {
        val jf = listOf(movie("a", "Achterwasser"), movie("b", "Blaue Stunde in Tarnow"), movie("k", "Kobaltnacht"))
        val shares = listOf(movie("f:z", "Zwielicht", share = true), movie("f:d", "Der Pegelwart", share = true), movie("f:aa", "Ankerplatz", share = true))
        assertEquals(listOf("a", "f:aa", "b", "f:d", "k"), MediaLogic.alphabetical(jf, jellyfinComplete = false, shares).map { it.id })
        assertEquals(listOf("a", "f:aa", "b", "f:d", "k", "f:z"), MediaLogic.alphabetical(jf, jellyfinComplete = true, shares).map { it.id })
        // only shares
        assertEquals(listOf("f:aa", "f:d", "f:z"), MediaLogic.alphabetical(emptyList(), jellyfinComplete = true, shares).map { it.id })
    }

    @Test
    fun `Weiterschauen of all sources - watched last first, Zuletzt hinzugefügt newest first`() {
        val jf = listOf(movie("k", "Kobaltnacht", playedAt = 300), movie("p", "Paulas Werkstatt", playedAt = 100))
        val share = listOf(movie("f:l", "Die Lindqvist-Akten", share = true, playedAt = 200))
        assertEquals(listOf("k", "f:l", "p"), MediaLogic.byLastPlayed(listOf(jf, share), 12).map { it.id })
        assertEquals(listOf("k", "f:l"), MediaLogic.byLastPlayed(listOf(jf, share), 2).map { it.id })
        val latest = MediaLogic.byAdded(listOf(listOf(movie("s", "Sternwarte 9", addedAt = 50)), listOf(movie("f:g", "Graufeld", share = true, addedAt = 70))), 16)
        assertEquals(listOf("f:g", "s"), latest.map { it.id })
    }
}
