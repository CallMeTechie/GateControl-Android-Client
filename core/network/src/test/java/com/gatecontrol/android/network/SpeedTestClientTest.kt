package com.gatecontrol.android.network

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class SpeedTestClientTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
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

    /** A clock that moves [stepMs] on every reading. */
    private fun steppingClock(stepMs: Long): () -> Long {
        var t = 0L
        return { t += stepMs * 1_000_000L; t }
    }

    @Test
    fun `info - limits, older server, rate limit`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true,"maxDownloadBytes":52428800,"maxUploadBytes":20971520,"viaTunnel":true}"""))
        assertEquals(SpeedTestAvailability.Available(52_428_800, 20_971_520, true), SpeedTestClient.info(api))
        assertEquals("/api/v1/client/speedtest", server.takeRequest().path)

        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"Not found"}"""))
        assertEquals(SpeedTestAvailability.Unsupported, SpeedTestClient.info(api))

        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"ok":false}"""))
        assertEquals(SpeedTestAvailability.RateLimited, SpeedTestClient.info(api))

        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(500, assertThrows<SpeedTestHttpException> { SpeedTestClient.info(api) }.code)
    }

    @Test
    fun `download - streams the requested bytes`() = runTest {
        val size = 1L shl 20
        server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody(Buffer().write(ByteArray(size.toInt()))))
        val t = SpeedTestClient.download(api, size, maxMillis = 8_000)
        assertEquals(size, t.bytes)
        assertTrue(t.millis >= 1)
        assertEquals("/api/v1/client/speedtest/download?bytes=$size", server.takeRequest().path)
    }

    @Test
    fun `download - stops at the time cap with what arrived`() = runTest {
        val size = 4L shl 20
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(size.toInt()))))
        val t = SpeedTestClient.download(api, size, maxMillis = 2_500, nanoTime = steppingClock(1_000))
        assertTrue(t.bytes in 1 until size, "bytes ${t.bytes}")
        assertTrue(t.millis >= 2_500)
    }

    @Test
    fun `download - 429 is reported with its code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        assertEquals(429, assertThrows<SpeedTestHttpException> { SpeedTestClient.download(api, 1000, 8_000) }.code)
    }

    @Test
    fun `upload - sends the bytes as octet-stream and reads the answer`() = runTest {
        val size = 300_000L
        server.enqueue(MockResponse().setBody("""{"ok":true,"bytes":300000,"serverMs":120}"""))
        val t = SpeedTestClient.upload(api, size, maxMillis = 8_000)
        val recorded = server.takeRequest()
        assertEquals("/api/v1/client/speedtest/upload", recorded.path)
        assertEquals("POST", recorded.method)
        assertEquals("application/octet-stream", recorded.getHeader("Content-Type"))
        assertEquals(size, recorded.bodySize)
        assertEquals(size, t.bytes)
        assertEquals(120L, t.serverMs)
        assertEquals(size, t.serverBytes)
    }

    @Test
    fun `upload - aborted at the time cap, rate from what was written`() = runTest {
        val size = 10L shl 20
        server.enqueue(MockResponse().setBody("""{"ok":true,"bytes":0}"""))
        val t = SpeedTestClient.upload(api, size, maxMillis = 2_500, nanoTime = steppingClock(1_000))
        assertTrue(t.bytes in 1 until size, "bytes ${t.bytes}")
        assertNull(t.serverMs)
    }

    @Test
    fun `upload - 429 is reported with its code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        assertEquals(429, assertThrows<SpeedTestHttpException> { SpeedTestClient.upload(api, 1000, 8_000) }.code)
    }
}
