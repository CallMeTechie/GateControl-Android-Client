package com.gatecontrol.android.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AuthInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun buildClient(
        token: String = "test-token",
        version: String = "1.0.0",
        platform: String = "android",
        storedServer: () -> String = { server.url("/").toString() },
    ): OkHttpClient {
        val interceptor = AuthInterceptor(
            tokenProvider = { token },
            serverUrlProvider = storedServer,
            versionProvider = { version },
            platformProvider = { platform },
        )
        return OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
    }

    @Test
    fun `adds all required headers when token is non-empty`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val httpClient = buildClient(
            token = "my-secret-token",
            version = "2.3.1",
            platform = "android",
        )

        val request = Request.Builder()
            .url(server.url("/test"))
            .build()

        httpClient.newCall(request).execute().use { }

        val recorded = server.takeRequest()
        assertEquals("my-secret-token", recorded.getHeader("X-API-Token"))
        assertEquals("2.3.1", recorded.getHeader("X-Client-Version"))
        assertEquals("android", recorded.getHeader("X-Client-Platform"))
    }

    @Test
    fun `explicit token of a connection test is not replaced by the stored one`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val httpClient = buildClient(token = "stored-token")

        val request = Request.Builder()
            .url(server.url("/test"))
            .header("X-API-Token", "typed-token")
            .build()

        httpClient.newCall(request).execute().use { }

        val recorded = server.takeRequest()
        assertEquals("typed-token", recorded.getHeader("X-API-Token"))
        assertEquals(1, recorded.headers.values("X-API-Token").size)
    }

    @Test
    fun `skips token header when token is empty`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val httpClient = buildClient(token = "")

        val request = Request.Builder()
            .url(server.url("/test"))
            .build()

        httpClient.newCall(request).execute().use { }

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("X-API-Token"))
        assertEquals("1.0.0", recorded.getHeader("X-Client-Version"))
        assertEquals("android", recorded.getHeader("X-Client-Platform"))
    }

    @Test
    fun `stored token is not sent to another server`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val other = MockWebServer().apply { start() }
        try {
            // Setting up a new server while the old one is still stored.
            val httpClient = buildClient(token = "old-server-token", storedServer = { other.url("/").toString() })

            httpClient.newCall(Request.Builder().url(server.url("/api/v1/client/enroll")).build()).execute().use { }

            val recorded = server.takeRequest()
            assertNull(recorded.getHeader("X-API-Token"))
            assertEquals("android", recorded.getHeader("X-Client-Platform"))
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun `stored token is not sent without a stored server`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val httpClient = buildClient(token = "orphan-token", storedServer = { "" })

        httpClient.newCall(Request.Builder().url(server.url("/test")).build()).execute().use { }

        assertNull(server.takeRequest().getHeader("X-API-Token"))
    }

    @Test
    fun `stored server matches regardless of path and host case`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val stored = server.url("/").toString().replace(server.hostName, server.hostName.uppercase()) + "gatecontrol/"

        val httpClient = buildClient(token = "stored-token", storedServer = { stored })

        httpClient.newCall(Request.Builder().url(server.url("/api/v1/ping")).build()).execute().use { }

        assertEquals("stored-token", server.takeRequest().getHeader("X-API-Token"))
    }
}
