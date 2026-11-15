package com.gatecontrol.android.chat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Messenger 1.18.0, "Neuer Chat" with contacts: the feature word, `GET contacts/new-chat` as server/contactchat.js
 * sends it (sections, states, what is dropped), the order of the numbers, the refusals of a tap — and that no name or
 * number gets into `toString()`.
 */
class ChatNewContactsTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val ref = "k" + "A".repeat(48)

    private val full = """{
        "network":"whatsapp","query":"",
        "gatecontrol":[{"userId":3,"name":"Marc","avatarUrl":null,"initials":"MA","colorHex":"#84CC16","conversationId":12},
                       {"userId":4,"name":"Anna","initials":"AN","colorHex":"#F97316","conversationId":null}],
        "whatsapp":{"state":"ok","total":86,"items":[
          {"ref":"$ref","name":"Lena Berger","avatarMediaId":null,"avatarUrl":null,"conversationId":null,"alsoInAddressBook":true,"addressBookId":13},
          {"ref":"bad","name":"Kein Ref"},
          {"ref":"${ref}B","name":"Kim Muster","conversationId":31,"alsoInAddressBook":false}]},
        "addressBook":{"state":"ok","total":41,"items":[
          {"contactId":12,"name":"Sabine Wolf","conversationId":null,"phones":[
            {"key":"home","kind":"home","number":"+49 30 12345678","e164":"+493012345678","landline":true,"notOnNetwork":false},
            {"key":"mobile","kind":"mobile","number":"+49 151 23456780","e164":"+4915123456780","landline":false,"notOnNetwork":false},
            {"key":"work","kind":"work","number":"+49 170 2345678","e164":"+491702345678"},
            {"key":"pager","kind":"pager","number":"1"}]},
          {"contactId":14,"name":"Ohne Nummer","phones":[]}]},
        "addressBookReason":null,
        "number":null,"numberEntry":true,
        "limit":{"perHour":10,"left":7,"retryAfterS":0}
    }"""

    @Test
    fun `bridge_contacts is a word this app knows - no update hint for it`() {
        val f = ChatFeatures(tokens = setOf("bridge_chats", "bridge_new_chat", "bridge_history", "bridge_contacts"))
        assertTrue(ChatFeatureHint.unknown(f).isEmpty())
        assertTrue(ChatNewContacts.enabled(f))
        assertFalse(ChatNewContacts.enabled(ChatFeatures(tokens = setOf("bridge_new_chat"))))
        assertFalse(ChatNewContacts.enabled(null))
    }

    @Test
    fun `the list - three sections, rows without a reference or a number dropped`() {
        val d = ChatNewContacts.list(json(full))!!
        assertEquals(listOf(3L, 4L), d.gatecontrol.map { it.person.userId })
        assertEquals(12L, d.gatecontrol[0].conversationId)
        assertNull(d.gatecontrol[1].conversationId)
        val wa = d.whatsapp!!
        assertTrue(wa.ok)
        assertEquals(86, wa.total)
        assertEquals(listOf("Lena Berger", "Kim Muster"), wa.items.map { it.name })
        assertTrue(wa.items[0].alsoInAddressBook)
        assertEquals(31L, wa.items[1].conversationId)
        val book = d.addressBook!!
        assertEquals(listOf(12L), book.items.map { it.contactId })
        // the numbers as delivered — only keys this app knows
        assertEquals(listOf("home", "mobile", "work"), book.items[0].phones.map { it.key })
        assertEquals("+49 30 12345678", book.items[0].phones[0].number)
        assertTrue(book.items[0].phones[0].landline)
        assertTrue(d.numberEntry)
        assertEquals(10, d.perHour)
        assertNull(d.number)
    }

    @Test
    fun `the numbers of a contact - mobile first, then work, home, other`() {
        val c = ChatNewContacts.list(json(full))!!.addressBook!!.items[0]
        assertEquals(listOf("mobile", "work", "home"), ChatNewContacts.ordered(c.phones).map { it.key })
        assertEquals(ChatNewContacts.KIND_OTHER, ChatNewContacts.kindOf(ChatBookPhone("other-2", "other", "1", false, false)))
        assertNull(ChatNewContacts.single(c))
        assertEquals("Sabine", ChatNewContacts.firstName("Sabine Wolf"))
    }

    @Test
    fun `WhatsApp signed out or away - the section says so, the address book is there but greyed`() {
        val d = ChatNewContacts.list(
            json(
                """{"gatecontrol":[],"whatsapp":{"state":"logged_out","items":[],"total":0},
                "addressBook":{"state":"logged_out","total":1,"items":[{"contactId":5,"name":"Paul Hoffmann","phones":[{"key":"mobile","kind":"mobile","number":"+49 160 98765432"}]}]},
                "numberEntry":true}""",
            ),
        )!!
        assertEquals(ChatNewContacts.STATE_LOGGED_OUT, d.whatsapp!!.state)
        assertFalse(d.whatsapp!!.ok)
        // the address book shows (the rows), even though its state follows WhatsApp
        assertEquals(1, d.addressBook!!.items.size)
        // a state of a newer server: like "not reachable"
        val n = ChatNewContacts.list(json("""{"gatecontrol":[],"whatsapp":{"state":"paused","items":[]},"numberEntry":true}"""))!!
        assertEquals(ChatNewContacts.STATE_UNREACHABLE, n.whatsapp!!.state)
    }

    @Test
    fun `without WhatsApp - neither the address book, nor a number line, nor Nummer eingeben`() {
        val d = ChatNewContacts.list(
            json(
                """{"gatecontrol":[{"userId":3,"name":"Marc"}],"whatsapp":null,
                "addressBook":{"state":"ok","items":[{"contactId":5,"name":"X","phones":[{"key":"mobile","number":"+49 1"}]}]},
                "number":{"e164":"+4917155501230","show":"+49 171 55501230","conversationId":null},"numberEntry":true}""",
            ),
        )!!
        assertNull(d.whatsapp)
        assertNull(d.addressBook)
        assertNull(d.number)
        assertFalse(d.numberEntry)
    }

    @Test
    fun `a search that looks like a number - the last line`() {
        val d = ChatNewContacts.list(
            json("""{"gatecontrol":[],"whatsapp":{"state":"ok","items":[],"total":0},"addressBook":null,"number":{"e164":"+4917155501230","show":"+49 171 55501230","conversationId":null},"numberEntry":true}"""),
        )!!
        assertEquals("+4917155501230", d.number!!.e164)
        assertEquals("+49 171 55501230", d.number!!.show)
        assertNull(d.addressBook)
        // not a number with its country code: no line
        val bad = ChatNewContacts.list(json("""{"gatecontrol":[],"whatsapp":{"state":"ok","items":[]},"number":{"e164":"0171","show":"0171"}}"""))!!
        assertNull(bad.number)
    }

    @Test
    fun `the search - trimmed, control characters out, at most 100`() {
        assertEquals("ber", ChatNewContacts.query("  ber\n"))
        assertEquals(100, ChatNewContacts.query("x".repeat(150)).length)
    }

    @Test
    fun `long sections - three rows and Alle anzeigen, all with a search or opened`() {
        val five = (1..5).toList()
        assertEquals(3, ChatNewContacts.visible(five, searching = false, expanded = false).size)
        assertTrue(ChatNewContacts.hasMore(five, searching = false, expanded = false))
        assertEquals(5, ChatNewContacts.visible(five, searching = true, expanded = false).size)
        assertFalse(ChatNewContacts.hasMore(five, searching = true, expanded = false))
        assertEquals(5, ChatNewContacts.visible(five, searching = false, expanded = true).size)
        assertFalse(ChatNewContacts.hasMore(listOf(1, 2, 3), searching = false, expanded = false))
    }

    @Test
    fun `refusals of a tap`() {
        fun r(code: String, http: Int, reason: String? = null) = ChatNewContacts.refusal(ChatResult.Failed(code, http, reason))
        assertEquals(ChatNewContacts.Refusal.RELOAD, r("contact_expired", 409))
        assertEquals(ChatNewContacts.Refusal.RELOAD, r("bridge_logged_out", 409))
        assertEquals(ChatNewContacts.Refusal.RELOAD, r("not_found", 404, "contact_gone"))
        assertEquals(ChatNewContacts.Refusal.NOT_ON, r("not_found", 404, "not_on_network"))
        assertEquals(ChatNewContacts.Refusal.RATE_LIMITED, r("rate_limited", 429))
        assertEquals(ChatNewContacts.Refusal.UNREACHABLE, r("bridge_unreachable", 503))
        assertEquals(ChatNewContacts.Refusal.UNREACHABLE, r("bridge_off", 409))
        assertEquals(ChatNewContacts.Refusal.MAIL_MISSING, r("mail_missing", 409))
        assertEquals(ChatNewContacts.Refusal.FAILED, r("bridge_locked", 403))
        assertEquals(ChatNewContacts.Refusal.OFFLINE, ChatNewContacts.refusal(ChatResult.Offline))
    }

    @Test
    fun `no name, no number, no reference in toString`() {
        val d = ChatNewContacts.list(json(full))!!
        val text = listOf(d.toString(), d.whatsapp!!.items.toString(), d.addressBook!!.items.toString(), d.addressBook!!.items[0].phones.toString(), d.gatecontrol.toString()).joinToString()
        for (secret in listOf("Lena", "Sabine", "Marc", "+49", "4915", ref)) assertFalse(text.contains(secret), secret)
        assertFalse(ChatNumberHit("+4917155501230", "+49 171 55501230", null).toString().contains("171"))
        assertFalse(ChatNumberStarted.Line("+49 1", "Paul", ChatNumberStarted.Kind.BOOK).toString().contains("Paul"))
    }
}
