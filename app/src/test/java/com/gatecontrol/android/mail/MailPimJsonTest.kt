package com.gatecontrol.android.mail

import com.gatecontrol.android.notify.model.PushMessage
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MailPimJsonTest {

    private fun o(s: String) = JsonParser.parseString(s).asJsonObject

    @Test
    fun `me - calendar and contacts features, account sides, missing scopes, consent link`() {
        val me = MailJson.me(
            o(
                """{"features":["mail","calendar","contacts","dav"],"microsoft":{"clientId":"c","adminConsentUrl":"https://login.microsoftonline.com/x/adminconsent"},
                "accounts":[{"id":3,"email":"a@firma.de","provider":"m365","calendar":{"sync":true,"state":"consent_required","readOnly":false,"server":"graph.microsoft.com"},
                "contacts":{"sync":false,"state":"off","readOnly":false,"server":null},"missingScopes":["Calendars.ReadWrite","Contacts.ReadWrite"]},
                {"id":4,"email":"b@gmx.de","provider":"gmx"}]}""",
            ),
        )!!
        assertTrue(me.has(MMe.FEATURE_CALENDAR))
        assertTrue(me.has(MMe.FEATURE_CONTACTS))
        assertEquals("https://login.microsoftonline.com/x/adminconsent", me.microsoft.adminConsentUrl)
        val a = me.account(3)!!
        assertEquals(MPimSide.STATE_CONSENT, a.calendar?.state)
        assertEquals(MPimSide.STATE_OFF, a.contacts?.state)
        assertFalse(a.contacts!!.sync)
        assertEquals(listOf(MailMicrosoft.CALENDAR_SCOPE, MailMicrosoft.CONTACTS_SCOPE), a.missingScopes)
        // an older server: no sides, nothing missing
        assertNull(me.account(4)!!.calendar)
        assertTrue(me.account(4)!!.missingScopes.isEmpty())
        assertNull(MailJson.me(o("""{"features":["mail"],"microsoft":{"adminConsentUrl":"http://x"}}"""))!!.microsoft.adminConsentUrl)
    }

    @Test
    fun `suggestions - contacts, groups with their members, the old form still read`() {
        val list = MailJson.contacts(
            o(
                """{"items":[{"name":"Anna Lang","email":"anna@x.de","source":"contact","contactId":7,"photo":true},
                {"name":"Familie (2)","email":null,"source":"group","groupId":3,"members":[{"name":"Mama","email":"m@x.de"},{"email":"p@x.de"}]},
                {"name":"","email":null,"source":"group","groupId":4,"members":[]},
                {"name":"Tim","email":"tim@firma.de","source":"person"},{"email":"old@x.de"}]}""",
            ),
        )!!
        assertEquals(4, list.size)
        assertEquals(7L, list[0].contactId)
        assertTrue(list[0].photo)
        assertTrue(list[1].group)
        assertEquals(listOf("m@x.de", "p@x.de"), list[1].members.map { it.email })
        assertEquals(MContact.SOURCE_RECENT, list[3].source)
    }

    @Test
    fun `summary and message - the invitation label and card`() {
        val s = MailJson.summary(o("""{"id":1,"accountId":2,"from":{"email":"a@b.de"},"invite":"request"}"""))!!
        assertEquals("request", s.invite)
        assertNull(MailJson.summary(o("""{"id":1,"accountId":2,"from":{"email":"a@b.de"},"invite":"weird"}"""))!!.invite)
        val m = MailJson.message(o("""{"id":1,"accountId":2,"from":{"email":"a@b.de"},"invite":$INVITATION}"""))!!
        assertEquals(9L, m.invite?.id)
        assertNull(MailJson.message(o("""{"id":1,"accountId":2,"from":{"email":"a@b.de"},"invite":null}"""))!!.invite)
    }

    @Test
    fun `calendars - list, overlays, settings, consent`() {
        val c = MailPimJson.calendars(
            o(
                """{"calendars":[{"id":1,"source":"local","accountId":null,"name":"Privat","color":"#46e0a0","kind":"private","writable":true,"visible":true,"notify":true,
                "isDefault":true,"canInvite":true,"canOnline":false,"maxReminders":3,"state":"ok","error":null,"syncedAt":null,"version":4},
                {"id":2,"source":"graph","accountId":3,"name":"Kalender","color":"bad","writable":true,"visible":false,"maxReminders":1,"state":"syncing","error":{"code":"unreachable","at":5}},
                {"id":0,"name":"kaputt"}],
                "overlays":[{"id":"alltag:7","source":"alltag","name":"Abfuhr","color":"#8C96A5","visible":false}],
                "prefs":{"defaultCalendarId":1,"view":"week","weekStart":0,"workWeek":true,"timeZone":"Europe/Berlin","reminders":true,"defaultReminder":null,"allDayReminder":1080},
                "consent":[{"accountId":3,"missing":["Calendars.ReadWrite"]}]}""",
            ),
        )!!
        assertEquals(listOf(1L, 2L), c.calendars.map { it.id })
        assertEquals("#46E0A0", c.calendars[0].color)
        assertEquals(1, c.calendars[1].maxReminders)
        assertEquals("unreachable", c.calendars[1].errorCode)
        assertFalse(c.calendars[1].visible)
        assertEquals("alltag:7", c.overlays.single().id)
        assertEquals("week", c.prefs.view)
        assertEquals(0, c.prefs.weekStart)
        assertNull(c.prefs.defaultReminder)
        assertEquals(1080, c.prefs.allDayReminder)
        assertEquals(3L, c.consent.single().accountId)
    }

    @Test
    fun `events - occurrences, overlays, pending, all-day dates`() {
        val e = MailPimJson.events(
            o(
                """{"items":[{"key":"5:1730800800000","eventId":5,"calendarId":1,"occurrence":"1730800800000","title":"Weekly","start":1730800800000,"end":1730804400000,
                "allDay":false,"timeZone":"Europe/Berlin","location":"Raum 1","online":{"url":"https://teams.microsoft.com/l/x","provider":"teams"},"status":"confirmed",
                "busy":"busy","myResponse":"needs_action","recurring":true,"attendees":4,"private":false,"reminder":15},
                {"eventId":6,"calendarId":1,"title":"Urlaub","start":1,"end":2,"allDay":true,"startDate":"2026-11-04","online":{"url":"javascript:alert(1)"}},
                {"eventId":7,"title":"ohne Beginn"}],
                "overlays":[{"key":"bday:3:2026","overlayId":"birthdays","title":"Anna Lang (40)","start":1,"end":2,"allDay":true,"startDate":"2026-03-14","endDate":"2026-03-14","readOnly":true}],
                "pending":[2]}""",
            ),
        )!!
        assertEquals(2, e.items.size)
        val w = e.items[0]
        assertTrue(w.open)
        assertTrue(w.recurring)
        assertEquals("teams", w.online?.provider)
        assertEquals("1730800800000", w.occurrence)
        val u = e.items[1]
        assertEquals("6", u.key)
        assertEquals("2026-11-04", u.endDate)
        assertNull(u.online)
        assertEquals("birthdays", e.overlays.single().overlayId)
        assertEquals(listOf(2L), e.pending)
    }

    @Test
    fun `event - attendees, recurrence, rights, etag, series`() {
        val ev = MailPimJson.event(o(EVENT))!!
        assertEquals(5L, ev.id)
        assertEquals("Sabine Keller", ev.organizer?.label)
        assertEquals(2, ev.attendees.size)
        assertEquals(11L, ev.attendees[0].contactId)
        assertTrue(ev.attendees[1].self)
        val r = ev.recurrence!!
        assertEquals(MRule.WEEKLY, r.rule.freq)
        assertEquals(listOf("TU", "TH"), r.rule.byDay)
        assertTrue(r.editable)
        assertEquals(listOf("1730800800000"), r.exceptions)
        assertTrue(ev.canRespond)
        assertFalse(ev.canEdit)
        assertEquals("\"1700-2\"", ev.etag)
        assertEquals(listOf(15), ev.reminders)
        assertEquals(1730800000000L, ev.series?.start)
        // an all-day series with its dates; a single event without one
        val allDay = MailPimJson.event(o("""{"eventId":8,"start":1,"end":2,"allDay":true,"startDate":"2026-01-05","series":{"start":1,"end":2,"allDay":true,"startDate":"2026-01-05","endDate":"2026-01-05"}}"""))!!
        assertEquals("2026-01-05", allDay.series?.startDate)
        assertNull(MailPimJson.event(o("""{"eventId":9,"start":1,"end":2,"series":null}"""))!!.series)
        // a rule without the form's fields is read from the RRULE
        val raw = MailPimJson.event(o("""{"eventId":9,"start":1,"end":2,"recurrence":{"rrule":"FREQ=MONTHLY;BYDAY=-1FR","editable":true}}"""))!!
        assertEquals(-1, raw.recurrence?.rule?.bySetPos)
    }

    @Test
    fun `saving - the answer, the conflict's newer state, the series refusal`() {
        val saved = MailPimJson.eventSaved(o("""{"event":$EVENT,"sent":{"mail":2,"internal":1}}"""))!!
        assertEquals(2, saved.sentMail)
        assertEquals(1, saved.sentInternal)
        // MResult.Failed carries the error object
        val conflict = MailPimJson.conflictEvent(o("""{"code":"conflict","current":$EVENT}"""))
        assertEquals("\"1700-2\"", conflict?.etag)
        assertNotNull(MailPimJson.conflictEvent(o("""{"error":{"code":"conflict","current":$EVENT}}""")))
        assertNull(MailPimJson.conflictEvent(o("""{"code":"conflict"}""")))
        val card = MailPimJson.conflictCard(o("""{"code":"conflict","current":{"id":4,"etag":"\"3\"","name":{"given":"Anna"}}}"""))
        assertEquals("\"3\"", card?.etag)
    }

    @Test
    fun `invitation - request with day and conflicts, counter, reply`() {
        val inv = MailPimJson.invitation(o(INVITATION))!!
        assertEquals(MInvitation.METHOD_REQUEST, inv.method)
        assertEquals("Wöchentlich (Di)", inv.recurrenceDe)
        assertEquals(1, inv.counts.accepted)
        assertEquals(3, inv.counts.total)
        assertEquals(listOf(1L, 2L), inv.calendarChoices)
        assertEquals("Zahnarzt", inv.conflicts.single().title)
        assertEquals(1, inv.day.size)
        val counter = MailPimJson.invitation(o("""{"id":3,"method":"counter","start":10,"end":20,"organizer":{"name":"Ich","email":"me@x.de"},"from":{"name":"Anna","email":"a@x.de"},"proposed":{"start":30,"end":40}}"""))!!
        assertEquals(30L, counter.proposedStart)
        assertEquals("Anna", counter.from.label)
        val answer = MailPimJson.inviteAnswer(o("""{"invitation":$INVITATION,"event":null}"""))!!
        assertNull(answer.event)
    }

    @Test
    fun `contacts - books, groups, page, card, lookup, duplicates, export`() {
        val books = MailPimJson.books(
            o(
                """{"items":[{"id":1,"source":"local","name":"Meine Kontakte","writable":true,"visible":true,"isDefault":true,"count":58,"state":"ok"},
                {"id":2,"source":"graph","accountId":3,"name":"Firma","writable":true,"visible":false,"count":121,"state":"syncing"}],
                "groups":[{"id":5,"name":"Familie","color":null,"count":6}],"prefs":{"sort":"given","show":"family_given","defaultAddressbookId":1,"birthdays":false}}""",
            ),
        )!!
        assertEquals(2, books.items.size)
        assertFalse(books.book(2)!!.visible)
        assertEquals("Familie", books.groups.single().name)
        assertEquals("given", books.prefs.sort)
        assertFalse(books.prefs.birthdays)
        val page = MailPimJson.cardPage(
            o(
                """{"items":[{"id":4,"addressbookId":1,"name":"Ärzte Haus","sortKey":"ARZTE HAUS","company":"Praxis","emails":["a@x.de","kaputt"],"phone":"0221",
                "photo":true,"photoEtag":"e1","groups":[5],"linked":[9]}],"next":"abc","version":17,"deleted":[3]}""",
            ),
        )!!
        val c = page.items.single()
        assertEquals("A", c.letter)
        assertEquals(listOf("a@x.de"), c.emails)
        assertEquals(listOf(9L), c.linked)
        assertEquals(17L, page.version)
        assertEquals(listOf(3L), page.deleted)
        val card = MailPimJson.fullCard(o(CARD))!!
        assertEquals("Sabine", card.name.given)
        assertEquals(2, card.emails.size)
        assertTrue(card.emails[0].primary)
        assertEquals("mobile", card.phones[0].type)
        assertEquals("Köln", card.addresses.single().city)
        assertEquals("--03-14", card.birthday)
        assertEquals("e2", card.photoEtag)
        assertEquals("graph", card.linked.single().source)
        assertNull(MailPimJson.fullCard(o("""{"name":{"given":"x"}}""")))
        val l = MailPimJson.lookup(o("""{"card":$CARD,"person":{"userId":5,"name":"Sabine"},"recent":{"name":"S. K.","sent":3,"received":7,"lastAt":9}}"""))
        assertEquals(4L, l.card?.id)
        assertEquals("Sabine", l.personName)
        assertEquals(7, l.received)
        assertNull(MailPimJson.lookup(o("""{"card":null,"person":null,"recent":null}""")).card)
        val dups = MailPimJson.duplicates(o("""{"items":[{"key":"k","reason":"email","cards":[{"id":1,"name":"A"},{"id":2,"name":"A"}]},{"key":"one","cards":[{"id":3}]}]}"""))!!
        assertEquals(1, dups.size)
        assertEquals("email", dups[0].reason)
        val ex = MailPimJson.export(o("""{"name":"kontakte.vcf","mime":"text/vcard","data":"QkVHSU46VkNBUkQ="}"""))!!
        assertEquals("BEGIN:VCARD", String(ex.bytes))
        assertEquals("kontakte.vcf", MailPimJson.export(o("""{"name":"../evil.sh","data":""}"""))!!.name)
        assertNull(MailPimJson.photo(o("""{"mime":"image/svg+xml","data":"QQ=="}""")))
        assertEquals(3, MailPimJson.importAnswer(o("""{"imported":3,"updated":1,"skipped":0}"""))!!.imported)
    }

    @Test
    fun `live events - calendar and contacts`() {
        val c = MailPimJson.calChanged(o("""{"calendar":{"kind":"changed","calendarId":2,"from":1,"to":2}}"""))!!
        assertEquals(2L, c.calendarId)
        assertEquals(MCalChanged.INVITATION, MailPimJson.calChanged(o("""{"kind":"invitation","invitationId":4,"messageId":8}"""))!!.kind)
        assertNull(MailPimJson.calChanged(o("""{"kind":"exploded"}""")))
        val k = MailPimJson.contactsChanged(o("""{"kind":"changed","addressbookId":1,"contactIds":[4,5]}"""))!!
        assertEquals(listOf(4L, 5L), k.contactIds)
        assertEquals(MContactsChanged.GROUPS, MailPimJson.contactsChanged(o("""{"contacts":{"kind":"groups"}}"""))!!.kind)
    }

    @Test
    fun `request bodies - the event's fields, cleared ones as null, the rule, the series' scope`() {
        val body = MailPimJson.eventBody(
            MEventInput(
                title = " Treffen ", allDay = false, start = 10, end = 20, timeZone = "Europe/Berlin", location = "", onlineSet = true,
                recurrence = null, recurrenceSet = true, reminders = listOf(30, 15, 15), attendees = listOf(MAttendeeInput("a@x.de", "Anna")), sendInvites = true,
            ),
            scope = "occurrence",
            occurrence = "1730800800000",
            etag = "\"1-1\"",
        )
        assertEquals("Treffen", body.get("title").asString)
        assertTrue(body.get("location").isJsonNull)
        assertTrue(body.get("online").isJsonNull)
        assertTrue(body.get("recurrence").isJsonNull)
        assertEquals("[15,30]", body.get("reminders").toString())
        assertEquals("occurrence", body.get("scope").asString)
        assertEquals("\"1-1\"", body.get("etag").asString)
        assertFalse(body.has("startDate"))
        val allDay = MailPimJson.eventBody(MEventInput(allDay = true, start = 1, startDate = "2026-11-04", endDate = "2026-11-05", teams = true, onlineSet = true, recurrence = MRule(MRule.MONTHLY, byDay = listOf("TU"), bySetPos = 2), recurrenceSet = true))
        assertFalse(allDay.has("start"))
        assertEquals("teams", allDay.getAsJsonObject("online").get("create").asString)
        assertEquals(2, allDay.getAsJsonObject("recurrence").get("bySetPos").asInt)
        val respond = MailPimJson.respondBody("tentative", " ", proposeStart = 5, proposeEnd = 4)
        assertFalse(respond.has("comment"))
        assertFalse(respond.has("proposeStart"))
    }

    @Test
    fun `request bodies - a contact in full, cleared fields as null, merge picks`() {
        val c = MailPimJson.fullCard(o(CARD))!!.copy(company = "", emails = listOf(MCardEmail(" a@x.de ", "work"), MCardEmail("A@x.de"), MCardEmail("")), birthday = null)
        val b = MailPimJson.cardBody(c, etag = "\"3\"")
        assertTrue(b.get("company").isJsonNull)
        assertTrue(b.get("birthday").isJsonNull)
        assertEquals(1, b.getAsJsonArray("emails").size())
        assertEquals("a@x.de", b.getAsJsonArray("emails")[0].asJsonObject.get("value").asString)
        assertEquals("\"3\"", b.get("etag").asString)
        assertFalse(b.getAsJsonObject("name").has("display"))
        val m = MailPimJson.mergeBody(listOf(1, 2, 2), 1, MMergePick(company = 2), joinNotes = true)
        assertEquals(2, m.getAsJsonArray("ids").size())
        assertEquals(2, m.getAsJsonObject("pick").get("company").asInt)
        assertEquals("join", m.getAsJsonObject("pick").get("note").asString)
    }

    private fun push(topic: String, data: String?) = PushMessage(
        seq = 1, id = 1, eventId = "e", topic = topic, priority = "normal", title = "Termin", body = "14:00",
        createdAt = null, expiresAt = null, collapseKey = null, silent = false, route = null, actions = emptyList(), dataJson = data,
    )

    @Test
    fun `pushes - a reminder opens its event, an invitation the invitation`() {
        val r = push(MailPush.TOPIC_REMINDER, """{"calendar":{"eventId":5,"occurrence":"1730800800000"}}""")
        assertEquals("event:5:1730800800000", MailPush.destination(r))
        assertEquals(5L to "1730800800000", MailPush.eventOf("event:5:1730800800000"))
        assertEquals(5L to null, MailPush.eventOf(MailPush.destination(push(MailPush.TOPIC_REMINDER, """{"calendar":{"eventId":5,"occurrence":null}}"""))!!))
        assertEquals("event:5:2026-11-04", MailPush.destination(push(MailPush.TOPIC_REMINDER, """{"calendar":{"eventId":5,"occurrence":"2026-11-04"}}""")))
        assertEquals("event:5", MailPush.destination(push(MailPush.TOPIC_REMINDER, """{"calendar":{"eventId":5,"occurrence":"../x"}}""")))
        assertEquals("invitation:9", MailPush.destination(push(MailPush.TOPIC_INVITE, """{"calendar":{"invitationId":9}}""")))
        assertEquals(9L, MailPush.invitationOf("invitation:9"))
        assertNull(MailPush.eventOf("message:4"))
        assertTrue(MailPush.isCalendarTopic(MailPush.TOPIC_REMINDER))
        assertFalse(MailPush.isCalendarTopic(MailPush.TOPIC_MAIL))
    }

    private companion object {
        const val EVENT = """{"key":"5","eventId":5,"calendarId":2,"occurrence":null,"title":"Weekly","start":1730800800000,"end":1730804400000,"allDay":false,
            "timeZone":"Europe/Berlin","status":"confirmed","busy":"busy","myResponse":"needs_action","recurring":true,"attendees":2,"private":false,"reminder":15,
            "description":"Agenda","descriptionHtml":null,"organizer":{"name":"Sabine Keller","email":"s@k.de","self":false},
            "attendees":[{"name":"Anna","email":"a@x.de","role":"required","response":"accepted","self":false,"contactId":11},{"name":"","email":"me@x.de","role":"optional","response":"needs_action","self":true}],
            "reminders":[15],"recurrence":{"rrule":"FREQ=WEEKLY;BYDAY=TU,TH","editable":true,"freq":"weekly","interval":1,"byDay":["TU","TH"],"byMonthDay":null,"bySetPos":null,
            "until":null,"count":null,"exceptions":["1730800800000"],"deleted":[]},"seriesId":null,"readOnly":false,"canRespond":true,"canEdit":false,"canDelete":true,
            "categories":[],"invitationId":9,"url":null,"createdAt":1,"updatedAt":1700,"etag":"\"1700-2\"","series":{"start":1730800000000,"end":1730803600000,"allDay":false}}"""

        const val INVITATION = """{"id":9,"kind":"mail","messageId":1,"accountId":2,"method":"request","state":"open","uid":"u","title":"Sprint-Review","start":10,"end":20,
            "allDay":false,"timeZone":"Europe/Berlin","location":"Raum 1","online":null,"recurrence":{"summary":{"de":"Wöchentlich (Di)","en":"Weekly (Tue)"}},
            "organizer":{"name":"Sabine","email":"s@k.de","contactId":null},"from":{"name":"Sabine","email":"s@k.de"},
            "attendees":[{"name":"Tim","email":"t@k.de","response":"accepted"},{"email":"me@x.de","response":"needs_action","self":true}],
            "counts":{"accepted":1,"tentative":0,"declined":0,"needs_action":2},"myResponse":"needs_action","comment":null,"proposed":null,"eventId":5,"calendarId":2,
            "calendarChoices":[1,2],"day":[{"eventId":7,"calendarId":1,"title":"Zahnarzt","start":5,"end":15}],"conflicts":[{"title":"Zahnarzt","start":5,"end":15,"calendarId":1}]}"""

        const val CARD = """{"id":4,"addressbookId":2,"source":"graph","readOnly":false,"etag":"\"3\"",
            "name":{"display":"Sabine Keller","given":"Sabine","family":"Keller","middle":null},
            "emails":[{"value":"s@k.de","type":"work","primary":true},{"value":"s@gmx.de","type":"home","primary":false},{"value":"kaputt"}],
            "phones":[{"value":"0171 2345678","type":"mobile"}],"company":"Keller GmbH","title":"Projektleitung",
            "addresses":[{"type":"work","street":"Hansaring 12","postalCode":"50670","city":"Köln","region":"","country":""},{"type":"home","street":"","city":""}],
            "birthday":"--03-14","note":"","urls":["https://k.de","ftp://x"],"photo":{"etag":"e2","mime":"image/jpeg"},"groups":[5],
            "linked":[{"id":8,"addressbookId":1,"source":"graph"}],"gcUserId":null,"updatedAt":3}"""
    }
}
