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
        fingerprint: String = "fp-abc123"
    ): OkHttpClient {
        val interceptor = AuthInterceptor(
            tokenProvider = { token },
            versionProvider = { version },
            platformProvider = { platform },
            fingerprintProvider = { fingerprint }
        )
        return OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
    }

    @Test
    fun `adds all required headers when token and fingerprint are non-empty`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val httpClient = buildClient(
            token = "my-secret-token",
            version = "2.3.1",
            platform = "android",
            fingerprint = "device-fp-xyz"
        )

        val request = Request.Builder()
            .url(server.url("/test"))
            .build()

        httpClient.newCall(request).execute().use { }

        val recorded = server.takeRequest()
        assertEquals("my-secret-token", recorded.getHeader("X-API-Token"))
        assertEquals("2.3.1", recorded.getHeader("X-Client-Version"))
        assertEquals("android", recorded.getHeader("X-Client-Platform"))
        assertEquals("device-fp-xyz", recorded.getHeader("X-Machine-Fingerprint"))
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
    fun `skips fingerprint header when fingerprint is empty`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val httpClient = buildClient(fingerprint = "")

        val request = Request.Builder()
            .url(server.url("/test"))
            .build()

        httpClient.newCall(request).execute().use { }

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("X-Machine-Fingerprint"))
        assertEquals("test-token", recorded.getHeader("X-API-Token"))
    }
}
