package com.gatecontrol.android.network

import kotlinx.coroutines.runBlocking
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
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

// runBlocking, not runTest: the timeout must run on real time, as the
// MockWebServer answers on real time.
class ServiceLinkTest {

    private lateinit var server: MockWebServer
    private lateinit var apiClient: ApiClient
    private val nas = VpnService(id = 7, name = "NAS", domain = "nas.example.com", url = "https://nas.example.com", hasAuth = true)

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        apiClient = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiClient::class.java)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `returns the one-time link for the service`() = runBlocking {
        val link = "https://nas.example.com/route-auth/app/" + "a".repeat(43)
        server.enqueue(MockResponse().setBody("""{"ok":true,"url":"$link","autoLogin":true,"expiresIn":60}"""))

        assertEquals(link, apiClient.getServiceLink(nas))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/client/services/7/open", request.path)
    }

    @Test
    fun `older server without the endpoint falls back`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"ok":false}"""))
        assertNull(apiClient.getServiceLink(nas))
    }

    @Test
    fun `a link to another host is ignored`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"url":"https://evil.example.org/x","autoLogin":true}"""))
        assertNull(apiClient.getServiceLink(nas))
    }

    @Test
    fun `no answer in time falls back`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"url":"https://nas.example.com"}""").setBodyDelay(2, TimeUnit.SECONDS))
        assertNull(apiClient.getServiceLink(nas, timeoutMs = 200))
    }

    @Test
    fun `host check needs https on the exact host`() {
        assertTrue(isServiceHost("https://NAS.example.com/route-auth/app/x", "nas.example.com"))
        assertFalse(isServiceHost("http://nas.example.com/", "nas.example.com"))
        assertFalse(isServiceHost("https://nas.example.com.evil.org/", "nas.example.com"))
        assertFalse(isServiceHost("not a url", "nas.example.com"))
    }

    @Test
    fun `toString never shows the link`() {
        val r = ServiceOpenResponse(ok = true, url = "https://nas.example.com/route-auth/app/secret", autoLogin = true)
        assertFalse(r.toString().contains("secret"))
    }
}
