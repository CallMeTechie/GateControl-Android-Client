package com.gatecontrol.android.ui.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MAccount
import com.gatecontrol.android.mail.MFilingItem
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MSettings
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme

/** "Zum Ablegen" (mockup): each attachment with Drive's suggestion and how sure it is; "Alle ablegen". */
@Composable
fun MailFilingScreen(onBack: () -> Unit, viewModel: MailFilingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val me by viewModel.me.collectAsStateWithLifecycle()
    val toastText by viewModel.toast.collectAsStateWithLifecycle()
    val context = mailContext()
    val extra = GateControlTheme.extraColors
    var other by remember { mutableStateOf<MFilingItem?>(null) }
    LaunchedEffect(toastText) {
        toastText?.let {
            toast(context, it)
            viewModel.toastShown()
        }
    }
    Column(Modifier.fillMaxSize()) {
        GcSubpageBar(title = stringResource(R.string.mail_filing), onBack = onBack, backDescription = stringResource(R.string.mail_back)) {
            if (state.items.any { it.suggestion != null }) TextButton(onClick = viewModel::confirmAll) { Text(stringResource(R.string.mail_filing_all), fontWeight = FontWeight.SemiBold) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "intro") {
                Text(stringResource(R.string.mail_filing_intro), style = MaterialTheme.typography.bodySmall, color = extra.muted, modifier = Modifier.padding(horizontal = 4.dp))
            }
            when {
                state.loading -> item(key = "load") { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                state.error != null && state.items.isEmpty() -> item(key = "err") { ErrorBox(mailText(context, state.error), onRetry = viewModel::load) }
                state.items.isEmpty() -> item(key = "empty") { ErrorBox(stringResource(R.string.mail_filing_empty), onRetry = null) }
            }
            items(state.items, key = { it.id }) { item ->
                FilingCard(
                    item = item,
                    account = null,
                    busy = item.id in state.busy,
                    onConfirm = { viewModel.confirm(item) },
                    onOther = { other = item },
                    onDismiss = { viewModel.dismiss(item) },
                )
            }
            me?.accounts?.takeIf { it.isNotEmpty() }?.let { accounts ->
                item(key = "auto") {
                    Text(
                        stringResource(
                            R.string.mail_filing_auto,
                            accounts.joinToString(", ") { a ->
                                a.label + ": " + context.getString(
                                    when (a.attachments) {
                                        "auto" -> R.string.mail_attachments_auto
                                        "suggest" -> R.string.mail_attachments_suggest
                                        else -> R.string.mail_attachments_off
                                    },
                                )
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.muted,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
    other?.let { item ->
        com.gatecontrol.android.ui.drive.DriveFolderChooser(
            onPicked = { folderId ->
                other = null
                viewModel.confirm(item, folderId)
            },
            onDismiss = { other = null },
        )
    }
}

@Composable
private fun FilingCard(item: MFilingItem, account: MAccount?, busy: Boolean, onConfirm: () -> Unit, onOther: () -> Unit, onDismiss: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FileBadge(item.name, "")
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (account != null) MailDot(accountColor(account))
                    Text(
                        listOfNotNull(item.from?.label, item.date?.let { mailTime(it) }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.muted,
                        maxLines = 1,
                    )
                }
            }
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        item.suggestion?.let { s ->
            Text(
                stringResource(R.string.mail_filing_suggestion, s.path ?: s.where ?: "", reasonText(s.reason, s.confidence)),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.suggestion != null) GcPrimaryButton(stringResource(R.string.mail_filing_confirm), onClick = onConfirm, enabled = !busy, fillWidth = false, minHeight = 40.dp)
            GcOutlineButton(stringResource(R.string.mail_drive_other), onClick = onOther, enabled = !busy, fillWidth = false, minHeight = 40.dp)
            GcOutlineButton(stringResource(R.string.mail_filing_dismiss), onClick = onDismiss, enabled = !busy, fillWidth = false, minHeight = 40.dp)
        }
    }
}

/** "Postausgang": planned and failed sends; "Bearbeiten" stops one and opens its draft. */
@Composable
fun MailOutboxScreen(onBack: () -> Unit, onDraft: (Long) -> Unit, viewModel: MailOutboxViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val me by viewModel.me.collectAsStateWithLifecycle()
    val open by viewModel.open.collectAsStateWithLifecycle()
    val toastText by viewModel.toast.collectAsStateWithLifecycle()
    val context = mailContext()
    val extra = GateControlTheme.extraColors
    LaunchedEffect(open) {
        open?.let {
            viewModel.opened()
            onDraft(it)
        }
    }
    LaunchedEffect(toastText) {
        toastText?.let {
            toast(context, it)
            viewModel.toastShown()
        }
    }
    Column(Modifier.fillMaxSize()) {
        GcSubpageBar(title = stringResource(R.string.mail_outbox), onBack = onBack, backDescription = stringResource(R.string.mail_back))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                state.loading -> item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                state.error != null && state.items.isEmpty() -> item { ErrorBox(mailText(context, state.error), onRetry = viewModel::load) }
                state.items.isEmpty() -> item { ErrorBox(stringResource(R.string.mail_outbox_empty), onRetry = null) }
            }
            items(state.items, key = { it.id }) { o ->
                val account = me?.account(o.accountId)
                GcCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MailDot(accountColor(account))
                        Text(o.subject.ifEmpty { stringResource(R.string.mail_no_subject) }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        when (o.state) {
                            "failed" -> stringResource(R.string.mail_outbox_failed) + (o.error?.let { " · " + mailText(context, it) } ?: "")
                            "sending" -> stringResource(R.string.mail_outbox_sending)
                            else -> o.sendAt?.let { stringResource(R.string.mail_planned_for, mailDateTime(it)) } ?: stringResource(R.string.mail_outbox_waiting)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (o.state == "failed") MaterialTheme.colorScheme.error else extra.muted,
                    )
                    if (o.state != "sending") {
                        TextButton(onClick = { viewModel.cancel(o) }) { Text(stringResource(R.string.mail_outbox_edit)) }
                    }
                }
            }
        }
    }
}

/** The search: words, "Ungelesen", "Mit Anhang", an account; the server searches its copy and, when needed, the provider. */
@Composable
fun MailSearchScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, viewModel: MailSearchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val me by viewModel.me.collectAsStateWithLifecycle()
    val context = mailContext()
    val extra = GateControlTheme.extraColors
    Column(Modifier.fillMaxSize().imePadding()) {
        GcSubpageBar(title = stringResource(R.string.mail_search), onBack = onBack, backDescription = stringResource(R.string.mail_back))
        GcTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            label = stringResource(R.string.mail_search_hint),
            modifier = Modifier.padding(horizontal = 16.dp),
            trailingIcon = { if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
        )
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GcFilterChip(stringResource(R.string.mail_filter_unread), selected = state.unread, onClick = viewModel::toggleUnread)
            GcFilterChip(stringResource(R.string.mail_filter_attachments), selected = state.attachments, onClick = viewModel::toggleAttachments)
        }
        if ((me?.accounts?.size ?: 0) > 1) {
            Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GcFilterChip(stringResource(R.string.mail_box_all), selected = state.accountId == null, onClick = { viewModel.setAccount(null) })
                me?.accounts?.forEach { a -> GcFilterChip(a.label, selected = state.accountId == a.id, onClick = { viewModel.setAccount(a.id) }) }
            }
        }
        if (state.remote) Text(stringResource(R.string.mail_search_remote), style = MaterialTheme.typography.bodySmall, color = extra.muted, modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(Modifier.weight(1f)) {
            if (state.error != null && state.items.isEmpty()) item { ErrorBox(mailText(context, state.error), onRetry = null) }
            if (state.error == null && !state.busy && state.items.isEmpty() && state.query.length >= 2) {
                item { ErrorBox(stringResource(R.string.mail_search_none), onRetry = null) }
            }
            items(state.items, key = { it.id }) { item ->
                MailRow(item = item, account = me?.account(item.accountId), onClick = { onOpen(item.id) }, onLongClick = { onOpen(item.id) })
                MailDivider()
            }
            if (state.next != null) item { TextButton(onClick = viewModel::more, modifier = Modifier.padding(16.dp)) { Text(stringResource(R.string.mail_more_results)) } }
        }
    }
}

/** The label of a swipe setting. */
@Composable
private fun swipeLabel(v: String): String = stringResource(
    when (v) {
        MSettings.SWIPE_ARCHIVE -> R.string.mail_archive
        MSettings.SWIPE_DELETE -> R.string.mail_delete
        MSettings.SWIPE_READ -> R.string.mail_swipe_read
        else -> R.string.mail_swipe_none
    },
)

/** "Mail-Einstellungen": conversations, the swipes, "Darstellung" (mails dark), the accounts and the storage. */
@Composable
fun MailSettingsScreen(
    onBack: () -> Unit,
    onAccount: (Long) -> Unit,
    onAdd: () -> Unit,
    viewModel: MailSettingsViewModel = hiltViewModel(),
    display: MailDisplayViewModel = hiltViewModel(),
) {
    val me by viewModel.me.collectAsStateWithLifecycle()
    val darkMails by display.darkMails.collectAsStateWithLifecycle()
    val toastText by viewModel.toast.collectAsStateWithLifecycle()
    val context = mailContext()
    LaunchedEffect(toastText) {
        toastText?.let {
            toast(context, it)
            viewModel.toastShown()
        }
    }
    Column(Modifier.fillMaxSize()) {
        GcSubpageBar(title = stringResource(R.string.mail_settings), onBack = onBack, backDescription = stringResource(R.string.mail_back))
        val m = me
        if (m == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GcSectionLabel(stringResource(R.string.mail_settings_list))
            GcCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                GcSwitchRow(
                    label = stringResource(R.string.mail_settings_threads),
                    description = stringResource(R.string.mail_settings_threads_text),
                    checked = m.settings.threads,
                    onCheckedChange = { viewModel.put(threads = it) },
                )
            }
            listOf(true, false).forEach { left ->
                Text(stringResource(if (left) R.string.mail_settings_swipe_left else R.string.mail_settings_swipe_right), style = MaterialTheme.typography.labelLarge)
                GcSegmented(
                    options = MSettings.SWIPES.map { it to swipeLabel(it) },
                    selected = if (left) m.settings.swipeLeft else m.settings.swipeRight,
                    onSelect = { v -> if (left) viewModel.put(swipeLeft = v) else viewModel.put(swipeRight = v) },
                )
            }
            GcSectionLabel(stringResource(R.string.mail_settings_display))
            GcCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                GcSwitchRow(
                    label = stringResource(R.string.mail_settings_dark_mails),
                    description = stringResource(R.string.mail_settings_dark_mails_text),
                    checked = darkMails,
                    onCheckedChange = display::setDarkMails,
                )
            }
            GcSectionLabel(stringResource(R.string.mail_accounts))
            GcCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                m.accounts.forEach { a ->
                    GcListRow(
                        title = a.label,
                        description = a.email,
                        onClick = { onAccount(a.id) },
                        leading = { MailAccountAvatar(a, size = 36.dp) },
                        trailing = { GcChevron() },
                    )
                }
                GcListRow(title = stringResource(R.string.mail_add_account), onClick = onAdd, leading = { Icon(GcIcons.Plus, contentDescription = null) })
            }
            com.gatecontrol.android.mail.MailLogic.quotaPercent(m.limits.usedBytes, m.limits.quotaBytes)?.let { p ->
                GcSectionLabel(stringResource(R.string.mail_storage))
                GcCard {
                    Text(stringResource(R.string.mail_quota, mailSize(m.limits.usedBytes), mailSize(m.limits.quotaBytes)), style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
                    Text(pluralStringResource(R.plurals.mail_storage_text, m.limits.retentionMonths, m.limits.retentionMonths), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
                }
            }
        }
    }
}

/** One account: name and color, kind, push, AI, attachments to Drive, signature, password or "Neu anmelden", remove. */
@Composable
fun MailAccountScreen(onBack: () -> Unit, onRelogin: (Long) -> Unit, viewModel: MailAccountViewModel = hiltViewModel()) {
    val me by viewModel.me.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val gone by viewModel.gone.collectAsStateWithLifecycle()
    val toastText by viewModel.toast.collectAsStateWithLifecycle()
    val context = mailContext()
    val extra = GateControlTheme.extraColors
    LaunchedEffect(gone) { if (gone) onBack() }
    LaunchedEffect(toastText) {
        toastText?.let {
            toast(context, it)
            viewModel.toastShown()
        }
    }
    val a = me?.account(viewModel.id)
    Column(Modifier.fillMaxSize().imePadding()) {
        GcSubpageBar(title = a?.label ?: stringResource(R.string.mail_account), onBack = onBack, backDescription = stringResource(R.string.mail_back))
        if (a == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (me == null) CircularProgressIndicator() else Text(stringResource(R.string.mail_err_not_found)) }
            return@Column
        }
        var name by rememberSaveable(a.id) { mutableStateOf(a.name.orEmpty()) }
        var signature by rememberSaveable(a.id) { mutableStateOf(a.signature.orEmpty()) }
        var password by rememberSaveable(a.id) { mutableStateOf("") }
        var askRemove by remember { mutableStateOf(false) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MailAccountAvatar(a, size = 56.dp, ring = MaterialTheme.colorScheme.background)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(a.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (a.label != a.email) Text(a.email, style = MaterialTheme.typography.bodyMedium, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            when (a.state) {
                MAccount.STATE_AUTH_FAILED -> GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
                    Text(stringResource(R.string.mail_account_auth_failed, a.label), style = MaterialTheme.typography.bodySmall)
                }
                MAccount.STATE_UNREACHABLE -> GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
                    Text(stringResource(R.string.mail_account_unreachable, a.label), style = MaterialTheme.typography.bodySmall)
                }
                MAccount.STATE_SYNCING -> GcBanner(tone = GcTone.Info, icon = GcIcons.Refresh) {
                    Text(stringResource(R.string.mail_account_syncing), style = MaterialTheme.typography.bodySmall)
                }
            }
            GcSectionLabel(stringResource(R.string.mail_name_color))
            GcTextField(value = name, onValueChange = { name = it }, label = stringResource(R.string.mail_name))
            if (name != a.name.orEmpty()) TextButton(onClick = { viewModel.patch(name = name) }, enabled = !busy) { Text(stringResource(R.string.mail_save)) }
            ColorChoice(a.color) { viewModel.patch(color = it) }
            GcSegmented(
                options = listOf(MAccount.KIND_PERSONAL to stringResource(R.string.mail_kind_personal), MAccount.KIND_BUSINESS to stringResource(R.string.mail_kind_business)),
                selected = a.kind,
                onSelect = { viewModel.patch(kind = it) },
            )
            GcSectionLabel(stringResource(R.string.mail_push))
            GcSegmented(
                options = listOf("off" to stringResource(R.string.mail_push_off), "relevant" to stringResource(R.string.mail_push_relevant), "all" to stringResource(R.string.mail_push_all)),
                selected = a.push,
                onSelect = { viewModel.patch(push = it) },
            )
            if (me?.has(MMe.FEATURE_AI) == true) {
                GcCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                    GcSwitchRow(label = stringResource(R.string.mail_account_ai), description = stringResource(R.string.mail_account_ai_text), checked = a.ai, onCheckedChange = { viewModel.patch(ai = it) })
                }
            }
            if (me?.has(MMe.FEATURE_DRIVE) == true) {
                GcSectionLabel(stringResource(R.string.mail_account_attachments))
                GcSegmented(
                    options = listOf("off" to stringResource(R.string.mail_attachments_off), "suggest" to stringResource(R.string.mail_attachments_suggest), "auto" to stringResource(R.string.mail_attachments_auto)),
                    selected = a.attachments,
                    onSelect = { viewModel.patch(attachments = it) },
                )
            }
            GcSectionLabel(stringResource(R.string.mail_signature))
            GcTextField(value = signature, onValueChange = { signature = it }, label = stringResource(R.string.mail_signature), singleLine = false)
            if (signature != a.signature.orEmpty()) TextButton(onClick = { viewModel.patch(signature = signature) }, enabled = !busy) { Text(stringResource(R.string.mail_save)) }
            GcSectionLabel(stringResource(R.string.mail_signin))
            if (a.microsoft) {
                GcOutlineButton(stringResource(R.string.mail_relogin), onClick = { onRelogin(a.id) })
            } else {
                GcTextField(value = password, onValueChange = { password = it }, label = stringResource(R.string.mail_new_password), visualTransformation = PasswordVisualTransformation())
                if (password.isNotEmpty()) {
                    GcPrimaryButton(stringResource(R.string.mail_save_password), onClick = { viewModel.patch(password = password); password = "" }, loading = busy, enabled = !busy)
                }
            }
            GcOutlineButton(stringResource(R.string.mail_account_remove), onClick = { askRemove = true }, enabled = !busy)
        }
        if (askRemove) {
            AlertDialog(
                onDismissRequest = { askRemove = false },
                title = { Text(stringResource(R.string.mail_account_remove_title, a.label)) },
                text = { Text(stringResource(R.string.mail_account_remove_text)) },
                confirmButton = { TextButton(onClick = { askRemove = false; viewModel.remove() }) { Text(stringResource(R.string.mail_account_remove), color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { askRemove = false }) { Text(stringResource(R.string.mail_cancel)) } },
            )
        }
    }
}
