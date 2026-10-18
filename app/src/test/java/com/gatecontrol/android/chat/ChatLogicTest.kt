package com.gatecontrol.android.chat

import com.gatecontrol.android.chat.db.OutboxEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Timeline, ticks, outbox decisions and the JSON patches of the chats. */
class ChatLogicTest {

    private fun msg(
        id: Long,
        mine: Boolean = false,
        sender: Long? = if (mine) 7 else 8,
        at: String = "2026-10-17T10:00:00.000Z",
        kind: String = ChatMessage.KIND_TEXT,
        clientId: String? = null,
        status: String? = if (mine) ChatMessage.STATUS_SENT else null,
        deleted: Boolean = false,
    ) = ChatMessage(
        id = id, conversationId = 12, senderId = sender, clientId = clientId, kind = kind, via = null, mine = mine,
        createdAt = at, editedAt = null, deletedAt = null, expiresAt = null, deleted = deleted, undecryptable = false,
        text = "t$id", replyTo = null, media = null, meta = null, location = null, system = null, reactions = emptyList(), status = status,
    )

    private fun pending(clientId: String, at: Long = 1_000L) = ChatPending(
        clientId = clientId, conversationId = 12, createdAtMs = at, kind = ChatMessage.KIND_TEXT, text = "p", replyToId = null,
        localPath = null, mime = null, meta = null, location = null, failed = false, error = null,
    )

    private fun conv(group: Boolean = false, readUpTo: Long? = null, deliveredUpTo: Long? = null, unread: Int = 0, muted: Boolean = false, id: Long = 12) =
        ChatConversation(
            id = id, kind = if (group) ChatConversation.KIND_GROUP else ChatConversation.KIND_DIRECT, title = "T", peer = null,
            avatarUrl = null, avatarMediaId = null, managedBy = null, readonly = false, disappearingS = 0, createdAt = null, updatedAt = null,
            lastMessageId = null, members = emptyList(), me = ChatMembership(null, null, "all", null, null), muted = muted, unread = unread,
            lastMessage = null, typing = emptyList(), readUpTo = readUpTo, deliveredUpTo = deliveredUpTo, receipts = emptyList(),
            can = ChatCan(true, false, false, false, false),
        )

    // ─── ticks ──────────────────────────────────────────────────────

    @Test
    fun `ticks take the newer of the message status and the receipts`() {
        assertEquals(ChatMessage.STATUS_SENT, ChatTimeline.status(msg(10, mine = true), null, null))
        assertEquals(ChatMessage.STATUS_DELIVERED, ChatTimeline.status(msg(10, mine = true), null, 10))
        assertEquals(ChatMessage.STATUS_READ, ChatTimeline.status(msg(10, mine = true), 12, 12))
        assertEquals(ChatMessage.STATUS_READ, ChatTimeline.status(msg(10, mine = true, status = ChatMessage.STATUS_READ), null, null))
        assertNull(ChatTimeline.status(msg(10), 12, 12), "no ticks on others' messages")
        assertNull(ChatTimeline.status(msg(10, mine = true, deleted = true), 12, 12))
    }

    // ─── timeline ───────────────────────────────────────────────────

    @Test
    fun `day separators, sender names in groups and pending messages at the end`() {
        val items = ChatTimeline.build(
            messages = listOf(
                msg(3, at = "2026-10-17T08:00:00.000Z"),
                msg(1, at = "2026-10-16T08:00:00.000Z"),
                msg(2, at = "2026-10-16T09:00:00.000Z"),
                msg(4, mine = true, at = "2026-10-17T08:01:00.000Z", clientId = "sentsent01"),
            ),
            pending = listOf(pending("waiting01", Instant.parse("2026-10-17T09:00:00Z").toEpochMilli()), pending("sentsent01")),
            conversation = conv(group = true, readUpTo = 4),
            zone = ZoneOffset.UTC,
        )
        assertEquals(listOf("d:2026-10-16", "m:1", "m:2", "d:2026-10-17", "m:3", "m:4", "c:waiting01"), items.map { it.key })
        val m1 = items[1] as TimelineItem.Message
        val m2 = items[2] as TimelineItem.Message
        val m3 = items[4] as TimelineItem.Message
        assertTrue(m1.showSender)
        assertFalse(m2.showSender, "same sender again")
        assertTrue(m3.showSender, "after a day separator")
        assertEquals(ChatMessage.STATUS_READ, (items[5] as TimelineItem.Message).status)
        assertEquals(listOf("sentsent01"), ChatTimeline.delivered(listOf(msg(4, mine = true, clientId = "sentsent01")), listOf(pending("waiting01"), pending("sentsent01"))))
    }

    @Test
    fun `direct chats never show sender names`() {
        val items = ChatTimeline.build(listOf(msg(1), msg(2)), emptyList(), conv(), ZoneOffset.UTC)
        assertTrue(items.filterIsInstance<TimelineItem.Message>().none { it.showSender })
        assertEquals(LocalDate.parse("2026-10-17"), (items[0] as TimelineItem.Day).date)
    }

    @Test
    fun `a page proves cached messages in its range gone`() {
        assertEquals(listOf(5L, 7L), ChatTimeline.vanished(listOf(4L, 5L, 6L, 7L), listOf(msg(4), msg(6))))
        assertTrue(ChatTimeline.vanished(emptyList(), listOf(msg(1))).isEmpty())
    }

    @Test
    fun `edit and delete for everyone only by the sender within the window`() {
        val now = Instant.parse("2026-10-17T10:10:00Z").toEpochMilli()
        val mine = msg(1, mine = true)
        assertTrue(ChatTimeline.withinWindow(mine, 900, now))
        assertFalse(ChatTimeline.withinWindow(mine, 300, now))
        assertFalse(ChatTimeline.withinWindow(mine, 0, now), "window 0 = not allowed")
        assertFalse(ChatTimeline.withinWindow(msg(1), 900, now))
        assertTrue(ChatTimeline.canEdit(mine, 900, now))
        assertFalse(ChatTimeline.canEdit(msg(2, mine = true, kind = ChatMessage.KIND_VOICE), 900, now))
        assertFalse(ChatTimeline.canEdit(msg(3, mine = true, kind = ChatMessage.KIND_LOCATION), 900, now))
    }

    @Test
    fun `badge counts unread of conversations that are not muted, typing excludes the caller`() {
        assertEquals(5, ChatTimeline.badge(listOf(conv(unread = 2, id = 1), conv(unread = 3, id = 2), conv(unread = 9, muted = true, id = 3))))
        val typing = mapOf(12L to mapOf(7L to 2_000L, 8L to 2_000L, 9L to 500L))
        assertEquals(listOf(8L), ChatTimeline.typingNow(typing, 12, myId = 7, nowMs = 1_000L))
        assertTrue(ChatTimeline.typingNow(typing, 13, 7, 1_000L).isEmpty())
    }

    // ─── outbox ─────────────────────────────────────────────────────

    private fun out(clientId: String, conv: Long = 12, at: Long = 0L, next: Long = 0L, state: String = OutboxEntity.STATE_QUEUED) = OutboxEntity(
        clientId = clientId, conversationId = conv, createdAtMs = at, kind = ChatMessage.KIND_TEXT, text = "x", replyToId = null,
        filePath = null, mime = null, thumbPath = null, metaJson = null, locationJson = null, mediaId = null, thumbMediaId = null,
        state = state, attempts = 0, error = null, nextAttemptMs = next, source = null,
    )

    @Test
    fun `the outbox sends the oldest due entry and keeps each conversation's order`() {
        val items = listOf(
            out("a", conv = 1, at = 10, next = 5_000),
            out("b", conv = 1, at = 20, next = 0),
            out("c", conv = 2, at = 30, next = 0),
            out("d", conv = 3, at = 5, state = OutboxEntity.STATE_FAILED),
        )
        // "b" is due but "a" (older, same chat) is not: chat 1 waits, chat 2 goes.
        assertEquals("c", ChatOutboxLogic.next(items, nowMs = 1_000)!!.clientId)
        assertEquals("a", ChatOutboxLogic.next(items, nowMs = 6_000)!!.clientId)
        assertEquals(0L, ChatOutboxLogic.nextDueMs(items))
        assertNull(ChatOutboxLogic.next(listOf(out("x", state = OutboxEntity.STATE_FAILED)), 0))
        assertNull(ChatOutboxLogic.nextDueMs(listOf(out("x", state = OutboxEntity.STATE_FAILED))))
    }

    @Test
    fun `outbox steps - offline and busy retry, refusals give up, stale uploads go up again`() {
        assertEquals(OutboxStep.Done, ChatOutboxLogic.step(ChatResult.Ok(Unit), 1, false, 0))
        assertEquals(OutboxStep.Retry(5_000), ChatOutboxLogic.step(ChatResult.Offline, 1, false, 0))
        assertEquals(OutboxStep.Retry(10_000), ChatOutboxLogic.step(ChatResult.Failed("http_503", 503), 2, false, 0))
        assertEquals(OutboxStep.Retry(20_000), ChatOutboxLogic.step(ChatResult.Failed("rate_limited", 429), 3, false, 0))
        assertEquals(OutboxStep.GiveUp("readonly"), ChatOutboxLogic.step(ChatResult.Failed("readonly", 403), 1, false, 0))
        assertEquals(OutboxStep.GiveUp("not_found"), ChatOutboxLogic.step(ChatResult.Failed("not_found", 404), 1, false, 0))
        assertEquals(OutboxStep.GiveUp("http_413"), ChatOutboxLogic.step(ChatResult.Failed("http_413", 413), 1, false, 0))
        assertEquals(OutboxStep.Reupload, ChatOutboxLogic.step(ChatResult.Failed("media_invalid", 409), 1, true, 0))
        assertEquals(OutboxStep.GiveUp("media_invalid"), ChatOutboxLogic.step(ChatResult.Failed("media_invalid", 409), 3, true, 0))
        assertInstanceOf(OutboxStep.GiveUp::class.java, ChatOutboxLogic.step(ChatResult.Offline, ChatOutboxLogic.MAX_ATTEMPTS, false, 0))
        assertEquals(15 * 60_000L, ChatOutboxLogic.backoffMs(40))
    }

    @Test
    fun `meta and location survive the outbox`() {
        val meta = ChatMeta(durationMs = 1200, waveform = listOf(1, 2, 3), fileName = "brief.pdf")
        assertEquals(meta, ChatOutboxLogic.metaOf(ChatOutboxLogic.metaJson(meta)!!))
        val loc = ChatLocation(52.52, 13.405, "Berlin", 20)
        assertEquals(loc, ChatOutboxLogic.locationOf(ChatOutboxLogic.locationJson(loc)!!))
        assertNull(ChatOutboxLogic.metaJson(null))
    }

    // ─── JSON patches ───────────────────────────────────────────────

    @Test
    fun `patches - tombstone, reactions, read mark, last message`() {
        val m = ChatSerializer.message(msg(5, mine = true).copy(reactions = listOf(ChatReaction("👍", 1, listOf(8)))))
        val tomb = ChatJson.message(ChatJsonPatch.tombstone(m, "2026-10-17T11:00:00.000Z"))!!
        assertTrue(tomb.deleted)
        assertNull(tomb.text)
        assertTrue(tomb.reactions.isEmpty())
        assertEquals("2026-10-17T11:00:00.000Z", tomb.deletedAt)

        val re = ChatJson.message(ChatJsonPatch.reactions(m, listOf(ChatReaction("❤️", 2, listOf(7, 8)))))!!
        assertEquals(listOf(ChatReaction("❤️", 2, listOf(7, 8))), re.reactions)

        val c = ChatSerializer.conversation(conv(unread = 4))
        val read = ChatJson.conversation(ChatJsonPatch.read(c, 0, 30))!!
        assertEquals(0, read.unread)
        assertEquals(30L, read.me.lastReadId)
        // The read mark never moves back.
        assertEquals(30L, ChatJson.conversation(ChatJsonPatch.read(ChatSerializer.conversation(read), 0, 20))!!.me.lastReadId)

        val withLast = ChatJson.conversation(ChatJsonPatch.lastMessage(c, m))!!
        assertEquals(5L, withLast.lastMessage!!.id)
        val older = ChatJson.conversation(ChatJsonPatch.lastMessage(ChatSerializer.conversation(withLast), ChatSerializer.message(msg(3))))!!
        assertEquals(5L, older.lastMessage!!.id, "an older message does not replace the newest")
    }
}
