package com.gatecontrol.android.familie

import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.familie.shopping.ShoppingOp
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import retrofit2.Response
import java.io.IOException

/** The calls the phone app and GateControl TV share, through the client plugin bridge. */
class FamilieHomeApiTest {

    private val client = mockk<ApiClient>()
    private var configured = true
    private val setup = mockk<SetupRepository> {
        every { getServerUrl() } returns "https://gc.example.com"
        every { isConfigured() } answers { configured }
    }
    private val provider = mockk<ApiClientProvider> { every { getClient(any()) } returns client }
    private val api = FamilieHomeApi(setup, provider)

    private fun ok(json: String): Response<JsonObject> = Response.success(JsonParser.parseString(json).asJsonObject)

    private fun error(code: Int, json: String): Response<JsonObject> =
        Response.error(code, json.toResponseBody("application/json".toMediaType()))

    @Test
    fun `shopping goes to the familie plugin with since`() = runBlocking {
        coEvery { client.pluginGet("gatecontrol-familie", "shopping", any()) } returns
            ok("""{"ok":true,"lists":[{"id":"list-0001","name":"Wocheneinkauf","sort":1}],"items":[{"id":"item-0001","listId":"list-0001","text":"Milch"}],"serverTime":"2026-11-24T18:00:00Z"}""")
        val r = api.shopping("2026-11-24T17:00:00Z") as FamilieResult.Ok
        assertEquals(listOf("Wocheneinkauf"), r.value.lists.map { it.name })
        assertEquals(listOf("Milch"), r.value.items.map { it.text })
        coVerify { client.pluginGet("gatecontrol-familie", "shopping", mapOf("since" to "2026-11-24T17:00:00Z")) }

        api.shopping(null)
        coVerify { client.pluginGet("gatecontrol-familie", "shopping", emptyMap()) }
    }

    @Test
    fun `ops are posted in one body`() = runBlocking {
        val body = slot<JsonObject>()
        coEvery { client.pluginPost("gatecontrol-familie", "shopping/ops", capture(body)) } returns
            ok("""{"ok":true,"applied":1,"rejected":[],"serverTime":"2026-11-24T18:00:01Z"}""")
        val r = api.shoppingOps(listOf(ShoppingOp(ShoppingOp.ITEM_CHECK, id = "item-0001", checked = true))) as FamilieResult.Ok
        assertEquals(1, r.value.applied)
        assertEquals("""{"ops":[{"op":"item_check","id":"item-0001","checked":true}]}""", body.captured.toString())
    }

    @Test
    fun `dates and alltag come from their plugins`() = runBlocking {
        coEvery { client.pluginGet("gatecontrol-familie", "dates", any()) } returns
            ok("""{"ok":true,"dates":[{"id":"7","title":"Elternabend","date":"2026-11-24","time":"18:30","allDay":false}]}""")
        coEvery { client.pluginGet("gatecontrol-alltag", "app/events", any()) } returns
            ok("""{"ok":true,"events":[],"waste":[{"date":"2026-11-25","kinds":["Gelbe Tonne"]}]}""")
        val dates = api.dates("2026-11-24", "2026-11-30") as FamilieResult.Ok
        assertEquals("18:30", dates.value.single().time)
        val alltag = api.alltagEvents("2026-11-24", "2026-12-29") as FamilieResult.Ok
        assertEquals(listOf("Gelbe Tonne"), alltag.value.waste.single().kinds)
        coVerify { client.pluginGet("gatecontrol-familie", "dates", mapOf("from" to "2026-11-24", "to" to "2026-11-30")) }
        coVerify { client.pluginGet("gatecontrol-alltag", "app/events", mapOf("from" to "2026-11-24", "to" to "2026-12-29")) }
    }

    @Test
    fun `errors, no answer and no setup never throw`() = runBlocking {
        coEvery { client.pluginGet("gatecontrol-alltag", "app/events", any()) } returns error(404, """{"ok":false,"code":"not_found"}""")
        assertEquals(FamilieResult.Failed("not_found", 404), api.alltagEvents("2026-11-24", "2026-12-29"))

        coEvery { client.pluginGet("gatecontrol-familie", "dates", any()) } returns ok("""{"ok":false,"code":"module_off"}""")
        assertEquals(FamilieResult.Failed("module_off", 200), api.dates("2026-11-24", "2026-11-30"))

        coEvery { client.pluginGet("gatecontrol-familie", "shopping", any()) } returns error(502, "<html>")
        assertEquals(FamilieResult.Failed("http_502", 502), api.shopping(null))

        coEvery { client.pluginGet("gatecontrol-familie", "shopping", any()) } throws IOException("down")
        assertEquals(FamilieResult.Offline, api.shopping(null))
        assertEquals(FamiliePluginCalls.CODE_OFFLINE, api.shopping(null).errorCode())

        configured = false
        assertEquals(FamilieResult.NotConfigured, api.shopping(null))
    }

    @Test
    fun `a date id that is not a plain token is refused before any call`() = runBlocking {
        assertEquals(FamilieResult.Failed(FamiliePluginCalls.CODE_INVALID, 0), api.deleteDate("../admin"))
        assertNull(FamiliePluginCalls.path("dates", "a/b"))
        assertEquals("dates/member:5", FamiliePluginCalls.path("dates", "member:5"))
    }
}
