package com.gatecontrol.android.chat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A new chat by number (Messenger 1.15.0 API, 1.16.0 `newChat`): how a number is read (+49 by default, leading 0 off,
 * `+`/`00` with their own code), the answers of `GET bridges`, `resolve` and `chats`, when the row shows, why the page
 * cannot check, how refusals read — and that no number shows in `toString()`.
 */
class ChatNewChatTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    @Test
    fun `numbers - German by default, a leading 0 goes, + and 00 bring their own country`() {
        val n = ChatNewChat.number("0151 23456789")
        assertEquals("+4915123456789", n.e164)
        assertEquals("+49 151 23456789", n.display)
        assertEquals("+4915123456789", ChatNewChat.number("151-234 567 89").e164)
        assertEquals("+4915123456789", ChatNewChat.number("+49 (151) 234/56789").e164)
        assertEquals("+43 664 1234567", ChatNewChat.number("0043 664 1234567").display)
        assertEquals("+43 664 1234567", ChatNewChat.number("0664 1234567", ChatCountry("AT", "43")).display)
        assertEquals("+12025550143", ChatNewChat.number("+1 202 555 0143").e164)
        // a code that is not in the list is shown as it is
        assertEquals("+8613812345678", ChatNewChat.number("+86 138 1234 5678").display)
    }

    @Test
    fun `what is no number - nothing is asked for it`() {
        assertEquals(ChatNumber.Invalid.EMPTY, ChatNewChat.number("  ").invalid)
        assertEquals(ChatNumber.Invalid.SHORT_MOBILE_DE, ChatNewChat.number("0151 234").invalid)
        assertEquals(ChatNumber.Invalid.SHORT, ChatNewChat.number("030 123").invalid)
        assertEquals(ChatNumber.Invalid.CHARS, ChatNewChat.number("0151 abc").invalid)
        assertEquals(ChatNumber.Invalid.CHARS, ChatNewChat.number("+").invalid)
        assertEquals(ChatNumber.Invalid.LONG, ChatNewChat.number("+49 151 234567890123").invalid)
        assertEquals(ChatNumber.Invalid.SHORT, ChatNewChat.number("+0049").invalid)
        assertFalse(ChatNewChat.number("0151 234").ok)
        // a landline is a number
        assertTrue(ChatNewChat.number("030 1234567").ok)
    }

    @Test
    fun `the country of a picked number`() {
        assertEquals("AT", ChatNewChat.countryOf("+43 664 1234567")?.iso)
        assertEquals("LU", ChatNewChat.countryOf("00352 621 123456")?.iso, "the longest code wins")
        assertNull(ChatNewChat.countryOf("0151 23456789"))
    }

    @Test
    fun `no number in toString`() {
        val n = ChatNewChat.number("0151 23456789")
        assertFalse(n.toString().contains("151"))
        assertFalse(ChatNumberLookup(true, "Lena Berger", 4).toString().contains("Lena"))
    }

    private fun net(newChat: String?, logins: String = """[{"id":"l1","name":"+49 170 1111111","state":"connected"}]""", state: String = "running") =
        ChatBridgesJson.info(
            json("""{"bridges":{"available":true,"networks":[{"id":"whatsapp","state":"$state","allowed":true,"logins":$logins${newChat?.let { ""","newChat":$it""" } ?: ""}}]}}"""),
        )!!

    @Test
    fun `newChat of GET bridges`() {
        val n = net("""{"available":false,"reason":"logged_out","limit":{"perHour":10,"left":7,"retryAfterS":0}}""").networks.single()
        assertEquals(ChatBridgeNewChat(false, "logged_out", 10, 7, 0), n.newChat)
        assertEquals("off", net("""{"available":false,"reason":"strange"}""").networks.single().newChat!!.reason)
        assertNull(net(null).networks.single().newChat)
    }

    @Test
    fun `the row - the word, a link (even signed out), not for a child with new chats off`() {
        val on = ChatFeatures(tokens = setOf("bridge_chats", "bridge_new_chat"))
        assertEquals(listOf("whatsapp"), ChatNewChat.networks(on, net("""{"available":true,"reason":null}""")).map { it.id })
        assertEquals(1, ChatNewChat.networks(on, net("""{"available":false,"reason":"logged_out"}""")).size)
        assertEquals(1, ChatNewChat.networks(on, net(null)).size, "a 1.15 server without newChat")
        assertTrue(ChatNewChat.networks(on, net("""{"available":false,"reason":"child_new_chat_off"}""")).isEmpty())
        assertTrue(ChatNewChat.networks(on, net(null, logins = "[]")).isEmpty(), "never linked: no row")
        assertTrue(ChatNewChat.networks(ChatFeatures(tokens = setOf("bridge_chats")), net(null)).isEmpty(), "without the word")
        assertTrue(ChatNewChat.networks(on, ChatBridgesInfo(available = false)).isEmpty())
    }

    @Test
    fun `why the page cannot check`() {
        fun b(nc: String?, logins: String = """[{"id":"l1","name":"x","state":"connected"}]""", state: String = "running") = ChatNewChat.blocked(net(nc, logins, state).networks.single())
        assertNull(b("""{"available":true}"""))
        assertEquals(ChatNewChat.Blocked.LOGGED_OUT, b("""{"available":false,"reason":"logged_out"}"""))
        assertEquals(ChatNewChat.Blocked.UNLINKED, b("""{"available":false,"reason":"unlinked"}"""))
        assertEquals(ChatNewChat.Blocked.UNREACHABLE, b("""{"available":false,"reason":"unreachable"}"""))
        assertEquals(ChatNewChat.Blocked.LOCKED, b("""{"available":false,"reason":"child_locked"}"""))
        assertEquals(ChatNewChat.Blocked.OFF, b("""{"available":false,"reason":"off"}"""))
        // 1.15: read from the link
        assertNull(b(null))
        assertEquals(ChatNewChat.Blocked.LOGGED_OUT, b(null, """[{"id":"l1","name":"x","state":"logged_out"}]"""))
        assertEquals(ChatNewChat.Blocked.UNREACHABLE, b(null, state = "unreachable"))
    }

    @Test
    fun `the answers of resolve and chats`() {
        assertEquals(ChatNumberLookup(true, "Lena Berger", 44), ChatNewChat.lookup(json("""{"ok":true,"found":true,"name":"Lena Berger","avatarUrl":null,"existing":{"conversationId":44}}""")))
        assertEquals(ChatNumberLookup(true, null, null), ChatNewChat.lookup(json("""{"ok":true,"found":true,"name":null}""")), "1.15: no existing")
        assertEquals(ChatNumberLookup(false, null, null), ChatNewChat.lookup(json("""{"ok":true,"found":false,"name":null,"existing":null}""")))
        assertNull(ChatNewChat.lookup(json("""{"ok":true}""")))
        val chat = ChatNewChat.chat(
            json(
                """{"ok":true,"created":true,"conversation":{"id":44,"kind":"direct","title":"Lena Berger","peer":null,"members":[{"userId":7,"name":"Anna"}],
                    "me":{"role":"member","notify":"all"},"bridge":{"network":"whatsapp","label":"WhatsApp","state":"ok","supports":["text"]},"can":{"send":true}}}""",
            ),
        )!!
        assertEquals(44L, chat.conversation.id)
        assertTrue(chat.created)
        assertEquals("whatsapp", chat.conversation.bridge!!.network)
        assertNull(ChatNewChat.chat(json("""{"ok":true}""")))
    }

    @Test
    fun `refusals`() {
        fun r(code: String, http: Int, reason: String? = null) = ChatNewChat.refusal(ChatResult.Failed(code, http, reason))
        assertEquals(ChatNewChat.Refusal.INVALID, r("invalid_number", 400))
        assertEquals(ChatNewChat.Refusal.RATE_LIMITED, r("rate_limited", 429))
        assertEquals(ChatNewChat.Refusal.NOT_FOUND, r("not_found", 404, "not_on_network"))
        assertEquals(ChatNewChat.Refusal.FAILED, r("not_found", 404))
        assertEquals(ChatNewChat.Refusal.LOGGED_OUT, r("bridge_unlinked", 409, "logged_out"))
        assertEquals(ChatNewChat.Refusal.LOGGED_OUT, r("bridge_logged_out", 409))
        assertEquals(ChatNewChat.Refusal.UNLINKED, r("bridge_unlinked", 409, "unlinked"))
        assertEquals(ChatNewChat.Refusal.UNREACHABLE, r("bridge_unreachable", 503))
        assertEquals(ChatNewChat.Refusal.LOCKED, r("bridge_locked", 403))
        assertEquals(ChatNewChat.Refusal.LOCKED, r("child_new_chat_off", 403))
        assertEquals(ChatNewChat.Refusal.OFF, r("feature_off", 403))
        assertEquals(ChatNewChat.Refusal.OFFLINE, ChatNewChat.refusal(ChatResult.Offline))
    }

    @Test
    fun `the paths`() {
        assertEquals("bridges/whatsapp/resolve", ChatNewChat.path("whatsapp", "resolve"))
        assertEquals("bridges/whatsapp/chats", ChatNewChat.path("whatsapp", "chats"))
        assertNull(ChatNewChat.path("icq", "chats"))
    }
}
