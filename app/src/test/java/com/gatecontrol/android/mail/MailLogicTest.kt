package com.gatecontrol.android.mail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class MailLogicTest {

    private val zone = ZoneId.of("Europe/Berlin")

    private fun s(id: Long, date: Long = 0, unread: Boolean = false) = MSummary(
        id = id, accountId = 1, folderId = null, threadId = null, threadCount = 1, from = MAddress("A", "a@b.de"), to = emptyList(),
        subject = "s$id", snippet = "", date = date, unread = unread, flagged = false, answered = false, hasAttachments = false,
        tab = MSummary.TAB_RELEVANT, savedToDrive = false, aiSummary = null,
    )

    @Test
    fun `boxes - all inboxes, one account, a folder`() {
        val inbox = MFolder(10, 3, "INBOX", "INBOX", MFolder.ROLE_INBOX, null, 0, 0)
        val sent = MFolder(11, 3, "Sent", "Sent", MFolder.ROLE_SENT, null, 0, 0)
        assertEquals(mapOf("box" to "all", "tab" to "relevant", "limit" to "50"), MailLogic.listQuery(MailLogic.Box(), "relevant", inbox = true))
        assertEquals(
            mapOf("box" to "3", "folder" to "11", "before" to "c", "unread" to "1", "limit" to "50"),
            MailLogic.listQuery(MailLogic.Box(3, 11), "relevant", inbox = false, before = "c", unread = true),
        )
        assertTrue(MailLogic.Box(3).isInbox(listOf(inbox, sent)))
        assertTrue(MailLogic.Box(3, 10).isInbox(listOf(inbox, sent)))
        assertFalse(MailLogic.Box(3, 11).isInbox(listOf(inbox, sent)))
        assertTrue(MailLogic.Box().all)
        assertEquals("box-all-0-other", MailLogic.Box().key("other"))
        assertEquals("box-3-11-all", MailLogic.Box(3, 11).key("all"))
    }

    @Test
    fun `pages - newer data replaces, more is added, changes and removals`() {
        val merged = MailLogic.merge(listOf(s(1), s(2)), listOf(s(2, unread = true), s(3)))
        assertEquals(listOf(1L, 2L, 3L), merged.map { it.id })
        assertTrue(merged[1].unread)
        val applied = MailLogic.apply(merged, listOf(s(1, unread = true)), removed = setOf(3))
        assertEquals(listOf(1L, 2L), applied.map { it.id })
        assertTrue(applied[0].unread)
    }

    @Test
    fun `swipes - only archive, delete and read act`() {
        assertEquals("archive", MailLogic.swipe("archive"))
        assertEquals("read", MailLogic.swipe("read"))
        assertNull(MailLogic.swipe("none"))
        assertNull(MailLogic.swipe(null))
        assertNull(MailLogic.swipe("explode"))
    }

    @Test
    fun `undo - the server's window when it fits, else 10 s less the call`() {
        val now = 1_000_000L
        assertEquals(9_000L, MailLogic.undoWindow(now + 9_000, now))
        assertEquals(10_000L, MailLogic.undoWindow(now + 10_000, now))
        // clocks apart (the server's time far ahead or behind): the contract's 10 s from the answer
        assertEquals(10_000L, MailLogic.undoWindow(now + 70_000, now))
        assertEquals(9_200L, MailLogic.undoWindow(now - 50_000, now, callMs = 800))
        assertEquals(10_000L, MailLogic.undoWindow(null, now))
        assertEquals(0L, MailLogic.undoWindow(null, now, callMs = 20_000))
        assertFalse(MailLogic.planned(now + 10_000, now), "the undo time is no plan")
        assertTrue(MailLogic.planned(now + 3_600_000, now))
        assertFalse(MailLogic.planned(null, now))
    }

    @Test
    fun `planning - tomorrow and Monday morning`() {
        val thursday = ZonedDateTime.of(2026, 10, 29, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
        val c = MailLogic.planChoices(thursday, zone).map { java.time.Instant.ofEpochMilli(it).atZone(zone) }
        assertEquals(LocalDate.of(2026, 10, 30), c[0].toLocalDate())
        assertEquals(8, c[0].hour)
        assertEquals(LocalDate.of(2026, 11, 2), c[1].toLocalDate())
        val sunday = ZonedDateTime.of(2026, 11, 1, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(1, MailLogic.planChoices(sunday, zone).size, "tomorrow is Monday: one choice")
    }

    @Test
    fun `the time column`() {
        val now = ZonedDateTime.of(2026, 10, 30, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        fun at(y: Int, m: Int, d: Int) = ZonedDateTime.of(y, m, d, 9, 42, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(MailLogic.When.TIME, MailLogic.whenOf(at(2026, 10, 30), now, zone))
        assertEquals(MailLogic.When.YESTERDAY, MailLogic.whenOf(at(2026, 10, 29), now, zone))
        assertEquals(MailLogic.When.WEEKDAY, MailLogic.whenOf(at(2026, 10, 26), now, zone))
        assertEquals(MailLogic.When.DATE, MailLogic.whenOf(at(2026, 3, 1), now, zone))
        assertEquals(MailLogic.When.DATE_YEAR, MailLogic.whenOf(at(2025, 12, 31), now, zone))
        assertEquals(MailLogic.When.TIME, MailLogic.whenOf(now + 3_600_000, now, zone), "a date ahead (clock skew) shows its time")
    }

    @Test
    fun `addresses - with names, lists, rest`() {
        assertEquals(MAddress("Tim Brandt", "tim@firma.de"), MailLogic.parseAddress("Tim Brandt <tim@firma.de>"))
        assertEquals(MAddress("Tim", "tim@firma.de"), MailLogic.parseAddress("\"Tim\" <tim@firma.de>"))
        assertEquals(MAddress(null, "a@b.de"), MailLogic.parseAddress(" a@b.de "))
        assertNull(MailLogic.parseAddress("no address"))
        assertNull(MailLogic.parseAddress("a@b"))
        val p = MailLogic.parseRecipients("a@b.de, Tim <tim@firma.de>; c@d.de e@f.de, Sab")
        assertEquals(listOf("a@b.de", "tim@firma.de", "c@d.de", "e@f.de"), p.addresses.map { it.email })
        assertEquals("Sab", p.rest)
        assertEquals(1, MailLogic.parseRecipients("a@b.de, A@B.de").addresses.size, "the same address once")
    }

    @Test
    fun `initials, colors, quota`() {
        assertEquals("SK", MailLogic.initials("Sabine Keller", "s@k.de"))
        assertEquals("M", MailLogic.initials("Mama", "m@x.de"))
        assertEquals("S", MailLogic.initials(null, "stadtwerke@x.de"))
        assertEquals("?", MailLogic.initials("", ""))
        assertEquals(0xFFF5B544L, MailLogic.color("#F5B544"))
        assertNull(MailLogic.color("F5B544"))
        assertNull(MailLogic.color("#GGGGGG"))
        assertEquals("#79B8FF", MailLogic.nextColor(listOf("#f5b544")))
        assertEquals(MailLogic.COLORS[0], MailLogic.nextColor(MailLogic.COLORS))
        assertEquals(50, MailLogic.quotaPercent(50, 100))
        assertEquals(100, MailLogic.quotaPercent(500, 100))
        assertNull(MailLogic.quotaPercent(5, 0))
        assertEquals(72, MailLogic.percent(0.72))
    }

    @Test
    fun `Drive files automatically only when sure`() {
        fun sug(reason: String, c: Double) = MSuggestion(1, null, null, null, null, c, reason)
        assertTrue(MailLogic.autoFiled(sug("rule", 0.1)))
        assertTrue(MailLogic.autoFiled(sug("learned", 0.1)))
        assertTrue(MailLogic.autoFiled(sug("ai", 0.9)))
        assertFalse(MailLogic.autoFiled(sug("ai", 0.89)))
        assertTrue(MailLogic.linkFirst(46L * 1024 * 1024))
        assertFalse(MailLogic.linkFirst(340L * 1024))
    }

    @Test
    fun `to-dos into a calendar`() {
        val deadline = MTodo(MTodo.DEADLINE, "Zahlen", "2026-11-28", null, null, null)
        val (start, allDay) = MailLogic.todoStart(deadline, zone)!!
        assertTrue(allDay)
        assertEquals(LocalDate.of(2026, 11, 28).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(), start)
        val event = deadline.copy(kind = MTodo.EVENT, time = "10:00")
        val (s2, all2) = MailLogic.todoStart(event, zone)!!
        assertFalse(all2)
        assertEquals(ZonedDateTime.of(2026, 11, 28, 10, 0, 0, 0, zone).toInstant().toEpochMilli(), s2)
        assertTrue(MailLogic.calendarTodo(event))
        assertFalse(MailLogic.calendarTodo(deadline.copy(kind = MTodo.QUESTION)))
        assertFalse(MailLogic.calendarTodo(deadline.copy(date = null)))
        assertNull(MailLogic.todoStart(deadline.copy(date = null), zone))
    }

    @Test
    fun `cid pictures point to the plugin's route`() {
        val atts = listOf(
            MAttachment(7, "logo.png", "image/png", 10, true, "logo@x", null, null),
            MAttachment(8, "a.pdf", "application/pdf", 10, false, null, null, null),
        )
        assertEquals(
            "<img src=\"cid/42/7\"><img src='cid/42/7'><img src=cid:unknown@x>",
            MailLogic.rewriteCid("<img src=\"cid:logo@x\"><img src='CID:LOGO@X'><img src=cid:unknown@x>", 42, atts),
        )
        assertEquals("<td background=\"cid/42/7\">", MailLogic.rewriteCid("<td background=\"cid:logo@x\">", 42, atts))
    }

    @Test
    fun `the WebView may only load the plugin's pictures`() {
        assertEquals("img/abc_DEF-1", MailLogic.interceptPath("https://mail.gatecontrol.invalid/img/abc_DEF-1"))
        assertEquals("cid/42/7", MailLogic.interceptPath("https://mail.gatecontrol.invalid/cid/42/7"))
        assertNull(MailLogic.interceptPath("https://evil.example/img/abc"), "another host")
        assertNull(MailLogic.interceptPath("http://mail.gatecontrol.invalid/img/abc"), "not https")
        assertNull(MailLogic.interceptPath("https://mail.gatecontrol.invalid/img/abc?x=1"), "no query")
        assertNull(MailLogic.interceptPath("https://mail.gatecontrol.invalid/img/../me"))
        assertNull(MailLogic.interceptPath("https://mail.gatecontrol.invalid/cid/0/7"))
        assertNull(MailLogic.interceptPath("https://mail.gatecontrol.invalid/messages/1"), "no other route")
        assertNull(MailLogic.interceptPath("not a url"))
        assertTrue(MailLogic.externalLink("https://x.de"))
        assertTrue(MailLogic.externalLink("mailto:a@b.de"))
        assertFalse(MailLogic.externalLink("javascript:alert(1)"))
        assertFalse(MailLogic.externalLink("file:///etc/passwd"))
        assertFalse(MailLogic.externalLink("intent://x#Intent;end"))
        assertEquals("a@b.de", MailLogic.mailtoAddress("mailto:a%40b.de?subject=x")!!.email)
        assertNull(MailLogic.mailtoAddress("https://x"))
    }

    @Test
    fun `the page has a strict policy and the mail inside`() {
        val page = MailLogic.page("<p>Hallo</p>")
        assertTrue(page.contains("script-src 'none'"))
        assertTrue(page.contains("img-src https://mail.gatecontrol.invalid/ data:"))
        assertTrue(page.contains("<p>Hallo</p>"))
    }

    @Test
    fun `a draft's body - typed text and quote apart and together`() {
        val html = MailLogic.composeHtml("Hallo <Tim>\nbis morgen", "<blockquote>alt</blockquote>")
        assertTrue(html.contains("Hallo &lt;Tim&gt;<br>bis morgen"))
        assertEquals("Hallo <Tim>\nbis morgen" to "<blockquote>alt</blockquote>", MailLogic.splitDraft(html))
        // the server's prefilled answer: all quote
        assertEquals("" to "<br><blockquote>alt</blockquote>", MailLogic.splitDraft("<br><blockquote>alt</blockquote>"))
        // another app's draft: all text
        assertEquals("Eins\nZwei", MailLogic.splitDraft("<p>Eins</p><p>Zwei</p>").first)
        assertEquals("a & b", MailLogic.htmlToText("a &amp; b"))
        assertEquals("Hi\n\n-- \nGruß", MailLogic.withSignature("Hi", "Gruß"))
        assertEquals("Hi", MailLogic.withSignature("Hi", " "))
        assertEquals("Hi\n\n-- \nGruß", MailLogic.withSignature("Hi\n\n-- \nGruß", "Gruß"), "not twice")
        assertEquals("Danke!\n\nText", MailLogic.withReply("Danke!", "Text"))
        assertEquals("Danke!", MailLogic.withReply("Danke!", " "))
    }

    @Test
    fun `attachments - type labels and file names`() {
        assertEquals("PDF", MailLogic.typeLabel("x.pdf", "application/octet-stream"))
        assertEquals("XLS", MailLogic.typeLabel("Preisliste.xlsx", ""))
        assertEquals("IMG", MailLogic.typeLabel("foto", "image/jpeg"))
        assertEquals("ICS", MailLogic.typeLabel("einladung.ics", ""))
        assertEquals("FILE", MailLogic.typeLabel("noext", ""))
        assertEquals("a_b.pdf", MailLogic.fileName("dir/a:b.pdf"))
        assertEquals("attachment", MailLogic.fileName(".."))
        assertEquals("attachment", MailLogic.fileName(null))
        assertNotNull(MailLogic.isEmail("a@b.de"))
    }
}
