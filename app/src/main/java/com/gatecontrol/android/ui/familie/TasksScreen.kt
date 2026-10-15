package com.gatecontrol.android.ui.familie

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.FamilieTask
import com.gatecontrol.android.familie.StarsBoard
import com.gatecontrol.android.familie.TaskDraft
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.time.LocalDate

private enum class TaskTab { MINE, ALL, KIDS }

/**
 * Aufgaben (mockup AppTasks): my tasks / all / children's, tick off,
 * "in 1 Stunde", stars of the week for children; parents create and edit
 * tasks (mode, assignees, repeat, time, stars, reminder).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(onBack: () -> Unit, viewModel: TasksViewModel = hiltViewModel()) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val payload by viewModel.payload.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val extra = GateControlTheme.extraColors
    var tab by rememberSaveable { mutableStateOf(TaskTab.MINE) }
    var editing by remember { mutableStateOf<FamilieTask?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val messageText = message?.let { familieErrorText(it) }

    LaunchedEffect(messageText) {
        if (messageText != null) {
            Toast.makeText(context, messageText, Toast.LENGTH_LONG).show()
            viewModel.clearMessage()
        }
    }

    val p = profile
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        GcSubpageBar(title = stringResource(R.string.familie_tasks_title), onBack = onBack, backDescription = stringResource(R.string.common_back)) {
            if (p?.me?.isParent == true) {
                GcIconButton(GcIcons.Plus, contentDescription = stringResource(R.string.familie_task_new), onClick = { creating = true }, tint = MaterialTheme.colorScheme.primary)
            }
        }
        PullToRefreshBox(isRefreshing = busy && payload != null, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val tabs = buildList {
                    add(TaskTab.MINE to stringResource(R.string.familie_tasks_mine))
                    add(TaskTab.ALL to stringResource(R.string.familie_tasks_all))
                    if (p?.me?.isParent == true && p.children.isNotEmpty()) add(TaskTab.KIDS to stringResource(R.string.familie_tasks_kids))
                }
                GcSegmented(options = tabs, selected = tab, onSelect = { tab = it })

                val data = payload
                when {
                    data == null && error != null -> FamilieErrorBanner(error, onRetry = viewModel::refresh)
                    data == null || p == null -> FamilieLoading()
                    else -> {
                        if (error != null) FamilieErrorBanner(error)
                        val stars = data.stars
                        if (p.modules.stars && stars != null) {
                            if (p.me.isChild) StarsCard(p, stars, listOf(p.me.userId))
                            else if (tab != TaskTab.MINE) StarsCard(p, stars, p.children.map { it.userId })
                        }
                        val shown = data.tasks.filter { t ->
                            when (tab) {
                                TaskTab.MINE -> p.me.userId in t.responsible() || (t.mode == FamilieTask.MODE_FIRST && p.me.userId in t.assignees)
                                TaskTab.ALL -> true
                                TaskTab.KIDS -> t.assignees.any { id -> p.children.any { it.userId == id } }
                            }
                        }
                        val today = LocalDate.now().toString()
                        val (now, later) = shown.partition { it.dueDate == null || it.dueDate <= today || it.doneToday }
                        if (shown.isEmpty()) FamilieEmpty(GcIcons.Tasks, stringResource(R.string.familie_tasks_none))
                        if (now.isNotEmpty()) {
                            GcSectionLabel(stringResource(R.string.familie_today_title), Modifier.padding(start = 4.dp))
                            TaskCard(now.sortedWith(compareBy({ it.doneToday }, { it.dueTime ?: "99" })), p, viewModel, onEdit = { editing = it })
                        }
                        if (later.isNotEmpty()) {
                            GcSectionLabel(stringResource(R.string.familie_tasks_later), Modifier.padding(start = 4.dp, top = 4.dp))
                            TaskCard(later.sortedWith(compareBy({ it.dueDate }, { it.dueTime ?: "99" })), p, viewModel, onEdit = { editing = it })
                        }
                        GcBanner(tone = GcTone.Info, icon = GcIcons.Bell) {
                            Text(stringResource(R.string.familie_tasks_hint), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                        }
                    }
                }
            }
        }
    }

    val editTask = editing
    if (p != null && (creating || editTask != null)) {
        TaskEditDialog(
            profile = p,
            task = editTask,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { draft ->
                viewModel.save(editTask?.id, draft)
                creating = false
                editing = null
            },
            onDelete = editTask?.let { t ->
                {
                    viewModel.delete(t.id)
                    editing = null
                }
            },
        )
    }
}

@Composable
private fun StarsCard(p: FamilieProfile, stars: StarsBoard, ids: List<Int>) {
    val extra = GateControlTheme.extraColors
    GcCard {
        Text(stringResource(R.string.familie_stars_title), style = MaterialTheme.typography.titleMedium)
        ids.forEach { id ->
            val week = stars.week[id] ?: 0
            val goal = stars.goal[id] ?: 0
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FamilieAvatar(memberName(p, id), p.member(id)?.color, size = 32.dp, face = p.faceOf(id))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.familie_stars_line, memberName(p, id), week, goal),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (goal > 0) {
                        LinearProgressIndicator(
                            progress = { (week.toFloat() / goal).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = extra.panel2,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskCard(tasks: List<FamilieTask>, p: FamilieProfile, viewModel: TasksViewModel, onEdit: (FamilieTask) -> Unit) {
    val extra = GateControlTheme.extraColors
    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
        tasks.forEachIndexed { i, t ->
            if (i > 0) GcRowDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                GcIconButton(
                    icon = if (t.doneToday) GcIcons.Check else GcIcons.Minus,
                    contentDescription = stringResource(if (t.doneToday) R.string.familie_task_done_cd else R.string.familie_task_mark_done_cd, t.title),
                    onClick = { if (!t.doneToday) viewModel.done(t.id) },
                    tint = if (t.doneToday) MaterialTheme.colorScheme.primary else extra.faint,
                    enabled = !t.doneToday,
                )
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(
                        t.title,
                        style = MaterialTheme.typography.titleSmall.copy(textDecoration = if (t.doneToday) TextDecoration.LineThrough else null),
                        color = if (t.doneToday) extra.muted else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(taskMeta(t, p) + repeatSuffix(t), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
                if (!t.doneToday && (p.me.userId in t.assignees)) {
                    GcIconButton(GcIcons.Clock, contentDescription = stringResource(R.string.familie_task_snooze_cd, t.title), onClick = { viewModel.snooze(t.id) }, iconSize = 18.dp)
                }
                if (p.me.isParent) {
                    GcIconButton(GcIcons.Edit, contentDescription = stringResource(R.string.familie_task_edit_cd, t.title), onClick = { onEdit(t) }, iconSize = 18.dp)
                }
            }
        }
    }
}

@Composable
private fun repeatSuffix(t: FamilieTask): String = when (t.repeat) {
    "daily" -> " · " + stringResource(R.string.familie_repeat_daily)
    "weekly" -> " · " + stringResource(R.string.familie_repeat_weekly)
    "monthly" -> " · " + stringResource(R.string.familie_repeat_monthly)
    else -> t.dueDate?.let { " · " + relativeDay(it) }.orEmpty()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskEditDialog(
    profile: FamilieProfile,
    task: FamilieTask?,
    onDismiss: () -> Unit,
    onSave: (TaskDraft) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var title by rememberSaveable { mutableStateOf(task?.title.orEmpty()) }
    var mode by rememberSaveable { mutableStateOf(task?.mode ?: FamilieTask.MODE_FIXED) }
    var assignees by rememberSaveable { mutableStateOf(task?.assignees ?: emptyList()) }
    var repeat by rememberSaveable { mutableStateOf(task?.repeat ?: "none") }
    var weekdays by rememberSaveable { mutableStateOf(task?.weekdays ?: emptyList()) }
    var dueDate by rememberSaveable { mutableStateOf(task?.dueDate ?: LocalDate.now().toString()) }
    var dueTime by rememberSaveable { mutableStateOf(task?.dueTime) }
    var stars by rememberSaveable { mutableIntStateOf(task?.stars ?: 0) }
    var reminder by rememberSaveable { mutableStateOf(task?.reminder ?: true) }

    FamilieFormDialog(
        title = stringResource(if (task == null) R.string.familie_task_new else R.string.familie_task_edit),
        onDismiss = onDismiss,
        saveEnabled = title.isNotBlank() && assignees.isNotEmpty() && (repeat != "weekly" || weekdays.isNotEmpty()),
        onSave = {
            onSave(
                TaskDraft(
                    title = title,
                    mode = mode,
                    assignees = assignees,
                    repeat = repeat,
                    weekdays = if (repeat == "weekly") weekdays.sorted() else emptyList(),
                    dueTime = dueTime,
                    dueDate = dueDate,
                    stars = stars,
                    reminder = reminder,
                ),
            )
        },
        onDelete = onDelete,
    ) {
        GcTextField(value = title, onValueChange = { title = it.take(120) }, label = stringResource(R.string.familie_task_title_label))

        Text(stringResource(R.string.familie_task_assignees), style = MaterialTheme.typography.titleSmall, color = GateControlTheme.extraColors.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            profile.everyone.forEach { m ->
                GcFilterChip(
                    text = m.name,
                    selected = m.userId in assignees,
                    onClick = { assignees = if (m.userId in assignees) assignees - m.userId else assignees + m.userId },
                )
            }
        }

        Text(stringResource(R.string.familie_task_mode), style = MaterialTheme.typography.titleSmall, color = GateControlTheme.extraColors.muted)
        GcSegmented(
            options = listOf(
                FamilieTask.MODE_FIXED to stringResource(R.string.familie_task_mode_fixed),
                FamilieTask.MODE_ROTATE to stringResource(R.string.familie_task_mode_rotate),
                FamilieTask.MODE_FIRST to stringResource(R.string.familie_task_mode_first),
            ),
            selected = mode,
            onSelect = { mode = it },
        )

        Text(stringResource(R.string.familie_task_repeat), style = MaterialTheme.typography.titleSmall, color = GateControlTheme.extraColors.muted)
        GcSegmented(
            options = listOf(
                "none" to stringResource(R.string.familie_repeat_none),
                "daily" to stringResource(R.string.familie_repeat_daily),
                "weekly" to stringResource(R.string.familie_repeat_weekly),
                "monthly" to stringResource(R.string.familie_repeat_monthly),
            ),
            selected = repeat,
            onSelect = { repeat = it },
        )
        if (repeat == "weekly") {
            val names = weekdayNames()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..7).forEach { d ->
                    GcFilterChip(text = names[d - 1], selected = d in weekdays, onClick = { weekdays = if (d in weekdays) weekdays - d else weekdays + d })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FamilieDateField(
                label = stringResource(if (repeat == "none") R.string.familie_task_due_date else R.string.familie_task_first_date),
                value = dueDate,
                onValueChange = { v -> if (v != null) dueDate = v },
                modifier = Modifier.weight(1f),
            )
            FamilieTimeField(label = stringResource(R.string.familie_task_due_time), value = dueTime, onValueChange = { dueTime = it }, modifier = Modifier.weight(1f))
        }
        if (profile.modules.stars) {
            Text(stringResource(R.string.familie_task_stars, stars), style = MaterialTheme.typography.titleSmall, color = GateControlTheme.extraColors.muted)
            Slider(value = stars.toFloat(), onValueChange = { stars = it.toInt() }, valueRange = 0f..10f, steps = 9)
        }
        GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
            GcSwitchRow(
                label = stringResource(R.string.familie_task_reminder),
                description = stringResource(R.string.familie_task_reminder_desc),
                checked = reminder,
                onCheckedChange = { reminder = it },
                icon = GcIcons.Bell,
            )
        }
    }
}

/** Mo … So in the app language (1 = Monday). */
@Composable
fun weekdayNames(): List<String> {
    val locale = appLocale()
    return java.time.DayOfWeek.entries.map { it.getDisplayName(java.time.format.TextStyle.SHORT, locale) }
}
