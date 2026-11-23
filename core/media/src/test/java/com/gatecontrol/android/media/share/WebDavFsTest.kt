package com.gatecontrol.android.media.share

import com.gatecontrol.android.media.MediaResult
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class WebDavFsTest {

    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder().followRedirects(false).build()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun base() = server.url("/filme").toString().trimEnd('/')

    private fun multistatus(vararg responses: String) = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">${responses.joinToString("")}</D:multistatus>"""

    private fun entry(href: String, dir: Boolean, size: Long = 0, modified: String? = null) = """
<D:response><D:href>$href</D:href><D:propstat><D:prop>
<D:resourcetype>${if (dir) "<D:collection/>" else ""}</D:resourcetype>
${if (!dir) "<D:getcontentlength>$size</D:getcontentlength>" else ""}
${modified?.let { "<D:getlastmodified>$it</D:getlastmodified>" } ?: ""}
</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
<D:propstat><D:prop><D:displayname/></D:prop><D:status>HTTP/1.1 404 Not Found</D:status></D:propstat></D:response>"""

    @Test
    fun `a folder by PROPFIND - the folder itself left out, names decoded`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                multistatus(
                    entry("/filme/Neu/", dir = true),
                    entry("/filme/Neu/Kobaltnacht%20(2025).mkv", dir = false, size = 4_000_000_000, modified = "Mon, 23 Nov 2026 20:15:00 GMT"),
                    entry("${server.url("/filme/Neu/Serien/")}", dir = true),
                    entry("/filme/Neu/.DS_Store", dir = false, size = 6),
                    // not in this folder: left out
                    entry("/anderes/geheim.mkv", dir = false, size = 1),
                ),
            ),
        )
        val fs = WebDavFs(base(), http, null, null)
        val list = fs.list("Neu").valueOrNull()!!
        assertEquals(listOf("Kobaltnacht (2025).mkv", "Serien"), list.map { it.name })
        assertEquals(ShareEntry("Kobaltnacht (2025).mkv", "Neu/Kobaltnacht (2025).mkv", false, 4_000_000_000, 1_795_464_900_000), list[0])
        assertTrue(list[1].directory)
        val req = server.takeRequest()
        assertEquals("PROPFIND", req.method)
        assertEquals("/filme/Neu/", req.requestUrl!!.encodedPath)
        assertEquals("1", req.getHeader("Depth"))
    }

    @Test
    fun `refused, missing, not reachable, no DTD`() = runTest {
        val fs = WebDavFs(base(), http, "kino", "pw")
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(MediaResult.Unauthorized, fs.list(""))
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(MediaResult.NotFound, fs.list("weg"))
        // an entity of the network is never read
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """<?xml version="1.0"?><!DOCTYPE m [<!ENTITY x SYSTEM "file:///etc/passwd">]><D:multistatus xmlns:D="DAV:"><D:response><D:href>/filme/&x;</D:href></D:response></D:multistatus>""",
            ),
        )
        assertTrue(fs.list("") is MediaResult.Failed)
        assertEquals(MediaResult.NotFound, fs.list("../etc"))
        server.shutdown()
        assertEquals(MediaResult.Offline, WebDavFs(base(), http, null, null).list(""))
    }

    @Test
    fun `Basic - after the first 401 at once on every request`() = runTest {
        val fs = WebDavFs(base(), http, "kino", "geheim")
        server.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Basic realm=\"NAS\""))
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus(entry("/filme/", dir = true))))
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus(entry("/filme/", dir = true))))
        assertEquals(emptyList<ShareEntry>(), fs.list("").valueOrNull())
        assertNull(server.takeRequest().getHeader("Authorization"))
        val basic = "Basic " + java.util.Base64.getEncoder().encodeToString("kino:geheim".toByteArray())
        assertEquals(basic, server.takeRequest().getHeader("Authorization"))
        fs.list("")
        assertEquals(basic, server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `Digest - the answer of RFC 7616, a wrong password stays 401`() = runTest {
        val fs = WebDavFs(base(), http, "kino", "geheim")
        val challenge = "Digest realm=\"NAS\", qop=\"auth\", nonce=\"abc123\", opaque=\"op\", algorithm=MD5"
        server.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", challenge))
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus(entry("/filme/", dir = true))))
        assertTrue(fs.list("") is MediaResult.Ok)
        server.takeRequest()
        val auth = server.takeRequest().getHeader("Authorization")!!
        assertTrue(auth.startsWith("Digest "))
        val p = WebDavAuth.parse(listOf(auth)).single().params
        fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        val expected = md5("${md5("kino:NAS:geheim")}:abc123:${p["nc"]}:${p["cnonce"]}:auth:${md5("PROPFIND:/filme/")}")
        assertEquals(expected, p["response"])
        assertEquals("/filme/", p["uri"])
        assertEquals("op", p["opaque"])
        assertEquals("00000001", p["nc"])

        // the same challenge again after the answer: the password is wrong
        server.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", challenge))
        assertEquals(MediaResult.Unauthorized, fs.list(""))
    }

    @Test
    fun `the access never goes to another server`() {
        val other = MockWebServer()
        other.start()
        try {
            val auth = WebDavAuth(server.url("/filme"), "kino", "geheim")
            val client = OkHttpClient.Builder().addInterceptor(auth).build()
            other.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Basic realm=\"x\""))
            client.newCall(okhttp3.Request.Builder().url(other.url("/")).build()).execute().close()
            assertEquals(1, other.requestCount)
            assertNull(other.takeRequest().getHeader("Authorization"))
        } finally {
            other.shutdown()
        }
        // Basic over plain HTTP only to a host of the home network
        val away = WebDavAuth(okhttp3.HttpUrl.Builder().scheme("http").host("dav.example.test").build(), "kino", "geheim")
        assertNull(away.choose(listOf(AuthChallenge("basic", mapOf("realm" to "x")))))
        assertEquals("digest", away.choose(WebDavAuth.parse(listOf("Basic realm=\"x\", Digest realm=\"y\", nonce=\"n\", algorithm=SHA-256")))?.scheme)
    }

    @Test
    fun `a file from a place by Range, the whole length from Content-Range`() {
        val fs = WebDavFs(base(), http, null, null)
        server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 500-999/1000").setBody("x".repeat(500)))
        val input = fs.open("Neu/Kobaltnacht (2025).mkv", 500)
        assertEquals(1000, input.length)
        assertEquals(500, input.stream.readBytes().size)
        input.close()
        val req = server.takeRequest()
        assertEquals("bytes=500-", req.getHeader("Range"))
        assertEquals("/filme/Neu/Kobaltnacht%20(2025).mkv", req.requestUrl!!.encodedPath)
        // a server without ranges: skipped to the place
        server.enqueue(MockResponse().setResponseCode(200).setBody("0123456789"))
        val all = fs.open("a.mkv", 4)
        assertEquals("456789", all.stream.readBytes().decodeToString())
        server.enqueue(MockResponse().setResponseCode(403))
        assertTrue(runCatching { fs.open("a.mkv", 0) }.isFailure)
    }

    @Test
    fun `challenges as servers write them`() {
        val list = WebDavAuth.parse(listOf("Digest realm=\"a, b\", nonce=\"n\\\"1\", qop=\"auth,auth-int\", Basic realm=\"c\"", "Negotiate"))
        assertEquals(listOf("digest", "basic", "negotiate"), list.map { it.scheme })
        assertEquals("a, b", list[0].params["realm"])
        assertEquals("n\"1", list[0].params["nonce"])
        assertEquals("auth,auth-int", list[0].params["qop"])
    }
}
