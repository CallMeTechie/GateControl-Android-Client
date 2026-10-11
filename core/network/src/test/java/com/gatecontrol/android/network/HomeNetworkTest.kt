package com.gatecontrol.android.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

// runBlocking, not runTest: the timeout must run on real time.
class HomeNetworkTest {

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

    private fun answer(code: Int, body: String = "") {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    @Test
    fun `same_lan true via tunnel is home`() = runBlocking {
        answer(200, """{"ok":true,"lan":["192.168.1.10"],"same_lan":true,"via":"tunnel"}""")

        assertEquals(HomeNetworkStatus.Home(viaTunnel = true), apiClient.probeHomeNetwork())
        assertEquals("/api/v1/client/home-network", server.takeRequest().path)
    }

    @Test
    fun `same_lan false direct is not home`() = runBlocking {
        answer(200, """{"ok":true,"lan":["192.168.1.10"],"same_lan":false,"via":"direct"}""")

        assertEquals(HomeNetworkStatus.NotHome(viaTunnel = false), apiClient.probeHomeNetwork())
    }

    @Test
    fun `response model parses the contract`() = runBlocking {
        answer(200, """{"ok":true,"lan":["192.168.1.10","10.0.0.2"],"same_lan":true,"via":"direct"}""")

        val body = apiClient.getHomeNetwork().body()!!
        assertEquals(true, body.ok)
        assertEquals(listOf("192.168.1.10", "10.0.0.2"), body.lan)
        assertEquals(true, body.sameLan)
        assertEquals("direct", body.via)
    }

    @Test
    fun `404 from an older server is unsupported`() = runBlocking {
        answer(404, """{"ok":false,"error":"Not found"}""")

        assertEquals(HomeNetworkStatus.Unsupported, apiClient.probeHomeNetwork())
    }

    @Test
    fun `answer without same_lan is unsupported`() = runBlocking {
        answer(200, """{"ok":true}""")

        assertEquals(HomeNetworkStatus.Unsupported, apiClient.probeHomeNetwork())
    }

    @Test
    fun `server errors and broken answers are unknown`() = runBlocking {
        answer(500, """{"ok":false}""")
        assertEquals(HomeNetworkStatus.Unknown, apiClient.probeHomeNetwork())

        answer(401, """{"ok":false}""")
        assertEquals(HomeNetworkStatus.Unknown, apiClient.probeHomeNetwork())

        answer(200, """{"ok":false,"same_lan":true}""")
        assertEquals(HomeNetworkStatus.Unknown, apiClient.probeHomeNetwork())

        answer(200, "not json")
        assertEquals(HomeNetworkStatus.Unknown, apiClient.probeHomeNetwork())
    }

    @Test
    fun `timeout is unknown`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        assertEquals(HomeNetworkStatus.Unknown, apiClient.probeHomeNetwork(timeoutMs = 300))
    }

    @Test
    fun `classification without a server`() {
        assertEquals(HomeNetworkStatus.Unknown, classifyHomeNetwork(200, null))
        assertEquals(HomeNetworkStatus.Unsupported, classifyHomeNetwork(404, null))
        assertEquals(HomeNetworkStatus.Unknown, classifyHomeNetwork(503, HomeNetworkResponse(ok = true, sameLan = true)))
        assertEquals(
            HomeNetworkStatus.Home(viaTunnel = false),
            classifyHomeNetwork(200, HomeNetworkResponse(ok = true, sameLan = true, via = null)),
        )
    }
}
