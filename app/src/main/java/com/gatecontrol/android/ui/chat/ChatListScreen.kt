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
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.foundation.combinedClickable
import com.gatecontrol.android.chat.events
import com.gatecontrol.android.chat.msgTools
import com.gatecontrol.android.chat.status
import com.gatecontrol.android.chat.stories
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatConversation
import com.gatecontrol.android.chat.ChatPrefs
import com.gatecontrol.android.chat.ChatSync
import com.gatecontrol.android.chat.ChatSystemBubbles
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily

/**
 * The tab "Chats": the status strip, under "Angeheftet" the "GateControl"
 * entry (the core inbox) and the chats pinned to the list (Messenger 1.6,
 * the family group), then the conversations with unread badge, preview and
 * "schreibt …", filters and a search, the chat preferences and "Neuer Chat".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    onOpen: (Long) -> Unit,
    onOpenInbox: () -> Unit,
    onNewChat: () -> Unit,
    onOpenUpdates: () -> Unit,
    selectedId: Long? = null,
    /** Messenger 1.4: Chat-Einstellungen as a page, the status strip's viewer and composer. */
    onOpenSettings: () -> Unit = {},
    onOpenStory: (Long) -> Unit = {},
    onOpenStoryComposer: () -> Unit = {},
    /** Messenger 1.20: the page „Kanäle“ (the TV symbol, the side menu). */
    onOpenChannels: () -> Unit = {},
    /** A page of the chat settings ([com.gatecontrol.android.ui.chat.settings.ChatSettingsPage]) from the side menu. */
    onOpenSettingsPage: (String) -> Unit = { onOpenSettings() },
    viewModel: ChatListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val linked by viewModel.linked.collectAsStateWithLifecycle()
    val drawer = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val closeDrawer: () -> Unit = { scope.launch { drawer.close() } }
    androidx.activity.compose.BackHandler(enabled = drawer.isOpen) { closeDrawer() }
    androidx.compose.runtime.LaunchedEffect(drawer.isOpen) { if (drawer.isOpen) viewModel.loadLinked() }
    val updateHint by viewModel.showUpdateHint.collectAsStateWithLifecycle()
    val extra = GateControlTheme.extraColors
    val context = LocalContext.current
    var searching by rememberSaveable { mutableStateOf(false) }
    var showStatus by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<ChatConversation?>(null) }
    val f14 = state.profile?.features
    val myId = state.profile?.user?.userId
    // Messenger 1.21: the suggestion card and its dialog „Zu einer Person zusammenführen?“
    val suggestion by viewModel.suggestion.collectAsStateWithLifecycle()
    val merging by viewModel.merging.collectAsStateWithLifecycle()
    var mergeFor by remember { mutableStateOf<com.gatecontrol.android.chat.ChatPersonSuggestion?>(null) }
    androidx.compose.runtime.LaunchedEffect(f14?.tokens?.contains(com.gatecontrol.android.chat.ChatPeople.FEATURE)) { viewModel.loadSuggestions() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.peopleNotice.collect { n ->
            when (n) {
                is ChatPeopleNotice.Merged -> {
                    mergeFor = null
                    android.widget.Toast.makeText(context, R.string.chat_pp_merged, android.widget.Toast.LENGTH_SHORT).show()
                    onOpen(n.id)
                }
                is ChatPeopleNotice.Error -> android.widget.Toast.makeText(context, ChatText.actionError(context, n.code), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    // Chats v2: the side menu behind ☰ (as Drive and Notizen) — the views, the lists, the linked services and, at its
    // foot, the settings (the gear left the head)
    com.gatecontrol.android.ui.components.GcNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            com.gatecontrol.android.ui.components.GcDrawerSheet(onClose = { closeDrawer() }) {
                ChatDrawer(
                    ChatDrawerState(
                        name = state.profile?.user?.name,
                        status = state.profile?.status?.text?.takeIf { it.isNotBlank() },
                        channels = channels,
                        archived = state.archived.size,
                        status14 = f14?.status == true || f14?.stories == true,
                        lists = state.lists.takeIf { f14?.msgTools == true },
                        listId = state.listId,
                        showArchived = state.showArchived,
                        linked = linked,
                    ),
                    ChatDrawerActions(
                        all = { closeDrawer(); viewModel.showAll() },
                        channels = { closeDrawer(); onOpenChannels() },
                        archive = { closeDrawer(); viewModel.showArchive() },
                        status = { closeDrawer(); if (f14?.status == true) showStatus = true else onOpenStoryComposer() },
                        list = { id -> closeDrawer(); viewModel.setList(id) },
                        page = { p -> closeDrawer(); onOpenSettingsPage(p) },
                    ),
                )
            }
        },
    ) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GcIconButton(GcIcons.Menu, stringResource(R.string.chat_dr_menu), onClick = { scope.launch { drawer.open() } }, tint = MaterialTheme.colorScheme.onBackground)
                Text(
                    stringResource(R.string.chat_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                GcIconButton(GcIcons.Search, stringResource(R.string.chat_search), onClick = {
                    searching = !searching
                    if (!searching) viewModel.setQuery("")
                })
                // Messenger 1.20: the channels — only once one has a post; a grey dot for new posts (no number)
                if (com.gatecontrol.android.chat.ChatChannels.showTv(channels)) ChannelsButton(dot = com.gatecontrol.android.chat.ChatChannels.dot(channels), onClick = onOpenChannels)
                // "⋯" of the page when Chats is pinned to Start.
                com.gatecontrol.android.ui.start.StartPageMenuButton()
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
                GcFilterChip(stringResource(R.string.chat_filter_all), state.filter == ChatFilter.All && state.net == null, {
                    viewModel.setFilter(ChatFilter.All)
                    viewModel.setNet(null)
                })
                // Messenger 1.14: one filter per service once a chat over a bridge exists.
                if (state.networks.isNotEmpty()) {
                    val gc = com.gatecontrol.android.chat.ChatBridgeChat.NET_GC
                    GcFilterChip(stringResource(R.string.chat_bridged_only_gc), state.net == gc, { viewModel.setNet(gc) })
                    // Messenger 1.20: the service's logo in its chip
                    state.networks.forEach { (id, label) -> GcFilterChip(label, state.net == id, { viewModel.setNet(id) }, leading = { ChatNetLogo(id, 16.dp) }) }
                }
                GcFilterChip(
                    stringResource(R.string.chat_filter_unread) + if (state.unreadChats > 0) " · ${state.unreadChats}" else "",
                    state.filter == ChatFilter.Unread,
                    { viewModel.setFilter(ChatFilter.Unread) },
                )
                GcFilterChip(stringResource(R.string.chat_filter_groups), state.filter == ChatFilter.Groups, { viewModel.setFilter(ChatFilter.Groups) })
                // Messenger 1.4: chats with an upcoming event, and the own lists.
                if (f14?.events == true) GcFilterChip(stringResource(R.string.chat_filter_events), state.filter == ChatFilter.Events, { viewModel.setFilter(ChatFilter.Events) })
                state.lists.forEach { l ->
                    GcFilterChip(l.name, state.listId == l.id, { viewModel.setList(if (state.listId == l.id) null else l.id) })
                }
            }
            PullToRefreshBox(
                isRefreshing = state.sync == ChatSync.Syncing && state.loaded,
                onRefresh = viewModel::refresh,
                modifier = Modifier.weight(1f),
            ) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    val sectioned = !state.sharing && state.query.isBlank()
                    // Messenger 1.21: one card above the list — the first suggestion
                    val sugg = suggestion
                    if (sectioned && sugg != null) item(key = "suggestion") { PersonSuggestionCard(sugg) { mergeFor = sugg } }
                    if (sectioned) {
                        // "Mein Status" above "Angeheftet"; there GateControl, then the chats pinned to the list (Messenger 1.6).
                        item(key = "pinned") {
                            if (f14?.status == true || f14?.stories == true) {
                                val me = state.feed?.me
                                com.gatecontrol.android.ui.chat.status.StatusStrip(
                                    me = state.profile?.user,
                                    meHasStories = me?.stories?.isNotEmpty() == true,
                                    items = com.gatecontrol.android.chat.ChatStatusLogic.strip(state.feed),
                                    onOpenMine = { if (f14.status) showStatus = true else onOpenStoryComposer() },
                                    onOpen = { onOpenStory(it.user.userId) },
                                    onViewMine = { onOpenStory(0L) },
                                )
                            }
                            SectionLabel(stringResource(R.string.chat_pinned))
                            InboxRow(state, onOpenInbox)
                        }
                        items(state.pinned, key = { "p" + it.id }) { c ->
                            ConversationRow(
                                c = c,
                                typingText = ChatText.typing(context, c, state.typing[c.id].orEmpty(), myId, state.contactTyping[c.id]),
                                myId = myId,
                                selected = c.id == selectedId,
                                onClick = { onOpen(c.id) },
                                onLongClick = if (f14?.msgTools == true) ({ menuFor = c }) else null,
                            )
                        }
                        // Only pinned chats so far: no empty "Chats" heading.
                        if (state.conversations.isNotEmpty() || state.pinned.isEmpty()) {
                            item(key = "chats") { SectionLabel(stringResource(R.string.chat_section_chats)) }
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
                            typingText = ChatText.typing(context, c, state.typing[c.id].orEmpty(), myId, state.contactTyping[c.id]),
                            myId = myId,
                            selected = c.id == selectedId,
                            onClick = { onOpen(c.id) },
                            onLongClick = if (f14?.msgTools == true) ({ menuFor = c }) else null,
                        )
                    }
                    // Messenger 1.4: archived chats, folded away at the end.
                    if (state.archived.isNotEmpty() && state.query.isBlank() && !state.sharing) {
                        item(key = "archived") {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = viewModel::toggleArchived).padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(Icons.Outlined.Archive, contentDescription = null, tint = extra.muted)
                                Text(stringResource(R.string.chat_archived_n, state.archived.size), style = MaterialTheme.typography.titleSmall, color = extra.muted, modifier = Modifier.weight(1f))
                                Icon(if (state.showArchived) GcIcons.ChevronUp else GcIcons.ChevronDown, contentDescription = null, tint = extra.faint)
                            }
                        }
                        if (state.showArchived) {
                            items(state.archived, key = { "a" + it.id }) { c ->
                                ConversationRow(
                                    c = c,
                                    typingText = null,
                                    myId = myId,
                                    selected = c.id == selectedId,
                                    onClick = { onOpen(c.id) },
                                    onLongClick = { menuFor = c },
                                )
                            }
                        }
                    }
                    if (state.conversations.isEmpty() && (!sectioned || state.pinned.isEmpty())) {
                        item(key = "empty") {
                            Text(
                                stringResource(
                                    when {
                                        !state.loaded -> R.string.chat_loading
                                        state.query.isNotBlank() || state.filter != ChatFilter.All || state.net != null -> R.string.chat_empty_filtered
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
                    // "Alle Chats liegen nur auf deinem GateControl-Server" is not true for somebody with chats over a
                    // bridge (Messenger 1.14): the sentence is left out then — each such chat says where it runs.
                    if (!state.bridged) item(key = "footer") {
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
    }

    if (showStatus) {
        com.gatecontrol.android.ui.chat.status.StatusSheet(
            onDismiss = { showStatus = false },
            onOpenStoryComposer = { showStatus = false; onOpenStoryComposer() },
            onOpenProfile = null,
            onViewOwnStories = if (state.feed?.me?.stories?.isNotEmpty() == true) ({ showStatus = false; onOpenStory(0L) }) else null,
        )
    }
    mergeFor?.let { sg ->
        PersonMergeSheet(
            s = sg,
            busy = merging,
            onMerge = { name -> viewModel.mergeSuggestion(sg, name) },
            onLater = {
                mergeFor = null
                viewModel.answerSuggestion(sg, com.gatecontrol.android.chat.ChatPeople.ACTION_LATER)
            },
            onNever = {
                mergeFor = null
                viewModel.answerSuggestion(sg, com.gatecontrol.android.chat.ChatPeople.ACTION_NEVER)
            },
            onDismiss = { mergeFor = null },
        )
    }
    menuFor?.let { c ->
        ConversationMenu(
            c = c,
            lists = state.lists,
            onArchive = {
                menuFor = null
                viewModel.archive(c, !c.archived)
            },
            onToggleList = { l -> viewModel.toggleInList(l, c) },
            onDismiss = { menuFor = null },
        )
    }
}

/**
 * The TV symbol beside the search (Messenger 1.20): the page „Kanäle“; a grey dot (like a muted chat's number) while
 * there are new posts.
 */
@Composable
private fun ChannelsButton(dot: Boolean, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Box {
        GcIconButton(GcIcons.Tv, stringResource(if (dot) R.string.chat_ch_tv_new else R.string.chat_ch_tv), onClick = onClick, modifier = Modifier.testTag("channels-tv"))
        if (dot) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 11.dp, end = 10.dp).size(9.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background).padding(1.5.dp).clip(CircleShape).background(extra.muted)
                    .testTag("channels-dot"),
            )
        }
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

/** Long press on a chat (Messenger 1.4): archive, put into lists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationMenu(
    c: ChatConversation,
    lists: List<com.gatecontrol.android.chat.ChatList>,
    onArchive: () -> Unit,
    onToggleList: (com.gatecontrol.android.chat.ChatList) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(ChatText.title(ctx, c), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onArchive).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(if (c.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, contentDescription = null, tint = GateControlTheme.extraColors.muted)
                Text(stringResource(if (c.archived) R.string.chat_unarchive else R.string.chat_archive), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
            if (lists.isNotEmpty()) {
                SectionLabel(stringResource(R.string.chat_lists_add_chat))
                lists.forEach { l ->
                    GcSwitchRow(l.name, null, c.id in l.conversationIds, { onToggleList(l) })
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(c: ChatConversation, typingText: String?, myId: Long?, selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    val last = c.lastMessage
    Row(
        Modifier.fillMaxWidth().heightIn(min = 74.dp)
            .then(if (selected) Modifier.background(extra.accentBg) else Modifier)
            .then(
                if (onLongClick != null) Modifier.combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick, onLongClickLabel = stringResource(R.string.chat_actions))
                else Modifier.clickable(role = Role.Button, onClick = onClick),
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Messenger 1.21: a person — its picture without a corner, the logos of its ways behind the name
        val person = c.person?.takeIf { it.conversationId == c.id }
        if (person != null) PersonAvatar(c, 52.dp) else ConversationAvatar(c, 52.dp)
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
                if (person != null) {
                    Spacer(Modifier.width(6.dp))
                    PersonWayLogos(person, 14.dp, Modifier.testTag("person-ways"))
                }
                if (c.isNotice) {
                    // Messenger 1.6: a notice channel (GateControl's notifications of one source, read only).
                    Spacer(Modifier.width(6.dp))
                    GcChip(stringResource(R.string.chat_notice_tag), tone = GcTone.Neutral, small = true)
                }
                if (c.bridge?.bot == true) {
                    // Messenger 1.24: a chat with a Telegram bot
                    Spacer(Modifier.width(6.dp))
                    GcChip(stringResource(R.string.chat_bot_tag), tone = GcTone.Neutral, small = true)
                }
                if (c.bridge?.channel == true) {
                    // Messenger 1.20: a channel among the search's hits
                    Spacer(Modifier.width(6.dp))
                    GcChip(stringResource(R.string.chat_ch_tag), tone = GcTone.Neutral, small = true)
                }
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
                        c.isGroup -> ChatText.senderOf(ctx, c, last, myId) + ": "
                        else -> ""
                    }
                    // Messenger 1.24: a large group of another messenger says its size first („340 Mitglieder · …“)
                    val members = c.bridge?.members?.takeIf { it >= LARGE_GROUP }?.let { n -> pluralStringResource(R.plurals.chat_members_n, n, n) + " · " }.orEmpty()
                    Text(
                        members + (last?.let { prefix + ChatText.content(ctx, it, c, myId) } ?: stringResource(R.string.chat_no_messages)),
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

/** Messenger 1.24: a group of this many members and more says its size in the list (and starts muted). */
private const val LARGE_GROUP = 200

/** Ticks of the list preview. */
internal object ChatTimelineStatus {
    fun of(m: com.gatecontrol.android.chat.ChatMessage, c: ChatConversation): String? =
        com.gatecontrol.android.chat.ChatTimeline.status(m, c.readUpTo, c.deliveredUpTo)
}

/** The app's page in the Play Store (Google Play build: updates come from Play only). */
internal fun openPlayStore(context: android.content.Context) {
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
