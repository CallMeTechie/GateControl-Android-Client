package com.gatecontrol.android.profile

import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.Response

class ProfileRepositoryTest {

    private val cache = MutableStateFlow<String?>(null)
    private val settings = mockk<SettingsRepository> {
        every { getProfileCache() } returns cache
        coEvery { setProfileCache(any()) } coAnswers { cache.value = firstArg() }
    }
    private val setup = mockk<SetupRepository> {
        every { getServerUrl() } returns "https://gc.example.com"
        every { getApiToken() } returns "token"
        every { isConfigured() } returns true
        every { revision } returns MutableStateFlow(0L)
    }
    private val api = mockk<ApiClient>()
    private val provider = mockk<ApiClientProvider> { every { getClient(any()) } returns api }
    private val loader = mockk<AvatarLoader>(relaxed = true)

    private val secure = mockk<com.gatecontrol.android.data.EncryptedStorage>(relaxed = true)
    private fun repo() = ProfileRepository(setup, settings, provider, loader, secure)
    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val profile = """{"ok":true,"user":{"id":7,"name":"Anna","initials":"AN","color":"green","colorHex":"#22C55E","avatarUrl":null},
        "palette":[{"key":"blue","hex":"#3B82F6"},{"key":"green","hex":"#22C55E"}]}"""

    @Test
    fun `profile is loaded and cached for this login`() = runTest {
        coEvery { api.getMyProfile() } returns Response.success(json(profile))
        val r = repo()
        val state = r.refresh()
        assertEquals(ProfileSupport.SUPPORTED, state.support)
        assertTrue(state.available)
        assertEquals("Anna", state.profile?.name)
        assertEquals("Anna", ProfileJson.decode(cache.value, com.gatecontrol.android.familie.PluginDirectory.ownerKey("https://gc.example.com", "token"))?.name)
    }

    @Test
    fun `older server hides the feature and forgets the cache`() = runTest {
        cache.value = "x"
        coEvery { api.getMyProfile() } returns Response.error(404, "{}".toResponseBody("application/json".toMediaType()))
        val state = repo().refresh()
        assertEquals(ProfileSupport.UNSUPPORTED, state.support)
        assertNull(state.profile)
        assertNull(cache.value)
    }

    @Test
    fun `machine tokens have no profile`() = runTest {
        coEvery { api.getMyProfile() } returns Response.error(403, """{"ok":false,"code":"NO_USER"}""".toResponseBody("application/json".toMediaType()))
        assertEquals(ProfileSupport.NO_USER, repo().refresh().support)
    }

    @Test
    fun `colour change sends the key, also null for the default`() = runTest {
        coEvery { api.getMyProfile() } returns Response.success(json(profile))
        val body = slot<RequestBody>()
        coEvery { api.putMyColor(capture(body)) } returns Response.success(json("""{"ok":true,"color":"blue","colorHex":"#3B82F6"}"""))
        val r = repo()
        r.refresh()
        // The refresh after the change sees the new colour.
        coEvery { api.getMyProfile() } returns Response.success(json(profile.replace("\"color\":\"green\",\"colorHex\":\"#22C55E\"", "\"color\":\"blue\",\"colorHex\":\"#3B82F6\"")))
        assertEquals(ProfileResult.Ok, r.setColor("blue"))
        assertEquals("""{"color":"blue"}""", Buffer().also { body.captured.writeTo(it) }.readUtf8())
        assertEquals("#3B82F6", r.state.value.profile?.colorHex)
        r.setColor(null)
        assertEquals("""{"color":null}""", Buffer().also { body.captured.writeTo(it) }.readUtf8())
    }

    @Test
    fun `upload failures name the reason`() = runTest {
        coEvery { api.putMyAvatar(any()) } returns Response.error(413, """{"ok":false,"code":"TOO_LARGE"}""".toResponseBody("application/json".toMediaType()))
        assertEquals(ProfileResult.Failed(ProfileRepository.CODE_TOO_LARGE), repo().uploadAvatar(ByteArray(10), "image/webp"))
        coEvery { api.putMyAvatar(any()) } throws java.io.IOException("offline")
        assertEquals(ProfileResult.Failed(ProfileRepository.CODE_OFFLINE), repo().uploadAvatar(ByteArray(10), "image/webp"))
    }

    @Test
    fun `a successful upload primes the picture cache under the new address`() = runTest {
        coEvery { api.getMyProfile() } returns Response.success(json(profile))
        coEvery { api.putMyAvatar(any()) } returns Response.success(json("""{"ok":true,"avatarUrl":"/avatars/AbCdEfGhIjKlMnOpQrStUv.webp"}"""))
        val r = repo()
        r.refresh()
        coEvery { api.getMyProfile() } returns Response.success(json(profile.replace("\"avatarUrl\":null", "\"avatarUrl\":\"/avatars/AbCdEfGhIjKlMnOpQrStUv.webp\"")))
        val bytes = ByteArray(10)
        assertEquals(ProfileResult.Ok, r.uploadAvatar(bytes, "image/webp"))
        coVerify { loader.prime("https://gc.example.com/avatars/AbCdEfGhIjKlMnOpQrStUv.webp", bytes) }
        assertEquals("/avatars/AbCdEfGhIjKlMnOpQrStUv.webp", r.state.value.profile?.avatarUrl)
    }

    // ─── the profile's fields (server 1.280) ──────────────────────

    private val fields = """{"userId":7,"username":"anna","displayName":"Anna","phones":[],"family":false,
        "recognition":{"byPhone":false,"phoneAnswered":false},"features":{"phones":true,"emailConfirm":true,"recognition":true}}"""
    private val withFields = profile.trimEnd().removeSuffix("}") + ""","profile":$fields}"""

    private fun bodyOf(b: RequestBody) = Buffer().also { b.writeTo(it) }.readUtf8()

    @Test
    fun `the fields come with the profile and go only to the encrypted storage`() = runTest {
        coEvery { api.getMyProfile() } returns Response.success(json(withFields))
        val r = repo()
        val state = r.refresh()
        assertEquals("anna", state.details?.username)
        io.mockk.verify { secure.putString("profile_details_v1", match { it.contains("\"userId\":7") }) }
        // the DataStore copy (plain) keeps picture and colour only
        assertTrue(cache.value!!.contains("Anna") && !cache.value!!.contains("phones"))
    }

    @Test
    fun `an older server has no fields - none kept`() = runTest {
        coEvery { api.getMyProfile() } returns Response.success(json(profile))
        val state = repo().refresh()
        assertNull(state.details)
        io.mockk.verify { secure.remove("profile_details_v1") }
    }

    @Test
    fun `a change replaces the profile with the server's answer`() = runTest {
        coEvery { api.getMyProfile() } returns Response.success(json(withFields))
        val body = slot<RequestBody>()
        val after = fields.replace("\"phones\":[]", "\"phones\":[{\"id\":11,\"kind\":\"mobile\",\"number\":\"+4915100000000\",\"visibility\":\"none\",\"useForRecognition\":true,\"state\":\"unconfirmed\"}]")
        coEvery { api.addMyPhone(capture(body)) } returns Response.success(json("""{"ok":true,"phone":{"id":11},"profile":$after}"""))
        val r = repo()
        r.refresh()
        val result = r.addPhone("mobile", "+4915100000000", "none", true)
        assertTrue(result is ProfileEdit.Done)
        assertEquals("""{"kind":"mobile","number":"+4915100000000","visibility":"none","useForRecognition":true}""", bodyOf(body.captured))
        assertEquals(1, r.state.value.details?.phones?.size)
        // no second question to the server
        coVerify(exactly = 1) { api.getMyProfile() }
    }

    @Test
    fun `a refusal names code, field and the server's text`() = runTest {
        coEvery { api.patchMyPhone(11, any()) } returns Response.error(
            409, """{"ok":false,"code":"DUPLICATE","error":"Diese Nummer steht schon in deinem Profil.","field":"number"}""".toResponseBody("application/json".toMediaType()),
        )
        assertEquals(
            ProfileEdit.Refused("DUPLICATE", "number", "Diese Nummer steht schon in deinem Profil."),
            repo().updatePhone(11, null, "+4915100000000", null, null),
        )
        coEvery { api.patchMyProfile(any()) } throws java.io.IOException("offline")
        assertEquals(ProfileEdit.Refused(ProfileRepository.CODE_OFFLINE), repo().updateFields(com.google.gson.JsonObject()))
        assertEquals("RATE_LIMITED", ProfileRepository.refusal(429, null).code)
    }

    @Test
    fun `my data as a file with the server's name`() = kotlinx.coroutines.runBlocking {
        // runBlocking: the file is read on Dispatchers.IO, under runTest's virtual time the timeout would win
        coEvery { api.exportMyProfile() } returns Response.success(
            "{\"account\":{}}".toResponseBody("application/json".toMediaType()),
            okhttp3.Headers.headersOf("Content-Disposition", "attachment; filename=\"gatecontrol-meine-daten-2026-11-15.json\""),
        )
        val file = repo().export() as ProfileExport.File
        assertEquals("gatecontrol-meine-daten-2026-11-15.json", file.name)
        assertEquals("{\"account\":{}}", String(file.bytes))
        coEvery { api.exportMyProfile() } returns Response.error(429, """{"ok":false,"code":"RATE_LIMITED"}""".toResponseBody("application/json".toMediaType()))
        assertEquals(ProfileExport.Refused("RATE_LIMITED"), repo().export())
        Unit
    }
}
