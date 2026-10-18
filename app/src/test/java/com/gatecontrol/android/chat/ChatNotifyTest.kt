package com.gatecontrol.android.chat

import com.gatecontrol.android.notify.model.PushMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Chat pushes (core contract "Chat-Benachrichtigungen"), share rules, file types and waveforms. */
class ChatNotifyTest {

    private val data = """
        {"route":"plg-gatecontrol-messenger","messageId":4711,
         "conversation":{"id":"12","title":"Familie","isGroup":true},
         "sender":{"id":7,"name":"Anna","avatarUrl":"/avatars/Xk3.webp"},
         "actions":[{"id":"reply","label":"Antworten","type":"reply","path":"conversations/12/messages"},
                    {"id":"mark_read","label":"Gelesen","type":"mark_read","path":"conversations/12/read","body":{"upTo":4711}}]}
    """.trimIndent()

    private fun push(dataJson: String? = data, seq: Long = 130, id: Long = 52, kind: String? = PushMessage.KIND_MESSAGE) = PushMessage(
        seq = seq, id = id, eventId = "plugin:gatecontrol-messenger:message", topic = MESSENGER_TOPIC, priority = "normal",
        title = "Anna", body = "Bist du schon unterwegs?", createdAt = "2026-10-17T18:02:11Z", expiresAt = null,
        collapseKey = "plugin:gatecontrol-messenger:chat:12", silent = false, route = MESSENGER_ROUTE, actions = emptyList(),
        dataJson = dataJson, kind = kind,
    )

    @Test
    fun `the payload names conversation, sender and the two actions`() {
        val p = ChatNotifyLogic.payload(push())!!
        assertEquals(12L, p.conversationId)
        assertEquals(4711L, p.messageId)
        assertEquals("Familie", p.conversationTitle)
        assertTrue(p.isGroup)
        assertEquals(7L, p.senderId)
        assertEquals("Anna", p.senderName)
        assertEquals("/avatars/Xk3.webp", p.senderAvatarUrl)
        assertEquals("conversations/12/messages", p.replyPath)
        assertEquals("Antworten", p.replyLabel)
        assertEquals("conversations/12/read", p.readPath)
        assertEquals("""{"upTo":4711}""", p.readBody)
    }

    @Test
    fun `foreign paths and a GateControl sender`() {
        val evil = data.replace("conversations/12/messages", "../../admin/users").replace("conversations/12/read", "conversations/99/read")
            .replace("\"id\":7", "\"id\":\"gatecontrol\"")
        val p = ChatNotifyLogic.payload(push(evil))!!
        assertNull(p.replyPath)
        assertNull(p.readPath, "another conversation's path")
        assertNull(p.senderId)
        assertNull(ChatNotifyLogic.payload(push(kind = null)), "not a chat push")
        assertNull(ChatNotifyLogic.payload(push(dataJson = null)))
    }

    @Test
    fun `lines - appended, replays not twice, at most seven, removal by push id`() {
        val p = ChatNotifyLogic.payload(push())!!
        var c: ChatNotifConversation? = null
        for (i in 1..9) c = ChatNotifyLogic.add(c, p, ChatNotifLine(100L + i, 50L + i, "7", "Anna", null, "m$i", i * 1000L))
        c = ChatNotifyLogic.add(c, p, ChatNotifLine(109, 59, "7", "Anna", null, "m9 again", 9000))
        assertEquals(ChatNotifyLogic.MAX_LINES, c!!.lines.size)
        assertEquals("m9 again", c.lines.last().text)
        assertEquals(listOf(103L, 104L, 105L, 106L, 107L, 108L, 109L), c.seqs)

        val withMine = ChatNotifyLogic.addMine(c, "Ich", "Bin unterwegs", 10_000)
        assertTrue(withMine.lines.last().mine)
        assertFalse(0L in withMine.seqs, "the own answer has no push to ack")

        val less = ChatNotifyLogic.remove(withMine, listOf(53L, 54L, 55L))!!
        assertEquals(listOf(106L, 107L, 108L, 109L), less.seqs)
        assertNull(ChatNotifyLogic.remove(less, listOf(56L, 57L, 58L, 59L)), "only the own answer left")
    }

    @Test
    fun `what is shown survives a restart`() {
        val p = ChatNotifyLogic.payload(push())!!
        val c = ChatNotifyLogic.add(null, p, ChatNotifLine(130, 52, "7", "Anna", "/avatars/Xk3.webp", "Hallo", 1000))
        val back = ChatNotifyLogic.fromJson(ChatNotifyLogic.toJson(mapOf(12L to c)))
        assertEquals(mapOf(12L to c), back)
        assertTrue(ChatNotifyLogic.fromJson("garbage").isEmpty())
        assertTrue(ChatNotifyLogic.fromJson(null).isEmpty())
    }

    // ─── share target, files, voice ─────────────────────────────────

    @Test
    fun `shared URIs - other apps' content only`() {
        val own = "com.gatecontrol.client"
        assertTrue(ChatShareRules.acceptable("content", "com.google.android.apps.photos.contentprovider", own))
        assertFalse(ChatShareRules.acceptable("file", null, own))
        assertFalse(ChatShareRules.acceptable("content", "com.gatecontrol.client.provider", own))
        assertFalse(ChatShareRules.acceptable("content", "10@com.gatecontrol.client.provider", own))
        assertFalse(ChatShareRules.acceptable("content", "", own))
        assertFalse(ChatShareRules.acceptable("http", "example.com", own))
    }

    @Test
    fun `file types - allowed as is, aliases, octet-stream fallback`() {
        val allowed = listOf("image/jpeg", "audio/mp4", "application/pdf", "application/octet-stream")
        assertEquals("application/pdf", ChatMime.sendable("application/pdf", allowed))
        assertEquals("image/jpeg", ChatMime.sendable("image/jpg", allowed))
        assertEquals("audio/mp4", ChatMime.sendable("audio/x-m4a", allowed))
        assertEquals("application/octet-stream", ChatMime.sendable("application/x-unknown", allowed))
        assertNull(ChatMime.sendable("application/x-unknown", listOf("image/jpeg")))
        assertEquals("text/plain", ChatMime.sendable("text/plain; charset=utf-8", emptyList()))
        assertEquals(ChatMessage.KIND_IMAGE, ChatMime.kindOf("image/png"))
        assertEquals(ChatMessage.KIND_FILE, ChatMime.kindOf("image/gif"))
        assertEquals(ChatMessage.KIND_FILE, ChatMime.kindOf("application/pdf"))
        assertEquals("a_b_.pdf", ChatMime.safeName("a/b:.pdf", "pdf"))
        assertEquals("datei.bin", ChatMime.safeName("..", "bin"))
    }

    @Test
    fun `waveform - at most 64 bars scaled to the loudest`() {
        val wave = VoiceWave.reduce((1..640).map { it * 10 }, 64)
        assertEquals(64, wave.size)
        assertEquals(255, wave.last())
        assertTrue(wave.all { it in 0..255 })
        assertEquals(listOf(0, 0), VoiceWave.reduce(listOf(0, 0), 64))
        assertTrue(VoiceWave.reduce(emptyList()).isEmpty())
        assertEquals(10, VoiceWave.bars(listOf(255, 0), 10).size)
        assertEquals("1:05", VoiceWave.clock(65_400))
    }
}
