package com.gatecontrol.android.ui.mail

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MAccount
import com.gatecontrol.android.mail.MAtt
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MailLogic
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Writing a mail (mockup "Schreiben"): the account it goes from, recipients with suggestions, subject, text, the
 * quote of an answer, attachments from the device or from Drive (copy or link), the server's warnings, and send —
 * with the undo bar on the list afterwards — or "Senden planen".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MailComposeScreen(onClose: () -> Unit, viewModel: MailComposeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val me by viewModel.me.collectAsStateWithLifecycle()
    val event by viewModel.event.collectAsStateWithLifecycle()
    val context = mailContext()
    val extra = GateControlTheme.extraColors
    var attach by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var askDiscard by remember { mutableStateOf(false) }
    var noSubject by remember { mutableStateOf<Long?>(null) }
    var showQuote by remember { mutableStateOf(false) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.addFiles(uris)
    }

    LaunchedEffect(event) {
        when (val e = event) {
            null -> Unit
            ComposeEvent.Sent, ComposeEvent.Discarded -> onClose()
            ComposeEvent.Saved -> {
                toast(context, context.getString(R.string.mail_draft_saved))
                onClose()
            }
            is ComposeEvent.Toast -> toast(context, e.text)
        }
        if (event != null) viewModel.consumed()
    }
    BackHandler { viewModel.close() }

    val send: (Long?) -> Unit = { at -> if (state.subject.isBlank()) noSubject = at ?: 0L else viewModel.send(at) }
    val account = me?.account(state.accountId)

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            GcIconButton(GcIcons.Close, contentDescription = stringResource(R.string.mail_close), onClick = viewModel::close, tint = MaterialTheme.colorScheme.onBackground)
            Text(
                stringResource(
                    when (viewModel.mode) {
                        "reply", "replyAll" -> R.string.mail_reply
                        "forward" -> R.string.mail_forward
                        else -> R.string.mail_new
                    },
                ),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
                maxLines = 1,
            )
            if (state.saving) CircularProgressIndicator(Modifier.size(16.dp).padding(end = 4.dp), strokeWidth = 2.dp)
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(extra.panel2)) {
                GcIconButton(GcIcons.Paperclip, contentDescription = stringResource(R.string.mail_attach), onClick = { attach = true }, size = 44.dp, tint = MaterialTheme.colorScheme.onBackground)
            }
            Box {
                val sendLabel = stringResource(R.string.mail_send)
                val planLabel = stringResource(R.string.mail_plan)
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .combinedClickable(
                            enabled = !state.sending && !state.loading,
                            role = Role.Button,
                            onClick = { send(null) },
                            onLongClick = { plan = true },
                            onLongClickLabel = planLabel,
                        )
                        .semantics { contentDescription = sendLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(MailIcons.Send, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(22.dp))
                }
            }
            Box {
                GcIconButton(GcIcons.MoreVertical, contentDescription = stringResource(R.string.mail_more), onClick = { more = true }, tint = MaterialTheme.colorScheme.onBackground)
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.mail_plan)) }, onClick = { more = false; plan = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.mail_discard)) }, onClick = { more = false; askDiscard = true })
                }
            }
        }
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            FromRow(me, account, onPick = viewModel::setAccount)
            RecipientRow(RecipientField.TO, stringResource(R.string.mail_to), state, viewModel, trailing = {
                if (!state.showCc) TextButton(onClick = viewModel::showCc) { Text(stringResource(R.string.mail_cc_bcc)) }
            })
            if (state.showCc) {
                RecipientRow(RecipientField.CC, stringResource(R.string.mail_cc), state, viewModel)
                RecipientRow(RecipientField.BCC, stringResource(R.string.mail_bcc), state, viewModel)
            }
            FieldRow(stringResource(R.string.mail_subject)) {
                BasicTextField(
                    value = state.subject,
                    onValueChange = viewModel::setSubject,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onBackground),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = context.getString(R.string.mail_subject) },
                )
            }
            if (state.warnings.isNotEmpty() || state.error != null) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.warnings.forEach { w ->
                        GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
                            Text(w.text.ifBlank { warningText(w.kind) }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    state.error?.let { GcBanner(tone = GcTone.Error, icon = GcIcons.Alert) { Text(mailText(context, it), style = MaterialTheme.typography.bodySmall) } }
                }
            }
            state.attachments.forEach { a -> AttachmentRow(a, onRemove = { viewModel.removeAttachment(a) }) }
            BasicTextField(
                value = state.text,
                onValueChange = viewModel::setText,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f), fontSize = MaterialTheme.typography.bodyLarge.fontSize, lineHeight = MaterialTheme.typography.bodyLarge.lineHeight),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .semantics { contentDescription = context.getString(R.string.mail_text) },
            )
            if (state.quote.isNotBlank()) {
                TextButton(onClick = { showQuote = !showQuote }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(if (showQuote) R.string.mail_quote_hide else R.string.mail_quote_show))
                }
                if (showQuote) {
                    Text(
                        MailLogic.htmlToText(state.quote).trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.muted,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
                    )
                }
            }
        }
    }

    if (attach) {
        MailAttachSheet(
            drive = me?.has(MMe.FEATURE_DRIVE) == true,
            onDevice = {
                attach = false
                pickFiles.launch(arrayOf("*/*"))
            },
            onDrive = { picks ->
                attach = false
                viewModel.addDrive(picks)
            },
            onDismiss = { attach = false },
        )
    }
    if (plan) PlanDialog(onPick = { at -> plan = false; send(at) }, onDismiss = { plan = false })
    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text(stringResource(R.string.mail_discard_title)) },
            text = { Text(stringResource(R.string.mail_discard_text)) },
            confirmButton = { TextButton(onClick = { askDiscard = false; viewModel.discard() }) { Text(stringResource(R.string.mail_discard)) } },
            dismissButton = { TextButton(onClick = { askDiscard = false }) { Text(stringResource(R.string.mail_cancel)) } },
        )
    }
    noSubject?.let { at ->
        AlertDialog(
            onDismissRequest = { noSubject = null },
            title = { Text(stringResource(R.string.mail_no_subject_title)) },
            text = { Text(stringResource(R.string.mail_no_subject_text)) },
            confirmButton = { TextButton(onClick = { noSubject = null; viewModel.send(at.takeIf { it > 0 }) }) { Text(stringResource(R.string.mail_send)) } },
            dismissButton = { TextButton(onClick = { noSubject = null }) { Text(stringResource(R.string.mail_cancel)) } },
        )
    }
}

@Composable
private fun warningText(kind: String): String = when (kind) {
    "too_large" -> stringResource(R.string.mail_warn_too_large)
    "password" -> stringResource(R.string.mail_warn_password)
    "no_subject" -> stringResource(R.string.mail_no_subject_text)
    else -> stringResource(R.string.mail_warn_other)
}

/** A row of the head (mockup `.f`): its label left. */
@Composable
private fun FieldRow(label: String, trailing: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.faint, modifier = Modifier.width(52.dp))
            Box(Modifier.weight(1f)) { content() }
            trailing()
        }
        MailDivider()
    }
}

/** "Von": the account it goes from (tap: another one). */
@Composable
private fun FromRow(me: MMe?, account: MAccount?, onPick: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val accounts = me?.accounts.orEmpty()
    FieldRow(stringResource(R.string.mail_from)) {
        Box {
            Row(
                Modifier.fillMaxWidth().clickable(enabled = accounts.size > 1, role = Role.DropdownList) { open = true },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MailAccountAvatar(account, size = 24.dp, dot = false)
                Text(account?.email.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                account?.name?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted, maxLines = 1) }
                if (accounts.size > 1) Icon(GcIcons.ChevronDown, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                accounts.forEach { a ->
                    DropdownMenuItem(
                        leadingIcon = { MailAccountAvatar(a, size = 32.dp, ring = MaterialTheme.colorScheme.surfaceContainer) },
                        text = { MailAccountText(a) },
                        onClick = { open = false; onPick(a.id) },
                    )
                }
            }
        }
    }
}

/** "An" / "Cc" / "Bcc": the recipients as chips, what is typed, the suggestions below. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipientRow(f: RecipientField, label: String, state: ComposeState, viewModel: MailComposeViewModel, trailing: @Composable () -> Unit = {}) {
    val extra = GateControlTheme.extraColors
    val context = mailContext()
    FieldRow(label, trailing = trailing) {
        FlowRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            state.recipients(f).forEach { a ->
                Row(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(extra.panel2)
                        .clickable(role = Role.Button, onClickLabel = context.getString(R.string.mail_remove)) { viewModel.remove(f, a) }
                        .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(a.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Icon(GcIcons.Close, contentDescription = null, modifier = Modifier.size(14.dp), tint = extra.muted)
                }
            }
            BasicTextField(
                value = state.inputs[f].orEmpty(),
                onValueChange = { viewModel.setInput(f, it) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onBackground),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { viewModel.commit(f) }),
                modifier = Modifier
                    .widthIn(min = 120.dp)
                    .padding(vertical = 4.dp)
                    .onFocusChanged { if (!it.isFocused) viewModel.commit(f) }
                    .semantics { contentDescription = label },
            )
        }
    }
    if (state.suggestFor == f && state.suggestions.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
            // photo or initials and the source; a group puts in all its members (since Mail 1.2.0)
            state.suggestions.take(6).forEach { c ->
                com.gatecontrol.android.ui.mail.contacts.SuggestionRow(c) { viewModel.pick(f, c) }
            }
        }
    }
}

/** An attachment of the mail: type, name, size, "Link", remove. */
@Composable
private fun AttachmentRow(a: ComposeAttachment, onRemove: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val (mime, link) = when (a) {
        is ComposeAttachment.Saved -> a.a.mime to a.a.asLink
        is ComposeAttachment.Pending -> a.mime to ((a.att as? MAtt.Drive)?.asLink == true)
        is ComposeAttachment.Uploading -> "application/octet-stream" to false
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FileBadge(a.name, mime, size = 36)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(a.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    a.size.takeIf { it >= 0 }?.let { mailSize(it) },
                    stringResource(R.string.mail_attach_link).takeIf { link },
                    stringResource(R.string.mail_uploading).takeIf { a is ComposeAttachment.Uploading },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
            )
        }
        if (a is ComposeAttachment.Uploading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            GcIconButton(GcIcons.Close, contentDescription = stringResource(R.string.mail_remove_attachment, a.name), onClick = onRemove, size = 40.dp, iconSize = 18.dp)
        }
    }
}

/** "Senden planen": tomorrow morning, Monday morning, or a day and time of one's own. */
@Composable
private fun PlanDialog(onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val context = mailContext()
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val choices = remember { MailLogic.planChoices(now, zone) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mail_plan)) },
        text = {
            Column {
                choices.forEach { at ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onPick(at) }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(GcIcons.Clock, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(mailDateTime(at), modifier = Modifier.padding(start = 12.dp))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                        val today = LocalDate.now(zone)
                        DatePickerDialog(context, { _, y, mo, d ->
                            TimePickerDialog(context, { _, h, mi ->
                                val at = LocalDate.of(y, mo + 1, d).atTime(LocalTime.of(h, mi)).atZone(zone).toInstant().toEpochMilli()
                                if (at > System.currentTimeMillis() + 60_000) onPick(at) else toast(context, context.getString(R.string.mail_plan_past))
                            }, 8, 0, DateFormat.is24HourFormat(context)).show()
                        }, today.year, today.monthValue - 1, today.dayOfMonth).apply { datePicker.minDate = now }.show()
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(GcIcons.Calendar, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.mail_plan_pick), modifier = Modifier.padding(start = 12.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.mail_cancel)) } },
    )
}
