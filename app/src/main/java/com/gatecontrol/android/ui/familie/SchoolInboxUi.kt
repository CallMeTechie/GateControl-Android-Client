package com.gatecontrol.android.ui.familie

import com.gatecontrol.android.ui.components.GcListRow
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.Icons
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.school.AbsenceStatus
import com.gatecontrol.android.familie.school.SchoolAbsence
import com.gatecontrol.android.familie.school.SchoolAttachment
import com.gatecontrol.android.familie.school.SchoolConfirmation
import com.gatecontrol.android.familie.school.SchoolFileKind
import com.gatecontrol.android.familie.school.SchoolFiles
import com.gatecontrol.android.familie.school.SchoolInbox
import com.gatecontrol.android.familie.school.SchoolLocalFile
import com.gatecontrol.android.familie.school.SchoolLogic
import com.gatecontrol.android.familie.school.SchoolMessage
import com.gatecontrol.android.familie.school.SchoolRepository
import com.gatecontrol.android.familie.school.SchoolStudent
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

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
 * sender, date, subject, preview, a "neu" badge (also said in words), a
 * clip for attachments and "Bestätigung erbeten" while the school waits
 * for a read confirmation; a tap shows the message ([openId]), the back
 * key or "Alle Nachrichten" return to the list. Opening reads it (the view
 * model): the badge and bold text go at once. Attachments the view model
 * loaded ([files]) open in another app; without one for the type the
 * person can share the file instead.
 */
@Composable
internal fun SchoolMessagesTab(
    inbox: SchoolInbox?,
    student: SchoolStudent,
    openId: String?,
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    actions: SchoolMessageActions,
) {
    val messages = inbox?.messages
    val error = inbox?.messagesError
    BackHandler(enabled = openId != null, onBack = onClose)
    SchoolFileOpener(actions.files)
    when {
        messages == null && error != null -> SchoolError(error, onRetry)
        messages == null -> FamilieLoading()
        openId != null -> {
            InboxStaleBanner(error)
            SchoolMessageDetail(messages.firstOrNull { it.id == openId }, onClose, actions)
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

/** What the message detail can do and how it stands (from [SchoolViewModel]). */
internal class SchoolMessageActions(
    /** Message whose confirmation is being sent. */
    val confirming: String?,
    val confirmError: SchoolActionError?,
    val onConfirm: (String) -> Unit,
    /** Attachments being loaded. */
    val loadingAttachments: Set<String>,
    val attachmentError: SchoolActionError?,
    val onAttachment: (messageId: String, attachment: SchoolAttachment) -> Unit,
    /** Attachments loaded and ready to open. */
    val files: Flow<SchoolLocalFile>,
    /** Schule 1.7: attachments can go into Drive; the message being saved; how it went. */
    val drive: Boolean = false,
    val driveSaving: String? = null,
    val onDrive: (SchoolMessage, folderId: Long) -> Unit = { _, _ -> },
    val driveNotices: Flow<SchoolViewModel.DriveNotice> = kotlinx.coroutines.flow.emptyFlow(),
)

@Composable
private fun SchoolMessageRow(m: SchoolMessage, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val subject = m.subject.ifBlank { stringResource(R.string.school_message_no_subject) }
    val sender = m.sender.ifBlank { stringResource(R.string.school_message_unknown_sender) }
    val sent = m.sentAt?.let { sentText(it) }
    val newLabel = stringResource(R.string.school_message_new)
    val openLabel = stringResource(R.string.school_message_open)
    val confirmLabel = stringResource(R.string.school_confirm_chip)
    val description = listOfNotNull(
        newLabel.takeIf { m.isNew },
        stringResource(R.string.school_message_from, sender),
        subject,
        sent,
        stringResource(R.string.school_desc_attachments).takeIf { m.hasAttachments },
        confirmLabel.takeIf { m.confirmationPending },
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
            if (m.confirmationPending) GcChip(confirmLabel, tone = GcTone.Warn, small = true, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * One message in full: subject (a heading), sender, date, the text
 * (selectable); without the full text a note that it comes with the next
 * fetch. Below: the read confirmation the school asks for and the
 * attachments. [m] null: the message is not in the list.
 */
@Composable
private fun SchoolMessageDetail(m: SchoolMessage?, onClose: () -> Unit, actions: SchoolMessageActions) {
    val extra = GateControlTheme.extraColors
    GcOutlineButton(text = stringResource(R.string.school_message_back), onClick = onClose, icon = GcIcons.ChevronLeft, fillWidth = false)
    if (m == null) {
        FamilieEmpty(GcIcons.Mail, stringResource(R.string.school_message_not_found))
        return
    }
    GcCard(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            m.subject.ifBlank { stringResource(R.string.school_message_no_subject) },
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.school_message_from, m.sender.ifBlank { stringResource(R.string.school_message_unknown_sender) }),
            style = MaterialTheme.typography.bodyMedium,
            color = extra.muted,
        )
        m.sentAt?.let { Text(sentText(it), style = MaterialTheme.typography.bodySmall, color = extra.muted) }
        GcRowDivider()
        if (m.text.isNotBlank()) {
            SelectionContainer {
                Text(m.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        // Plugin 1.5+ loads the rest with its next fetch; older plugins only ever send the preview.
        val hint = when {
            m.fullText -> null
            m.extended -> R.string.school_message_full_text_later
            m.body == null -> R.string.school_message_preview_only
            else -> null
        }
        hint?.let { res ->
            GcBanner(tone = GcTone.Info, icon = GcIcons.Clock) {
                Text(stringResource(res), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
    m.confirmation?.let { SchoolConfirmationCard(m, it, actions) }
    if (m.attachments.isNotEmpty()) {
        SchoolAttachmentsCard(m, actions)
    } else if (m.hasAttachments) {
        Row(
            Modifier.padding(horizontal = 4.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(GcIcons.Paperclip, contentDescription = null, tint = extra.muted, modifier = Modifier.size(16.dp))
            Text(
                stringResource(if (m.extended) R.string.school_message_attachments_later else R.string.school_message_has_attachments),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
            )
        }
    }
}

/**
 * The school asks for a read confirmation: while open a button
 * "Lesen bestätigen" (with a short question first); afterwards
 * "Bestätigt am … (von …)".
 */
@Composable
private fun SchoolConfirmationCard(m: SchoolMessage, c: SchoolConfirmation, actions: SchoolMessageActions) {
    val extra = GateControlTheme.extraColors
    var ask by rememberSaveable(m.id) { mutableStateOf(false) }
    GcCard(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (c.confirmed) {
            Row(
                Modifier.semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(GcIcons.Check, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(20.dp))
                Text(confirmedText(c), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        } else {
            Row(
                Modifier.semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(GcIcons.Alert, contentDescription = null, tint = extra.warn, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(R.string.school_confirm_requested),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            actions.confirmError?.takeIf { it.id == m.id }?.let { e ->
                GcBanner(tone = GcTone.Error, icon = GcIcons.Alert) {
                    Text(schoolActionErrorText(e.code), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            GcPrimaryButton(
                text = stringResource(R.string.school_confirm_button),
                onClick = { ask = true },
                loading = actions.confirming == m.id,
                enabled = actions.confirming == null,
                icon = GcIcons.Check,
            )
        }
    }
    if (ask) {
        FamilieConfirmDialog(
            title = stringResource(R.string.school_confirm_title),
            text = stringResource(R.string.school_confirm_text),
            confirmLabel = stringResource(R.string.school_confirm_button),
            onConfirm = {
                ask = false
                actions.onConfirm(m.id)
            },
            onDismiss = { ask = false },
        )
    }
}

/** "Bestätigt am Mo., 13. Okt. 14:05 (von Anna)"; without a time "Bestätigt". */
@Composable
private fun confirmedText(c: SchoolConfirmation): String {
    val at = c.confirmedAt?.let { ms ->
        val zone = ZoneId.systemDefault()
        val instant = Instant.ofEpochMilli(ms)
        FamilieFormat.day(instant.atZone(zone).toLocalDate(), appLocale()) + " " + FamilieFormat.time(instant, zone)
    }
    return when {
        at != null && c.by != null -> stringResource(R.string.school_confirmed_at_by, at, c.by)
        at != null -> stringResource(R.string.school_confirmed_at, at)
        c.by != null -> stringResource(R.string.school_confirmed_by, c.by)
        else -> stringResource(R.string.school_confirmed)
    }
}

/** The attachments: type symbol, name, type and size; a tap loads and opens one (a spinner while it loads). */
@Composable
private fun SchoolAttachmentsCard(m: SchoolMessage, actions: SchoolMessageActions) {
    GcSectionLabel(stringResource(R.string.school_attachments_title), Modifier.padding(start = 4.dp, top = 4.dp).semantics { heading() })
    GcCard(contentPadding = PaddingValues(vertical = 2.dp), verticalArrangement = Arrangement.Top) {
        m.attachments.forEachIndexed { i, a ->
            if (i > 0) GcRowDivider()
            SchoolAttachmentRow(
                a,
                loading = a.id in actions.loadingAttachments,
                error = actions.attachmentError?.takeIf { it.id == a.id }?.code,
                onClick = { actions.onAttachment(m.id, a) },
            )
        }
        if (actions.drive) {
            GcRowDivider()
            SchoolDriveRow(m, actions)
        }
    }
}

/** "In Drive speichern" (Schule 1.7): every attachment into a Drive folder the person picks; how it went as a toast. */
@Composable
private fun SchoolDriveRow(m: SchoolMessage, actions: SchoolMessageActions) {
    val context = LocalContext.current
    var choose by remember { mutableStateOf(false) }
    val saving = actions.driveSaving == m.id
    LaunchedEffect(actions.driveNotices) {
        actions.driveNotices.collect { n ->
            Toast.makeText(context, schoolDriveNoticeText(context, n), Toast.LENGTH_LONG).show()
        }
    }
    GcListRow(
        title = if (m.attachments.size > 1) stringResource(R.string.school_drive_all, m.attachments.size) else stringResource(R.string.chat_to_drive),
        onClick = if (saving) null else ({ choose = true }),
        leading = { Icon(Icons.Outlined.CloudUpload, contentDescription = null, tint = GateControlTheme.extraColors.accentText) },
        trailing = { if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
    )
    if (choose) {
        com.gatecontrol.android.ui.drive.DriveFolderChooser(
            onPicked = { folderId ->
                choose = false
                actions.onDrive(m, folderId)
            },
            onDismiss = { choose = false },
        )
    }
}

/** A Drive refusal in words (Drive's codes, passed on by the Schule plugin). */
private fun schoolDriveNoticeText(context: android.content.Context, n: SchoolViewModel.DriveNotice): String = when {
    n.code != null && n.saved == 0 -> schoolDriveErrorText(context, n.code)
    n.code != null -> context.getString(R.string.school_drive_partly, n.saved, schoolDriveErrorText(context, n.code))
    n.saved == 1 && n.where != null -> context.getString(R.string.chat_drive_done, n.where)
    else -> context.resources.getQuantityString(R.plurals.school_drive_done, n.saved, n.saved)
}

private fun schoolDriveErrorText(context: android.content.Context, code: String): String = context.getString(
    when (code.lowercase()) {
        "no_seat" -> R.string.chat_err_drive_no_seat
        "not_licensed" -> R.string.chat_err_drive_not_licensed
        "quota" -> R.string.chat_err_drive_quota
        "forbidden" -> R.string.chat_err_drive_forbidden
        "device_locked" -> R.string.drive_code_device_locked
        "drive_unavailable" -> R.string.chat_err_drive_unavailable
        "offline" -> R.string.chat_err_offline
        else -> R.string.school_drive_failed
    },
)

@Composable
private fun SchoolAttachmentRow(a: SchoolAttachment, loading: Boolean, error: String?, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val context = LocalContext.current
    val kind = SchoolLogic.fileKind(a.mime, a.name)
    val name = a.name.ifBlank { stringResource(R.string.school_attachment_unnamed) }
    val kindLabel = stringResource(fileKindLabel(kind))
    val size = a.size?.let { Formatter.formatShortFileSize(context, it) }
    val details = listOfNotNull(kindLabel, size).joinToString(" · ")
    val openLabel = stringResource(R.string.school_attachment_open)
    val loadingLabel = stringResource(R.string.school_attachment_loading)
    val errorText = error?.let { schoolActionErrorText(it) }
    val description = listOfNotNull(
        stringResource(R.string.school_desc_attachment, name),
        details,
        loadingLabel.takeIf { loading },
        errorText,
    ).joinToString(", ")
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = !loading, onClickLabel = openLabel, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                if (loading) disabled()
                onClick(label = openLabel) { if (!loading) onClick(); !loading }
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(extra.panel2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (kind == SchoolFileKind.IMAGE) GcIcons.Photo else GcIcons.File, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(details, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1)
            }
            if (loading) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            } else {
                Icon(GcIcons.Download, contentDescription = null, tint = extra.muted, modifier = Modifier.size(18.dp))
            }
        }
        errorText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

@StringRes
private fun fileKindLabel(kind: SchoolFileKind): Int = when (kind) {
    SchoolFileKind.PDF -> R.string.school_file_pdf
    SchoolFileKind.IMAGE -> R.string.school_file_image
    SchoolFileKind.DOCUMENT -> R.string.school_file_document
    SchoolFileKind.SHEET -> R.string.school_file_sheet
    SchoolFileKind.MEDIA -> R.string.school_file_media
    SchoolFileKind.ARCHIVE -> R.string.school_file_archive
    SchoolFileKind.OTHER -> R.string.school_file_other
}

/** Errors of opening an attachment or confirming, in words; others as [schoolErrorText]. */
@Composable
private fun schoolActionErrorText(code: String): String = when (code) {
    "SCHULE_ATTACHMENT_NOT_FOUND" -> stringResource(R.string.school_attachment_error_not_found)
    "SCHULE_ATTACHMENT_TOO_LARGE" -> stringResource(R.string.school_attachment_error_too_large)
    "SCHULE_ATTACHMENT_TYPE" -> stringResource(R.string.school_attachment_error_type)
    "SCHULE_GATECONTROL_TOO_OLD" -> stringResource(R.string.school_attachment_error_server_old)
    "SCHULE_ATTACHMENT_FAILED", SchoolFiles.CODE_DOWNLOAD -> stringResource(R.string.school_attachment_error_failed)
    "SCHULE_UNTIS_UNAVAILABLE" -> stringResource(R.string.school_error_school_unavailable)
    "SCHULE_MESSAGE_NOT_FOUND" -> stringResource(R.string.school_message_error_not_found)
    "SCHULE_CONFIRM_NOT_REQUIRED" -> stringResource(R.string.school_confirm_error_not_required)
    "SCHULE_CONFIRM_FAILED" -> stringResource(R.string.school_confirm_error_failed)
    else -> schoolErrorText(code)
}

/**
 * Opens each attachment of [files] in another app (ACTION_VIEW through the
 * FileProvider); when no app takes the type, a question offers to share
 * the file instead.
 */
@Composable
private fun SchoolFileOpener(files: Flow<SchoolLocalFile>) {
    val context = LocalContext.current
    var noApp by remember { mutableStateOf<SchoolLocalFile?>(null) }
    LaunchedEffect(files) {
        files.collect { f ->
            when (viewSchoolFile(context, f)) {
                FileOpen.OPENED -> Unit
                FileOpen.NO_APP -> noApp = f
                FileOpen.FAILED -> Toast.makeText(context, R.string.school_attachment_error_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
    noApp?.let { f ->
        FamilieConfirmDialog(
            title = stringResource(R.string.school_attachment_no_app_title),
            text = stringResource(R.string.school_attachment_no_app_text, f.name),
            confirmLabel = stringResource(R.string.school_attachment_share),
            onConfirm = {
                noApp = null
                shareSchoolFile(context, f)
            },
            onDismiss = { noApp = null },
        )
    }
}

private enum class FileOpen { OPENED, NO_APP, FAILED }

private fun schoolFileUri(context: Context, f: SchoolLocalFile): Uri =
    FileProvider.getUriForFile(context, context.packageName + ".provider", f.file)

private fun viewSchoolFile(context: Context, f: SchoolLocalFile): FileOpen = try {
    val uri = schoolFileUri(context, f)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, f.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = ClipData.newRawUri(f.name, uri)
    context.startActivity(intent)
    FileOpen.OPENED
} catch (e: ActivityNotFoundException) {
    FileOpen.NO_APP
} catch (e: Exception) {
    FileOpen.FAILED
}

private fun shareSchoolFile(context: Context, f: SchoolLocalFile) {
    try {
        val uri = schoolFileUri(context, f)
        val intent = Intent(Intent.ACTION_SEND).setType(f.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(f.name, uri)
        context.startActivity(Intent.createChooser(intent, null))
    } catch (e: Exception) {
        Toast.makeText(context, R.string.school_attachment_error_failed, Toast.LENGTH_LONG).show()
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
