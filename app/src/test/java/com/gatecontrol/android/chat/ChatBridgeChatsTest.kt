package com.gatecontrol.android.chat

import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.notify.model.PushMessage
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.mockk.coEvery
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.Response
import java.time.ZoneOffset

/**
 * Chats over a bridge (Messenger 1.14.0, README "Chats over a bridge"): the feature word, `conversation.bridge` and
 * `message.bridge` as the plugin sends them (server/bridgechats.js) and through the cache, the live events of
 * contacts, the ticks of an own message, what can be done with a message, the mark `?bridged=1` on every call, the
 * refusals that are never retried, and the push of a contact's message.
 */
class ChatBridgeChatsTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val me = """"ok":true,"user":{"id":7,"name":"Anna"},"prefs":{},"limits":{}"""
    private val supports = """["text","reply","reaction","edit","delete","read","typing"]"""

    /** A bridged conversation as `GET conversations?bridged=1` lists it. */
    private fun convJson(kind: String = "direct", title: String? = "Kim Muster", state: String = "ok", closed: Boolean = false, send: Boolean = true) =
        """{"id":31,"kind":"$kind","title":${title?.let { "\"$it\"" }},"peer":null,"avatarUrl":null,"members":[{"userId":7,"name":"Anna","role":"member"}],
            "me":{"role":"member","notify":"all","lastReadId":90,"lastDeliveredId":90},"unread":1,"readUpTo":88,"deliveredUpTo":89,
            "bridge":{"network":"whatsapp","label":"WhatsApp","state":"$state","closed":$closed,"supports":$supports},
            "can":{"send":$send,"pin":true,"editInfo":false,"manageMembers":false,"leave":false,"setDisappearing":false}}"""

    private fun conv(kind: String = "direct", title: String? = "Kim Muster", state: String = "ok", closed: Boolean = false, send: Boolean = true) =
        ChatJson.conversation(json(convJson(kind, title, state, closed, send)))!!

    private fun msgJson(id: Long, mine: Boolean, bridge: String, extra: String = "", text: String? = "Hallo") =
        """{"id":$id,"conversationId":31,"senderId":${if (mine) 7 else null},"kind":"text","via":${if (mine) null else "\"bridge:whatsapp\""},"mine":$mine,
            "createdAt":"2026-11-14T20:58:00.000Z","text":${text?.let { "\"$it\"" }},"reactions":[],"bridge":$bridge$extra}"""

    private fun msg(id: Long, mine: Boolean, bridge: String, extra: String = "", text: String? = "Hallo") = ChatJson.message(json(msgJson(id, mine, bridge, extra, text)))!!

    private val contact = """{"sender":{"contactId":12,"name":"Kim Muster"},"remote":false,"state":null,"reason":null,"placeholder":null}"""
    private fun own(state: String?, reason: String? = null, remote: Boolean = false) =
        """{"sender":null,"remote":$remote,"state":${state?.let { "\"$it\"" }},"reason":${reason?.let { "\"$it\"" }},"placeholder":null}"""

    // ─── the feature word ───────────────────────────────────────────

    @Test
    fun `bridge_chats is a word this app knows - bridges and e2e are not`() {
        val f = ChatJson.profile(json("""{$me,"features":{"direct":true,"bridges":false,"e2e":false,"linked_services":true,"bridge_chats":true}}"""))!!.features
        assertTrue(ChatBridgeChat.FEATURE in f.tokens)
        assertEquals(emptySet<String>(), ChatFeatureHint.unknown(f), "Messenger 1.14.0 with a bridged chat: no „neuere App-Version“")
        assertTrue(ChatBridgeChat.FEATURE in ChatFeatureHint.KNOWN)
        // listed as known although the app has nothing for them (an old slip): switched on, they ask for a newer app
        assertFalse("bridges" in ChatFeatureHint.KNOWN)
        assertFalse("e2e" in ChatFeatureHint.KNOWN)
        assertTrue(ChatFeatureHint.show(ChatJson.profile(json("""{$me,"features":{"e2e":true}}"""))!!.features, null))
        // an older messenger names neither: nothing changes
        val old = ChatJson.profile(json("""{$me,"features":{"direct":true,"bridges":false,"e2e":false,"linked_services":true}}"""))!!.features
        assertFalse(ChatBridgeChat.FEATURE in old.tokens)
        assertFalse(ChatFeatureHint.show(old, null))
    }

    @Test
    fun `whether the person has chats over a bridge - the word or a listed chat`() {
        val normal = conv().copy(bridge = null)
        assertFalse(ChatBridgeChat.any(ChatFeatures(), listOf(normal)))
        assertFalse(ChatBridgeChat.any(null, emptyList()))
        assertTrue(ChatBridgeChat.any(ChatFeatures(tokens = setOf("bridge_chats")), emptyList()), "the server says so")
        assertTrue(ChatBridgeChat.any(ChatFeatures(), listOf(normal, conv())), "the profile is a few minutes old, the list already holds one")
    }

    // ─── conversation.bridge ────────────────────────────────────────

    @Test
    fun `a bridged conversation - one member, no peer, the service, and through the cache`() {
        val c = conv()
        assertEquals(ChatConvBridge("whatsapp", "WhatsApp", ChatBridgeChat.OK, false, setOf("text", "reply", "reaction", "edit", "delete", "read", "typing")), c.bridge)
        assertNull(c.peer)
        assertEquals("Kim Muster", c.title)
        assertEquals(1, c.members.size)
        assertTrue(c.can.send)
        assertEquals(88L, c.readUpTo)
        assertEquals(c, ChatJson.conversation(ChatSerializer.conversation(c)), "stored and read again")
        // a chat inside GateControl: `bridge: null`, or no word of it from an older messenger
        assertNull(ChatJson.conversation(json("""{"id":1,"kind":"direct","bridge":null}"""))!!.bridge)
        assertNull(ChatJson.conversation(json("""{"id":1,"kind":"direct"}"""))!!.bridge)
        assertNull(ChatJson.conversation(json("""{"id":1,"kind":"direct","bridge":"whatsapp"}"""))!!.bridge, "not an object")
        assertNull(ChatJson.conversation(json("""{"id":1,"kind":"direct","bridge":{"network":"Whats App"}}"""))!!.bridge, "not a service's id")
    }

    @Test
    fun `the states of the link - and one this app does not know`() {
        for (s in listOf("ok", "unreachable", "logged_out", "unlinked", "locked", "off")) assertEquals(s, conv(state = s).bridge!!.state)
        val unknown = conv(state = "paused").bridge!!
        assertEquals(ChatBridgeChat.OFF, unknown.state, "nothing is offered that may not work")
        // the name falls back to the id, a group of the service stays a group, a contact without a name has no title
        assertEquals("signal", ChatJson.conversation(json("""{"id":2,"kind":"direct","bridge":{"network":"signal","state":"ok"}}"""))!!.bridge!!.label)
        assertTrue(conv(kind = "group", title = "Elternrunde 4b").isGroup)
        assertNull(conv(title = null).title)
    }

    @Test
    fun `the bar above the input - closed first, then the state, nothing while all is well`() {
        fun bar(state: String, closed: Boolean = false) = ChatBridgeChat.bar(conv(state = state, closed = closed).bridge!!)
        assertNull(bar("ok"))
        assertEquals(ChatBridgeChat.Bar.Closed, bar("ok", closed = true))
        assertEquals(ChatBridgeChat.Bar.Closed, bar("unreachable", closed = true))
        assertEquals(ChatBridgeChat.Bar.Unreachable, bar("unreachable"))
        assertEquals(ChatBridgeChat.Bar.LoggedOut, bar("logged_out"))
        assertEquals(ChatBridgeChat.Bar.Unlinked, bar("unlinked"))
        assertEquals(ChatBridgeChat.Bar.Locked, bar("locked"))
        assertEquals(ChatBridgeChat.Bar.Off, bar("off"))
    }

    @Test
    fun `the list - a filter per service and the mark at the picture`() {
        val wa = conv()
        val gc = conv().copy(id = 5, bridge = null)
        val other = conv().copy(id = 6, bridge = ChatConvBridge("signal", "Signal", ChatBridgeChat.OK))
        val unknown = conv().copy(id = 7, bridge = ChatConvBridge("icq", "ICQ", ChatBridgeChat.OK))
        val telegram = conv().copy(id = 8, bridge = ChatConvBridge("telegram", "Telegram", ChatBridgeChat.OK))
        assertEquals(emptyList<Pair<String, String>>(), ChatBridgeChat.networks(listOf(gc)), "no chat over a bridge: no filter")
        assertEquals(listOf("whatsapp" to "WhatsApp", "signal" to "Signal"), ChatBridgeChat.networks(listOf(wa, gc, other, wa.copy(id = 9))))
        assertEquals(listOf(wa, gc, other), listOf(wa, gc, other).filter { ChatBridgeChat.inNet(it, null) })
        assertEquals(listOf(gc), listOf(wa, gc, other).filter { ChatBridgeChat.inNet(it, ChatBridgeChat.NET_GC) })
        assertEquals(listOf(wa), listOf(wa, gc, other).filter { ChatBridgeChat.inNet(it, "whatsapp") })
        // Messenger 1.20: the logo of the service, no longer its letters; a service this app does not know: a speech bubble
        assertEquals(ChatBridgeChat.Logo.WhatsApp, ChatBridgeChat.logo(wa.bridge!!.network))
        // Messenger 1.23: Signal's own logo
        assertEquals(ChatBridgeChat.Logo.Signal, ChatBridgeChat.logo(other.bridge!!.network))
        assertEquals(ChatBridgeChat.Logo.Other, ChatBridgeChat.logo(unknown.bridge!!.network))
        // Messenger 1.24: Telegram's paper plane
        assertEquals(ChatBridgeChat.Logo.Telegram, ChatBridgeChat.logo(telegram.bridge!!.network))
        assertEquals(ChatBridgeChat.Logo.GateControl, ChatBridgeChat.logo(ChatBridgeChat.NET_GC))
        // text only: nothing can be shared into such a chat from another app
        assertFalse(ChatShortcutLogic.shareable(wa))
        assertTrue(ChatShortcutLogic.shareable(gc))
    }

    // ─── message.bridge ─────────────────────────────────────────────

    @Test
    fun `a contact's message - no account, the contact's name, through the cache`() {
        val m = msg(91, mine = false, bridge = contact)
        assertNull(m.senderId)
        assertFalse(m.mine)
        assertEquals(ChatMessageBridge(contactId = 12, senderName = "Kim Muster"), m.bridge)
        assertTrue(m.bridge!!.fromContact)
        assertNull(m.status, "ticks belong to own messages")
        assertEquals(m, ChatJson.message(ChatSerializer.message(m)))
        // a contact the service knows no name of
        val nameless = msg(92, mine = false, bridge = """{"sender":{"contactId":13,"name":null},"remote":false,"state":null,"reason":null,"placeholder":null}""")
        assertEquals(13L, nameless.bridge!!.contactId)
        assertNull(nameless.bridge!!.senderName)
        assertEquals(nameless, ChatJson.message(ChatSerializer.message(nameless)))
        // outside a bridged chat
        assertNull(msg(93, mine = false, bridge = "null").bridge)
        assertNull(ChatJson.message(json("""{"id":1,"conversationId":2,"kind":"text","createdAt":"2026-11-14T20:58:00.000Z"}"""))!!.bridge)
    }

    @Test
    fun `an own message - what became of it at the other side`() {
        for ((state, status) in listOf("pending" to "sending", "sent" to "sent", "delivered" to "delivered", "read" to "read", "failed" to "failed")) {
            val m = msg(94, mine = true, bridge = own(state, if (state == "failed") "network_error" else null), extra = ""","status":"$status"""")
            assertEquals(state, m.bridge!!.state)
            assertEquals(status, m.status)
            assertEquals(m, ChatJson.message(ChatSerializer.message(m)))
        }
        val failed = msg(95, mine = true, bridge = own("failed", "network_error"), extra = ""","status":"failed"""")
        assertTrue(failed.bridge!!.failed)
        assertTrue(failed.bridge!!.notOut)
        assertEquals("network_error", failed.bridge!!.reason)
        assertTrue(msg(96, mine = true, bridge = own("pending")).bridge!!.notOut)
        assertFalse(msg(97, mine = true, bridge = own("sent")).bridge!!.notOut)
        // written in the service's own app: an own message, not to be changed here
        val remote = msg(98, mine = true, bridge = own("sent", remote = true))
        assertTrue(remote.mine && remote.bridge!!.remote)
        // words this app does not know are dropped, not shown
        val odd = msg(99, mine = true, bridge = """{"sender":null,"remote":false,"state":"queued","reason":"Nope!","placeholder":null}""", extra = ""","status":"queued"""")
        assertNull(odd.bridge!!.state)
        assertNull(odd.bridge!!.reason)
        assertNull(odd.status)
    }

    @Test
    fun `the reasons a message did not go out - a word with a sentence, else bridge_error`() {
        for (r in listOf("not_running", "unreachable", "timeout", "network_error", "no_permission", "too_old", "unsupported", "not_handled", "bridge_unavailable", "chat_closed", "bridge_error")) {
            assertEquals(r, ChatBridgeChat.reason(r))
        }
        assertEquals("bridge_error", ChatBridgeChat.reason("m_forbidden"), "a word of the inner server")
        assertEquals("bridge_error", ChatBridgeChat.reason("bad_answer"))
        assertEquals("bridge_error", ChatBridgeChat.reason(null))
    }

    @Test
    fun `a placeholder - a file or kind that does not cross yet is never swallowed`() {
        val ph = """{"sender":{"contactId":12,"name":"Kim Muster"},"remote":false,"state":null,"reason":null,"placeholder":{"kind":"image","name":"grill-2025.jpg"}}"""
        val m = msg(100, mine = false, bridge = ph, text = "So sah es letztes Jahr aus")
        assertEquals(ChatBridgePlaceholder("image", "grill-2025.jpg"), m.bridge!!.placeholder)
        assertEquals(ChatMessage.KIND_TEXT, m.kind)
        assertEquals("So sah es letztes Jahr aus", m.text, "the caption")
        assertEquals(m, ChatJson.message(ChatSerializer.message(m)))
        for (k in ChatBridgeChat.PLACEHOLDERS) {
            val p = msg(101, mine = false, text = null, bridge = """{"sender":null,"remote":false,"state":null,"reason":null,"placeholder":{"kind":"$k","name":null}}""")
            assertEquals(ChatBridgePlaceholder(k, null), p.bridge!!.placeholder)
            assertEquals(p, ChatJson.message(ChatSerializer.message(p)))
        }
        // a kind of a later messenger: still a line that something arrived
        val later = msg(102, mine = false, text = null, bridge = """{"sender":null,"remote":false,"state":null,"reason":null,"placeholder":{"kind":"contact_card","name":"x"}}""")
        assertEquals(ChatBridgeChat.PH_UNSUPPORTED, later.bridge!!.placeholder!!.kind)
        // deleted for everyone: the tombstone keeps who wrote it
        val gone = ChatJson.message(ChatJsonPatch.tombstone(ChatSerializer.message(m), "2026-11-14T21:00:00.000Z"))!!
        assertTrue(gone.deleted)
        assertEquals(12L, gone.bridge!!.contactId)
    }

    @Test
    fun `a quote and the reactions of contacts`() {
        val m = msg(
            103, mine = true, bridge = own("read"),
            extra = ""","status":"read","replyTo":{"id":91,"senderId":null,"kind":"text","text":"Kommt ihr Samstag?","deleted":false,"missing":false,"contactId":12,"senderName":"Kim Muster"},
                "reactions":[{"emoji":"👍","count":2,"userIds":[7],"contactIds":[12]},{"emoji":"🎉","count":1,"userIds":[],"contactIds":[13]}]""",
        )
        // `reactions` is given twice above: the later one counts (JSON), so read it from a clean object too
        val r = ChatJson.reactions(JsonParser.parseString("""[{"emoji":"👍","count":2,"userIds":[7],"contactIds":[12]},{"emoji":"🎉","count":1,"userIds":[],"contactIds":[13]},{"emoji":"🙂","contactIds":[12,13]}]""").asJsonArray)
        assertEquals(listOf(ChatReaction("👍", 2, listOf(7), listOf(12)), ChatReaction("🎉", 1, emptyList(), listOf(13)), ChatReaction("🙂", 2, emptyList(), listOf(12, 13))), r)
        assertEquals(12L, m.replyTo!!.contactId)
        assertEquals("Kim Muster", m.replyTo!!.senderName)
        assertEquals(m, ChatJson.message(ChatSerializer.message(m)))
        // a live `reaction` event patched into the cache keeps the contacts
        val patched = ChatJson.message(ChatJsonPatch.reactions(ChatSerializer.message(m), r))!!
        assertEquals(r, patched.reactions)
        // a normal chat's quote and reactions carry none of it
        val plain = ChatJson.reactions(JsonParser.parseString("""[{"emoji":"👍","count":1,"userIds":[7]}]""").asJsonArray).single()
        assertEquals(emptyList<Long>(), plain.contactIds)
    }

    @Test
    fun `a note of the bridge itself is a system message with its own event`() {
        val m = ChatJson.message(
            json("""{"id":104,"conversationId":31,"senderId":null,"kind":"system","via":"bridge:whatsapp","mine":false,"createdAt":"2026-11-14T20:58:00.000Z",
                "text":"Die Verbindung wurde wiederhergestellt.","system":{"event":"bridge_notice"},"bridge":{"sender":null,"remote":false,"state":null,"reason":null,"placeholder":null}}"""),
        )!!
        assertTrue(m.isSystem)
        assertEquals(ChatBridgeChat.EVENT_NOTICE, m.system!!.event)
        assertFalse(m.bridge!!.fromContact)
        assertEquals("-", ChatBridgeChat.senderKey(m))
    }

    // ─── ticks, rows, actions ───────────────────────────────────────

    @Test
    fun `the tick of an own message - failed and on its way beat the receipts`() {
        fun status(state: String, status: String, readUpTo: Long? = null, deliveredUpTo: Long? = null) =
            ChatTimeline.status(msg(94, mine = true, bridge = own(state), extra = ""","status":"$status""""), readUpTo, deliveredUpTo)
        assertEquals(ChatMessage.STATUS_FAILED, status("failed", "failed"))
        assertEquals(ChatMessage.STATUS_FAILED, status("failed", "failed", readUpTo = 200), "a later message was read: this one still did not go out")
        assertEquals(ChatMessage.STATUS_SENDING, status("pending", "sending", deliveredUpTo = 200))
        assertEquals(ChatMessage.STATUS_SENT, status("sent", "sent"))
        assertEquals(ChatMessage.STATUS_DELIVERED, status("sent", "sent", deliveredUpTo = 94))
        assertEquals(ChatMessage.STATUS_READ, status("delivered", "delivered", readUpTo = 94))
        assertEquals(ChatMessage.STATUS_READ, status("read", "read"))
    }

    @Test
    fun `a group of the service - the sender shows when another contact writes`() {
        fun from(id: Long, contactId: Long, name: String) = msg(id, mine = false, bridge = """{"sender":{"contactId":$contactId,"name":"$name"},"remote":false,"state":null,"reason":null,"placeholder":null}""")
        val items = ChatTimeline.build(
            listOf(from(1, 12, "Kim"), from(2, 12, "Kim"), from(3, 13, "Robin"), msg(4, mine = true, bridge = own("sent")), from(5, 13, "Robin")),
            emptyList(), conv(kind = "group", title = "Elternrunde 4b"), ZoneOffset.UTC,
        ).filterIsInstance<TimelineItem.Message>()
        assertEquals(listOf(true, false, true, false, true), items.map { it.showSender }, "contacts are told apart although none of them is an account")
        assertEquals("c:12", ChatBridgeChat.senderKey(from(1, 12, "Kim")))
        assertEquals("u:7", ChatBridgeChat.senderKey(msg(4, mine = true, bridge = own("sent"))))
        // a direct chat shows no sender at all
        assertTrue(ChatTimeline.build(listOf(from(1, 12, "Kim")), emptyList(), conv(), ZoneOffset.UTC).filterIsInstance<TimelineItem.Message>().none { it.showSender })
    }

    @Test
    fun `what can be done with a message - only what the other side got, only while sending works`() {
        val c = conv()
        val theirs = msg(91, mine = false, bridge = contact)
        val sent = msg(94, mine = true, bridge = own("sent"))
        val failed = msg(95, mine = true, bridge = own("failed", "timeout"))
        val pending = msg(96, mine = true, bridge = own("pending"))
        assertTrue(ChatBridgeChat.actsOut(c, theirs))
        assertTrue(ChatBridgeChat.actsOut(c, sent))
        assertFalse(ChatBridgeChat.actsOut(c, failed), "no answer, reaction or edit on what never went out")
        assertFalse(ChatBridgeChat.actsOut(c, pending))
        val away = conv(state = "unreachable", send = false)
        assertFalse(ChatBridgeChat.actsOut(away, theirs), "nothing crosses while the bridge is away")
        assertFalse(ChatBridgeChat.actsOut(away, sent))
        // a chat inside GateControl is not touched by any of this
        assertTrue(ChatBridgeChat.actsOut(c.copy(bridge = null, can = c.can.copy(send = false)), sent.copy(bridge = null)))

        assertTrue(ChatBridgeChat.editable(sent))
        assertFalse(ChatBridgeChat.editable(msg(98, mine = true, bridge = own("sent", remote = true))), "written at the phone in the service's own app")
        assertFalse(ChatBridgeChat.editable(failed))
        assertFalse(ChatBridgeChat.editable(sent.copy(bridge = sent.bridge!!.copy(placeholder = ChatBridgePlaceholder("image", null)))))
        assertTrue(ChatBridgeChat.editable(sent.copy(bridge = null)))
    }

    // ─── live events ────────────────────────────────────────────────

    @Test
    fun `typing of contacts, read by the other side, a new message over the bridge`() {
        val now = 1_800_000_000_000L
        val until = java.time.Instant.ofEpochMilli(now + 6_000).toString()
        val t = ChatEvents.parse(MESSENGER_PLUGIN, "typing", json("""{"conversationId":31,"userId":null,"contacts":[{"contactId":12,"name":"Kim Muster"},{"contactId":13,"name":null},{"name":"x"}],"until":"$until"}"""), now)
        assertEquals(ChatEvent.ContactTyping(31, listOf(ChatEvent.TypingContact(12, "Kim Muster"), ChatEvent.TypingContact(13, null)), now + 6_000), t)
        val stop = ChatEvents.parse(MESSENGER_PLUGIN, "typing", json("""{"conversationId":31,"userId":null,"contacts":[],"until":null}"""), now)
        assertEquals(ChatEvent.ContactTyping(31, emptyList(), null), stop)
        // an account's typing is what it was; a typing event that names nobody is none
        assertEquals(ChatEvent.Typing(31, 8, now + 6_000), ChatEvents.parse(MESSENGER_PLUGIN, "typing", json("""{"conversationId":31,"userId":8,"until":"$until"}"""), now))
        assertNull(ChatEvents.parse(MESSENGER_PLUGIN, "typing", json("""{"conversationId":31,"userId":null,"until":"$until"}"""), now))

        val read = ChatEvents.parse(MESSENGER_PLUGIN, "read", json("""{"conversationId":31,"userId":null,"lastReadId":94,"lastDeliveredId":94,"readUpTo":94,"deliveredUpTo":95}""")) as ChatEvent.Read
        assertEquals(0L, read.userId)
        assertFalse(read.self)
        assertEquals(ChatEvent.UpTo(94, 95), read.upTo)
        // the ticks move, and no receipt of a "user 0" is invented
        val patched = ChatJson.conversation(ChatJsonPatch.readEvent(ChatSerializer.conversation(conv()), read))!!
        assertEquals(94L, patched.readUpTo)
        assertEquals(95L, patched.deliveredUpTo)
        assertEquals(emptyList<ChatReceipt>(), patched.receipts)
        // without the marks a nameless `read` says nothing
        assertNull(ChatEvents.parse(MESSENGER_PLUGIN, "read", json("""{"conversationId":31,"userId":null,"lastReadId":94}""")))

        val new = ChatEvents.parse(MESSENGER_PLUGIN, "message.new", json("""{"conversationId":31,"messageId":91,"senderId":null,"clientId":null,"kind":"text","bridge":"whatsapp"}""")) as ChatEvent.MessageNew
        assertEquals("whatsapp", new.bridge)
        assertNull((ChatEvents.parse(MESSENGER_PLUGIN, "message.new", json("""{"conversationId":3,"messageId":9,"senderId":8}""")) as ChatEvent.MessageNew).bridge)
        // a state change of an own message and a contact's reaction: the message is fetched again
        assertEquals(ChatEvent.MessageUpdate(31, 94), ChatEvents.parse(MESSENGER_PLUGIN, "message.update", json("""{"conversationId":31,"messageId":94,"status":"delivered"}""")))
        assertEquals(ChatEvent.MessageUpdate(31, 94), ChatEvents.parse(MESSENGER_PLUGIN, "message.update", json("""{"conversationId":31,"messageId":94,"reactions":true}""")))
        assertEquals(setOf("bridge"), (ChatEvents.parse(MESSENGER_PLUGIN, "conversation.update", json("""{"conversationId":31,"reason":"bridge"}""")) as ChatEvent.ConversationUpdate).reasons)
    }

    @Test
    fun `who of the contacts types right now`() {
        val typing = mapOf(31L to ChatContactTyping(listOf("Kim Muster", null), 5_000L))
        assertEquals(listOf("Kim Muster", null), ChatTimeline.contactsTypingNow(typing, 31, 4_999))
        assertNull(ChatTimeline.contactsTypingNow(typing, 31, 5_000), "over")
        assertNull(ChatTimeline.contactsTypingNow(typing, 32, 0))
        assertNull(ChatTimeline.contactsTypingNow(mapOf(31L to ChatContactTyping(emptyList(), 5_000L)), 31, 0))
    }

    // ─── the outbox: nothing is queued at the other side ────────────

    @Test
    fun `a refusal of the bridge is never sent again by itself`() {
        for ((code, http) in listOf("bridge_unreachable" to 503, "bridge_failed" to 502, "bridge_unlinked" to 409, "bridge_off" to 409, "bridge_locked" to 403, "bridge_text_only" to 409, "bridge_media_type" to 409)) {
            assertEquals(OutboxStep.GiveUp(code), ChatOutboxLogic.step(ChatResult.Failed(code, http, "not_running"), 1, hadUpload = false, nowMs = 0), code)
        }
        // any other 503 of the server is still tried again
        assertInstanceOf(OutboxStep.Retry::class.java, ChatOutboxLogic.step(ChatResult.Failed("http_503", 503), 1, hadUpload = false, nowMs = 0))
        assertEquals(ChatBridgeChat.REFUSALS, setOf("bridge_text_only", "bridge_unlinked", "bridge_off", "bridge_unreachable", "bridge_failed", "bridge_locked", "bridge_media_type", "bridge_read_only"))
    }

    // ─── the API: the mark on every call ────────────────────────────

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

    private fun refusal(http: Int, body: String): Response<JsonObject> = Response.error(http, body.toResponseBody("application/json".toMediaType()))

    @Test
    fun `every call says that this app knows bridged chats`() = runBlocking {
        val api = api()
        val q = slot<Map<String, String>>()
        val queries = mutableListOf<Map<String, String>>()
        val one = json("""{"ok":true,"message":${msgJson(94, true, own("sent"), ""","status":"sent"""")}}""")

        coEvery { client.pluginGet(MESSENGER_PLUGIN, "conversations", capture(q)) } answers { queries += q.captured; Response.success(json("""{"ok":true,"conversations":[${convJson()}],"removed":[]}""")) }
        val list = api.conversations("2026-11-14T20:00:00.000Z").valueOrNull()!!
        assertEquals("whatsapp", list.conversations.single().bridge!!.network)
        assertEquals(mapOf("since" to "2026-11-14T20:00:00.000Z", "lang" to "de", "bridged" to "1"), queries.last())

        coEvery { client.pluginGet(MESSENGER_PLUGIN, "conversations/31", capture(q)) } answers { queries += q.captured; Response.success(json("""{"ok":true,"conversation":${convJson()}}""")) }
        api.conversation(31)
        coEvery { client.pluginGet(MESSENGER_PLUGIN, "conversations/31/messages", capture(q)) } answers { queries += q.captured; Response.success(json("""{"ok":true,"messages":[],"hasMore":false}""")) }
        api.messages(31, after = 90)
        coEvery { client.pluginGet(MESSENGER_PLUGIN, "me", capture(q)) } answers { queries += q.captured; Response.success(json("{$me}")) }
        api.me()

        val body = slot<JsonObject>()
        coEvery { client.pluginPost(MESSENGER_PLUGIN, "conversations/31/messages", capture(body), capture(q)) } answers { queries += q.captured; Response.success(one) }
        assertEquals("sent", api.send(31, ChatJson.sendBody("0123456789abcdef", ChatMessage.KIND_TEXT, "Gern!", replyTo = 91)).valueOrNull()!!.bridge!!.state)
        assertEquals("""{"clientId":"0123456789abcdef","kind":"text","text":"Gern!","replyTo":91}""", body.captured.toString(), "a text with its answer mark, nothing else")
        coEvery { client.pluginPost(MESSENGER_PLUGIN, "conversations/31/read", any(), capture(q)) } answers { queries += q.captured; Response.success(json("""{"ok":true,"unread":0}""")) }
        api.read(31, 94)
        coEvery { client.pluginPost(MESSENGER_PLUGIN, "conversations/31/typing", any(), capture(q)) } answers { queries += q.captured; Response.success(json("""{"ok":true,"until":null}""")) }
        api.typing(31)
        coEvery { client.pluginPost(MESSENGER_PLUGIN, "messages/94/resend", capture(body), capture(q)) } answers { queries += q.captured; Response.success(one) }
        assertEquals(94L, api.resend(94).valueOrNull()!!.id)
        assertEquals("{}", body.captured.toString())
        coEvery { client.pluginPatch(MESSENGER_PLUGIN, "messages/94", any(), capture(q)) } answers { queries += q.captured; Response.success(one) }
        api.edit(94, "Gern.")
        coEvery { client.pluginPatch(MESSENGER_PLUGIN, "conversations/31", any(), capture(q)) } answers { queries += q.captured; Response.success(json("""{"ok":true,"conversation":${convJson()}}""")) }
        api.patchConversation(31, ChatPatches.notify(false))
        coEvery { client.pluginPut(MESSENGER_PLUGIN, "messages/94/reaction", any(), capture(q)) } answers { queries += q.captured; Response.success(json("""{"ok":true,"reactions":[{"emoji":"👍","count":2,"userIds":[7],"contactIds":[12]}]}""")) }
        assertEquals(listOf(12L), api.react(94, "👍").valueOrNull()!!.single().contactIds)
        coEvery { client.pluginDelete(MESSENGER_PLUGIN, "messages/94", capture(q)) } answers { queries += q.captured; Response.success(json("""{"ok":true}""")) }
        api.delete(94, forAll = true)
        assertEquals("all", queries.last()["for"])
        // the push's buttons use the same calls: the mark travels in the query, as good as `bridged: true` in the body
        coEvery { client.pluginPost(MESSENGER_PLUGIN, "conversations/31/read", any(), capture(q)) } answers { queries += q.captured; Response.success(json("""{"ok":true,"unread":0}""")) }
        api.readPath("conversations/31/read", ChatJson.readBody(94))

        assertEquals(13, queries.size)
        assertTrue(queries.all { it["bridged"] == "1" }, "without the mark a bridged chat answers 404 and is missing from the list")
    }

    @Test
    fun `the server's refusals keep their word and reason`() = runBlocking {
        val api = api()
        coEvery { client.pluginPost(MESSENGER_PLUGIN, "conversations/31/messages", any(), any()) } returns refusal(409, """{"ok":false,"code":"bridge_text_only","reason":"kind"}""")
        assertEquals(ChatResult.Failed("bridge_text_only", 409, "kind"), api.send(31, ChatJson.sendBody("0123456789abcdef", ChatMessage.KIND_IMAGE, null, mediaId = "0123456789abcdef0123456789abcdef")))
        coEvery { client.pluginPost(MESSENGER_PLUGIN, "messages/95/resend", any(), any()) } returns refusal(409, """{"ok":false,"code":"bridge_unlinked","reason":"logged_out"}""")
        assertEquals(ChatResult.Failed("bridge_unlinked", 409, "logged_out"), api.resend(95))
        coEvery { client.pluginPatch(MESSENGER_PLUGIN, "messages/94", any(), any()) } returns refusal(503, """{"ok":false,"code":"bridge_unreachable","reason":"not_running"}""")
        assertEquals(ChatResult.Failed("bridge_unreachable", 503, "not_running"), api.edit(94, "x"))
        // a message the bridge did not take is still an answer: stored, shown with its reason
        coEvery { client.pluginPost(MESSENGER_PLUGIN, "messages/96/resend", any(), any()) } returns
            Response.success(json("""{"ok":true,"message":${msgJson(96, true, own("failed", "network_error"), ""","status":"failed"""")}}"""))
        val again = api.resend(96).valueOrNull()!!
        assertTrue(again.bridge!!.failed)
        assertEquals("network_error", again.bridge!!.reason)
        // an app that does not send the mark (or an older messenger without the route): 404
        coEvery { client.pluginGet(MESSENGER_PLUGIN, "conversations/32", any()) } returns refusal(404, """{"ok":false,"code":"not_found"}""")
        assertEquals(ChatResult.Failed("not_found", 404), api.conversation(32))
    }

    // ─── push ───────────────────────────────────────────────────────

    private fun push(title: String, body: String, data: String) = PushMessage(
        seq = 140, id = 60, eventId = "plugin:gatecontrol-messenger:message", topic = MESSENGER_TOPIC, priority = "normal",
        title = title, body = body, createdAt = "2026-11-14T20:58:11Z", expiresAt = null,
        collapseKey = "plugin:gatecontrol-messenger:chat:31", silent = false, route = MESSENGER_ROUTE, actions = emptyList(),
        dataJson = data, kind = PushMessage.KIND_MESSAGE,
    )

    @Test
    fun `the push of a contact's message - the contact is a person of its own, the buttons stay`() {
        fun data(contact: String, group: Boolean) =
            """{"messageId":91,"conversationId":31,"bridge":"whatsapp",
                "conversation":{"id":"31","title":${if (group) "\"Elternrunde 4b\"" else "null"},"isGroup":$group},
                "sender":{"id":"$contact","name":"Kim Muster","avatarUrl":null},
                "actions":[{"type":"reply","label":"Antworten","path":"conversations/31/messages","body":{"bridged":true}},
                           {"type":"mark_read","label":"Gelesen","path":"conversations/31/read","body":{"upTo":91,"bridged":true}}]}"""
        // with preview: "Kim Muster · WhatsApp" and the text
        val p = ChatNotifyLogic.payload(push("Kim Muster · WhatsApp", "Kommt ihr Samstag?", data("ext-12", group = false)))!!
        assertEquals(31L, p.conversationId)
        assertEquals("whatsapp", p.bridge)
        assertNull(p.senderId, "a contact is no account")
        assertEquals("ext-12", p.senderKey)
        assertEquals("Kim Muster", p.senderName)
        assertEquals("conversations/31/messages", p.replyPath)
        assertEquals("conversations/31/read", p.readPath)
        assertEquals("""{"upTo":91}""", p.readBody, "the mark of a bridged chat goes with every call (?bridged=1)")
        // two contacts of a group are two people in the notification
        val a = ChatNotifyLogic.payload(push("Kim · WhatsApp", "a", data("ext-12", group = true)))!!
        val b = ChatNotifyLogic.payload(push("Robin · WhatsApp", "b", data("ext-13", group = true)))!!
        assertTrue(a.isGroup)
        assertEquals("Elternrunde 4b", a.conversationTitle)
        assertTrue(a.senderKey != b.senderKey)
        // a note of the bridge itself, and anything that is not such an id
        assertNull(ChatNotifyLogic.payload(push("WhatsApp", "x", data("gatecontrol", group = false)))!!.senderKey)
        assertNull(ChatNotifyLogic.payload(push("x", "x", data("ext-0", group = false)))!!.senderKey)
        assertNull(ChatNotifyLogic.payload(push("x", "x", data("ext-12/../1", group = false)))!!.senderKey)
        // a push of a chat inside GateControl has neither
        val plain = ChatNotifyLogic.payload(push("Anna", "Hi", """{"messageId":4711,"conversation":{"id":"12","isGroup":false},"sender":{"id":7,"name":"Anna"}}"""))!!
        assertNull(plain.bridge)
        assertNull(plain.senderKey)
        assertEquals(7L, plain.senderId)
    }
}
