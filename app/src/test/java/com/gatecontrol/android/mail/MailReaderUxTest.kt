package com.gatecontrol.android.mail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The reader's rules: a wide mail fits, the menu's folded accounts, a conversation, a sender's mails. */
class MailReaderUxTest {

    private fun s(id: Long, date: Long, from: String = "a@b.de", name: String? = "Anna") = MSummary(
        id = id, accountId = 1, folderId = null, threadId = 9, threadCount = 3, from = MAddress(name, from), to = emptyList(),
        subject = "s$id", snippet = "", date = date, unread = false, flagged = false, answered = false, hasAttachments = false,
        tab = MSummary.TAB_RELEVANT, savedToDrive = false, aiSummary = null,
    )

    private fun m(id: Long, date: Long, from: String = "a@b.de", name: String? = "Anna") =
        MMessage(s(id, date, from, name), emptyList(), emptyList(), emptyList(), "<p>$id</p>", null, false, 0, emptyList(), null)

    // ─── a wide mail fits ───────────────────────────────────────────

    @Test
    fun `the page keeps pictures, tables, links and pre inside and wraps the mail for the zoom`() {
        val page = MailLogic.page("<table width=\"600\"><tr><td>x</td></tr></table>")
        assertTrue(page.contains("name=\"viewport\" content=\"width=device-width, initial-scale=1\""), "device wide, pinching allowed")
        assertFalse(page.contains("user-scalable"), "pinching stays allowed")
        assertTrue(page.contains("img{max-width:100%;height:auto}"))
        assertTrue(page.contains("table{max-width:100%}"))
        assertTrue(page.contains("pre{white-space:pre-wrap"))
        assertTrue(page.contains("a{overflow-wrap:anywhere"))
        assertTrue(page.contains("<div id=\"gc-fit\"><table width=\"600\">"))
        assertFalse(page.contains("zoom:"), "not zoomed until measured")
        assertTrue(page.contains("script-src 'none'"), "the mail never runs a script")
    }

    @Test
    fun `a measured zoom goes into the page, never below the minimum`() {
        assertTrue(MailLogic.page("<p>x</p>", zoom = 0.6).contains("#gc-fit{zoom:0.600}"))
        assertTrue(MailLogic.page("<p>x</p>", zoom = 0.05).contains("#gc-fit{zoom:0.250}"))
        assertFalse(MailLogic.page("<p>x</p>", zoom = 1.4).contains("zoom:"))
    }

    @Test
    fun `fitZoom - a 600 px newsletter on a 360 px phone is made smaller, a fitting mail is not`() {
        // 360 wide, the mail's table 600 + the left margin
        val z = MailLogic.fitZoom(360.0, 612.0)!!
        assertEquals((360.0 - 24) / 600, z, 0.002)
        // laid out at that zoom it fits: no further round
        assertNull(MailLogic.fitZoom(360.0, 12 + 600 * z + 12))
        // fits already (1 px of rounding)
        assertNull(MailLogic.fitZoom(360.0, 360.0))
        assertNull(MailLogic.fitZoom(360.0, 361.0))
        // measured again at a zoom: the next is computed from the mail at zoom 1
        val again = MailLogic.fitZoom(360.0, 12 + 600 * 0.8, current = 0.8)!!
        assertEquals((360.0 - 24) / 600, again, 0.002)
        // never below the minimum, never larger than now
        assertEquals(MailLogic.MIN_ZOOM, MailLogic.fitZoom(360.0, 5000.0)!!, 0.0001)
        assertNull(MailLogic.fitZoom(360.0, 5000.0, current = MailLogic.MIN_ZOOM))
        assertNull(MailLogic.fitZoom(0.0, 600.0))
    }

    // ─── the menu: folded accounts ──────────────────────────────────

    @Test
    fun `an account's folders - open for the account shown until folded, then as the person left them`() {
        val box = MailLogic.Box(3)
        assertTrue(MailLogic.foldOpen(emptyMap(), 3, box), "the shown account starts open")
        assertFalse(MailLogic.foldOpen(emptyMap(), 4, box))
        // the open one closes again (it could not before)
        val closed = MailLogic.toggleFold(emptyMap(), 3, box)
        assertFalse(MailLogic.foldOpen(closed, 3, box))
        val other = MailLogic.toggleFold(closed, 4, box)
        assertTrue(MailLogic.foldOpen(other, 4, box))
        assertFalse(MailLogic.foldOpen(other, 3, box), "each account keeps its own")
        // picking another box later keeps what the person chose
        assertFalse(MailLogic.foldOpen(other, 3, MailLogic.Box(3, 11)))
        assertTrue(MailLogic.foldOpen(MailLogic.toggleFold(other, 3, box), 3, box))
    }

    // ─── a conversation ─────────────────────────────────────────────

    @Test
    fun `a conversation around the open mail - older above, newer below, each oldest first`() {
        val items = listOf(m(3, 300), m(1, 100), m(2, 200), m(4, 400))
        val (older, newer) = MailLogic.conversation(items, 2)
        assertEquals(listOf(1L), older.map { it.id })
        assertEquals(listOf(3L, 4L), newer.map { it.id })
        // the newest open: all others above
        assertEquals(listOf(1L, 2L, 3L) to emptyList<Long>(), MailLogic.conversation(items, 4).let { (a, b) -> a.map { it.id } to b.map { it.id } })
        // not in it: all above
        assertEquals(4, MailLogic.conversation(items, 99).first.size)
        // the same date: by id
        assertEquals(listOf(5L, 6L), MailLogic.conversation(listOf(m(6, 100), m(5, 100)), 0).first.map { it.id })
    }

    @Test
    fun `a conversation's senders, each once`() {
        val list = listOf(s(1, 1, "a@b.de", "Anna"), s(2, 2, "tim@b.de", "Tim"), s(3, 3, "A@B.de", "Anna B."), s(4, 4, "x@y.de", null))
        assertEquals(listOf("Anna", "Tim", "x@y.de"), MailLogic.participants(list))
        assertEquals(listOf("Anna"), MailLogic.participants(list, max = 1))
    }

    // ─── a sender ───────────────────────────────────────────────────

    @Test
    fun `a sender's mails - the search in the account, page by page, exactly that address`() {
        assertEquals(mapOf("from" to "anna@b.de", "box" to "3"), MailLogic.senderQuery(" Anna@b.de ", 3))
        assertEquals(mapOf("from" to "anna@b.de", "box" to "all", "cursor" to "c-1"), MailLogic.senderQuery("anna@b.de", null, "c-1"))
        val items = listOf(s(1, 1, "anna@b.de"), s(2, 2, "hanna@b.de"), s(3, 3, "ANNA@b.de"))
        assertEquals(listOf(1L, 3L), MailLogic.fromSender(items, "anna@b.de").map { it.id })
    }

    @Test
    fun `a sender's name from the contacts - only a person's`() {
        val contacts = listOf(
            MContact("Anna Recent", "anna@b.de", MContact.SOURCE_RECENT),
            MContact("Anna Müller", "Anna@B.de", MContact.SOURCE_PERSON),
        )
        assertEquals("Anna Müller", MailLogic.contactOf(contacts, "anna@b.de")?.name)
        assertNull(MailLogic.contactOf(contacts.take(1), "anna@b.de"), "someone written with lately is no contact")
        assertNull(MailLogic.contactOf(listOf(MContact(null, "anna@b.de", MContact.SOURCE_PERSON)), "anna@b.de"), "without a name")
    }
}
