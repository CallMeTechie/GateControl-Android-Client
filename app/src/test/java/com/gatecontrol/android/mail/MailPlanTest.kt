package com.gatecontrol.android.mail

import com.gatecontrol.android.notify.model.PushMessage
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Mail 1.9.0 (phase 3): free/busy, shared calendars, resources and bookings — what is read, sent and shown. */
class MailPlanTest {

    private fun o(s: String) = JsonParser.parseString(s).asJsonObject
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val day: LocalDate = LocalDate.of(2026, 11, 5)
    private fun at(h: Int, m: Int = 0): Long = day.atTime(LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `me - the phase 3 flags, an older server has none`() {
        val me = MailJson.me(o("""{"features":["mail","calendar","freeBusy","calendarSharing","resources","bookingRequests"]}"""))!!
        assertTrue(me.has(MMe.FEATURE_FREE_BUSY))
        assertTrue(me.has(MMe.FEATURE_SHARING))
        assertTrue(me.has(MMe.FEATURE_RESOURCES))
        assertTrue(me.has(MMe.FEATURE_BOOKING_REQUESTS))
        val old = MailJson.me(o("""{"features":["mail","calendar","contacts","scan"]}"""))!!
        listOf(MMe.FEATURE_FREE_BUSY, MMe.FEATURE_SHARING, MMe.FEATURE_RESOURCES, MMe.FEATURE_BOOKING_REQUESTS).forEach { assertFalse(old.has(it), it) }
    }

    @Test
    fun `calendars - shared ones, sharedBy, free busy and working hours of the prefs`() {
        val c = MailPimJson.calendars(
            o(
                """{"calendars":[{"id":1,"name":"Privat","color":"#46e0a0"},{"id":2,"name":"Team","source":"graph","accountId":3,"sharedBy":{"email":"chef@firma.de"}}],
                "prefs":{"freeBusy":false,"workHours":{"days":[1,2,3,4],"from":"07:30","to":"16:00"}},
                "shared":[{"id":9,"name":"Urlaube","color":"#8C96A5","owner":{"userId":4,"name":"Sabine K."},"level":"read","via":"group","group":"Familie","visible":true,"accepted":false,"writable":false},
                {"id":0,"name":"kaputt"},{"id":10,"name":"Frei","level":"bogus"}]}""",
            ),
        )!!
        assertNull(c.calendar(1)!!.sharedBy)
        assertEquals("chef@firma.de", c.calendar(2)!!.sharedBy)
        assertFalse(c.prefs.freeBusy)
        assertEquals(MWorkHours(listOf(1, 2, 3, 4), "07:30", "16:00"), c.prefs.workHours)
        assertEquals(listOf(9L, 10L), c.shared.map { it.id })
        val s = c.sharedCalendar(9)!!
        assertEquals("Sabine K.", s.ownerName)
        assertEquals(MShare.GROUP, s.via)
        assertEquals("Familie", s.group)
        assertTrue(s.visible)
        assertFalse(s.accepted)
        assertEquals(MShare.FREEBUSY, c.sharedCalendar(10)!!.level, "an unknown level is the least")
        // an older server: no shared calendars, free/busy on, Mon–Fri 8–18
        val old = MailPimJson.calendars(o("""{"calendars":[],"prefs":{"workHours":{"days":[1],"from":"18:00","to":"08:00"}}}"""))!!
        assertTrue(old.shared.isEmpty())
        assertTrue(old.prefs.freeBusy)
        assertEquals(MWorkHours.DEFAULT, old.prefs.workHours, "from after to is not read")
    }

    @Test
    fun `events - a free busy entry of a shared calendar without an event`() {
        val e = MailPimJson.events(
            o(
                """{"items":[{"key":"s:9:1:1","eventId":null,"calendarId":9,"title":"Gebucht","start":1000,"end":2000,"busy":"busy","private":true,"shared":"freebusy"},
                {"eventId":5,"calendarId":9,"title":"Urlaub","start":1000,"end":2000,"shared":"read"},
                {"eventId":null,"title":"kaputt","start":1}]}""",
            ),
        )!!
        assertEquals(2, e.items.size, "without an event only a shared entry is kept")
        val busy = e.items[0]
        assertTrue(busy.busyOnly)
        assertEquals(MShare.FREEBUSY, busy.shared)
        assertEquals("s:9:1:1", busy.key)
        assertFalse(e.items[1].busyOnly)
        assertEquals(MShare.READ, e.items[1].shared)
        assertNull(MailPimJson.occurrence(o("""{"eventId":null,"start":1,"shared":"read"}""")), "no key and no event: nothing to tell it by")
    }

    @Test
    fun `free busy - rows, unknown ones grey, suggestions`() {
        val fb = MailPimJson.freeBusy(
            o(
                """{"from":0,"to":100,"items":[{"key":"self","kind":"self","userId":1,"name":"Ich","state":"ok","blocks":[{"start":10,"end":20,"status":"busy"}]},
                {"key":"tom@x.de","kind":"person","email":"tom@x.de","name":"Tom B.","state":"ok","blocks":[{"start":30,"end":40,"status":"tentative"},{"start":50,"end":40}]},
                {"key":"r:3","kind":"resource","resourceId":3,"name":"Raum 1","state":"ok","blocks":[{"start":60,"end":70,"status":"pending"}]},
                {"key":"kunde@mueller.de","kind":"external","email":"kunde@mueller.de","name":"","state":"unknown","blocks":[{"start":1,"end":2}]}]}""",
            ),
        )!!
        assertEquals(listOf(MFreeBusyRow.SELF, MFreeBusyRow.PERSON, MFreeBusyRow.RESOURCE, MFreeBusyRow.EXTERNAL), fb.items.map { it.kind })
        assertEquals(1, fb.items[1].blocks.size, "a block ending before it starts is left out")
        assertEquals(MBusyBlock.TENTATIVE, fb.items[2].blocks[0].status, "a request shows as tentative")
        assertFalse(fb.items[3].known)
        assertTrue(fb.items[3].blocks.isEmpty())
        val sg = MailPimJson.suggestions(o("""{"items":[{"start":5,"end":10},{"start":9}],"unknown":["kunde@mueller.de"],"workHours":{"days":[1,2,3,4,5],"from":"08:00","to":"24:00"},"timeZone":"Europe/Berlin"}"""))!!
        assertEquals(listOf(MSlot(5, 10)), sg.items)
        assertEquals(listOf("kunde@mueller.de"), sg.unknown)
        assertEquals("24:00", sg.workHours!!.to)
    }

    @Test
    fun `free busy - the window, the bar, who cannot`() {
        // the working hours, widened to a chosen time before them (from its full hour) and after them
        assertEquals(at(8) to at(18), MailPlanLogic.dayWindow(day, null, null, null, zone))
        assertEquals(at(7) to at(19, 30), MailPlanLogic.dayWindow(day, MWorkHours.DEFAULT, at(7, 30), at(19, 30), zone))
        assertEquals(at(9) to at(17), MailPlanLogic.dayWindow(day, MWorkHours(listOf(1), "09:00", "17:00"), at(10), at(11), zone))
        assertEquals(at(8) to at(18), MailPlanLogic.dayWindow(day, null, at(7) + 86_400_000L, at(8) + 86_400_000L, zone), "another day's time widens nothing")
        val bar = MailPlanLogic.bar(listOf(MBusyBlock(at(10), at(11), "busy"), MBusyBlock(at(7), at(9), "tentative")), at(8), at(12))
        assertEquals(listOf("tentative", "free", "busy", "free"), bar.map { it.status })
        assertEquals(1f, bar.sumOf { it.fraction.toDouble() }.toFloat(), 0.0001f)
        assertEquals(0.25f, bar[0].fraction, 0.0001f, "cut to the window")
        assertEquals(listOf("unknown"), MailPlanLogic.bar(emptyList(), at(8), at(12), MailPlanLogic.UNKNOWN).map { it.status })
        assertTrue(MailPlanLogic.bar(emptyList(), 5, 5).isEmpty())
        assertEquals(0.25f to 0.5f, MailPlanLogic.frame(at(9), at(11), at(8), at(12)))
        assertNull(MailPlanLogic.frame(at(13), at(14), at(8), at(12)))
        assertEquals(listOf(8, 10, 12, 14, 16, 18), MailPlanLogic.scale(at(8), at(18), zone).map { it.first })
        assertEquals(listOf(0, 4, 8, 12, 16, 20, 0), MailPlanLogic.scale(at(0), at(0) + 86_400_000L, zone).map { it.first })

        val rows = listOf(
            MFreeBusyRow("self", MFreeBusyRow.SELF, null, null, 1, "", true, listOf(MBusyBlock(at(9), at(10), "busy"))),
            MFreeBusyRow("s", MFreeBusyRow.PERSON, "s@x.de", null, 2, "Sabine K.", true, listOf(MBusyBlock(at(14), at(15), "tentative"))),
            MFreeBusyRow("t", MFreeBusyRow.PERSON, "t@x.de", null, 3, "Tom B.", true, listOf(MBusyBlock(at(13), at(16), "busy"))),
            MFreeBusyRow("k", MFreeBusyRow.EXTERNAL, "kunde@mueller.de", null, null, "", false, emptyList()),
        )
        val c = MailPlanLogic.clashes(rows, at(14), at(15))
        assertEquals(listOf("Tom", "Sabine"), c.map { it.name }, "the busy ones first, first names")
        assertEquals(listOf("busy", "tentative"), c.map { it.status })
        assertTrue(MailPlanLogic.clashes(rows, at(9, 30), at(10)).single().self)
        assertTrue(MailPlanLogic.clashes(rows, at(11), at(12)).isEmpty())
        assertEquals(listOf("kunde"), MailPlanLogic.unknown(rows).map { MailPlanLogic.label(it) })
        assertEquals("SK", MailPlanLogic.initials("Sabine K."))
        assertEquals("KU", MailPlanLogic.initials("kunde@mueller.de"))
        assertEquals("?", MailPlanLogic.initials(""))
        assertEquals("Raum 1", MailPlanLogic.label(MFreeBusyRow("r", MFreeBusyRow.RESOURCE, null, 3, null, "Raum 1", true, emptyList())))
    }

    @Test
    fun `resources - as attendees, the people and resources of a form, suggestion body`() {
        val a = listOf(
            MAttendeeInput("Tom@X.de", "Tom"),
            MAttendeeInput("resource-12@resources.gatecontrol.invalid", "Raum 1", MAttendee.ROLE_RESOURCE),
            MAttendeeInput("tom@x.de"),
            MAttendeeInput("resource-x@resources.gatecontrol.invalid"),
        )
        val (people, res) = MailPlanLogic.split(a)
        assertEquals(listOf("tom@x.de", "resource-x@resources.gatecontrol.invalid"), people, "an address that only looks like one stays a person")
        assertEquals(listOf(12L), res)
        assertTrue(MailPlanLogic.isResource("x@y.de", MAttendee.ROLE_RESOURCE))
        assertEquals(12L, MailPlanLogic.resourceId("RESOURCE-12@resources.gatecontrol.invalid"))
        assertNull(MailPlanLogic.resourceId("resource-12@resources.gatecontrol.invalid.evil.de"))
        val body = MailPimJson.suggestBody(2000, 7L, people, res, limit = 99)
        assertEquals(1440, body.get("duration").asInt)
        assertEquals(10, body.get("limit").asInt)
        assertEquals(12L, body.getAsJsonArray("resources")[0].asLong)
        assertFalse(MailPimJson.freeBusyBody(1, 2, emptyList(), emptyList()).has("resources"))
        assertEquals(60, MailPlanLogic.duration(0, 10, allDay = true))
        assertEquals(15, MailPlanLogic.duration(0, 60_000, allDay = false))
    }

    @Test
    fun `resources - the list, rules, labels and chips`() {
        val r = MailPimJson.resources(
            o(
                """{"items":[{"id":3,"name":"Firmenwagen Kombi","kind":"vehicle","location":null,"seats":0,"color":"#79b8ff","approval":"approval","approvers":["Fuhrpark",""],
                "rules":{"maxMinutes":1440,"horizonDays":30,"hours":null},"timeZone":"Europe/Berlin","address":"resource-3@resources.gatecontrol.invalid",
                "busy":[{"start":10,"end":20,"status":"busy","mine":true},{"start":30,"end":40,"status":"pending"}]},
                {"id":4,"name":"Beamer","kind":"thing","address":"resource-4@resources.gatecontrol.invalid"},{"id":5,"name":"ohne Adresse"}],"kinds":["room","vehicle","device","other"]}""",
            ),
        )!!
        assertEquals(listOf(3L, 4L), r.items.map { it.id })
        val car = r.items[0]
        assertTrue(car.needsApproval)
        assertEquals(listOf("Fuhrpark"), car.approvers)
        assertNull(car.seats)
        assertEquals(1440, car.rules.maxMinutes)
        assertTrue(car.busy[0].mine)
        assertEquals(MBusyBlock.PENDING, car.busy[1].status)
        assertEquals(MResource.OTHER, r.items[1].kind)
        assertFalse(r.items[1].needsApproval)
        assertEquals(listOf("room", "vehicle", "device", "other"), MailPlanLogic.kindChips(r.items))
        assertEquals(listOf("room", "vehicle", "device"), MailPlanLogic.kindChips(listOf(car)), "„Sonstiges“ only when there is one")
        assertEquals("day" to 1.0, MailPlanLogic.maxLimit(1440))
        assertEquals("days" to 3.0, MailPlanLogic.maxLimit(4320))
        assertEquals("hours" to 2.5, MailPlanLogic.maxLimit(150))
        // a pending block in a resource's bar shows like tentative
        assertEquals(listOf("free", "tentative", "free"), MailPlanLogic.bar(listOf(MBusyBlock(2, 4, MBusyBlock.PENDING)), 0, 6).map { it.status })
        val book = MailPimJson.bookBody("  Kunde Müller ", 100, 200, timeZone = "Europe/Berlin")
        assertEquals("Kunde Müller", book.get("title").asString)
        assertFalse(book.has("allDay"))
        val days = MailPimJson.bookBody(null, null, null, startDate = "2026-11-06", endDate = "2026-11-07", timeZone = null)
        assertTrue(days.get("allDay").asBoolean)
        assertFalse(days.has("title"))
    }

    @Test
    fun `bookings - pills, what may be done, the refusals`() {
        val list = MailPimJson.bookings(
            o(
                """{"items":[
                {"id":1,"resourceId":3,"resource":{"id":3,"name":"Firmenwagen","kind":"vehicle","color":"#79B8FF"},"title":"Kunde Müller","start":100,"end":200,"state":"pending",
                 "booker":{"userId":2,"name":"Markus B."},"violations":[],"conflicts":[],"canDecide":true,"day":[{"start":0,"end":50,"status":"busy"}]},
                {"id":2,"resourceId":3,"resource":{"name":"Firmenwagen","kind":"vehicle"},"title":"Messe","start":100,"end":200,"allDay":true,"startDate":"2026-11-06","endDate":"2026-11-07",
                 "state":"pending","violations":[{"rule":"max_duration","limit":1440},{"rule":"bogus"}],"conflicts":[],"canDecide":true},
                {"id":3,"resourceId":3,"start":100,"end":200,"state":"pending","violations":[],"conflicts":[{"start":120,"end":150}],"canDecide":true},
                {"id":4,"resourceId":3,"start":100,"end":200,"state":"confirmed","decidedBy":{"userId":5,"name":"Fuhrpark"},"decidedAt":99,"override":true,"comment":"ok","eventId":7},
                {"id":5,"resourceId":3,"start":100,"end":200,"state":"cancelled"},{"id":6,"start":1}],"counts":{"open":3}}""",
            ),
        )!!
        assertEquals(3, list.open)
        assertEquals(5, list.items.size)
        val (ok, rules, taken, done, off) = list.items
        assertEquals(MailPlanLogic.PILL_WAITING, MailPlanLogic.pill(ok))
        assertEquals(MailPlanLogic.Approve.APPROVE, MailPlanLogic.approve(ok))
        assertEquals("Markus B.", ok.bookerName)
        assertEquals(MailPlanLogic.PILL_CONFLICT, MailPlanLogic.pill(rules))
        assertEquals(listOf(MViolation.MAX_DURATION), rules.violations.map { it.rule })
        assertEquals(MailPlanLogic.Approve.OVERRIDE, MailPlanLogic.approve(rules), "„Trotzdem freigeben“")
        assertEquals("2026-11-07", rules.endDate)
        assertEquals(MailPlanLogic.Approve.NONE, MailPlanLogic.approve(taken), "a confirmed booking in the way: no approving")
        assertEquals(MailPlanLogic.PILL_CONFLICT, MailPlanLogic.pill(taken))
        assertEquals(MailPlanLogic.PILL_CONFIRMED, MailPlanLogic.pill(done))
        assertEquals("Fuhrpark", done.decidedBy)
        assertTrue(done.override)
        assertEquals(7L, done.eventId)
        assertEquals(MailPlanLogic.Approve.NONE, MailPlanLogic.approve(done))
        assertEquals(MailPlanLogic.PILL_CANCELLED, MailPlanLogic.pill(off))
        assertTrue(MailPlanLogic.canCancel(done, 150))
        assertFalse(MailPlanLogic.canCancel(done, 200), "over")
        assertFalse(MailPlanLogic.canCancel(off, 0))
        assertEquals(listOf("busy", MailPlanLogic.ASK), MailPlanLogic.bookingBlocks(ok).map { it.status })
        // refusals: the rules, who decided first
        val err = o("""{"code":"rule_violation","violations":[{"rule":"hours","days":[1,2,3,4,5],"from":"08:00","to":"18:00"},{"rule":"horizon","limit":30}]}""")
        assertEquals(listOf(MViolation.HOURS, MViolation.HORIZON), MailPimJson.violations(err).map { it.rule })
        assertEquals("08:00", MailPimJson.violations(o("""{"error":${err}}"""))[0].from)
        assertEquals("Sabine", MailPimJson.decidedBy(o("""{"code":"already_decided","decidedBy":{"userId":4,"name":"Sabine"}}""")))
        assertNull(MailPimJson.decidedBy(o("""{"code":"already_decided","decidedBy":null}""")))
        assertEquals("approve", MailPimJson.decideBody(true, override = true).get("decision").asString)
        assertTrue(MailPimJson.decideBody(true, override = true).get("override").asBoolean)
        assertFalse(MailPimJson.decideBody(false).has("override"))
        // a saved event with its bookings
        val saved = MailPimJson.eventSaved(o("""{"event":{"eventId":7,"start":1,"end":2},"sent":{"mail":0},"bookings":[{"id":9,"resourceId":3,"start":1,"end":2,"state":"confirmed"}]}"""))!!
        assertEquals(listOf(9L), saved.bookings.map { it.id })
        assertTrue(MailPimJson.eventSaved(o("""{"event":{"eventId":7,"start":1,"end":2}}"""))!!.bookings.isEmpty())
    }

    @Test
    fun `sharing - shares, the people to share with, shared with me, bodies`() {
        val s = MailPimJson.shares(
            o(
                """{"items":[{"id":1,"type":"user","principalId":4,"name":"Sabine K.","level":"write","state":"accepted","members":null},
                {"id":2,"type":"group","principalId":7,"name":"Familie","level":"read","state":"group","members":4},{"id":0}],"freeBusy":true}""",
            ),
        )!!
        assertEquals(listOf(1L, 2L), s.items.map { it.id })
        assertEquals(MShare.STATE_ACCEPTED, s.items[0].state)
        assertEquals(4, s.items[1].members)
        assertEquals(true, s.freeBusy)
        val p = MailPimJson.principals(
            o("""{"items":[{"type":"user","id":4,"name":"Sabine K.","initials":"SK","color":"#79B8FF"},{"type":"group","id":7,"name":"Familie","sub":"Haus/Familie","members":4},{"type":"device","id":1,"name":"x"}]}"""),
        )!!
        assertEquals(listOf(MShare.USER, MShare.GROUP), p.map { it.type })
        assertEquals("Haus/Familie", p[1].sub)
        val mine = MailPimJson.sharedList(
            o(
                """{"items":[{"id":9,"name":"Urlaube","color":"#8C96A5","owner":{"userId":4,"name":"Sabine"},"level":"read","via":"user","visible":false,"accepted":false}],
                "accounts":[{"calendarId":12,"accountId":3,"name":"Team","color":"#79B8FF","owner":{"email":"chef@firma.de"},"writable":true,"visible":true}]}""",
            ),
        )!!
        assertEquals("Urlaube", mine.items.single().name)
        assertEquals("chef@firma.de", mine.accounts.single().ownerEmail)
        assertTrue(mine.accounts.single().writable)
        val body = MailPimJson.shareBody("group", 7, "bogus")
        assertEquals("group", body.get("type").asString)
        assertEquals(MShare.READ, body.get("level").asString)
        assertEquals("#AABBCC", MailPimJson.sharedPatch(color = "#aabbcc").get("color").asString)
        assertFalse(MailPimJson.sharedPatch(color = "red").has("color"))
        assertTrue(MailPimJson.calSettingsBody(freeBusy = false).has("freeBusy"))
        assertFalse(MailPimJson.calSettingsBody(view = "day").has("freeBusy"))
    }

    @Test
    fun `live - shares, changes in shared calendars, resources`() {
        val sh = MailPimJson.calChanged(JsonParser.parseString("""{"kind":"shares","calendarId":9}"""))!!
        assertEquals(MCalChanged.SHARES, sh.kind)
        assertTrue(MailPimJson.calChanged(JsonParser.parseString("""{"kind":"changed","calendarId":9,"accountId":null,"shared":true}"""))!!.shared)
        val r = MailPimJson.resChanged(JsonParser.parseString("""{"resources":{"kind":"request","bookingId":4,"resourceId":3,"state":"pending"}}"""))!!
        assertEquals(MResChanged.REQUEST, r.kind)
        assertEquals(4L, r.bookingId)
        assertEquals(MResChanged.RESOURCES, MailPimJson.resChanged(JsonParser.parseString("""{"kind":"resources"}"""))!!.kind)
        assertNull(MailPimJson.resChanged(JsonParser.parseString("""{"kind":"other"}""")))
        assertNull(MailPimJson.resChanged(null))
    }

    private fun msg(topic: String, data: String?) = PushMessage(
        seq = 1, id = 1, eventId = "e", topic = topic, priority = "normal", title = "Buchungsanfrage: Firmenwagen", body = "Markus B. · Kunde Müller",
        createdAt = null, expiresAt = null, collapseKey = null, silent = false, route = null, actions = emptyList(), dataJson = data,
    )

    @Test
    fun `pushes - a request opens the requests at its card, an answer the event, a share shared with me`() {
        val req = MailPush.destination(msg(MailPush.TOPIC_RES_REQUEST, """{"resources":{"bookingId":4,"resourceId":3}}"""))!!
        assertEquals("request:4", req)
        assertEquals("open" to 4L, MailPush.bookingsOf(req))
        assertEquals("open" to null, MailPush.bookingsOf(MailPush.destination(msg(MailPush.TOPIC_RES_REQUEST, null))!!))
        val answer = MailPush.destination(msg(MailPush.TOPIC_RES_BOOKING, """{"resources":{"bookingId":4,"resourceId":3},"calendar":{"eventId":7,"occurrence":null}}"""))!!
        assertEquals(7L to null, MailPush.eventOf(answer))
        val gone = MailPush.destination(msg(MailPush.TOPIC_RES_BOOKING, """{"resources":{"bookingId":4,"resourceId":3}}"""))!!
        assertEquals("mine" to 4L, MailPush.bookingsOf(gone))
        val share = MailPush.destination(msg(MailPush.TOPIC_SHARE, """{"calendar":{"sharedCalendarId":9}}"""))!!
        assertTrue(MailPush.isShared(share))
        assertNull(MailPush.bookingsOf(share))
        assertNull(MailPush.eventOf(share))
        assertTrue(MailPush.isCalendarTopic(MailPush.TOPIC_SHARE))
        assertTrue(MailPush.isCalendarTopic(MailPush.TOPIC_RES_REQUEST))
        assertTrue(MailPush.isCalendarTopic(MailPush.TOPIC_RES_BOOKING))
        assertNotNull(MailPush.destination(msg(MailPush.TOPIC_SHARE, null)))
    }

    @Test
    fun `new events - own writable calendars first, then the shared ones at Bearbeiten, never read or free busy`() {
        val c = MailPimJson.calendars(
            o(
                """{"calendars":[{"id":1,"name":"Privat","color":"#46e0a0","writable":true},{"id":2,"name":"Feiertage","source":"ics","writable":false}],
                "prefs":{},
                "shared":[{"id":9,"name":"Urlaube","color":"#8C96A5","owner":{"userId":4,"name":"Sabine K."},"level":"read","via":"user","visible":true,"accepted":true,"writable":false},
                {"id":11,"name":"Team","color":"#5B8DEF","owner":{"userId":5,"name":"Tom B."},"level":"write","via":"group","group":"Vertrieb","visible":false,"accepted":true,"writable":true},
                {"id":12,"name":"Belegt","color":"#5B8DEF","owner":{"userId":5,"name":"Tom B."},"level":"freebusy","via":"user","visible":true,"accepted":true,"writable":false}]}""",
            ),
        )!!
        val t = MailPlanLogic.eventTargets(c)
        assertEquals(listOf(1L, 11L), t.map { it.id })
        assertFalse(t[0].isShared)
        assertEquals("Privat", t[0].calendar!!.name)
        assertTrue(t[1].isShared)
        assertEquals("Tom B.", t[1].shared!!.ownerName)
        assertNull(t[1].calendar)
        assertTrue(MailPlanLogic.isSharedTarget(c, 11))
        assertFalse(MailPlanLogic.isSharedTarget(c, 1))
        assertFalse(MailPlanLogic.isSharedTarget(c, null))
        assertTrue(MailPlanLogic.eventTargets(null).isEmpty())
    }

    @Test
    fun `working hours - only what the server takes, midnight as the end is 24 00, the body`() {
        assertEquals(MWorkHours(listOf(1, 3, 5), "07:30", "16:00"), MailPlanLogic.workHours(listOf(5, 1, 3, 3), "07:30", "16:00"))
        assertEquals(MWorkHours(listOf(6), "20:00", "24:00"), MailPlanLogic.workHours(listOf(6), "20:00", "24:00"))
        assertNull(MailPlanLogic.workHours(emptyList(), "08:00", "18:00"), "no day")
        assertNull(MailPlanLogic.workHours(listOf(1), "18:00", "08:00"), "from after to")
        assertNull(MailPlanLogic.workHours(listOf(1), "08:00", "08:00"), "nothing")
        assertNull(MailPlanLogic.workHours(listOf(1), "24:00", "24:00"), "24:00 only as the end")
        assertNull(MailPlanLogic.workHours(listOf(8), "08:00", "18:00"), "no eighth day")
        val h = MWorkHours.DEFAULT
        assertEquals(listOf(1, 2, 3, 4, 5, 6), MailPlanLogic.toggleDay(h, 6)!!.days)
        assertEquals(listOf(2, 3, 4, 5), MailPlanLogic.toggleDay(h, 1)!!.days)
        assertNull(MailPlanLogic.toggleDay(MWorkHours(listOf(3), "08:00", "18:00"), 3), "the last day stays")
        assertEquals("24:00", MailPlanLogic.hhmm(0, 0, end = true))
        assertEquals("00:00", MailPlanLogic.hhmm(0, 0))
        assertEquals("07:05", MailPlanLogic.hhmm(7, 5, end = true))
        val b = MailPimJson.calSettingsBody(workHours = MWorkHours(listOf(1, 2), "09:00", "17:30"))
        assertEquals("""{"workHours":{"days":[1,2],"from":"09:00","to":"17:30"}}""", b.toString())
        assertFalse(MailPimJson.calSettingsBody(freeBusy = true).has("workHours"))
    }
}
