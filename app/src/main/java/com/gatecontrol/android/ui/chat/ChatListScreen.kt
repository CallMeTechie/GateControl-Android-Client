package com.gatecontrol.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatConversation
import com.gatecontrol.android.chat.ChatPrefs
import com.gatecontrol.android.chat.ChatSync
import com.gatecontrol.android.chat.ChatSystemBubbles
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily

/**
 * The tab "Chats": the pinned "GateControl" entry (the core inbox), the
 * conversations with unread badge, preview and "schreibt …", filters and
 * a search, the chat preferences and "Neuer Chat".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    onOpen: (Long) -> Unit,
    onOpenInbox: () -> Unit,
    onNewChat: () -> Unit,
    onOpenUpdates: () -> Unit,
    selectedId: Long? = null,
    viewModel: ChatListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val updateHint by viewModel.showUpdateHint.collectAsStateWithLifecycle()
    val extra = GateControlTheme.extraColors
    val context = LocalContext.current
    var searching by rememberSaveable { mutableStateOf(false) }
    var showPrefs by remember { mutableStateOf(false) }
    val myId = state.profile?.user?.userId

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.chat_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                GcIconButton(GcIcons.Search, stringResource(R.string.chat_search), onClick = {
                    searching = !searching
                    if (!searching) viewModel.setQuery("")
                })
                GcIconButton(GcIcons.Settings, stringResource(R.string.chat_prefs_title), onClick = { showPrefs = true })
            }
            if (searching) {
                GcTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    label = stringResource(R.string.chat_search_chats),
                    placeholder = stringResource(R.string.chat_search_chats_hint),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (updateHint) {
                // The server offers chat features this app does not know yet: never blocks anything.
                GcBanner(tone = GcTone.Info, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(
                        stringResource(R.string.chat_update_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        if (com.gatecontrol.android.update.PlayUpdater.enabled) openPlayStore(context) else onOpenUpdates()
                    }) { Text(stringResource(R.string.chat_update_hint_action)) }
                    GcIconButton(GcIcons.Close, stringResource(R.string.chat_update_hint_dismiss), onClick = viewModel::dismissUpdateHint, size = 40.dp, iconSize = 18.dp)
                }
            }
            if (state.sharing) {
                GcBanner(tone = GcTone.Info, icon = GcIcons.Share, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(
                        stringResource(R.string.chat_share_pick),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::cancelShare) { Text(stringResource(R.string.chat_cancel)) }
                }
            }
            if (state.profile?.licensed == false) {
                GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(stringResource(R.string.chat_unlicensed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GcFilterChip(stringResource(R.string.chat_filter_all), state.filter == ChatFilter.All, { viewModel.setFilter(ChatFilter.All) })
                GcFilterChip(
                    stringResource(R.string.chat_filter_unread) + if (state.unreadChats > 0) " · ${state.unreadChats}" else "",
                    state.filter == ChatFilter.Unread,
                    { viewModel.setFilter(ChatFilter.Unread) },
                )
                GcFilterChip(stringResource(R.string.chat_filter_groups), state.filter == ChatFilter.Groups, { viewModel.setFilter(ChatFilter.Groups) })
            }
            PullToRefreshBox(
                isRefreshing = state.sync == ChatSync.Syncing && state.loaded,
                onRefresh = viewModel::refresh,
                modifier = Modifier.weight(1f),
            ) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    if (!state.sharing && state.query.isBlank()) {
                        item(key = "pinned") {
                            SectionLabel(stringResource(R.string.chat_pinned))
                            InboxRow(state, onOpenInbox)
                            SectionLabel(stringResource(R.string.chat_section_chats))
                        }
                    }
                    if (state.sync is ChatSync.Offline && state.loaded) {
                        item(key = "offline") {
                            Text(
                                stringResource(R.string.chat_offline_list),
                                style = MaterialTheme.typography.bodySmall,
                                color = extra.warn,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }
                    items(state.conversations, key = { it.id }) { c ->
                        ConversationRow(
                            c = c,
                            typingText = state.typing[c.id]?.let { ChatText.typing(context, c, it, myId) },
                            myId = myId,
                            selected = c.id == selectedId,
                            onClick = { onOpen(c.id) },
                        )
                    }
                    if (state.conversations.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                stringResource(
                                    when {
                                        !state.loaded -> R.string.chat_loading
                                        state.query.isNotBlank() || state.filter != ChatFilter.All -> R.string.chat_empty_filtered
                                        state.sync is ChatSync.Failed -> R.string.chat_load_failed
                                        else -> R.string.chat_empty
                                    },
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = extra.muted,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                            )
                        }
                    }
                    item(key = "footer") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Lock, contentDescription = null, tint = extra.faint, modifier = Modifier.size(16.dp))
                            Text(stringResource(R.string.chat_footer), style = MaterialTheme.typography.bodySmall, color = extra.faint)
                        }
                    }
                }
            }
        }
        if (!state.sharing) {
            ExtendedFloatingActionButton(
                onClick = onNewChat,
                icon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                text = { Text(stringResource(R.string.chat_new)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }

    if (showPrefs) {
        val device by viewModel.device.collectAsStateWithLifecycle()
        ChatPrefsDialog(
            prefs = state.profile?.prefs ?: ChatPrefs(),
            device = device,
            onSave = { p -> viewModel.setPrefs(p) { } },
            onSaveDevice = viewModel::setDevice,
            onDismiss = { showPrefs = false },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
        color = GateControlTheme.extraColors.faint,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** The pinned "GateControl" entry: the core's inbox (notifications), not a chat of the plugin. */
@Composable
private fun InboxRow(state: ChatListUiState, onOpen: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val latest = state.inboxLatest
    Row(
        Modifier.fillMaxWidth().heightIn(min = 74.dp).clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(extra.accentBg), contentAlignment = Alignment.Center) {
            Icon(GcIcons.ShieldCheck, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(26.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.chat_gatecontrol),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                latest?.let {
                    Text(
                        ChatText.listTime(LocalContext.current, it.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.inboxUnread > 0) MaterialTheme.colorScheme.primary else extra.muted,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    latest?.let { m -> if (m.body.isBlank()) m.title else "${m.title}: ${m.body.lineSequence().first()}" }
                        ?: stringResource(R.string.chat_inbox_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = extra.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (state.inboxUnread > 0) UnreadBadge(state.inboxUnread)
            }
        }
    }
}

@Composable
private fun UnreadBadge(n: Int, muted: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val extra = GateControlTheme.extraColors
    Box(
        Modifier.height(22.dp).widthIn(min = 22.dp).clip(RoundedCornerShape(11.dp))
            .background(if (muted) extra.panel2 else scheme.primary).padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (n > 99) "99+" else n.toString(),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = if (muted) extra.muted else scheme.onPrimary,
        )
    }
}

@Composable
private fun ConversationRow(c: ChatConversation, typingText: String?, myId: Long?, selected: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    val last = c.lastMessage
    Row(
        Modifier.fillMaxWidth().heightIn(min = 74.dp)
            .then(if (selected) Modifier.background(extra.accentBg) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConversationAvatar(c, 52.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ChatText.title(ctx, c),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (c.muted) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Outlined.VolumeOff, contentDescription = stringResource(R.string.chat_muted), tint = extra.faint, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(
                    ChatText.listTime(ctx, last?.createdAt ?: c.updatedAt),
                    style = MaterialTheme.typography.bodySmall.let { if (c.unread > 0) it.copy(fontWeight = FontWeight.Bold) else it },
                    color = if (c.unread > 0 && !c.muted) MaterialTheme.colorScheme.primary else extra.muted,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (typingText != null) {
                    Text(
                        typingText,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    if (last != null && last.mine && !last.isSystem) ChatTicks(ChatTimelineStatus.of(last, c), pending = false)
                    val prefix = when {
                        last == null || last.isSystem -> ""
                        last.mine -> ""
                        c.isGroup -> ChatText.nameOf(ctx, c, last.senderId, myId) + ": "
                        else -> ""
                    }
                    Text(
                        last?.let { prefix + ChatText.content(ctx, it, c, myId) } ?: stringResource(R.string.chat_no_messages),
                        style = MaterialTheme.typography.bodyMedium,
                        color = extra.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (c.unread > 0) UnreadBadge(c.unread, muted = c.muted)
            }
        }
    }
}

/** Ticks of the list preview. */
internal object ChatTimelineStatus {
    fun of(m: com.gatecontrol.android.chat.ChatMessage, c: ChatConversation): String? =
        com.gatecontrol.android.chat.ChatTimeline.status(m, c.readUpTo, c.deliveredUpTo)
}

/**
 * Chat preferences (README `PUT prefs`): read receipts, "schreibt …",
 * content in notifications — and, for this device only, conversation
 * shortcuts and bubbles.
 */
@Composable
fun ChatPrefsDialog(
    prefs: ChatPrefs,
    device: ChatDevicePrefs,
    onSave: (ChatPrefs) -> Unit,
    onSaveDevice: (ChatDevicePrefs) -> Unit,
    onDismiss: () -> Unit,
) {
    var p by remember(prefs) { mutableStateOf(prefs) }
    var d by remember(device) { mutableStateOf(device) }
    val context = LocalContext.current
    val systemBubbles = remember { ChatSystemBubbles.allowed(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Notifications, contentDescription = null) },
        title = { Text(stringResource(R.string.chat_prefs_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                GcSwitchRow(
                    label = stringResource(R.string.chat_prefs_receipts),
                    description = stringResource(R.string.chat_prefs_receipts_desc),
                    checked = p.readReceipts,
                    onCheckedChange = { p = p.copy(readReceipts = it) },
                )
                GcSwitchRow(
                    label = stringResource(R.string.chat_prefs_typing),
                    description = stringResource(R.string.chat_prefs_typing_desc),
                    checked = p.typing,
                    onCheckedChange = { p = p.copy(typing = it) },
                )
                GcSwitchRow(
                    label = stringResource(R.string.chat_prefs_preview),
                    description = stringResource(R.string.chat_prefs_preview_desc),
                    checked = p.preview,
                    onCheckedChange = { p = p.copy(preview = it) },
                )
                Text(
                    stringResource(R.string.chat_prefs_device).uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                    color = GateControlTheme.extraColors.faint,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                GcSwitchRow(
                    label = stringResource(R.string.chat_prefs_shortcuts),
                    description = stringResource(R.string.chat_prefs_shortcuts_desc),
                    checked = d.shortcuts,
                    onCheckedChange = { d = d.copy(shortcuts = it) },
                )
                GcSwitchRow(
                    label = stringResource(R.string.chat_prefs_bubbles),
                    description = stringResource(
                        when {
                            !d.shortcuts -> R.string.chat_prefs_bubbles_needs_shortcuts
                            systemBubbles == false -> R.string.chat_prefs_bubbles_system_off
                            else -> R.string.chat_prefs_bubbles_desc
                        },
                    ),
                    checked = d.bubbles && d.shortcuts,
                    enabled = d.shortcuts,
                    onCheckedChange = { d = d.copy(bubbles = it) },
                )
                if (d.shortcuts && d.bubbles && systemBubbles == false) {
                    TextButton(onClick = { ChatSystemBubbles.openSettings(context) }) { Text(stringResource(R.string.chat_prefs_bubbles_open_settings)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (p != prefs) onSave(p)
                if (d != device) onSaveDevice(d)
                onDismiss()
            }) { Text(stringResource(R.string.chat_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) } },
    )
}

/** The app's page in the Play Store (Google Play build: updates come from Play only). */
private fun openPlayStore(context: android.content.Context) {
    val id = context.packageName
    val market = android.content.Intent(android.content.Intent.ACTION_VIEW, "market://details?id=$id".toUri())
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(market)
    } catch (e: android.content.ActivityNotFoundException) {
        runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$id".toUri())
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
