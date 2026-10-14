package com.gatecontrol.android.familie

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
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import retrofit2.Response
import java.io.IOException

class FamilieApiTest {

    private val api = mockk<ApiClient>()
    private val setup = mockk<SetupRepository> {
        every { getServerUrl() } returns "https://gc.example.com"
        every { isConfigured() } returns true
    }
    private val provider = mockk<ApiClientProvider> { every { getClient(any()) } returns api }
    private val familie = FamilieApi(setup, provider)

    private fun ok(json: String): Response<JsonObject> = Response.success(JsonParser.parseString(json).asJsonObject)

    private fun error(code: Int, json: String): Response<JsonObject> =
        Response.error(code, json.toResponseBody("application/json".toMediaType()))

    @Test
    fun `calls go through the client plugin bridge of the familie plugin`() = runBlocking {
        val path = slot<String>()
        val query = slot<Map<String, String>>()
        coEvery { api.pluginGet("gatecontrol-familie", capture(path), capture(query)) } returns
            ok("""{"ok":true,"lists":[],"items":[],"serverTime":"2026-10-14T12:00:00Z"}""")
        val r = familie.shopping(since = "2026-10-14T11:00:00Z")
        assertInstanceOf(FamilieResult.Ok::class.java, r)
        assertEquals("shopping", path.captured)
        assertEquals(mapOf("since" to "2026-10-14T11:00:00Z"), query.captured)
    }

    @Test
    fun `alltag events come from the alltag plugin`() = runBlocking {
        coEvery { api.pluginGet("gatecontrol-alltag", "app/events", any()) } returns ok("""{"ok":true,"events":[],"waste":[]}""")
        assertInstanceOf(FamilieResult.Ok::class.java, familie.alltagEvents("2026-10-14", "2026-12-13"))
        coVerify { api.pluginGet("gatecontrol-alltag", "app/events", mapOf("from" to "2026-10-14", "to" to "2026-12-13")) }
    }

    @Test
    fun `error answers carry the contract code`() = runBlocking {
        coEvery { api.pluginPost(any(), "locate/5", any()) } returns error(429, """{"ok":false,"code":"too_soon","error":"Bitte warten","retryAfter":42,"requestId":17}""")
        assertEquals(FamilieResult.Failed("too_soon", 429, retryAfter = 42, requestId = 17), familie.requestLocation(5))

        coEvery { api.pluginGet(any(), "me", any()) } returns error(403, """{"ok":false,"code":"not_member"}""")
        assertEquals(FamilieResult.Failed("not_member", 403), familie.me())

        coEvery { api.pluginGet(any(), "tasks", any()) } returns error(404, "<html>")
        assertEquals(FamilieResult.Failed("http_404", 404), familie.tasks())

        // 200 with ok:false is an error too.
        coEvery { api.pluginPost(any(), "tasks/4/done", any()) } returns ok("""{"ok":false,"code":"module_off"}""")
        assertEquals(FamilieResult.Failed("module_off", 200), familie.taskDone(4))
    }

    @Test
    fun `network trouble is offline, a create without the object still succeeds`() = runBlocking {
        coEvery { api.pluginGet(any(), "places", any()) } throws IOException("reset")
        assertEquals(FamilieResult.Offline, familie.places())

        coEvery { api.pluginPost(any(), "locate/5", any()) } returns ok("""{"ok":true}""")
        assertEquals(FamilieResult.Ok(Created<LocateRequest>(null)), familie.requestLocation(5))

        coEvery { api.pluginPost(any(), "sos", any()) } returns ok("""{"ok":true,"sosId":8}""")
        assertEquals(FamilieResult.Ok(Created(8)), familie.sos(null, null, null, null))
    }

    @Test
    fun `done and snooze from a reminder name the occurrence`() = runBlocking {
        val body = slot<JsonObject>()
        coEvery { api.pluginPost(any(), "tasks/4/snooze", capture(body)) } returns ok("""{"ok":true,"snoozedUntil":"2026-10-14T18:00:00Z","occurrence":"2026-10-14"}""")
        assertEquals(FamilieResult.Ok(Unit), familie.taskSnooze(4, 60, "2026-10-14"))
        assertEquals("""{"minutes":60,"occurrence":"2026-10-14"}""", body.captured.toString())
        coEvery { api.pluginPost(any(), "tasks/4/done", capture(body)) } returns ok("""{"ok":true,"alreadyDone":false,"starsBooked":1}""")
        familie.taskDone(4)
        assertEquals("{}", body.captured.toString())
    }

    @Test
    fun `answer body as the contract describes it`() = runBlocking {
        val body = slot<JsonObject>()
        coEvery { api.pluginPost(any(), "locate/requests/21/answer", capture(body)) } returns ok("""{"ok":true}""")
        familie.answer(21, 52.52, 13.405, 12.0, "2026-10-14T14:42:09Z", 54)
        assertEquals("""{"lat":52.52,"lon":13.405,"accuracy":12.0,"at":"2026-10-14T14:42:09Z","battery":54}""", body.captured.toString())
    }

    @Test
    fun `nothing is sent without a configured server`() = runBlocking {
        every { setup.isConfigured() } returns false
        assertEquals(FamilieResult.NotConfigured, familie.me())
    }
}
