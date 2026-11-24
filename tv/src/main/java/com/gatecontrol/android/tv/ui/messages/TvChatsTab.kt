package com.gatecontrol.android.tv.ui.messages

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.gatecontrol.android.chat.ChatConversation
import com.gatecontrol.android.chat.ChatMessage
import com.gatecontrol.android.chat.ChatPerson
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.chat.TvChatBlock
import com.gatecontrol.android.tv.chat.TvChatLogic
import com.gatecontrol.android.tv.chat.TvChatPending
import com.gatecontrol.android.tv.chat.TvChatSendError
import com.gatecontrol.android.tv.chat.TvChatShow
import com.gatecontrol.android.tv.chat.TvChatStatus
import com.gatecontrol.android.tv.chat.TvChatsUi
import com.gatecontrol.android.tv.input.TvHandoff
import com.gatecontrol.android.tv.input.TvSnippet
import com.gatecontrol.android.tv.input.TvSnippetKind
import com.gatecontrol.android.tv.input.TvSnippetSource
import com.gatecontrol.android.tv.ui.components.TvButton
import com.gatecontrol.android.tv.ui.components.TvButtonStyle
import com.gatecontrol.android.tv.ui.components.TvDialog
import com.gatecontrol.android.tv.ui.drive.requestSoon
import com.gatecontrol.android.tv.ui.home.TvIcons
import com.gatecontrol.android.tv.ui.input.TvComposerDialog
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import com.gatecontrol.android.tv.zuhause.TvSourceState

/** What the tab „Chats“ does with the remote. */
data class TvChatsActions(
    val onStart: () -> Unit = {},
    val onOpen: (Long) -> Unit = {},
    /** The open chat is on the screen (true) or not any more — only then the TV sends read receipts. */
    val onReading: (Boolean) -> Unit = {},
    val onOlder: () -> Unit = {},
    /** Text, the message it answers (or null) and the clientId; true: sent. */
    val send: suspend (String, Long?, String) -> Boolean = { _, _, _ -> false },
    val onRetry: (String) -> Unit = {},
    val onResend: (ChatMessage) -> Unit = {},
    val onDrop: (String) -> Unit = {},
    val onReact: (ChatMessage, String) -> Unit = { _, _ -> },
    val suggestions: suspend (Long) -> List<String> = { emptyList() },
    val thumb: suspend (ChatMessage) -> Bitmap? = { null },
    /** OK on a photo: the viewer, from [Int] among the history's photos. */
    val onPhoto: (List<ChatMessage>, Int) -> Unit = { _, _ -> },
    /** The own building blocks (Einstellungen › Eingabe). */
    val ownSnippets: TvSnippetSource? = null,
    /** „Am Handy weiterschreiben“ for a chat. */
    val handoff: (Long) -> TvHandoff? = { null },
)

/** What the composer answers to: the chat, and the message when it is a reply. */
private data class TvAnswer(val conversationId: Long, val replyTo: ChatMessage?)

/**
 * The tab „Chats“ of Nachrichten (mockup „5 · Messenger“): left the chats the TV shows (a small W/S/T at a chat over a
 * bridge), right the history — texts, photos as a preview (OK shows them large), lines for voice messages, videos,
 * files and locations. „Antworten“ (and OK › Antworten on a message) opens the composer with quick answers, then
 * always the preview „So senden?“. ☰ on a message: 👍 ❤️ 😂. Over a bridge only text, only while it is „ok“.
 */
@Composable
fun TvChatsTab(ui: TvChatsUi, actions: TvChatsActions, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) { actions.onStart() }
    // the first chat opens by itself, so the right side is never empty
    LaunchedEffect(ui.chats.firstOrNull()?.id, ui.open == null) {
        if (ui.open == null) ui.chats.firstOrNull()?.let { actions.onOpen(it.id) }
    }
    var answer by remember { mutableStateOf<TvAnswer?>(null) }
    var menu by remember { mutableStateOf<ChatMessage?>(null) }
    var failed by remember { mutableStateOf<TvChatPending?>(null) }
    var focused by remember { mutableStateOf<ChatMessage?>(null) }
    Column(
        modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                val target = focused
                if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU && target != null) {
                    menu = target
                    true
                } else {
                    false
                }
            },
    ) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ChatList(ui, actions, Modifier.width(270.dp))
            val open = ui.open
            if (open == null) {
                Note(stringResource(if (ui.chats.isEmpty()) R.string.tv_chats_none_chosen else R.string.tv_chats_choose), Modifier.weight(1f))
            } else {
                History(
                    ui, open, actions,
                    onAnswer = { answer = TvAnswer(open.id, it) },
                    onMenu = { menu = it },
                    onFailed = { failed = it },
                    onFocus = { focused = it },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            KeyHint("OK", stringResource(R.string.tv_chats_key_ok))
            KeyHint("☰", stringResource(R.string.tv_chats_key_menu))
            KeyHint("◀ ▶", stringResource(R.string.tv_home_key_column))
        }
    }
    val open = ui.open
    menu?.let { m ->
        if (open == null) {
            menu = null
        } else {
            MessageMenu(
                m, open, ui.myId,
                onAnswer = {
                    menu = null
                    answer = TvAnswer(open.id, m)
                },
                onReact = { emoji ->
                    menu = null
                    actions.onReact(m, emoji)
                },
                onResend = {
                    menu = null
                    actions.onResend(m)
                },
                onDismiss = { menu = null },
            )
        }
    }
    failed?.let { p ->
        FailedDialog(
            p,
            onRetry = {
                failed = null
                actions.onRetry(p.clientId)
            },
            onDrop = {
                failed = null
                actions.onDrop(p.clientId)
            },
            onDismiss = { failed = null },
        )
    }
    answer?.let { a ->
        val c = ui.chats.firstOrNull { it.id == a.conversationId }
        if (c == null) {
            answer = null
        } else {
            Composer(c, a.replyTo, ui, actions, onClose = { answer = null })
        }
    }
}

// ─── left: the chats ────────────────────────────────────────────────────

@Composable
private fun ChatList(ui: TvChatsUi, actions: TvChatsActions, modifier: Modifier) {
    val first = remember { FocusRequester() }
    LaunchedEffect(ui.state == TvSourceState.OK && ui.chats.isNotEmpty()) { if (ui.chats.isNotEmpty()) first.requestSoon() }
    val unnamed = stringResource(R.string.tv_chats_unnamed)
    Column(modifier.fillMaxHeight()) {
        ColumnHead(stringResource(R.string.tv_messages_tab_chats), TvChatLogic.badge(ui.unread)?.let { stringResource(R.string.tv_chats_unread, it) })
        when {
            ui.chats.isNotEmpty() -> LazyColumn(contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                items(ui.chats, key = { it.id }) { c ->
                    ChatLine(
                        c, unnamed,
                        selected = c.id == ui.open?.id,
                        onClick = { actions.onOpen(c.id) },
                        modifier = if (c.id == ui.chats.first().id) Modifier.focusRequester(first) else Modifier,
                    )
                }
            }
            ui.state == TvSourceState.LOADING -> Note(stringResource(R.string.tv_home_loading))
            ui.state == TvSourceState.OFFLINE -> Note(stringResource(R.string.tv_home_offline))
            ui.state == TvSourceState.MISSING -> Note(stringResource(R.string.tv_chats_missing))
            else -> Note(stringResource(R.string.tv_chats_none_chosen))
        }
    }
}

@Composable
private fun ChatLine(c: ChatConversation, unnamed: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = GateControlTvTheme.colors
    val title = TvChatLogic.title(c, unnamed)
    val mark = TvChatLogic.bridgeMark(c.bridge?.network)
    Line(onClick = onClick, description = listOfNotNull(title, c.bridge?.label, c.unread.takeIf { it > 0 }?.toString()).joinToString(", "), modifier = modifier, selected = selected) {
        Avatar(ChatPerson.initialsOf(title), mark)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            c.lastMessage?.let { m ->
                Text(previewLine(m), style = MaterialTheme.typography.bodySmall, color = if (selected) colors.accentText else colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        TvChatLogic.badge(c.unread)?.let { TvCountBadge(it) }
    }
}

/** The line under a chat's name: the last text, or what it was (Foto, Sprachnachricht …). */
@Composable
private fun previewLine(m: ChatMessage): String = when (TvChatLogic.show(m)) {
    TvChatShow.TEXT -> m.text.orEmpty()
    else -> kindLine(m)
}

/** Round initials with the bridge's letter (W, S, T) at the corner. */
@Composable
private fun Avatar(initials: String, mark: Char?) {
    val colors = GateControlTvTheme.colors
    Box(Modifier.size(30.dp)) {
        Box(Modifier.size(28.dp).background(colors.panel2, CircleShape).border(BorderStroke(1.dp, colors.line2), CircleShape), contentAlignment = Alignment.Center) {
            Text(initials, style = MaterialTheme.typography.labelMedium, color = colors.text)
        }
        if (mark != null) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(13.dp).background(colors.info, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(mark.toString(), style = MaterialTheme.typography.labelSmall, color = colors.bg)
            }
        }
    }
}

// ─── right: the history ─────────────────────────────────────────────────

@Composable
private fun History(
    ui: TvChatsUi,
    c: ChatConversation,
    actions: TvChatsActions,
    onAnswer: (ChatMessage?) -> Unit,
    onMenu: (ChatMessage) -> Unit,
    onFailed: (TvChatPending) -> Unit,
    onFocus: (ChatMessage?) -> Unit,
    modifier: Modifier,
) {
    val colors = GateControlTvTheme.colors
    val unnamed = stringResource(R.string.tv_chats_unnamed)
    // read receipts only while this chat is really on the screen (the page shows, GateControl in front)
    val reading by rememberUpdatedState(actions.onReading)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(c.id, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> reading(true)
                Lifecycle.Event.ON_PAUSE -> reading(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) reading(true)
        onDispose {
            lifecycle.removeObserver(observer)
            reading(false)
        }
    }
    val photos = remember(ui.messages) { TvChatLogic.photos(ui.messages) }
    val list = rememberLazyListState()
    // newest at the bottom: the list starts there and follows new messages
    LaunchedEffect(c.id, ui.messages.lastOrNull()?.id, ui.pending.size) {
        val n = ui.messages.size + ui.pending.size + 1
        if (n > 0) list.scrollToItem(n - 1)
    }
    val block = TvChatLogic.block(c)
    Column(modifier.fillMaxHeight()) {
        ColumnHead(TvChatLogic.title(c, unnamed), c.bridge?.let { stringResource(R.string.tv_chats_via, it.label) })
        if (ui.messages.isEmpty() && ui.pending.isEmpty()) {
            Note(
                stringResource(
                    when (ui.history) {
                        TvSourceState.LOADING -> R.string.tv_home_loading
                        TvSourceState.OFFLINE -> R.string.tv_home_offline
                        TvSourceState.MISSING -> R.string.tv_chats_missing
                        TvSourceState.OK -> R.string.tv_chats_empty
                    },
                ),
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (ui.more) {
                item(key = "older") { TvButton(stringResource(R.string.tv_chats_older), onClick = actions.onOlder) }
            }
            items(ui.messages, key = { "m:" + it.id }) { m ->
                MessageLine(
                    m, c, ui,
                    thumb = actions.thumb,
                    onClick = {
                        when {
                            TvChatLogic.show(m) == TvChatShow.PHOTO -> actions.onPhoto(photos, photos.indexOfFirst { it.id == m.id }.coerceAtLeast(0))
                            else -> onMenu(m)
                        }
                    },
                    modifier = Modifier.onFocusChanged { if (it.isFocused) onFocus(m) },
                )
            }
            items(ui.pending, key = { "p:" + it.clientId }) { p ->
                PendingLine(p, onClick = { if (p.failed) onFailed(p) }, modifier = Modifier.onFocusChanged { if (it.isFocused) onFocus(null) })
            }
            item(key = "answer") {
                Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (block == null) {
                        c.bridge?.let { b -> Text(stringResource(R.string.tv_chats_text_only, b.label), style = MaterialTheme.typography.bodySmall, color = colors.muted) }
                        TvButton(
                            stringResource(R.string.tv_chats_answer),
                            onClick = { onAnswer(null) },
                            style = TvButtonStyle.Primary,
                            icon = TvIcons.Mic,
                            modifier = Modifier.onFocusChanged { if (it.isFocused) onFocus(null) },
                        )
                    } else {
                        Text(
                            blockText(block, c),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.warn,
                            modifier = Modifier.fillMaxWidth().background(colors.warnBg, RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun blockText(block: TvChatBlock, c: ChatConversation): String = when (block) {
    TvChatBlock.READ_ONLY -> stringResource(R.string.tv_chats_block_read_only)
    TvChatBlock.CLOSED -> stringResource(R.string.tv_chats_block_closed, c.bridge?.label.orEmpty())
    TvChatBlock.BRIDGE_DOWN -> stringResource(R.string.tv_chats_block_bridge, c.bridge?.label.orEmpty())
    TvChatBlock.NO_RIGHT -> stringResource(R.string.tv_chats_block_no_right)
}

@Composable
private fun MessageLine(
    m: ChatMessage,
    c: ChatConversation,
    ui: TvChatsUi,
    thumb: suspend (ChatMessage) -> Bitmap?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GateControlTvTheme.colors
    val show = TvChatLogic.show(m)
    if (show == TvChatShow.SYSTEM) {
        Text(
            m.text ?: stringResource(R.string.tv_chats_system),
            style = MaterialTheme.typography.bodySmall,
            color = colors.faint,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        )
        return
    }
    val sender = TvChatLogic.senderName(c, m)
    val status = TvChatLogic.status(m, ui.readUpTo, ui.deliveredUpTo)
    val body = if (show == TvChatShow.TEXT) m.text.orEmpty() else kindLine(m)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
        Bubble(
            mine = m.mine,
            onClick = onClick,
            description = listOfNotNull(sender, body).joinToString(": "),
            modifier = modifier.widthIn(max = 520.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (sender != null && (c.isGroup || c.bridge != null)) Text(sender, style = MaterialTheme.typography.labelMedium, color = colors.info, maxLines = 1)
                m.replyTo?.let { r ->
                    Text(
                        listOfNotNull(r.senderName, r.text ?: stringResource(R.string.tv_chats_reply_other)).joinToString(": "),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.border(BorderStroke(1.dp, colors.line2), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
                when (show) {
                    TvChatShow.PHOTO -> {
                        Photo(m, thumb)
                        m.text?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    }
                    TvChatShow.TEXT -> Text(m.text.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                    else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(kindIcon(show), contentDescription = null, modifier = Modifier.size(14.dp), tint = colors.muted)
                        Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                    }
                }
                val reactions = m.reactions.filter { it.count > 0 }
                val foot = listOfNotNull(
                    reactions.takeIf { it.isNotEmpty() }?.joinToString("  ") { if (it.count > 1) "${it.emoji} ${it.count}" else it.emoji },
                    status?.let { statusText(it) },
                )
                if (foot.isNotEmpty()) {
                    Text(foot.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = if (status == TvChatStatus.FAILED) colors.warn else colors.faint)
                }
            }
        }
    }
}

@Composable
private fun Photo(m: ChatMessage, thumb: suspend (ChatMessage) -> Bitmap?) {
    val colors = GateControlTvTheme.colors
    var picture by remember(m.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(m.id) { picture = thumb(m) }
    val p = picture
    Box(Modifier.size(200.dp, 130.dp).background(colors.panel2, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
        if (p != null) {
            Image(p.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(TvIcons.Photo, contentDescription = null, tint = colors.faint, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun PendingLine(p: TvChatPending, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = GateControlTvTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Bubble(mine = true, onClick = onClick, description = p.text, modifier = modifier.widthIn(max = 520.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(p.text, style = MaterialTheme.typography.bodyLarge)
                val error = p.error
                if (error == null) {
                    Text(stringResource(R.string.tv_chats_status_sending), style = MaterialTheme.typography.labelSmall, color = colors.faint)
                } else {
                    Text(
                        stringResource(R.string.tv_chats_not_sent) + " · " + errorText(error),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.warn,
                    )
                    Text(stringResource(R.string.tv_chats_retry_hint), style = MaterialTheme.typography.labelSmall, color = colors.muted)
                }
            }
        }
    }
}

@Composable
private fun statusText(s: TvChatStatus): String = stringResource(
    when (s) {
        TvChatStatus.SENDING -> R.string.tv_chats_status_sending
        TvChatStatus.SENT -> R.string.tv_chats_status_sent
        TvChatStatus.DELIVERED -> R.string.tv_chats_status_delivered
        TvChatStatus.READ -> R.string.tv_chats_status_read
        TvChatStatus.FAILED -> R.string.tv_chats_not_sent
    },
)

@Composable
private fun errorText(e: TvChatSendError): String = stringResource(
    when (e) {
        TvChatSendError.TEXT_ONLY -> R.string.tv_chats_error_text_only
        TvChatSendError.BRIDGE -> R.string.tv_chats_error_bridge
        TvChatSendError.OFFLINE -> R.string.tv_chats_error_offline
        TvChatSendError.FAILED -> R.string.tv_chats_error_failed
    },
)

/** What a message is when the TV shows no text for it. */
@Composable
private fun kindLine(m: ChatMessage): String {
    val name = m.bridge?.placeholder?.name ?: m.meta?.fileName
    return when (TvChatLogic.show(m)) {
        TvChatShow.PHOTO -> stringResource(R.string.tv_chats_kind_photo)
        TvChatShow.VOICE -> stringResource(R.string.tv_chats_kind_voice)
        TvChatShow.VIDEO -> stringResource(R.string.tv_chats_kind_video)
        TvChatShow.FILE -> name?.let { stringResource(R.string.tv_chats_kind_file_named, it) } ?: stringResource(R.string.tv_chats_kind_file)
        TvChatShow.LOCATION -> m.location?.label?.let { stringResource(R.string.tv_chats_kind_location_named, it) } ?: stringResource(R.string.tv_chats_kind_location)
        TvChatShow.DELETED -> stringResource(R.string.tv_chats_kind_deleted)
        TvChatShow.SYSTEM -> m.text ?: stringResource(R.string.tv_chats_system)
        TvChatShow.TEXT -> m.text.orEmpty()
        TvChatShow.OTHER -> if (m.bridge?.placeholder?.kind == "image") stringResource(R.string.tv_chats_kind_photo_missing) else stringResource(R.string.tv_chats_kind_other)
    }
}

private fun kindIcon(show: TvChatShow) = when (show) {
    TvChatShow.VOICE -> TvIcons.Mic
    TvChatShow.VIDEO -> TvIcons.Play
    TvChatShow.LOCATION -> TvIcons.Home
    TvChatShow.PHOTO -> TvIcons.Photo
    else -> TvIcons.File
}

// ─── dialogs ────────────────────────────────────────────────────────────

/** OK / ☰ on a message: Antworten, 👍 ❤️ 😂, „Erneut versuchen“ on an own one a bridge did not take. */
@Composable
private fun MessageMenu(
    m: ChatMessage,
    c: ChatConversation,
    myId: Long?,
    onAnswer: () -> Unit,
    onReact: (String) -> Unit,
    onResend: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = GateControlTvTheme.colors
    val first = remember { FocusRequester() }
    val reply = TvChatLogic.canReply(c, m)
    val react = TvChatLogic.canReact(c, m)
    val resend = m.mine && TvChatLogic.status(m, null, null) == TvChatStatus.FAILED && TvChatLogic.block(c) == null
    val own = TvChatLogic.ownReaction(m, myId)
    TvDialog(onDismiss) {
        Text(stringResource(R.string.tv_chats_menu_title), style = MaterialTheme.typography.headlineSmall, color = colors.text)
        m.text?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.muted, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        if (!reply && !react && !resend) Text(stringResource(R.string.tv_chats_menu_nothing), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var firstSet = false
            fun firstMod(): Modifier = if (!firstSet) {
                firstSet = true
                Modifier.focusRequester(first)
            } else {
                Modifier
            }
            if (resend) TvButton(stringResource(R.string.tv_chats_retry), onClick = onResend, style = TvButtonStyle.Primary, modifier = firstMod())
            if (reply) TvButton(stringResource(R.string.tv_chats_answer), onClick = onAnswer, style = TvButtonStyle.Primary, icon = TvIcons.Mic, modifier = firstMod())
            if (react) {
                for (emoji in TvChatLogic.REACTIONS) {
                    TvButton(
                        if (own == emoji) "$emoji ✓" else emoji,
                        onClick = { onReact(emoji) },
                        modifier = firstMod().semantics { contentDescription = emoji },
                    )
                }
            }
            TvButton(stringResource(R.string.tv_cancel), onClick = onDismiss, modifier = firstMod())
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun FailedDialog(p: TvChatPending, onRetry: () -> Unit, onDrop: () -> Unit, onDismiss: () -> Unit) {
    val colors = GateControlTvTheme.colors
    val first = remember { FocusRequester() }
    TvDialog(onDismiss) {
        Text(stringResource(R.string.tv_chats_not_sent), style = MaterialTheme.typography.headlineSmall, color = colors.text)
        Text(p.text, style = MaterialTheme.typography.bodyMedium, color = colors.muted, maxLines = 4, overflow = TextOverflow.Ellipsis)
        p.error?.let { Text(errorText(it), style = MaterialTheme.typography.bodySmall, color = colors.warn) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            // a bridge that takes only text, or refuses: trying again cannot help
            if (p.error != TvChatSendError.TEXT_ONLY) {
                TvButton(stringResource(R.string.tv_chats_retry), onClick = onRetry, style = TvButtonStyle.Primary, modifier = Modifier.focusRequester(first))
            }
            TvButton(stringResource(R.string.tv_chats_drop), onClick = onDrop, modifier = if (p.error == TvChatSendError.TEXT_ONLY) Modifier.focusRequester(first) else Modifier)
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/**
 * The answer: [TvComposerDialog] with the messenger's quick answers to the last message of another person (or to the
 * message answered) and the own building blocks, the way it goes („über WhatsApp“) and „Am Handy weiterschreiben“.
 * One clientId per text: sending the same text again („Nicht gesendet“ → Senden) keeps it.
 */
@Composable
private fun Composer(c: ChatConversation, replyTo: ChatMessage?, ui: TvChatsUi, actions: TvChatsActions, onClose: () -> Unit) {
    val colors = GateControlTvTheme.colors
    val unnamed = stringResource(R.string.tv_chats_unnamed)
    val title = TvChatLogic.title(c, unnamed)
    val about = replyTo ?: ui.messages.lastOrNull { !it.mine && !it.isSystem && it.id > 0 }
    val suggestionSource = remember(about?.id) {
        about?.let { m -> TvSnippetSource { actions.suggestions(m.id).map { TvSnippet(it, TvSnippetKind.SUGGESTION) } } }
    }
    val sources = remember(suggestionSource) { listOfNotNull(suggestionSource, actions.ownSnippets) }
    val ids = remember { ClientIds() }
    val meta = listOfNotNull(
        stringResource(R.string.tv_chats_to, title),
        c.bridge?.let { stringResource(R.string.tv_chats_via, it.label) },
    ).joinToString(" · ")
    TvComposerDialog(
        title = stringResource(if (replyTo != null) R.string.tv_chats_reply_title else R.string.tv_chats_answer_title, title),
        onSend = { text -> actions.send(text, replyTo?.id, ids.forText(text)) },
        onClose = onClose,
        previewMeta = meta,
        sources = sources,
        handoff = remember(c.id) { actions.handoff(c.id) },
        maxLength = TvChatLogic.TEXT_MAX,
        header = {
            c.bridge?.let { b -> Text(stringResource(R.string.tv_chats_via, b.label), style = MaterialTheme.typography.bodySmall, color = colors.muted) }
            replyTo?.let { r ->
                Text(
                    listOfNotNull(TvChatLogic.senderName(c, r), r.text ?: kindLine(r)).joinToString(": "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().border(BorderStroke(1.dp, colors.line2), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        },
    )
}

/** The clientId of the text being written: kept while the text stays the same, a new one once it changed. */
private class ClientIds {
    private var text: String? = null
    private var id: String = TvChatLogic.newClientId()

    fun forText(t: String): String {
        if (text != null && text != t) id = TvChatLogic.newClientId()
        text = t
        return id
    }
}

// ─── building blocks ────────────────────────────────────────────────────

@Composable
private fun ColumnHead(title: String, extra: String?) {
    val colors = GateControlTvTheme.colors
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (extra != null) Text(extra, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = GateControlTvTheme.colors.muted, modifier = modifier.padding(top = 4.dp))
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

/** A line of the chat list with the TV focus; [selected]: the chat open on the right. */
@Composable
private fun Line(onClick: () -> Unit, description: String, modifier: Modifier = Modifier, selected: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val colors = GateControlTvTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = description },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) colors.accentBg else colors.panel,
            contentColor = if (selected) colors.accentText else colors.text,
            focusedContainerColor = if (selected) colors.accentBg else colors.panel2,
            focusedContentColor = if (selected) colors.accentText else colors.text,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (selected) Color.Transparent else colors.line), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, colors.focus), shape = shape),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(elevationColor = colors.focusGlow, elevation = 10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(
            Modifier.defaultMinSize(minHeight = 40.dp).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            content = content,
        )
    }
}

/** A message: own ones right in green, the others left; focusable for OK and ☰. */
@Composable
private fun Bubble(mine: Boolean, onClick: () -> Unit, description: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = GateControlTvTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = description },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (mine) colors.accentBg else colors.panel,
            contentColor = if (mine) colors.accentText else colors.text,
            focusedContainerColor = if (mine) colors.accentBg else colors.panel2,
            focusedContentColor = if (mine) colors.accentText else colors.text,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (mine) Color.Transparent else colors.line), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, colors.focus), shape = shape),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(elevationColor = colors.focusGlow, elevation = 8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Box(Modifier.padding(horizontal = 11.dp, vertical = 7.dp)) { content() }
    }
}
