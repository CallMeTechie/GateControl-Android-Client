package com.gatecontrol.android.network

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class ApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var apiClient: ApiClient

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
    fun `ping returns server version`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":true,"version":"1.2.3","timestamp":"2026-04-05T10:00:00Z"}""")
        )

        val response = apiClient.ping()

        val recorded = server.takeRequest()
        assertEquals("/api/v1/client/ping", recorded.path)
        assertTrue(response.ok)
        assertEquals("1.2.3", response.version)
    }

    @Test
    fun `permissions returns feature flags`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"ok":true,"permissions":{"services":true,"traffic":true,"dns":false,"rdp":true},"scopes":["read","write"]}"""
                )
        )

        val response = apiClient.getPermissions()

        val recorded = server.takeRequest()
        assertEquals("/api/v1/client/permissions", recorded.path)
        assertTrue(response.ok)
        assertTrue(response.permissions.services)
        assertFalse(response.permissions.dns)
        assertTrue(response.permissions.rdp)
        assertEquals(listOf("read", "write"), response.scopes)
    }

    @Test
    fun `register sends hostname and platform via POST`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":true,"peerId":99,"peerName":"dev-phone","config":null,"hash":null}""")
        )

        val response = apiClient.register(
            RegisterRequest(hostname = "my-device", platform = "android", clientVersion = "1.0.0")
        )

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/client/register", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("my-device"), "Body should contain hostname")
        assertTrue(body.contains("android"), "Body should contain platform")
        assertTrue(response.ok)
        assertEquals(99, response.peerId)
    }

    @Test
    fun `getConfig passes peerId as query parameter`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":true,"config":"[Interface]\nPrivateKey=abc","hash":"sha256abc","peerName":"peer42"}""")
        )

        val response = apiClient.getConfig(peerId = 42)

        val recorded = server.takeRequest()
        assertTrue(
            recorded.path?.contains("peerId=42") == true,
            "Path should contain peerId=42, got: ${recorded.path}"
        )
        assertTrue(response.ok)
        assertNotNull(response.config)
    }

    @Test
    fun `checkConfigUpdate passes hash query parameter`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":true,"updated":false,"config":null,"hash":"sha256abc"}""")
        )

        apiClient.checkConfigUpdate(peerId = 10, hash = "sha256abc")

        val recorded = server.takeRequest()
        val path = recorded.path ?: ""
        assertTrue(path.contains("peerId=10"), "Path should contain peerId=10")
        assertTrue(path.contains("hash=sha256abc"), "Path should contain hash=sha256abc")
    }

    @Test
    fun `getTraffic returns period stats`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"ok":true,"traffic":{"total":{"rx":1000000,"tx":500000},"last24h":{"rx":100000,"tx":50000},"last7d":{"rx":700000,"tx":350000},"last30d":{"rx":1000000,"tx":500000}}}"""
                )
        )

        val response = apiClient.getTraffic(peerId = 5)

        val recorded = server.takeRequest()
        assertTrue(recorded.path?.contains("peerId=5") == true)
        assertTrue(response.ok)
        assertEquals(1000000L, response.traffic.total.rx)
        assertEquals(500000L, response.traffic.total.tx)
        assertEquals(100000L, response.traffic.last24h.rx)
    }

    @Test
    fun `getRdpRoutes returns route list`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"ok":true,"routes":[{"id":1,"name":"Office PC","host":"192.168.1.10","port":3389,"access_mode":"direct","credential_mode":"stored","status":{"online":true,"lastCheck":null}}]}"""
                )
        )

        val response = apiClient.getRdpRoutes()

        val recorded = server.takeRequest()
        assertEquals("/api/v1/client/rdp", recorded.path)
        assertTrue(response.ok)
        assertEquals(1, response.routes.size)
        assertEquals("Office PC", response.routes[0].name)
        assertEquals("192.168.1.10", response.routes[0].host)
        assertTrue(response.routes[0].status?.online == true)
    }

    @Test
    fun `checkUpdate returns available flag`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"ok":true,"available":true,"version":"2.0.0","downloadUrl":"https://example.com/app.apk","fileName":"app.apk","fileSize":12345678,"releaseNotes":"Bug fixes"}"""
                )
        )

        val response = apiClient.checkUpdate(version = "1.5.0")

        val recorded = server.takeRequest()
        val path = recorded.path ?: ""
        assertTrue(path.contains("version=1.5.0"), "Path should contain version=1.5.0")
        assertTrue(path.contains("platform=android"), "Path should contain platform=android")
        assertTrue(path.contains("client=gatecontrol"), "Path should contain client=gatecontrol")
        assertTrue(response.ok)
        assertTrue(response.available)
        assertEquals("2.0.0", response.version)
    }
}
