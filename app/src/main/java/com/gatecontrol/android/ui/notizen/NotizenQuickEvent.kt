package com.gatecontrol.android.ui.notizen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.notizen.NDue
import com.gatecontrol.android.notizen.NEventCalendar
import com.gatecontrol.android.notizen.NRepeat
import com.gatecontrol.android.notizen.NotizenEvents
import com.gatecontrol.android.notizen.NotizenFormat
import com.gatecontrol.android.mail.MailPimLogic
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.mail.calendar.reminderText
import com.gatecontrol.android.ui.theme.GateControlTheme

/** `#RRGGBB` of a calendar → its colour (the app's accent without one). */
@Composable
internal fun calendarColor(hex: String?): Color =
    hex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: MaterialTheme.colorScheme.primary

/** „Das sieht wie ein Termin aus.“ (Bild 3): the app asks, it never switches by itself. */
@Composable
internal fun QuickEventAsk(onYes: () -> Unit, onNo: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.primary.copy(alpha = 0.12f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val head = stringResource(R.string.notizen_ev_ask_head)
        val rest = stringResource(R.string.notizen_ev_ask)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(head) }
                append(" ")
                append(rest)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        GcPrimaryButton(stringResource(R.string.notizen_ev_ask_yes), onYes, fillWidth = false, minHeight = 40.dp)
        GcOutlineButton(stringResource(R.string.notizen_ev_ask_no), onNo, fillWidth = false, minHeight = 40.dp)
    }
}

/** „Kein Kalender vorhanden“ (Bild 9): why, and „Als Aufgabe mit Datum speichern“ — the text is not lost. */
@Composable
internal fun QuickEventBlocked(reason: String, enabled: Boolean, onTask: () -> Unit) {
    val x = GateControlTheme.extraColors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(x.panel2)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(GcIcons.Calendar, null, modifier = Modifier.size(18.dp), tint = x.muted)
            Text(stringResource(R.string.notizen_ev_none_title), style = MaterialTheme.typography.titleSmall)
        }
        Text(stringResource(whyText(reason)), style = MaterialTheme.typography.bodyMedium, color = x.muted)
        GcOutlineButton(stringResource(R.string.notizen_ev_as_task), onTask, enabled = enabled, fillWidth = false, minHeight = 40.dp)
        Text(stringResource(R.string.notizen_ev_as_task_hint), style = MaterialTheme.typography.bodySmall, color = x.muted)
    }
}

internal fun whyText(reason: String?): Int = when (NotizenEvents.why(reason)) {
    NotizenEvents.WHY_MAIL -> R.string.notizen_ev_why_mail
    NotizenEvents.WHY_OFF -> R.string.notizen_ev_why_off
    NotizenEvents.WHY_NONE -> R.string.notizen_ev_why_none
    else -> R.string.notizen_ev_why_unreachable
}

/** A chip that offers something not taken yet (dashed border): „Ort: <Titel>?“. */
@Composable
internal fun DashedChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    val line = GateControlTheme.extraColors.border2
    Row(
        Modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(18.dp))
            .drawBehind {
                drawRoundRect(
                    color = line,
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                    cornerRadius = CornerRadius(18.dp.toPx()),
                )
            }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** The chip „Kalender: Privat“ with the calendar's colour as a dot. */
@Composable
private fun CalendarChip(cal: NEventCalendar?, onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    Row(
        Modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(calendarColor(cal?.color)))
        Text(stringResource(R.string.notizen_quick_calendar, cal?.name ?: "…"), style = MaterialTheme.typography.labelLarge, color = if (cal == null) x.muted else MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * The chips of an event (Bilder 1, 2): the day, the time (or „Ganztägig“), the place (a text only — or the dashed
 * „Ort: <Titel>?“), the calendar, the reminder (the calendar's own until one is chosen), a rule, „Mehr Angaben …“.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickEventChips(
    d: NotizenEvents.Draft,
    calendar: NEventCalendar?,
    reminder: Int?,
    lang: String,
    colors: Map<String, Color>,
    onDialog: (String) -> Unit,
    onTitleAsPlace: () -> Unit,
    onDropRepeat: () -> Unit,
    onMore: () -> Unit,
) {
    val x = GateControlTheme.extraColors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickChip(NotizenFormat.shortDate(d.date, lang), colors.getValue("date"), GcIcons.Calendar) { onDialog(EV_DATE) }
        val time = if (d.time == null) stringResource(R.string.notizen_ev_all_day) else listOfNotNull(NotizenFormat.time(d.time), NotizenFormat.time(d.endTime)).joinToString("–")
        QuickChip(time, colors.getValue("time"), GcIcons.Clock) { onDialog(EV_TIME) }
        if (d.location != null) {
            QuickChip(stringResource(R.string.notizen_ev_place, d.location), colors.getValue("place"), GcIcons.Pin) { onDialog(EV_PLACE) }
        } else if (d.title.isNotBlank()) {
            DashedChip(stringResource(R.string.notizen_ev_place_ask, d.title), GcIcons.Pin, onTitleAsPlace)
        }
        CalendarChip(calendar) { onDialog(EV_CALENDAR) }
        QuickChip(reminder?.let { reminderText(it, d.allDay) } ?: stringResource(R.string.notizen_ev_remind_none), x.panel2, GcIcons.Bell) { onDialog(EV_REMIND) }
        d.repeat?.let { QuickChip(NRepeat.describe(it, lang), colors.getValue("repeat"), GcIcons.Refresh) { onDropRepeat() } }
        QuickChip(stringResource(R.string.notizen_ev_more), x.panel2, GcIcons.Edit, onMore)
    }
    val title = stringResource(R.string.notizen_ev_title_is, d.title)
    val more = when {
        d.location == null && d.title.isNotBlank() -> " " + stringResource(R.string.notizen_ev_title_place_hint)
        d.allDay -> " " + stringResource(R.string.notizen_ev_all_day_hint)
        else -> ""
    }
    Text(title + more, style = MaterialTheme.typography.bodySmall, color = x.muted)
}

internal const val EV_DATE = "ev_date"
internal const val EV_TIME = "ev_time"
internal const val EV_PLACE = "ev_place"
internal const val EV_CALENDAR = "ev_calendar"
internal const val EV_REMIND = "ev_remind"

/** The dialogs of the event's chips; [dialog] one of `EV_*` (anything else: none). */
@Composable
internal fun QuickEventDialogs(
    dialog: String?,
    d: NotizenEvents.Draft?,
    today: String,
    calendars: List<NEventCalendar>,
    calendar: NEventCalendar?,
    reminder: Int?,
    onDue: (NDue) -> Unit,
    onPlace: (String?) -> Unit,
    onCalendar: (Long) -> Unit,
    onReminder: (Int?) -> Unit,
    onClose: () -> Unit,
) {
    if (d == null) return
    when (dialog) {
        EV_DATE -> {
            var day by rememberSaveable { mutableStateOf<String?>(null) }
            if (day == null) {
                NDatePicker(d.date, today, { day = it }, onClose)
            } else {
                // „Ohne Uhrzeit“ in the time: all day
                NTimePicker(d.time, { t -> onClose(); onDue(NDue(day!!, t)) }, onClose)
            }
        }
        EV_TIME -> NTimePicker(d.time, { t -> onClose(); onDue(NDue(d.date, t)) }, onClose)
        EV_PLACE -> PlaceTextDialog(d.location.orEmpty(), onDone = { onClose(); onPlace(it) }, onRemove = { onClose(); onPlace(null) }, onDismiss = onClose)
        EV_CALENDAR -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text(stringResource(R.string.notizen_ev_calendar_pick)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    calendars.filter { it.target && it.id != null }.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { onClose(); onCalendar(c.id!!) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(calendarColor(c.color)))
                            Text(c.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (c.id == calendar?.id) FontWeight.Bold else null))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.notizen_cancel)) } },
        )
        EV_REMIND -> {
            val options: List<Int?> = listOf<Int?>(null) + (if (d.allDay) MailPimLogic.ALL_DAY_REMINDERS else MailPimLogic.REMINDERS)
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text(stringResource(R.string.notizen_ev_remind_pick)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        options.forEach { m ->
                            Text(
                                m?.let { reminderText(it, d.allDay) } ?: stringResource(R.string.notizen_ev_remind_none),
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (m == reminder) FontWeight.Bold else null),
                                modifier = Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { onClose(); onReminder(m) }.padding(vertical = 12.dp),
                            )
                        }
                    }
                },
                confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.notizen_cancel)) } },
            )
        }
    }
}

/** „Ort des Termins“ (Bild 4): one text field — no map, no search, no remembered places. */
@Composable
private fun PlaceTextDialog(initial: String, onDone: (String) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notizen_ev_place_title)) },
        text = { EventPlaceForm(text) { text = it } },
        confirmButton = { TextButton(onClick = { onDone(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.notizen_ev_place_take)) } },
        dismissButton = { if (initial.isNotBlank()) TextButton(onClick = onRemove) { Text(stringResource(R.string.notizen_ev_place_remove)) } else TextButton(onClick = onDismiss) { Text(stringResource(R.string.notizen_cancel)) } },
    )
}

/** The field of „Ort des Termins“ and what it is (Bild 4). */
@Composable
internal fun EventPlaceForm(text: String, onText: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(text, { onText(it.take(255)) }, label = { Text(stringResource(R.string.notizen_ev_place_field)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.notizen_ev_place_hint), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
    }
}
