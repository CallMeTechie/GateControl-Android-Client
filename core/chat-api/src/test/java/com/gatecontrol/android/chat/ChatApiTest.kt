package com.gatecontrol.android.chat

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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.Response
import java.io.IOException

/** The messenger's routes through the client plugin bridge as the phone app and GateControl TV call them. */
class ChatApiTest {

    private val client = mockk<ApiClient>()
    private val setup = mockk<SetupRepository> {
        every { getServerUrl() } returns "https://gc.example.com"
        every { isConfigured() } returns true
    }
    private val settings = mockk<SettingsRepository> { every { getLocale() } returns flowOf("de") }
    private val provider = mockk<ApiClientProvider> { every { getClient(any()) } returns client }
    private val api = ChatApi(setup, provider, settings)

    private fun ok(json: String): Response<JsonObject> = Response.success(JsonParser.parseString(json).asJsonObject)

    private fun error(code: Int, json: String): Response<JsonObject> =
        Response.error(code, json.toResponseBody("application/json".toMediaType()))

    private val message = """{"message":{"id":71,"conversationId":5,"senderId":1,"clientId":"tv0123456789abcdef","kind":"text",
        "mine":true,"createdAt":"2026-11-24T18:00:00Z","text":"Bin gleich da","status":"sent"}}"""

    @Test
    fun `every call carries bridged=1, reads too`() = runBlocking {
        coEvery { client.pluginGet(MESSENGER_PLUGIN, "conversations", any()) } returns ok("""{"conversations":[],"removed":[]}""")
        coEvery { client.pluginGet(MESSENGER_PLUGIN, "conversations/5/messages", any()) } returns ok("""{"messages":[],"hasMore":false}""")
        coEvery { client.pluginPost(MESSENGER_PLUGIN, "conversations/5/read", any(), any()) } returns ok("""{"unread":0}""")
        coEvery { client.pluginPut(MESSENGER_PLUGIN, "messages/71/reaction", any(), any()) } returns ok("""{"reactions":[]}""")
        api.conversations(null)
        api.messages(5, limit = 40)
        api.read(5, 71)
        api.react(71, "👍")
        coVerify { client.pluginGet(MESSENGER_PLUGIN, "conversations", mapOf("lang" to "de", "bridged" to "1")) }
        coVerify { client.pluginGet(MESSENGER_PLUGIN, "conversations/5/messages", mapOf("limit" to "40", "lang" to "de", "bridged" to "1")) }
        coVerify { client.pluginPost(MESSENGER_PLUGIN, "conversations/5/read", any(), mapOf("lang" to "de", "bridged" to "1")) }
        coVerify { client.pluginPut(MESSENGER_PLUGIN, "messages/71/reaction", any(), mapOf("bridged" to "1")) }
    }

    @Test
    fun `a text goes with its clientId and the reply`() = runBlocking {
        val body = slot<JsonObject>()
        coEvery { client.pluginPost(MESSENGER_PLUGIN, "conversations/5/messages", capture(body), any()) } returns ok(message)
        val r = api.send(5, ChatJson.sendBody("tv0123456789abcdef", ChatMessage.KIND_TEXT, "Bin gleich da", replyTo = 70))
        assertEquals(71L, (r as ChatResult.Ok).value.id)
        assertEquals("tv0123456789abcdef", body.captured.get("clientId").asString)
        assertEquals("text", body.captured.get("kind").asString)
        assertEquals(70L, body.captured.get("replyTo").asLong)
    }

    @Test
    fun `a bridge that takes only text answers 409 bridge_text_only`() = runBlocking {
        coEvery { client.pluginPost(MESSENGER_PLUGIN, "conversations/9/messages", any(), any()) } returns
            error(409, """{"ok":false,"code":"bridge_text_only","error":"Nur Text"}""")
        val r = api.send(9, ChatJson.sendBody("tv0123456789abcdef", ChatMessage.KIND_IMAGE, null, mediaId = "m0123456789abcdef"))
        assertEquals("bridge_text_only", (r as ChatResult.Failed).code)
        assertEquals(409, r.http)
        assertTrue(r.code in ChatBridgeChat.REFUSALS)
    }

    @Test
    fun `quick answers come from messages suggest`() = runBlocking {
        coEvery { client.pluginGet(MESSENGER_PLUGIN, "messages/71/suggest", any()) } returns ok("""{"items":["Ja","Bin unterwegs"],"dates":[]}""")
        val r = api.suggest(71)
        assertEquals(listOf("Ja", "Bin unterwegs"), (r as ChatResult.Ok).value.items)
        coVerify { client.pluginGet(MESSENGER_PLUGIN, "messages/71/suggest", mapOf("lang" to "de", "bridged" to "1")) }
    }

    @Test
    fun `no connection is Offline, never an exception`() = runBlocking {
        coEvery { client.pluginGet(MESSENGER_PLUGIN, "conversations", any()) } throws IOException("down")
        assertEquals(ChatResult.Offline, api.conversations(null))
        assertFalse(api.conversations(null) is ChatResult.Ok)
    }
}
