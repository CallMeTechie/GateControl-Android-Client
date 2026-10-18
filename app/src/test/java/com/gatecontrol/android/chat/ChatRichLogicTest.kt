package com.gatecontrol.android.chat

import com.gatecontrol.android.ui.chat.ChatLock
import com.gatecontrol.android.ui.chat.status.ChatStoryTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** Decisions of the Messenger 1.4 cards, storage, app lock and stories. */
class ChatRichLogicTest {

    private fun poll(multi: Boolean = false, mine: List<String> = emptyList(), anonymous: Boolean = false, creator: Long? = 7, closed: Boolean = false) = ChatPoll(
        question = "Q",
        options = listOf(
            ChatPollOption("a", "A", 2, if (anonymous) null else listOf(7, 8), null),
            ChatPollOption("b", "B", 1, if (anonymous) null else listOf(9), null),
        ),
        multi = multi, anonymous = anonymous, allowAdd = false, closesAt = null, closed = closed, voterCount = 3, memberCount = 4, myVotes = mine, creatorId = creator,
    )

    @Test
    fun `voting picks, toggles and withdraws`() {
        assertEquals(listOf("b"), ChatRichLogic.vote(poll(mine = listOf("a")), "b"), "single choice: the other replaces")
        assertEquals(emptyList<String>(), ChatRichLogic.vote(poll(mine = listOf("a")), "a"), "the same again withdraws")
        assertEquals(listOf("a", "b"), ChatRichLogic.vote(poll(multi = true, mine = listOf("a")), "b"))
        assertEquals(emptyList<String>(), ChatRichLogic.vote(poll(multi = true, mine = listOf("a")), "a"))
        assertEquals(0.5f, ChatRichLogic.share(1, 2))
        assertEquals(0f, ChatRichLogic.share(1, 0))
    }

    @Test
    fun `who has not voted, who may close or remind`() {
        assertEquals(listOf(10L), ChatRichLogic.nonVoters(poll(), listOf(7, 8, 9, 10, 11), myId = 11))
        assertNull(ChatRichLogic.nonVoters(poll(anonymous = true), listOf(7, 8), 7), "anonymous: unknown")
        assertTrue(ChatRichLogic.canClose(poll(), 7, admin = false, mine = false), "creator")
        assertTrue(ChatRichLogic.canClose(poll(), 8, admin = true, mine = false), "group admin")
        assertFalse(ChatRichLogic.canClose(poll(), 8, admin = false, mine = false))
        assertFalse(ChatRichLogic.canClose(poll(closed = true), 7, admin = true, mine = true))
        assertTrue(ChatRichLogic.canNudge(poll(), 7, mine = false))
        assertFalse(ChatRichLogic.canNudge(poll(), 8, mine = false))
    }

    private fun event(start: String, end: String? = null, cancelled: Boolean = false, place: Boolean = true, creator: Long? = 7) = ChatEventInfo(
        title = "T", startsAt = start, endsAt = end, allDay = false, place = if (place) ChatEventPlace(null, "Oma", 52.0, 13.0) else null,
        description = null, checklist = emptyList(), rsvp = null, guests = false, reminderMin = 60, familyCalendar = false,
        departureHint = true, cancelled = cancelled, creatorId = creator,
    )

    @Test
    fun `event days, arrival offer, answers and who may cancel`() {
        val zone = ZoneOffset.UTC
        val today = LocalDate.of(2026, 10, 18)
        assertEquals(8L, ChatRichLogic.daysUntil("2026-10-26T11:30:00.000Z", today, zone))
        assertEquals(0L, ChatRichLogic.daysUntil("2026-10-18T23:00:00.000Z", today, zone))
        assertEquals(-1L, ChatRichLogic.daysUntil("2026-10-17T10:00:00.000Z", today, zone))
        val start = java.time.Instant.parse("2026-10-18T12:00:00Z").toEpochMilli()
        val ev = event("2026-10-18T12:00:00.000Z")
        assertTrue(ChatRichLogic.offerArrival(ev, start - 2 * 3_600_000L), "within 3 h before")
        assertFalse(ChatRichLogic.offerArrival(ev, start - 4 * 3_600_000L))
        assertTrue(ChatRichLogic.offerArrival(ev, start + 30 * 60_000L), "while it runs (1 h without an end)")
        assertFalse(ChatRichLogic.offerArrival(ev, start + 2 * 3_600_000L))
        assertFalse(ChatRichLogic.offerArrival(event("2026-10-18T12:00:00.000Z", cancelled = true), start))
        assertFalse(ChatRichLogic.offerArrival(event("2026-10-18T12:00:00.000Z", place = false), start))
        assertNull(ChatRichLogic.rsvp(ChatRsvp.YES, ChatRsvp.YES))
        assertEquals(ChatRsvp.NO, ChatRichLogic.rsvp(ChatRsvp.YES, ChatRsvp.NO))
        assertTrue(ChatRichLogic.canManageEvent(ev, 7, admin = false, mine = false))
        assertFalse(ChatRichLogic.canManageEvent(ev, 8, admin = false, mine = false))
    }

    @Test
    fun `next event banner, pins, eta, distance, visible actions`() {
        val now = java.time.Instant.parse("2026-10-18T12:00:00Z").toEpochMilli()
        val next = ChatNextEvent(50, "Essen", "2026-10-18T11:30:00.000Z", 3)
        assertTrue(ChatRichLogic.showNextEvent(next, null, now), "still running")
        assertFalse(ChatRichLogic.showNextEvent(next, 50, now), "dismissed")
        assertFalse(ChatRichLogic.showNextEvent(next.copy(startsAt = "2026-10-18T10:00:00.000Z"), null, now), "over")
        assertFalse(ChatRichLogic.showNextEvent(null, null, now))
        fun m(id: Long, deleted: Boolean = false) = ChatMessage(
            id, 12, 8, null, ChatMessage.KIND_TEXT, null, false, "2026-10-18T10:00:00.000Z", null, null, null, deleted, false,
            "t", null, null, null, null, null, emptyList(), null,
        )
        assertEquals(listOf(41L, 40L), ChatRichLogic.pinnedMessages(listOf(41, 40, 39, 42), listOf(m(40), m(41), m(42, deleted = true))).map { it.id })
        assertEquals(9, ChatRichLogic.minutesUntil("2026-10-18T12:09:00.000Z", now))
        assertEquals(0, ChatRichLogic.minutesUntil("2026-10-18T11:00:00.000Z", now))
        assertEquals("850 m", ChatRichLogic.distance(850, Locale.GERMANY))
        assertEquals("3,1 km", ChatRichLogic.distance(3100, Locale.GERMANY))
        assertEquals("3.1 km", ChatRichLogic.distance(3100, Locale.US))
        val actions = listOf(ChatHomeAction("a", null, "A", null, null, false), ChatHomeAction("b", null, "B", null, null, false))
        assertEquals(listOf("b"), ChatRichLogic.visibleActions(actions, setOf("a")).map { it.id })
    }

    @Test
    fun `cached files are told apart by their first bytes`() {
        fun b(vararg x: Int) = ByteArray(16).also { a -> x.forEachIndexed { i, v -> a[i] = v.toByte() } }
        fun s(text: String, at: Int = 0) = ByteArray(16).also { a -> text.toByteArray(Charsets.ISO_8859_1).copyInto(a, at) }
        assertEquals(ChatStorageLogic.PHOTO, ChatStorageLogic.kindOf(b(0xFF, 0xD8, 0xFF)))
        assertEquals(ChatStorageLogic.PHOTO, ChatStorageLogic.kindOf(s("\u0089PNG")))
        assertEquals(ChatStorageLogic.PHOTO, ChatStorageLogic.kindOf(s("RIFF\u0000\u0000\u0000\u0000WEBP")))
        assertEquals(ChatStorageLogic.VOICE, ChatStorageLogic.kindOf(s("\u0000\u0000\u0000 ftypM4A ")))
        assertEquals(ChatStorageLogic.VIDEO, ChatStorageLogic.kindOf(s("\u0000\u0000\u0000 ftypisom")))
        assertEquals(ChatStorageLogic.VOICE, ChatStorageLogic.kindOf(s("OggS")))
        assertEquals(ChatStorageLogic.FILE, ChatStorageLogic.kindOf(s("%PDF-1.7")))
        assertEquals(ChatStorageLogic.FILE, ChatStorageLogic.kindOf(ByteArray(0)))
        assertEquals(10L, ChatStorageUsage(1, 2, 3, 3, 1).total)
    }

    @Test
    fun `the app lock asks again after a minute away, story times`() {
        assertFalse(ChatLock.relock(null, 1_000))
        assertFalse(ChatLock.relock(0, ChatLock.RELOCK_AFTER_MS))
        assertTrue(ChatLock.relock(0, ChatLock.RELOCK_AFTER_MS + 1))
        val now = java.time.Instant.parse("2026-10-18T12:00:00Z").toEpochMilli()
        assertEquals(12L, ChatStoryTime.minutesAgo("2026-10-18T11:48:00.000Z", now))
        assertEquals(23 * 60L, ChatStoryTime.minutesLeft("2026-10-19T11:00:00.000Z", now))
        assertNull(ChatStoryTime.minutesAgo(null, now))
    }
}
