package com.gatecontrol.android.tv.mail

import com.gatecontrol.android.mail.MAddress
import com.gatecontrol.android.mail.MAi
import com.gatecontrol.android.mail.MContact
import com.gatecontrol.android.mail.MDraftAttachment
import com.gatecontrol.android.mail.MReply
import com.gatecontrol.android.mail.MailLogic
import com.gatecontrol.android.scan.MediaScan
import com.gatecontrol.android.tv.input.TvSnippetKind
import com.gatecontrol.android.tv.mail.TvMailFixtures.account
import com.gatecontrol.android.tv.mail.TvMailFixtures.attachment
import com.gatecontrol.android.tv.mail.TvMailFixtures.draft
import com.gatecontrol.android.tv.mail.TvMailFixtures.me
import com.gatecontrol.android.tv.mail.TvMailFixtures.message
import com.gatecontrol.android.tv.mail.TvMailFixtures.summary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TvMailLogicTest {

    @Test
    fun `only the chosen accounts show, in the persons order`() {
        val me = me(account(1), account(2), account(3))
        assertEquals(listOf(1L, 3L), TvMailLogic.accounts(me, setOf(3, 1, 9)).map { it.id })
        assertTrue(TvMailLogic.accounts(me, emptySet()).isEmpty())
        assertTrue(TvMailLogic.accounts(null, setOf(1)).isEmpty())
        assertEquals(setOf(1L, 4L), TvMailLogic.cleanAccounts(listOf(1, 0, -3, 4, 1)))
    }

    @Test
    fun `the inbox of all chosen accounts - newest first, each mail once, never an account switched off`() {
        val lists = mapOf(
            1L to listOf(summary(10, 1, date = 5_000), summary(11, 1, date = 9_000)),
            2L to listOf(summary(20, 2, date = 7_000), summary(11, 1, date = 9_000)),
            3L to listOf(summary(30, 3, date = 99_000)),
        )
        assertEquals(listOf(11L, 20L, 10L), TvMailLogic.inbox(lists, setOf(1, 2)).map { it.id })
        assertEquals(listOf(11L), TvMailLogic.inbox(lists, setOf(1, 2), max = 1).map { it.id })
        assertTrue(TvMailLogic.inbox(lists, emptySet()).isEmpty())
        // same time: the higher id first
        assertEquals(listOf(2L, 1L), TvMailLogic.inbox(mapOf(1L to listOf(summary(1, date = 5), summary(2, date = 5))), setOf(1)).map { it.id })
    }

    @Test
    fun `one accounts inbox, both tabs`() {
        val q = TvMailLogic.inboxQuery(7)
        assertEquals("7", q["box"])
        assertEquals("all", q["tab"])
        assertEquals(TvMailLogic.PAGE.toString(), q["limit"])
    }

    @Test
    fun `the text in large type - plain text, else the HTML as text, else the snippet, tidied`() {
        assertEquals("Hallo,\nbis morgen.", TvMailLogic.body(message(1)))
        assertEquals("Erste Zeile\nZweite", TvMailLogic.body(message(1, text = null, html = "<p>Erste Zeile</p><p>Zweite</p>")))
        assertEquals("Anfang 1", TvMailLogic.body(message(1, text = " ", html = null)))
        assertEquals("a\n\nb", TvMailLogic.body(message(1, text = "a   \n\n\n\n\nb  ")))
        val long = TvMailLogic.body(message(1, text = "x".repeat(TvMailLogic.MAX_BODY + 50)))
        assertTrue(long.endsWith(" …"))
        assertTrue(long.length <= TvMailLogic.MAX_BODY + 2)
        assertTrue(TvMailLogic.hasHtml(message(1, html = "<p>x</p>")))
        assertFalse(TvMailLogic.hasHtml(message(1)))
    }

    @Test
    fun `attachments - photos and PDFs open, anything else, blocked or huge ones are a name only`() {
        val photo = attachment(1, "urlaub.jpg", "image/jpeg")
        val pdf = attachment(2, "rechnung.pdf", "application/pdf")
        val pdfByName = attachment(3, "Brief.PDF", "application/octet-stream")
        val doc = attachment(4, "liste.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        val inline = attachment(5, "logo.png", "image/png", inline = true)
        val blocked = attachment(6, "scan.pdf", "application/pdf").copy(scan = MediaScan(MediaScan.INFECTED, blocked = true))
        val huge = attachment(7, "video.jpg", "image/jpeg", size = TvMailLogic.MAX_OPEN_BYTES + 1)
        assertEquals(TvAttachmentKind.PHOTO, TvMailLogic.kind(photo))
        assertEquals(TvAttachmentKind.PDF, TvMailLogic.kind(pdf))
        assertEquals(TvAttachmentKind.PDF, TvMailLogic.kind(pdfByName))
        assertEquals(TvAttachmentKind.OTHER, TvMailLogic.kind(doc))
        assertTrue(TvMailLogic.canOpen(photo))
        assertTrue(TvMailLogic.canOpen(pdfByName))
        assertFalse(TvMailLogic.canOpen(doc))
        assertFalse(TvMailLogic.canOpen(blocked))
        assertFalse(TvMailLogic.canOpen(huge))
        val m = message(1, attachments = listOf(photo, inline, doc))
        assertEquals(listOf(1L, 4L), TvMailLogic.attachments(m).map { it.id })
        assertEquals("m9_a2.pdf", TvMailLogic.cacheName(9, pdf))
        assertEquals("m9_a3.pdf", TvMailLogic.cacheName(9, attachment(3, "ohne", "application/pdf")))
    }

    @Test
    fun `the AI - only when the server and the account have it, its answers as building blocks`() {
        assertTrue(TvMailLogic.aiOn(me(account(1, ai = true), ai = true), 1))
        assertFalse(TvMailLogic.aiOn(me(account(1, ai = false), ai = true), 1))
        assertFalse(TvMailLogic.aiOn(me(account(1, ai = true), ai = false), 1))
        val ai = MAi(true, "Mila kommt morgen.", emptyList(), listOf(MReply("Ja", "Ja, passt."), MReply("Nein", " "), MReply("Ja", "Ja, passt.")))
        val snippets = TvMailLogic.aiSnippets(ai)
        assertEquals(listOf("Ja, passt."), snippets.map { it.text })
        assertEquals(TvSnippetKind.AI, snippets.single().kind)
        assertTrue(TvMailLogic.aiSnippets(null).isEmpty())
    }

    @Test
    fun `the draft - created with mode only, the text above the servers quote, attachments kept`() {
        val create = TvMailLogic.createBody(TvMailMode.REPLY_ALL, 42)
        assertEquals(42L, create["replyTo"].asLong)
        assertEquals("replyAll", create["mode"].asString)
        assertFalse(create.has("attachments"))
        assertFalse(create.has("to"))

        val d = draft(5, attachments = listOf(MDraftAttachment(8, "a.pdf", 10, "application/pdf", false, null)))
        val reply = TvMailLogic.updateBody(d, TvMailMode.REPLY, 42, "Bis morgen <3")
        assertFalse(reply.has("to"))
        assertEquals("reply", reply["mode"].asString)
        assertEquals(42L, reply["replyTo"].asLong)
        assertEquals(MailLogic.composeHtml("Bis morgen <3", "<blockquote>Zitat</blockquote>"), reply["html"].asString)
        assertEquals(8L, reply["attachments"].asJsonArray.single().asJsonObject["id"].asLong)
        // written again after „Rückgängig“: the quote stays once
        val again = d.copy(html = reply["html"].asString)
        assertEquals("Bis morgen <3", TvMailLogic.written(again))
        assertEquals(MailLogic.composeHtml("Neu", "<blockquote>Zitat</blockquote>"), TvMailLogic.updateBody(again, TvMailMode.REPLY, 42, "Neu")["html"].asString)

        val fwd = TvMailLogic.updateBody(d, TvMailMode.FORWARD, 42, "Schau mal", to = listOf(MAddress("Jonas", "jonas@example.com")))
        assertEquals("jonas@example.com", fwd["to"].asJsonArray.single().asJsonObject["email"].asString)
        assertEquals("", TvMailLogic.written(draft(1)))
        assertEquals(listOf("Mila"), TvMailLogic.recipients(d))
    }

    @Test
    fun `forward targets - people Mail knows, each address once, never a group or a bad address`() {
        val list = listOf(
            MContact("Jonas", "jonas@example.com", MContact.SOURCE_CONTACT),
            MContact(null, "JONAS@example.com", MContact.SOURCE_RECENT),
            MContact("Familie", "", MContact.SOURCE_GROUP, groupId = 3),
            MContact("Kaputt", "kein-at", MContact.SOURCE_RECENT),
            MContact("  ", "mila@example.com", MContact.SOURCE_PERSON),
        )
        val t = TvMailLogic.forwardTargets(list)
        assertEquals(listOf("jonas@example.com", "mila@example.com"), t.map { it.email })
        assertNull(t[1].name)
        assertEquals(1, TvMailLogic.forwardTargets(list, max = 1).size)
    }

    @Test
    fun `undo window after sending - the servers, never longer than 10 s`() {
        assertEquals(10_000L, TvMailLogic.undoWindow(null, 1_000, 0))
        assertEquals(6_000L, TvMailLogic.undoWindow(7_000, 1_000, 0))
        assertEquals(10_000L - 400, TvMailLogic.undoWindow(60_000, 1_000, 400))
    }

    @Test
    fun `news - per sender, newest first, only unread`() {
        val a = summary(1, date = 1_000, unread = true, from = "mila@example.com")
        val b = summary(2, date = 5_000, unread = true, from = "jonas@example.com")
        val c = summary(3, date = 3_000, unread = true, from = "MILA@example.com")
        val d = summary(4, date = 9_000, unread = false, from = "lea@example.com")
        val news = TvMailLogic.news(listOf(a, b, c, d))
        assertEquals(listOf(2L, 3L), news.map { it.newest.id })
        assertEquals(listOf(1, 2), news.map { it.count })
        assertEquals(1, TvMailLogic.news(listOf(a, b, c), max = 1).size)
    }
}
