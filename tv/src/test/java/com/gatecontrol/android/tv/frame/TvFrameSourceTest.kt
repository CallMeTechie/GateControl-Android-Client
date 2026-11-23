package com.gatecontrol.android.tv.frame

import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import retrofit2.Response
import java.io.IOException

/** GET frame through [TvFrameSource]: kept for half an hour, asked again, failures and the states. */
class TvFrameSourceTest {

    private val client = mockk<ApiClient>()
    private val setup = mockk<SetupRepository> {
        every { getServerUrl() } returns "https://gate.example.com"
        every { isConfigured() } returns true
    }
    private val provider = mockk<ApiClientProvider> { every { getClient(any()) } returns client }
    private var now = 1_000_000L
    private val source = TvFrameSource(setup, provider).also { it.clock = { now } }

    private fun ok(s: String): Response<JsonObject> = Response.success(JsonParser.parseString(s).asJsonObject)

    private val two = """{"ok":true,"on":true,"ready":true,"seconds":20,"order":"random","items":[
        {"url":"/plugin-media/a/preview?size=large","takenAt":"2026-10-02T10:00:00.000Z"},
        {"url":"/plugin-media/b/preview?size=large","takenAt":"2026-10-01T10:00:00.000Z"}]}"""

    @Test
    fun `the answer is kept for half an hour, then asked again`() = runTest {
        coEvery { client.pluginGet("gatecontrol-drive", "frame", any()) } returns ok(two)
        assertEquals(2, source.load()!!.items.size)
        assertEquals(TvFrameStatus.Photos(2), source.status.value)
        now += 29 * 60_000
        source.load()
        coVerify(exactly = 1) { client.pluginGet(any(), any(), any()) }
        now += 2 * 60_000
        source.load()
        coVerify(exactly = 2) { client.pluginGet(any(), any(), any()) }
        source.load(force = true)
        coVerify(exactly = 3) { client.pluginGet(any(), any(), any()) }
    }

    @Test
    fun `nothing shared or switched off says so`() = runTest {
        coEvery { client.pluginGet(any(), any(), any()) } returns ok("""{"ok":true,"on":true,"ready":true,"seconds":20,"order":"random","items":[]}""")
        source.load()
        assertEquals(TvFrameStatus.NoPhotos, source.status.value)
        coEvery { client.pluginGet(any(), any(), any()) } returns ok("""{"ok":true,"on":false,"ready":true,"seconds":20,"order":"random","items":[]}""")
        source.load(force = true)
        assertEquals(TvFrameStatus.NoPhotos, source.status.value)
        coEvery { client.pluginGet(any(), any(), any()) } returns ok("""{"ok":true,"on":true,"ready":false,"seconds":20,"order":"random","items":[]}""")
        source.load(force = true)
        assertEquals(TvFrameStatus.NotReady, source.status.value)
    }

    @Test
    fun `a Drive without the frame for apps is unavailable`() = runTest {
        coEvery { client.pluginGet(any(), any(), any()) } returns Response.error(403, """{"error":"forbidden"}""".toResponseBody())
        assertNull(source.load())
        assertEquals(TvFrameStatus.Unavailable, source.status.value)
    }

    @Test
    fun `a failure keeps the photos in hand and asks again in a minute`() = runTest {
        coEvery { client.pluginGet(any(), any(), any()) } returns ok(two)
        source.load()
        coEvery { client.pluginGet(any(), any(), any()) } throws IOException("offline")
        now += 31 * 60_000
        assertNull(source.load())
        assertEquals(TvFrameStatus.Photos(2), source.status.value)
        assertNotNull(source.last)
        now += 30_000
        source.load()
        coVerify(exactly = 2) { client.pluginGet(any(), any(), any()) }
        now += 31_000
        source.load()
        coVerify(exactly = 3) { client.pluginGet(any(), any(), any()) }
    }

    @Test
    fun `a failure without photos says so, and unpairing forgets the answer`() = runTest {
        coEvery { client.pluginGet(any(), any(), any()) } throws IOException("offline")
        source.load()
        assertEquals(TvFrameStatus.Failed, source.status.value)
        coEvery { client.pluginGet(any(), any(), any()) } returns ok(two)
        now += 61_000
        source.load()
        assertEquals(TvFrameStatus.Photos(2), source.status.value)
        source.clear()
        assertEquals(TvFrameStatus.Unknown, source.status.value)
        assertNull(source.last)
    }
}
