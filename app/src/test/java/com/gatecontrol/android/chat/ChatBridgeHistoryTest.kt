package com.gatecontrol.android.chat

import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.notify.model.PushMessage
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
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.Response

/**
 * Older history over a bridge (Messenger 1.17.0, apps-contract stage 5): the feature word, `bridges.history` and
 * `PUT bridges/history`, the phone warning and `clientOutdated` of a service (as server/bridges.js mine() sends them),
 * the placeholder `on_request` and `POST messages/:id/fetch`, `conversation.update { reason: "history" }`, and the
 * push of the topic `bridge` that opens „Verknüpfte Dienste“.
 */
class ChatBridgeHistoryTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    // ─── the feature word ───────────────────────────────────────────

    @Test
    fun `bridge_history is a word this app knows - no update hint for it`() {
        val f = ChatFeatures(tokens = setOf("linked_services", "bridge_chats", "bridge_media", "bridge_new_chat", "bridge_history"))
        assertTrue(ChatFeatureHint.unknown(f).isEmpty())
        assertFalse(ChatFeatureHint.show(f, null))
        // a word of a still newer messenger asks for an update as before
        assertEquals(setOf("bridge_calls"), ChatFeatureHint.unknown(ChatFeatures(tokens = setOf("bridge_history", "bridge_calls"))))
    }

    // ─── GET bridges ────────────────────────────────────────────────

    private val mine = """{"ok":true,"bridges":{"available":true,"location":"server","networks":[{"id":"whatsapp","state":"running","allowed":true,"lock":null,
        "consent":"2026-11-02T08:00:00.000Z","outdated":false,"version":"v0.12.5","wantVersion":"v0.12.5","clientOutdated":true,
        "phone":{"offline":true,"days":13,"leftDays":1},
        "logins":[{"id":"4915120000000","name":"+49 1512 0000000","state":"connected","since":"2026-11-02T08:00:00.000Z"}]}],
        "history":{"available":true,"days":30,"default":30,"options":[0,30,-1],"pending":412}}}"""

    @Test
    fun `GET bridges - the choice of older messages, the phone, a version WhatsApp turns away`() {
        val info = ChatBridgesJson.info(json(mine))!!
        assertEquals(ChatBridgeHistory(available = true, days = 30, default = 30, options = listOf(0, 30, -1), pending = 412), info.history)
        val n = info.networks.single()
        assertTrue(n.clientOutdated)
        assertFalse(n.outdated)
        assertTrue(ChatBridges.stale(n), "shown like outdated")
        assertEquals(ChatBridgePhone(offline = true, days = 13, leftDays = 1), n.phone)
        assertEquals(ChatBridgePhone(true, 13, 1), ChatBridges.phoneWarning(n))
        // the login stays connected: the row says linked, the warning comes under it
        assertEquals(ChatBridgeRow.LINKED, ChatBridges.row(n))
    }

    @Test
    fun `GET bridges - a server without history, switched off by the administrator, odd values`() {
        // Messenger before 1.17 or GateControl without gc.bridges version 5: no row
        val old = ChatBridgesJson.info(json("""{"ok":true,"bridges":{"available":true,"networks":[{"id":"whatsapp","state":"running","logins":[]}]}}"""))!!
        assertNull(old.history)
        assertNull(old.networks.single().phone)
        assertFalse(old.networks.single().clientOutdated)
        assertNull(ChatBridgesJson.info(json("""{"ok":true,"bridges":{"available":true,"networks":[],"history":null}}"""))!!.history)

        val off = ChatBridgesJson.history(json("""{"available":false,"days":30,"default":30,"options":[0,30,-1],"pending":0}"""))!!
        assertFalse(off.available)
        assertEquals(30, off.days)

        // what the app does not understand falls back to the default, never to a choice the server would refuse
        val odd = ChatBridgesJson.history(json("""{"available":true,"days":"many","default":-7,"options":["x",30,30,-1,99999],"pending":-3}"""))!!
        assertEquals(30, odd.days)
        assertEquals(listOf(30, -1), odd.options)
        assertEquals(0, odd.pending)
        assertEquals(ChatBridgeHistory.OPTIONS, ChatBridgesJson.history(json("""{"available":true,"days":0}"""))!!.options)
        assertEquals(0, ChatBridgesJson.history(json("""{"available":true,"days":0}"""))!!.days)
    }

    @Test
    fun `the phone warning - only while the link is not signed out`() {
        fun net(state: String, phone: ChatBridgePhone?) = ChatBridgeNetwork(
            ChatBridges.WHATSAPP, ChatBridges.NET_RUNNING, logins = listOf(ChatBridgeLogin("wa1", "", state, null)), phone = phone,
        )
        val away = ChatBridgePhone(true, 12, 2)
        assertNotNull(ChatBridges.phoneWarning(net(ChatBridges.LOGIN_CONNECTED, away)))
        assertNotNull(ChatBridges.phoneWarning(net(ChatBridges.LOGIN_ERROR, away)))
        assertNull(ChatBridges.phoneWarning(net(ChatBridges.LOGIN_LOGGED_OUT, away)), "signed out: the row says so already")
        assertNull(ChatBridges.phoneWarning(net(ChatBridges.LOGIN_CONNECTED, ChatBridgePhone(false, null, null))))
        assertNull(ChatBridges.phoneWarning(net(ChatBridges.LOGIN_CONNECTED, null)))
        assertNull(ChatBridges.phoneWarning(ChatBridgeNetwork(ChatBridges.WHATSAPP, ChatBridges.NET_RUNNING, phone = away)), "nothing linked")
        // not offline: days and leftDays are not read
        assertEquals(ChatBridgePhone(false, null, null), ChatBridgesJson.info(json("""{"bridges":{"available":true,"networks":[{"id":"whatsapp","state":"running","phone":{"offline":false,"days":3,"leftDays":11}}]}}"""))!!.networks.single().phone)
    }

    // ─── the calls ──────────────────────────────────────────────────

    private val client: ApiClient = mockk()
    private val media: ApiClient = mockk()

    private fun api(): ChatApi {
        val setup: SetupRepository = mockk()
        every { setup.getServerUrl() } returns "https://gc.example"
        every { setup.isConfigured() } returns true
        val provider: ApiClientProvider = mockk()
        every { provider.getClient(any()) } returns client
        every { provider.mediaClient(any()) } returns media
        val settings: SettingsRepository = mockk()
        every { settings.getLocale() } returns flowOf("de")
        return ChatApi(setup, provider, settings)
    }

    private fun refusal(http: Int, body: String): Response<JsonObject> = Response.error(http, body.toResponseBody("application/json".toMediaType()))

    @Test
    fun `PUT bridges history - the days in the body, the choice as it stands now, the refusals`() = runBlocking {
        val api = api()
        val body = slot<JsonObject>()
        val query = slot<Map<String, String>>()
        coEvery { client.pluginPut(MESSENGER_PLUGIN, "bridges/history", capture(body), capture(query)) } returns
            Response.success(json("""{"ok":true,"history":{"available":true,"days":-1,"default":30,"options":[0,30,-1],"pending":0}}"""))
        assertEquals(-1, api.bridgeHistory(-1).valueOrNull()!!.days)
        assertEquals("""{"days":-1}""", body.captured.toString())
        assertEquals("1", query.captured["bridged"])

        coEvery { client.pluginPut(MESSENGER_PLUGIN, "bridges/history", any(), any()) } returns refusal(403, """{"ok":false,"code":"feature_off"}""")
        assertEquals(ChatBridgeError.OFF, ChatBridges.error(api.bridgeHistory(0)))
        coEvery { client.pluginPut(MESSENGER_PLUGIN, "bridges/history", any(), any()) } returns refusal(400, """{"ok":false,"code":"invalid"}""")
        assertEquals(ChatBridgeError.INVALID, ChatBridges.error(api.bridgeHistory(30)))

        // a choice that is none never goes out
        assertEquals(ChatResult.Failed("invalid", 0), api.bridgeHistory(-5))
        coVerify(exactly = 3) { client.pluginPut(any(), any(), any(), any()) }
    }

    @Test
    fun `POST messages fetch - the file of the history is on its way`() = runBlocking {
        val api = api()
        val body = slot<JsonObject>()
        val query = slot<Map<String, String>>()
        coEvery { client.pluginPost(MESSENGER_PLUGIN, "messages/93/fetch", capture(body), capture(query)) } returns Response.success(
            json(
                """{"ok":true,"message":{"id":93,"conversationId":31,"senderId":null,"kind":"text","mine":false,"createdAt":"2025-03-01T09:58:00.000Z","text":null,"reactions":[],
                "bridge":{"sender":{"contactId":12,"name":"Kim"},"remote":false,"state":null,"reason":null,"placeholder":{"kind":"image","name":"IMG-2041.jpg","state":"loading","reason":null}}}}""",
            ),
        )
        val m = api.fetchBridged(93).valueOrNull()!!
        assertTrue(m.bridge!!.placeholder!!.loading)
        assertEquals("{}", body.captured.toString(), "an empty body")
        assertEquals("1", query.captured["bridged"])

        coEvery { client.pluginPost(MESSENGER_PLUGIN, "messages/94/fetch", any(), any()) } returns refusal(400, """{"ok":false,"code":"invalid"}""")
        assertEquals(ChatResult.Failed("invalid", 400), api.fetchBridged(94))
        coEvery { client.pluginPost(MESSENGER_PLUGIN, "messages/95/fetch", any(), any()) } returns refusal(404, """{"ok":false,"code":"not_found"}""")
        assertEquals(404, (api.fetchBridged(95) as ChatResult.Failed).http)
    }

    // ─── the placeholder on request ─────────────────────────────────

    private fun msg(state: String?, reason: String? = null) = ChatJson.message(
        json(
            """{"id":93,"conversationId":31,"senderId":null,"kind":"text","mine":false,"createdAt":"2025-03-01T09:58:00.000Z","text":null,"reactions":[],
            "bridge":{"sender":{"contactId":12,"name":"Kim"},"remote":false,"state":null,"reason":null,
            "placeholder":{"kind":"image","name":"IMG-2041.jpg","state":${state?.let { "\"$it\"" }},"reason":${reason?.let { "\"$it\"" }}}}}""",
        ),
    )!!

    @Test
    fun `on_request - read, stored and read again, then loading, then gone`() {
        val ask = msg("on_request")
        val ph = ask.bridge!!.placeholder!!
        assertTrue(ph.onRequest)
        assertFalse(ph.loading)
        assertFalse(ph.failed)
        assertNull(ph.reason)
        assertEquals(ask, ChatJson.message(ChatSerializer.message(ask)))
        assertFalse(msg("loading").bridge!!.placeholder!!.onRequest)
        // very old files WhatsApp no longer has
        val gone = msg("failed", "gone").bridge!!.placeholder!!
        assertTrue(gone.failed)
        assertEquals("gone", gone.reason)
        // a state of a still newer messenger reads like an older server's placeholder ("folgt")
        assertNull(msg("on_demand").bridge!!.placeholder!!.state)
    }

    // ─── live events ────────────────────────────────────────────────

    @Test
    fun `conversation update with reason history - the chat loads its messages again`() {
        val e = assertInstanceOf(
            ChatEvent.ConversationUpdate::class.java,
            ChatEvents.parse(MESSENGER_PLUGIN, "conversation.update", JsonParser.parseString("""{"conversationId":31,"reason":"history"}""")),
        )
        assertTrue(e.history)
        assertFalse(e.released)
        val other = ChatEvents.parse(MESSENGER_PLUGIN, "conversation.update", JsonParser.parseString("""{"conversationId":31,"reason":"avatar"}""")) as ChatEvent.ConversationUpdate
        assertFalse(other.history)
    }

    // ─── the push of the topic bridge ───────────────────────────────

    private fun push(topic: String, data: String?) = PushMessage(
        seq = 1, id = 77, eventId = "e", topic = topic, priority = "normal", title = "WhatsApp: Handy seit 12 Tagen nicht gesehen",
        body = "Öffne WhatsApp auf deinem Handy …", createdAt = null, expiresAt = null, collapseKey = "bridge-phone-whatsapp", silent = false,
        route = null, actions = emptyList(), dataJson = data,
    )

    @Test
    fun `the phone warning push opens Verknuepfte Dienste`() {
        assertEquals(ChatBridgePush.DEEP_LINK, ChatBridgePush.destination(push("plugin:gatecontrol-messenger:bridge", """{"deepLink":"bridges","bridge":"whatsapp"}""")))
        assertEquals(ChatBridgePush.DEEP_LINK, ChatBridgePush.destination(push("plugin:gatecontrol-messenger:bridge", null)), "the topic alone is enough")
        assertEquals(ChatBridgePush.DEEP_LINK, ChatBridgePush.destination(push("plugin:gatecontrol-messenger:other", """{"deepLink":"bridges"}""")))
        assertNull(ChatBridgePush.destination(push("plugin:gatecontrol-messenger:status", """{"deepLink":"status"}""")))
        assertNull(ChatBridgePush.destination(push("plugin:gatecontrol-drive:deadline", """{"deepLink":"bridges"}""")), "another plugin's word")
        assertNull(ChatBridgePush.destination(push("plugin:gatecontrol-messenger:status", "not json")))
    }
}
