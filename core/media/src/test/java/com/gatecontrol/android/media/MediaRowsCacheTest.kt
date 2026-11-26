package com.gatecontrol.android.media

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The overview's rows kept per source: in memory and on the disk, by age, without an access. */
class MediaRowsCacheTest {

    private var clock = 1_800_000_000_000L

    private val jellyfin = MediaSource("jf", MediaSourceKind.JELLYFIN, "NAS", "http://192.168.1.20:8096", userId = "u1", token = "geheim-1", serverId = "srv-1")
    private val share = MediaSource("nas", MediaSourceKind.SMB, "nas", "smb://nas.example.test/filme", password = "pw")

    private val film = MediaTitle(
        "m1", "jf", MediaTitle.Kind.MOVIE, "Kobaltnacht", year = 2025, runtimeMs = 6_480_000, positionMs = 2_530_000,
        genres = listOf("Drama", "Krimi"), rating = "FSK-12", posterId = "m1", posterTag = "t1", backdropId = "m1",
        backdropTag = "b1", addedAt = 1_700_000_000_000, playedAt = 1_700_000_100_000,
    )
    private val episode = MediaTitle(
        "e1", "jf", MediaTitle.Kind.EPISODE, "Folge 3", seriesId = "s1", seriesName = "Station Eisvogel", seasonId = "se2",
        season = 2, episode = 3, overview = "Am Hafen …", played = true, unplayed = 4, childCount = 2,
    )
    private val fromShare = MediaTitle("n1", "nas", MediaTitle.Kind.MOVIE, "Kupferstadt", posterId = "Kupferstadt/poster.jpg", fromShare = true)

    private val boxSet = MediaTitle("b1", "jf", MediaTitle.Kind.COLLECTION, "Sternwarte-Reihe", childCount = 3, posterId = "b1", posterTag = "tb")

    private val rows = MediaRows(
        resume = listOf(film, episode),
        nextUp = listOf(episode.copy(id = "e2", episode = 4, played = false)),
        nextUpTotal = 3,
        latestMovies = listOf(film),
        latestEpisodes = listOf(episode.copy(id = "s1", kind = MediaTitle.Kind.SERIES, newCount = 3, childCount = null)),
        movies = listOf(film),
        moviesTotal = 412,
        moviesNext = 40,
        series = listOf(episode.copy(id = "s1", kind = MediaTitle.Kind.SERIES)),
        seriesTotal = 87,
        seriesNext = 40,
        favorites = listOf(film.copy(favorite = true)),
        favoritesTotal = 5,
        collections = listOf(boxSet),
        collectionsTotal = 1,
        genres = listOf("Drama", "Krimi"),
    )

    private fun cache(dir: File?) = MediaRowsCache(dir, now = { clock })

    @Test
    fun `the rows round trip as JSON, every field the overview shows`() {
        val entry = MediaRowsCache.Entry(rows, clock)
        assertEquals(entry, MediaRowsCache.decode(MediaRowsCache.encode(entry)))
        val shared = MediaRowsCache.Entry(MediaRows(latestMovies = listOf(fromShare)), clock)
        assertEquals(shared, MediaRowsCache.decode(MediaRowsCache.encode(shared)))
    }

    @Test
    fun `broken or other versions are no entry`() {
        assertNull(MediaRowsCache.decode("kein json"))
        assertNull(MediaRowsCache.decode("""{"v":99,"at":1}"""))
        assertNull(MediaRowsCache.decode("""{"v":2}"""))
        // the rows of the first version (before „Ergänzt“) are loaded anew
        assertNull(MediaRowsCache.decode("""{"v":1,"at":5,"resume":[]}"""))
        // a broken title is left out, the others stay
        val e = MediaRowsCache.decode("""{"v":2,"at":5,"resume":[{"id":"x"},{"id":"m1","source":"jf","kind":"MOVIE","name":"Kobaltnacht"}]}""")
        assertEquals(listOf("m1"), e!!.rows.resume.map { it.id })
    }

    @Test
    fun `kept per source and person, the token is not part of the key`() {
        val c = cache(null)
        assertEquals(c.key(jellyfin), c.key(jellyfin.copy(token = "geheim-2")), "signed in again: the same rows")
        assertNotEquals(c.key(jellyfin), c.key(jellyfin.copy(userId = "u2")), "another person")
        assertNotEquals(c.key(jellyfin), c.key(jellyfin.copy(serverId = "srv-2")), "another server")
        assertNotEquals(c.key(jellyfin), c.key(share))
        assertFalse(c.key(jellyfin).contains("geheim"))
    }

    @Test
    fun `fresh for a minute, kept for a week`() {
        val c = cache(null)
        assertNull(c.get(jellyfin))
        assertFalse(c.fresh(jellyfin))
        c.put(jellyfin, rows)
        assertTrue(c.fresh(jellyfin))
        // the overview shows "Filme" too: kept with the other rows
        assertEquals(rows, c.get(jellyfin)!!.rows)
        clock += MediaRowsCache.FRESH_MS
        assertFalse(c.fresh(jellyfin))
        assertNotNull(c.get(jellyfin), "older: still shown, asked again")
        clock += MediaRowsCache.MAX_AGE_MS
        assertNull(c.get(jellyfin), "a week old: not shown")
        assertNull(c.peek(jellyfin))
    }

    @Test
    fun `a heart set on a detail page - asked again at the next opening, still shown`() {
        val c = cache(null)
        c.put(jellyfin, rows)
        assertTrue(c.fresh(jellyfin))
        c.markStale(jellyfin)
        assertFalse(c.fresh(jellyfin))
        assertEquals(rows, c.peek(jellyfin)!!.rows)
        c.markStale(share)
        assertNull(c.peek(share))
    }

    @Test
    fun `on the disk across a restart, no secret in the file`(@TempDir dir: File) = runBlocking {
        cache(dir).put(jellyfin, rows)
        cache(dir).put(share, MediaRows(latestMovies = listOf(fromShare)))
        val text = dir.listFiles()!!.joinToString("") { it.readText() }
        assertFalse(text.contains("geheim") || text.contains("\"pw\"") || text.contains("192.168.1.20"), "titles only, never an access or address")

        val again = cache(dir)
        assertNull(again.peek(jellyfin), "not in memory yet after a restart")
        assertFalse(again.fresh(jellyfin))
        assertEquals(rows.resume, again.load(jellyfin)!!.rows.resume)
        assertEquals(listOf(fromShare), again.load(share)!!.rows.latestMovies)
        assertNotNull(again.peek(jellyfin), "read once, then from memory")
    }

    @Test
    fun `a source removed takes its rows along, also from the disk`(@TempDir dir: File) {
        val c = cache(dir)
        c.put(jellyfin, rows)
        c.put(share, MediaRows(latestMovies = listOf(fromShare)))
        c.keepOnly(listOf(share))
        assertNull(c.get(jellyfin))
        assertNull(cache(dir).get(jellyfin))
        assertNotNull(cache(dir).get(share))
        c.clear()
        assertNull(cache(dir).get(share))
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun `a broken file is no entry`(@TempDir dir: File) {
        val c = cache(dir)
        c.put(jellyfin, rows)
        dir.listFiles()!!.single().writeText("{kaputt")
        assertNull(cache(dir).get(jellyfin))
    }
}
