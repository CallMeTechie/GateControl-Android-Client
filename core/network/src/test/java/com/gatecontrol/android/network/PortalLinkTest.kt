package com.gatecontrol.android.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import timber.log.Timber
import java.util.concurrent.TimeUnit

// runBlocking, not runTest: the timeout must run on real time, as the
// MockWebServer answers on real time.
class PortalLinkTest {

    private lateinit var server: MockWebServer
    private lateinit var apiClient: ApiClient
    private lateinit var portalUrl: String
    private val logLines = mutableListOf<String>()

    private val captureTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            synchronized(logLines) { logLines += message + (t?.toString() ?: "") }
        }
    }

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        portalUrl = server.url("/").toString().trimEnd('/')
        // Same log level as debug builds (headers, never bodies).
        val httpLog = HttpLoggingInterceptor { line -> synchronized(logLines) { logLines += line } }
            .apply { level = HttpLoggingInterceptor.Level.HEADERS }
        apiClient = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().addInterceptor(httpLog).build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiClient::class.java)
        Timber.plant(captureTree)
    }

    @AfterEach
    fun tearDown() {
        Timber.uproot(captureTree)
        server.shutdown()
    }

    private fun ok(url: String) = MockResponse().setResponseCode(200)
        .setBody("""{"ok":true,"url":"$url","expiresIn":60}""")

    @Test
    fun `returns the one-time link on success`() = runBlocking {
        val link = "$portalUrl/auto?t=secret-ticket-123"
        server.enqueue(ok(link))

        assertEquals(link, apiClient.getPortalLink(portalUrl))

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/client/portal-link", recorded.path)
    }

    @Test
    fun `falls back on 404`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"ok":false}"""))
        assertNull(apiClient.getPortalLink(portalUrl))
    }

    @Test
    fun `falls back on 500`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertNull(apiClient.getPortalLink(portalUrl))
    }

    @Test
    fun `falls back on timeout`() = runBlocking {
        server.enqueue(ok("$portalUrl/auto?t=late").setHeadersDelay(3, TimeUnit.SECONDS))
        val started = System.nanoTime()
        assertNull(apiClient.getPortalLink(portalUrl, timeoutMs = 300))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_500)
    }

    @Test
    fun `default timeout is five seconds`() {
        assertEquals(5_000L, PORTAL_LINK_TIMEOUT_MS)
    }

    @Test
    fun `falls back on network error`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertNull(apiClient.getPortalLink(portalUrl))
    }

    @Test
    fun `falls back when url is missing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"expiresIn":60}"""))
        assertNull(apiClient.getPortalLink(portalUrl))
    }

    @Test
    fun `falls back on malformed body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
        assertNull(apiClient.getPortalLink(portalUrl))
    }

    @Test
    fun `falls back on host mismatch`() = runBlocking {
        server.enqueue(ok("http://evil.example.com:${server.port}/auto?t=abc"))
        assertNull(apiClient.getPortalLink(portalUrl))
    }

    @Test
    fun `falls back on scheme mismatch`() = runBlocking {
        server.enqueue(ok(portalUrl.replaceFirst("http://", "https://") + "/auto?t=abc"))
        assertNull(apiClient.getPortalLink(portalUrl))
    }

    @Test
    fun `same-origin check compares scheme and host only`() {
        assertTrue(isSameOrigin("https://Portal.Example.com/auto?t=x", "https://portal.example.com"))
        assertTrue(isSameOrigin("https://portal.example.com:8443/auto?t=x", "https://portal.example.com/"))
        assertFalse(isSameOrigin("https://portal.example.com.evil.io/auto?t=x", "https://portal.example.com"))
        assertFalse(isSameOrigin("http://portal.example.com/auto?t=x", "https://portal.example.com"))
        assertFalse(isSameOrigin("javascript:alert(1)", "https://portal.example.com"))
        assertFalse(isSameOrigin("https://portal.example.com/auto", "not a url"))
    }

    @Test
    fun `the link and its ticket are never logged`() = runBlocking {
        val ticket = "tkt-9f8e7d6c5b4a"
        server.enqueue(ok("$portalUrl/auto?t=$ticket"))
        assertEquals("$portalUrl/auto?t=$ticket", apiClient.getPortalLink(portalUrl))
        server.enqueue(ok("https://evil.example.com/auto?t=$ticket"))
        assertNull(apiClient.getPortalLink(portalUrl))

        val logs = synchronized(logLines) { logLines.joinToString("\n") }
        assertTrue(logs.contains("portal-link"), "HTTP log should have been captured")
        assertFalse(logs.contains(ticket), "ticket leaked into logs:\n$logs")
        assertFalse(logs.contains("auto?t="), "portal link leaked into logs:\n$logs")
    }

    @Test
    fun `response toString hides the link`() {
        val text = PortalLinkResponse(ok = true, url = "https://p.example.com/auto?t=abc", expiresIn = 60).toString()
        assertFalse(text.contains("abc"))
    }
}
