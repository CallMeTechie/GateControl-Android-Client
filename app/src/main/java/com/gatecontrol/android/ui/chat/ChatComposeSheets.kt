package com.gatecontrol.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DoorFront
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatActionCard
import com.gatecontrol.android.chat.ChatConversation
import com.gatecontrol.android.chat.ChatEventInfo
import com.gatecontrol.android.chat.ChatEventPlace
import com.gatecontrol.android.chat.ChatHomeAction
import com.gatecontrol.android.chat.ChatNotifyPrefs
import com.gatecontrol.android.chat.ChatPerson
import com.gatecontrol.android.chat.ChatPlace
import com.gatecontrol.android.chat.ChatPoll
import com.gatecontrol.android.quickactions.QuickActionIcons
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.familie.LatLon
import com.gatecontrol.android.ui.familie.OsmMap
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// ─── attach sheet (Messenger 1.4 layout) ────────────────────────────

/** What the attach sheet offers (each switched on by the server and the device). */
data class AttachOptions(
    val camera: Boolean,
    val files: Boolean,
    val location: Boolean,
    val polls: Boolean,
    val events: Boolean,
    val arrival: Boolean,
    val actions: Boolean,
    val maxBytes: Long?,
)

/** The attach sheet: media and places, then polls, events and live arrival, then the home's actions. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AttachSheet14(
    options: AttachOptions,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onFile: () -> Unit,
    onLocation: () -> Unit,
    onPoll: () -> Unit,
    onEvent: () -> Unit,
    onArrival: () -> Unit,
    onAction: (mode: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val extra = GateControlTheme.extraColors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = 4, horizontalArrangement = Arrangement.SpaceEvenly) {
                AttachTile14(Icons.Outlined.PhotoLibrary, stringResource(R.string.chat_attach_gallery), extra.blue, extra.blueBg, onGallery)
                if (options.camera) AttachTile14(Icons.Outlined.PhotoCamera, stringResource(R.string.chat_attach_camera), extra.pro, extra.proBg, onCamera)
                if (options.location) AttachTile14(Icons.Outlined.Place, stringResource(R.string.chat_attach_location), extra.accentText, extra.accentBg, onLocation)
                if (options.files) AttachTile14(Icons.Outlined.AttachFile, stringResource(R.string.chat_attach_file), extra.warn, extra.warnBg, onFile)
                if (options.polls) AttachTile14(Icons.Outlined.BarChart, stringResource(R.string.chat_attach_poll), extra.accentText, extra.accentBg, onPoll)
                if (options.events) AttachTile14(Icons.Outlined.Event, stringResource(R.string.chat_attach_event), extra.blue, extra.blueBg, onEvent)
                if (options.arrival) AttachTile14(Icons.Outlined.Navigation, stringResource(R.string.chat_attach_arrival), extra.warn, extra.warnBg, onArrival)
            }
            if (options.actions) {
                GcSectionLabel(stringResource(R.string.chat_attach_home), Modifier.padding(start = 12.dp, top = 8.dp))
                FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = 4, horizontalArrangement = Arrangement.SpaceEvenly) {
                    AttachTile14(Icons.Outlined.PlayCircleOutline, stringResource(R.string.chat_attach_action_run), extra.accentText, extra.accentBg) { onAction(ChatActionCard.MODE_RUN) }
                    AttachTile14(Icons.Outlined.DoorFront, stringResource(R.string.chat_attach_action_request), extra.warn, extra.warnBg) { onAction(ChatActionCard.MODE_REQUEST) }
                    AttachTile14(Icons.Outlined.Share, stringResource(R.string.chat_attach_action_grant), extra.pro, extra.proBg) { onAction(ChatActionCard.MODE_GRANT) }
                }
                Text(stringResource(R.string.chat_attach_home_hint), style = MaterialTheme.typography.bodySmall, color = extra.muted, modifier = Modifier.padding(horizontal = 12.dp))
            }
            Text(
                stringResource(R.string.chat_attach_hint, ChatText.bytes(options.maxBytes ?: (25L * 1024 * 1024))),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun AttachTile14(icon: ImageVector, label: String, tint: Color, bg: Color, onClick: () -> Unit) {
    Column(
        Modifier.width(84.dp).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(bg), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
    }
}

// ─── full-screen editors ────────────────────────────────────────────

@Composable
private fun EditorScaffold(title: String, onClose: () -> Unit, sendLabel: String, sendEnabled: Boolean, busy: Boolean, onSend: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                GcIconButton(GcIcons.Close, stringResource(R.string.chat_close), onClick = onClose, tint = MaterialTheme.colorScheme.onBackground)
                Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                content()
                Spacer(Modifier.height(8.dp))
            }
            GcPrimaryButton(text = sendLabel, onClick = onSend, enabled = sendEnabled && !busy, loading = busy, modifier = Modifier.fillMaxWidth().padding(16.dp))
        }
    }
}

@Composable
private fun Field(value: String, onValue: (String) -> Unit, label: String, placeholder: String? = null, singleLine: Boolean = true, max: Int = 200) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValue(it.take(max)) },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 5,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
        supportingText = if (value.length > max * 3 / 4) ({ Text("${value.length}/$max") }) else null,
    )
}

@Composable
private fun ChoiceRow(label: String, value: String, onClick: () -> Unit, description: String? = null) {
    val extra = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = extra.muted) }
        }
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = extra.accentText)
    }
}

private fun locale(ctx: android.content.Context): Locale = ctx.resources.configuration.locales[0] ?: Locale.getDefault()

internal fun dateTimeLabel(ctx: android.content.Context, ms: Long): String =
    DateTimeFormatter.ofPattern("EEE dd.MM. HH:mm", locale(ctx)).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** Poll editor (mockup "Abstimmung erstellen"). */
@Composable
fun PollCreateDialog(busy: Boolean, onSend: (question: String, options: List<String>, multi: Boolean, anonymous: Boolean, allowAdd: Boolean, closesAtMs: Long?) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var question by rememberSaveable { mutableStateOf("") }
    val answers = remember { mutableStateListOf("", "") }
    var multi by rememberSaveable { mutableStateOf(false) }
    var anonymous by rememberSaveable { mutableStateOf(false) }
    var allowAdd by rememberSaveable { mutableStateOf(true) }
    var closesAt by rememberSaveable { mutableStateOf<Long?>(null) }
    var picking by remember { mutableStateOf(false) }
    val filled = answers.map { it.trim() }.filter { it.isNotEmpty() }
    val valid = question.isNotBlank() && filled.size >= ChatPoll.MIN_OPTIONS && filled.distinctBy { it.lowercase() }.size == filled.size
    EditorScaffold(stringResource(R.string.chat_poll_create), onDismiss, stringResource(R.string.chat_poll_send), valid, busy, {
        onSend(question.trim(), filled, multi, anonymous, allowAdd, closesAt)
    }) {
        GcSectionLabel(stringResource(R.string.chat_poll_question))
        Field(question, { question = it }, stringResource(R.string.chat_poll_question), stringResource(R.string.chat_poll_question_hint), max = ChatPoll.QUESTION_MAX)
        GcSectionLabel(stringResource(R.string.chat_poll_answers))
        answers.forEachIndexed { i, a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Field(a, { answers[i] = it }, stringResource(R.string.chat_poll_answer_n, i + 1), max = ChatPoll.OPTION_MAX) }
                if (answers.size > ChatPoll.MIN_OPTIONS) GcIconButton(GcIcons.Close, stringResource(R.string.chat_poll_answer_remove), onClick = { answers.removeAt(i) })
            }
        }
        if (answers.size < ChatPoll.MAX_OPTIONS) {
            TextButton(onClick = { answers.add("") }) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text(stringResource(R.string.chat_poll_answer_add))
            }
        }
        GcSectionLabel(stringResource(R.string.chat_poll_options))
        GcCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            GcSwitchRow(stringResource(R.string.chat_poll_opt_multi), null, multi, { multi = it })
            GcSwitchRow(stringResource(R.string.chat_poll_opt_anonymous), stringResource(R.string.chat_poll_opt_anonymous_desc), anonymous, { anonymous = it })
            GcSwitchRow(stringResource(R.string.chat_poll_opt_add), null, allowAdd, { allowAdd = it })
            ChoiceRow(
                stringResource(R.string.chat_poll_ends),
                closesAt?.let { dateTimeLabel(ctx, it) } ?: stringResource(R.string.chat_poll_ends_never),
                { picking = true },
                stringResource(R.string.chat_poll_ends_desc),
            )
        }
    }
    if (picking) {
        DateTimePickerDialog(
            initialMs = closesAt ?: (System.currentTimeMillis() + 86_400_000L),
            onPick = { closesAt = it.takeIf { ms -> ms > System.currentTimeMillis() }; picking = false },
            onClear = { closesAt = null; picking = false },
            onDismiss = { picking = false },
        )
    }
}

/** What the event editor sends. */
data class EventDraft(
    val title: String,
    val startsAtMs: Long,
    val endsAtMs: Long?,
    val allDay: Boolean,
    val place: ChatEventPlace?,
    val description: String?,
    val checklist: List<String>,
    val rsvp: Boolean,
    val guests: Boolean,
    val reminderMin: Int,
    val familyCalendar: Boolean,
    val departureHint: Boolean,
)

/** Event editor (mockup "Ereignis erstellen"). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventCreateDialog(
    places: List<ChatPlace>?,
    defaultReminderMin: Int,
    arrivalOn: Boolean,
    busy: Boolean,
    onSend: (EventDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    val zone = ZoneId.systemDefault()
    val initial = remember { ZonedDateTime.now(zone).plusDays(1).withHour(18).withMinute(0).withSecond(0).withNano(0) }
    var title by rememberSaveable { mutableStateOf("") }
    var startMs by rememberSaveable { mutableStateOf(initial.toInstant().toEpochMilli()) }
    var endMs by rememberSaveable { mutableStateOf<Long?>(initial.plusHours(2).toInstant().toEpochMilli()) }
    var allDay by rememberSaveable { mutableStateOf(false) }
    var placeLabel by rememberSaveable { mutableStateOf("") }
    var placeId by rememberSaveable { mutableStateOf<String?>(null) }
    var placeLat by rememberSaveable { mutableStateOf<Double?>(null) }
    var placeLon by rememberSaveable { mutableStateOf<Double?>(null) }
    var description by rememberSaveable { mutableStateOf("") }
    val checklist = remember { mutableStateListOf<String>() }
    var rsvp by rememberSaveable { mutableStateOf(true) }
    var guests by rememberSaveable { mutableStateOf(true) }
    var reminder by rememberSaveable { mutableIntStateOf(defaultReminderMin) }
    var calendar by rememberSaveable { mutableStateOf(false) }
    var departure by rememberSaveable { mutableStateOf(false) }
    var pick by remember { mutableStateOf<String?>(null) }
    val valid = title.isNotBlank() && (endMs == null || endMs!! >= startMs)
    EditorScaffold(stringResource(R.string.chat_event_create), onDismiss, stringResource(R.string.chat_event_send), valid, busy, {
        onSend(
            EventDraft(
                title = title.trim(),
                startsAtMs = startMs,
                endsAtMs = endMs,
                allDay = allDay,
                place = placeLabel.trim().takeIf { it.isNotEmpty() }?.let { ChatEventPlace(placeId, it, placeLat, placeLon) },
                description = description.trim().takeIf { it.isNotEmpty() },
                checklist = checklist.map { it.trim() }.filter { it.isNotEmpty() },
                rsvp = rsvp,
                guests = guests && rsvp,
                reminderMin = reminder,
                familyCalendar = calendar,
                departureHint = departure && arrivalOn,
            ),
        )
    }) {
        Field(title, { title = it }, stringResource(R.string.chat_event_title), stringResource(R.string.chat_event_title_hint), max = ChatEventInfo.TITLE_MAX)
        GcCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            ChoiceRow(stringResource(R.string.chat_event_date), DateTimeFormatter.ofPattern("EEE dd.MM.yyyy", locale(ctx)).format(Instant.ofEpochMilli(startMs).atZone(zone)), { pick = "start" })
            GcSwitchRow(stringResource(R.string.chat_event_all_day), null, allDay, { allDay = it })
            if (!allDay) {
                ChoiceRow(stringResource(R.string.chat_event_time), DateTimeFormatter.ofPattern("HH:mm", locale(ctx)).format(Instant.ofEpochMilli(startMs).atZone(zone)), { pick = "startTime" })
                ChoiceRow(
                    stringResource(R.string.chat_event_ends),
                    endMs?.let { DateTimeFormatter.ofPattern("HH:mm", locale(ctx)).format(Instant.ofEpochMilli(it).atZone(zone)) } ?: stringResource(R.string.chat_event_no_end),
                    { pick = "end" },
                )
            }
        }
        GcSectionLabel(stringResource(R.string.chat_event_place))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            places.orEmpty().forEach { p ->
                PickChip(p.name, placeId == p.id) {
                    if (placeId == p.id) {
                        placeId = null; placeLabel = ""; placeLat = null; placeLon = null
                    } else {
                        placeId = p.id; placeLabel = p.name; placeLat = p.lat; placeLon = p.lon
                    }
                }
            }
        }
        Field(placeLabel, { placeLabel = it; if (placeId != null) { placeId = null; placeLat = null; placeLon = null } }, stringResource(R.string.chat_event_place_free), stringResource(R.string.chat_event_place_free_hint), max = 120)
        Field(description, { description = it }, stringResource(R.string.chat_event_description), singleLine = false, max = ChatEventInfo.DESCRIPTION_MAX)
        GcSectionLabel(stringResource(R.string.chat_event_checklist_add) + if (checklist.isNotEmpty()) " · " + pluralStringResource(R.plurals.chat_event_checklist_items, checklist.size, checklist.size) else "")
        checklist.forEachIndexed { i, item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Field(item, { checklist[i] = it }, stringResource(R.string.chat_event_checklist_item, i + 1), max = ChatEventInfo.CHECK_TEXT_MAX) }
                GcIconButton(GcIcons.Close, stringResource(R.string.chat_poll_answer_remove), onClick = { checklist.removeAt(i) })
            }
        }
        if (checklist.size < ChatEventInfo.CHECKLIST_MAX) {
            TextButton(onClick = { checklist.add("") }) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text(stringResource(R.string.chat_event_checklist_add))
            }
        }
        GcCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            GcSwitchRow(stringResource(R.string.chat_event_opt_rsvp), stringResource(R.string.chat_event_opt_rsvp_desc), rsvp, { rsvp = it })
            if (rsvp) GcSwitchRow(stringResource(R.string.chat_event_opt_guests), null, guests, { guests = it })
            GcSwitchRow(stringResource(R.string.chat_event_opt_calendar), stringResource(R.string.chat_event_opt_calendar_desc), calendar, { calendar = it })
            ChoiceRow(stringResource(R.string.chat_event_opt_reminder), reminderLabel(reminder), { pick = "reminder" }, stringResource(R.string.chat_event_opt_reminder_desc))
            if (arrivalOn) GcSwitchRow(stringResource(R.string.chat_event_opt_departure), stringResource(R.string.chat_event_opt_departure_desc), departure, { departure = it })
        }
        Text("", color = extra.muted)
    }
    when (pick) {
        "start" -> DateTimePickerDialog(startMs, dateOnly = true, onPick = { ms ->
            val t = Instant.ofEpochMilli(startMs).atZone(zone).toLocalTime()
            val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
            val shift = endMs?.let { it - startMs }
            startMs = d.atTime(t).atZone(zone).toInstant().toEpochMilli()
            endMs = shift?.let { startMs + it }
            pick = null
        }, onDismiss = { pick = null })
        "startTime" -> TimeOnlyDialog(startMs, onPick = { ms ->
            val shift = endMs?.let { it - startMs }
            startMs = ms
            endMs = shift?.let { startMs + it.coerceAtLeast(0) }
            pick = null
        }, onDismiss = { pick = null })
        "end" -> TimeOnlyDialog(endMs ?: (startMs + 3_600_000L), onPick = { ms ->
            endMs = if (ms < startMs) ms + 86_400_000L else ms
            pick = null
        }, onClear = { endMs = null; pick = null }, onDismiss = { pick = null })
        "reminder" -> OptionDialog(
            stringResource(R.string.chat_event_opt_reminder),
            ChatNotifyPrefs.REMINDER_MIN.map { it to reminderLabel(it) },
            reminder,
            { reminder = it; pick = null },
            { pick = null },
        )
    }
}

@Composable
internal fun reminderLabel(min: Int): String = when {
    min <= 0 -> stringResource(R.string.chat_reminder_off)
    min >= 1440 -> stringResource(R.string.chat_reminder_day)
    min >= 60 -> pluralStringResource(R.plurals.chat_reminder_hours, min / 60, min / 60)
    else -> pluralStringResource(R.plurals.chat_reminder_min, min, min)
}

@Composable
internal fun PickChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp))
            .background(if (selected) scheme.primary else Color.Transparent)
            .border(1.dp, if (selected) scheme.primary else extra.border2, RoundedCornerShape(20.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = if (selected) scheme.onPrimary else scheme.onSurface)
    }
}

/** A choice among [options] (value → label). */
@Composable
internal fun <T> OptionDialog(title: String, options: List<Pair<T, String>>, selected: T?, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (v, label) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.RadioButton) { onPick(v) }.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (v == selected) MaterialTheme.colorScheme.primary else GateControlTheme.extraColors.border2, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (v == selected) Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                        }
                        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) } },
    )
}

/** Date (and then time, unless [dateOnly]) → epoch ms in the device's zone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimePickerDialog(initialMs: Long, onPick: (Long) -> Unit, onDismiss: () -> Unit, dateOnly: Boolean = false, onClear: (() -> Unit)? = null) {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(initialMs).atZone(zone)
    var date by remember { mutableStateOf<LocalDate?>(null) }
    val d = date
    if (d == null) {
        val state = rememberDatePickerState(initialSelectedDateMillis = start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    val picked = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: start.toLocalDate()
                    if (dateOnly) onPick(picked.atTime(start.toLocalTime()).atZone(zone).toInstant().toEpochMilli()) else date = picked
                }) { Text(stringResource(R.string.chat_ok)) }
            },
            dismissButton = {
                Row {
                    onClear?.let { TextButton(onClick = it) { Text(stringResource(R.string.chat_poll_ends_never)) } }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) }
                }
            },
        ) { DatePicker(state = state) }
    } else {
        TimeOnlyDialog(d.atTime(start.toLocalTime()).atZone(zone).toInstant().toEpochMilli(), onPick = onPick, onDismiss = onDismiss)
    }
}

/** A time on the day of [initialMs] → epoch ms. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeOnlyDialog(initialMs: Long, onPick: (Long) -> Unit, onDismiss: () -> Unit, onClear: (() -> Unit)? = null) {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(initialMs).atZone(zone)
    val state = rememberTimePickerState(initialHour = start.hour, initialMinute = start.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_pick_time)) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = {
                onPick(start.toLocalDate().atTime(LocalTime.of(state.hour, state.minute)).atZone(zone).toInstant().toEpochMilli())
            }) { Text(stringResource(R.string.chat_ok)) }
        },
        dismissButton = {
            Row {
                onClear?.let { TextButton(onClick = it) { Text(stringResource(R.string.chat_event_no_end)) } }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) }
            }
        },
    )
}

// ─── live arrival ───────────────────────────────────────────────────

/** Destination picker of a live arrival: Familie places or a point on the map, an optional action on arrival. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ArrivalSheet(
    places: List<ChatPlace>?,
    actions: List<ChatHomeAction>,
    initial: ChatEventPlace?,
    busy: Boolean,
    onStart: (ChatEventPlace, ChatHomeAction?) -> Unit,
    onDismiss: () -> Unit,
) {
    val extra = GateControlTheme.extraColors
    val usable = places.orEmpty().filter { it.lat != null && it.lon != null }
    var picked by remember { mutableStateOf(initial?.takeIf { it.lat != null && it.lon != null }) }
    var mapMode by remember { mutableStateOf(usable.isEmpty() && initial == null) }
    var label by rememberSaveable { mutableStateOf(initial?.label.orEmpty()) }
    var onArrive by remember { mutableStateOf<ChatHomeAction?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.chat_arrival_create), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            GcSectionLabel(stringResource(R.string.chat_arrival_dest))
            if (usable.isEmpty() && places != null) Text(stringResource(R.string.chat_arrival_no_places), style = MaterialTheme.typography.bodySmall, color = extra.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                usable.forEach { p ->
                    PickChip(p.name, !mapMode && picked?.familiePlaceId == p.id) {
                        mapMode = false
                        picked = ChatEventPlace(p.id, p.name, p.lat, p.lon, p.radiusM)
                        label = p.name
                    }
                }
                PickChip(stringResource(R.string.chat_arrival_dest_map), mapMode) {
                    mapMode = true
                    if (picked?.familiePlaceId != null) picked = null
                }
            }
            if (mapMode) {
                Text(stringResource(R.string.chat_arrival_dest_map_hint), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                val p = picked
                OsmMap(
                    point = if (p?.lat != null && p.lon != null) LatLon(p.lat, p.lon) else null,
                    contentDescription = stringResource(R.string.chat_arrival_dest_map),
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    zoom = if (p != null) 15.0 else 6.0,
                    radiusM = p?.let { (it.radiusM ?: com.gatecontrol.android.chat.ChatArrival.DEFAULT_RADIUS_M).toDouble() },
                    onTap = { pt -> picked = ChatEventPlace(null, label.ifBlank { "" }, pt.lat, pt.lon) },
                )
                Field(label, { label = it }, stringResource(R.string.chat_arrival_dest_label), max = 120)
            }
            if (actions.isNotEmpty()) {
                GcSectionLabel(stringResource(R.string.chat_arrival_on_arrive_pick))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PickChip(stringResource(R.string.chat_arrival_on_arrive_none), onArrive == null) { onArrive = null }
                    actions.forEach { a -> PickChip(a.label, onArrive?.id == a.id) { onArrive = a } }
                }
            }
            Text(stringResource(R.string.chat_arrival_info), style = MaterialTheme.typography.bodySmall, color = extra.muted)
            val dest = picked?.let { if (mapMode) it.copy(label = label.trim()) else it }
            GcPrimaryButton(
                text = stringResource(R.string.chat_arrival_start),
                onClick = { dest?.let { onStart(it, onArrive) } },
                enabled = !busy && dest != null && dest.label.isNotBlank() && dest.lat != null,
                loading = busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ─── home actions ───────────────────────────────────────────────────

/** Picks a home action for [mode]; a grant also asks to whom and until when. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ActionPickerSheet(
    mode: String,
    actions: List<ChatHomeAction>?,
    members: List<ChatPerson>,
    busy: Boolean,
    onPick: (ChatHomeAction, grantTo: Long?, untilMs: Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    var chosen by remember { mutableStateOf<ChatHomeAction?>(null) }
    var grantTo by remember { mutableStateOf(members.singleOrNull()?.userId) }
    var until by remember { mutableStateOf(ZonedDateTime.now().withHour(22).withMinute(0).withSecond(0).withNano(0).let { if (it.toInstant().toEpochMilli() < System.currentTimeMillis()) it.plusDays(1) else it }.toInstant().toEpochMilli()) }
    var pickUntil by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(
                    when (mode) {
                        ChatActionCard.MODE_REQUEST -> R.string.chat_action_pick_request
                        ChatActionCard.MODE_GRANT -> R.string.chat_action_pick_grant
                        else -> R.string.chat_action_pick_run
                    },
                ),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            when {
                actions == null -> androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp))
                actions.isEmpty() -> Text(stringResource(R.string.chat_action_none), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
                else -> actions.forEach { a ->
                    val icon = remember(a.icon) { QuickActionIcons.vector(a.icon) }
                    val sel = chosen?.id == a.id
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (sel) extra.accentBg else Color.Transparent)
                            .clickable(role = Role.Button) {
                                chosen = a
                                if (mode != ChatActionCard.MODE_GRANT) onPick(a, null, null)
                            }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(extra.warnBg), contentAlignment = Alignment.Center) {
                            Icon(icon, contentDescription = null, tint = extra.warn, modifier = Modifier.size(20.dp))
                        }
                        Text(a.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        if (sel) Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (mode == ChatActionCard.MODE_GRANT && chosen != null) {
                GcSectionLabel(stringResource(R.string.chat_action_grant_to))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    members.forEach { p -> PickChip(ChatText.name(ctx, p), grantTo == p.userId) { grantTo = p.userId } }
                }
                ChoiceRow(stringResource(R.string.chat_action_grant_until), dateTimeLabel(ctx, until), { pickUntil = true })
                GcPrimaryButton(
                    text = stringResource(R.string.chat_action_grant_send),
                    onClick = { chosen?.let { onPick(it, grantTo, until) } },
                    enabled = !busy && grantTo != null && until > System.currentTimeMillis(),
                    loading = busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    if (pickUntil) {
        DateTimePickerDialog(until, onPick = { until = it; pickUntil = false }, onDismiss = { pickUntil = false })
    }
}

// ─── polls: voters, a new answer ────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VotersSheet(poll: ChatPoll, conversation: ChatConversation?, myId: Long?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.chat_voters_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(poll.question, style = MaterialTheme.typography.bodyMedium, color = extra.muted)
            poll.options.forEach { op ->
                GcSectionLabel("${op.text} · ${op.count}", Modifier.padding(top = 8.dp))
                val voters = op.voters.orEmpty()
                if (voters.isEmpty()) Text(stringResource(R.string.chat_poll_no_votes), style = MaterialTheme.typography.bodySmall, color = extra.faint)
                voters.forEach { uid ->
                    val p = conversation?.members?.firstOrNull { it.userId == uid }
                    Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ChatPersonAvatar(p, 32.dp)
                        Text(ChatText.nameOf(ctx, conversation, uid, myId), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            val members = conversation?.members?.map { it.userId }.orEmpty()
            val missing = com.gatecontrol.android.chat.ChatRichLogic.nonVoters(poll, members, null).orEmpty()
            if (missing.isNotEmpty()) {
                GcSectionLabel(stringResource(R.string.chat_poll_not_voted), Modifier.padding(top = 8.dp))
                Text(missing.joinToString(", ") { ChatText.nameOf(ctx, conversation, it, myId) }, style = MaterialTheme.typography.bodyMedium, color = extra.muted)
            }
        }
    }
}

/** One line of text (a new poll answer). */
@Composable
fun TextLineDialog(title: String, hint: String, max: Int, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it.take(max) }, singleLine = true, placeholder = { Text(hint) }, shape = RoundedCornerShape(14.dp)) },
        confirmButton = { TextButton(onClick = { onDone(text.trim()) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.chat_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) } },
    )
}

/** "Erinnere mich": in an hour, this evening, tomorrow morning or a picked time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindSheet(onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val now = ZonedDateTime.now(zone)
    val evening = now.withHour(18).withMinute(0).withSecond(0).withNano(0).let { if (!it.isAfter(now)) it.plusDays(1) else it }
    val morning = now.plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(stringResource(R.string.chat_remind_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            SheetChoice(stringResource(R.string.chat_remind_1h)) { onPick(System.currentTimeMillis() + 3_600_000L) }
            SheetChoice(stringResource(R.string.chat_remind_evening)) { onPick(evening.toInstant().toEpochMilli()) }
            SheetChoice(stringResource(R.string.chat_remind_tomorrow)) { onPick(morning.toInstant().toEpochMilli()) }
            SheetChoice(stringResource(R.string.chat_remind_custom)) { custom = true }
        }
    }
    if (custom) {
        DateTimePickerDialog(System.currentTimeMillis() + 3_600_000L, onPick = { custom = false; onPick(it) }, onDismiss = { custom = false })
    }
}

@Composable
private fun SheetChoice(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) }
}
