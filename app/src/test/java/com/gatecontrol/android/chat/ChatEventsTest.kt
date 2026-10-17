package com.gatecontrol.android.chat

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** The messenger's live events (README "Live events") as the push stream delivers them. */
class ChatEventsTest {

    private fun parse(type: String, data: String, plugin: String = MESSENGER_PLUGIN, now: Long = NOW) =
        ChatEvents.parse(plugin, type, JsonParser.parseString(data), now)

    @Test
    fun `message new with clientId (outbox match) and system sender`() {
        val e = assertInstanceOf(ChatEvent.MessageNew::class.java, parse("message.new", """{"conversationId":12,"messageId":4711,"senderId":7,"clientId":"abcdefgh12","kind":"text","createdAt":"2026-10-17T10:00:00.000Z"}"""))
        assertEquals(12L, e.conversationId)
        assertEquals(4711L, e.messageId)
        assertEquals(7L, e.senderId)
        assertEquals("abcdefgh12", e.clientId)
        val sys = assertInstanceOf(ChatEvent.MessageNew::class.java, parse("message.new", """{"conversationId":"12","messageId":5,"senderId":null,"clientId":"bad id"}"""))
        assertNull(sys.senderId)
        assertNull(sys.clientId)
    }

    @Test
    fun `message delete keeps the reason - only deleted is a tombstone`() {
        val del = assertInstanceOf(ChatEvent.MessageDelete::class.java, parse("message.delete", """{"conversationId":12,"messageIds":[1,2,2,"x"],"reason":"deleted"}"""))
        assertEquals(listOf(1L, 2L), del.messageIds)
        assertTrue(del.tombstone)
        val hidden = assertInstanceOf(ChatEvent.MessageDelete::class.java, parse("message.delete", """{"conversationId":12,"messageIds":[3],"reason":"expired"}"""))
        assertFalse(hidden.tombstone)
        assertNull(parse("message.delete", """{"conversationId":12,"messageIds":[]}"""))
    }

    @Test
    fun `reaction replaces the reactions, update names the message`() {
        val r = assertInstanceOf(ChatEvent.Reaction::class.java, parse("reaction", """{"conversationId":12,"messageId":9,"userId":7,"emoji":"👍","reactions":[{"emoji":"👍","count":1,"userIds":[7]}]}"""))
        assertEquals(listOf("👍"), r.reactions.map { it.emoji })
        val u = assertInstanceOf(ChatEvent.MessageUpdate::class.java, parse("message.update", """{"conversationId":12,"messageId":9,"editedAt":"2026-10-17T10:00:00.000Z"}"""))
        assertEquals(9L, u.messageId)
    }

    @Test
    fun `read of another person and of the own devices`() {
        val other = assertInstanceOf(ChatEvent.Read::class.java, parse("read", """{"conversationId":12,"userId":8,"lastReadId":null,"lastDeliveredId":40}"""))
        assertFalse(other.self)
        assertNull(other.lastReadId)
        assertEquals(40L, other.lastDeliveredId)
        val self = assertInstanceOf(ChatEvent.Read::class.java, parse("read", """{"conversationId":12,"userId":7,"lastReadId":41,"lastDeliveredId":41,"self":true,"unread":0}"""))
        assertTrue(self.self)
        assertEquals(0, self.unread)
    }

    @Test
    fun `typing shows until the server's time, at most a few seconds, null stops`() {
        val until = Instant.ofEpochMilli(NOW + 6_000).toString()
        val t = assertInstanceOf(ChatEvent.Typing::class.java, parse("typing", """{"conversationId":12,"userId":8,"until":"$until"}"""))
        assertEquals(NOW + 6_000, t.untilMs)
        val far = Instant.ofEpochMilli(NOW + 3_600_000).toString()
        assertEquals(NOW + ChatEvents.TYPING_MAX_MS, (parse("typing", """{"conversationId":12,"userId":8,"until":"$far"}""") as ChatEvent.Typing).untilMs)
        assertNull((parse("typing", """{"conversationId":12,"userId":8,"until":null}""") as ChatEvent.Typing).untilMs)
    }

    @Test
    fun `conversation update reasons and removal`() {
        val u = assertInstanceOf(ChatEvent.ConversationUpdate::class.java, parse("conversation.update", """{"conversationId":12,"reason":"members,title,BAD REASON"}"""))
        assertEquals(setOf("members", "title"), u.reasons)
        assertInstanceOf(ChatEvent.ConversationRemove::class.java, parse("conversation.remove", """{"conversationId":12}"""))
    }

    @Test
    fun `other plugins, unknown types and malformed data are ignored`() {
        assertNull(parse("message.new", """{"conversationId":12,"messageId":1}""", plugin = "gatecontrol-familie"))
        assertNull(parse("message.exploded", """{"conversationId":12}"""))
        assertNull(parse("message.new", """{"messageId":1}"""))
        assertNull(parse("message.new", """[1]"""))
        assertNull(ChatEvents.parse(MESSENGER_PLUGIN, "message.new", null))
    }

    companion object {
        private const val NOW = 1_792_000_000_000L
    }
}
