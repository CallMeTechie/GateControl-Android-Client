package com.gatecontrol.android.tv.chat

import com.gatecontrol.android.chat.ChatJson
import com.gatecontrol.android.chat.ChatResult
import com.gatecontrol.android.tv.TvChatHintSettings
import com.gatecontrol.android.tv.chat.TvChatFixtures.bridge
import com.gatecontrol.android.tv.chat.TvChatFixtures.conversation
import com.gatecontrol.android.tv.chat.TvChatFixtures.message
import com.gatecontrol.android.tv.chat.TvChatFixtures.messageJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The rules of Nachrichten › Chats on the TV: which chats show, the bridges, what goes out, the hint. */
class TvChatLogicTest {

    private val family = conversation(1, "Familie", managedBy = "gatecontrol-familie", unread = 2, at = "2026-11-24T19:00:00Z")
    private val pinned = conversation(2, "Wir vier", listPinned = true, at = "2026-11-24T17:00:00Z")
    private val direct = conversation(3, "Mila", kind = "direct", unread = 4)
    private val club = conversation(4, "Sportverein", unread = 1)
    private val all = listOf(family, pinned, direct, club)

    @Test
    fun `by default only the family's groups show, then what was chosen`() {
        assertEquals(listOf(1L, 2L), TvChatLogic.visible(all, null).map { it.id })
        assertEquals(listOf(3L), TvChatLogic.visible(all, setOf(3L)).map { it.id })
        assertEquals(emptyList<Long>(), TvChatLogic.visible(all, emptySet()).map { it.id })
        // the first switch starts from the default
        assertEquals(setOf(1L, 2L, 4L), TvChatLogic.choose(all, null, 4L, true))
        assertEquals(setOf(2L), TvChatLogic.choose(all, null, 1L, false))
        // archived chats never
        val archived = conversation(9, "Alt", managedBy = "gatecontrol-familie", archived = true)
        assertTrue(TvChatLogic.visible(all + archived, null).none { it.id == 9L })
    }

    @Test
    fun `counts and the start row carry only names and numbers`() {
        val visible = TvChatLogic.visible(all, setOf(1L, 3L))
        assertEquals(6, TvChatLogic.unread(visible))
        val news = TvChatLogic.news(visible, "Chat")
        assertEquals(listOf("Familie" to 2, "Mila" to 4), news.map { it.name to it.count })
        assertEquals("99+", TvChatLogic.badge(120))
        assertNull(TvChatLogic.badge(0))
    }

    @Test
    fun `a chat over a bridge has its letter`() {
        assertEquals('W', TvChatLogic.bridgeMark("whatsapp"))
        assertEquals('S', TvChatLogic.bridgeMark("signal"))
        assertEquals('T', TvChatLogic.bridgeMark("telegram"))
        assertNull(TvChatLogic.bridgeMark(null))
        val c = conversation(7, "Oma", kind = "direct", bridge = bridge())
        assertEquals('W', TvChatLogic.news(listOf(c.copy(unread = 1)), "").single().bridgeMark)
    }

    @Test
    fun `over a bridge only while it is ok and the chat open`() {
        assertNull(TvChatLogic.block(conversation(7, "Oma", bridge = bridge())))
        assertEquals(TvChatBlock.BRIDGE_DOWN, TvChatLogic.block(conversation(7, "Oma", bridge = bridge(state = "unreachable"))))
        assertEquals(TvChatBlock.BRIDGE_DOWN, TvChatLogic.block(conversation(7, "Oma", bridge = bridge(state = "logged_out"))))
        assertEquals(TvChatBlock.CLOSED, TvChatLogic.block(conversation(7, "Oma", bridge = bridge(closed = true))))
        assertEquals(TvChatBlock.NO_RIGHT, TvChatLogic.block(conversation(7, "Oma", canSend = false)))
        assertNull(TvChatLogic.block(family))
        // a message that did not reach the other side cannot be answered
        val c = conversation(7, "Oma", bridge = bridge())
        val failedOwn = message(11, 7, mine = true, extra = ""","bridge":{"state":"failed","reason":"timeout"}""")
        assertFalse(TvChatLogic.canReply(c, failedOwn))
        assertTrue(TvChatLogic.canReply(c, message(12, 7)))
        assertFalse(TvChatLogic.canReply(conversation(7, "Oma", bridge = bridge(state = "off")), message(12, 7)))
    }

    @Test
    fun `409 bridge_text_only and the other refusals`() {
        assertEquals(TvChatSendError.TEXT_ONLY, TvChatLogic.sendError(ChatResult.Failed("bridge_text_only", 409)))
        assertEquals(TvChatSendError.BRIDGE, TvChatLogic.sendError(ChatResult.Failed("bridge_unreachable", 409)))
        assertEquals(TvChatSendError.OFFLINE, TvChatLogic.sendError(ChatResult.Offline))
        assertEquals(TvChatSendError.FAILED, TvChatLogic.sendError(ChatResult.Failed("http_500", 500)))
    }

    @Test
    fun `each message has its own clientId the messenger takes`() {
        val a = TvChatLogic.newClientId()
        val b = TvChatLogic.newClientId()
        assertNotEquals(a, b)
        assertTrue(ChatJson.isClientId(a))
        assertNull(TvChatLogic.text("   "))
        assertEquals(TvChatLogic.TEXT_MAX, TvChatLogic.text("x".repeat(5000))!!.length)
    }

    @Test
    fun `what a message shows`() {
        assertEquals(TvChatShow.TEXT, TvChatLogic.show(message(1)))
        assertEquals(TvChatShow.PHOTO, TvChatLogic.show(message(2, text = null, kind = "image", extra = ""","media":{"id":"0123456789abcdef0123456789abcdef","mime":"image/jpeg","thumbId":"fedcba9876543210fedcba9876543210"}""")))
        assertEquals(TvChatShow.VOICE, TvChatLogic.show(message(3, text = null, kind = "voice", extra = ""","media":{"id":"0123456789abcdef0123456789abcdef","mime":"audio/ogg"}""")))
        assertEquals(TvChatShow.VIDEO, TvChatLogic.show(message(4, text = null, kind = "file", extra = ""","media":{"id":"0123456789abcdef0123456789abcdef","mime":"video/mp4"}""")))
        assertEquals(TvChatShow.FILE, TvChatLogic.show(message(5, text = null, kind = "file", extra = ""","media":{"id":"0123456789abcdef0123456789abcdef","mime":"application/pdf"}""")))
        assertEquals(TvChatShow.LOCATION, TvChatLogic.show(message(6, text = null, kind = "location", extra = ""","location":{"lat":52.5,"lon":13.4}""")))
        // a picture of a bridge that did not cross: a line, no preview
        assertEquals(TvChatShow.OTHER, TvChatLogic.show(message(7, text = null, extra = ""","bridge":{"placeholder":{"kind":"image","state":"loading"}}""")))
        assertEquals(TvChatShow.VOICE, TvChatLogic.show(message(8, text = null, extra = ""","bridge":{"placeholder":{"kind":"voice"}}""")))
    }

    @Test
    fun `status at the own text`() {
        assertEquals(TvChatStatus.SENT, TvChatLogic.status(message(10, mine = true, status = "sent"), null, null))
        assertEquals(TvChatStatus.DELIVERED, TvChatLogic.status(message(10, mine = true, status = "sent"), null, 10))
        assertEquals(TvChatStatus.READ, TvChatLogic.status(message(10, mine = true, status = "sent"), 12, 12))
        assertEquals(TvChatStatus.FAILED, TvChatLogic.status(message(10, mine = true, status = "failed"), null, null))
        assertNull(TvChatLogic.status(message(10), null, null))
    }

    @Test
    fun `history merges by id and the sends end with the stored message`() {
        val merged = TvChatLogic.merge(listOf(message(3), message(1)), listOf(message(2), message(3, text = "neu")))
        assertEquals(listOf(1L, 2L, 3L), merged.map { it.id })
        assertEquals("neu", merged.last().text)
        val p = listOf(TvChatPending("tvaaaaaaaaaaaa", 5, "Bis gleich", null, 1), TvChatPending("tvbbbbbbbbbbbb", 5, "Ok", null, 2), TvChatPending("tvcccccccccccc", 6, "x", null, 3))
        val stored = listOf(message(4, mine = true, clientId = "tvaaaaaaaaaaaa"))
        assertEquals(listOf("tvbbbbbbbbbbbb"), TvChatLogic.pendingIn(p, stored, 5).map { it.clientId })
    }

    @Test
    fun `the hint over other apps is off by default, never in front, never in the quiet hours`() {
        val on = TvChatHintSettings(on = true, quiet = true, quietFrom = 22 * 60, quietTo = 7 * 60)
        fun shows(s: TvChatHintSettings = on, visible: Boolean = true, front: Boolean = false, door: Boolean = false, minute: Int = 18 * 60, last: Long? = null, now: Long = 1_000_000L) =
            TvChatLogic.hintShows(s, visible, front, door, minute, last, now)
        assertFalse(shows(TvChatHintSettings()))
        assertTrue(shows())
        assertFalse(shows(visible = false))
        assertFalse(shows(front = true))
        assertFalse(shows(door = true))
        assertFalse(shows(minute = 23 * 60))
        assertFalse(shows(minute = 6 * 60))
        assertTrue(shows(on.copy(quiet = false), minute = 23 * 60))
        assertFalse(shows(last = 1_000_000L - 10_000L))
        assertTrue(shows(last = 1_000_000L - TvChatLogic.HINT_GAP_MS))
    }

    @Test
    fun `the hint names who wrote, never what`() {
        val c = conversation(1, "Familie", lastMessage = messageJson(30, 1, text = "Geheim"))
        assertEquals("Mila", TvChatLogic.hintName(c, 30, "Chat"))
        assertEquals("Familie", TvChatLogic.hintName(c, 31, "Chat"))
        assertFalse(TvChatLogic.hintName(c, 30, "Chat").contains("Geheim"))
    }
}
