package com.gatecontrol.android.ui.mail.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MCalendar
import com.gatecontrol.android.mail.MRule
import com.gatecontrol.android.mail.MailPimLogic
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.mail.ErrorBox
import com.gatecontrol.android.ui.mail.mailContext
import com.gatecontrol.android.ui.mail.mailText
import com.gatecontrol.android.ui.mail.toast
import com.gatecontrol.android.ui.mail.contacts.SuggestionRow
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.update.currentLocale
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * A new event or a change (contract D3 "Termin bearbeiten"): title, calendar (writable ones, new only), all-day,
 * start and end, the zone (folded), the rule, place, online meeting (Teams in Microsoft 365, else a link), reminders
 * (up to the calendar's number), attendees with suggestions from the contacts and groups, free / busy, private and the
 * description. A series' occurrence asks "Nur dieser Termin" / "Alle Termine der Serie" before saving; a conflict
 * offers "Neu laden" and "Meine Änderung erneut anwenden".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MailEventEditScreen(onClose: () -> Unit, viewModel: MailEventEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cals by viewModel.calendars.collectAsStateWithLifecycle()
    val me by viewModel.me.collectAsStateWithLifecycle()
    val context = mailContext()
    val extra = GateControlTheme.extraColors
    val locale = currentLocale()
    val f = state.form
    LaunchedEffect(state.done) { if (state.done) onClose() }
    LaunchedEffect(state.message) {
        state.message?.let {
            toast(context, if (it == MailEventEditViewModel.SERIES_SHIFT) context.getString(R.string.cal_series_shift) else mailText(context, it))
            viewModel.clearMessage()
        }
    }
    // a new event without a calendar yet (the list came later): the default one
    LaunchedEffect(cals) {
        if (viewModel.isNew && f.calendarId == null) cals?.calendars?.firstOrNull { it.writable && it.isDefault }?.let { c -> viewModel.edit { it.copy(calendarId = c.id) } }
    }
    val cal = cals?.calendar(f.calendarId)
    Column(Modifier.fillMaxSize().imePadding()) {
        GcSubpageBar(
            title = stringResource(if (viewModel.isNew) R.string.cal_new_event else R.string.cal_edit),
            onBack = onClose,
            backDescription = stringResource(R.string.mail_close),
        ) {
            TextButton(onClick = viewModel::save, enabled = !state.saving && viewModel.problem() == null && !state.loading) {
                if (state.saving) CircularProgressIndicator(Modifier.size(18.dp)) else Text(stringResource(R.string.mail_save), fontWeight = FontWeight.Bold)
            }
        }
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            return@Column
        }
        if (state.error != null && state.existing == null && !viewModel.isNew) {
            ErrorBox(mailText(context, state.error), onRetry = null)
            return@Column
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GcTextField(f.title, { v -> viewModel.edit { it.copy(title = v.take(255)) } }, stringResource(R.string.cal_title))
            if (viewModel.isNew) {
                CalendarPicker(cals?.calendars.orEmpty().filter { it.writable }, cal) { c -> viewModel.edit { it.copy(calendarId = c.id) } }
            }
            GcCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GcSwitchRow(label = stringResource(R.string.cal_all_day), description = null, checked = f.allDay, onCheckedChange = { v -> viewModel.edit { it.copy(allDay = v) } })
                DateTimeRow(stringResource(R.string.cal_start), f.startDay, f.startTime.takeIf { !f.allDay }, locale) { d, t -> viewModel.setStart(d, t ?: f.startTime) }
                DateTimeRow(stringResource(R.string.cal_end), f.endDay, f.endTime.takeIf { !f.allDay }, locale) { d, t -> viewModel.edit { it.copy(endDay = d, endTime = t ?: it.endTime) } }
                if (viewModel.problem() == "end") Text(stringResource(R.string.cal_end_before), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (!f.allDay) ZoneRow(f.zone, viewModel.deviceZone.id) { z -> viewModel.edit { it.copy(zone = z) } }
            }
            RuleBlock(viewModel, f, locked = state.ruleLocked && state.existing?.occ?.occurrence != null, locale = locale)
            GcTextField(f.location, { v -> viewModel.edit { it.copy(location = v.take(500)) } }, stringResource(R.string.cal_location))
            OnlineBlock(cal, f.teams, f.onlineUrl, onTeams = { v -> viewModel.editOnline { it.copy(teams = v) } }, onUrl = { v -> viewModel.editOnline { it.copy(onlineUrl = v.take(2000)) } })
            RemindersBlock(f.reminders, f.allDay, max = cal?.maxReminders ?: 3) { list -> viewModel.edit { it.copy(reminders = list) } }
            GcSectionLabel(stringResource(R.string.cal_attendees))
            if (f.attendees.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    f.attendees.forEach { a ->
                        Row(
                            Modifier.clip(RoundedCornerShape(999.dp)).background(extra.panel2)
                                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.mail_remove)) { viewModel.removeAttendee(a) }
                                .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(a.name ?: a.email, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Icon(GcIcons.Close, contentDescription = null, tint = extra.muted, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
            GcTextField(
                state.attendeeInput,
                viewModel::setAttendeeInput,
                stringResource(R.string.cal_add_attendee),
                placeholder = stringResource(R.string.cal_add_attendee_hint),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.commitAttendees() }),
            )
            if (state.suggestions.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)) {
                    state.suggestions.take(6).forEach { c -> SuggestionRow(c) { viewModel.pickAttendee(c) } }
                }
            }
            if (f.attendees.isNotEmpty()) {
                val sender = viewModel.sender(me, cal)
                val text = when {
                    cal?.source == MCalendar.SOURCE_GRAPH -> stringResource(R.string.cal_invites_provider)
                    sender != null -> stringResource(R.string.cal_invites_via, sender.label)
                    else -> null
                }
                if (text != null) GcBanner(tone = GcTone.Info, icon = GcIcons.Mail) { Text(text, style = MaterialTheme.typography.bodySmall) }
            }
            GcCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                GcSwitchRow(label = stringResource(R.string.cal_show_free), description = null, checked = f.free, onCheckedChange = { v -> viewModel.edit { it.copy(free = v) } })
                GcSwitchRow(label = stringResource(R.string.cal_private), description = stringResource(R.string.cal_private_desc), checked = f.private, onCheckedChange = { v -> viewModel.edit { it.copy(private = v) } })
            }
            GcTextField(f.description, { v -> viewModel.edit { it.copy(description = v) } }, stringResource(R.string.cal_description), singleLine = false)
            Box(Modifier.heightIn(min = 24.dp))
        }
    }
    if (state.askScope) {
        var series by remember { mutableStateOf(false) }
        val seriesAllowed = viewModel.seriesAllowed()
        AlertDialog(
            onDismissRequest = viewModel::dismissScope,
            title = { Text(stringResource(R.string.cal_scope_title)) },
            text = {
                Column {
                    ScopeChoice(series, seriesEnabled = seriesAllowed) { series = it }
                    if (!seriesAllowed) Text(stringResource(R.string.cal_scope_time_one), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.pickScope(series) }) { Text(stringResource(R.string.mail_save)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissScope) { Text(stringResource(R.string.mail_cancel)) } },
        )
    }
    state.conflict?.let {
        AlertDialog(
            onDismissRequest = viewModel::dismissConflict,
            title = { Text(stringResource(R.string.cal_conflict_title)) },
            text = { Text(stringResource(R.string.cal_conflict_text)) },
            confirmButton = { TextButton(onClick = viewModel::applyAgain) { Text(stringResource(R.string.pim_apply_again)) } },
            dismissButton = { TextButton(onClick = viewModel::reloadConflict) { Text(stringResource(R.string.pim_reload)) } },
        )
    }
}

@Composable
private fun CalendarPicker(list: List<MCalendar>, current: MCalendar?, onPick: (MCalendar) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val extra = GateControlTheme.extraColors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.cal_calendar), style = MaterialTheme.typography.titleSmall, color = extra.muted)
        Box {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, extra.border2, RoundedCornerShape(14.dp))
                    .clickable(role = Role.DropdownList) { open = true }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                current?.let { Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(calColor(it.color))) }
                Text(current?.name ?: "–", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Icon(GcIcons.ChevronDown, contentDescription = null, tint = extra.muted, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                list.forEach { c ->
                    DropdownMenuItem(
                        text = { Text(c.name) },
                        leadingIcon = { Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(calColor(c.color))) },
                        onClick = { open = false; onPick(c) },
                    )
                }
            }
        }
    }
}

/** "Beginn" / "Ende": the day and (without all-day) the time, each opening its picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateTimeRow(label: String, day: LocalDate, time: LocalTime?, locale: java.util.Locale, onPick: (LocalDate, LocalTime?) -> Unit) {
    var pickDay by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val extra = GateControlTheme.extraColors
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = extra.muted, modifier = Modifier.weight(1f))
        PickerChip(day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)), "$label: " + dayLong(day, locale)) { pickDay = true }
        if (time != null) PickerChip(time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)), label) { pickTime = true }
    }
    if (pickDay) {
        val st = rememberDatePickerState(initialSelectedDateMillis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDay = false },
            confirmButton = {
                TextButton(onClick = {
                    pickDay = false
                    st.selectedDateMillis?.let { ms -> onPick(java.time.Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate(), time) }
                }) { Text(stringResource(R.string.mail_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickDay = false }) { Text(stringResource(R.string.mail_cancel)) } },
        ) { DatePicker(st) }
    }
    if (pickTime && time != null) {
        val st = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = locale.language != "en")
        AlertDialog(
            onDismissRequest = { pickTime = false },
            text = { TimePicker(st) },
            confirmButton = { TextButton(onClick = { pickTime = false; onPick(day, LocalTime.of(st.hour, st.minute)) }) { Text(stringResource(R.string.mail_ok)) } },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(stringResource(R.string.mail_cancel)) } },
        )
    }
}

@Composable
internal fun PickerChip(text: String, description: String, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Box(
        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).background(extra.panel2)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description }.padding(horizontal = 12.dp, vertical = 9.dp),
    ) { Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
}

/** The zone, folded: "Zeitzone: Europe/Berlin"; open, a field (IANA name). */
@Composable
private fun ZoneRow(zone: String, device: String, onZone: (String) -> Unit) {
    var open by remember { mutableStateOf(zone != device) }
    var text by remember(zone) { mutableStateOf(zone) }
    val extra = GateControlTheme.extraColors
    if (!open) {
        Text(
            stringResource(R.string.cal_zone_line, zone),
            style = MaterialTheme.typography.bodySmall,
            color = extra.accentText,
            modifier = Modifier.clickable(role = Role.Button) { open = true }.padding(vertical = 6.dp),
        )
    } else {
        val ok = MailPimLogic.zone(text.trim()) != null
        GcTextField(
            text,
            { v ->
                text = v
                if (MailPimLogic.zone(v.trim()) != null) onZone(v.trim())
            },
            stringResource(R.string.cal_zone),
            placeholder = "Europe/Berlin",
            error = if (ok) null else stringResource(R.string.cal_zone_bad),
        )
    }
}

/** The rule: none, daily, weekly on days, monthly on the day / the n-th weekday, yearly; the end never / on / after n. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleBlock(viewModel: MailEventEditViewModel, f: EventForm, locked: Boolean, locale: java.util.Locale) {
    val extra = GateControlTheme.extraColors
    var open by remember { mutableStateOf(false) }
    val nth = MailPimLogic.nthWeekday(f.startDay)
    GcCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.cal_repeat), style = MaterialTheme.typography.titleSmall, color = extra.muted)
        if (locked) {
            Text(viewModel.rule(f)?.let { ruleText(it, f.startDay) } ?: "", style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.cal_rule_locked), style = MaterialTheme.typography.bodySmall, color = extra.muted)
            return@GcCard
        }
        Box {
            PickerChip(ruleKindText(f.ruleKind, f.startDay, nth), stringResource(R.string.cal_repeat)) { open = true }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                MailPimLogic.RULE_KINDS.forEach { k ->
                    DropdownMenuItem(text = { Text(ruleKindText(k, f.startDay, nth)) }, onClick = { open = false; viewModel.editRule { it.copy(ruleKind = k) } })
                }
            }
        }
        if (f.ruleKind == MailPimLogic.RULE_NONE) return@GcCard
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.cal_every), style = MaterialTheme.typography.bodyMedium)
            GcTextField(
                f.interval.toString(),
                { v -> v.filter { it.isDigit() }.take(3).toIntOrNull()?.let { n -> viewModel.editRule { it.copy(interval = n.coerceIn(1, 999)) } } },
                label = "",
                modifier = Modifier.size(width = 80.dp, height = 76.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        if (f.ruleKind == MRule.WEEKLY) {
            val days = f.byDay.ifEmpty { listOf(MRule.DAYS[f.startDay.dayOfWeek.value - 1]) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MRule.DAYS.forEachIndexed { i, d ->
                    val on = d in days
                    val name = java.time.DayOfWeek.of(i + 1).getDisplayName(java.time.format.TextStyle.SHORT, locale).removeSuffix(".")
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(999.dp))
                            .background(if (on) extra.accentBg else extra.panel2)
                            .selectable(selected = on, role = Role.Checkbox) {
                                val next = if (on) days - d else days + d
                                if (next.isNotEmpty()) viewModel.editRule { it.copy(byDay = next) }
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text(name, style = MaterialTheme.typography.labelMedium, color = if (on) extra.accentText else extra.muted, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        Text(stringResource(R.string.cal_ends), style = MaterialTheme.typography.bodySmall, color = extra.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(EventForm.END_NEVER to R.string.cal_ends_never, EventForm.END_UNTIL to R.string.cal_ends_on, EventForm.END_COUNT to R.string.cal_ends_after).forEach { (k, label) ->
                com.gatecontrol.android.ui.components.GcFilterChip(stringResource(label), selected = f.ruleEnd == k, onClick = {
                    viewModel.editRule { it.copy(ruleEnd = k, until = it.until ?: it.startDay.plusMonths(3)) }
                })
            }
        }
        when (f.ruleEnd) {
            EventForm.END_UNTIL -> DateTimeRow(stringResource(R.string.cal_ends_on), f.until ?: f.startDay.plusMonths(3), null, locale) { d, _ -> viewModel.editRule { it.copy(until = d) } }
            EventForm.END_COUNT -> GcTextField(
                f.count.toString(),
                { v -> v.filter { it.isDigit() }.take(4).toIntOrNull()?.let { n -> viewModel.editRule { it.copy(count = n.coerceIn(1, 1000)) } } },
                stringResource(R.string.cal_ends_count),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        viewModel.rule(f)?.let { Text(ruleText(it, f.startDay), style = MaterialTheme.typography.bodySmall, color = extra.accentText) }
    }
}

@Composable
private fun ruleKindText(kind: String, start: LocalDate, nth: Pair<Int, String>): String = when (kind) {
    MailPimLogic.RULE_NONE -> stringResource(R.string.cal_repeat_never)
    MRule.DAILY -> stringResource(R.string.cal_repeat_daily)
    MRule.WEEKLY -> stringResource(R.string.cal_repeat_weekly)
    MailPimLogic.RULE_MONTHLY_DAY -> stringResource(R.string.cal_repeat_monthly_day, start.dayOfMonth)
    MailPimLogic.RULE_MONTHLY_WEEKDAY -> ruleText(MRule(MRule.MONTHLY, byDay = listOf(nth.second), bySetPos = nth.first), start)
    else -> stringResource(R.string.cal_repeat_yearly)
}

/** Online: Teams (Microsoft 365 calendars) or a link. */
@Composable
private fun OnlineBlock(cal: MCalendar?, teams: Boolean, url: String, onTeams: (Boolean) -> Unit, onUrl: (String) -> Unit) {
    if (cal?.canOnline == true) {
        GcCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.Top) {
            GcSwitchRow(label = stringResource(R.string.cal_teams), description = null, checked = teams, onCheckedChange = onTeams)
        }
    }
    if (!teams) {
        GcTextField(url, onUrl, stringResource(R.string.cal_online_link), placeholder = "https://…", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
    }
}

/** The reminders (up to [max]): each one picked from the choices, "+" adds, "×" removes. */
@Composable
private fun RemindersBlock(list: List<Int>, allDay: Boolean, max: Int, onChange: (List<Int>) -> Unit) {
    val extra = GateControlTheme.extraColors
    val choices = if (allDay) MailPimLogic.ALL_DAY_REMINDERS else MailPimLogic.REMINDERS
    GcCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.cal_reminders), style = MaterialTheme.typography.titleSmall, color = extra.muted)
        if (list.isEmpty()) Text(stringResource(R.string.cal_no_reminder), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
        list.forEachIndexed { i, m ->
            var open by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    PickerChip(reminderText(m, allDay), stringResource(R.string.cal_reminders)) { open = true }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        (choices + m).distinct().sorted().forEach { c ->
                            DropdownMenuItem(text = { Text(reminderText(c, allDay)) }, onClick = { open = false; onChange(list.toMutableList().also { l -> l[i] = c }.distinct()) })
                        }
                    }
                }
                com.gatecontrol.android.ui.components.GcIconButton(GcIcons.Close, contentDescription = stringResource(R.string.mail_remove), onClick = { onChange(list - m) }, size = 40.dp)
            }
        }
        if (list.size < max.coerceAtLeast(1)) {
            TextButton(onClick = { onChange((list + (choices.firstOrNull { it !in list } ?: choices.first())).distinct()) }) {
                Icon(GcIcons.Plus, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.cal_add_reminder), modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}
