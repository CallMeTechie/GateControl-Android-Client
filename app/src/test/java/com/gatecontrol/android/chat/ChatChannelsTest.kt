package com.gatecontrol.android.chat

import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * Messenger 1.20 (Chats v2, Teil A): the feature word `bridge_channels`, `?channels=1`, `GET channels` and the
 * channels' numbers, `bridge.channel` / `readOnly` / `reactions`, `can.sendReason` / `can.react`, the format of a
 * message (`marks`) and addresses as links, the link preview, „Erneut versuchen“ (`placeholder.retry`) — as the
 * plugin sends them (server/bridgechannels.js) and through the cache.
 */
class ChatChannelsTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private fun convJson(
        id: Long = 41,
        title: String = "Sicherheit kompakt",
        channel: Boolean = true,
        send: Boolean = false,
        reason: String? = "channel",
        react: Boolean = true,
        reactions: String? = "own",
        unread: Int = 3,
        last: String = "2026-11-15T14:05:00.000Z",
        notify: String = "none",
    ) = """{"id":$id,"kind":"group","title":"$title","peer":null,"members":[{"userId":7,"name":"Anna","role":"member"}],
            "me":{"role":"member","notify":"$notify"},"muted":${notify == "none"},"unread":$unread,"updatedAt":"$last",
            "lastMessage":{"id":${id * 10},"conversationId":$id,"senderId":null,"kind":"text","mine":false,"createdAt":"$last","text":"Kritische Lücke","reactions":[],
              "bridge":{"sender":null,"channel":$channel,"remote":false,"state":null,"reason":null,"placeholder":null}},
            "bridge":{"network":"whatsapp","label":"WhatsApp","state":"ok","closed":false,"channel":$channel,"readOnly":${!send},${reactions?.let { "\"reactions\":\"$it\"," } ?: ""}"supports":["text","reaction"]},
            "can":{"send":$send,"sendReason":${reason?.let { "\"$it\"" }},"react":$react,"pin":true,"editInfo":false,"manageMembers":false,"leave":false,"setDisappearing":false}}"""

    private fun conv(vararg a: Pair<String, Any?>): ChatConversation {
        val m = a.toMap()
        return ChatJson.conversation(
            json(
                convJson(
                    id = (m["id"] as? Long) ?: 41,
                    title = (m["title"] as? String) ?: "Sicherheit kompakt",
                    channel = (m["channel"] as? Boolean) ?: true,
                    send = (m["send"] as? Boolean) ?: false,
                    reason = if (m.containsKey("reason")) m["reason"] as String? else "channel",
                    react = (m["react"] as? Boolean) ?: true,
                    reactions = if (m.containsKey("reactions")) m["reactions"] as String? else "own",
                    unread = (m["unread"] as? Int) ?: 3,
                    last = (m["last"] as? String) ?: "2026-11-15T14:05:00.000Z",
                    notify = (m["notify"] as? String) ?: "none",
                ),
            ),
        )!!
    }

    private fun msg(text: String, marks: String = "null", preview: String = "null", mine: Boolean = false, reactions: String = "[]", bridge: String = """{"sender":null,"channel":true,"remote":false,"state":null,"reason":null,"placeholder":null}""") =
        ChatJson.message(
            json(
                """{"id":410,"conversationId":41,"senderId":${if (mine) 7 else null},"kind":"text","mine":$mine,"createdAt":"2026-11-15T14:05:00.000Z","text":${com.google.gson.JsonPrimitive(text)},
                    "marks":$marks,"preview":$preview,"reactions":$reactions,"bridge":$bridge}""",
            ),
        )!!

    // ─── the feature word, the query ────────────────────────────────

    @Test
    fun `the word bridge_channels is known and keeps channels apart`() {
        assertTrue(ChatBridgeChat.FEATURE_CHANNELS in ChatFeatureHint.KNOWN)
        assertFalse(ChatFeatureHint.show(ChatFeatures(tokens = setOf("bridge_chats", "bridge_channels")), null), "no update hint for the word")
        assertTrue(ChatChannels.on(ChatFeatures(tokens = setOf("bridge_channels"))))
        assertFalse(ChatChannels.on(ChatFeatures(tokens = setOf("bridge_chats"))))
        assertFalse(ChatChannels.on(null))
        val ch = conv()
        val chat = conv("id" to 5L, "channel" to false, "send" to true, "reason" to null, "reactions" to null)
        assertFalse(ChatChannels.inList(ch, on = true), "with the word: the channel has its own page")
        assertTrue(ChatChannels.inList(ch, on = false), "without the word: a read-only chat in the list, as before")
        assertTrue(ChatChannels.inList(chat, on = true))
    }

    private val client: ApiClient = mockk()

    private fun api(): ChatApi {
        val setup: SetupRepository = mockk()
        every { setup.getServerUrl() } returns "https://gc.example"
        every { setup.isConfigured() } returns true
        val provider: ApiClientProvider = mockk()
        every { provider.getClient(any()) } returns client
        every { provider.mediaClient(any()) } returns client
        val settings: SettingsRepository = mockk()
        every { settings.getLocale() } returns flowOf("de")
        return ChatApi(setup, provider, settings)
    }

    @Test
    fun `with the word every call says channels=1, GET channels lists the page`() = runBlocking {
        val api = api()
        val q = slot<Map<String, String>>()
        val queries = mutableListOf<Map<String, String>>()
        coEvery { client.pluginGet(MESSENGER_PLUGIN, "conversations", capture(q)) } answers {
            queries += q.captured
            Response.success(json("""{"ok":true,"conversations":[],"removed":[],"channels":{"count":3,"unread":4},"serverTime":"2026-11-15T14:06:00.000Z"}"""))
        }
        // without the word: as before
        api.conversations(null)
        assertEquals(mapOf("lang" to "de", "bridged" to "1"), queries.last())
        api.channels = true
        val list = api.conversations(null).valueOrNull()!!
        assertEquals(mapOf("lang" to "de", "bridged" to "1", "channels" to "1"), queries.last())
        assertEquals(ChatChannelSummary(3, 4), list.channels)

        coEvery { client.pluginGet(MESSENGER_PLUGIN, "channels", capture(q)) } answers {
            queries += q.captured
            Response.success(json("""{"ok":true,"channels":[${convJson()},${convJson(id = 5, channel = false, send = true, reason = null)}],"removed":[40],"unread":3,"serverTime":"2026-11-15T14:06:00.000Z"}"""))
        }
        val page = api.channels("2026-11-15T14:00:00.000Z").valueOrNull()!!
        assertEquals(mapOf("since" to "2026-11-15T14:00:00.000Z", "lang" to "de", "bridged" to "1", "channels" to "1"), queries.last())
        assertEquals(listOf(41L), page.channels.map { it.id }, "only channels on this page")
        assertEquals(listOf(40L), page.removed)
        assertEquals(3, page.unread)

        coEvery { client.pluginPatch(MESSENGER_PLUGIN, "conversations/41", any(), capture(q)) } answers { queries += q.captured; Response.success(json("""{"ok":true,"conversation":${convJson(notify = "all")}}""")) }
        val bell = api.patchConversation(41, ChatPatches.notify(true)).valueOrNull()!!
        assertEquals("1", queries.last()["channels"], "the bell too")
        assertEquals("all", bell.conversation!!.me.notify)
        coEvery { client.pluginPut(MESSENGER_PLUGIN, "messages/410/reaction", any(), capture(q)) } answers {
            queries += q.captured
            Response.error(409, """{"ok":false,"code":"bridge_read_only","reason":"reactions_off"}""".toResponseBody("application/json".toMediaType()))
        }
        assertEquals(ChatResult.Failed(ChatBridgeChat.READ_ONLY, 409, ChatBridgeChat.READ_ONLY_REACTIONS), api.react(410, "👍"))
        coEvery { client.pluginDelete(MESSENGER_PLUGIN, "messages/410", capture(q)) } answers { queries += q.captured; Response.success(json("""{"ok":true}""")) }
        api.delete(410, forAll = false)
        assertTrue(queries.drop(1).all { it["channels"] == "1" && it["bridged"] == "1" })
    }

    // ─── conversation and message fields ────────────────────────────

    @Test
    fun `a channel - read only, muted, its own reaction, and the cache keeps it all`() {
        val c = conv()
        val b = c.bridge!!
        assertTrue(b.channel)
        assertTrue(b.readOnly)
        assertEquals(ChatBridgeChat.REACTIONS_OWN, b.reactions)
        assertEquals("group", c.kind)
        assertFalse(c.can.send)
        assertEquals(ChatBridgeChat.READ_ONLY_CHANNEL, c.can.sendReason)
        assertTrue(c.can.react)
        assertTrue(ChatBridgeChat.isChannel(c))
        assertEquals(ChatBridgeChat.ReadOnly.Channel, ChatBridgeChat.readOnly(c))
        assertEquals(c, ChatJson.conversation(ChatSerializer.conversation(c)), "through the cache")
        // a post of the channel: no sender
        val post = c.lastMessage!!
        assertTrue(post.bridge!!.channel)
        assertEquals("-", ChatBridgeChat.senderKey(post))
        assertEquals(post, ChatJson.message(ChatSerializer.message(post)))
    }

    @Test
    fun `a group where only the admins write - a bar, and reacting stays`() {
        val g = conv("channel" to false, "reason" to "admins_only", "reactions" to null)
        assertFalse(g.bridge!!.channel)
        assertTrue(g.bridge!!.readOnly)
        assertEquals(ChatBridgeChat.ReadOnly.AdminsOnly, ChatBridgeChat.readOnly(g))
        val m = msg("Treffen um 8", bridge = """{"sender":{"contactId":12,"name":"Kim"},"remote":false,"state":null,"reason":null,"placeholder":null}""")
        assertTrue(ChatBridgeChat.reacts(g, m), "reacting stays")
        assertFalse(ChatBridgeChat.actsOut(g, m), "no answer, no edit")
        // the admins open it again: as any chat
        val open = conv("channel" to false, "send" to true, "reason" to null, "reactions" to null)
        assertNull(ChatBridgeChat.readOnly(open))
        assertTrue(ChatBridgeChat.reacts(open, m))
        // sending fails for another reason (the bridge away): no read-only bar — the bars of the link say why
        assertNull(ChatBridgeChat.readOnly(conv("channel" to false, "reason" to null, "reactions" to null)))
        // an older server without `can.react`: as before
        val old = ChatJson.conversation(json(convJson(channel = false, send = true, reason = null).replace(""""react":true,""", "")))!!
        assertTrue(old.can.react)
        assertNull(old.can.sendReason)
    }

    @Test
    fun `reactions in a channel - only the own one, off after the bridge refused one`() {
        val c = conv()
        val post = msg("Neu", reactions = """[{"emoji":"👍","count":1,"userIds":[7]}]""")
        assertTrue(ChatBridgeChat.reacts(c, post))
        assertTrue(ChatBridgeChat.ownReactionNote(c, post, myId = 7), "the note under the own reaction")
        assertFalse(ChatBridgeChat.ownReactionNote(c, msg("Neu"), myId = 7), "no reaction: no note")
        assertFalse(ChatBridgeChat.ownReactionNote(conv("channel" to false, "reason" to "admins_only"), post, myId = 7), "only in a channel")
        val off = conv("react" to false, "reactions" to "off")
        assertEquals(ChatBridgeChat.REACTIONS_OFF, off.bridge!!.reactions)
        assertFalse(ChatBridgeChat.reacts(off, post), "the reactions leave the menu")
        assertEquals("bridge_read_only", ChatBridgeChat.READ_ONLY)
        assertTrue(ChatBridgeChat.READ_ONLY in ChatBridgeChat.REFUSALS, "never sent again by itself")
    }

    @Test
    fun `the channels' numbers - the TV symbol and its grey dot`() {
        val a = conv("id" to 41L, "unread" to 3, "last" to "2026-11-15T14:05:00.000Z")
        val b = conv("id" to 42L, "title" to "Stadtwerke Beispielstadt", "unread" to 0, "last" to "2026-11-15T11:40:00.000Z")
        val s = ChatChannels.summary(listOf(a, b))
        assertEquals(ChatChannelSummary(2, 3), s)
        assertTrue(ChatChannels.showTv(s))
        assertTrue(ChatChannels.dot(s))
        assertFalse(ChatChannels.dot(ChatChannelSummary(2, 0)))
        assertFalse(ChatChannels.showTv(ChatChannelSummary(0, 0)), "no channel with a post: no symbol")
        assertFalse(ChatChannels.showTv(null), "without the word: no symbol")
        assertEquals(ChatChannelSummary(0, 0), ChatChannels.summary(json("""{}""")))
        assertEquals(ChatChannelSummary(0, 0), ChatChannels.summary(json("""{"count":-1,"unread":"x"}""")))
        // the page: newest post first, the search on the name and the post
        assertEquals(listOf(41L, 42L), ChatChannels.rows(listOf(b, a), "").map { it.id })
        assertEquals(listOf(42L), ChatChannels.rows(listOf(b, a), "stadtwerke").map { it.id })
        assertEquals(listOf(41L, 42L), ChatChannels.rows(listOf(b, a), "lücke").map { it.id })
        assertEquals(listOf("WhatsApp"), ChatChannels.networks(listOf(a, b)))
    }

    // ─── format and links ───────────────────────────────────────────

    @Test
    fun `marks - parsed, checked, through the cache`() {
        val m = msg(
            "Jetzt patchen: Für die Router-Serie X200",
            marks = """[{"type":"bold","start":0,"end":14},{"type":"italic","start":36,"end":40},{"type":"link","start":15,"end":18,"url":"https://a.example/x"},
                {"type":"link","start":1,"end":2,"url":"javascript:alert(1)"},{"type":"blink","start":0,"end":3},{"type":"code","start":5,"end":5},{"type":"strike","start":-1,"end":3}]""",
        )
        assertEquals(
            listOf(ChatMark("bold", 0, 14), ChatMark("italic", 36, 40), ChatMark("link", 15, 18, "https://a.example/x")),
            m.marks,
            "unknown types, empty or negative ranges and a link that is not http(s) are left out",
        )
        assertEquals(m, ChatJson.message(ChatSerializer.message(m)))
        assertNull(msg("Hallo").marks)
        assertNull(msg("Hallo", marks = "[]").marks)
    }

    @Test
    fun `spans - marks nest and overlap, offsets are UTF-16, marks outside the text are dropped`() {
        val text = "fett und kursiv"
        val spans = ChatFormat.spans(text, listOf(ChatMark("bold", 0, 8), ChatMark("italic", 5, 15), ChatMark("strike", 3, 99)))
        assertEquals(listOf(0 to 5, 5 to 8, 8 to 15), spans.map { it.start to it.end })
        assertEquals(listOf(true, true, false), spans.map { it.bold })
        assertEquals(listOf(false, true, true), spans.map { it.italic })
        assertTrue(spans.none { it.strike }, "past the end: ignored")
        // an emoji is two UTF-16 units — the offsets count them as JavaScript does
        val emoji = "👍 super"
        val e = ChatFormat.spans(emoji, listOf(ChatMark("bold", 3, 8)))
        assertEquals("super", emoji.substring(e.last().start, e.last().end))
        assertTrue(e.last().bold)
        // the pieces together are the whole text
        assertEquals(text, spans.joinToString("") { text.substring(it.start, it.end) })
        assertEquals(emptyList<ChatSpan>(), ChatFormat.spans("", null))
        assertFalse(ChatFormat.rich("nur Text", null))
    }

    @Test
    fun `links - http(s) and www in every chat, punctuation at the end stays outside`() {
        val text = "Siehe https://sicherheit-kompakt.example/news/x200. Oder www.example.org/a?b=1), und (http://x.example)!"
        val links = ChatFormat.links(text)
        assertEquals(
            listOf("https://sicherheit-kompakt.example/news/x200", "https://www.example.org/a?b=1", "http://x.example"),
            links.map { it.url },
        )
        assertEquals("https://sicherheit-kompakt.example/news/x200", text.substring(links[0].start, links[0].end))
        assertEquals("www.example.org/a?b=1", text.substring(links[1].start, links[1].end))
        // spans: the plain text keeps its addresses as links, without marks
        val spans = ChatFormat.spans(text, null)
        assertEquals(3, spans.count { it.url != null })
        assertTrue(ChatFormat.rich(text, null))
        // a link mark wins over the address beneath
        val marked = ChatFormat.spans("Mehr: www.a.example", listOf(ChatMark("link", 6, 19, "https://b.example/")))
        assertEquals(listOf(null, "https://b.example/"), marked.map { it.url })
        // never anything but http(s), no name and password in the address
        assertNull(ChatFormat.safeUrl("javascript:alert(1)"))
        assertNull(ChatFormat.safeUrl("ftp://x.example"))
        assertNull(ChatFormat.safeUrl("https://user:pw@x.example/"))
        assertNull(ChatFormat.safeUrl("https://"))
        assertNull(ChatFormat.safeUrl("https://a b.example"))
        assertEquals("https://www.x.example", ChatFormat.safeUrl("www.x.example"))
        assertTrue(ChatFormat.links("mail@www.example.org und abc.www.example").all { it.url != null })
        assertEquals(emptyList<ChatSpan>(), ChatFormat.links("keine Adresse, nur www. und https://"))
    }

    // ─── link preview ───────────────────────────────────────────────

    @Test
    fun `the link preview - card, picture states, through the cache`() {
        val p = """{"url":"https://sicherheit-kompakt.example/news/x200","title":"Kritische Lücke in VPN-Router X200","description":"Angreifer können ohne Anmeldung Befehle ausführen.",
            "site":"sicherheit-kompakt.example","image":{"mediaId":"0f3c0f3c0f3c0f3c0f3c0f3c0f3c0f3c","url":null,"mime":"image/jpeg","w":640,"h":360,"state":null}}"""
        val m = msg("Jetzt patchen", preview = p)
        val pv = m.preview!!
        assertEquals("https://sicherheit-kompakt.example/news/x200", pv.url)
        assertEquals("Kritische Lücke in VPN-Router X200", pv.title)
        assertEquals("sicherheit-kompakt.example", pv.site)
        assertEquals(640, pv.image!!.w)
        assertTrue(pv.image!!.shown)
        assertFalse(pv.image!!.loading)
        assertEquals(m, ChatJson.message(ChatSerializer.message(m)))
        val loading = msg("x", preview = p.replace(""""state":null""", """"state":"loading""""))
        assertTrue(loading.preview!!.image!!.loading)
        assertFalse(msg("x", preview = p.replace(""""state":null""", """"state":"failed"""")).preview!!.image!!.shown, "failed: the card without a picture")
        assertFalse(msg("x", preview = p.replace(""""mediaId":"0f3c0f3c0f3c0f3c0f3c0f3c0f3c0f3c"""", """"mediaId":null""")).preview!!.image!!.shown)
        assertNull(msg("x", preview = """{"url":"javascript:alert(1)","title":"x"}""").preview, "no http(s): no card")
        assertNull(msg("x", preview = """{"title":"x"}""").preview)
        // only on a text
        val img = ChatJson.message(json("""{"id":1,"conversationId":41,"kind":"image","mine":false,"createdAt":"2026-11-15T14:05:00.000Z","preview":$p}"""))!!
        assertNull(img.preview)
    }

    // ─── „Erneut versuchen“ ──────────────────────────────────────────

    @Test
    fun `a failed file with retry gets the button, others not`() {
        fun ph(state: String, extra: String) = ChatJson.message(
            json(
                """{"id":7,"conversationId":31,"kind":"text","mine":false,"createdAt":"2026-11-15T14:05:00.000Z","text":null,"reactions":[],
                    "bridge":{"sender":{"contactId":12,"name":"Kim"},"remote":false,"state":null,"reason":null,"placeholder":{"kind":"image","name":"a.jpg","state":"$state","reason":"unavailable"$extra}}}""",
            ),
        )!!
        val retry = ph("failed", ""","retry":true""")
        assertTrue(retry.bridge!!.placeholder!!.retryable)
        assertEquals(retry, ChatJson.message(ChatSerializer.message(retry)), "through the cache")
        assertFalse(ph("failed", "").bridge!!.placeholder!!.retryable, "without retry: no button, the line says why")
        assertFalse(ph("loading", ""","retry":true""").bridge!!.placeholder!!.retryable, "retry only on a failed one")
    }
}
