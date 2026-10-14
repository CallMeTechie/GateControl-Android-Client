package com.gatecontrol.android.ui.familie

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.AgendaEntry
import com.gatecontrol.android.familie.EventDraft
import com.gatecontrol.android.familie.FamilieEvent
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.time.LocalDate

/**
 * Termine (no own mockup; Main/Portal style): agenda of the next 60 days
 * by day — Familie dates and birthdays, plus the Alltag plugin's
 * appointments in their calendar colour and waste collections when Alltag
 * is installed. Own dates and birthdays can be added and edited; member
 * birthdays come from the family settings and are read only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatesScreen(onBack: () -> Unit, viewModel: DatesViewModel = hiltViewModel()) {
    val agenda by viewModel.agenda.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val alltagMissing by viewModel.alltagMissing.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val extra = GateControlTheme.extraColors
    val locale = appLocale()
    var editing by remember { mutableStateOf<FamilieEvent?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val messageText = message?.let { familieErrorText(it) }

    LaunchedEffect(messageText) {
        if (messageText != null) {
            Toast.makeText(context, messageText, Toast.LENGTH_LONG).show()
            viewModel.clearMessage()
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        GcSubpageBar(title = stringResource(R.string.familie_dates_title), onBack = onBack, backDescription = stringResource(R.string.common_back)) {
            GcIconButton(GcIcons.Plus, contentDescription = stringResource(R.string.familie_date_new), onClick = { creating = true }, tint = MaterialTheme.colorScheme.primary)
        }
        PullToRefreshBox(isRefreshing = busy && agenda != null, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val list = agenda
                when {
                    list == null && error != null -> FamilieErrorBanner(error, onRetry = viewModel::refresh)
                    list == null -> FamilieLoading()
                    else -> {
                        if (error != null) FamilieErrorBanner(error)
                        if (alltagMissing) {
                            GcBanner(tone = GcTone.Warn, icon = GcIcons.Calendar) {
                                Text(stringResource(R.string.familie_dates_alltag_missing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        if (list.isEmpty()) FamilieEmpty(GcIcons.Calendar, stringResource(R.string.familie_dates_none))
                        list.groupBy { it.date }.forEach { (date, entries) ->
                            val d = FamilieFormat.parseDate(date)
                            GcSectionLabel(
                                (d?.let { FamilieFormat.day(it, locale) } ?: date) + " · " + relativeDay(date),
                                Modifier.padding(start = 4.dp, top = 6.dp),
                            )
                            GcCard(contentPadding = PaddingValues(vertical = 2.dp), verticalArrangement = Arrangement.Top) {
                                entries.forEachIndexed { i, e ->
                                    if (i > 0) GcRowDivider()
                                    AgendaRow(e, profile, onEdit = { editing = it })
                                }
                            }
                        }
                        Text(stringResource(R.string.familie_dates_range_hint), style = MaterialTheme.typography.bodySmall, color = extra.muted, modifier = Modifier.padding(4.dp))
                    }
                }
            }
        }
    }

    val e = editing
    if (creating || e != null) {
        EventEditDialog(
            profile = profile,
            event = e,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { draft ->
                viewModel.save(e?.id, draft)
                creating = false
                editing = null
            },
            onDelete = e?.let { ev ->
                {
                    viewModel.delete(ev.id)
                    editing = null
                }
            },
        )
    }
}

@Composable
private fun AgendaRow(entry: AgendaEntry, profile: FamilieProfile?, onEdit: (FamilieEvent) -> Unit) {
    val extra = GateControlTheme.extraColors
    when (entry) {
        is AgendaEntry.Own -> {
            val ev = entry.event
            val who = ev.memberIds.mapNotNull { profile?.nameOf(it) }.joinToString(", ")
            GcListRow(
                title = ev.title,
                description = listOfNotNull(
                    entry.time ?: stringResource(R.string.familie_all_day),
                    who.ifBlank { null },
                    ev.note.ifBlank { null },
                    if (ev.readOnly) stringResource(R.string.familie_dates_member_birthday) else null,
                ).joinToString(" · "),
                leading = { Icon(if (ev.isBirthday) GcIcons.Star else GcIcons.Calendar, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(20.dp)) },
                onClick = if (ev.readOnly) null else ({ onEdit(ev) }),
            )
        }
        is AgendaEntry.Alltag -> {
            val ev = entry.event
            val color = parseHexColor(ev.calendarColor) ?: extra.blue
            GcListRow(
                title = ev.title,
                description = listOfNotNull(
                    if (ev.allDay) stringResource(R.string.familie_all_day) else listOfNotNull(ev.time, ev.endTime).joinToString("–"),
                    ev.calendarName ?: stringResource(R.string.familie_dates_alltag),
                ).joinToString(" · "),
                leading = { Box(Modifier.width(6.dp).height(28.dp).clip(RoundedCornerShape(3.dp)).background(color)) },
            )
        }
        is AgendaEntry.Waste -> {
            val color = parseHexColor(entry.waste.color) ?: extra.warn
            GcListRow(
                title = entry.waste.kinds.joinToString(", "),
                description = stringResource(R.string.familie_dates_waste),
                leading = { Icon(GcIcons.Trash, contentDescription = null, tint = color, modifier = Modifier.size(20.dp)) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventEditDialog(
    profile: FamilieProfile?,
    event: FamilieEvent?,
    onDismiss: () -> Unit,
    onSave: (EventDraft) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var title by rememberSaveable { mutableStateOf(event?.title.orEmpty()) }
    var kind by rememberSaveable { mutableStateOf(event?.kind ?: FamilieEvent.KIND_EVENT) }
    var date by rememberSaveable { mutableStateOf(event?.date ?: LocalDate.now().toString()) }
    var endDate by rememberSaveable { mutableStateOf(event?.endDate) }
    var allDay by rememberSaveable { mutableStateOf(event?.allDay ?: true) }
    var time by rememberSaveable { mutableStateOf(event?.time) }
    var yearly by rememberSaveable { mutableStateOf(event?.repeat == "yearly") }
    var members by rememberSaveable { mutableStateOf(event?.memberIds ?: emptyList()) }
    var remind by rememberSaveable { mutableStateOf(event?.remindDays?.toString().orEmpty()) }
    var note by rememberSaveable { mutableStateOf(event?.note.orEmpty()) }
    val remindDays = remind.toIntOrNull()?.takeIf { it in 0..14 }

    FamilieFormDialog(
        title = stringResource(if (event == null) R.string.familie_date_new else R.string.familie_date_edit),
        onDismiss = onDismiss,
        saveEnabled = title.isNotBlank() && (remind.isBlank() || remindDays != null),
        onSave = {
            onSave(
                EventDraft(
                    title = title,
                    date = date,
                    time = time.takeIf { !allDay },
                    endDate = endDate?.takeIf { it >= date },
                    allDay = allDay || time == null,
                    repeat = if (yearly || kind == FamilieEvent.KIND_BIRTHDAY) "yearly" else "none",
                    memberIds = members,
                    kind = kind,
                    remindDays = remindDays,
                    note = note,
                ),
            )
        },
        onDelete = onDelete,
    ) {
        GcSegmented(
            options = listOf(
                FamilieEvent.KIND_EVENT to stringResource(R.string.familie_date_kind_event),
                FamilieEvent.KIND_BIRTHDAY to stringResource(R.string.familie_date_kind_birthday),
            ),
            selected = kind,
            onSelect = { kind = it },
        )
        GcTextField(value = title, onValueChange = { title = it.take(120) }, label = stringResource(R.string.familie_date_title_label))
        FamilieDateField(label = stringResource(R.string.familie_date_date), value = date, onValueChange = { v -> if (v != null) date = v })
        if (kind == FamilieEvent.KIND_EVENT) {
            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                GcSwitchRow(label = stringResource(R.string.familie_all_day), description = null, checked = allDay, onCheckedChange = { allDay = it })
                GcSwitchRow(label = stringResource(R.string.familie_date_yearly), description = null, checked = yearly, onCheckedChange = { yearly = it })
            }
            if (!allDay) FamilieTimeField(label = stringResource(R.string.familie_date_time), value = time, onValueChange = { time = it })
            FamilieDateField(label = stringResource(R.string.familie_date_end), value = endDate, onValueChange = { endDate = it }, clearable = true)
        }
        if (profile != null) {
            Text(stringResource(R.string.familie_date_members), style = MaterialTheme.typography.titleSmall, color = GateControlTheme.extraColors.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                profile.everyone.forEach { m ->
                    GcFilterChip(text = m.name, selected = m.userId in members, onClick = { members = if (m.userId in members) members - m.userId else members + m.userId })
                }
            }
        }
        GcTextField(
            value = remind,
            onValueChange = { remind = it.filter(Char::isDigit).take(2) },
            label = stringResource(R.string.familie_date_remind),
            hint = stringResource(R.string.familie_date_remind_hint),
            error = if (remind.isNotBlank() && remindDays == null) stringResource(R.string.familie_date_remind_invalid) else null,
        )
        GcTextField(value = note, onValueChange = { note = it.take(500) }, label = stringResource(R.string.familie_date_note), singleLine = false)
    }
}
