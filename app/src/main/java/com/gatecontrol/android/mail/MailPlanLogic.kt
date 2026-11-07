package com.gatecontrol.android.mail

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * The pure parts of free/busy, sharing and resources (Mail 1.9.0, unit-tested): the bars of a day, its window (the
 * working hours, widened to the chosen time), who cannot at a time, the labels of rows and resources, the state of a
 * booking and what may be done with it. The texts come from the screens.
 */
object MailPlanLogic {

    const val FREE = "free"
    const val BUSY = "busy"
    const val TENTATIVE = "tentative"
    const val UNKNOWN = "unknown"
    /** A request in a booking's bar (the time asked for). */
    const val ASK = "ask"

    /** The pills of a booking. */
    const val PILL_WAITING = "waiting"
    const val PILL_CONFLICT = "conflict"
    const val PILL_CONFIRMED = "confirmed"
    const val PILL_DECLINED = "declined"
    const val PILL_CANCELLED = "cancelled"

    private const val HOUR_MS = 3_600_000L
    private val RESOURCE_ADDRESS = Regex("^resource-(\\d{1,15})@resources\\.gatecontrol\\.invalid$", RegexOption.IGNORE_CASE)

    // ─── where a new event can go (Mail 1.9.0) ───────────────────────

    /**
     * A calendar a new event can be saved in: an own writable one ([calendar] set) or a GateControl calendar shared with
     * the person at „Bearbeiten“ ([shared] set; saved as its owner, without attendees — the server answers 409
     * `not_supported { what: 'shared_attendees' }` otherwise).
     */
    data class EventTarget(val id: Long, val name: String, val color: String, val calendar: MCalendar?, val shared: MSharedCalendar?) {
        val isShared: Boolean get() = shared != null
    }

    /** The own writable calendars first, then those shared with the person at „Bearbeiten“ (as in the portal). */
    fun eventTargets(cals: MCalendars?): List<EventTarget> {
        cals ?: return emptyList()
        val own = cals.calendars.filter { it.writable }.map { EventTarget(it.id, it.name, it.color, it, null) }
        val ids = own.map { it.id }.toSet()
        val shared = cals.shared.filter { it.level == MShare.WRITE && it.id !in ids }.map { EventTarget(it.id, it.name, it.color, null, it) }
        return own + shared
    }

    /** Whether [calendarId] is a calendar shared with the person (not an own one). */
    fun isSharedTarget(cals: MCalendars?, calendarId: Long?): Boolean =
        calendarId != null && cals?.calendar(calendarId) == null && cals?.sharedCalendar(calendarId) != null

    // ─── working hours (`CalPrefs.workHours`) ───────────────────────

    private val HHMM = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

    /** The working hours as the server takes them (days 1…7 sorted, at least one, `from` < `to`, `to` up to 24:00), else null. */
    fun workHours(days: Collection<Int>, from: String, to: String): MWorkHours? {
        val d = days.filter { it in 1..7 }.distinct().sorted()
        if (d.isEmpty() || !HHMM.matches(from) || !(HHMM.matches(to) || to == END_OF_DAY) || from >= to) return null
        return MWorkHours(d, from, to)
    }

    /** A day switched on or off; null when no day would be left. */
    fun toggleDay(h: MWorkHours, day: Int): MWorkHours? {
        val next = if (day in h.days) h.days - day else h.days + day
        return workHours(next, h.from, h.to)
    }

    /** A picked time as `HH:MM`; midnight as the end of the working day is `24:00`. */
    fun hhmm(hour: Int, minute: Int, end: Boolean = false): String =
        if (end && hour == 0 && minute == 0) END_OF_DAY else "%02d:%02d".format(hour.coerceIn(0, 23), minute.coerceIn(0, 59))

    const val END_OF_DAY = "24:00"

    /** A part of a bar: [status] over [start, end), [fraction] of the whole bar. */
    data class Segment(val status: String, val start: Long, val end: Long, val fraction: Float)

    /**
     * The bar of [from, to): the blocks cut to it and sorted, the gaps between them [gap] (free, or unknown); where
     * blocks overlap the earlier one keeps its part.
     */
    fun bar(blocks: List<MBusyBlock>, from: Long, to: Long, gap: String = FREE): List<Segment> {
        val len = to - from
        if (len <= 0) return emptyList()
        val list = blocks.map { MBusyBlock(maxOf(from, it.start), minOf(to, it.end), it.status) }.filter { it.end > it.start }.sortedBy { it.start }
        val out = ArrayList<Segment>()
        fun push(status: String, a: Long, z: Long) {
            if (z > a) out += Segment(status, a, z, (z - a).toFloat() / len)
        }
        var t = from
        for (b in list) {
            if (b.start > t) push(gap, t, b.start)
            push(statusOf(b.status), maxOf(t, b.start), b.end)
            t = maxOf(t, b.end)
        }
        push(gap, t, to)
        return out
    }

    /** `pending` (a resource's request) shows like tentative. */
    private fun statusOf(s: String): String = when (s) {
        MBusyBlock.PENDING, MBusyBlock.TENTATIVE -> TENTATIVE
        ASK -> ASK
        else -> BUSY
    }

    /** `HH:MM` → minutes of the day (`24:00` → 1440), null when not such a time. */
    fun minutes(hhmm: String?): Int? {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(hhmm?.trim() ?: return null) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (min > 59 || h > 24 || (h == 24 && min != 0)) return null
        return h * 60 + min
    }

    private fun at(day: LocalDate, minute: Int, zone: ZoneId): Long = day.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant().toEpochMilli()

    /**
     * The window of a day's bars: the working hours ([hours], else 08:00–18:00), widened to the chosen time
     * [start, end) when it begins that day (from its full hour, to its end, at most to midnight).
     */
    fun dayWindow(day: LocalDate, hours: MWorkHours?, start: Long?, end: Long?, zone: ZoneId): Pair<Long, Long> {
        val a = minutes(hours?.from) ?: 8 * 60
        val z = (minutes(hours?.to) ?: 18 * 60).takeIf { it > a } ?: (a + 60).coerceAtMost(1440)
        var from = at(day, a, zone)
        var to = at(day, z, zone)
        if (start != null && end != null && end > start && Instant.ofEpochMilli(start).atZone(zone).toLocalDate() == day) {
            val s = Instant.ofEpochMilli(start).atZone(zone)
            from = minOf(from, s.withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli())
            to = maxOf(to, minOf(day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), end))
        }
        return from to to
    }

    /** The scale below a bar: full hours (every 2nd, every 4th in a long window) with their place (0…1). */
    fun scale(from: Long, to: Long, zone: ZoneId): List<Pair<Int, Float>> {
        if (to <= from) return emptyList()
        val first = Instant.ofEpochMilli(from).atZone(zone).let { z -> if (z.minute == 0 && z.second == 0) z else z.plusHours(1).withMinute(0).withSecond(0).withNano(0) }
        val hours = ((to - from) / HOUR_MS).toInt()
        val step = if (hours > 12) 4 else 2
        val out = ArrayList<Pair<Int, Float>>()
        var t = first
        while (t.toInstant().toEpochMilli() <= to) {
            val ms = t.toInstant().toEpochMilli()
            out += t.hour to ((ms - from).toFloat() / (to - from))
            t = t.plusHours(step.toLong())
        }
        return out
    }

    /** Where [start, end) sits in the window [from, to): its left edge and width (0…1), null when outside. */
    fun frame(start: Long, end: Long, from: Long, to: Long): Pair<Float, Float>? {
        if (to <= from || end <= start || end <= from || start >= to) return null
        val a = maxOf(from, start)
        val z = minOf(to, end)
        return (a - from).toFloat() / (to - from) to (z - a).toFloat() / (to - from)
    }

    /** Somebody who cannot at a time: busy or only tentative. */
    data class Clash(val key: String, val name: String, val status: String, val self: Boolean)

    /** Who of the known rows is taken in [start, end) — the busy ones first. */
    fun clashes(rows: List<MFreeBusyRow>, start: Long, end: Long): List<Clash> = rows.filter { it.known }.mapNotNull { r ->
        var st: String? = null
        r.blocks.forEach { b -> if (b.start < end && b.end > start) st = if (b.status == MBusyBlock.BUSY || st == BUSY) BUSY else TENTATIVE }
        st?.let { Clash(r.key, label(r), it, r.kind == MFreeBusyRow.SELF) }
    }.sortedBy { if (it.status == BUSY) 0 else 1 }

    /** The rows whose free/busy is unknown (grey). */
    fun unknown(rows: List<MFreeBusyRow>): List<MFreeBusyRow> = rows.filter { !it.known }

    /**
     * The short name of a row: a person's first name („Sabine K.“ → „Sabine“), a resource's name, else the address
     * before its `@`. The person asking („Du“) is the screen's.
     */
    fun label(r: MFreeBusyRow): String {
        val name = r.name.trim()
        return when {
            r.kind == MFreeBusyRow.RESOURCE && name.isNotEmpty() -> name
            (r.kind == MFreeBusyRow.PERSON || r.kind == MFreeBusyRow.MICROSOFT) && name.isNotEmpty() -> name.substringBefore(' ')
            name.isNotEmpty() && !name.contains('@') -> name.substringBefore(' ')
            else -> r.email?.substringBefore('@')?.takeIf { it.isNotEmpty() } ?: name
        }
    }

    /** Two letters for an avatar: of a name's first two words, else of the address. */
    fun initials(name: String): String {
        val words = name.trim().substringBefore('@').split(Regex("[\\s._-]+")).filter { it.isNotEmpty() && it[0].isLetterOrDigit() }
        return when {
            words.size >= 2 -> "${words[0][0]}${words[1][0]}".uppercase()
            words.size == 1 -> words[0].take(2).uppercase()
            else -> "?"
        }
    }

    /** A resource as an attendee (`resource-<id>@resources.gatecontrol.invalid` or the role). */
    fun isResource(email: String, role: String? = null): Boolean = role == MAttendee.ROLE_RESOURCE || RESOURCE_ADDRESS.matches(email.trim())

    /** The resource's id of its address, null for anybody else. */
    fun resourceId(email: String): Long? = RESOURCE_ADDRESS.matchEntire(email.trim())?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }

    /** The people of the attendees (addresses, without resources) and the resources' ids. */
    fun split(attendees: List<MAttendeeInput>): Pair<List<String>, List<Long>> {
        val people = attendees.filter { !isResource(it.email, it.role) }.map { it.email.trim().lowercase() }.filter { it.contains('@') }.distinct()
        val res = attendees.mapNotNull { resourceId(it.email) }.distinct()
        return people to res
    }

    /** „max. 1 Tag“ / „3 Tage“ / „2,5 Std.“: ("day", 1) | ("days", n) | ("hours", n). */
    fun maxLimit(minutes: Int): Pair<String, Double> = when {
        minutes % 1440 == 0 && minutes == 1440 -> "day" to 1.0
        minutes % 1440 == 0 -> "days" to (minutes / 1440).toDouble()
        else -> "hours" to ((minutes / 6.0).roundToInt() / 10.0)
    }

    /** The pill of a booking: waiting | conflict (a rule broken or a booking in the way) | confirmed | declined | cancelled. */
    fun pill(b: MBooking): String = when (b.state) {
        MBooking.PENDING -> if (b.violations.isNotEmpty() || b.conflicts.isNotEmpty()) PILL_CONFLICT else PILL_WAITING
        MBooking.CONFIRMED -> PILL_CONFIRMED
        MBooking.DECLINED -> PILL_DECLINED
        else -> PILL_CANCELLED
    }

    enum class Approve { NONE, APPROVE, OVERRIDE }

    /** What „Freigeben“ is: nothing with a booking in the way, „Trotzdem freigeben“ with broken rules. */
    fun approve(b: MBooking): Approve = when {
        !b.canDecide || b.state != MBooking.PENDING -> Approve.NONE
        b.conflicts.isNotEmpty() -> Approve.NONE
        b.violations.isNotEmpty() -> Approve.OVERRIDE
        else -> Approve.APPROVE
    }

    /** May the booker call it off („Absagen“): waiting or confirmed and not over. */
    fun canCancel(b: MBooking, now: Long): Boolean = (b.state == MBooking.PENDING || b.state == MBooking.CONFIRMED) && b.end > now

    /** A pending booking's bar: the resource's day and the time asked for. */
    fun bookingBlocks(b: MBooking): List<MBusyBlock> = b.day + MBusyBlock(b.start, b.end, ASK)

    /** The kinds of the chips: rooms, vehicles, devices always, „Sonstiges“ only when there is one. */
    fun kindChips(list: List<MResource>): List<String> =
        listOf(MResource.ROOM, MResource.VEHICLE, MResource.DEVICE) + (if (list.any { it.kind == MResource.OTHER }) listOf(MResource.OTHER) else emptyList())

    /** The length of an event in minutes for the suggestions (all-day: an hour, at least a quarter). */
    fun duration(start: Long, end: Long, allDay: Boolean): Int = if (allDay) 60 else ((end - start) / 60_000L).toInt().coerceIn(15, 1440)

    /** Whether [ms] is on [day] in [zone] (a suggestion that day shows „Do 15:00“, else with its date). */
    fun sameDay(ms: Long, day: LocalDate, zone: ZoneId): Boolean = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() == day
}
