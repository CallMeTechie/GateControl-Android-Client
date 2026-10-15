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

    private fun repo() = ProfileRepository(setup, settings, provider, loader)
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
}
