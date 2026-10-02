package com.gatecontrol.android.network

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.zip.GZIPInputStream

class SupportBundleUploaderTest {

    private lateinit var server: MockWebServer
    private lateinit var uploader: SupportBundleUploader

    private val token = "gc_" + "f00dfeed".repeat(6)
    private val wgKey = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiClient::class.java)
        val provider = mockk<ApiClientProvider> { every { getClient(any()) } returns api }
        uploader = SupportBundleUploader(provider)
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun bundle() = mapOf(
        "schema" to 1,
        "client" to mapOf("product" to "android", "version" to "1.5.0"),
        "settings" to mapOf("serverUrl" to "https://gate.example.com", "apiToken" to token, "theme" to "dark"),
        "wireguardConfig" to "[Interface]\nPrivateKey = $wgKey\nAddress = 10.8.0.9/32",
        "logs" to mapOf("lines" to listOf("I/Api: X-API-Token: $token", "W/Tunnel: handshake 200 s")),
    )

    @Test
    fun `uploads gzip JSON with peerId, redacted`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"ok":true,"bundle":{"id":42,"created_at":"2026-10-02 10:00:00","size_bytes":123}}"""))
        val res = uploader.upload(server.url("/").toString(), 7, bundle())
        assertTrue(res.ok)
        assertEquals(42L, res.bundle?.id)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/client/support-bundle?peerId=7", req.path)
        assertEquals("application/gzip", req.getHeader("Content-Type"))
        val json = GZIPInputStream(req.body.inputStream()).bufferedReader().readText()
        assertFalse(json.contains(token), json)
        assertFalse(json.contains(wgKey), json)
        assertTrue(json.contains("\"apiToken\":\"[REDACTED]\""), json)
        assertTrue(json.contains("PrivateKey = [REDACTED]"), json)
        assertTrue(json.contains("Address = 10.8.0.9/32"), json)
        assertTrue(json.contains("\"schema\":1"), json)
    }

    @Test
    fun `server errors surface as HttpException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"ok":false,"error":"rate_limited"}"""))
        val err = runCatching { uploader.upload(server.url("/").toString(), 7, bundle()) }.exceptionOrNull()
        assertTrue(err is retrofit2.HttpException)
        assertEquals(429, (err as retrofit2.HttpException).code())
    }

    @Test
    fun `heartbeat response carries the support request`() {
        val gson = com.google.gson.Gson()
        val r = gson.fromJson("""{"ok":true,"peerEnabled":true,"supportBundleRequested":true,"supportBundleRequestedAt":"2026-10-02 10:00:00"}""", HeartbeatResponse::class.java)
        assertEquals(true, r.supportBundleRequested)
        assertEquals("2026-10-02 10:00:00", r.supportBundleRequestedAt)
        val old = gson.fromJson("""{"ok":true}""", HeartbeatResponse::class.java)
        assertEquals(null, old.supportBundleRequested)
    }
}
