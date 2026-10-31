package com.gatecontrol.android.mail

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject

/**
 * The answers of Mail's calendars and contacts (contract C3, since Mail 1.2.0), read from the Gson tree like
 * [MailJson] (pure, unit-tested, never by reflection, so R8 cannot change what is read): unknown fields are ignored,
 * an entry missing what it needs is left out, a list keeps what it can read. Also the request bodies.
 */
object MailPimJson {

    private val COLOR = Regex("^#[0-9A-Fa-f]{6}$")
    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val BIRTHDAY = Regex("^(\\d{4}|-)-\\d{2}-\\d{2}$")
    private val ZONE = Regex("^[A-Za-z0-9_+\\-]+(/[A-Za-z0-9_+\\-]+){0,3}$")
    private const val FALLBACK_COLOR = "#46E0A0"

    // ─── helpers ────────────────────────────────────────────────────

    private fun JsonObject.el(key: String): JsonElement? = get(key)?.takeIf { it !is JsonNull }

    private fun JsonObject.str(key: String): String? =
        el(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }

    private fun JsonObject.text(key: String): String? = str(key)?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.long(key: String): Long? = el(key)?.takeIf { it.isJsonPrimitive }?.let { p ->
        val prim = p.asJsonPrimitive
        when {
            prim.isNumber -> runCatching { prim.asDouble }.getOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }?.toLong()
            prim.isString -> prim.asString.trim().toLongOrNull()
            else -> null
        }
    }

    private fun JsonObject.int(key: String): Int? = long(key)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    private fun JsonObject.bool(key: String): Boolean? = el(key)?.takeIf { it.isJsonPrimitive }?.let { p ->
        val prim = p.asJsonPrimitive
        when {
            prim.isBoolean -> prim.asBoolean
            prim.isNumber -> prim.asInt != 0
            prim.isString -> when (prim.asString) { "true", "1" -> true; "false", "0" -> false; else -> null }
            else -> null
        }
    }

    private fun JsonObject.obj(key: String): JsonObject? = el(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arr(key: String): JsonArray? = el(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonArray.objects(): List<JsonObject> = mapNotNull { e -> e.takeIf { it.isJsonObject }?.asJsonObject }

    private fun JsonArray.strings(): List<String> = mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() } }

    private fun JsonArray.longs(): List<Long> = mapNotNull { e ->
        e.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString.trim().toDouble().toLong() }.getOrNull() }?.takeIf { it > 0 }
    }

    private fun JsonArray.ints(): List<Int> = mapNotNull { e ->
        e.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString.trim().toDouble().toInt() }.getOrNull() }
    }

    private fun JsonObject.id(key: String): Long? = long(key)?.takeIf { it > 0 }

    private fun JsonObject.date(key: String): String? = str(key)?.takeIf { DATE.matches(it) }

    private fun JsonObject.color(key: String): String? = str(key)?.takeIf { COLOR.matches(it) }?.uppercase()

    private fun JsonObject.zone(key: String): String? = str(key)?.takeIf { it.length <= 64 && ZONE.matches(it) }

    private fun JsonObject.cursor(key: String): String? =
        el(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() && it.length <= 512 }

    /** A link the app may open: `https:` (or `http:`) only. */
    private fun JsonObject.link(key: String): String? = str(key)?.trim()?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }

    // ─── account side (C2) ──────────────────────────────────────────

    fun pimSide(o: JsonObject?): MPimSide? {
        o ?: return null
        return MPimSide(
            sync = o.bool("sync") ?: true,
            state = o.str("state") ?: MPimSide.STATE_OK,
            readOnly = o.bool("readOnly") ?: false,
            server = o.text("server"),
        )
    }

    // ─── calendars ──────────────────────────────────────────────────

    fun calendar(o: JsonObject): MCalendar? {
        val id = o.id("id") ?: return null
        return MCalendar(
            id = id,
            source = o.str("source") ?: MCalendar.SOURCE_LOCAL,
            accountId = o.id("accountId"),
            name = o.text("name") ?: return null,
            color = o.color("color") ?: FALLBACK_COLOR,
            kind = o.str("kind") ?: "other",
            writable = o.bool("writable") ?: false,
            visible = o.bool("visible") ?: true,
            notify = o.bool("notify") ?: true,
            isDefault = o.bool("isDefault") ?: false,
            canInvite = o.bool("canInvite") ?: false,
            canOnline = o.bool("canOnline") ?: false,
            maxReminders = (o.int("maxReminders") ?: 3).coerceIn(0, 3),
            state = o.str("state") ?: MPimSide.STATE_OK,
            errorCode = o.obj("error")?.str("code"),
            syncedAt = o.long("syncedAt"),
            version = o.long("version") ?: 0L,
        )
    }

    fun overlay(o: JsonObject): MOverlay? {
        val id = o.text("id")?.takeIf { it.length <= 80 } ?: return null
        return MOverlay(id, o.str("source") ?: id.substringBefore(':'), o.text("name") ?: id, o.color("color") ?: "#8C96A5", o.bool("visible") ?: true)
    }

    fun calPrefs(o: JsonObject?): MCalPrefs {
        o ?: return MCalPrefs.DEFAULT
        val d = MCalPrefs.DEFAULT
        return MCalPrefs(
            defaultCalendarId = o.id("defaultCalendarId"),
            view = o.str("view")?.takeIf { it in MCalPrefs.VIEWS } ?: d.view,
            weekStart = o.int("weekStart")?.takeIf { it == 0 || it == 1 } ?: d.weekStart,
            workWeek = o.bool("workWeek") ?: d.workWeek,
            timeZone = o.zone("timeZone"),
            reminders = o.bool("reminders") ?: d.reminders,
            defaultReminder = if (o.has("defaultReminder")) o.int("defaultReminder")?.coerceAtLeast(0) else d.defaultReminder,
            allDayReminder = if (o.has("allDayReminder")) o.int("allDayReminder")?.coerceAtLeast(0) else d.allDayReminder,
        )
    }

    /** `GET calendars`. */
    fun calendars(o: JsonObject): MCalendars? {
        val list = o.arr("calendars") ?: return null
        return MCalendars(
            calendars = list.objects().mapNotNull(::calendar),
            overlays = o.arr("overlays")?.objects()?.mapNotNull(::overlay).orEmpty(),
            prefs = calPrefs(o.obj("prefs")),
            consent = o.arr("consent")?.objects()?.mapNotNull { c ->
                MConsent(c.id("accountId") ?: return@mapNotNull null, c.arr("missing")?.strings().orEmpty())
            }.orEmpty(),
        )
    }

    fun calendarAnswer(o: JsonObject): MCalendar? = o.obj("calendar")?.let(::calendar)

    fun overlayAnswer(o: JsonObject): MOverlay? = o.obj("overlay")?.let(::overlay)

    fun prefsAnswer(o: JsonObject): MCalPrefs? = o.obj("prefs")?.let(::calPrefs)

    // ─── events ─────────────────────────────────────────────────────

    fun online(o: JsonObject?): MOnline? {
        o ?: return null
        val url = o.link("url") ?: return null
        return MOnline(url, o.str("provider") ?: "other")
    }

    fun occurrence(o: JsonObject): MOccurrence? {
        val eventId = o.id("eventId") ?: return null
        val start = o.long("start") ?: return null
        val end = (o.long("end") ?: start).coerceAtLeast(start)
        val occ = o.str("occurrence")?.takeIf { it.isNotEmpty() && it.length <= 40 }
        val allDay = o.bool("allDay") ?: false
        return MOccurrence(
            key = o.str("key")?.takeIf { it.isNotEmpty() } ?: (if (occ != null) "$eventId:$occ" else "$eventId"),
            eventId = eventId,
            calendarId = o.id("calendarId") ?: 0L,
            occurrence = occ,
            title = o.str("title") ?: "",
            start = start,
            end = end,
            allDay = allDay,
            startDate = o.date("startDate"),
            endDate = o.date("endDate") ?: o.date("startDate"),
            timeZone = o.zone("timeZone"),
            location = o.text("location"),
            online = online(o.obj("online")),
            status = o.str("status") ?: MOccurrence.STATUS_CONFIRMED,
            busy = o.str("busy") ?: "busy",
            myResponse = o.str("myResponse"),
            recurring = o.bool("recurring") ?: false,
            // a full Event has the list here (it replaces the count)
            attendees = o.int("attendees")?.coerceAtLeast(0) ?: o.arr("attendees")?.size() ?: 0,
            private = o.bool("private") ?: false,
            reminder = o.int("reminder")?.takeIf { it >= 0 },
        )
    }

    fun overlayItem(o: JsonObject): MOverlayItem? {
        val key = o.text("key") ?: return null
        val start = o.long("start") ?: return null
        return MOverlayItem(
            key = key,
            overlayId = o.text("overlayId") ?: key.substringBefore(':'),
            title = o.str("title") ?: "",
            start = start,
            end = (o.long("end") ?: start).coerceAtLeast(start),
            allDay = o.bool("allDay") ?: false,
            startDate = o.date("startDate"),
            endDate = o.date("endDate") ?: o.date("startDate"),
            location = o.text("location"),
        )
    }

    /** `GET events`. */
    fun events(o: JsonObject): MEvents? {
        val items = o.arr("items") ?: return null
        return MEvents(
            items = items.objects().mapNotNull(::occurrence),
            overlays = o.arr("overlays")?.objects()?.mapNotNull(::overlayItem).orEmpty(),
            pending = o.arr("pending")?.longs().orEmpty(),
        )
    }

    private fun organizer(o: JsonObject?): MOrganizer? {
        o ?: return null
        val email = o.text("email") ?: return null
        return MOrganizer(o.str("name")?.trim().orEmpty(), email, o.bool("self") ?: false, o.id("contactId"))
    }

    fun attendee(o: JsonObject): MAttendee? {
        val email = o.text("email") ?: return null
        return MAttendee(
            name = o.str("name")?.trim().orEmpty(),
            email = email,
            role = o.str("role")?.takeIf { it in setOf(MAttendee.ROLE_REQUIRED, MAttendee.ROLE_OPTIONAL, MAttendee.ROLE_RESOURCE) } ?: MAttendee.ROLE_REQUIRED,
            response = o.str("response") ?: MOccurrence.RESPONSE_NEEDS_ACTION,
            self = o.bool("self") ?: false,
            contactId = o.id("contactId"),
        )
    }

    fun rule(o: JsonObject): MRule? {
        val freq = o.str("freq")?.lowercase()?.takeIf { it in MRule.FREQS } ?: return null
        return MRule(
            freq = freq,
            interval = (o.int("interval") ?: 1).coerceIn(1, 999),
            byDay = o.arr("byDay")?.strings()?.map { it.uppercase() }?.filter { it in MRule.DAYS }?.takeIf { it.isNotEmpty() },
            byMonthDay = o.arr("byMonthDay")?.ints()?.filter { it != 0 && it in -31..31 }?.takeIf { it.isNotEmpty() },
            bySetPos = o.int("bySetPos")?.takeIf { it != 0 && it in -5..5 },
            until = o.date("until"),
            count = o.int("count")?.takeIf { it > 0 },
        )
    }

    private fun recurrence(o: JsonObject?): MRecurrence? {
        o ?: return null
        val rrule = o.str("rrule")?.takeIf { it.isNotBlank() } ?: return null
        val rule = rule(o) ?: MailPimLogic.parseRule(rrule) ?: return null
        return MRecurrence(
            rrule = rrule,
            editable = o.bool("editable") ?: false,
            rule = rule,
            exceptions = o.arr("exceptions")?.strings().orEmpty(),
            deleted = o.arr("deleted")?.strings().orEmpty(),
        )
    }

    /** `GET events/<id>` (the Event at the root) or the `current` of a `conflict`. */
    fun event(o: JsonObject): MEvent? {
        val occ = occurrence(o) ?: return null
        return MEvent(
            occ = occ,
            description = o.str("description") ?: "",
            descriptionHtml = o.str("descriptionHtml")?.takeIf { it.isNotBlank() },
            organizer = organizer(o.obj("organizer")),
            attendees = o.arr("attendees")?.objects()?.mapNotNull(::attendee).orEmpty(),
            reminders = o.arr("reminders")?.ints()?.filter { it >= 0 }?.take(3).orEmpty(),
            recurrence = recurrence(o.obj("recurrence")),
            seriesId = o.id("seriesId"),
            readOnly = o.bool("readOnly") ?: true,
            canRespond = o.bool("canRespond") ?: false,
            canEdit = o.bool("canEdit") ?: false,
            canDelete = o.bool("canDelete") ?: false,
            categories = o.arr("categories")?.strings().orEmpty(),
            invitationId = o.id("invitationId"),
            url = o.link("url"),
            createdAt = o.long("createdAt"),
            updatedAt = o.long("updatedAt"),
            etag = o.str("etag")?.takeIf { it.length <= 200 },
            series = o.obj("series")?.let { x ->
                val a = x.long("start") ?: return@let null
                MSeries(a, (x.long("end") ?: a).coerceAtLeast(a), x.bool("allDay") ?: occ.allDay, x.date("startDate"), x.date("endDate") ?: x.date("startDate"))
            },
        )
    }

    /** `{ event }` (`POST events/<id>/respond`). */
    fun eventAnswer(o: JsonObject): MEvent? = o.obj("event")?.let(::event)

    /** `{ event, sent: { mail, internal } }`. */
    fun eventSaved(o: JsonObject): MEventSaved? {
        val ev = o.obj("event")?.let(::event) ?: return null
        val sent = o.obj("sent")
        return MEventSaved(ev, sent?.int("mail")?.coerceAtLeast(0) ?: 0, sent?.int("internal")?.coerceAtLeast(0) ?: 0)
    }

    /** The newer state a `409 conflict` carries (`error.current`): an Event. */
    fun conflictEvent(error: JsonObject?): MEvent? = (error?.obj("error") ?: error)?.obj("current")?.let(::event)

    /** The newer state a `409 conflict` of a contact carries (`error.current`): a Card. */
    fun conflictCard(error: JsonObject?): MCard? = (error?.obj("error") ?: error)?.obj("current")?.let(::card)

    // ─── invitations ────────────────────────────────────────────────

    fun invitation(o: JsonObject): MInvitation? {
        val id = o.id("id") ?: return null
        val start = o.long("start") ?: return null
        val organizer = organizer(o.obj("organizer")) ?: MOrganizer("", o.obj("from")?.text("email") ?: "", false, null)
        val from = o.obj("from")?.let { f -> MAddress(f.text("name"), f.text("email") ?: organizer.email) } ?: MAddress(organizer.name.ifBlank { null }, organizer.email)
        val counts = o.obj("counts")
        val proposed = o.obj("proposed")
        val summary = o.obj("recurrence")?.obj("summary")
        return MInvitation(
            id = id,
            kind = o.str("kind") ?: "mail",
            messageId = o.id("messageId"),
            accountId = o.id("accountId"),
            method = o.str("method") ?: MInvitation.METHOD_REQUEST,
            state = o.str("state") ?: MInvitation.STATE_OPEN,
            uid = o.str("uid") ?: "",
            title = o.str("title") ?: "",
            start = start,
            end = (o.long("end") ?: start).coerceAtLeast(start),
            allDay = o.bool("allDay") ?: false,
            startDate = o.date("startDate"),
            endDate = o.date("endDate") ?: o.date("startDate"),
            timeZone = o.zone("timeZone"),
            location = o.text("location"),
            online = online(o.obj("online")),
            recurrenceDe = summary?.text("de"),
            recurrenceEn = summary?.text("en"),
            organizer = organizer,
            from = from,
            attendees = o.arr("attendees")?.objects()?.mapNotNull(::attendee).orEmpty(),
            counts = MInviteCounts(
                counts?.int("accepted")?.coerceAtLeast(0) ?: 0,
                counts?.int("tentative")?.coerceAtLeast(0) ?: 0,
                counts?.int("declined")?.coerceAtLeast(0) ?: 0,
                counts?.int("needs_action")?.coerceAtLeast(0) ?: 0,
            ),
            myResponse = o.str("myResponse"),
            comment = o.text("comment"),
            proposedStart = proposed?.long("start"),
            proposedEnd = proposed?.long("end"),
            eventId = o.id("eventId"),
            calendarId = o.id("calendarId"),
            calendarChoices = o.arr("calendarChoices")?.longs().orEmpty(),
            day = o.arr("day")?.objects()?.mapNotNull(::occurrence).orEmpty(),
            conflicts = o.arr("conflicts")?.objects()?.mapNotNull { c ->
                val s = c.long("start") ?: return@mapNotNull null
                MConflict(c.str("title") ?: "", s, c.long("end") ?: s, c.id("calendarId"))
            }.orEmpty(),
        )
    }

    fun invitations(o: JsonObject): List<MInvitation>? = o.arr("items")?.objects()?.mapNotNull(::invitation)

    /** `{ invitation, event }`. */
    fun inviteAnswer(o: JsonObject): MInviteAnswer? {
        val inv = o.obj("invitation")?.let(::invitation) ?: return null
        return MInviteAnswer(inv, o.obj("event")?.let(::event))
    }

    // ─── address books, groups ──────────────────────────────────────

    fun addressbook(o: JsonObject): MAddressbook? {
        val id = o.id("id") ?: return null
        return MAddressbook(
            id = id,
            source = o.str("source") ?: MAddressbook.SOURCE_LOCAL,
            accountId = o.id("accountId"),
            name = o.text("name") ?: return null,
            writable = o.bool("writable") ?: false,
            visible = o.bool("visible") ?: true,
            isDefault = o.bool("isDefault") ?: false,
            count = o.int("count")?.coerceAtLeast(0) ?: 0,
            state = o.str("state") ?: MPimSide.STATE_OK,
            errorCode = o.obj("error")?.str("code"),
            syncedAt = o.long("syncedAt"),
        )
    }

    fun group(o: JsonObject): MGroup? {
        val id = o.id("id") ?: return null
        return MGroup(id, o.text("name") ?: return null, o.color("color"), o.int("count")?.coerceAtLeast(0) ?: 0)
    }

    fun cardPrefs(o: JsonObject?): MCardPrefs {
        o ?: return MCardPrefs.DEFAULT
        return MCardPrefs(
            sort = o.str("sort")?.takeIf { it == "family" || it == "given" } ?: "family",
            show = o.str("show")?.takeIf { it == "given_family" || it == "family_given" } ?: "given_family",
            defaultAddressbookId = o.id("defaultAddressbookId"),
            birthdays = o.bool("birthdays") ?: true,
        )
    }

    /** `GET addressbooks`. */
    fun books(o: JsonObject): MBooks? {
        val items = o.arr("items") ?: return null
        return MBooks(items.objects().mapNotNull(::addressbook), o.arr("groups")?.objects()?.mapNotNull(::group).orEmpty(), cardPrefs(o.obj("prefs")))
    }

    fun bookAnswer(o: JsonObject): MAddressbook? = o.obj("addressbook")?.let(::addressbook)

    fun cardPrefsAnswer(o: JsonObject): MCardPrefs? = o.obj("prefs")?.let(::cardPrefs)

    fun groupAnswer(o: JsonObject): MGroup? = o.obj("group")?.let(::group)

    /** `GET groups/<id>` / `POST groups/<id>/members`. */
    fun groupDetail(o: JsonObject): MGroupDetail? {
        val g = o.obj("group")?.let(::group) ?: return null
        return MGroupDetail(g, o.arr("members")?.objects()?.mapNotNull { m ->
            val id = m.id("id") ?: return@mapNotNull null
            MGroupMember(id, m.id("contactId"), m.str("name")?.trim().orEmpty(), m.text("email"))
        }.orEmpty())
    }

    // ─── contacts ───────────────────────────────────────────────────

    fun cardSummary(o: JsonObject): MCardSummary? {
        val id = o.id("id") ?: return null
        val name = o.str("name")?.trim().orEmpty()
        val sortKey = o.str("sortKey") ?: name.uppercase()
        return MCardSummary(
            id = id,
            addressbookId = o.id("addressbookId") ?: 0L,
            name = name,
            sortKey = sortKey,
            letter = o.str("letter")?.takeIf { it.length == 1 } ?: MailPimLogic.letterOf(sortKey),
            company = o.text("company"),
            title = o.text("title"),
            emails = o.arr("emails")?.strings()?.filter { it.contains('@') }?.take(3).orEmpty(),
            phone = o.text("phone"),
            photo = o.bool("photo") ?: false,
            photoEtag = o.str("photoEtag")?.takeIf { it.isNotEmpty() && it.length <= 200 },
            groups = o.arr("groups")?.longs().orEmpty(),
            linked = o.arr("linked")?.longs().orEmpty(),
            gcUserId = o.id("gcUserId"),
            updatedAt = o.long("updatedAt"),
            favorite = o.bool("favorite"),
            conflicts = o.int("conflicts")?.coerceAtLeast(0) ?: 0,
            masterId = o.id("masterId"),
            sources = o.arr("sources")?.objects()?.mapNotNull { x ->
                MSourceRef(x.id("addressbookId") ?: return@mapNotNull null, x.str("source") ?: MAddressbook.SOURCE_LOCAL, x.id("accountId"))
            }.orEmpty(),
        )
    }

    /** `GET cards`. */
    fun cardPage(o: JsonObject): MCardPage? {
        val items = o.arr("items") ?: return null
        return MCardPage(items.objects().mapNotNull(::cardSummary), o.cursor("next"), o.long("version") ?: 0L, o.arr("deleted")?.longs().orEmpty())
    }

    fun card(o: JsonObject): MCard? {
        val n = o.obj("name")
        return MCard(
            id = o.id("id") ?: 0L,
            addressbookId = o.id("addressbookId") ?: 0L,
            source = o.str("source") ?: MAddressbook.SOURCE_LOCAL,
            readOnly = o.bool("readOnly") ?: false,
            etag = o.str("etag")?.takeIf { it.length <= 200 },
            name = MCardName(
                display = n?.str("display")?.trim().orEmpty(),
                given = n?.str("given")?.trim().orEmpty(),
                family = n?.str("family")?.trim().orEmpty(),
                middle = n?.text("middle"),
                prefix = n?.text("prefix"),
                suffix = n?.text("suffix"),
                nickname = n?.text("nickname"),
            ),
            emails = o.arr("emails")?.objects()?.mapNotNull { e ->
                val v = e.text("value")?.takeIf { it.contains('@') } ?: return@mapNotNull null
                MCardEmail(v, e.str("type")?.takeIf { it in MCard.EMAIL_TYPES } ?: "other", e.bool("primary") ?: false)
            }.orEmpty(),
            phones = o.arr("phones")?.objects()?.mapNotNull(::phone).orEmpty(),
            company = o.text("company"),
            department = o.text("department"),
            title = o.text("title"),
            addresses = o.arr("addresses")?.objects()?.mapNotNull(::address).orEmpty(),
            birthday = o.str("birthday")?.takeIf { BIRTHDAY.matches(it) },
            note = o.str("note") ?: "",
            urls = o.arr("urls")?.strings()?.filter { it.startsWith("https://", true) || it.startsWith("http://", true) }.orEmpty(),
            photoEtag = o.obj("photo")?.str("etag")?.takeIf { it.isNotEmpty() },
            photoMime = o.obj("photo")?.str("mime"),
            groups = o.arr("groups")?.longs().orEmpty(),
            linked = o.arr("linked")?.objects()?.mapNotNull { l ->
                MCardLink(l.id("id") ?: return@mapNotNull null, l.id("addressbookId") ?: 0L, l.str("source") ?: MAddressbook.SOURCE_LOCAL)
            }.orEmpty(),
            gcUserId = o.id("gcUserId"),
            updatedAt = o.long("updatedAt"),
            favorite = o.bool("favorite"),
            mainPhone = o.obj("mainPhone")?.let(::phone),
            mainPhoneChosen = o.bool("mainPhoneChosen") ?: false,
            masterId = o.id("masterId"),
            conflicts = o.arr("conflicts")?.objects()?.mapNotNull(::conflict).orEmpty(),
            sources = o.arr("sources")?.objects()?.mapNotNull(::source).orEmpty(),
        )
    }

    private fun phone(p: JsonObject): MCardPhone? =
        MCardPhone(p.text("value") ?: return null, p.str("type")?.takeIf { it in MCard.PHONE_TYPES } ?: "other")

    private fun address(a: JsonObject): MCardAddress? = MCardAddress(
        type = a.str("type")?.takeIf { it in MCard.ADDRESS_TYPES } ?: "other",
        street = a.str("street")?.trim().orEmpty(),
        postalCode = a.str("postalCode")?.trim().orEmpty(),
        city = a.str("city")?.trim().orEmpty(),
        region = a.str("region")?.trim().orEmpty(),
        country = a.str("country")?.trim().orEmpty(),
    ).takeIf { !it.empty }

    /** A card of a contact (Mail 1.4.0 `Source`). */
    fun source(o: JsonObject): MSource? {
        val id = o.id("id") ?: return null
        return MSource(
            id = id,
            addressbookId = o.id("addressbookId") ?: 0L,
            addressbook = o.str("addressbook")?.trim().orEmpty(),
            source = o.str("source")?.takeIf { it in setOf(MAddressbook.SOURCE_LOCAL, MAddressbook.SOURCE_GRAPH, MAddressbook.SOURCE_CARDDAV) } ?: MAddressbook.SOURCE_LOCAL,
            accountId = o.id("accountId"),
            account = o.text("account"),
        )
    }

    /** A value of [field] in its form: a number, a postal address or a text. */
    private fun fieldValue(field: String, e: JsonElement?): MFieldValue? {
        e ?: return null
        if (e is JsonNull) return null
        return when (field) {
            MCardConflict.PHONES -> e.takeIf { it.isJsonObject }?.asJsonObject?.let(::phone)?.let { MFieldValue.Phone(it) }
            MCardConflict.ADDRESSES -> e.takeIf { it.isJsonObject }?.asJsonObject?.let(::address)?.let { MFieldValue.Address(it) }
            MCardConflict.BIRTHDAY -> e.takeIf { it.isJsonPrimitive }?.asString?.takeIf { BIRTHDAY.matches(it) }?.let { MFieldValue.Text(it) }
            else -> e.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }?.let { MFieldValue.Text(it.take(500)) }
        }
    }

    /** A conflict of a GC contact (Mail 1.4.0); an unknown field or an unreadable value leaves it out. */
    fun conflict(o: JsonObject): MCardConflict? {
        val id = o.id("id") ?: return null
        val field = o.str("field")?.takeIf { it in MCardConflict.FIELDS } ?: return null
        val value = fieldValue(field, o.el("value")) ?: return null
        return MCardConflict(id, field, o.str("type"), value, fieldValue(field, o.el("replaces")), o.obj("source")?.let(::source), o.long("at"))
    }

    /** `PATCH cards/<id>` → the contact and `writeBack` (Mail 1.4.0; empty before). */
    fun cardSaved(o: JsonObject): MCardSaved? {
        val card = cardAnswer(o) ?: return null
        return MCardSaved(card, o.arr("writeBack")?.objects()?.mapNotNull { w ->
            MWriteBack(w.id("id") ?: return@mapNotNull null, w.id("addressbookId") ?: 0L, w.bool("ok") ?: false, w.str("code"))
        }.orEmpty())
    }

    /** A `Preview` (Mail 1.4.0): the Card fields of the result and where they come from. */
    fun preview(o: JsonObject): MPreview? {
        val c = card(o) ?: return null
        if (c.label.isBlank() && c.emails.isEmpty() && c.phones.isEmpty()) return null
        return MPreview(
            id = o.id("id"),
            card = c.copy(id = o.id("id") ?: 0L, mainPhone = o.obj("mainPhone")?.let(::phone)),
            photoFrom = o.id("photoFrom"),
            sources = o.arr("sources")?.objects()?.mapNotNull(::source).orEmpty(),
            losers = o.arr("conflicts")?.objects()?.mapNotNull { x ->
                val field = x.str("field") ?: return@mapNotNull null
                val v = x.el("value")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                MPreviewLoser(field, v.take(500), x.obj("source")?.let(::source))
            }.orEmpty(),
        )
    }

    /** `POST cards/merge/preview`. */
    fun previewAnswer(o: JsonObject): MPreview? = o.obj("preview")?.let(::preview)

    /** A merge of the protocol (Mail 1.4.0). */
    fun merge(o: JsonObject): MMerge? {
        val id = o.id("id") ?: return null
        val master = o.id("masterId") ?: return null
        return MMerge(
            id = id,
            masterId = master,
            mode = o.str("mode")?.takeIf { it in setOf(MMerge.AUTO, MMerge.MANUAL, MMerge.MIGRATED) } ?: MMerge.MANUAL,
            created = o.bool("created") ?: false,
            reason = o.str("reason"),
            at = o.long("at") ?: 0L,
            undoneAt = o.long("undoneAt"),
            cards = o.arr("cards")?.objects()?.mapNotNull { c ->
                MMergeCard(
                    id = c.id("id") ?: return@mapNotNull null,
                    name = c.str("name")?.trim().orEmpty(),
                    addressbookId = c.id("addressbookId") ?: 0L,
                    addressbook = c.str("addressbook")?.trim().orEmpty(),
                    source = c.str("source") ?: MAddressbook.SOURCE_LOCAL,
                    removed = c.bool("removed") ?: false,
                )
            }.orEmpty(),
        )
    }

    /** `GET cards/merges`. */
    fun merges(o: JsonObject): List<MMerge>? = o.arr("items")?.objects()?.mapNotNull(::merge)

    /** `POST cards/merges/<id>/undo`, `POST cards/<id>/unmerge` (the contact may be gone: `card: null`). */
    fun unmerge(o: JsonObject): MUnmerge? {
        if (!o.has("unlinked") && !o.has("card")) return null
        return MUnmerge(o.obj("card")?.let(::card)?.takeIf { it.id > 0 }, o.arr("unlinked")?.longs().orEmpty(), o.arr("restored")?.longs().orEmpty())
    }

    /** `GET cards/favorites` / `PUT cards/favorites` (by their position). */
    fun favorites(o: JsonObject): List<MFavorite>? = o.arr("items")?.objects()?.mapNotNull { f ->
        MFavorite(
            id = f.id("id") ?: return@mapNotNull null,
            name = f.str("name")?.trim().orEmpty(),
            photoEtag = f.str("photoEtag")?.takeIf { it.isNotEmpty() && it.length <= 200 && f.bool("photo") != false },
            phone = f.obj("phone")?.let(::phone) ?: f.text("phone")?.let { MCardPhone(it, "other") },
            email = f.text("email")?.takeIf { it.contains('@') },
        )
    }

    /** `GET events/upcoming` (Mail 1.4.0, the calendar widget on Start). */
    fun upcoming(o: JsonObject): MUpcoming? {
        val items = o.arr("items") ?: return null
        val today = o.date("today") ?: return null
        return MUpcoming(
            timeZone = o.zone("timeZone"),
            today = today,
            items = items.objects().mapNotNull { x ->
                val start = x.long("start") ?: return@mapNotNull null
                val key = x.str("key")?.takeIf { it.isNotEmpty() && it.length <= 120 } ?: return@mapNotNull null
                MUpcomingItem(
                    key = key,
                    eventId = x.id("eventId"),
                    calendarId = x.id("calendarId"),
                    overlayId = x.text("overlayId"),
                    color = x.color("color"),
                    title = x.str("title")?.trim().orEmpty(),
                    start = start,
                    end = (x.long("end") ?: start).coerceAtLeast(start),
                    allDay = x.bool("allDay") ?: false,
                    day = x.date("day") ?: x.date("startDate") ?: today,
                    location = x.text("location"),
                    tentative = x.bool("tentative") ?: false,
                )
            },
            more = o.int("more")?.coerceAtLeast(0) ?: 0,
        )
    }

    /** `GET cards/<id>` (a Card with an id). */
    fun fullCard(o: JsonObject): MCard? = card(o).takeIf { it?.id ?: 0L > 0 }

    /** `{ card }`. */
    fun cardAnswer(o: JsonObject): MCard? = o.obj("card")?.let(::card)?.takeIf { it.id > 0 }

    /** `GET cards/lookup?email=`. */
    fun lookup(o: JsonObject): MLookup {
        val person = o.obj("person")
        val recent = o.obj("recent")
        return MLookup(
            card = o.obj("card")?.let(::card)?.takeIf { it.id > 0 },
            personUserId = person?.id("userId"),
            personName = person?.text("name"),
            recentName = recent?.text("name"),
            sent = recent?.int("sent")?.coerceAtLeast(0) ?: 0,
            received = recent?.int("received")?.coerceAtLeast(0) ?: 0,
            lastAt = recent?.long("lastAt"),
        )
    }

    /** `GET cards/<id>/activity`. */
    fun activity(o: JsonObject): MActivity = MActivity(
        mails = MailJson.summaries(o.arr("mails")),
        events = o.arr("events")?.objects()?.mapNotNull(::occurrence).orEmpty(),
    )

    /** `GET cards/draft`. */
    fun cardDraft(o: JsonObject): MCardDraft? {
        val d = o.obj("draft")?.let(::card) ?: return null
        return MCardDraft(d.copy(id = 0, etag = null), o.obj("existing")?.let(::card)?.takeIf { it.id > 0 })
    }

    /** `GET cards/duplicates`. */
    fun duplicates(o: JsonObject): List<MDuplicate>? = o.arr("items")?.objects()?.mapNotNull { d ->
        val cards = d.arr("cards")?.objects()?.mapNotNull(::cardSummary).orEmpty()
        if (cards.size < 2) return@mapNotNull null
        MDuplicate(
            key = d.str("key") ?: cards.joinToString("-") { it.id.toString() },
            reason = d.str("reason") ?: "name",
            cards = cards,
            certain = d.bool("certain"),
            details = d.arr("details")?.objects()?.mapNotNull { x ->
                val c = x.obj("card")?.let(::card)?.takeIf { it.id > 0 } ?: return@mapNotNull null
                MDuplicateCard(c, x.obj("source")?.let(::source))
            }.orEmpty(),
            preview = d.obj("preview")?.let(::preview),
        )
    }

    fun importAnswer(o: JsonObject): MImport? {
        if (!o.has("imported")) return null
        return MImport(o.int("imported")?.coerceAtLeast(0) ?: 0, o.int("updated")?.coerceAtLeast(0) ?: 0, o.int("skipped")?.coerceAtLeast(0) ?: 0)
    }

    /** `GET cards/export` → the file (Base64 decoded); an empty address book gives an empty file. */
    fun export(o: JsonObject): MExport? {
        val data = o.str("data") ?: return null
        val bytes = if (data.isEmpty()) ByteArray(0) else runCatching { java.util.Base64.getMimeDecoder().decode(data) }.getOrNull() ?: return null
        val name = o.str("name")?.takeIf { it.matches(Regex("^[A-Za-z0-9 ._-]{1,80}\\.vcf$")) } ?: "kontakte.vcf"
        return MExport(name, "text/vcard", bytes)
    }

    /** `GET cards/<id>/photo` → JPEG or PNG only. */
    fun photo(o: JsonObject): MPhoto? {
        val mime = o.str("mime")?.lowercase()?.takeIf { it == "image/jpeg" || it == "image/png" } ?: return null
        val bytes = o.str("data")?.let { runCatching { java.util.Base64.getMimeDecoder().decode(it) }.getOrNull() } ?: return null
        if (bytes.isEmpty() || bytes.size > 512 * 1024) return null
        return MPhoto(mime, bytes)
    }

    // ─── live ───────────────────────────────────────────────────────

    /** Realtime event `calendar` (or the plugin's data under `calendar`). */
    fun calChanged(data: JsonElement?): MCalChanged? {
        val o = data?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val root = o.obj("calendar") ?: o
        val kind = root.str("kind")?.takeIf { it in setOf(MCalChanged.CHANGED, MCalChanged.CALENDARS, MCalChanged.INVITATION, MCalChanged.CONFLICT) } ?: return null
        return MCalChanged(kind, root.id("calendarId"), root.id("accountId"), root.long("from"), root.long("to"), root.id("invitationId"), root.id("messageId"), root.id("eventId"))
    }

    /** Realtime event `contacts` (or the plugin's data under `contacts`). */
    fun contactsChanged(data: JsonElement?): MContactsChanged? {
        val o = data?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val root = o.obj("contacts") ?: o
        val kind = root.str("kind")?.takeIf { it in MContactsChanged.KINDS } ?: return null
        return MContactsChanged(kind, root.id("addressbookId"), root.arr("contactIds")?.longs()?.take(50).orEmpty(), root.id("masterId"))
    }

    // ─── request bodies ─────────────────────────────────────────────

    fun calendarBody(name: String, color: String?): JsonObject = JsonObject().apply {
        addProperty("name", name.trim())
        color?.takeIf { COLOR.matches(it) }?.let { addProperty("color", it) }
    }

    fun calendarPatch(name: String? = null, color: String? = null, visible: Boolean? = null, notify: Boolean? = null): JsonObject = JsonObject().apply {
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { addProperty("name", it) }
        color?.takeIf { COLOR.matches(it) }?.let { addProperty("color", it) }
        visible?.let { addProperty("visible", it) }
        notify?.let { addProperty("notify", it) }
    }

    fun overlayPatch(visible: Boolean? = null, color: String? = null): JsonObject = JsonObject().apply {
        visible?.let { addProperty("visible", it) }
        color?.takeIf { COLOR.matches(it) }?.let { addProperty("color", it) }
    }

    fun subscribeBody(url: String, name: String?): JsonObject = JsonObject().apply {
        addProperty("url", url.trim())
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { addProperty("name", it) }
    }

    fun calSettingsBody(view: String? = null, timeZone: String? = null, defaultCalendarId: Long? = null, workWeek: Boolean? = null): JsonObject = JsonObject().apply {
        view?.takeIf { it in MCalPrefs.VIEWS }?.let { addProperty("view", it) }
        timeZone?.let { addProperty("timeZone", it) }
        defaultCalendarId?.let { addProperty("defaultCalendarId", it) }
        workWeek?.let { addProperty("workWeek", it) }
    }

    fun ruleBody(r: MRule): JsonObject = JsonObject().apply {
        addProperty("freq", r.freq)
        addProperty("interval", r.interval.coerceIn(1, 999))
        r.byDay?.takeIf { it.isNotEmpty() }?.let { d -> add("byDay", JsonArray().apply { d.forEach { add(it) } }) }
        r.byMonthDay?.takeIf { it.isNotEmpty() }?.let { d -> add("byMonthDay", JsonArray().apply { d.forEach { add(it) } }) }
        r.bySetPos?.let { addProperty("bySetPos", it) }
        r.until?.let { addProperty("until", it) }
        r.count?.let { addProperty("count", it) }
    }

    /** `POST events` / `PATCH events/<id>`: only what is given; [scope] and [occurrence] for a series, [etag] to guard. */
    fun eventBody(x: MEventInput, scope: String? = null, occurrence: String? = null, etag: String? = null): JsonObject = JsonObject().apply {
        x.calendarId?.let { addProperty("calendarId", it) }
        x.title?.let { addProperty("title", it.trim()) }
        x.allDay?.let { addProperty("allDay", it) }
        if (x.allDay == true) {
            x.startDate?.let { addProperty("startDate", it) }
            x.endDate?.let { addProperty("endDate", it) }
        } else {
            x.start?.let { addProperty("start", it) }
            x.end?.let { addProperty("end", it) }
        }
        x.timeZone?.let { addProperty("timeZone", it) }
        x.location?.let { if (it.isBlank()) add("location", JsonNull.INSTANCE) else addProperty("location", it.trim()) }
        x.description?.let { addProperty("description", it) }
        if (x.onlineSet) {
            when {
                x.teams -> add("online", JsonObject().apply { addProperty("create", "teams") })
                !x.onlineUrl.isNullOrBlank() -> add("online", JsonObject().apply { addProperty("url", x.onlineUrl.trim()) })
                else -> add("online", JsonNull.INSTANCE)
            }
        }
        x.reminders?.let { r -> add("reminders", JsonArray().apply { r.distinct().sorted().take(3).forEach { add(it) } }) }
        if (x.recurrenceSet) add("recurrence", x.recurrence?.let(::ruleBody) ?: JsonNull.INSTANCE)
        x.attendees?.let { list ->
            add("attendees", JsonArray().apply {
                list.forEach { a ->
                    add(JsonObject().apply {
                        addProperty("email", a.email.trim())
                        a.name?.trim()?.takeIf { it.isNotEmpty() }?.let { addProperty("name", it) }
                        addProperty("role", a.role)
                    })
                }
            })
        }
        x.busy?.let { addProperty("busy", it) }
        x.private?.let { addProperty("private", it) }
        x.sendInvites?.let { addProperty("sendInvites", it) }
        scope?.let { addProperty("scope", it) }
        occurrence?.let { addProperty("occurrence", it) }
        etag?.let { addProperty("etag", it) }
    }

    fun respondBody(response: String, comment: String?, occurrence: String? = null, proposeStart: Long? = null, proposeEnd: Long? = null, calendarId: Long? = null): JsonObject =
        JsonObject().apply {
            addProperty("response", response)
            comment?.trim()?.takeIf { it.isNotEmpty() }?.let { addProperty("comment", it.take(2000)) }
            occurrence?.let { addProperty("occurrence", it) }
            calendarId?.let { addProperty("calendarId", it) }
            if (proposeStart != null && proposeEnd != null && proposeEnd >= proposeStart) {
                addProperty("proposeStart", proposeStart)
                addProperty("proposeEnd", proposeEnd)
            }
        }

    fun dismissBody(decline: Boolean): JsonObject = JsonObject().apply { if (decline) addProperty("decline", true) }

    /** A contact's fields for `POST cards` / `PATCH cards/<id>` (all of them: the form edits the whole contact). */
    fun cardBody(c: MCard, addressbookId: Long? = null, etag: String? = null, groups: List<Long>? = c.groups): JsonObject = JsonObject().apply {
        addressbookId?.let { addProperty("addressbookId", it) }
        add("name", JsonObject().apply {
            addProperty("given", c.name.given.trim())
            addProperty("family", c.name.family.trim())
            c.name.middle?.let { addProperty("middle", it) }
            c.name.prefix?.let { addProperty("prefix", it) }
            c.name.suffix?.let { addProperty("suffix", it) }
            c.name.nickname?.let { addProperty("nickname", it) }
        })
        add("emails", JsonArray().apply {
            c.emails.filter { it.value.isNotBlank() }.distinctBy { it.value.trim().lowercase() }.forEach { e ->
                add(JsonObject().apply {
                    addProperty("value", e.value.trim())
                    addProperty("type", e.type)
                    if (e.primary) addProperty("primary", true)
                })
            }
        })
        add("phones", JsonArray().apply {
            c.phones.filter { it.value.isNotBlank() }.forEach { p ->
                add(JsonObject().apply {
                    addProperty("value", p.value.trim())
                    addProperty("type", p.type)
                })
            }
        })
        fun opt(key: String, v: String?) { if (v.isNullOrBlank()) add(key, JsonNull.INSTANCE) else addProperty(key, v.trim()) }
        opt("company", c.company)
        opt("department", c.department)
        opt("title", c.title)
        add("addresses", JsonArray().apply {
            c.addresses.filter { !it.empty }.forEach { a ->
                add(JsonObject().apply {
                    addProperty("type", a.type)
                    addProperty("street", a.street.trim())
                    addProperty("postalCode", a.postalCode.trim())
                    addProperty("city", a.city.trim())
                    addProperty("region", a.region.trim())
                    addProperty("country", a.country.trim())
                })
            }
        })
        if (c.birthday != null && BIRTHDAY.matches(c.birthday)) addProperty("birthday", c.birthday) else add("birthday", JsonNull.INSTANCE)
        addProperty("note", c.note)
        groups?.let { g -> add("groups", JsonArray().apply { g.distinct().forEach { add(it) } }) }
        etag?.let { addProperty("etag", it) }
    }

    fun bookPatch(visible: Boolean): JsonObject = JsonObject().apply { addProperty("visible", visible) }

    /** `POST cards/merge`; [primaryId] null: the server picks (Mail 1.4.0, as its preview shows; older ones need it). */
    fun mergeBody(ids: List<Long>, primaryId: Long?, pick: MMergePick?, joinNotes: Boolean): JsonObject = JsonObject().apply {
        add("ids", JsonArray().apply { ids.distinct().forEach { add(it) } })
        primaryId?.let { addProperty("primaryId", it) }
        val p = JsonObject().apply {
            pick?.name?.let { addProperty("name", it) }
            pick?.company?.let { addProperty("company", it) }
            pick?.title?.let { addProperty("title", it) }
            pick?.birthday?.let { addProperty("birthday", it) }
            pick?.photo?.let { addProperty("photo", it) }
            if (joinNotes) addProperty("note", "join")
        }
        if (p.size() > 0) add("pick", p)
    }

    fun idsBody(ids: List<Long>): JsonObject = JsonObject().apply { add("ids", JsonArray().apply { ids.distinct().forEach { add(it) } }) }

    /** `PATCH cards/<id>` of GateControl's own fields only (never to the provider): favourite, main number (null: none chosen). */
    fun ownBody(favorite: Boolean? = null, mainPhone: String? = null, clearMainPhone: Boolean = false): JsonObject = JsonObject().apply {
        favorite?.let { addProperty("favorite", it) }
        if (clearMainPhone) add("mainPhone", JsonNull.INSTANCE) else mainPhone?.let { addProperty("mainPhone", it) }
    }

    /** `POST cards/<id>/conflicts/<cid>/accept`: `replace` or `add` (null: the server's default). */
    fun acceptBody(mode: String?): JsonObject = JsonObject().apply { mode?.let { addProperty("mode", it) } }

    /** `POST cards/<id>/unmerge` ([ids] null: all links) and `POST cards/merges/<id>/undo`. */
    fun unmergeBody(ids: List<Long>?, keepMaster: Boolean): JsonObject = JsonObject().apply {
        ids?.let { add("ids", JsonArray().apply { it.distinct().forEach { id -> add(id) } }) }
        if (keepMaster) addProperty("keepMaster", true)
    }

    fun importBody(vcf: String, addressbookId: Long?): JsonObject = JsonObject().apply {
        addressbookId?.let { addProperty("addressbookId", it) }
        addProperty("vcf", vcf)
    }

    fun groupBody(name: String, color: String? = null): JsonObject = JsonObject().apply {
        addProperty("name", name.trim())
        color?.takeIf { COLOR.matches(it) }?.let { addProperty("color", it) }
    }

    fun membersBody(addContacts: List<Long> = emptyList(), addEmails: List<MAddress> = emptyList(), remove: List<Long> = emptyList()): JsonObject = JsonObject().apply {
        add("add", JsonArray().apply {
            addContacts.forEach { id -> add(JsonObject().apply { addProperty("contactId", id) }) }
            addEmails.forEach { a ->
                add(JsonObject().apply {
                    addProperty("email", a.email)
                    a.name?.let { addProperty("name", it) }
                })
            }
        })
        add("remove", JsonArray().apply { remove.forEach { add(it) } })
    }

    fun photoBody(mime: String, bytes: ByteArray): JsonObject = JsonObject().apply {
        addProperty("mime", mime)
        addProperty("data", java.util.Base64.getEncoder().encodeToString(bytes))
    }
}
