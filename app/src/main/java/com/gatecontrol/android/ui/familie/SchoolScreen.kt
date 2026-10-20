package com.gatecontrol.android.ui.familie

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.school.SchoolLogic
import com.gatecontrol.android.familie.school.SchoolStudent
import com.gatecontrol.android.familie.school.SchoolTimetable
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import kotlin.math.abs

private const val TAB_PLAN = "plan"
private const val TAB_CHANGES = "changes"

/**
 * Stundenplan (Schule plugin, WebUntis): a child chip per student (more
 * than one), a day strip Mo–Fr (Sa with lessons) for this week and next,
 * the day's exams ("Klassenarbeiten", on top), its lessons with their
 * state (an "Arbeit" badge on the lesson an exam is written in), the
 * homework due ("Hausaufgaben"), "Stand" of the last fetch, and
 * the tab "Änderungen" (the change log of the last 7 days by day). Opens
 * on [initialStudentId] / [initialDate] (card or push), else on the first
 * child and [SchoolLogic.defaultDay].
 */
@Composable
fun SchoolScreen(
    onBack: () -> Unit,
    initialStudentId: Int? = null,
    initialDate: String? = null,
    viewModel: SchoolViewModel = hiltViewModel(),
) {
    val timetable by viewModel.timetable.collectAsStateWithLifecycle()
    val studentId by viewModel.studentId.collectAsStateWithLifecycle()
    val date by viewModel.date.collectAsStateWithLifecycle()
    val windowStart by viewModel.windowStart.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val changes by viewModel.changes.collectAsStateWithLifecycle()
    val changesError by viewModel.changesError.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(TAB_PLAN) }
    LaunchedEffect(initialStudentId, initialDate) { viewModel.open(initialStudentId, initialDate) }

    val tt = timetable
    val student = SchoolLogic.pickStudent(tt, studentId)

    FamiliePageFrame(
        title = stringResource(R.string.school_title),
        onBack = onBack,
        refreshing = busy && tt != null,
        onRefresh = viewModel::refresh,
    ) {
        GcSegmented(
            options = listOf(TAB_PLAN to stringResource(R.string.school_tab_plan), TAB_CHANGES to stringResource(R.string.school_tab_changes)),
            selected = tab,
            onSelect = { tab = it },
        )
        when {
            tt == null && error != null -> SchoolError(error, onRetry = viewModel::refresh)
            tt == null -> FamilieLoading()
            tt.students.isEmpty() -> FamilieEmpty(GcIcons.School, stringResource(R.string.school_none))
            else -> {
                if (error == FamilieApi.CODE_OFFLINE) {
                    GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
                        Text(stringResource(R.string.school_offline_cached), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                } else if (error != null) {
                    SchoolError(error, onRetry = null)
                }
                if (tt.students.size > 1) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tt.students.forEach { s ->
                            GcFilterChip(text = s.name, selected = s.id == student?.id, onClick = { viewModel.selectStudent(s.id) })
                        }
                    }
                }
                if (student != null) {
                    if (tab == TAB_PLAN) {
                        Plan(tt, student, date, windowStart, viewModel::selectDate, viewModel::shiftWeek)
                    } else {
                        Changes(changes, changesError, showStudent = false, studentId = student.id, onRetry = viewModel::refresh)
                    }
                }
            }
        }
    }
}

@Composable
private fun SchoolError(code: String?, onRetry: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GcBanner(tone = if (code == FamilieApi.CODE_OFFLINE) GcTone.Warn else GcTone.Error, icon = GcIcons.Alert) {
            Text(schoolErrorText(code), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
        if (onRetry != null) {
            com.gatecontrol.android.ui.components.GcOutlineButton(text = stringResource(R.string.familie_retry), onClick = onRetry, icon = GcIcons.Refresh)
        }
    }
}

@Composable
private fun Plan(
    tt: SchoolTimetable,
    student: SchoolStudent,
    date: LocalDate,
    windowStart: LocalDate,
    onDate: (LocalDate) -> Unit,
    onWeek: (Int) -> Unit,
) {
    val extra = GateControlTheme.extraColors
    val locale = appLocale()
    val hasLessons = SchoolLogic.hasLessons(tt, student.id)
    val monday = SchoolLogic.monday(date)
    val days = SchoolLogic.weekDays(monday, hasLessons)
    val today = LocalDate.now()

    // Week: arrows between this week and next (the loaded range).
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GcIconButton(
            GcIcons.ChevronLeft,
            contentDescription = stringResource(R.string.familie_week_prev),
            onClick = { onWeek(-1) },
            size = 40.dp,
            enabled = monday > windowStart,
        )
        Text(
            when (monday) {
                SchoolLogic.monday(today) -> stringResource(R.string.school_week_this)
                SchoolLogic.monday(today).plusWeeks(1) -> stringResource(R.string.school_week_next)
                else -> stringResource(R.string.school_week_of, FamilieFormat.day(monday, locale))
            },
            style = MaterialTheme.typography.titleSmall,
            color = extra.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        GcIconButton(
            GcIcons.ChevronRight,
            contentDescription = stringResource(R.string.familie_week_next),
            onClick = { onWeek(1) },
            size = 40.dp,
            enabled = monday.plusWeeks(1) <= SchoolLogic.windowEnd(windowStart),
        )
    }

    // Day strip.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        days.forEach { d ->
            val sd = student.day(d.toString())
            val special = sd?.lessons?.any { it.isSpecial } == true
            val exam = sd?.exams?.isNotEmpty() == true
            DayChip(d, selected = d == date, isToday = d == today, special = special, exam = exam, onClick = { onDate(d) }, modifier = Modifier.weight(1f))
        }
    }

    // The day: swipe left / right for the next / previous school day.
    val day = student.day(date.toString())
    var drag by remember { mutableFloatStateOf(0f) }
    val swipe = Modifier.pointerInput(date, days) {
        detectHorizontalDragGestures(
            onDragStart = { drag = 0f },
            onDragEnd = {
                val i = days.indexOf(date)
                if (abs(drag) > 80.dp.toPx() && i >= 0) {
                    val next = if (drag < 0) days.getOrNull(i + 1) else days.getOrNull(i - 1)
                    if (next != null) onDate(next) else onWeek(if (drag < 0) 1 else -1)
                }
                drag = 0f
            },
            onHorizontalDrag = { _, amount -> drag += amount },
        )
    }
    Column(swipe.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GcSectionLabel(dayHeading(date.toString()), Modifier.padding(start = 4.dp, top = 4.dp))
        day?.holiday?.let { h ->
            GcBanner(tone = GcTone.Info, icon = GcIcons.Calendar) {
                Text(stringResource(R.string.school_holiday, h), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        val exams = day?.exams.orEmpty()
        if (exams.isNotEmpty()) SchoolExamsCard(exams)
        val lessons = day?.lessons.orEmpty()
        if (lessons.isEmpty()) {
            if (day?.holiday == null) FamilieEmpty(GcIcons.School, stringResource(R.string.school_no_lessons))
        } else {
            GcCard(contentPadding = PaddingValues(vertical = 2.dp), verticalArrangement = Arrangement.Top) {
                lessons.forEachIndexed { i, l ->
                    if (i > 0) GcRowDivider()
                    SchoolLessonRow(l, exam = SchoolLogic.examFor(l, exams))
                }
            }
        }
        day?.homework?.takeIf { it.isNotEmpty() }?.let { SchoolHomeworkCard(it) }
        val updated = student.updatedAt ?: tt.updatedAt
        Text(
            if (updated != null) stringResource(R.string.school_updated, updatedText(updated)) else stringResource(R.string.school_never_updated),
            style = MaterialTheme.typography.bodySmall,
            color = extra.muted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** "Di., 20. Okt. · heute"; days that are not coming up soon: the date alone. */
@Composable
private fun dayHeading(date: String): String {
    val d = FamilieFormat.parseDate(date) ?: return date
    val n = FamilieFormat.daysFrom(LocalDate.now(), d)
    val day = FamilieFormat.day(d, appLocale())
    return if (n in 0L..13L) day + " · " + relativeDay(date) else day
}

/** "14:05" today, else "Mo., 19. Okt. 14:05". */
@Composable
private fun updatedText(ms: Long): String {
    val zone = ZoneId.systemDefault()
    val instant = Instant.ofEpochMilli(ms)
    val time = FamilieFormat.time(instant, zone)
    val d = instant.atZone(zone).toLocalDate()
    return if (d == LocalDate.now(zone)) time else FamilieFormat.day(d, appLocale()) + " " + time
}

/**
 * A day of the strip: weekday and date; a dot when something changed, a
 * second one (accent colour) for an exam (both also said in words).
 */
@Composable
private fun DayChip(date: LocalDate, selected: Boolean, isToday: Boolean, special: Boolean, exam: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val extra = GateControlTheme.extraColors
    val locale = appLocale()
    val full = FamilieFormat.day(date, locale)
    val withChanges = if (special) stringResource(R.string.school_day_with_changes, full) else full
    val description = if (exam) stringResource(R.string.school_day_with_exams, withChanges) else withChanges
    Column(
        modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) extra.accentBg else MaterialTheme.colorScheme.surface)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else if (isToday) extra.accentText else extra.border, RoundedCornerShape(12.dp))
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .clearAndSetSemantics {
                contentDescription = description
                this.selected = selected
                role = Role.Tab
            }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).trimEnd('.'),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) extra.accentText else extra.muted,
        )
        Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            // Without either: an empty spot, so all chips keep their height.
            if (special || !exam) Box(Modifier.size(6.dp).clip(CircleShape).background(if (special) extra.warn else androidx.compose.ui.graphics.Color.Transparent))
            if (exam) Box(Modifier.size(6.dp).clip(CircleShape).background(extra.pro))
        }
    }
}

@Composable
private fun Changes(changes: List<com.gatecontrol.android.familie.school.SchoolChange>?, error: String?, showStudent: Boolean, studentId: Int?, onRetry: () -> Unit) {
    when {
        changes == null && error != null -> SchoolError(error, onRetry)
        changes == null -> FamilieLoading()
        else -> {
            if (error != null) SchoolError(error, onRetry = null)
            val groups = SchoolLogic.changesByDay(changes, studentId)
            if (groups.isEmpty()) FamilieEmpty(GcIcons.School, stringResource(R.string.school_changes_none))
            groups.forEach { (date, list) ->
                GcSectionLabel(dayHeading(date), Modifier.padding(start = 4.dp, top = 6.dp))
                GcCard(contentPadding = PaddingValues(vertical = 2.dp), verticalArrangement = Arrangement.Top) {
                    list.forEachIndexed { i, c ->
                        if (i > 0) GcRowDivider()
                        SchoolChangeRow(c, showStudent)
                    }
                }
            }
        }
    }
}
