package com.gatecontrol.android.network

import kotlinx.coroutines.test.runTest
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

/** The app's platform on the wire: header, update check and the TV pairing calls. */
class ClientPlatformTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun api(platform: ClientPlatform): ApiClient {
        val auth = AuthInterceptor(
            tokenProvider = { "gc_test" },
            serverUrlProvider = { server.url("/").toString() },
            versionProvider = { "1.136.0" },
            platformProvider = { platform.wire },
        )
        return Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().addInterceptor(auth).build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiClient::class.java)
    }

    private fun updateAnswer() = MockResponse().setResponseCode(200)
        .setBody("""{"ok":true,"available":false,"version":null,"downloadUrl":null,"fileName":null,"fileSize":null,"releaseNotes":null}""")

    @Test
    fun `wire values and update query of both apps`() {
        assertEquals("android", ClientPlatform.ANDROID.wire)
        assertEquals("android-tv", ClientPlatform.ANDROID_TV.wire)
        assertEquals(UpdateQuery(client = "android", platform = "android"), ClientPlatform.ANDROID.updateQuery)
        assertEquals(UpdateQuery(client = "android-tv", platform = "android-tv"), ClientPlatform.ANDROID_TV.updateQuery)
    }

    @Test
    fun `GateControl TV sends its platform on every request`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"version":"1.318.0"}"""))
        api(ClientPlatform.ANDROID_TV).ping()
        assertEquals("android-tv", server.takeRequest().getHeader("X-Client-Platform"))
    }

    @Test
    fun `the TV's update check asks for the TV APK`() = runTest {
        server.enqueue(updateAnswer())
        val q = ClientPlatform.ANDROID_TV.updateQuery
        api(ClientPlatform.ANDROID_TV).checkUpdateWithLocal("1.136.0", q.platform, q.client, null)
        val req = server.takeRequest()
        val url = req.requestUrl!!
        assertEquals("/api/v1/client/update/check", url.encodedPath)
        assertEquals("android-tv", url.queryParameter("client"))
        assertEquals("android-tv", url.queryParameter("platform"))
        assertEquals("1.136.0", url.queryParameter("version"))
        assertEquals("android-tv", req.getHeader("X-Client-Platform"))
        assertNull(req.getHeader(HomeCache.HEADER_LOCAL))
    }

    @Test
    fun `the phone's update check stays as before`() = runTest {
        server.enqueue(updateAnswer())
        val q = ClientPlatform.ANDROID.updateQuery
        api(ClientPlatform.ANDROID).checkUpdateWithLocal("1.136.0", q.platform, q.client, "192.168.1.5")
        val req = server.takeRequest()
        assertEquals("android", req.requestUrl!!.queryParameter("client"))
        assertEquals("android", req.requestUrl!!.queryParameter("platform"))
        assertEquals("android", req.getHeader("X-Client-Platform"))
        assertEquals("192.168.1.5", req.getHeader(HomeCache.HEADER_LOCAL))
    }

    @Test
    fun `pairing calls and their answers`() = runTest {
        val api = api(ClientPlatform.ANDROID_TV)
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"ok":true,"pairingId":"AbCdEfGhIjKlMnOpQrSt_-","pollSecret":"s","code":"K7Q4-MZAB",""" +
                    """"expiresAt":1790000000000,"interval":5,"url":"https://gate.example.com",""" +
                    """"link":"gatecontrol://tv-pair?url=https%3A%2F%2Fgate.example.com&id=AbCdEfGhIjKlMnOpQrSt_-"}""",
            ),
        )
        val start = api.pairStart(PairStartRequest(name = "TV", platform = "android-tv", clientVersion = "1.136.0"))
        val startReq = server.takeRequest()
        assertEquals("/api/v1/client/pair/start", startReq.path)
        assertEquals("""{"name":"TV","platform":"android-tv","clientVersion":"1.136.0"}""", startReq.body.readUtf8())
        assertEquals(201, start.code())
        assertEquals("K7Q4-MZAB", start.body()?.code)
        assertEquals(1_790_000_000_000L, start.body()?.expiresAt)
        assertFalse(start.body().toString().contains("pollSecret=s"))

        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"ok":false,"error":"slow_down","interval":5}"""))
        val poll = api.pairPoll(PairPollRequest("AbCdEfGhIjKlMnOpQrSt_-", "s"))
        assertEquals("/api/v1/client/pair/poll", server.takeRequest().path)
        val body = ApiErrors.body(poll)
        assertEquals("slow_down", ApiErrors.code(body))
        assertEquals(5L, ApiErrors.number(body, "interval"))

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"status":"approved","code":"AB12-CD34-EF56-7890","expiresAt":"2026-11-22T10:00:00.000Z","name":"Wohnzimmer"}"""))
        val approved = api.pairPoll(PairPollRequest("AbCdEfGhIjKlMnOpQrSt_-", "s")).body()!!
        server.takeRequest()
        assertEquals("approved", approved.status)
        assertEquals("AB12-CD34-EF56-7890", approved.code)
        assertFalse(approved.toString().contains("AB12"))

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"name":"Wohnzimmer","platform":"android-tv","clientVersion":"1.136.0"}"""))
        val ok = api.pairApprove(PairApproveRequest(code = "K7Q4MZAB", pairingId = "AbCdEfGhIjKlMnOpQrSt_-"))
        val approveReq = server.takeRequest()
        assertEquals("/api/v1/client/pair/approve", approveReq.path)
        assertEquals("gc_test", approveReq.getHeader("X-API-Token"))
        assertTrue(ok.isSuccessful)
        assertEquals("Wohnzimmer", ok.body()?.name)
    }

    @Test
    fun `error codes of a JSON answer`() {
        assertEquals("invalid_code", ApiErrors.code("""{"ok":false,"error":"invalid_code"}"""))
        assertEquals(42L, ApiErrors.number("""{"ok":false,"error":"locked","retryAfter":42}""", "retryAfter"))
        assertNull(ApiErrors.code("<html>Bad Gateway</html>"))
        assertNull(ApiErrors.code(null))
        assertNull(ApiErrors.number("""{"ok":false}""", "interval"))
    }
}
