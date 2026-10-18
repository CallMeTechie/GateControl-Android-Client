package com.gatecontrol.android.chat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneOffset

/** The Messenger 1.1 API: `around=`, gaps, `serverTime`, receipts in `read` events, released groups. */
class ChatApi11Test {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private fun msg(id: Long, at: String = "2026-10-17T10:00:00.000Z") = ChatMessage(
        id = id, conversationId = 12, senderId = 8, clientId = null, kind = ChatMessage.KIND_TEXT, via = null, mine = false,
        createdAt = at, editedAt = null, deletedAt = null, expiresAt = null, deleted = false, undecryptable = false,
        text = "t$id", replyTo = null, media = null, meta = null, location = null, system = null, reactions = emptyList(), status = null,
    )

    private fun conv(readUpTo: Long? = null, deliveredUpTo: Long? = null, receipts: List<ChatReceipt> = emptyList(), unread: Int = 0) = ChatConversation(
        id = 12, kind = ChatConversation.KIND_GROUP, title = "T", peer = null,
        avatarUrl = null, avatarMediaId = null, managedBy = null, readonly = false, disappearingS = 0, createdAt = null, updatedAt = null,
        lastMessageId = null, members = emptyList(), me = ChatMembership(null, null, "all", 10, 10), muted = false, unread = unread,
        lastMessage = null, typing = emptyList(), readUpTo = readUpTo, deliveredUpTo = deliveredUpTo, receipts = receipts,
        can = ChatCan(true, false, false, false, false),
    )

    // ─── messages?around= / serverTime ─────────────────────────────

    @Test
    fun `an around page says what lies before and after`() {
        val page = ChatJson.messagePage(
            json(
                """{"ok":true,"messages":[{"id":4710,"conversationId":12,"kind":"text","text":"a","createdAt":"2026-10-17T10:00:00.000Z"}],
                 "hasMore":true,"hasMoreBefore":false,"hasMoreAfter":true,"readUpTo":null,"deliveredUpTo":4711,"receipts":[],
                 "serverTime":"2026-10-17T10:00:05.123Z"}""",
            ),
        )!!
        assertEquals(false, page.hasMoreBefore)
        assertEquals(true, page.hasMoreAfter)
        assertTrue(page.isAround)
        assertEquals("2026-10-17T10:00:05.123Z", page.serverTime)
    }

    @Test
    fun `servers before 1_1 send neither`() {
        val page = ChatJson.messagePage(json("""{"ok":true,"messages":[],"hasMore":false,"serverTime":"nonsense"}"""))!!
        assertNull(page.hasMoreBefore)
        assertNull(page.hasMoreAfter)
        assertFalse(page.isAround, "the newest page of a server that ignores around")
        assertNull(page.serverTime, "an invalid time is dropped")
    }

    // ─── gaps ───────────────────────────────────────────────────────

    @Test
    fun `a jump far back leaves a gap up to the cached messages`() {
        // Cache 900…1000, jump to 100: page 75…124 with more on both sides.
        assertEquals(listOf(ChatGap(124, 900)), ChatGaps.afterPage(emptyList(), 900, 1000, 75, 124, hasMoreBefore = true, hasMoreAfter = true))
        // Nothing newer than the page: it reaches the cache, no gap.
        assertEquals(emptyList<ChatGap>(), ChatGaps.afterPage(emptyList(), 900, 1000, 75, 124, hasMoreBefore = true, hasMoreAfter = false))
        // A page overlapping the cache leaves nothing open.
        assertEquals(emptyList<ChatGap>(), ChatGaps.afterPage(emptyList(), 900, 1000, 870, 919, hasMoreBefore = true, hasMoreAfter = true))
        // An empty cache: the page is all there is.
        assertEquals(emptyList<ChatGap>(), ChatGaps.afterPage(emptyList(), null, null, 75, 124, hasMoreBefore = true, hasMoreAfter = true))
    }

    @Test
    fun `a jump into a gap splits it`() {
        val gaps = listOf(ChatGap(124, 900))
        assertEquals(
            listOf(ChatGap(124, 475), ChatGap(524, 900)),
            ChatGaps.afterPage(gaps, 75, 1000, 475, 524, hasMoreBefore = true, hasMoreAfter = true),
        )
    }

    @Test
    fun `filling a gap moves it on and closes it`() {
        var gaps = listOf(ChatGap(124, 900))
        // after=124 → 125…174, more follow.
        gaps = ChatGaps.afterPage(gaps, 75, 1000, 124, 174, hasMoreBefore = true, hasMoreAfter = true)
        assertEquals(listOf(ChatGap(174, 900)), gaps)
        // after=174 → reaches 900 and beyond.
        gaps = ChatGaps.afterPage(gaps, 75, 1000, 174, 920, hasMoreBefore = true, hasMoreAfter = true)
        assertEquals(emptyList<ChatGap>(), gaps)
        // An empty answer with nothing more closes it as well.
        assertEquals(emptyList<ChatGap>(), ChatGaps.afterPage(listOf(ChatGap(174, 900)), 75, 1000, 174, 174, hasMoreBefore = true, hasMoreAfter = false))
    }

    @Test
    fun `gaps survive the cache and know their messages`() {
        val gaps = listOf(ChatGap(124, 900), ChatGap(950, 980))
        assertEquals(gaps, ChatGaps.parse(ChatGaps.format(gaps)))
        assertNull(ChatGaps.format(emptyList()))
        assertEquals(listOf(ChatGap(1, 5)), ChatGaps.parse("1-5;x-9;9-3;-4-7;7"))
        assertTrue(ChatGaps.inGap(gaps, 500))
        assertFalse(ChatGaps.inGap(gaps, 124))
        assertFalse(ChatGaps.inGap(gaps, 900))
        assertFalse(ChatGaps.inGap(gaps, 1000))
    }

    @Test
    fun `the timeline shows a gap row where messages are missing`() {
        val items = ChatTimeline.build(listOf(msg(100), msg(124), msg(900), msg(1000)), emptyList(), null, ZoneOffset.UTC, gaps = listOf(ChatGap(124, 900)))
        val keys = items.map { it.key }
        assertEquals(listOf("d:2026-10-17", "m:100", "m:124", "g:124:900", "m:900", "m:1000"), keys)
        // Without gaps, as before.
        assertTrue(ChatTimeline.build(listOf(msg(100), msg(900)), emptyList(), null, ZoneOffset.UTC).none { it is TimelineItem.Gap })
        // The message the gap starts at gone (hidden): the row stays between its neighbours.
        val hidden = ChatTimeline.build(listOf(msg(100), msg(900)), emptyList(), null, ZoneOffset.UTC, gaps = listOf(ChatGap(124, 900)))
        assertEquals(listOf("d:2026-10-17", "m:100", "g:124:900", "m:900"), hidden.map { it.key })
    }

    // ─── read events ────────────────────────────────────────────────

    @Test
    fun `read events carry readUpTo and deliveredUpTo`() {
        val e = assertInstanceOf(
            ChatEvent.Read::class.java,
            ChatEvents.parse(MESSENGER_PLUGIN, "read", json("""{"conversationId":12,"userId":8,"lastReadId":40,"lastDeliveredId":41,"readUpTo":40,"deliveredUpTo":41}""")),
        )
        assertEquals(ChatEvent.UpTo(40, 41), e.upTo)
        // Explicit nulls (receipts off) are values too.
        val off = ChatEvents.parse(MESSENGER_PLUGIN, "read", json("""{"conversationId":12,"userId":8,"lastReadId":null,"lastDeliveredId":41,"readUpTo":null,"deliveredUpTo":41}""")) as ChatEvent.Read
        assertEquals(ChatEvent.UpTo(null, 41), off.upTo)
        // Servers before 1.1.0.
        val old = ChatEvents.parse(MESSENGER_PLUGIN, "read", json("""{"conversationId":12,"userId":8,"lastReadId":40,"lastDeliveredId":41}""")) as ChatEvent.Read
        assertNull(old.upTo)
    }

    @Test
    fun `a read event sets the ticks and the reader's receipt`() {
        val o = ChatSerializer.conversation(conv(readUpTo = 30, deliveredUpTo = 35, receipts = listOf(ChatReceipt(8, 30, 35), ChatReceipt(9, 33, 40))))
        val e = ChatEvent.Read(12, userId = 8, lastReadId = 40, lastDeliveredId = 41, self = false, unread = null, upTo = ChatEvent.UpTo(33, 40))
        val c = ChatJson.conversation(ChatJsonPatch.readEvent(o, e))!!
        assertEquals(33L, c.readUpTo)
        assertEquals(40L, c.deliveredUpTo)
        assertEquals(listOf(ChatReceipt(8, 40, 41), ChatReceipt(9, 33, 40)), c.receipts)
        // Ticks of own messages follow.
        val mine = msg(36).copy(mine = true, status = ChatMessage.STATUS_SENT)
        assertEquals(ChatMessage.STATUS_DELIVERED, ChatTimeline.status(mine, c.readUpTo, c.deliveredUpTo))
        // A reader not listed yet is added.
        val added = ChatJson.conversation(ChatJsonPatch.readEvent(ChatSerializer.conversation(conv()), e))!!
        assertEquals(listOf(ChatReceipt(8, 40, 41)), added.receipts)
    }

    @Test
    fun `a read event of the own devices sets unread and may carry the ticks`() {
        val o = ChatSerializer.conversation(conv(readUpTo = 30, deliveredUpTo = 35, unread = 4))
        val self = ChatEvent.Read(12, userId = 7, lastReadId = 50, lastDeliveredId = 50, self = true, unread = 0, upTo = ChatEvent.UpTo(null, 36))
        val c = ChatJson.conversation(ChatJsonPatch.readEvent(o, self))!!
        assertEquals(0, c.unread)
        assertEquals(50L, c.me.lastReadId)
        assertNull(c.readUpTo)
        assertEquals(36L, c.deliveredUpTo)
        assertEquals(emptyList<ChatReceipt>(), c.receipts, "the own entry is not a receipt of others")
        // Older servers: unread only, the ticks stay.
        val old = ChatJson.conversation(ChatJsonPatch.readEvent(o, self.copy(upTo = null)))!!
        assertEquals(30L, old.readUpTo)
        assertEquals(35L, old.deliveredUpTo)
        // Nothing to change: the same JSON (no write).
        val other = ChatEvent.Read(12, userId = 8, lastReadId = 30, lastDeliveredId = 35, self = false, unread = null, upTo = null)
        assertEquals(o, ChatJsonPatch.readEvent(o, other))
    }

    // ─── released groups ────────────────────────────────────────────

    @Test
    fun `a released group says so`() {
        val e = ChatEvents.parse(MESSENGER_PLUGIN, "conversation.update", json("""{"conversationId":12,"reason":"released"}""")) as ChatEvent.ConversationUpdate
        assertTrue(e.released)
        val multi = ChatEvents.parse(MESSENGER_PLUGIN, "conversation.update", json("""{"conversationId":12,"reason":"members,released"}""")) as ChatEvent.ConversationUpdate
        assertTrue(multi.released)
        val title = ChatEvents.parse(MESSENGER_PLUGIN, "conversation.update", json("""{"conversationId":12,"reason":"title"}""")) as ChatEvent.ConversationUpdate
        assertFalse(title.released)
        val sys = ChatJson.message(
            json("""{"id":60,"conversationId":12,"senderId":null,"kind":"system","createdAt":"2026-10-17T10:00:00.000Z","system":{"event":"released","by":null}}"""),
        )!!
        assertEquals("released", sys.system?.event)
        assertNull(sys.system?.by)
    }
}
