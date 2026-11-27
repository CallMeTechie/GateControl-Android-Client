package com.gatecontrol.android.notizen

import com.google.gson.JsonObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * „Termin“ in the quick entry (plugin 1.9.0, `calendarEvents`; pure, unit-tested): the event as it will be sent
 * (`POST events`), why one cannot be made, the waiting event on the overview, the link into Mail's form.
 *
 * The event is saved in the calendar in Mail only — Notizen passes it on and keeps no copy.
 */
object NotizenEvents {

    /** The event as the chips show it. [calendarId] / [reminder] null with [calendarChosen] / [reminderChosen] false: the calendar's own. */
    data class Draft(
        val title: String,
        /** `YYYY-MM-DD` */
        val date: String,
        /** `HH:MM`; null: all day. */
        val time: String?,
        val endTime: String?,
        val location: String?,
        val calendarId: Long?,
        val calendarChosen: Boolean,
        /** Minutes before (all day: before the midnight of the day); null with [reminderChosen]: none. */
        val reminder: Int?,
        val reminderChosen: Boolean,
        val repeat: NRepeat.Rule?,
    ) {
        val allDay: Boolean get() = time == null
    }

    private val CLIENT_ID = Regex("^[A-Za-z0-9_-]{8,64}$")

    /**
     * `POST events` (README „Termine“): `calendarId` and `reminder` only when the person chose them — else the
     * calendar's at the time of sending (also for the outbox); no `time`: all day; „nach Erledigung“ is no rule of an event.
     */
    fun body(d: Draft, clientId: String?): JsonObject = JsonObject().apply {
        addProperty("title", d.title.replace(Regex("[\\r\\n]+"), " ").trim().take(255))
        addProperty("date", d.date)
        if (d.time != null) {
            addProperty("time", d.time)
            d.endTime?.let { addProperty("endTime", it) }
        }
        d.location?.trim()?.takeIf { it.isNotEmpty() }?.let { addProperty("location", it.take(255)) }
        if (d.calendarChosen) d.calendarId?.let { addProperty("calendarId", it) }
        if (d.reminderChosen) {
            if (d.reminder == null) addProperty("reminder", false) else addProperty("reminder", d.reminder.coerceIn(0, 40320))
        }
        d.repeat?.takeIf { it.type != "after" }?.let { add("repeat", NRepeat.toJson(it)) }
        clientId?.takeIf { CLIENT_ID.matches(it) }?.let { addProperty("clientId", it) }
    }

    // ── why no event can be made ────────────────────────────

    /** The texts of the card „Kein Kalender“ (Bild 9): Mail is missing, the calendar is off, no calendar to write in, no answer. */
    const val WHY_MAIL = "mail"
    const val WHY_OFF = "off"
    const val WHY_NONE = "none"
    const val WHY_UNREACHABLE = "unreachable"

    fun why(reason: String?): String = when (reason) {
        "no_mail", "mail_old" -> WHY_MAIL
        "feature_off", "not_allowed", "not_licensed", "no_seat" -> WHY_OFF
        "no_calendar" -> WHY_NONE
        else -> WHY_UNREACHABLE
    }

    /** A reason that will not pass by itself: the event is not queued, „Als Aufgabe speichern“ is offered instead. */
    fun permanent(reason: String?): Boolean = reason in setOf("no_mail", "mail_old", "feature_off", "not_allowed", "not_licensed", "no_seat", "no_calendar")

    /**
     * Why the person cannot make an event right now (null: they can). `me.events` is asked at most every 10 minutes,
     * `GET events/calendars` is the newer word when it is there (offline: the last known one).
     */
    fun blocked(me: NMe?, calendars: NEventCalendars?): String? = reasonOf(me, calendars)?.takeIf { permanent(it) }

    /** Mail does not answer right now (no lasting reason): the event waits in the outbox and goes later. */
    fun waits(me: NMe?, calendars: NEventCalendars?): Boolean = reasonOf(me, calendars)?.let { !permanent(it) } ?: false

    private fun reasonOf(me: NMe?, calendars: NEventCalendars?): String? = when {
        calendars != null && calendars.ok -> if (calendars.targets.isEmpty()) "no_calendar" else null
        calendars != null -> calendars.reason ?: "no_calendar"
        me?.events?.available == false -> me.events?.reason ?: "no_calendar"
        else -> null
    }

    /** The reminder a chip shows without a choice: the calendar's for a timed or an all-day event. */
    fun defaultReminder(calendars: NEventCalendars?, allDay: Boolean): Int? = if (allDay) calendars?.remindAllDay else calendars?.remindTimed

    /** An hour after [time] (past midnight it wraps — the next day). */
    fun plusHour(time: String): String {
        val t = LocalTime.parse(time).plusHours(1)
        return String.format(java.util.Locale.ROOT, "%02d:%02d", t.hour, t.minute)
    }

    /**
     * A waiting event that can never be made (Mail gone, the calendar off): `POST tasks` with its title, day, time and
     * reminder at the start — the place goes into the note. Nothing the person typed is lost.
     */
    fun asTask(event: JsonObject, clientId: String): JsonObject = JsonObject().apply {
        fun s(k: String) = event.get(k)?.takeIf { it.isJsonPrimitive }?.asString
        addProperty("title", s("title").orEmpty().take(500))
        val date = NotizenJson.date(s("date"))
        val time = NotizenJson.time(s("time"))
        if (date != null) {
            add("due", NotizenJson.dueBody(NDue(date, time)))
            if (time != null) add("remindAt", NotizenJson.dueBody(NDue(date, time)))
        }
        s("location")?.takeIf { it.isNotBlank() }?.let { addProperty("note", it) }
        event.get("repeat")?.takeIf { it.isJsonObject }?.let { add("repeat", it.deepCopy()) }
        addProperty("clientId", clientId)
    }

    /**
     * A waiting event the server refused with [http] (contract § 6): [op] itself while Mail does not answer (`503`
     * with a reason that passes — it stays), the same without `calendarId` and with a new clientId (another body) when
     * the calendar is gone (`404 { field: "calendarId" }`), a task with its day and time when no event can be made for
     * good; null: given up (`400` and anything else).
     */
    fun refused(op: NotizenOutbox.Op, http: Int, reason: String?, field: String?, newClientId: () -> String): NotizenOutbox.Op? {
        val body = op.body ?: return null
        return when {
            http == 503 && !permanent(reason) -> op
            http == 404 && field == "calendarId" && body.has("calendarId") -> {
                val cid = newClientId()
                op.copy(body = body.deepCopy().apply { remove("calendarId"); addProperty("clientId", cid) }, clientId = cid)
            }
            http == 503 -> {
                val cid = newClientId()
                op.copy(what = NotizenOutbox.TASK, body = asTask(body, cid), clientId = cid)
            }
            else -> null
        }
    }

    // ── the waiting event (contract § 7) ────────────────────

    /**
     * A waiting `POST events` as a row of the overview: title, time and place from its body, „wartet auf Verbindung“.
     * Its id is the outbox's negative one; the answer's `items` take its place.
     */
    fun itemOf(op: NotizenOutbox.Op): NItem? {
        if (op.kind != NotizenOutbox.CREATE || op.what != NotizenOutbox.EVENT) return null
        val b = op.body ?: return null
        val title = b.get("title")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val date = NotizenJson.date(b.get("date")?.takeIf { it.isJsonPrimitive }?.asString) ?: return null
        val time = NotizenJson.time(b.get("time")?.takeIf { it.isJsonPrimitive }?.asString)
        val end = NotizenJson.time(b.get("endTime")?.takeIf { it.isJsonPrimitive }?.asString)
        val loc = b.get("location")?.takeIf { it.isJsonPrimitive }?.asString
        val due = NDue(date, time)
        return NItem(
            id = op.id, kind = "event", title = title, subtitle = loc, plugin = "gatecontrol-mail", sourceLabel = "", open = null,
            due = due, show = due, allDay = time == null, lead = null, overdue = false, area = NArea.PRIVATE, important = false, done = false,
            doneAt = null, canComplete = false, assignee = null, progress = null, groupId = null, groupLabel = null, meta = mapOf(PENDING to "true"),
            updatedAt = op.at,
            event = NItemEvent(end?.let { NDue(date, it) }, loc, null, null, null, "own", null, recurring = false, writable = false),
        )
    }

    /** `meta` of a waiting event's row. */
    const val PENDING = "pending"

    fun isPending(i: NItem): Boolean = i.meta[PENDING] == "true"

    /** The waiting events in „Mein Tag“: today's under „Termine heute“, those of the next 14 days under „Demnächst“. */
    fun overlay(view: NView, ops: List<NotizenOutbox.Op>, today: String?): NView {
        if (view.view != "myday" || today == null) return view
        val waiting = ops.mapNotNull { itemOf(it) }
        if (waiting.isEmpty()) return view
        val last = NRepeat.addDays(today, 14) ?: today
        var sections = view.sections
        fun into(id: String, items: List<NItem>) {
            if (items.isEmpty()) return
            sections = if (sections.any { it.id == id }) {
                sections.map { s -> if (s.id == id) s.copy(items = sortByTime(s.items + items), count = s.count + items.size) else s }
            } else {
                // „Termine heute“ on top, „Demnächst“ at the end
                if (id == "events") listOf(NSection(id, "", items.size, items, false)) + sections else sections + NSection(id, "", items.size, items, false)
            }
        }
        into("events", waiting.filter { it.due?.date == today })
        into("upcoming", waiting.filter { val d = it.due?.date; d != null && d > today && d <= last })
        return view.copy(sections = sections)
    }

    private fun sortByTime(items: List<NEntry>): List<NEntry> = items.sortedWith(
        compareBy<NEntry>({ (it as? NItem)?.show?.date ?: (it as? NTask)?.due?.date ?: "9999" }, { (it as? NItem)?.show?.time ?: (it as? NTask)?.due?.time ?: "99" }),
    )

    // ── „Mehr Angaben …“: Mail's own form, filled in ──────────

    /** What Mail's form takes: start and end (epoch ms), all day, the calendar, title, place, reminder, rule. */
    data class FormArgs(
        val start: Long,
        val end: Long,
        val allDay: Boolean,
        val calendarId: Long?,
        val title: String,
        val location: String?,
        /** `none` or minutes. */
        val reminder: String?,
        /** `daily|weekly|monthly|yearly[:interval][:MO,FR]` */
        val repeat: String?,
    )

    /**
     * [d] for Mail's form: the wall-clock time of the hub's zone ([zone]) as instants (all day: the day's midnight in
     * [deviceZone], as the form reads it); the reminder the chip shows ([shownReminder]) goes with it, so the form shows the same.
     */
    fun formArgs(d: Draft, zone: ZoneId, deviceZone: ZoneId, calendarId: Long?, shownReminder: Int?): FormArgs {
        val day = LocalDate.parse(d.date)
        val (start, end) = if (d.time == null) {
            // the form reads an all-day event's last day from the end: the same day
            val s = day.atStartOfDay(deviceZone).toInstant().toEpochMilli()
            s to s
        } else {
            val s = day.atTime(LocalTime.parse(d.time)).atZone(zone)
            var e = day.atTime(LocalTime.parse(d.endTime ?: plusHour(d.time))).atZone(zone)
            // an end not after the start is the next day („22 bis 1 Uhr“)
            if (!e.isAfter(s)) e = e.plusDays(1)
            s.toInstant().toEpochMilli() to e.toInstant().toEpochMilli()
        }
        val rem = if (d.reminderChosen) d.reminder?.toString() ?: "none" else shownReminder?.toString() ?: "none"
        return FormArgs(start, end, d.allDay, calendarId, d.title, d.location, rem, repeatArg(d.repeat))
    }

    private val DAYS = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

    /** A rule of the quick entry as Mail's form reads it: `weekly:2:MO,FR`; null without one (or „nach Erledigung“). */
    fun repeatArg(r: NRepeat.Rule?): String? {
        if (r == null || r.type !in setOf("daily", "weekly", "monthly", "yearly")) return null
        val days = r.days?.mapNotNull { DAYS.getOrNull(it - 1) }?.takeIf { r.type == "weekly" && it.isNotEmpty() }
        return listOfNotNull(r.type, r.interval.coerceAtLeast(1).toString(), days?.joinToString(",")).joinToString(":")
    }

    /** [repeatArg] read again: (kind, interval, days `MO`…); null when it is none. */
    fun parseRepeatArg(v: String?): Triple<String, Int, List<String>>? {
        val parts = v?.split(':') ?: return null
        val type = parts[0].takeIf { it in setOf("daily", "weekly", "monthly", "yearly") } ?: return null
        val interval = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 99) ?: 1
        val days = parts.getOrNull(2)?.split(',')?.filter { it in DAYS }.orEmpty()
        return Triple(type, interval, days)
    }
}
