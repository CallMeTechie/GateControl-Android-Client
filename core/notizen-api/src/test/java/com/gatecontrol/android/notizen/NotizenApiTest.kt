package com.gatecontrol.android.notizen

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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.Response
import java.io.IOException

/** The routes of „Notizen & Aufgaben“ through the client plugin bridge, as the phone app and GateControl TV call them. */
class NotizenApiTest {

    private val client = mockk<ApiClient>()
    private var configured = true
    private val setup = mockk<SetupRepository> {
        every { getServerUrl() } returns "https://gc.example.com"
        every { isConfigured() } answers { configured }
    }
    private val settings = mockk<SettingsRepository> { every { getLocale() } returns flowOf("de") }
    private val provider = mockk<ApiClientProvider> { every { getClient(any()) } returns client }
    private val api = NotizenApi(setup, provider, settings)

    private fun ok(json: String): Response<JsonObject> = Response.success(JsonParser.parseString(json).asJsonObject)

    private fun error(code: Int, json: String): Response<JsonObject> =
        Response.error(code, json.toResponseBody("application/json".toMediaType()))

    @Test
    fun `nav and views carry the area and the language`() = runBlocking {
        coEvery { client.pluginGet(NOTIZEN_PLUGIN, "nav", any()) } returns ok(
            """{"area":"private","smart":{"myday":4,"planned":9},"lists":[{"id":3,"name":"Haushalt","area":"private","open":6}],
               "notes":{"total":2,"notebooks":[{"id":5,"name":"Rezepte","area":"private","count":2}]}}""",
        )
        val nav = (api.nav(NArea.PRIVATE) as NResult.Ok).value.first
        assertEquals(4, nav.smart["myday"])
        assertEquals(listOf("Haushalt"), nav.lists.map { it.name })
        assertEquals(listOf("Rezepte"), nav.notebooks.map { it.name })
        coVerify { client.pluginGet(NOTIZEN_PLUGIN, "nav", mapOf("area" to "private", "lang" to "de")) }

        coEvery { client.pluginGet(NOTIZEN_PLUGIN, "views/list%3A3", any()) } returns ok(
            """{"view":"list:3","title":"Haushalt","sections":[{"id":"open","title":"Offen","items":[{"type":"task","id":11,"listId":3,"title":"Blumen gießen"}]}]}""",
        )
        val view = (api.view("list:3", NArea.PRIVATE) as NResult.Ok).value.first
        assertEquals("Blumen gießen", view.sections.single().items.single().title)
    }

    @Test
    fun `a view name of its own is refused before any call`() = runBlocking {
        assertEquals(NResult.Failed("invalid", 0), api.view("../admin", NArea.PRIVATE))
        assertEquals(NResult.Failed("invalid", 0), api.view("list:abc", NArea.PRIVATE))
    }

    @Test
    fun `complete, move and the quick entry post their bodies`() = runBlocking {
        val done = slot<JsonObject>()
        coEvery { client.pluginPost(NOTIZEN_PLUGIN, "tasks/11/complete", capture(done), any()) } returns
            ok("""{"task":{"id":11,"title":"Blumen gießen","done":true},"next":{"id":12,"title":"Blumen gießen"}}""")
        val (task, next) = (api.completeTask(11, true) as NResult.Ok).value
        assertTrue(task.done)
        assertEquals(12L, next?.id)
        assertEquals("""{"done":true}""", done.captured.toString())

        val move = slot<JsonObject>()
        coEvery { client.pluginPost(NOTIZEN_PLUGIN, "tasks/11/move", capture(move), any()) } returns
            ok("""{"task":{"id":11,"title":"Blumen gießen","due":{"date":"2026-11-25"}}}""")
        val moved = (api.moveTask(11, NotizenJson.moveBody("tomorrow")) as NResult.Ok).value
        assertEquals("2026-11-25", moved.due?.date)
        assertEquals("""{"to":"tomorrow"}""", move.captured.toString())

        val line = slot<JsonObject>()
        coEvery { client.pluginPost(NOTIZEN_PLUGIN, "parse", capture(line), any()) } returns
            ok("""{"parsed":{"title":"Winterreifen abholen","date":"2026-11-25","time":"17:00","important":false},"attach":null,"place":null}""")
        val parsed = (api.parseLine("Winterreifen abholen morgen 17 Uhr", NArea.PRIVATE) as NResult.Ok).value
        assertEquals(NParsedLine("Winterreifen abholen", NDue("2026-11-25", "17:00"), false), parsed)
        assertEquals("""{"text":"Winterreifen abholen morgen 17 Uhr","area":"private"}""", line.captured.toString())
    }

    @Test
    fun `refusals, errors, no answer and no setup never throw`() = runBlocking {
        coEvery { client.pluginGet(NOTIZEN_PLUGIN, "nav", any()) } returns error(404, """{"ok":false,"code":"not_found"}""")
        val failed = api.nav(NArea.PRIVATE) as NResult.Failed
        assertEquals(404, failed.http)

        coEvery { client.pluginGet(NOTIZEN_PLUGIN, "me", any()) } returns ok("""{"ok":false,"code":"module_off"}""")
        assertEquals("module_off", (api.me() as NResult.Failed).code)

        coEvery { client.pluginGet(NOTIZEN_PLUGIN, "tasks/7", any()) } throws IOException("down")
        assertEquals(NResult.Offline, api.task(7))

        coEvery { client.pluginPost(NOTIZEN_PLUGIN, "parse", any(), any()) } returns ok("""{"parsed":{"title":""}}""")
        assertEquals(NotizenJson.CODE_INVALID, (api.parseLine("x", NArea.PRIVATE) as NResult.Failed).code)

        configured = false
        assertEquals(NResult.NotConfigured, api.nav(NArea.PRIVATE))
    }

    @Test
    fun `the place words of the outbox stay what they were`() {
        assertEquals("place/later", NotizenApi.PLACE_LATER)
        assertEquals("place/skip", NotizenApi.PLACE_SKIP)
        assertEquals("rotate", NotizenJson.TURN_ROTATE)
        assertEquals("first", NotizenJson.TURN_FIRST)
        assertNull(NotizenJson.turn("other"))
    }
}
