package com.gatecontrol.android.notizen

import com.gatecontrol.android.notizen.NotizenJson.arr
import com.gatecontrol.android.notizen.NotizenJson.bool
import com.gatecontrol.android.notizen.NotizenJson.int
import com.gatecontrol.android.notizen.NotizenJson.long
import com.gatecontrol.android.notizen.NotizenJson.obj
import com.gatecontrol.android.notizen.NotizenJson.str
import com.google.gson.JsonObject

/** The JSON of the events of the calendar (plugin 1.9.0, `calendarEvents`; README „Termine“): parsed defensively. */
object NotizenEventsJson {
    private val COLOR = Regex("^#[0-9A-Fa-f]{6}$")
    private val KEY = Regex("^[A-Za-z0-9:_-]{1,80}$")
    private val REASON = Regex("^[a-z_]{1,40}$")
    private val KINDS = setOf("own", "shared", "family", "subscribed", "tasks")

    fun color(v: String?): String? = v?.takeIf { COLOR.matches(it) }

    private fun reason(v: String?): String? = v?.takeIf { REASON.matches(it) }

    /** `me.events`; null with an older plugin. */
    fun info(o: JsonObject?): NEventsInfo? = o?.let { NEventsInfo(it.bool("available") ?: false, reason(it.str("reason"))) }

    fun calendar(o: JsonObject): NEventCalendar? {
        val key = o.str("key")?.takeIf { KEY.matches(it) } ?: return null
        return NEventCalendar(
            id = o.long("id")?.takeIf { it > 0 },
            key = key,
            name = o.str("name")?.take(120) ?: return null,
            color = color(o.str("color")),
            kind = o.str("kind")?.takeIf { it in KINDS } ?: "own",
            writable = o.bool("writable") ?: false,
            target = o.bool("target") ?: false,
            isDefault = o.bool("isDefault") ?: false,
            area = NArea.of(o.str("area")),
            shown = o.bool("shown") ?: false,
        )
    }

    /** `GET events/calendars`. */
    fun calendars(o: JsonObject): NEventCalendars? {
        val state = o.str("state")?.takeIf { it == "ok" || it == "none" } ?: return null
        val rem = o.obj("reminders")
        return NEventCalendars(
            state = state,
            reason = reason(o.str("reason")),
            calendars = o.arr("calendars")?.mapNotNull { e -> (e as? JsonObject)?.let { runCatching { calendar(it) }.getOrNull() } }.orEmpty(),
            defaultId = o.long("defaultId")?.takeIf { it > 0 },
            remindTimed = rem?.int("timed")?.takeIf { it in 0..40320 },
            remindAllDay = rem?.int("allDay")?.takeIf { it in 0..40320 },
            timeZone = o.str("timeZone")?.take(80),
        )
    }

    /** `Item.event`; null for any other item. */
    fun itemEvent(o: JsonObject?): NItemEvent? {
        if (o == null) return null
        val cal = o.obj("calendar")
        val end = o.obj("end")?.let { e -> NotizenJson.date(e.str("date"))?.let { NDue(it, NotizenJson.time(e.str("time"))) } }
        return NItemEvent(
            end = end,
            location = o.str("location")?.take(255)?.takeIf { it.isNotBlank() },
            calendarKey = cal?.str("key")?.takeIf { KEY.matches(it) },
            calendarName = cal?.str("name")?.take(120),
            color = color(cal?.str("color")),
            calendarKind = cal?.str("kind")?.takeIf { it in KINDS },
            eventId = o.long("eventId")?.takeIf { it > 0 },
            recurring = o.bool("recurring") ?: false,
            writable = o.bool("writable") ?: false,
        )
    }

    /** The answer of `POST events` / `PATCH events/<id>`. */
    fun created(o: JsonObject): NEventCreated? {
        val ev = o.obj("event") ?: return null
        return NEventCreated(
            eventId = ev.long("id")?.takeIf { it > 0 } ?: return null,
            calendarId = ev.long("calendarId")?.takeIf { it > 0 },
            items = o.arr("items")?.mapNotNull { e -> (e as? JsonObject)?.let { runCatching { NotizenJson.item(it) }.getOrNull() } }.orEmpty(),
            replayed = o.bool("replayed") ?: false,
        )
    }

    /** `DELETE events/<id>` → removed. */
    fun removed(o: JsonObject): Boolean = o.bool("removed") ?: false
}
