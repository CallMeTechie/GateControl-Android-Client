package com.gatecontrol.android.network

import android.content.Context
import android.content.pm.ApplicationInfo
import com.gatecontrol.android.data.MachineFingerprint
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import com.google.gson.JsonParser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The real client wiring of [ApiClientProvider]: fingerprint header, enroll body, binding errors. */
class MachineFingerprintRequestTest {

    private val fingerprint = "0123456789abcdef".repeat(4)
    private lateinit var server: MockWebServer
    private lateinit var foreign: MockWebServer
    private lateinit var monitor: MachineBindingMonitor
    private lateinit var provider: ApiClientProvider

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        foreign = MockWebServer().apply { start() }
        monitor = MachineBindingMonitor()
        val context = mockk<Context> {
            every { applicationInfo } returns mockk<ApplicationInfo>(relaxed = true)
        }
        val machineFingerprint = mockk<MachineFingerprint> { every { get() } returns fingerprint }
        val auth = AuthInterceptor({ "gc_token" }, { "1.0.0" }, { "android" })
        provider = ApiClientProvider(auth, context, machineFingerprint, monitor)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        foreign.shutdown()
    }

    private fun ok(body: String = "{\"ok\":true}") = MockResponse().setResponseCode(200).setBody(body)

    @Test
    fun `client API calls carry the fingerprint header`() = runTest {
        val api = provider.getClient(server.url("/").toString())
        server.enqueue(ok("{\"ok\":true,\"version\":\"1\",\"timestamp\":\"t\"}"))
        server.enqueue(ok("{\"ok\":true}"))
        server.enqueue(ok("{\"ok\":true}"))
        server.enqueue(ok("{\"ok\":true}"))
        server.enqueue(ok("{\"ok\":true}"))
        server.enqueue(ok("{\"ok\":true,\"available\":false}"))

        api.ping()
        runCatching { api.getTraffic(1) }
        runCatching { api.sendHeartbeat(HeartbeatRequest(1, true, 0, 0, 0, "pixel")) }
        runCatching { api.getClientPolicy() }
        runCatching { api.uploadSupportBundle(1, "x".toRequestBody()) }
        runCatching { api.checkUpdate("1.0.0") }

        repeat(6) {
            val recorded = server.takeRequest()
            assertEquals(fingerprint, recorded.getHeader("X-Machine-Fingerprint"), recorded.path)
            assertEquals("gc_token", recorded.getHeader("X-API-Token"))
        }
    }

    @Test
    fun `enroll sends the fingerprint in body and header`() = runTest {
        server.enqueue(ok("{\"ok\":true,\"token\":\"gc_new\",\"peerId\":3}"))
        provider.getClient(server.url("/").toString()).enroll(
            EnrollRequest("AB12-CD34-EF56-7890", "pixel", "android", "1.0.0", fingerprint),
        )
        val recorded = server.takeRequest()
        assertEquals("/api/v1/client/enroll", recorded.path)
        assertEquals(fingerprint, JsonParser.parseString(recorded.body.readUtf8()).asJsonObject.get("fingerprint").asString)
        assertEquals(fingerprint, recorded.getHeader("X-Machine-Fingerprint"))
    }

    @Test
    fun `redirect to a foreign host does not carry the fingerprint`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", foreign.url("/elsewhere")))
        foreign.enqueue(ok())

        val http = provider.buildOkHttpClient(server.url("/").toString())
        http.newCall(Request.Builder().url(server.url("/api/v1/client/ping")).build()).execute().close()

        assertEquals(fingerprint, server.takeRequest().getHeader("X-Machine-Fingerprint"))
        assertNull(foreign.takeRequest().getHeader("X-Machine-Fingerprint"))
    }

    @Test
    fun `request to another host on the same client gets no fingerprint`() {
        foreign.enqueue(ok())
        val http = provider.buildOkHttpClient(server.url("/").toString())
        http.newCall(
            Request.Builder().url(foreign.url("/x")).header("X-Machine-Fingerprint", fingerprint).build(),
        ).execute().close()
        assertNull(foreign.takeRequest().getHeader("X-Machine-Fingerprint"))
    }

    @Test
    fun `api token goes to the server but not across a redirect to a foreign host`() {
        server.enqueue(ok())
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", foreign.url("/steal")))
        foreign.enqueue(ok())

        val http = provider.buildOkHttpClient(server.url("/").toString())
        http.newCall(Request.Builder().url(server.url("/api/v1/client/ping")).build()).execute().close()
        http.newCall(Request.Builder().url(server.url("/api/v1/client/traffic")).build()).execute().close()

        assertEquals("gc_token", server.takeRequest().getHeader("X-API-Token"))
        assertEquals("gc_token", server.takeRequest().getHeader("X-API-Token"))
        val leaked = foreign.takeRequest()
        assertEquals("/steal", leaked.path)
        assertNull(leaked.getHeader("X-API-Token"))
        assertNull(leaked.getHeader("X-Machine-Fingerprint"))
    }

    @Test
    fun `explicit test token is stripped on a foreign host too`() {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", foreign.url("/x")))
        foreign.enqueue(ok())
        val http = provider.buildOkHttpClient(server.url("/").toString())
        http.newCall(
            Request.Builder().url(server.url("/api/v1/client/ping")).header("X-API-Token", "typed").build(),
        ).execute().close()
        assertEquals("typed", server.takeRequest().getHeader("X-API-Token"))
        assertNull(foreign.takeRequest().getHeader("X-API-Token"))
    }

    @Test
    fun `same host on a different port counts as foreign`() {
        val http = provider.buildOkHttpClient(server.url("/").toString())
        foreign.enqueue(ok())
        // server and foreign are both localhost — only the port differs.
        http.newCall(Request.Builder().url(foreign.url("/x")).build()).execute().close()
        val recorded = foreign.takeRequest()
        assertNull(recorded.getHeader("X-API-Token"))
        assertNull(recorded.getHeader("X-Machine-Fingerprint"))
    }

    @Test
    fun `binding rejection is reported and cleared by the next success`() = runTest {
        val api = provider.getClient(server.url("/").toString())
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("{\"ok\":false,\"error\":\"Token is bound to a different machine\"}"),
        )
        val err = runCatching { api.getTraffic(1) }.exceptionOrNull()
        assertTrue(err is retrofit2.HttpException)
        assertEquals(MachineBindingError.MISMATCH, monitor.error.value)
        // The body is still readable for the caller.
        assertEquals(MachineBindingError.MISMATCH, MachineBindingError.from(err!!))

        server.enqueue(ok("{\"ok\":true}"))
        runCatching { api.getTraffic(1) }
        assertNull(monitor.error.value)
    }

    @Test
    fun `other 403s are not binding errors`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("{\"ok\":false,\"error\":\"Forbidden\"}"))
        runCatching { provider.getClient(server.url("/").toString()).getTraffic(1) }
        assertNull(monitor.error.value)
    }

    @Test
    fun `classifier matches english, german and enroll codes`() {
        assertEquals(MachineBindingError.MISMATCH, MachineBindingError.fromResponse(403, "{\"error\":\"Token ist an eine andere Maschine gebunden\"}"))
        assertEquals(MachineBindingError.REQUIRED, MachineBindingError.fromResponse(403, "{\"error\":\"Machine fingerprint required\"}"))
        assertEquals(MachineBindingError.REQUIRED, MachineBindingError.fromResponse(403, "{\"error\":\"Maschinen-Fingerprint erforderlich\"}"))
        assertEquals(MachineBindingError.REQUIRED, MachineBindingError.fromResponse(400, "{\"ok\":false,\"error\":\"fingerprint_required\"}"))
        assertEquals(MachineBindingError.INVALID, MachineBindingError.fromResponse(400, "{\"error\":\"Invalid machine fingerprint format\"}"))
        assertEquals(MachineBindingError.INVALID, MachineBindingError.fromResponse(400, "{\"error\":\"Ungültiges Fingerprint-Format\"}"))
        assertNull(MachineBindingError.fromResponse(500, "bound to a different machine"))
        assertNull(MachineBindingError.fromResponse(403, null))
    }
}
