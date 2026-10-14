package com.gatecontrol.android.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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
import java.net.InetAddress

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

    @Test
    fun `local addresses go out as one query parameter`() = runBlocking {
        answer(200, """{"ok":true,"same_lan":false,"via":"direct","site":null}""")

        apiClient.probeHomeNetwork(local = listOf("192.168.27.5", "10.0.0.3"))
        assertEquals("/api/v1/client/home-network?local=192.168.27.5,10.0.0.3", server.takeRequest().path)
    }

    @Test
    fun `invalid and public local addresses are dropped, at most four`() = runBlocking {
        answer(200, """{"ok":true,"same_lan":false}""")
        answer(200, """{"ok":true,"same_lan":false}""")

        apiClient.probeHomeNetwork(local = listOf("8.8.8.8", "fe80::1", "192.168.1.1&x=1", " "))
        assertEquals("/api/v1/client/home-network", server.takeRequest().path, "nothing valid: no parameter")

        apiClient.probeHomeNetwork(
            local = listOf("10.0.0.1", "10.0.0.1", "10.0.0.2", "172.16.0.1", "172.31.255.254", "192.168.0.1"),
        )
        assertEquals(
            "/api/v1/client/home-network?local=10.0.0.1,10.0.0.2,172.16.0.1,172.31.255.254",
            server.takeRequest().path,
        )
    }

    @Test
    fun `gateway site is parsed`() = runBlocking {
        answer(
            200,
            """{"ok":true,"lan":[],"same_lan":true,"via":"direct","site":{"type":"gateway","gatewayId":7,"name":"DS218+"}}""",
        )

        assertEquals(
            HomeNetworkStatus.Home(viaTunnel = false, site = HomeSite(HomeSite.Type.GATEWAY, gatewayId = "7", name = "DS218+")),
            apiClient.probeHomeNetwork(local = listOf("192.168.27.5")),
        )
    }

    @Test
    fun `server site is parsed`() = runBlocking {
        answer(200, """{"ok":true,"same_lan":true,"via":"tunnel","site":{"type":"server"}}""")

        assertEquals(
            HomeNetworkStatus.Home(viaTunnel = true, site = HomeSite(HomeSite.Type.SERVER)),
            apiClient.probeHomeNetwork(),
        )
    }

    @Test
    fun `explicit null site marks a server that knows gateway sites`() = runBlocking {
        answer(200, """{"ok":true,"same_lan":false,"via":"direct","site":null}""")
        assertEquals(HomeNetworkStatus.NotHome(viaTunnel = false, siteAware = true), apiClient.probeHomeNetwork())

        // Older server: no `site` at all.
        answer(200, """{"ok":true,"same_lan":false,"via":"direct"}""")
        assertEquals(HomeNetworkStatus.NotHome(viaTunnel = false, siteAware = false), apiClient.probeHomeNetwork())
    }

    @Test
    fun `odd site values do not break the answer`() = runBlocking {
        answer(200, """{"ok":true,"same_lan":true,"via":"tunnel","site":"gateway"}""")
        assertEquals(HomeNetworkStatus.Home(viaTunnel = true, site = null), apiClient.probeHomeNetwork())

        answer(200, """{"ok":true,"same_lan":true,"via":"tunnel","site":{"type":"satellite","name":"  \u0007Box  "}}""")
        assertEquals(
            HomeNetworkStatus.Home(viaTunnel = true, site = HomeSite(HomeSite.Type.OTHER, name = "Box")),
            apiClient.probeHomeNetwork(),
        )
    }

    @Test
    fun `private ipv4 check`() {
        listOf("10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.1.1", "192.168.27.5").forEach {
            assertTrue(isPrivateIpv4(it), it)
        }
        listOf(
            "172.15.0.1", "172.32.0.1", "192.169.0.1", "8.8.8.8", "100.64.0.1", "127.0.0.1", "169.254.1.1",
            "10.0.0", "10.0.0.1.1", "10.0.0.256", "10.00.0.1", "10.0.0.-1", "10.0.0.1 ", "", "::1", "a.b.c.d",
        ).forEach { assertFalse(isPrivateIpv4(it), it) }
    }

    @Test
    fun `local lan addresses keep private ipv4 only`() {
        val candidates = listOf(
            "fe80::1", "192.168.27.5", "8.8.8.8", "192.168.27.5", "127.0.0.1", "10.0.0.3",
            "172.20.0.1", "10.1.1.1", "10.2.2.2",
        ).map { InetAddress.getByName(it) }

        assertEquals(listOf("192.168.27.5", "10.0.0.3", "172.20.0.1", "10.1.1.1"), localLanAddresses(candidates))
        assertEquals(emptyList<String>(), localLanAddresses(emptyList()))
        assertNull(homeNetworkLocalQuery(emptyList()))
    }
}
