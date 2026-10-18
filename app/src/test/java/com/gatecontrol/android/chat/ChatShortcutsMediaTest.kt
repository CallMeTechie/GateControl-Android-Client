package com.gatecontrol.android.chat

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Conversation shortcuts, the detail-pane selection and the media overview of the chats. */
class ChatShortcutsMediaTest {

    private fun conv(
        id: Long,
        at: String? = "2026-10-17T10:00:00.000Z",
        title: String? = "T$id",
        group: Boolean = false,
        readonly: Boolean = false,
        canSend: Boolean = true,
        avatarMediaId: String? = null,
    ) = ChatConversation(
        id = id, kind = if (group) ChatConversation.KIND_GROUP else ChatConversation.KIND_DIRECT, title = title, peer = null,
        avatarUrl = null, avatarMediaId = avatarMediaId, managedBy = null, readonly = readonly, disappearingS = 0, createdAt = null, updatedAt = at,
        lastMessageId = null, members = emptyList(), me = ChatMembership(null, null, "all", null, null), muted = false, unread = 0,
        lastMessage = null, typing = emptyList(), readUpTo = null, deliveredUpTo = null, receipts = emptyList(),
        can = ChatCan(canSend, false, false, true, false),
    )

    private fun msg(id: Long, kind: String, mime: String? = null, deleted: Boolean = false, media: Boolean = true) = ChatMessage(
        id = id, conversationId = 12, senderId = 8, clientId = null, kind = kind, via = null, mine = false,
        createdAt = "2026-10-17T10:00:00.000Z", editedAt = null, deletedAt = null, expiresAt = null, deleted = deleted, undecryptable = false,
        text = null, replyTo = null, media = if (media) ChatMedia("%032x".format(id), mime, 100, null) else null, meta = null, location = null,
        system = null, reactions = emptyList(), status = null,
    )

    // ─── shortcuts ──────────────────────────────────────────────────

    @Test
    fun `shortcut ids name their conversation and nothing else`() {
        assertEquals("chat:12", ChatShortcutLogic.id(12))
        assertEquals(12L, ChatShortcutLogic.conversationOf("chat:12"))
        assertNull(ChatShortcutLogic.conversationOf("qa:door"))
        assertNull(ChatShortcutLogic.conversationOf("familie:emergency"))
        assertNull(ChatShortcutLogic.conversationOf("chat:"))
        assertNull(ChatShortcutLogic.conversationOf("chat:-3"))
        assertNull(ChatShortcutLogic.conversationOf("chat:0"))
        assertNull(ChatShortcutLogic.conversationOf("chat:abc"))
        assertNull(ChatShortcutLogic.conversationOf(null))
    }

    @Test
    fun `the most recent conversations get a shortcut`() {
        val list = listOf(
            conv(1, at = "2026-10-15T10:00:00.000Z"),
            conv(2, at = "2026-10-17T10:00:00.000Z"),
            conv(3, at = "2026-10-16T10:00:00.000Z"),
            conv(4, at = "2026-10-14T10:00:00.000Z"),
            conv(5, at = "2026-10-13T10:00:00.000Z"),
        )
        assertEquals(listOf(2L, 3L, 1L, 4L), ChatShortcutLogic.recent(list).map { it.id })
        assertEquals(listOf(2L, 3L), ChatShortcutLogic.recent(list, 2).map { it.id })
        assertTrue(ChatShortcutLogic.recent(list, 0).isEmpty())
    }

    @Test
    fun `only chats one can write in are share targets`() {
        assertTrue(ChatShortcutLogic.shareable(conv(1)))
        assertFalse(ChatShortcutLogic.shareable(conv(1, readonly = true)), "a plugin's notice channel")
        assertFalse(ChatShortcutLogic.shareable(conv(1, canSend = false)))
    }

    @Test
    fun `stale chat shortcuts go, other features' shortcuts stay`() {
        val existing = listOf("chat:1", "chat:2", "qa:door", "familie:emergency", "chat:3")
        assertEquals(listOf("chat:1", "chat:3"), ChatShortcutLogic.stale(existing, setOf(2L)))
        assertEquals(emptyList<String>(), ChatShortcutLogic.stale(listOf("qa:door"), emptySet()))
    }

    @Test
    fun `the signature changes with what the shortcut shows`() {
        val a = ChatShortcutLogic.signature(conv(1), "Anna")
        assertEquals(a, ChatShortcutLogic.signature(conv(1, at = "2026-10-18T10:00:00.000Z"), "Anna"), "activity alone changes nothing")
        assertNotEquals(a, ChatShortcutLogic.signature(conv(1), "Anna B."))
        assertNotEquals(a, ChatShortcutLogic.signature(conv(1, avatarMediaId = "0".repeat(32)), "Anna"))
        assertNotEquals(a, ChatShortcutLogic.signature(conv(1, readonly = true), "Anna"))
    }

    @Test
    fun `chat shortcuts leave room for quick actions and Familie`() {
        assertEquals(4, ChatShortcutLogic.room(maxPerActivity = 15, others = 2, reserved = 5))
        assertEquals(3, ChatShortcutLogic.room(maxPerActivity = 10, others = 7, reserved = 5))
        assertEquals(0, ChatShortcutLogic.room(maxPerActivity = 5, others = 1, reserved = 5))
        assertEquals(0, ChatShortcutLogic.room(maxPerActivity = 4, others = 6, reserved = 5))
    }

    // ─── detail pane ────────────────────────────────────────────────

    @Test
    fun `the detail pane lets go of a dropped or no longer listed conversation`() {
        assertTrue(ChatSelection.keep(null, setOf(1L), setOf(2L), seenListed = true))
        assertTrue(ChatSelection.keep(1, emptySet(), setOf(1L, 2L), seenListed = true))
        assertFalse(ChatSelection.keep(1, setOf(1L), setOf(1L), seenListed = true), "left: dropped even if the list lags behind")
        assertFalse(ChatSelection.keep(1, emptySet(), setOf(2L), seenListed = true), "was listed, now gone")
        assertTrue(ChatSelection.keep(1, emptySet(), setOf(2L), seenListed = false), "opened from a push before the list has it")
        assertTrue(ChatSelection.keep(1, emptySet(), null, seenListed = true), "list not loaded yet")
    }

    // ─── media overview ─────────────────────────────────────────────

    @Test
    fun `pictures and videos, files and voice messages go to their tabs`() {
        val image = msg(1, ChatMessage.KIND_IMAGE, "image/webp")
        val video = msg(2, ChatMessage.KIND_FILE, "video/mp4")
        val pdf = msg(3, ChatMessage.KIND_FILE, "application/pdf")
        val voice = msg(4, ChatMessage.KIND_VOICE, "audio/mp4")
        val gone = msg(5, ChatMessage.KIND_IMAGE, "image/webp", deleted = true)
        val noFile = msg(6, ChatMessage.KIND_IMAGE, media = false)
        val all = listOf(image, video, pdf, voice, gone, noFile)
        assertEquals(listOf(2L, 1L), ChatMediaLogic.merge(ChatMediaLogic.Tab.Media, emptyList(), all).map { it.id })
        assertEquals(listOf(3L), ChatMediaLogic.merge(ChatMediaLogic.Tab.Files, emptyList(), all).map { it.id })
        assertEquals(listOf(4L), ChatMediaLogic.merge(ChatMediaLogic.Tab.Voice, emptyList(), all).map { it.id })
        assertTrue(ChatMediaLogic.isVideo(video))
        assertFalse(ChatMediaLogic.isVideo(image))
        assertEquals("all", ChatMediaLogic.Tab.Media.kind)
        assertEquals("file", ChatMediaLogic.Tab.Files.kind)
        assertEquals("voice", ChatMediaLogic.Tab.Voice.kind)
    }

    @Test
    fun `pages merge newest first without doubles and page on from the oldest id`() {
        val first = listOf(msg(30, ChatMessage.KIND_IMAGE, "image/jpeg"), msg(20, ChatMessage.KIND_IMAGE, "image/jpeg"))
        val second = listOf(msg(20, ChatMessage.KIND_IMAGE, "image/jpeg"), msg(10, ChatMessage.KIND_IMAGE, "image/jpeg"))
        val merged = ChatMediaLogic.merge(ChatMediaLogic.Tab.Media, ChatMediaLogic.merge(ChatMediaLogic.Tab.Media, emptyList(), first), second)
        assertEquals(listOf(30L, 20L, 10L), merged.map { it.id })
        assertEquals(20L, ChatMediaLogic.cursor(null, first))
        // A page of files only (skipped in the picture grid) still moves the cursor.
        assertEquals(5L, ChatMediaLogic.cursor(20L, listOf(msg(5, ChatMessage.KIND_FILE, "application/pdf"))))
        assertEquals(20L, ChatMediaLogic.cursor(20L, emptyList()))
        assertNull(ChatMediaLogic.cursor(null, emptyList()))
    }

    @Test
    fun `media page parses items and counts`() {
        val page = ChatJson.mediaPage(
            JsonParser.parseString(
                """
                {"ok":true,"hasMore":true,"counts":{"image":12,"voice":3,"file":-1},"items":[
                  {"id":40,"conversationId":12,"senderId":7,"kind":"image","mine":false,"createdAt":"2026-10-17T10:00:00.000Z",
                   "media":{"id":"0123456789abcdef0123456789abcdef","mime":"image/webp","bytes":1000,"thumbId":"abcdefabcdefabcdefabcdefabcdefab"},
                   "meta":{"w":800,"h":600},"reactions":[]},
                  {"id":39,"conversationId":12,"senderId":7,"kind":"image","mine":false,"createdAt":"2026-10-17T09:00:00.000Z",
                   "deleted":true,"media":null,"reactions":[]},
                  {"id":38,"conversationId":12,"senderId":8,"kind":"voice","mine":false,"createdAt":"2026-10-17T08:00:00.000Z",
                   "media":{"id":"ffffffffffffffffffffffffffffffff","mime":"audio/mp4","bytes":5000},"meta":{"durationMs":4000},"reactions":[]}
                ]}
                """.trimIndent(),
            ).asJsonObject,
        )
        assertNotNull(page)
        page!!
        assertEquals(listOf(40L, 38L), page.items.map { it.id }, "the deleted one carries no file")
        assertEquals(12, page.images)
        assertEquals(3, page.voice)
        assertEquals(0, page.files, "negative counts are clamped")
        assertTrue(page.hasMore)
        assertEquals("abcdefabcdefabcdefabcdefabcdefab", page.items[0].media?.thumbId)
        assertNull(ChatJson.mediaPage(JsonParser.parseString("""{"ok":true}""").asJsonObject))
    }
}
