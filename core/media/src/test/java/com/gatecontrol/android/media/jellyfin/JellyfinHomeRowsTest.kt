package com.gatecontrol.android.media.jellyfin

import com.gatecontrol.android.media.MediaClientInfo
import com.gatecontrol.android.media.MediaOrder
import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaTitle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The rows of the overview „Ergänzt“ (decisions 72–76) against a fake Jellyfin with the server's own answers
 * (fixtures in `resources/jellyfin`): Als Nächstes, Neue Filme, Neue Folgen grouped per series, Favoriten (and the
 * heart), Sammlungen with their films, Genres.
 */
class JellyfinHomeRowsTest {

    private lateinit var server: MockWebServer
    private lateinit var library: JellyfinLibrary

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        library = JellyfinLibrary(
            MediaSource("jf", MediaSourceKind.JELLYFIN, "NAS", server.url("/").toString().trimEnd('/'), userId = "u1", token = "tok-secret"),
            OkHttpClient(),
            MediaClientInfo("GateControl", "Pixel", "dev-1", "1.0"),
        )
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/jellyfin/$name")) { "fixture $name" }.readText()

    private fun answer(name: String) =
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(fixture(name)))

    @Test
    fun `Als Naechstes - Shows NextUp without the episodes begun and without seeing a series again`() = runTest {
        answer("next-up.json")
        val page = (library.nextUp(16) as MediaResult.Ok).value
        assertEquals(listOf("e16", "e41", "e24"), page.items.map { it.id })
        assertEquals(5, page.total)
        val first = page.items.first()
        assertEquals(MediaTitle.Kind.EPISODE, first.kind)
        assertEquals("Ankerplatz 12", first.seriesName)
        assertEquals(1 to 6, first.season to first.episode)
        assertEquals(2_640_000L, first.runtimeMs)
        // the wide card shows the series' backdrop
        assertEquals("s12" to "sb12", first.backdropId to first.backdropTag)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/Shows/NextUp", url.encodedPath)
        assertEquals("u1", url.queryParameter("userId"))
        assertEquals("false", url.queryParameter("enableResumable"))
        assertEquals("false", url.queryParameter("enableRewatching"))
        assertEquals("true", url.queryParameter("enableTotalRecordCount"))
        assertFalse(url.toString().contains("tok-secret"), "the access goes in the header")
    }

    @Test
    fun `Neue Filme - only films, newest first as the server sends them`() = runTest {
        answer("latest-movies.json")
        val films = (library.latestMovies(16) as MediaResult.Ok).value
        assertEquals(listOf("Sternwarte 9", "Kupferstadt", "Moorlicht"), films.map { it.name })
        assertTrue(films.first().favorite)
        assertTrue(films.last().played)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/Items/Latest", url.encodedPath)
        assertEquals("Movie", url.queryParameter("includeItemTypes"))
        assertEquals("16", url.queryParameter("limit"))
    }

    @Test
    fun `Neue Folgen - grouped per series with the number of new ones, a single one as the episode with its series' poster`() = runTest {
        answer("latest-episodes.json")
        val rows = (library.latestEpisodes(16) as MediaResult.Ok).value
        assertEquals(listOf("s3", "e57", "s7"), rows.map { it.id })
        val graufeld = rows[0]
        assertEquals(MediaTitle.Kind.SERIES, graufeld.kind)
        assertEquals(3, graufeld.newCount, "\"3 neu\"")
        assertNull(graufeld.childCount, "not its seasons")
        val single = rows[1]
        assertEquals(MediaTitle.Kind.EPISODE, single.kind)
        assertNull(single.newCount)
        assertEquals("s9" to "sp9", single.posterId to single.posterTag, "the series' poster, not the still")
        assertEquals(1, rows[2].newCount)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/Items/Latest", url.encodedPath)
        assertEquals("Episode", url.queryParameter("includeItemTypes"))
        assertEquals("true", url.queryParameter("groupItems"))
        assertEquals(5, com.gatecontrol.android.media.MediaLogic.newEpisodes(rows), "3 + 1 + 1 episodes")
    }

    @Test
    fun `Favoriten - films, series and episodes of the person, with their number`() = runTest {
        answer("favorites.json")
        val page = (library.favorites(20) as MediaResult.Ok).value
        assertEquals(listOf("s12", "m9", "e57"), page.items.map { it.id })
        assertTrue(page.items.all { it.favorite })
        assertEquals(7, page.total)
        assertEquals("s9", page.items[2].posterId)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/Items", url.encodedPath)
        assertEquals("IsFavorite", url.queryParameter("filters"))
        assertEquals("Movie,Series,Episode", url.queryParameter("includeItemTypes"))
        assertEquals("true", url.queryParameter("recursive"))
    }

    @Test
    fun `the heart - set and taken back in Jellyfin`() = runTest {
        server.enqueue(MockResponse().setBody("""{"IsFavorite":true}"""))
        assertEquals(MediaResult.Ok(Unit), library.setFavorite("m9", true))
        val set = server.takeRequest()
        assertEquals("POST", set.method)
        assertEquals("/UserFavoriteItems/m9", set.requestUrl!!.encodedPath)
        assertEquals("u1", set.requestUrl!!.queryParameter("userId"))
        server.enqueue(MockResponse().setBody("""{"IsFavorite":false}"""))
        assertEquals(MediaResult.Ok(Unit), library.setFavorite("m9", false))
        assertEquals("DELETE", server.takeRequest().method)
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(MediaResult.Unauthorized, library.setFavorite("m9", true))
    }

    @Test
    fun `Sammlungen - box sets with how many films, then a set's films in their order`() = runTest {
        answer("boxsets.json")
        val sets = (library.collections(20) as MediaResult.Ok).value
        assertEquals(listOf("Sternwarte-Reihe", "Kupferstadt-Trilogie"), sets.items.map { it.name })
        assertTrue(sets.items.all { it.kind == MediaTitle.Kind.COLLECTION && !it.playable })
        assertEquals(3, sets.items.first().childCount)
        assertEquals(2, sets.total)
        val url = server.takeRequest().requestUrl!!
        assertEquals("BoxSet", url.queryParameter("includeItemTypes"))
        assertEquals("true", url.queryParameter("recursive"))

        answer("boxset-items.json")
        val films = (library.collection("b1") as MediaResult.Ok).value
        assertEquals(listOf("m7", "m8", "m9"), films.map { it.id })
        val inner = server.takeRequest().requestUrl!!
        assertEquals("b1", inner.queryParameter("parentId"))
        assertEquals("ProductionYear,SortName", inner.queryParameter("sortBy"))
    }

    @Test
    fun `Genres - the films' genres once each`() = runTest {
        answer("genres.json")
        assertEquals(listOf("Doku", "Krimi", "Science-Fiction"), (library.genres(true) as MediaResult.Ok).value)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/Genres", url.encodedPath)
        assertEquals("Movie", url.queryParameter("includeItemTypes"))
    }

    @Test
    fun `the lists behind the rows - favourites only, series by their newest episode`() = runTest {
        answer("favorites.json")
        library.list(movies = true, start = 0, limit = 40, order = MediaOrder(favorite = true, unplayed = true))
        assertEquals("IsUnplayed,IsFavorite", server.takeRequest().requestUrl!!.queryParameter("filters"))
        answer("favorites.json")
        library.list(movies = false, start = 0, limit = 40, order = MediaOrder(sort = MediaOrder.Sort.ADDED))
        assertEquals("DateLastContentAdded,SortName", server.takeRequest().requestUrl!!.queryParameter("sortBy"))
        answer("favorites.json")
        library.list(movies = true, start = 0, limit = 40, order = MediaOrder(sort = MediaOrder.Sort.ADDED))
        assertEquals("DateCreated,SortName", server.takeRequest().requestUrl!!.queryParameter("sortBy"))
    }

    @Test
    fun `an older server without NextUp - the row stays empty, nothing else fails`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(MediaResult.NotFound, library.nextUp(16))
    }
}
