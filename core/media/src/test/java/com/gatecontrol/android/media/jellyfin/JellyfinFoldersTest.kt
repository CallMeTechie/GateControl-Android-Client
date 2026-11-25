package com.gatecontrol.android.media.jellyfin

import com.gatecontrol.android.media.MediaClientInfo
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The page "Ordner" against a fake Jellyfin: the person's libraries, then what a library or folder holds. */
class JellyfinFoldersTest {

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
    fun `the libraries of the person, without music, books and photos`() = runTest {
        server.enqueue(
            json(
                """{"Items":[
                {"Id":"v1","Name":"Filme","Type":"CollectionFolder","CollectionType":"movies","IsFolder":true},
                {"Id":"v2","Name":"Serien","Type":"CollectionFolder","CollectionType":"tvshows","IsFolder":true},
                {"Id":"v3","Name":"Musik","Type":"CollectionFolder","CollectionType":"music","IsFolder":true},
                {"Id":"v4","Name":"Fotos","Type":"CollectionFolder","CollectionType":"photos","IsFolder":true},
                {"Id":"v5","Name":"Heimvideos","Type":"CollectionFolder","CollectionType":"homevideos","IsFolder":true},
                {"Id":"v6","Name":"Gemischt","Type":"CollectionFolder","IsFolder":true},
                {"Name":"ohne Id"}
                ],"TotalRecordCount":7}""",
            ),
        )
        val views = (library.views() as MediaResult.Ok).value
        assertEquals(listOf("Filme", "Serien", "Heimvideos", "Gemischt"), views.map { it.name })
        val url = server.takeRequest().requestUrl!!
        assertEquals("/UserViews", url.encodedPath)
        assertEquals("u1", url.queryParameter("userId"))
    }

    @Test
    fun `no library - an empty list, not an error`() = runTest {
        server.enqueue(json("""{"Items":[],"TotalRecordCount":0}"""))
        assertEquals(emptyList<JellyfinFolder>(), library.views().valueOrNull())
    }

    @Test
    fun `a library's content - folders and titles apart, music left out`() = runTest {
        server.enqueue(
            json(
                """{"Items":[
                {"Id":"f1","Name":"Familie","Type":"Folder","IsFolder":true},
                {"Id":"b1","Name":"Sternwarte-Reihe","Type":"BoxSet","IsFolder":true},
                {"Id":"m1","Name":"Kobaltnacht","Type":"Movie","ProductionYear":2024,"IsFolder":false},
                {"Id":"s1","Name":"Die Lindqvist-Akten","Type":"Series","IsFolder":true,"ChildCount":2},
                {"Id":"a1","Name":"Ein Album","Type":"MusicAlbum","IsFolder":true},
                {"Id":"x1","Name":"Lied","Type":"Audio","IsFolder":false}
                ],"TotalRecordCount":6}""",
            ),
        )
        val page = (library.children("v1") as MediaResult.Ok).value
        assertEquals(listOf("Familie", "Sternwarte-Reihe"), page.folders.map { it.name })
        assertEquals(listOf("m1", "s1"), page.titles.map { it.id })
        assertEquals(MediaTitle.Kind.SERIES, page.titles[1].kind)
        assertEquals(6, page.total)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/Items", url.encodedPath)
        assertEquals("v1", url.queryParameter("parentId"))
        assertEquals("u1", url.queryParameter("userId"))
        assertEquals("SortName", url.queryParameter("sortBy"))
        assertTrue(url.queryParameter("fields")!!.contains("ChildCount"))
    }

    @Test
    fun `the server not reached - the error, not an empty folder`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(library.views() !is MediaResult.Ok)
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(library.children("v1") !is MediaResult.Ok)
    }
}
