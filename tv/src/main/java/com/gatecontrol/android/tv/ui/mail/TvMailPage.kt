package com.gatecontrol.android.tv.ui.mail

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.gatecontrol.android.mail.MAddress
import com.gatecontrol.android.mail.MAttachment
import com.gatecontrol.android.mail.MMessage
import com.gatecontrol.android.mail.MSummary
import com.gatecontrol.android.mail.MailBulk
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.input.TvHandoff
import com.gatecontrol.android.tv.input.TvSnippetSource
import com.gatecontrol.android.tv.mail.TvAttachmentKind
import com.gatecontrol.android.tv.mail.TvMailCompose
import com.gatecontrol.android.tv.mail.TvMailLogic
import com.gatecontrol.android.tv.mail.TvMailMessage
import com.gatecontrol.android.tv.mail.TvMailMode
import com.gatecontrol.android.tv.mail.TvMailUi
import com.gatecontrol.android.tv.ui.components.TvButton
import com.gatecontrol.android.tv.ui.components.TvButtonStyle
import com.gatecontrol.android.tv.ui.drive.TvLocalFile
import com.gatecontrol.android.tv.ui.drive.requestSoon
import com.gatecontrol.android.tv.ui.home.TvIcons
import com.gatecontrol.android.tv.ui.input.TvComposer
import com.gatecontrol.android.tv.ui.input.TvInputField
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import com.gatecontrol.android.tv.zuhause.TvSourceState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** What the page „Mail“ does with the remote. */
data class TvMailActions(
    val onStart: () -> Unit = {},
    val onSelect: (MSummary) -> Unit = {},
    val onAnswer: (TvMailMode, Long) -> Unit = { _, _ -> },
    val onSearch: (String) -> Unit = {},
    val onPick: (MAddress) -> Unit = {},
    /** „Senden“ in the composer's preview → whether it went. */
    val send: suspend (String) -> Boolean = { false },
    val onCloseCompose: () -> Unit = {},
    val onUndoSend: () -> Unit = {},
    val onArchive: (Long) -> Unit = {},
    val onDelete: (Long) -> Unit = {},
    val onUndoMove: () -> Unit = {},
    val sources: (Long) -> List<TvSnippetSource> = { emptyList() },
    val handoff: (TvMailCompose.Writing) -> TvHandoff? = { null },
    val attachment: suspend (Long, MAttachment) -> File? = { _, _ -> null },
    val picture: suspend (String) -> Pair<ByteArray, String>? = { null },
)

/** How long the focus rests on a row before the mail loads on the right (leafing through loads nothing). */
private const val SELECT_DELAY_MS = 300L

/**
 * The tab „Mail“ of „Nachrichten“ (mockup „4 · Mail“): on the left the inbox of every account chosen on the TV, on the
 * right the chosen mail in large type with „Kurz gesagt“ of the AI and its attachments (photos and PDFs open in the
 * viewer). ☰ shows its HTML (external pictures blocked as on the phone). A mail counts as read after 3 s. Antworten,
 * Allen antworten, Weiterleiten go through the plugin's drafts and the composer (preview, then 10 s „Rückgängig“);
 * Archivieren and Löschen have „Rückgängig“ too. No new mails to typed addresses.
 */
@Composable
fun TvMailPage(ui: TvMailUi, actions: TvMailActions, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) { actions.onStart() }
    var focused by remember { mutableStateOf<MSummary?>(null) }
    var html by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<TvMailOpened?>(null) }
    val reply = remember { FocusRequester() }
    LaunchedEffect(focused?.id) {
        val f = focused ?: return@LaunchedEffect
        delay(SELECT_DELAY_MS)
        actions.onSelect(f)
    }
    Column(
        modifier
            .fillMaxSize()
            .padding(end = 30.dp, bottom = 12.dp)
            .onPreviewKeyEvent { e ->
                val m = ui.message
                if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU && m != null && TvMailLogic.hasHtml(m)) {
                    html = true
                    true
                } else {
                    false
                }
            },
    ) {
        when {
            ui.meState == TvSourceState.MISSING && ui.allAccounts.isEmpty() -> Note(stringResource(R.string.tv_mail_missing))
            ui.meState == TvSourceState.OFFLINE && ui.allAccounts.isEmpty() -> Note(stringResource(R.string.tv_home_offline))
            ui.meState == TvSourceState.LOADING && ui.allAccounts.isEmpty() -> Note(stringResource(R.string.tv_home_loading))
            ui.accounts.isEmpty() -> Note(stringResource(R.string.tv_mail_none_chosen))
            else -> Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MailList(
                    ui,
                    onFocus = { focused = it },
                    onOpen = {
                        actions.onSelect(it)
                        // OK: to the mail's buttons on the right
                        focused = it
                    },
                    reply = reply,
                    modifier = Modifier.width(340.dp),
                )
                Reader(ui, actions, reply = reply, onHtml = { html = true }, onOpened = { opened = it }, modifier = Modifier.weight(1f))
            }
        }
        Bars(ui, actions)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            KeyHint("OK", stringResource(R.string.tv_mail_key_open))
            KeyHint("▲ ▼", stringResource(R.string.tv_mail_key_scroll))
            if (ui.message?.let(TvMailLogic::hasHtml) == true) KeyHint("☰", stringResource(R.string.tv_mail_html))
            KeyHint("◀ ▶", stringResource(R.string.tv_home_key_column))
        }
    }
    val m = ui.message
    if (html && m != null) TvMailHtmlDialog(m, actions.picture, onClose = { html = false })
    opened?.let { TvMailViewer(it, onClose = { opened = null }) }
    Compose(ui, actions)
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = GateControlTvTheme.colors.muted, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun KeyHint(key: String, text: String) {
    val colors = GateControlTvTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.background(colors.panel2, RoundedCornerShape(5.dp)).border(BorderStroke(1.dp, colors.line2), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) {
            Text(key, style = MaterialTheme.typography.labelMedium, color = colors.text)
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = colors.muted)
    }
}

/** „14:05“ today, „Mo. 23.11.“ before (the date as the list's line). */
internal fun mailTime(date: Long, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    val at = Instant.ofEpochMilli(date).atZone(zone)
    return when {
        at.toLocalDate() == today -> at.format(DateTimeFormatter.ofPattern("HH:mm", locale))
        at.year == today.year -> at.format(DateTimeFormatter.ofPattern(if (locale.language == "de") "EE d.M." else "EE M/d", locale))
        else -> at.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale))
    }
}

// ─── left: the inbox ────────────────────────────────────────────────────

@Composable
private fun MailList(ui: TvMailUi, onFocus: (MSummary) -> Unit, onOpen: (MSummary) -> Unit, reply: FocusRequester, modifier: Modifier) {
    val colors = GateControlTvTheme.colors
    val first = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(ui.items.isNotEmpty()) { if (ui.items.isNotEmpty()) first.requestSoon() }
    Column(modifier.fillMaxHeight()) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
            Text(stringResource(R.string.tv_mail_inbox), style = MaterialTheme.typography.titleMedium, color = colors.text)
            if (ui.unread > 0) Text(pluralStringResource(R.plurals.tv_mail_unread, ui.unread, ui.unread), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        when {
            ui.items.isNotEmpty() -> LazyColumn(contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                items(ui.items, key = { it.id }) { s ->
                    MailLine(
                        s,
                        account = ui.accounts.takeIf { it.size > 1 }?.firstOrNull { it.id == s.accountId }?.label,
                        selected = s.id == ui.selectedId,
                        onClick = {
                            onOpen(s)
                            scope.launch { reply.requestSoon() }
                        },
                        modifier = (if (s.id == ui.items.first().id) Modifier.focusRequester(first) else Modifier)
                            .onFocusChanged { if (it.isFocused) onFocus(s) },
                    )
                }
            }
            ui.listState == TvSourceState.OK -> Note(stringResource(R.string.tv_mail_empty))
            ui.listState == TvSourceState.OFFLINE -> Note(stringResource(R.string.tv_home_offline))
            ui.listState == TvSourceState.LOADING -> Note(stringResource(R.string.tv_home_loading))
            else -> Note(stringResource(R.string.tv_mail_missing))
        }
    }
}

/** A focusable line with the TV focus (ring, glow, slight zoom); [selected]: the mail shown on the right. */
@Composable
private fun Line(onClick: () -> Unit, description: String, modifier: Modifier = Modifier, selected: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val colors = GateControlTvTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = description },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) colors.panel2 else colors.panel,
            contentColor = colors.text,
            focusedContainerColor = colors.panel2,
            focusedContentColor = colors.text,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (selected) colors.line2 else colors.line), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, colors.focus), shape = shape),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(elevationColor = colors.focusGlow, elevation = 10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(
            Modifier.defaultMinSize(minHeight = 36.dp).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            content = content,
        )
    }
}

@Composable
private fun MailLine(s: MSummary, account: String?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = GateControlTvTheme.colors
    val subject = s.subject.ifBlank { stringResource(R.string.tv_mail_no_subject) }
    val description = listOfNotNull(
        s.from.label,
        subject,
        if (s.unread) stringResource(R.string.tv_mail_unread_one) else null,
        if (s.hasAttachments) stringResource(R.string.tv_mail_attachments) else null,
    ).joinToString(", ")
    Line(onClick = onClick, description = description, modifier = modifier, selected = selected) {
        Box(Modifier.size(8.dp).background(if (s.unread) colors.accent else Color.Transparent, CircleShape))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    s.from.label,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (s.unread) FontWeight.Bold else FontWeight.Normal),
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (s.hasAttachments) Icon(TvIcons.File, contentDescription = null, tint = colors.muted, modifier = Modifier.size(12.dp))
                Text(mailTime(s.date), style = MaterialTheme.typography.labelSmall, color = colors.muted, maxLines = 1)
            }
            Text(subject, style = MaterialTheme.typography.bodySmall, color = if (s.unread) colors.text else colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (account != null) Text(account, style = MaterialTheme.typography.labelSmall, color = colors.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ─── right: the mail ────────────────────────────────────────────────────

@Composable
private fun Reader(ui: TvMailUi, actions: TvMailActions, reply: FocusRequester, onHtml: () -> Unit, onOpened: (TvMailOpened) -> Unit, modifier: Modifier) {
    val colors = GateControlTvTheme.colors
    val m = ui.message
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            ui.selectedId == null -> Note(stringResource(R.string.tv_mail_pick))
            m == null && ui.messageState == TvSourceState.LOADING -> Note(stringResource(R.string.tv_home_loading))
            m == null && ui.messageState == TvSourceState.OFFLINE -> Note(stringResource(R.string.tv_home_offline))
            m == null -> Note(stringResource(R.string.tv_mail_message_missing))
            else -> {
                Head(m)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val busy = ui.compose != TvMailCompose.Closed
                    TvButton(stringResource(R.string.tv_mail_reply), onClick = { actions.onAnswer(TvMailMode.REPLY, m.id) }, style = TvButtonStyle.Primary, enabled = !busy, modifier = Modifier.focusRequester(reply))
                    if ((m.summary.to + m.cc).size > 1) {
                        TvButton(stringResource(R.string.tv_mail_reply_all), onClick = { actions.onAnswer(TvMailMode.REPLY_ALL, m.id) }, enabled = !busy)
                    }
                    TvButton(stringResource(R.string.tv_mail_forward), onClick = { actions.onAnswer(TvMailMode.FORWARD, m.id) }, enabled = !busy)
                    TvButton(stringResource(R.string.tv_mail_archive), onClick = { actions.onArchive(m.id) })
                    TvButton(stringResource(R.string.tv_mail_delete), onClick = { actions.onDelete(m.id) }, style = TvButtonStyle.Warn)
                    if (TvMailLogic.hasHtml(m)) TvButton(stringResource(R.string.tv_mail_html), onClick = onHtml)
                }
                ui.ai?.summary?.takeIf { it.isNotBlank() }?.let { short ->
                    Column(
                        Modifier.fillMaxWidth().background(colors.infoBg, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(stringResource(R.string.tv_mail_short), style = MaterialTheme.typography.labelMedium, color = colors.info)
                        Text(short, style = MaterialTheme.typography.bodyLarge, color = colors.text)
                    }
                }
                Body(m, Modifier.weight(1f))
                Attachments(m, actions, onOpened)
            }
        }
    }
}

@Composable
private fun Head(m: MMessage) {
    val colors = GateControlTvTheme.colors
    Text(m.summary.subject.ifBlank { stringResource(R.string.tv_mail_no_subject) }, style = MaterialTheme.typography.headlineSmall, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(m.summary.from.label, style = MaterialTheme.typography.titleMedium, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (m.summary.from.name != null) Text(m.summary.from.email, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(mailTime(m.summary.date), style = MaterialTheme.typography.bodySmall, color = colors.muted)
    }
    val to = (m.summary.to + m.cc).map { it.label }
    if (to.isNotEmpty()) {
        Text(stringResource(R.string.tv_mail_to, to.joinToString(", ")), style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The text in large type: focused, ▲ ▼ scroll it (at the top ▲ goes on to the buttons). */
@Composable
private fun Body(m: MMessage, modifier: Modifier) {
    val colors = GateControlTvTheme.colors
    val text = remember(m.id, m.text, m.html) { TvMailLogic.body(m) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(m.id) { scroll.scrollTo(0) }
    Box(
        modifier
            .fillMaxWidth()
            .background(colors.panel, RoundedCornerShape(10.dp))
            .border(BorderStroke(if (focused) 2.dp else 1.dp, if (focused) colors.focus else colors.line), RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val step = SCROLL_STEP
                when (e.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (scroll.value < scroll.maxValue) {
                        scope.launch { scroll.animateScrollBy(step) }
                        true
                    } else {
                        false
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> if (scroll.value > 0) {
                        scope.launch { scroll.animateScrollBy(-step) }
                        true
                    } else {
                        false
                    }
                    else -> false
                }
            }
            .focusable()
            .semantics { contentDescription = text.take(200) },
    ) {
        Text(
            text.ifBlank { stringResource(R.string.tv_mail_no_text) },
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
            color = colors.text,
            modifier = Modifier.verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

private const val SCROLL_STEP = 260f

@Composable
private fun Attachments(m: MMessage, actions: TvMailActions, onOpened: (TvMailOpened) -> Unit) {
    val colors = GateControlTvTheme.colors
    val list = TvMailLogic.attachments(m)
    if (list.isEmpty()) return
    val scope = rememberCoroutineScope()
    var loading by remember(m.id) { mutableStateOf<Long?>(null) }
    var failed by remember(m.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.tv_mail_attachments), style = MaterialTheme.typography.labelMedium, color = colors.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (a in list) {
                val kind = TvMailLogic.kind(a)
                if (TvMailLogic.canOpen(a)) {
                    TvButton(a.name, enabled = loading == null, onClick = {
                        loading = a.id
                        failed = false
                        scope.launch {
                            val file = actions.attachment(m.id, a)
                            val opened = file?.let { f ->
                                when (kind) {
                                    TvAttachmentKind.PDF -> TvPdfPages.open(f, a.name)?.let { TvMailOpened.Pdf(it) }
                                    else -> TvMailOpened.Photo(TvLocalFile(f, a.name, a.mime))
                                }
                            }
                            loading = null
                            if (opened != null) onOpened(opened) else failed = true
                        }
                    })
                } else {
                    // only the name: other files, or blocked by the virus scan
                    val why = if (a.scan?.blocked == true || a.scan?.removed == true) stringResource(R.string.tv_mail_attachment_blocked) else stringResource(R.string.tv_mail_attachment_phone)
                    Text(
                        "${a.name} · $why",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                        modifier = Modifier.background(colors.panel2, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
        when {
            loading != null -> Text(stringResource(R.string.tv_mail_attachment_loading), style = MaterialTheme.typography.bodySmall, color = colors.muted)
            failed -> Text(stringResource(R.string.tv_mail_attachment_failed), style = MaterialTheme.typography.bodySmall, color = colors.warn)
        }
    }
}

// ─── bottom: „Rückgängig“ and messages ──────────────────────────────────

@Composable
private fun Bars(ui: TvMailUi, actions: TvMailActions) {
    val colors = GateControlTvTheme.colors
    ui.sent?.let {
        UndoBar(stringResource(R.string.tv_mail_sending), actions.onUndoSend)
    }
    ui.moved?.let { mv ->
        UndoBar(stringResource(if (mv.action == MailBulk.Action.ARCHIVE) R.string.tv_mail_note_archived else R.string.tv_mail_note_deleted), actions.onUndoMove)
    }
    val note = ui.note
    if (note != null && !(ui.moved != null && (note == TvMailMessage.ARCHIVED || note == TvMailMessage.DELETED))) {
        Text(
            stringResource(
                when (note) {
                    TvMailMessage.SENT -> R.string.tv_mail_note_sent
                    TvMailMessage.ARCHIVED -> R.string.tv_mail_note_archived
                    TvMailMessage.DELETED -> R.string.tv_mail_note_deleted
                    TvMailMessage.BACK -> R.string.tv_mail_note_back
                    TvMailMessage.SEND_STOPPED -> R.string.tv_mail_note_stopped
                    TvMailMessage.TOO_LATE -> R.string.tv_mail_note_too_late
                    TvMailMessage.REFUSED -> R.string.tv_mail_note_refused
                    TvMailMessage.OFFLINE -> R.string.tv_mail_note_offline
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = when (note) {
                TvMailMessage.REFUSED, TvMailMessage.OFFLINE, TvMailMessage.TOO_LATE -> colors.warn
                else -> colors.accentText
            },
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun UndoBar(text: String, onUndo: () -> Unit) {
    val colors = GateControlTvTheme.colors
    Row(
        Modifier.padding(top = 8.dp).fillMaxWidth().background(colors.panel2, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        TvButton(stringResource(R.string.tv_home_undo), onClick = onUndo, style = TvButtonStyle.Primary)
    }
}

// ─── answering ──────────────────────────────────────────────────────────

@Composable
private fun Compose(ui: TvMailUi, actions: TvMailActions) {
    when (val c = ui.compose) {
        TvMailCompose.Closed -> Unit
        is TvMailCompose.Preparing -> FullScreen {
            Note(stringResource(R.string.tv_mail_preparing))
        }
        is TvMailCompose.Picking -> FullScreen { ForwardPicker(c, actions) }
        is TvMailCompose.Writing -> {
            val subject = c.draft.subject.ifBlank { stringResource(R.string.tv_mail_no_subject) }
            val to = (c.to ?: (c.draft.to + c.draft.cc)).map { it.label }.distinct().joinToString(", ")
            val title = when (c.mode) {
                TvMailMode.REPLY -> stringResource(R.string.tv_mail_compose_reply, to)
                TvMailMode.REPLY_ALL -> stringResource(R.string.tv_mail_compose_reply_all)
                TvMailMode.FORWARD -> stringResource(R.string.tv_mail_compose_forward, to)
            }
            val colors = GateControlTvTheme.colors
            // a new key after „Rückgängig“: the composer starts again with the text sent
            FullScreen(padded = false) {
                androidx.compose.runtime.key(c.draft.id, c.initial) {
                    TvComposer(
                        title = title,
                        onSend = actions.send,
                        onClose = actions.onCloseCompose,
                        label = stringResource(R.string.tv_mail_compose_label),
                        previewMeta = stringResource(R.string.tv_mail_compose_meta, to, subject),
                        initial = c.initial,
                        sources = remember(c.messageId) { actions.sources(c.messageId) },
                        handoff = remember(c) { actions.handoff(c) },
                        modifier = Modifier.fillMaxSize().background(colors.bg).padding(horizontal = 36.dp, vertical = 24.dp),
                    ) {
                        Text(stringResource(R.string.tv_mail_to, to), style = MaterialTheme.typography.bodyMedium, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(subject, style = MaterialTheme.typography.bodyMedium, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val n = c.draft.attachments.size
                        if (n > 0) Text(pluralStringResource(R.plurals.tv_mail_forward_attachments, n, n), style = MaterialTheme.typography.bodySmall, color = colors.faint)
                        Text(stringResource(R.string.tv_mail_compose_quote), style = MaterialTheme.typography.bodySmall, color = colors.faint)
                    }
                }
            }
        }
    }
}

@Composable
private fun FullScreen(padded: Boolean = true, content: @Composable () -> Unit) {
    val colors = GateControlTvTheme.colors
    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Box(Modifier.fillMaxSize().background(colors.bg).then(if (padded) Modifier.padding(horizontal = 36.dp, vertical = 24.dp) else Modifier)) {
            content()
        }
    }
}

/** A forward: whom to — people Mail knows (own contacts, people of this GateControl, written with lately), by name. */
@Composable
private fun ForwardPicker(c: TvMailCompose.Picking, actions: TvMailActions) {
    val colors = GateControlTvTheme.colors
    var query by rememberSaveable { mutableStateOf(c.query) }
    val first = remember { FocusRequester() }
    androidx.activity.compose.BackHandler { actions.onCloseCompose() }
    LaunchedEffect(c.targets?.isNotEmpty() == true) { if (c.targets?.isNotEmpty() == true) first.requestSoon() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.tv_mail_forward_title), style = MaterialTheme.typography.headlineSmall, color = colors.text)
        Text(stringResource(R.string.tv_mail_forward_hint), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(420.dp)) {
                TvInputField(
                    value = query,
                    onValueChange = { query = it },
                    label = stringResource(R.string.tv_mail_forward_search),
                    maxLength = 100,
                    onDone = { actions.onSearch(query) },
                )
            }
            TvButton(stringResource(R.string.tv_mail_forward_find), onClick = { actions.onSearch(query) })
            TvButton(stringResource(R.string.tv_cancel), onClick = actions.onCloseCompose)
        }
        val targets = c.targets
        when {
            targets == null -> Note(stringResource(R.string.tv_home_loading))
            targets.isEmpty() -> Note(stringResource(R.string.tv_mail_forward_none))
            else -> LazyColumn(Modifier.width(520.dp), contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                items(targets, key = { it.email }) { t ->
                    Line(
                        onClick = { actions.onPick(t) },
                        description = listOfNotNull(t.name, t.email).joinToString(", "),
                        modifier = if (t == targets.first()) Modifier.focusRequester(first) else Modifier,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(t.label, style = MaterialTheme.typography.labelLarge, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (t.name != null) Text(t.email, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
