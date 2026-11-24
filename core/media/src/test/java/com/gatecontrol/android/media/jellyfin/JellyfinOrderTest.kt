package com.gatecontrol.android.media.jellyfin

import com.gatecontrol.android.media.MediaClientInfo
import com.gatecontrol.android.media.MediaOrder
import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The phone's grid and search against a fake Jellyfin: the server sorts, filters, pages and searches. */
class JellyfinOrderTest {

    private lateinit var server: MockWebServer
    private lateinit var library: JellyfinLibrary

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        library = JellyfinLibrary(
            MediaSource("s1", MediaSourceKind.JELLYFIN, "DS918+", server.url("/").toString().trimEnd('/'), userId = "u1", token = "tok"),
            OkHttpClient(),
            MediaClientInfo("GateControl", "Pixel", "dev-1", "1.150.0"),
        )
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `newest first, only unwatched, one genre, page by page`() = runTest {
        server.enqueue(json("""{"Items":[{"Id":"m1","Name":"Sternwarte 9","Type":"Movie"}],"TotalRecordCount":41}"""))
        val page = library.list(movies = true, start = 40, limit = 40, order = MediaOrder(MediaOrder.Sort.ADDED, unplayed = true, genre = "Drama")).valueOrNull()!!
        assertEquals(listOf("m1"), page.items.map { it.id })
        assertNull(page.next)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/Items", url.encodedPath)
        assertEquals("Movie", url.queryParameter("includeItemTypes"))
        assertEquals("DateCreated,SortName", url.queryParameter("sortBy"))
        assertEquals("Descending,Ascending", url.queryParameter("sortOrder"))
        assertEquals("IsUnplayed", url.queryParameter("filters"))
        assertEquals("Drama", url.queryParameter("genres"))
        assertEquals("40", url.queryParameter("startIndex"))
    }

    @Test
    fun `A-Z without filters as before`() = runTest {
        server.enqueue(json("""{"Items":[],"TotalRecordCount":0}"""))
        library.list(movies = false, start = 0, limit = 40, order = MediaOrder(MediaOrder.Sort.YEAR))
        val url = server.takeRequest().requestUrl!!
        assertEquals("Series", url.queryParameter("includeItemTypes"))
        assertEquals("ProductionYear,SortName", url.queryParameter("sortBy"))
        assertNull(url.queryParameter("filters"))
        assertNull(url.queryParameter("genres"))
    }

    @Test
    fun `the server's search over films and series`() = runTest {
        server.enqueue(json("""{"Items":[{"Id":"m1","Name":"Kobaltnacht","Type":"Movie"},{"Id":"s9","Name":"Kobaltküste","Type":"Series"}]}"""))
        val found = (library.search(" kobalt ", 20) as MediaResult.Ok).value
        assertEquals(listOf("m1", "s9"), found.map { it.id })
        val url = server.takeRequest().requestUrl!!
        assertEquals("kobalt", url.queryParameter("searchTerm"))
        assertEquals("Movie,Series", url.queryParameter("includeItemTypes"))
        assertEquals("20", url.queryParameter("limit"))
        // blank: nothing asked
        assertEquals(emptyList<String>(), library.search("  ", 20).valueOrNull())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the genres of the films`() = runTest {
        server.enqueue(json("""{"Items":[{"Name":"Drama","Id":"g1"},{"Name":" Komödie "},{"Name":"drama"},{"Id":"x"}]}"""))
        assertEquals(listOf("Drama", "Komödie"), library.genres(movies = true).valueOrNull())
        val url = server.takeRequest().requestUrl!!
        assertEquals("/Genres", url.encodedPath)
        assertEquals("Movie", url.queryParameter("includeItemTypes"))
    }
}
