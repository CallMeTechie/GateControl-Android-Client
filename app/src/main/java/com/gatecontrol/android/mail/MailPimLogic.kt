package com.gatecontrol.android.mail

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * The rules of the calendar and the contacts (pure, unit-tested): which days a view shows, where an event sits in the
 * grid (overlapping ones side by side), all-day events, the rule of a series in words, time zones, reminders, the
 * letters of the contact list and what merging contacts gives.
 */
object MailPimLogic {

    const val DAY_MS = 24L * 60 * 60 * 1000

    /** At most this many days per `GET events` (the server's limit). */
    const val RANGE_DAYS = 62

    // ─── views and ranges ───────────────────────────────────────────

    /** The days a view shows around [anchor]: one, three, a (work) week from [weekStart] or the month's full weeks. */
    fun days(view: String, anchor: LocalDate, weekStart: Int, workWeek: Boolean = false): List<LocalDate> = when (view) {
        MCalPrefs.VIEW_DAY -> listOf(anchor)
        MCalPrefs.VIEW_3DAY -> (0L..2L).map { anchor.plusDays(it) }
        MCalPrefs.VIEW_WEEK -> {
            val first = weekStartOf(anchor, weekStart)
            val all = (0L..6L).map { first.plusDays(it) }
            if (workWeek) all.filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY } else all
        }
        MCalPrefs.VIEW_MONTH -> monthGrid(anchor, weekStart).flatten()
        else -> (0L until 30L).map { anchor.plusDays(it) }
    }

    /** The first day of the week [date] lies in ([weekStart] 1: Monday, 0: Sunday). */
    fun weekStartOf(date: LocalDate, weekStart: Int): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(if (weekStart == 0) DayOfWeek.SUNDAY else DayOfWeek.MONDAY))

    /** The weeks of [anchor]'s month, each seven days from [weekStart] (the days of the months around included). */
    fun monthGrid(anchor: LocalDate, weekStart: Int): List<List<LocalDate>> {
        val first = anchor.withDayOfMonth(1)
        val last = anchor.with(TemporalAdjusters.lastDayOfMonth())
        var day = weekStartOf(first, weekStart)
        val weeks = ArrayList<List<LocalDate>>()
        while (!day.isAfter(last)) {
            weeks += (0L..6L).map { day.plusDays(it) }
            day = day.plusWeeks(1)
        }
        return weeks
    }

    /** Where ‹ / › go from [anchor] in a view ([delta] -1 or +1). */
    fun step(view: String, anchor: LocalDate, delta: Int): LocalDate = when (view) {
        MCalPrefs.VIEW_DAY -> anchor.plusDays(delta.toLong())
        MCalPrefs.VIEW_3DAY -> anchor.plusDays(3L * delta)
        MCalPrefs.VIEW_WEEK -> anchor.plusWeeks(delta.toLong())
        MCalPrefs.VIEW_MONTH -> anchor.plusMonths(delta.toLong()).withDayOfMonth(1)
        else -> anchor.plusDays(30L * delta)
    }

    /** `[from, to)` in ms of the days [first] … [last] in [zone] (midnight to midnight). */
    fun range(first: LocalDate, last: LocalDate, zone: ZoneId): Pair<Long, Long> =
        first.atStartOfDay(zone).toInstant().toEpochMilli() to last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** The ranges to load for [from] … [to] (each at most [RANGE_DAYS] days). */
    fun chunks(first: LocalDate, last: LocalDate): List<Pair<LocalDate, LocalDate>> {
        val out = ArrayList<Pair<LocalDate, LocalDate>>()
        var a = first
        while (!a.isAfter(last)) {
            val b = minOf(last, a.plusDays(RANGE_DAYS - 1L))
            out += a to b
            a = b.plusDays(1)
        }
        return out
    }

    // ─── what lies on a day ─────────────────────────────────────────

    /** An entry of the grid: an occurrence or an overlay item, reduced to what placing needs. */
    data class Slot(
        val key: String,
        val title: String,
        val start: Long,
        val end: Long,
        val allDay: Boolean,
        val startDate: String?,
        val endDate: String?,
        /** `#RRGGBB` of its calendar or overlay. */
        val color: String,
        /** An overlay's entry (read only). */
        val overlay: Boolean = false,
        val open: Boolean = false,
        val cancelled: Boolean = false,
        val location: String? = null,
        val occurrence: MOccurrence? = null,
    )

    /** Whether an all-day [s] covers [day] (its dates inclusive; without dates: its start and end in [zone]). */
    fun coversAllDay(s: Slot, day: LocalDate, zone: ZoneId): Boolean {
        val first = s.startDate?.let(::date) ?: Instant.ofEpochMilli(s.start).atZone(zone).toLocalDate()
        val last = s.endDate?.let(::date) ?: Instant.ofEpochMilli((s.end - 1).coerceAtLeast(s.start)).atZone(zone).toLocalDate()
        return !day.isBefore(first) && !day.isAfter(last)
    }

    /** Whether a timed [s] lies (partly) on [day] in [zone]. */
    fun onDay(s: Slot, day: LocalDate, zone: ZoneId): Boolean {
        val (from, to) = range(day, day, zone)
        return s.start < to && (s.end > from || (s.end == s.start && s.start >= from))
    }

    /** The all-day entries of [day]. */
    fun allDayOn(slots: List<Slot>, day: LocalDate, zone: ZoneId): List<Slot> = slots.filter { it.allDay && coversAllDay(it, day, zone) }

    /** The timed entries of [day]. */
    fun timedOn(slots: List<Slot>, day: LocalDate, zone: ZoneId): List<Slot> = slots.filter { !it.allDay && onDay(it, day, zone) }

    /** An entry placed in a day's column: from [top] to [bottom] minutes after midnight, column [col] of [cols]. */
    data class Placed(val slot: Slot, val top: Int, val bottom: Int, val col: Int, val cols: Int)

    /** Shortest height of an entry in the grid, in minutes (a 5-minute event still shows its title). */
    const val MIN_MINUTES = 20

    /**
     * The timed entries of [day] in their places: clipped to the day (an event over midnight shows on both days),
     * overlapping ones side by side — each group of entries that overlap one another shares its width, an entry takes
     * the first column free at its start.
     */
    fun layoutDay(slots: List<Slot>, day: LocalDate, zone: ZoneId): List<Placed> {
        val (from, to) = range(day, day, zone)
        val dayMinutes = ((to - from) / 60_000L).toInt()
        val items = timedOn(slots, day, zone).map { s ->
            val top = ((maxOf(s.start, from) - from) / 60_000L).toInt().coerceIn(0, dayMinutes)
            val rawBottom = ((minOf(s.end, to) - from) / 60_000L).toInt().coerceIn(0, dayMinutes)
            val bottom = maxOf(rawBottom, minOf(top + MIN_MINUTES, dayMinutes))
            Triple(s, top, bottom)
        }.sortedWith(compareBy({ it.second }, { -it.third }, { it.first.key }))
        val out = ArrayList<Placed>()
        var group = ArrayList<Triple<Slot, Int, Int>>()
        var groupEnd = -1
        val colEnds = ArrayList<Int>()
        val cols = HashMap<String, Int>()
        fun flush() {
            val n = colEnds.size.coerceAtLeast(1)
            group.forEach { (s, t, b) -> out += Placed(s, t, b, cols.getValue(s.key), n) }
            group = ArrayList()
            groupEnd = -1
            colEnds.clear()
            cols.clear()
        }
        for (it in items) {
            val (s, top, bottom) = it
            if (group.isNotEmpty() && top >= groupEnd) flush()
            val free = colEnds.indexOfFirst { end -> end <= top }
            val col = if (free >= 0) free else colEnds.size
            if (col == colEnds.size) colEnds += bottom else colEnds[col] = bottom
            cols[s.key] = col
            group += it
            groupEnd = maxOf(groupEnd, bottom)
        }
        if (group.isNotEmpty()) flush()
        return out
    }

    /** The agenda: from [first] on, each day with entries (all-day first, then by start). */
    fun agenda(slots: List<Slot>, first: LocalDate, days: Int, zone: ZoneId): List<Pair<LocalDate, List<Slot>>> =
        (0 until days).mapNotNull { i ->
            val day = first.plusDays(i.toLong())
            val list = allDayOn(slots, day, zone).sortedBy { it.title.lowercase() } + timedOn(slots, day, zone).sortedWith(compareBy({ it.start }, { it.key }))
            if (list.isEmpty()) null else day to list
        }

    /**
     * The series' new times when "Alle Termine der Serie" moves the shown occurrence from ([occStart], [occEnd]) to
     * ([newStart], [newEnd]): the series' own times (Mail 1.3.0 `Event.series`) moved by as much.
     */
    fun shiftSeries(
        series: MSeries,
        occ: MOccurrence,
        allDay: Boolean,
        newStart: Long,
        newEnd: Long,
        newStartDate: String?,
        newEndDate: String?,
        zone: ZoneId,
    ): MShift {
        // days and minutes of wall clock (the portal's L.shiftSeries): a series that started in winter keeps its
        // hour when a summer occurrence moves
        fun day(at: Long) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val occDay = (if (occ.allDay) date(occ.startDate) else null) ?: day(occ.start)
        val seriesDay = (if (series.allDay) date(series.startDate) else null) ?: day(series.start)
        if (allDay) {
            val s = date(newStartDate) ?: occDay
            val e = date(newEndDate) ?: s
            val start = seriesDay.plusDays(ChronoUnit.DAYS.between(occDay, s))
            return MShift(startDate = start.toString(), endDate = start.plusDays(ChronoUnit.DAYS.between(s, e).coerceAtLeast(0)).toString())
        }
        val days = ChronoUnit.DAYS.between(occDay, day(newStart))
        val base = if (series.allDay) seriesDay.atStartOfDay(zone).toInstant().toEpochMilli() else series.start
        val minutes = minuteOfDay(newStart, zone) - (if (occ.allDay || series.allDay) 0 else minuteOfDay(occ.start, zone))
        val start = ms(local(base, zone).plusDays(days).plusMinutes(minutes.toLong()), zone)
        return MShift(start = start, end = start + (newEnd - newStart).coerceAtLeast(0))
    }

    /** A tap on free time in the grid: the start rounded down to the half hour, one hour long. */
    fun newAt(day: LocalDate, minute: Int, zone: ZoneId): Pair<Long, Long> {
        val m = (minute.coerceIn(0, 24 * 60 - 30) / 30) * 30
        val start = day.atStartOfDay(zone).plusMinutes(m.toLong()).toInstant().toEpochMilli()
        return start to start + 60 * 60_000L
    }

    /** A new event without a tap: the next full hour from [now], one hour long. */
    fun nextHour(now: Long, zone: ZoneId): Pair<Long, Long> {
        val t = Instant.ofEpochMilli(now).atZone(zone).truncatedTo(ChronoUnit.HOURS).plusHours(1)
        val start = t.toInstant().toEpochMilli()
        return start to start + 60 * 60_000L
    }

    /** The minutes after midnight of [now] in [zone] (the now line). */
    fun minuteOfDay(now: Long, zone: ZoneId): Int = Instant.ofEpochMilli(now).atZone(zone).let { it.hour * 60 + it.minute }

    // ─── all-day dates ──────────────────────────────────────────────

    fun date(s: String?): LocalDate? = s?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** The dates of an all-day event from the person's start and end day (end inclusive, never before the start). */
    fun allDayDates(first: LocalDate, last: LocalDate): Pair<String, String> = first.toString() to (if (last.isBefore(first)) first else last).toString()

    /** The dates an all-day occurrence covers (its own dates, else from its ms in [zone]). */
    fun datesOf(o: MOccurrence, zone: ZoneId): Pair<LocalDate, LocalDate> {
        val first = date(o.startDate) ?: Instant.ofEpochMilli(o.start).atZone(zone).toLocalDate()
        val last = date(o.endDate) ?: Instant.ofEpochMilli((o.end - 1).coerceAtLeast(o.start)).atZone(zone).toLocalDate()
        return first to (if (last.isBefore(first)) first else last)
    }

    // ─── time zones ─────────────────────────────────────────────────

    /** A zone the device knows, else null. */
    fun zone(id: String?): ZoneId? = id?.let { runCatching { ZoneId.of(it) }.getOrNull() }

    /**
     * Whether an event's zone is worth showing beside the time: it differs from the person's at the event's start
     * (another offset; the same rules under another name count as the same).
     */
    fun otherZone(eventZone: String?, personZone: ZoneId, at: Long): ZoneId? {
        val z = zone(eventZone) ?: return null
        val i = Instant.ofEpochMilli(at)
        return z.takeIf { it.rules.getOffset(i) != personZone.rules.getOffset(i) }
    }

    /** `GMT+1`, `GMT-5:30` of [zone] at [at]. */
    fun offsetLabel(zone: ZoneId, at: Long): String {
        val o: ZoneOffset = zone.rules.getOffset(Instant.ofEpochMilli(at))
        val total = o.totalSeconds / 60
        if (total == 0) return "GMT"
        val sign = if (total < 0) "-" else "+"
        val h = kotlin.math.abs(total) / 60
        val m = kotlin.math.abs(total) % 60
        return "GMT$sign$h" + if (m != 0) ":%02d".format(m) else ""
    }

    /** The wall time of [at] in [zone] (what the form shows and edits). */
    fun local(at: Long, zone: ZoneId): LocalDateTime = Instant.ofEpochMilli(at).atZone(zone).toLocalDateTime()

    /** The ms of a wall time in [zone] (a time in a summer-time gap moves forward, an overlap takes the earlier). */
    fun ms(t: LocalDateTime, zone: ZoneId): Long = t.atZone(zone).toInstant().toEpochMilli()

    fun ms(day: LocalDate, time: LocalTime, zone: ZoneId): Long = ms(LocalDateTime.of(day, time), zone)

    // ─── rules in words ─────────────────────────────────────────────

    private val RULE_PART = Regex("^[A-Z]+=[A-Z0-9,+\\-]+$")

    /** `FREQ=WEEKLY;BYDAY=TU,TH;UNTIL=20261231` → the rule the form knows (null for what it cannot read). */
    fun parseRule(rrule: String): MRule? {
        val parts = rrule.trim().removePrefix("RRULE:").split(';').filter { it.isNotBlank() }
        if (parts.isEmpty() || parts.any { !RULE_PART.matches(it.uppercase()) }) return null
        val map = parts.associate { it.substringBefore('=').uppercase() to it.substringAfter('=').uppercase() }
        val freq = map["FREQ"]?.lowercase()?.takeIf { it in MRule.FREQS } ?: return null
        var bySetPos = map["BYSETPOS"]?.toIntOrNull()
        val byDay = map["BYDAY"]?.split(',')?.mapNotNull { d ->
            val m = Regex("^([+-]?\\d{1,2})?(MO|TU|WE|TH|FR|SA|SU)$").matchEntire(d) ?: return@mapNotNull null
            m.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull()?.let { bySetPos = it }
            m.groupValues[2]
        }?.takeIf { it.isNotEmpty() }
        val until = map["UNTIL"]?.takeIf { it.length >= 8 }?.let { "${it.substring(0, 4)}-${it.substring(4, 6)}-${it.substring(6, 8)}" }?.takeIf { date(it) != null }
        return MRule(
            freq = freq,
            interval = map["INTERVAL"]?.toIntOrNull()?.coerceIn(1, 999) ?: 1,
            byDay = byDay,
            byMonthDay = map["BYMONTHDAY"]?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.isNotEmpty() },
            bySetPos = bySetPos,
            until = until,
            count = map["COUNT"]?.toIntOrNull()?.takeIf { it > 0 },
        )
    }

    private fun dayName(d: String, lang: String, short: Boolean = true): String {
        val dow = DayOfWeek.of(MRule.DAYS.indexOf(d) + 1)
        val locale = if (lang == "en") Locale.ENGLISH else Locale.GERMAN
        val s = dow.getDisplayName(if (short) TextStyle.SHORT else TextStyle.FULL, locale)
        return s.removeSuffix(".")
    }

    private fun ordinal(n: Int, lang: String): String = if (lang == "en") {
        when {
            n == -1 -> "last"
            n % 100 in 11..13 -> "${n}th"
            n % 10 == 1 -> "${n}st"
            n % 10 == 2 -> "${n}nd"
            n % 10 == 3 -> "${n}rd"
            else -> "${n}th"
        }
    } else if (n == -1) "letzten" else "$n."

    /**
     * A rule in words: "Wöchentlich am Di, Do", "Alle 2 Wochen am Mo", "Monatlich am 4.", "Monatlich am 2. Dienstag",
     * "Jährlich am 14. März", with ", bis 31.12.2026" or ", 10-mal". [start] fills what the rule leaves to the start
     * (the weekday of a weekly rule, the day of a monthly or yearly one).
     */
    fun ruleText(r: MRule, lang: String, start: LocalDate? = null): String {
        val en = lang == "en"
        val n = r.interval.coerceAtLeast(1)
        val base = when (r.freq) {
            MRule.DAILY -> if (en) (if (n > 1) "Every $n days" else "Daily") else (if (n > 1) "Alle $n Tage" else "Täglich")
            MRule.WEEKLY -> if (en) (if (n > 1) "Every $n weeks" else "Weekly") else (if (n > 1) "Alle $n Wochen" else "Wöchentlich")
            MRule.MONTHLY -> if (en) (if (n > 1) "Every $n months" else "Monthly") else (if (n > 1) "Alle $n Monate" else "Monatlich")
            else -> if (en) (if (n > 1) "Every $n years" else "Yearly") else (if (n > 1) "Alle $n Jahre" else "Jährlich")
        }
        val on = if (en) " on " else " am "
        val detail = when (r.freq) {
            MRule.WEEKLY -> {
                val days = r.byDay ?: start?.let { listOf(MRule.DAYS[it.dayOfWeek.value - 1]) }
                days?.let { d -> on + MRule.DAYS.filter { it in d }.joinToString(", ") { dayName(it, lang) } }.orEmpty()
            }
            MRule.MONTHLY -> when {
                r.byDay != null && r.byDay.size == 1 && r.bySetPos != null ->
                    on + (if (en) "the " else "") + ordinal(r.bySetPos, lang) + " " + dayName(r.byDay[0], lang, short = false)
                r.byMonthDay != null -> on + (if (en) "day " else "") + r.byMonthDay.joinToString(", ") { if (en) "$it" else "$it." }
                start != null -> on + (if (en) "day ${start.dayOfMonth}" else "${start.dayOfMonth}.")
                else -> ""
            }
            MRule.YEARLY -> start?.let {
                val month = it.month.getDisplayName(TextStyle.FULL, if (en) Locale.ENGLISH else Locale.GERMAN)
                on + if (en) "$month ${it.dayOfMonth}" else "${it.dayOfMonth}. $month"
            }.orEmpty()
            else -> ""
        }
        val end = when {
            r.until != null -> date(r.until)?.let { d -> if (en) ", until ${d.monthValue}/${d.dayOfMonth}/${d.year}" else ", bis ${d.dayOfMonth}.${d.monthValue}.${d.year}" }.orEmpty()
            r.count != null -> if (en) ", ${r.count} times" else ", ${r.count}-mal"
            else -> ""
        }
        return base + detail + end
    }

    /** The n-th weekday of [date] in its month (1 … 4, -1 when it is the last one) and its day (`MO` …). */
    fun nthWeekday(date: LocalDate): Pair<Int, String> {
        val day = MRule.DAYS[date.dayOfWeek.value - 1]
        val last = date.plusWeeks(1).month != date.month
        val n = (date.dayOfMonth - 1) / 7 + 1
        return (if (last && n >= 4) -1 else n) to day
    }

    /** The form's choices of a rule ([kind]) for an event starting [start]. */
    fun ruleFor(kind: String, start: LocalDate, interval: Int = 1, byDay: List<String>? = null, until: String? = null, count: Int? = null): MRule? = when (kind) {
        RULE_NONE -> null
        MRule.DAILY -> MRule(MRule.DAILY, interval, until = until, count = count)
        MRule.WEEKLY -> MRule(MRule.WEEKLY, interval, byDay = byDay?.takeIf { it.isNotEmpty() } ?: listOf(MRule.DAYS[start.dayOfWeek.value - 1]), until = until, count = count)
        RULE_MONTHLY_DAY -> MRule(MRule.MONTHLY, interval, byMonthDay = listOf(start.dayOfMonth), until = until, count = count)
        RULE_MONTHLY_WEEKDAY -> nthWeekday(start).let { (pos, d) -> MRule(MRule.MONTHLY, interval, byDay = listOf(d), bySetPos = pos, until = until, count = count) }
        MRule.YEARLY -> MRule(MRule.YEARLY, interval, until = until, count = count)
        else -> null
    }

    /** The form's choice for a rule (the reverse of [ruleFor]). */
    fun kindOf(r: MRule?): String = when {
        r == null -> RULE_NONE
        r.freq == MRule.MONTHLY && r.byDay != null && r.bySetPos != null -> RULE_MONTHLY_WEEKDAY
        r.freq == MRule.MONTHLY -> RULE_MONTHLY_DAY
        else -> r.freq
    }

    const val RULE_NONE = "none"
    const val RULE_MONTHLY_DAY = "monthly_day"
    const val RULE_MONTHLY_WEEKDAY = "monthly_weekday"
    val RULE_KINDS = listOf(RULE_NONE, MRule.DAILY, MRule.WEEKLY, RULE_MONTHLY_DAY, RULE_MONTHLY_WEEKDAY, MRule.YEARLY)

    // ─── reminders ──────────────────────────────────────────────────

    /** The reminder choices of the form, in minutes before. */
    val REMINDERS = listOf(0, 5, 10, 15, 30, 60, 120, 1440, 2880, 10080)

    /**
     * The choices for an all-day event, in minutes before the midnight that starts its day (the server's rule): 18:00
     * and 9:00 the day before, 9:00 two days and a week before.
     */
    val ALL_DAY_REMINDERS = listOf(360, 900, 2340, 9540)

    /** An all-day reminder as (days before, minute of that day): 360 → (1, 1080) = 18:00 the day before. */
    fun allDayReminder(minutes: Int): Pair<Int, Int> {
        val daysBefore = Math.floorDiv(minutes + 1439, 1440)
        return daysBefore to Math.floorMod(daysBefore * 1440 - minutes, 1440)
    }

    /** A reminder in minutes as value and unit: (0, null) at the start, (15, min), (2, h), (1, d), (1, w). */
    fun reminderParts(minutes: Int): Pair<Int, String?> = when {
        minutes <= 0 -> 0 to null
        minutes % 10080 == 0 -> minutes / 10080 to "w"
        minutes % 1440 == 0 -> minutes / 1440 to "d"
        minutes % 60 == 0 -> minutes / 60 to "h"
        else -> minutes to "min"
    }

    // ─── responses ──────────────────────────────────────────────────

    /** "3 von 5 zugesagt": the accepted and everyone but the organizer's resources. */
    fun acceptedOf(attendees: List<MAttendee>): Pair<Int, Int> {
        val people = attendees.filter { it.role != MAttendee.ROLE_RESOURCE }
        return people.count { it.response == MOccurrence.RESPONSE_ACCEPTED } to people.size
    }

    // ─── contacts ───────────────────────────────────────────────────

    /** The letter of a sort key: `A` … `Z` (Ä → A, Ö → O, Ü → U, ß → S), `#` for anything else. */
    fun letterOf(sortKey: String): String {
        val c = sortKey.trim().firstOrNull()?.uppercaseChar() ?: return "#"
        val folded = when (c) {
            'Ä', 'À', 'Á', 'Â', 'Å' -> 'A'
            'Ö', 'Ò', 'Ó', 'Ô' -> 'O'
            'Ü', 'Ù', 'Ú', 'Û' -> 'U'
            'ß' -> 'S'
            'É', 'È', 'Ê' -> 'E'
            else -> c
        }
        return if (folded in 'A'..'Z') folded.toString() else "#"
    }

    /** The list's sections: by [MCardSummary.letter] in the order of the list, `#` last. */
    fun sections(items: List<MCardSummary>): List<Pair<String, List<MCardSummary>>> =
        items.groupBy { it.letter.ifEmpty { "#" } }.entries
            .sortedWith(compareBy({ it.key == "#" }, { it.key }))
            .map { it.key to it.value }

    /** The list after a `since` sync: [changed] replace or join, [deleted] go, sorted by the server's key. */
    fun applyCards(old: List<MCardSummary>, changed: List<MCardSummary>, deleted: Set<Long>): List<MCardSummary> {
        val ids = changed.mapTo(HashSet()) { it.id }
        return (old.filter { it.id !in deleted && it.id !in ids } + changed.filter { it.id !in deleted })
            .sortedWith(compareBy({ it.sortKey }, { it.id }))
    }

    /** The contacts that match [q] offline: name, company, addresses, number (digits only). */
    fun search(items: List<MCardSummary>, q: String): List<MCardSummary> {
        val t = q.trim().lowercase()
        if (t.isEmpty()) return items
        val digits = t.filter { it.isDigit() }
        return items.filter { c ->
            c.name.lowercase().contains(t) || (c.company?.lowercase()?.contains(t) == true) || c.emails.any { it.lowercase().contains(t) } ||
                (digits.length >= 3 && c.phone?.filter { it.isDigit() }?.contains(digits) == true)
        }
    }

    /** The second line of a contact in the list: position · company, else the first address, else the number. */
    fun subline(c: MCardSummary): String =
        listOfNotNull(c.title, c.company).joinToString(" · ").ifEmpty { c.emails.firstOrNull() ?: c.phone.orEmpty() }

    /** The digits of a number for comparing (`+49` and a leading `0` the same, the last 9). */
    fun phoneKey(v: String): String {
        var d = v.filter { it.isDigit() || it == '+' }
        if (d.startsWith("+49")) d = "0" + d.removePrefix("+49")
        if (d.startsWith("0049")) d = "0" + d.removePrefix("0049")
        return d.filter { it.isDigit() }.takeLast(9)
    }

    /** A number for `tel:` (digits, a leading `+`). */
    fun telUri(v: String): String? {
        val t = v.trim()
        val plus = t.startsWith("+")
        val digits = t.filter { it.isDigit() }
        return if (digits.length < 3) null else "tel:" + (if (plus) "+" else "") + digits
    }

    /**
     * What merging [cards] gives, kept by [primary]: addresses, numbers and postal addresses of all without doubles,
     * the single fields from the contact picked for them (else the primary, else the first that has one), notes joined.
     */
    fun merged(cards: List<MCard>, primary: Long, pick: MMergePick = MMergePick()): MCard {
        val main = cards.firstOrNull { it.id == primary } ?: cards.first()
        val others = listOf(main) + cards.filter { it.id != main.id }
        fun <T> from(id: Long?, get: (MCard) -> T?): T? = (id?.let { p -> cards.firstOrNull { it.id == p } }?.let(get)) ?: others.firstNotNullOfOrNull(get)
        val emails = others.flatMap { it.emails }.distinctBy { it.value.trim().lowercase() }
        val phones = others.flatMap { it.phones }.distinctBy { phoneKey(it.value).ifEmpty { it.value } }
        val addresses = others.flatMap { it.addresses }.distinctBy { "${it.street.trim()}|${it.city.trim()}".lowercase() }
        val name = (pick.name?.let { p -> cards.firstOrNull { it.id == p } } ?: main).name
        return main.copy(
            name = name,
            emails = emails.mapIndexed { i, e -> e.copy(primary = i == 0) },
            phones = phones,
            addresses = addresses,
            company = from(pick.company) { it.company },
            title = from(pick.title) { it.title },
            department = from(null) { it.department },
            birthday = from(pick.birthday) { it.birthday },
            note = others.map { it.note.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n\n"),
            groups = others.flatMap { it.groups }.distinct(),
        )
    }

    /** The single fields where [cards] differ (the merge preview asks for these): name, company, title, birthday. */
    fun conflicts(cards: List<MCard>): List<String> = buildList {
        if (cards.map { it.label.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().size > 1) add("name")
        if (cards.mapNotNull { it.company?.trim()?.lowercase() }.distinct().size > 1) add("company")
        if (cards.mapNotNull { it.title?.trim()?.lowercase() }.distinct().size > 1) add("title")
        if (cards.mapNotNull { it.birthday }.distinct().size > 1) add("birthday")
    }

    /** Merging contacts of different address books links them (nothing is deleted at the providers). */
    fun links(cards: List<MCardSummary>): Boolean = cards.map { it.addressbookId }.distinct().size > 1

    /** A birthday as `YYYY-MM-DD` / `--MM-DD` → (month, day, year or null). */
    fun birthdayParts(b: String?): Triple<Int, Int, Int?>? {
        val m = Regex("^(\\d{4}|-)-(\\d{2})-(\\d{2})$").matchEntire(b ?: return null) ?: return null
        val month = m.groupValues[2].toInt()
        val day = m.groupValues[3].toInt()
        if (month !in 1..12 || day !in 1..31) return null
        return Triple(month, day, m.groupValues[1].toIntOrNull())
    }

    /** The birthday the form gives: day and month (`14.03.`), the year optional. Null when it is no date. */
    fun birthdayOf(dayMonth: String, year: String): String? {
        val m = Regex("^\\s*(\\d{1,2})\\.(\\d{1,2})\\.?\\s*$").matchEntire(dayMonth) ?: return null
        val d = m.groupValues[1].toInt()
        val mo = m.groupValues[2].toInt()
        val y = year.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (year.isNotBlank() && (y == null || y !in 1800..2100)) return null
        val check = runCatching { LocalDate.of(y ?: 2000, mo, d) }.getOrNull() ?: return null
        return if (y != null) check.toString() else "--%02d-%02d".format(mo, d)
    }

    /** The age the person turns at their next birthday on or after [today] (null without a year). */
    fun turns(b: String?, today: LocalDate): Int? {
        val (m, d, y) = birthdayParts(b) ?: return null
        y ?: return null
        val thisYear = runCatching { LocalDate.of(today.year, m, d) }.getOrNull() ?: LocalDate.of(today.year, m, 28)
        return (if (thisYear.isBefore(today)) today.year + 1 else today.year) - y
    }
}

/** What the offline copy of calendars and contacts keeps (pure, unit-tested). */
object MailPimCacheLogic {
    /** Contact photos kept (the used last). */
    const val MAX_PHOTOS = 500

    private const val BACK_MS = 30L * MailPimLogic.DAY_MS
    private const val AHEAD_MS = 90L * MailPimLogic.DAY_MS

    private val RANGE = Regex("^(-?\\d{1,15})-(-?\\d{1,15})\\.json$")

    /** `<from>-<to>.json` → its range. */
    fun rangeOf(name: String): Pair<Long, Long>? = RANGE.matchEntire(name)?.let { m ->
        val a = m.groupValues[1].toLongOrNull() ?: return null
        val b = m.groupValues[2].toLongOrNull() ?: return null
        (a to b).takeIf { b > a }
    }

    /** Whether a range of occurrences is kept: it touches −30 … +90 days around [now]. */
    fun keep(from: Long, to: Long, now: Long): Boolean = to > now - BACK_MS && from < now + AHEAD_MS

    /** Every contact's summary as the server writes it (`{ items, version }`). */
    fun cardsJson(items: List<MCardSummary>, version: Long): com.google.gson.JsonObject = com.google.gson.JsonObject().apply {
        add("items", com.google.gson.JsonArray().apply {
            items.forEach { c ->
                add(com.google.gson.JsonObject().apply {
                    addProperty("id", c.id)
                    addProperty("addressbookId", c.addressbookId)
                    addProperty("name", c.name)
                    addProperty("sortKey", c.sortKey)
                    addProperty("letter", c.letter)
                    c.company?.let { addProperty("company", it) }
                    c.title?.let { addProperty("title", it) }
                    add("emails", com.google.gson.JsonArray().apply { c.emails.forEach { add(it) } })
                    c.phone?.let { addProperty("phone", it) }
                    addProperty("photo", c.photo)
                    c.photoEtag?.let { addProperty("photoEtag", it) }
                    add("groups", com.google.gson.JsonArray().apply { c.groups.forEach { add(it) } })
                    add("linked", com.google.gson.JsonArray().apply { c.linked.forEach { add(it) } })
                    c.gcUserId?.let { addProperty("gcUserId", it) }
                    c.updatedAt?.let { addProperty("updatedAt", it) }
                    // Mail 1.4.0
                    c.favorite?.let { addProperty("favorite", it) }
                    if (c.conflicts > 0) addProperty("conflicts", c.conflicts)
                    c.masterId?.let { addProperty("masterId", it) }
                    if (c.sources.isNotEmpty()) {
                        add("sources", com.google.gson.JsonArray().apply {
                            c.sources.forEach { x ->
                                add(com.google.gson.JsonObject().apply {
                                    addProperty("addressbookId", x.addressbookId)
                                    addProperty("source", x.source)
                                    x.accountId?.let { addProperty("accountId", it) }
                                })
                            }
                        })
                    }
                })
            }
        })
        addProperty("version", version)
    }
}
