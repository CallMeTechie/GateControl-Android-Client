package com.gatecontrol.android.dnsprotect

import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.PermissionFlags
import com.gatecontrol.android.network.PermissionsResponse
import com.gatecontrol.android.network.VpnDnsInfo
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Response
import java.io.IOException

class DnsRepositoryTest {

    private val setup: SetupRepository = mockk(relaxed = true)
    private val provider: ApiClientProvider = mockk(relaxed = true)
    private val api: ApiClient = mockk(relaxed = true)
    private val settings: SettingsRepository = mockk(relaxed = true)
    private val license = LicenseRepository()
    private val lang = mapOf("lang" to "de")

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject
    private fun error(http: Int, body: String): Response<JsonObject> =
        Response.error(http, body.toResponseBody("application/json".toMediaType()))

    private fun repo() = DnsRepository(setup, provider, license, settings)

    private fun reported(plugin: String = "gatecontrol-dns", state: String = "filtering") {
        license.updatePermissions(services = true, traffic = false, dns = false, rdp = false, vpnDns = LicenseRepository.VpnDns(plugin, state))
    }

    @BeforeEach
    fun setUp() {
        every { setup.getServerUrl() } returns "https://gate.example.org"
        every { provider.getClient(any()) } returns api
        every { settings.getLocale() } returns flowOf("de")
    }

    @Test
    fun `without a reported DNS protection nothing is asked`() = runTest {
        val r = repo().refresh()
        assertEquals(DnsResult.Unavailable, r)
        coVerify(exactly = 0) { api.pluginGet(any(), any(), any()) }
    }

    @Test
    fun `status is asked from the plugin the permissions name, in the app's language`() = runTest {
        reported(plugin = "other-dns")
        coEvery { api.pluginGet("other-dns", "status", lang) } returns Response.success(json("""{ "ok": true, "state": "active", "serverTime": "2026-11-12T13:48:00.000Z" }"""))
        val repo = repo()
        val r = repo.refresh(nowMs = 42)
        assertEquals(DnsSnapshot(DnsStatus("active", serverTime = "2026-11-12T13:48:00.000Z"), 42), (r as DnsResult.Ok).value)
        assertEquals(r.value, repo.snapshot.value)
    }

    @Test
    fun `pause posts the duration and takes the status of the answer`() = runTest {
        reported()
        coEvery { api.pluginPost("gatecontrol-dns", "pause", json("""{"minutes":30}"""), lang) } returns
            Response.success(json("""{ "ok": true, "pause": {}, "status": { "state": "paused", "pause": { "scope": "peer", "remainingSeconds": 1800 } } }"""))
        val repo = repo()
        val r = repo.pause(DnsDuration.Minutes(30), nowMs = 7)
        assertEquals("paused", (r as DnsResult.Ok).value.status.state)
        assertEquals(1800L, repo.snapshot.value?.status?.pause?.remainingSeconds)
    }

    @Test
    fun `resume for a person posts the target`() = runTest {
        reported()
        coEvery { api.pluginPost("gatecontrol-dns", "resume", json("""{"userId":4}"""), lang) } returns
            Response.success(json("""{ "ok": true, "resumed": false, "status": { "state": "active" } }"""))
        assertTrue(repo().resume(DnsTarget.User(4)) is DnsResult.Ok, "resumed:false is no error")
    }

    @Test
    fun `a refusal keeps the last status and carries the server's sentence`() = runTest {
        reported()
        coEvery { api.pluginGet("gatecontrol-dns", "status", lang) } returns Response.success(json("""{ "state": "active" }"""))
        coEvery { api.pluginPost("gatecontrol-dns", "pause", any(), lang) } returns
            error(403, """{ "ok": false, "code": "too_long", "error": "Höchstens 60 Minuten." }""")
        val repo = repo()
        repo.refresh()
        val r = repo.pause(DnsDuration.Minutes(240))
        assertEquals(DnsResult.Failed("too_long", 403, "Höchstens 60 Minuten."), r)
        assertNotNull(repo.snapshot.value)
    }

    @Test
    fun `gone drops the status - the core's 404, an old server's 501, switched off`() = runTest {
        reported()
        val repo = repo()
        for ((http, body) in listOf(404 to """{ "ok": false, "code": "not_found" }""", 501 to """{ "ok": false, "code": "unsupported", "error": "x" }""", 409 to """{ "ok": false, "code": "off", "error": "x" }""")) {
            coEvery { api.pluginGet("gatecontrol-dns", "status", lang) } returns Response.success(json("""{ "state": "active" }"""))
            repo.refresh()
            assertNotNull(repo.snapshot.value)
            coEvery { api.pluginGet("gatecontrol-dns", "status", lang) } returns error(http, body)
            repo.refresh()
            assertNull(repo.snapshot.value, "HTTP $http")
        }
    }

    @Test
    fun `no network keeps the last status`() = runTest {
        reported()
        coEvery { api.pluginGet("gatecontrol-dns", "status", lang) } returns Response.success(json("""{ "state": "active" }"""))
        val repo = repo()
        repo.refresh()
        coEvery { api.pluginGet("gatecontrol-dns", "status", lang) } throws IOException("offline")
        assertEquals(DnsResult.Offline, repo.refresh())
        assertEquals("active", repo.snapshot.value?.status?.state)
    }

    @Test
    fun `an answer without a state is an invalid answer, never a crash`() = runTest {
        reported()
        coEvery { api.pluginGet("gatecontrol-dns", "status", lang) } returns Response.success(json("""{ "ok": true }"""))
        assertEquals(DnsResult.Failed(DnsRepository.CODE_INVALID, 200), repo().refresh())
        coEvery { api.pluginGet("gatecontrol-dns", "status", lang) } throws IllegalStateException("boom")
        assertEquals(DnsResult.Failed(DnsRepository.CODE_INVALID, 0), repo().refresh())
    }

    @Test
    fun `people are read from the plugin`() = runTest {
        reported()
        coEvery { api.pluginGet("gatecontrol-dns", "people", lang) } returns Response.success(json("""{ "ok": true, "all": null, "people": [{ "userId": 4, "name": "Lea" }] }"""))
        assertEquals(listOf("Lea"), (repo().people() as DnsResult.Ok).value.people.map { it.name })
        coEvery { api.pluginGet("gatecontrol-dns", "people", lang) } returns error(403, """{ "ok": false, "code": "forbidden", "error": "Nein." }""")
        assertEquals(DnsResult.Failed("forbidden", 403, "Nein."), repo().people())
    }

    @Test
    fun `reloading the permissions takes vpnDns away when the server no longer reports it`() = runTest {
        reported()
        coEvery { api.getPermissions() } returns PermissionsResponse(
            ok = true,
            permissions = PermissionFlags(services = true, traffic = false, dns = false, rdp = false),
            scopes = emptyList(),
            vpnDns = null,
        )
        repo().reloadPermissions()
        assertNull(license.permissions.value.vpnDns)
        assertTrue(license.permissions.value.services, "the other permissions stay")

        coEvery { api.getPermissions() } returns PermissionsResponse(
            ok = true,
            permissions = PermissionFlags(services = true, traffic = false, dns = false, rdp = false),
            scopes = emptyList(),
            vpnDns = VpnDnsInfo("gatecontrol-dns", "fallback"),
        )
        repo().reloadPermissions()
        assertEquals(LicenseRepository.VpnDns("gatecontrol-dns", "fallback"), license.permissions.value.vpnDns)

        // No answer: what was known stays.
        coEvery { api.getPermissions() } throws IOException("offline")
        repo().reloadPermissions()
        assertNotNull(license.permissions.value.vpnDns)
    }
}
