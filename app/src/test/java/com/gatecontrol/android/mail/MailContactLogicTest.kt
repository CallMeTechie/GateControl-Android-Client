package com.gatecontrol.android.mail

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Mail 1.4.0 on the app's side: the JSON of the master model and the rules of [MailContactLogic]. */
class MailContactLogicTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    private fun o(s: String) = JsonParser.parseString(s).asJsonObject

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(berlin).toInstant().toEpochMilli()

    private val outlook = MSource(7, 3, "Kontakte", MAddressbook.SOURCE_GRAPH, 2, "ma@outlook.com")

    private fun conflict(field: String, value: MFieldValue, replaces: MFieldValue?, type: String? = null) =
        MCardConflict(1, field, type, value, replaces, outlook, 0)

    private fun sum(id: Long, master: Long? = null, sources: List<MSourceRef> = emptyList(), book: Long = 1) = MCardSummary(
        id = id, addressbookId = book, name = "N$id", sortKey = "N$id", letter = "N", company = null, title = null, emails = emptyList(), phone = null,
        photo = false, photoEtag = null, groups = emptyList(), linked = emptyList(), gcUserId = null, updatedAt = null, masterId = master, sources = sources,
    )

    // ─── JSON ───────────────────────────────────────────────────────

    @Test
    fun `a summary of Mail 1_4 - favourite, main number, conflicts, sources, an older one has none`() {
        val s = MailPimJson.cardSummary(
            o(
                """{"id":5,"addressbookId":1,"name":"Sabrina","phone":"+49 171 1","favorite":true,"mainPhone":"+49 171 1","conflicts":2,"masterId":null,
                "sources":[{"addressbookId":1,"source":"local","accountId":null},{"addressbookId":3,"source":"graph","accountId":2},{"source":"graph"}]}""",
            ),
        )!!
        assertEquals(true, s.favorite)
        assertEquals(2, s.conflicts)
        assertNull(s.masterId)
        assertEquals(listOf(MSourceRef(1, "local", null), MSourceRef(3, "graph", 2)), s.sources)
        val old = MailPimJson.cardSummary(o("""{"id":6,"name":"Alt"}"""))!!
        assertNull(old.favorite)
        assertEquals(0, old.conflicts)
        assertTrue(old.sources.isEmpty())
        // the offline copy keeps the new fields
        val back = MailPimJson.cardPage(MailPimCacheLogic.cardsJson(listOf(s, old), 9))!!.items
        assertEquals(listOf(s, old), back)
    }

    @Test
    fun `a card of Mail 1_4 - conflicts in their form, sources, main number, unknown fields and bad values left out`() {
        val c = MailPimJson.fullCard(
            o(
                """{"id":5,"addressbookId":1,"source":"local","name":{"given":"Peter","family":"Muster"},"phones":[{"value":"0170 1","type":"mobile"},{"value":"0171 2","type":"mobile"}],
                "favorite":false,"mainPhone":{"value":"0171 2","type":"mobile"},"mainPhoneChosen":true,"masterId":null,
                "conflicts":[
                  {"id":11,"field":"phones","type":"mobile","value":{"value":"0172 3","type":"mobile"},"replaces":{"value":"0170 1","type":"mobile"},"source":{"id":7,"addressbookId":3,"addressbook":"Kontakte","source":"graph","accountId":2,"account":"ma@outlook.com"},"at":1},
                  {"id":12,"field":"company","value":"ACME","replaces":"Muster GmbH","source":{"id":7}},
                  {"id":13,"field":"addresses","value":{"street":"Weg 1","postalCode":"1","city":"X"},"replaces":null},
                  {"id":14,"field":"name","value":"Pit"},
                  {"id":15,"field":"birthday","value":"gestern"}
                ],
                "sources":[{"id":5,"addressbookId":1,"addressbook":"Meine Kontakte","source":"local"},{"id":7,"addressbookId":3,"addressbook":"Kontakte","source":"graph","accountId":2,"account":"ma@outlook.com"}]}""",
            ),
        )!!
        assertEquals(false, c.favorite)
        assertEquals(MCardPhone("0171 2", "mobile"), c.mainPhone)
        assertTrue(c.mainPhoneChosen)
        assertEquals(listOf(11L, 12L, 13L), c.conflicts.map { it.id })
        assertEquals(MFieldValue.Phone(MCardPhone("0172 3", "mobile")), c.conflicts[0].value)
        assertEquals(MFieldValue.Phone(MCardPhone("0170 1", "mobile")), c.conflicts[0].replaces)
        assertEquals("ma@outlook.com", c.conflicts[0].source?.account)
        assertEquals(MFieldValue.Text("ACME"), c.conflicts[1].value)
        assertNull(c.conflicts[2].replaces)
        assertEquals(listOf(5L, 7L), c.sources.map { it.id })
        assertTrue(c.sources[0].local)
        assertTrue(MailContactLogic.masterModel(c))
        assertFalse(MailContactLogic.masterModel(MailPimJson.fullCard(o("""{"id":1,"name":{"given":"Alt"}}"""))!!))
    }

    @Test
    fun `PATCH answer with writeBack, an older server answers the card alone`() {
        val saved = MailPimJson.cardSaved(o("""{"card":{"id":5,"name":{"given":"A"}},"writeBack":[{"id":7,"addressbookId":3,"ok":true},{"id":8,"addressbookId":4,"ok":false,"code":"read_only"},{"ok":true}]}"""))!!
        assertEquals(5L, saved.card.id)
        assertEquals(listOf(MWriteBack(7, 3, true, null), MWriteBack(8, 4, false, "read_only")), saved.writeBack)
        assertTrue(MailPimJson.cardSaved(o("""{"card":{"id":5,"name":{"given":"A"}}}"""))!!.writeBack.isEmpty())
        assertNull(MailPimJson.cardSaved(o("""{"ok":true}""")))
    }

    @Test
    fun `duplicates with details and preview, merges, favourites, the widget's events`() {
        val d = MailPimJson.duplicates(
            o(
                """{"items":[{"key":"5-7","reason":"phone","certain":false,"cards":[{"id":5,"name":"Schatzimausi"},{"id":7,"name":"Sabrina Martin"}],
                "details":[{"card":{"id":5,"name":{"display":"Schatzimausi"},"phones":[{"value":"0171 1","type":"mobile"}]},"source":{"id":5,"addressbookId":1,"addressbook":"Meine Kontakte","source":"local"}},
                           {"card":{"id":7,"name":{"given":"Sabrina","family":"Martin"},"emails":[{"value":"s@m.de"}]},"source":{"id":7,"addressbookId":3,"addressbook":"Kontakte","source":"graph","accountId":2,"account":"ma@outlook.com"}}],
                "preview":{"id":5,"name":{"display":"Schatzimausi"},"phones":[{"value":"0171 1","type":"mobile"}],"emails":[{"value":"s@m.de"}],"mainPhone":{"value":"0171 1","type":"mobile"},"photoFrom":7,
                           "sources":[{"id":5,"source":"local"},{"id":7,"source":"graph"}],"conflicts":[{"field":"company","value":"ACME","source":{"id":7,"source":"graph"}}]}},
                {"key":"8-9","reason":"email","cards":[{"id":8},{"id":9}]}]}""",
            ),
        )!!
        assertEquals(false, d[0].certain)
        assertEquals(listOf(5L, 7L), d[0].details.map { it.card.id })
        val p = d[0].preview!!
        assertEquals(5L, p.id)
        assertEquals(7L, p.photoFrom)
        assertEquals(listOf("s@m.de"), p.card.emails.map { it.value })
        assertEquals("ACME", p.losers.single().value)
        // an older server: no certain flag, no details
        assertNull(d[1].certain)
        assertTrue(d[1].details.isEmpty())

        val merges = MailPimJson.merges(o("""{"items":[{"id":1,"masterId":5,"mode":"auto","created":true,"reason":"phone","at":10,"undoneAt":null,
            "cards":[{"id":5,"name":"A","addressbookId":1,"addressbook":"Meine Kontakte","source":"local","removed":false}]},{"id":2,"mode":"auto"}]}"""))!!
        assertEquals(1, merges.size)
        assertTrue(merges[0].automatic && merges[0].created)
        assertEquals(MailPimJson.unmerge(o("""{"card":null,"unlinked":[7],"restored":[]}"""))!!.unlinked, listOf(7L))

        val favs = MailPimJson.favorites(o("""{"items":[{"id":5,"name":"Anna","photo":true,"photoEtag":"e1","phone":{"value":"0171","type":"mobile"},"email":"a@x.de"},{"id":6,"name":"Ben","photo":false,"photoEtag":"x","phone":null}]}"""))!!
        assertEquals(MFavorite(5, "Anna", "e1", MCardPhone("0171", "mobile"), "a@x.de"), favs[0])
        assertNull(favs[1].photoEtag)
        assertNull(favs[1].phone)

        val u = MailPimJson.upcoming(o("""{"timeZone":"Europe/Berlin","today":"2026-10-31","more":3,"items":[
            {"key":"9:20261031T080000Z","eventId":9,"calendarId":2,"color":"#ff0000","title":"Daily","start":1,"end":2,"allDay":false,"day":"2026-10-31","tentative":true},
            {"key":"birthdays:4:2026","eventId":null,"overlayId":"birthdays","title":"Anna","start":1,"end":2,"allDay":true,"day":"2026-11-01"},
            {"key":"bad"}]}"""))!!
        assertEquals(3, u.more)
        assertEquals(2, u.items.size)
        assertEquals("20261031T080000Z", u.items[0].occurrence)
        assertEquals("#FF0000", u.items[0].color)
        assertNull(u.items[1].occurrence)
        assertNull(MailPimJson.upcoming(o("""{"items":[]}""")))
    }

    @Test
    fun `live events of Mail 1_4 - merged with its master, conflicts, favourites`() {
        val m = MailPimJson.contactsChanged(o("""{"kind":"merged","masterId":5,"contactIds":[5,7],"auto":true}"""))!!
        assertEquals(MContactsChanged.MERGED, m.kind)
        assertEquals(5L, m.masterId)
        assertEquals(MContactsChanged.FAVORITES, MailPimJson.contactsChanged(o("""{"kind":"favorites"}"""))!!.kind)
        assertEquals(listOf(5L), MailPimJson.contactsChanged(o("""{"kind":"conflicts","contactIds":[5]}"""))!!.contactIds)
    }

    @Test
    fun `request bodies - own fields, accept, unmerge, merge without primaryId`() {
        assertEquals("""{"favorite":true}""", MailPimJson.ownBody(favorite = true).toString())
        assertEquals("""{"mainPhone":"0171 2"}""", MailPimJson.ownBody(mainPhone = "0171 2").toString())
        assertEquals("""{"mainPhone":null}""", com.google.gson.GsonBuilder().serializeNulls().create().toJson(MailPimJson.ownBody(clearMainPhone = true)))
        assertEquals("""{"mode":"add"}""", MailPimJson.acceptBody("add").toString())
        assertEquals("""{"ids":[7],"keepMaster":true}""", MailPimJson.unmergeBody(listOf(7, 7), keepMaster = true).toString())
        assertEquals("{}", MailPimJson.unmergeBody(null, keepMaster = false).toString())
        assertFalse(MailPimJson.mergeBody(listOf(5, 7), null, null, joinNotes = false).has("primaryId"))
        assertEquals(5, MailPimJson.mergeBody(listOf(5, 7), 5, null, joinNotes = true).get("primaryId").asInt)
    }

    // ─── the list ───────────────────────────────────────────────────

    @Test
    fun `the list shows GC contacts and loose cards, the dots of every account`() {
        val list = listOf(sum(1), sum(2, master = 1), sum(3))
        assertEquals(listOf(1L, 3L), MailContactLogic.listed(list).map { it.id })
        val books = mapOf(1L to null, 3L to 2L, 4L to 9L)
        val master = sum(1, sources = listOf(MSourceRef(1, "local", null), MSourceRef(3, "graph", 2), MSourceRef(4, "carddav", null), MSourceRef(5, "graph", 2)))
        assertEquals(listOf(2L, 9L), MailContactLogic.sourceAccounts(master) { books[it] })
        // an older server: its own address book's account
        assertEquals(listOf(9L), MailContactLogic.sourceAccounts(sum(8, book = 4)) { books[it] })
        assertTrue(MailContactLogic.sourceAccounts(sum(8, book = 1)) { books[it] }.isEmpty())
    }

    // ─── hints ──────────────────────────────────────────────────────

    @Test
    fun `hints - what differs, from where, take over replaces, add only where it fits`() {
        val mobile = conflict(MCardConflict.PHONES, MFieldValue.Phone(MCardPhone("0171 2", "mobile")), MFieldValue.Phone(MCardPhone("0170 1", "mobile")), "mobile")
        assertEquals("mobile", MailContactLogic.hintKind(mobile))
        assertEquals("0171 2", MailContactLogic.valueText(mobile.value))
        assertEquals("0170 1", MailContactLogic.valueText(mobile.replaces))
        assertEquals("Outlook (ma@outlook.com)", MailContactLogic.originLabel(mobile.source))
        assertTrue(MailContactLogic.canAdd(mobile))
        assertEquals("replace", MailContactLogic.acceptMode(mobile, add = false))
        assertEquals("add", MailContactLogic.acceptMode(mobile, add = true))

        val work = conflict(MCardConflict.PHONES, MFieldValue.Phone(MCardPhone("030 1", "work")), null)
        assertEquals("phone", MailContactLogic.hintKind(work))
        assertFalse(MailContactLogic.canAdd(work))
        assertEquals("add", MailContactLogic.acceptMode(work, add = false))

        val company = conflict(MCardConflict.COMPANY, MFieldValue.Text("ACME"), MFieldValue.Text("Muster GmbH"))
        assertEquals("company", MailContactLogic.hintKind(company))
        assertFalse(MailContactLogic.canAdd(company))
        assertEquals("replace", MailContactLogic.acceptMode(company, add = false))

        val addr = MFieldValue.Address(MCardAddress(street = "Weg 1", postalCode = "12345", city = "Ort", country = ""))
        assertEquals("Weg 1, 12345 Ort", MailContactLogic.valueText(addr))
        assertEquals("iCloud (me@icloud.com)", MailContactLogic.originLabel(MSource(1, 1, "iCloud", MAddressbook.SOURCE_CARDDAV, 4, "me@icloud.com")))
        assertEquals("Meine Kontakte", MailContactLogic.originLabel(MSource(1, 1, "Meine Kontakte", MAddressbook.SOURCE_LOCAL, null, null)))
        assertEquals("", MailContactLogic.originLabel(null))
    }

    // ─── the preview ────────────────────────────────────────────────

    @Test
    fun `preview of an older server - the GC card keeps its name, the lists are joined, what only one card has`() {
        val gc = MCard(id = 1, source = "local", name = MCardName(given = "Sabrina"), phones = listOf(MCardPhone("+49 171 1111111", "mobile")))
        val acc = MCard(
            id = 2, source = "graph", name = MCardName(given = "Sabrina", family = "Martin"), phones = listOf(MCardPhone("0171 1111111", "mobile"), MCardPhone("030 22", "work")),
            emails = listOf(MCardEmail("s@m.de")), addresses = listOf(MCardAddress(street = "Weg 1", city = "Ort")),
        )
        val p = MailContactLogic.localPreview(listOf(acc, gc))!!
        assertEquals("Sabrina", p.name.given)
        assertEquals("", p.name.family)
        assertEquals(listOf("+49 171 1111111", "030 22"), p.phones.map { it.value })
        assertEquals(listOf("s@m.de"), p.emails.map { it.value })
        assertEquals(1, p.addresses.size)
        assertNull(MailContactLogic.localPreview(listOf(gc)))

        val own = MailContactLogic.ownValues(acc, listOf(gc))
        assertTrue("p:" + MailPimLogic.phoneKey("030 22") in own)
        assertFalse("p:" + MailPimLogic.phoneKey("0171 1111111") in own)
        assertTrue("e:s@m.de" in own)
        assertTrue("a:" + MailContactLogic.addressKey(acc.addresses[0]) in own)
        assertTrue(MailContactLogic.ownValues(gc, listOf(acc)).isEmpty())
    }

    // ─── favourites, the main number ────────────────────────────────

    @Test
    fun `favourites - on at the end, off out, at most 50, moving`() {
        assertEquals(listOf(1L, 2L, 3L), MailContactLogic.toggled(listOf(1, 2), 3, on = true))
        assertEquals(listOf(1L, 2L), MailContactLogic.toggled(listOf(1, 2), 2, on = true))
        assertEquals(listOf(1L), MailContactLogic.toggled(listOf(1, 2), 2, on = false))
        assertNull(MailContactLogic.toggled((1L..50L).toList(), 51, on = true))
        assertEquals(listOf(2L, 1L, 3L), MailContactLogic.moved(listOf(1, 2, 3), 2, -1))
        assertEquals(listOf(1L, 2L, 3L), MailContactLogic.moved(listOf(1, 2, 3), 3, 1))
    }

    @Test
    fun `main number - chosen by its digits, else the first mobile, else the first`() {
        val phones = listOf(MCardPhone("030 1234567", "work"), MCardPhone("0171 9876543", "mobile"), MCardPhone("0172 5555555", "mobile"))
        assertEquals("0171 9876543", MailContactLogic.mainPhoneOf(phones, null)?.value)
        assertEquals("0172 5555555", MailContactLogic.mainPhoneOf(phones, "+49 172 5555555")?.value)
        assertEquals("0171 9876543", MailContactLogic.mainPhoneOf(phones, "0999 0")?.value)
        assertEquals("030 1", MailContactLogic.mainPhoneOf(listOf(MCardPhone("030 1", "work")), null)?.value)
        assertNull(MailContactLogic.mainPhoneOf(emptyList(), null))

        val c = MCard(id = 1, phones = phones, favorite = false, mainPhone = MCardPhone("+49 172 5555555", "mobile"))
        assertTrue(MailContactLogic.isMain(c, phones[2]))
        assertFalse(MailContactLogic.isMain(c, phones[1]))
        assertTrue(MailContactLogic.canChooseMain(c, phones[0]))
        assertFalse(MailContactLogic.canChooseMain(c, phones[2]))
        // an older server: no main number to choose, the rule decides
        val old = c.copy(favorite = null, mainPhone = null)
        assertTrue(MailContactLogic.isMain(old, phones[1]))
        assertFalse(MailContactLogic.canChooseMain(old, phones[0]))
        assertFalse(MailContactLogic.canChooseMain(c.copy(phones = listOf(phones[0])), phones[0]))
    }

    // ─── Start ──────────────────────────────────────────────────────

    @Test
    fun `tiles - follow the favourites, leave when no favourite, a tap calls or opens`() {
        val pins = MailStartPins(listOf(StartContactPin(5, "Anna alt", null, "0170"), StartContactPin(6, "Ben", null, null)), agenda = true)
        val synced = MailContactLogic.syncedPins(pins, listOf(MFavorite(5, "Anna", "e", MCardPhone("0171 2", "mobile"), null), MFavorite(9, "Neu", null, null, null)))
        assertEquals(listOf(StartContactPin(5, "Anna", "e", "0171 2")), synced.contacts)
        assertTrue(synced.agenda)
        assertEquals(TileTap.Call("tel:01712"), MailContactLogic.tap(synced.contacts[0]))
        assertEquals(TileTap.Open(6), MailContactLogic.tap(pins.contacts[1]))
        assertEquals(TileTap.Call("tel:+4917112"), MailContactLogic.tap(StartContactPin(1, "x", null, "+49 171 12")))

        val more = MailContactLogic.withPin(pins, StartContactPin(7, "C"), on = true)
        assertEquals(listOf(5L, 6L, 7L), more.extras.contacts)
        assertEquals(listOf(6L), MailContactLogic.withPin(pins, StartContactPin(5, ""), on = false).extras.contacts)
        assertEquals("Anna neu", MailContactLogic.withPin(pins, StartContactPin(5, "Anna neu"), on = true).contacts[0].name)
        val full = MailStartPins((1L..12L).map { StartContactPin(it, "$it") })
        assertEquals(12, MailContactLogic.withPin(full, StartContactPin(13, "x"), on = true).contacts.size)
    }

    @Test
    fun `calling - straight away only with the permission, else the dialer`() {
        assertEquals(MailContactLogic.ACTION_DIAL, MailContactLogic.callAction(callPermission = false))
        assertEquals(MailContactLogic.ACTION_CALL, MailContactLogic.callAction(callPermission = true))
        assertEquals("android.intent.action.DIAL", MailContactLogic.ACTION_DIAL)
        assertEquals("android.intent.action.CALL", MailContactLogic.ACTION_CALL)
    }

    @Test
    fun `pins are kept without reflection, broken entries left out`() {
        val pins = MailStartPins(listOf(StartContactPin(5, "Anna", "e1", "0171"), StartContactPin(6, "Ben")), agenda = true)
        assertEquals(pins, MailContactLogic.pinsOf(MailContactLogic.pinsJson(pins)))
        val odd = MailContactLogic.pinsOf(o("""{"agenda":"x","contacts":[{"id":"7","name":"C"},{"id":-1},{"name":"x"},3,{"id":7,"name":"dup"}]}"""))
        assertEquals(listOf(StartContactPin(7, "C")), odd.contacts)
        assertFalse(odd.agenda)
        assertEquals(MailStartPins(), MailContactLogic.pinsOf(null))
    }

    @Test
    fun `the widget - today and tomorrow, over ones gone, all-day first, an old copy shows what is left`() {
        val now = ms(2026, 10, 31, 12)
        fun item(key: String, start: Long, end: Long, day: String, allDay: Boolean = false) =
            MUpcomingItem(key, 1, 1, null, null, key, start, end, allDay, day, null, false)
        val u = MUpcoming(
            "Europe/Berlin", "2026-10-31",
            listOf(
                item("over", ms(2026, 10, 31, 9), ms(2026, 10, 31, 10), "2026-10-31"),
                item("running", ms(2026, 10, 31, 11), ms(2026, 10, 31, 13), "2026-10-31"),
                item("later", ms(2026, 10, 31, 15), ms(2026, 10, 31, 16), "2026-10-31"),
                item("holiday", ms(2026, 10, 31, 0), ms(2026, 11, 1, 0), "2026-10-31", allDay = true),
                item("tomorrow", ms(2026, 11, 1, 9), ms(2026, 11, 1, 10), "2026-11-01"),
                item("far", ms(2026, 11, 2, 9), ms(2026, 11, 2, 10), "2026-11-02"),
            ),
            more = 2,
        )
        val v = MailContactLogic.agenda(u, now, berlin)
        assertEquals(listOf(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1)), v.days.map { it.day })
        assertTrue(v.days[0].today)
        assertEquals(listOf("holiday", "running", "later"), v.days[0].items.map { it.key })
        assertEquals(listOf("tomorrow"), v.days[1].items.map { it.key })
        assertEquals(2, v.more)

        // offline, the next morning: yesterday's are over, tomorrow's became today's
        val next = MailContactLogic.agenda(u, ms(2026, 11, 1, 8), berlin)
        assertEquals(listOf(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2)), next.days.map { it.day })
        assertTrue(next.days[0].today)
        assertEquals(listOf("tomorrow"), next.days[0].items.map { it.key })
        assertTrue(MailContactLogic.stale(u, ms(2026, 11, 1, 8), berlin))
        assertFalse(MailContactLogic.stale(u, now, berlin))

        assertTrue(MailContactLogic.agenda(u.copy(items = emptyList(), more = 0), now, berlin).empty)
        assertEquals(ms(2026, 11, 1, 0), MailContactLogic.nextDay(now, berlin))
    }

    @Test
    fun `written back - by provider name, each once, the failed ones apart`() {
        val books = mapOf(
            3L to MAddressbook(3, "graph", 2, "Kontakte", true, true, false, 0, "ok", null, null),
            4L to MAddressbook(4, "carddav", 5, "iCloud", false, true, false, 0, "ok", null, null),
        )
        val (ok, failed) = MailContactLogic.writeBack(
            listOf(MWriteBack(7, 3, true, null), MWriteBack(8, 3, true, null), MWriteBack(9, 4, false, "read_only"), MWriteBack(10, 99, true, null)),
        ) { books[it] }
        assertEquals(listOf("Outlook"), ok)
        assertEquals(listOf("iCloud"), failed)
        assertNotNull(MailContactLogic.writeBack(emptyList()) { null })
    }
}
