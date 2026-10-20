package com.gatecontrol.android.ui.familie

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieDest
import com.gatecontrol.android.familie.school.ChangeKind
import com.gatecontrol.android.familie.school.LessonState
import com.gatecontrol.android.familie.school.SchoolChange
import com.gatecontrol.android.familie.school.SchoolDaySummary
import com.gatecontrol.android.familie.school.SchoolLesson
import com.gatecontrol.android.familie.school.SchoolLogic
import com.gatecontrol.android.familie.school.SchoolSnapshot
import com.gatecontrol.android.familie.school.SchoolSummaryTexts
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.time.LocalDate
import java.time.LocalDateTime

/** Readable text for an error of the Schule plugin's API (403, plugin gone or unlicensed, …). */
@StringRes
fun schoolErrorRes(code: String?): Int? = when (code) {
    FamilieApi.CODE_OFFLINE -> R.string.familie_error_offline
    "FORBIDDEN", "forbidden", "no_user", "http_403" -> R.string.school_error_forbidden
    "not_found", "NOT_FOUND", "http_404", "license_required", "http_402" -> R.string.school_error_missing
    "INVALID", "invalid", "http_400" -> R.string.school_error_invalid
    "INTERNAL", "http_500", "http_502", "http_503", "http_504" -> R.string.school_error_plugin
    else -> null
}

@Composable
fun schoolErrorText(code: String?): String = schoolErrorRes(code)?.let { stringResource(it) } ?: familieErrorText(code)

/** "Planmäßig", "2 Ausfälle · 1 Vertretung", "Frei", the holidays — [SchoolLogic.summaryText] with the app's strings. */
@Composable
fun schoolSummaryText(summary: SchoolDaySummary): String {
    val planned = stringResource(R.string.school_planned)
    val free = stringResource(R.string.school_free)
    val unknown = stringResource(R.string.school_unknown)
    val cancelled = pluralStringResource(R.plurals.school_cancelled_count, summary.cancelled, summary.cancelled)
    val changed = pluralStringResource(R.plurals.school_changed_count, summary.changed, summary.changed)
    val extra = pluralStringResource(R.plurals.school_extra_count, summary.extra, summary.extra)
    return SchoolLogic.summaryText(
        summary,
        object : SchoolSummaryTexts {
            override fun planned() = planned
            override fun free() = free
            override fun unknown() = unknown
            override fun cancelled(n: Int) = cancelled
            override fun changed(n: Int) = changed
            override fun extra(n: Int) = extra
        },
    )
}

/** "3. Std." / "3.–4. Std."; null without a period. */
@Composable
fun schoolPeriod(period: Int?, periodEnd: Int?): String? = when {
    period == null -> null
    periodEnd != null && periodEnd > period -> stringResource(R.string.school_periods, period, periodEnd)
    else -> stringResource(R.string.school_period, period)
}

/** "Entfällt" / "Vertretung" / "Zusatz"; null for a normal lesson. */
@Composable
fun schoolStateLabel(state: LessonState): String? = when (state) {
    LessonState.CANCELLED -> stringResource(R.string.school_state_cancelled)
    LessonState.CHANGED -> stringResource(R.string.school_state_changed)
    LessonState.EXTRA -> stringResource(R.string.school_state_extra)
    LessonState.NORMAL -> null
}

@Composable
@ReadOnlyComposable
fun schoolStateColor(state: LessonState): Color {
    val extra = GateControlTheme.extraColors
    return when (state) {
        LessonState.CANCELLED -> MaterialTheme.colorScheme.error
        LessonState.CHANGED -> extra.warn
        LessonState.EXTRA -> extra.blue
        LessonState.NORMAL -> MaterialTheme.colorScheme.onSurface
    }
}

fun schoolStateTone(state: LessonState): GcTone = when (state) {
    LessonState.CANCELLED -> GcTone.Error
    LessonState.CHANGED -> GcTone.Warn
    LessonState.EXTRA -> GcTone.Info
    LessonState.NORMAL -> GcTone.Neutral
}

/** "Heute", "Morgen", else "Mo., 26. Okt.". */
@Composable
fun schoolDayLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (FamilieFormat.daysFrom(today, date)) {
    0L -> stringResource(R.string.school_day_today)
    1L -> stringResource(R.string.school_day_tomorrow)
    else -> FamilieFormat.day(date, appLocale())
}

/** "Lehrkraft SCH statt MUE" style for screen readers ([old] null: only the new value). */
@Composable
private fun instead(new: String?, old: String?): String? = when {
    new != null && old != null && old != new -> stringResource(R.string.school_desc_instead, new, old)
    else -> new ?: old
}

/**
 * Everything a screen reader says about a lesson: period, time, subject,
 * teacher and room (with what they replace), the state and the texts.
 */
@Composable
fun schoolLessonDescription(l: SchoolLesson): String = listOfNotNull(
    schoolPeriod(l.period, l.periodEnd),
    stringResource(R.string.school_desc_time, l.start, l.end),
    instead(l.title, l.subjectOld),
    instead(l.teacher, l.teacherOld)?.let { stringResource(R.string.school_desc_teacher, it) },
    instead(l.room, l.roomOld)?.let { stringResource(R.string.school_desc_room, it) },
    schoolStateLabel(l.state),
    l.substText, l.info, l.text,
).joinToString(", ")

/**
 * One lesson of the timetable: time and period on the left; subject,
 * teacher and room; cancelled = struck through with a red "Entfällt",
 * changed = orange with the old teacher / room / subject struck through
 * beside the new one, extra = "Zusatz"; info texts as a second line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SchoolLessonRow(l: SchoolLesson, modifier: Modifier = Modifier) {
    val extra = GateControlTheme.extraColors
    val color = schoolStateColor(l.state)
    val cancelled = l.state == LessonState.CANCELLED
    val description = schoolLessonDescription(l)
    Row(
        modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.width(72.dp)) {
            Text(l.start, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(l.end, style = MaterialTheme.typography.bodySmall, color = extra.muted)
            schoolPeriod(l.period, l.periodEnd)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = extra.faint) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    l.title.ifBlank { "–" },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = if (l.state == LessonState.NORMAL) MaterialTheme.colorScheme.onSurface else color,
                    textDecoration = if (cancelled) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                schoolStateLabel(l.state)?.let { GcChip(it, tone = schoolStateTone(l.state), small = true) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OldNew(old = l.subjectOld?.takeIf { it != l.subject && it != l.subjectLong }, new = null, color = color, struck = cancelled)
                OldNew(old = l.teacherOld?.takeIf { it != l.teacher }, new = l.teacher, color = color, struck = cancelled)
                OldNew(old = l.roomOld?.takeIf { it != l.room }, new = l.room, color = color, struck = cancelled)
            }
            val texts = listOfNotNull(l.substText, l.info, l.text).distinct()
            if (texts.isNotEmpty()) {
                Text(texts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = extra.muted)
            }
        }
    }
}

/** The old value struck through, then the new one (in the state's colour when it changed). */
@Composable
private fun OldNew(old: String?, new: String?, color: Color, struck: Boolean) {
    val extra = GateControlTheme.extraColors
    if (old == null && new == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (old != null) {
            Text(old, style = MaterialTheme.typography.bodySmall, color = extra.faint, textDecoration = TextDecoration.LineThrough)
        }
        if (new != null) {
            Text(
                new,
                style = MaterialTheme.typography.bodySmall,
                color = if (old != null) color else extra.muted,
                textDecoration = if (struck) TextDecoration.LineThrough else null,
            )
        }
    }
}

/** One entry of the change log: what happened to which lesson, when it was noticed. */
@Composable
fun SchoolChangeRow(c: SchoolChange, showStudent: Boolean) {
    val extra = GateControlTheme.extraColors
    val subject = c.title.ifBlank { "–" }
    val what = when (c.kind) {
        ChangeKind.CANCELLED -> stringResource(R.string.school_change_cancelled, subject)
        ChangeKind.CHANGED -> stringResource(R.string.school_change_changed, subject)
        ChangeKind.EXTRA -> stringResource(R.string.school_change_extra, subject)
        ChangeKind.RESTORED -> stringResource(R.string.school_change_restored, subject)
    }
    val state = when (c.kind) {
        ChangeKind.CANCELLED -> LessonState.CANCELLED
        ChangeKind.EXTRA -> LessonState.EXTRA
        ChangeKind.RESTORED -> LessonState.NORMAL
        ChangeKind.CHANGED -> LessonState.CHANGED
    }
    val detected = c.detectedAt?.let { relativeStamp(java.time.Instant.ofEpochMilli(it).toString()) }
    GcListRow(
        title = if (showStudent && c.student != null) stringResource(R.string.school_card_child, c.student, what) else what,
        titleColor = if (state == LessonState.NORMAL) extra.accentText else schoolStateColor(state),
        description = listOfNotNull(
            schoolPeriod(c.period, null),
            listOfNotNull(c.start, c.end).joinToString("–").ifBlank { null },
            instead(c.teacher, c.teacherOld)?.let { stringResource(R.string.school_desc_teacher, it) },
            instead(c.room, c.roomOld)?.let { stringResource(R.string.school_desc_room, it) },
            c.substText, c.info,
            detected?.let { stringResource(R.string.school_change_detected, it) },
            if (c.pushed) stringResource(R.string.school_change_pushed) else null,
        ).joinToString(" · "),
        leading = {
            Icon(
                if (c.kind == ChangeKind.CANCELLED) GcIcons.Close else if (c.kind == ChangeKind.RESTORED) GcIcons.Check else GcIcons.School,
                contentDescription = null,
                tint = if (state == LessonState.NORMAL) extra.accentText else schoolStateColor(state),
                modifier = Modifier.size(20.dp),
            )
        },
    )
}

/**
 * Familie card "Stundenplan": for the day [SchoolLogic.defaultDay] picks,
 * per child the summary ("Planmäßig", "2 Ausfälle", "Frei", the holidays)
 * and the next one or two changed lessons. A tap opens the timetable.
 */
@Composable
fun SchoolCard(snapshot: SchoolSnapshot?, error: String?, selected: Boolean, onOpen: (FamilieDest) -> Unit) {
    val extra = GateControlTheme.extraColors
    FamilieSectionCard(
        title = stringResource(R.string.school_title),
        icon = GcIcons.School,
        link = stringResource(R.string.familie_open),
        onLink = { onOpen(FamilieDest.School()) },
        selected = selected,
    ) {
        val tt = snapshot?.timetable
        val muted: @Composable (String) -> Unit = { text ->
            Text(text, style = MaterialTheme.typography.bodyMedium, color = extra.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        when {
            tt == null -> muted(if (error != null) schoolErrorText(error) else stringResource(R.string.familie_loading_short))
            tt.students.isEmpty() -> muted(stringResource(R.string.school_none))
            else -> {
                val now = LocalDateTime.now()
                val day = SchoolLogic.defaultDay(now, SchoolLogic.hasLessons(tt))
                Text(
                    schoolDayLabel(day, now.toLocalDate()),
                    style = MaterialTheme.typography.labelLarge,
                    color = extra.muted,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                tt.students.forEach { s ->
                    val summary = SchoolLogic.summary(s, day, now)
                    GcListRow(
                        title = s.name,
                        description = schoolSummaryText(summary),
                        onClick = { onOpen(FamilieDest.School(s.id, day.toString())) },
                        leading = { FamilieAvatar(s.name, null, size = 32.dp) },
                        trailing = {
                            val tone = when {
                                summary.cancelled > 0 -> GcTone.Error
                                summary.status == SchoolDaySummary.Status.CHANGES -> GcTone.Warn
                                else -> null
                            }
                            if (tone != null) Icon(GcIcons.Alert, contentDescription = null, tint = if (tone == GcTone.Error) MaterialTheme.colorScheme.error else extra.warn, modifier = Modifier.size(18.dp))
                        },
                    )
                    summary.next.forEach { l ->
                        val line = listOfNotNull(schoolPeriod(l.period, l.periodEnd), l.start, l.title.ifBlank { null }).joinToString(" · ") +
                            (schoolStateLabel(l.state)?.let { " – $it" } ?: "") +
                            (instead(l.teacher, l.teacherOld)?.takeIf { l.state == LessonState.CHANGED }?.let { " ($it)" } ?: "")
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = schoolStateColor(l.state),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 64.dp, end = 16.dp),
                        )
                    }
                }
            }
        }
    }
}
