package com.gatecontrol.android.ui.familie

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.school.AbsenceStatus
import com.gatecontrol.android.familie.school.SchoolAbsence
import com.gatecontrol.android.familie.school.SchoolInbox
import com.gatecontrol.android.familie.school.SchoolLogic
import com.gatecontrol.android.familie.school.SchoolMessage
import com.gatecontrol.android.familie.school.SchoolRepository
import com.gatecontrol.android.familie.school.SchoolStudent
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.time.Instant

/**
 * Tabs of the timetable screen. They share the width like [GcSegmented]
 * when all labels fit (measured with the font scale); otherwise — four
 * long German labels on a phone — the same segments scroll sideways
 * instead of being cut.
 */
@Composable
internal fun SchoolTabs(tabs: List<Pair<SchoolTab, String>>, selected: SchoolTab, onSelect: (SchoolTab) -> Unit) {
    val style = MaterialTheme.typography.titleSmall
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Each segment: its label plus 12 dp either side; the row: 4 dp padding and gaps.
        val needed = with(density) {
            tabs.sumOf { (_, label) -> measurer.measure(label, style).size.width.toDp().value.toDouble() + 24.0 } +
                8.0 + 4.0 * (tabs.size - 1)
        }
        if (needed <= maxWidth.value) {
            GcSegmented(options = tabs, selected = selected, onSelect = onSelect)
        } else {
            val extra = GateControlTheme.extraColors
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(extra.panel2)
                    .horizontalScroll(rememberScrollState())
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                tabs.forEach { (value, label) ->
                    val isSel = value == selected
                    Box(
                        Modifier
                            .heightIn(min = 40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSel) MaterialTheme.colorScheme.surface else Color.Transparent)
                            .selectable(selected = isSel, role = Role.Tab, onClick = { onSelect(value) })
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = style, color = if (isSel) MaterialTheme.colorScheme.onSurface else extra.muted, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** "heute 14:05", "gestern 17:48", "Mo., 13. Okt. 13:20". */
@Composable
private fun sentText(ms: Long): String = relativeStamp(Instant.ofEpochMilli(ms).toString())

/** The list is the last one loaded: offline (a hint) or another error (the error, no retry). */
@Composable
private fun InboxStaleBanner(error: String?) {
    when {
        error == null -> Unit
        error == FamilieApi.CODE_OFFLINE -> GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
            Text(stringResource(R.string.school_inbox_offline_cached), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
        else -> SchoolError(error, onRetry = null)
    }
}

/**
 * Tab "Nachrichten" (plugin 1.4+): [student]'s messages, newest first —
 * sender, date, subject, preview, a "neu" badge (also said in words) and
 * a clip for attachments; a tap shows the message ([openId]), the back
 * key or "Alle Nachrichten" return to the list.
 */
@Composable
internal fun SchoolMessagesTab(
    inbox: SchoolInbox?,
    student: SchoolStudent,
    openId: String?,
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
    onRetry: () -> Unit,
) {
    val messages = inbox?.messages
    val error = inbox?.messagesError
    BackHandler(enabled = openId != null, onBack = onClose)
    when {
        messages == null && error != null -> SchoolError(error, onRetry)
        messages == null -> FamilieLoading()
        openId != null -> {
            InboxStaleBanner(error)
            SchoolMessageDetail(messages.firstOrNull { it.id == openId }, onClose)
        }
        else -> {
            InboxStaleBanner(error)
            val list = SchoolLogic.messagesFor(messages, student.id)
            if (list.isEmpty()) {
                FamilieEmpty(GcIcons.Mail, stringResource(R.string.school_messages_none, student.name, SchoolRepository.MESSAGE_DAYS))
            } else {
                GcCard(contentPadding = PaddingValues(vertical = 2.dp), verticalArrangement = Arrangement.Top) {
                    list.forEachIndexed { i, m ->
                        if (i > 0) GcRowDivider()
                        SchoolMessageRow(m, onClick = { onOpen(m.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SchoolMessageRow(m: SchoolMessage, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val subject = m.subject.ifBlank { stringResource(R.string.school_message_no_subject) }
    val sender = m.sender.ifBlank { stringResource(R.string.school_message_unknown_sender) }
    val sent = m.sentAt?.let { sentText(it) }
    val newLabel = stringResource(R.string.school_message_new)
    val openLabel = stringResource(R.string.school_message_open)
    val description = listOfNotNull(
        newLabel.takeIf { m.isNew },
        stringResource(R.string.school_message_from, sender),
        subject,
        sent,
        stringResource(R.string.school_desc_attachments).takeIf { m.hasAttachments },
        m.preview.ifBlank { null },
    ).joinToString(", ")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(label = openLabel) { onClick(); true }
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            GcIcons.Mail,
            contentDescription = null,
            tint = if (m.isNew) extra.accentText else extra.faint,
            modifier = Modifier.padding(top = 2.dp).size(20.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    sender,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (m.isNew) FontWeight.SemiBold else null),
                    color = if (m.isNew) MaterialTheme.colorScheme.onSurface else extra.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (m.isNew) GcChip(newLabel, tone = GcTone.Info, small = true)
                if (m.hasAttachments) Icon(GcIcons.Paperclip, contentDescription = null, tint = extra.muted, modifier = Modifier.size(14.dp))
                sent?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1) }
            }
            Text(
                subject,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (m.isNew) FontWeight.SemiBold else null),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (m.preview.isNotBlank()) {
                Text(m.preview, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * One message in full: subject (a heading), sender, date, the text
 * (selectable); without a full text the preview and the hint
 * "Volltext in WebUntis". [m] null: the message is not in the list.
 */
@Composable
private fun SchoolMessageDetail(m: SchoolMessage?, onClose: () -> Unit) {
    val extra = GateControlTheme.extraColors
    GcOutlineButton(text = stringResource(R.string.school_message_back), onClick = onClose, icon = GcIcons.ChevronLeft, fillWidth = false)
    if (m == null) {
        FamilieEmpty(GcIcons.Mail, stringResource(R.string.school_message_not_found))
        return
    }
    GcCard(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                m.subject.ifBlank { stringResource(R.string.school_message_no_subject) },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (m.isNew) GcChip(stringResource(R.string.school_message_new), tone = GcTone.Info, small = true)
        }
        Text(
            stringResource(R.string.school_message_from, m.sender.ifBlank { stringResource(R.string.school_message_unknown_sender) }),
            style = MaterialTheme.typography.bodyMedium,
            color = extra.muted,
        )
        m.sentAt?.let { Text(sentText(it), style = MaterialTheme.typography.bodySmall, color = extra.muted) }
        GcRowDivider()
        if (m.body != null) {
            SelectionContainer {
                Text(m.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        } else {
            if (m.preview.isNotBlank()) {
                Text(m.preview, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            GcBanner(tone = GcTone.Info, icon = GcIcons.External) {
                Text(stringResource(R.string.school_message_full_in_webuntis), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (m.hasAttachments) {
            Row(
                Modifier.semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(GcIcons.Paperclip, contentDescription = null, tint = extra.muted, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.school_message_attachments), style = MaterialTheme.typography.bodySmall, color = extra.muted)
            }
        }
    }
}

/**
 * Tab "Fehlzeiten" (plugin 1.4+): [student]'s absences, latest first —
 * date (range), time, reason and the state as a chip with text
 * ("entschuldigt" / "unentschuldigt" / "offen"; the colour only supports it).
 */
@Composable
internal fun SchoolAbsencesTab(inbox: SchoolInbox?, student: SchoolStudent, onRetry: () -> Unit) {
    val absences = inbox?.absences
    val error = inbox?.absencesError
    when {
        absences == null && error != null -> SchoolError(error, onRetry)
        absences == null -> FamilieLoading()
        else -> {
            InboxStaleBanner(error)
            val list = SchoolLogic.absencesFor(absences, student.id)
            if (list.isEmpty()) {
                FamilieEmpty(GcIcons.Calendar, stringResource(R.string.school_absences_none, student.name, SchoolRepository.ABSENCE_DAYS))
            } else {
                GcCard(contentPadding = PaddingValues(vertical = 2.dp), verticalArrangement = Arrangement.Top) {
                    list.forEachIndexed { i, a ->
                        if (i > 0) GcRowDivider()
                        SchoolAbsenceRow(a)
                    }
                }
            }
        }
    }
}

@Composable
private fun SchoolAbsenceRow(a: SchoolAbsence) {
    val extra = GateControlTheme.extraColors
    val locale = appLocale()
    fun day(date: String) = FamilieFormat.parseDate(date)?.let { FamilieFormat.day(it, locale) } ?: date
    val dates = a.endDate?.let { "${day(a.date)} – ${day(it)}" } ?: day(a.date)
    val from = a.start?.let { stringResource(R.string.school_absence_from, it) }
    val until = a.end?.let { stringResource(R.string.school_absence_until, it) }
    val time = when {
        a.endDate == null && a.start != null && a.end != null -> "${a.start}–${a.end}"
        from != null || until != null -> listOfNotNull(from, until).joinToString(" · ")
        a.endDate == null -> stringResource(R.string.school_absence_all_day)
        else -> null
    }
    val (label, tone) = when (a.status) {
        AbsenceStatus.EXCUSED -> stringResource(R.string.school_absence_excused) to GcTone.Ok
        AbsenceStatus.UNEXCUSED -> stringResource(R.string.school_absence_unexcused) to GcTone.Error
        AbsenceStatus.OPEN -> stringResource(R.string.school_absence_open) to GcTone.Warn
    }
    val description = listOfNotNull(dates, time, a.reason, a.text, stringResource(R.string.school_desc_absence_status, label)).joinToString(", ")
    Row(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(dates, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
            time?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = extra.muted) }
            a.reason?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
            a.text?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = extra.muted) }
        }
        GcChip(label, tone = tone, small = true)
    }
}
