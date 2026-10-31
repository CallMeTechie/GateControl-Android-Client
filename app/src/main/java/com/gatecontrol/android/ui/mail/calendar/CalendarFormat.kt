package com.gatecontrol.android.ui.mail.calendar

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MOccurrence
import com.gatecontrol.android.mail.MRule
import com.gatecontrol.android.mail.MailLogic
import com.gatecontrol.android.mail.MailPimLogic
import com.gatecontrol.android.ui.update.currentLocale
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** A calendar's color (`#RRGGBB`), the accent when it is none. */
internal fun calColor(hex: String?, fallback: Color = Color(0xFF46E0A0)): Color = MailLogic.color(hex)?.let { Color(it) } ?: fallback

@Composable
internal fun lang(): String = if (currentLocale().language == "en") "en" else "de"

internal fun clock(at: Long, zone: ZoneId, locale: Locale): String =
    Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))

/** "Di., 3. Nov." / "Tue, Nov 3". */
internal fun dayText(day: LocalDate, locale: Locale): String =
    day.format(DateTimeFormatter.ofPattern(if (locale.language == "de") "EEE, d. MMM" else "EEE, MMM d", locale))

/** "Dienstag, 3. November 2026". */
internal fun dayLong(day: LocalDate, locale: Locale): String = day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))

/** "November 2026" (the year left out in the current one). */
internal fun monthTitle(day: LocalDate, today: LocalDate, locale: Locale): String =
    day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "LLLL" else "LLLL yyyy", locale)).replaceFirstChar { it.titlecase(locale) }

/**
 * The time of an occurrence: "10:00–11:00", "ganztägig", over several days "3. Nov. 10:00 – 5. Nov. 12:00" or
 * "3.–5. Nov. · ganztägig".
 */
@Composable
internal fun whenText(start: Long, end: Long, allDay: Boolean, startDate: String?, endDate: String?, zone: ZoneId): String {
    val locale = currentLocale()
    val allDayText = stringResource(R.string.cal_all_day)
    if (allDay) {
        val a = MailPimLogic.date(startDate) ?: Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        val b = MailPimLogic.date(endDate) ?: a
        return if (a == b) allDayText else "${dayText(a, locale)} – ${dayText(b, locale)} · $allDayText"
    }
    val a = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
    val b = Instant.ofEpochMilli((end - 1).coerceAtLeast(start)).atZone(zone).toLocalDate()
    return if (a == b) "${clock(start, zone, locale)}–${clock(end, zone, locale)}" else "${dayText(a, locale)} ${clock(start, zone, locale)} – ${dayText(b, locale)} ${clock(end, zone, locale)}"
}

@Composable
internal fun occurrenceWhen(o: MOccurrence, zone: ZoneId): String = whenText(o.start, o.end, o.allDay, o.startDate, o.endDate, zone)

/** "15 Minuten vorher", "1 Tag vorher", "Zu Beginn"; all-day: "Am Vortag um 18:00". */
@Composable
internal fun reminderText(minutes: Int, allDay: Boolean = false): String {
    if (allDay) {
        // minutes before the midnight that starts the day: 360 → 18:00 the day before
        val (daysBefore, minuteOfDay) = MailPimLogic.allDayReminder(minutes)
        val time = "%d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
        return when (daysBefore) {
            0 -> stringResource(R.string.cal_reminder_same_day, time)
            1 -> stringResource(R.string.cal_reminder_day_before, time)
            else -> pluralStringResource(R.plurals.cal_reminder_days_before, daysBefore, daysBefore, time)
        }
    }
    val (n, unit) = MailPimLogic.reminderParts(minutes)
    return when (unit) {
        null -> stringResource(R.string.cal_reminder_at_start)
        "w" -> pluralStringResource(R.plurals.cal_reminder_weeks, n, n)
        "d" -> pluralStringResource(R.plurals.cal_reminder_days, n, n)
        "h" -> pluralStringResource(R.plurals.cal_reminder_hours, n, n)
        else -> pluralStringResource(R.plurals.cal_reminder_minutes, n, n)
    }
}

/** An attendee's answer in words. */
@Composable
internal fun responseText(response: String?): String = stringResource(
    when (response) {
        MOccurrence.RESPONSE_ACCEPTED -> R.string.cal_resp_accepted
        MOccurrence.RESPONSE_TENTATIVE -> R.string.cal_resp_tentative
        MOccurrence.RESPONSE_DECLINED -> R.string.cal_resp_declined
        MOccurrence.RESPONSE_ORGANIZER -> R.string.cal_resp_organizer
        else -> R.string.cal_resp_open
    },
)

/** The rule of a series in words, in the app's language. */
@Composable
internal fun ruleText(rule: MRule, start: LocalDate?): String = MailPimLogic.ruleText(rule, lang(), start)

/** The view's name ("Tag", "3 Tage" …). */
@Composable
internal fun viewName(view: String): String = stringResource(
    when (view) {
        "day" -> R.string.cal_view_day
        "3day" -> R.string.cal_view_3day
        "week" -> R.string.cal_view_week
        "month" -> R.string.cal_view_month
        else -> R.string.cal_view_list
    },
)
