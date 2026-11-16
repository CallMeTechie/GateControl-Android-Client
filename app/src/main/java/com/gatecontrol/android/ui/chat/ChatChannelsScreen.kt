package com.gatecontrol.android.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.gatecontrol.android.ui.components.GcFilterChip
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatChannels
import com.gatecontrol.android.chat.ChatConversation
import com.gatecontrol.android.chat.ChatExtrasRepository
import com.gatecontrol.android.chat.ChatPatches
import com.gatecontrol.android.chat.ChatRepository
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The page „Kanäle“: its rows, the archived ones folded away, the services for the subtitle. */
data class ChatChannelsUiState(
    val rows: List<ChatConversation> = emptyList(),
    val archived: List<ChatConversation> = emptyList(),
    val networks: List<String> = emptyList(),
    val query: String = "",
    val loaded: Boolean = false,
    val syncing: Boolean = false,
    val showArchived: Boolean = false,
    val any: Boolean = false,
    /** Messenger 1.24: the services of the channels (id → label) — chips once there are two; [net] the one chosen. */
    val services: List<Pair<String, String>> = emptyList(),
    val net: String? = null,
)

/**
 * Messenger 1.20: the channels a person follows over a bridge (`GET channels`), kept out of the chat list. Loads when
 * it opens and on a pull; mute, archive and „Als gelesen markieren“ as on a chat.
 */
@HiltViewModel
class ChatChannelsViewModel @Inject constructor(
    private val repository: ChatRepository,
    private val extras: ChatExtrasRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val loaded = MutableStateFlow(false)
    private val syncing = MutableStateFlow(false)
    private val showArchived = MutableStateFlow(false)
    private val net = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ChatChannelsUiState> = combine(repository.channels, query, loaded, syncing, combine(showArchived, net) { a, n -> a to n }) { list, q, l, s, an ->
        val (a, n0) = an
        val searching = q.isNotBlank()
        val services = ChatChannels.services(list)
        // a filter of a service whose channels are gone is dropped
        val n = n0?.takeIf { id -> services.size > 1 && services.any { it.first == id } }
        val rows = ChatChannels.rows(ChatChannels.of(list, n), q)
        ChatChannelsUiState(
            rows = if (searching) rows else rows.filter { !it.archived },
            archived = if (searching) emptyList() else rows.filter { it.archived },
            networks = ChatChannels.networks(list),
            query = q,
            loaded = l || list.isNotEmpty(),
            syncing = s,
            showArchived = a,
            any = list.isNotEmpty(),
            services = services,
            net = n,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatChannelsUiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            syncing.value = true
            try {
                repository.syncChannels()
            } finally {
                syncing.value = false
                loaded.value = true
            }
        }
    }

    fun setQuery(q: String) {
        query.value = q.take(80)
    }

    /** Messenger 1.24: the chip of a service (tapped again, or null: all). */
    fun setNet(id: String?) {
        net.value = if (id == null || net.value == id) null else id
    }

    fun toggleArchived() {
        showArchived.value = !showArchived.value
    }

    fun archive(c: ChatConversation) {
        viewModelScope.launch { extras.archive(c.id, !c.archived) }
    }

    /** The bell of [c]: its posts as a push ([on]) or not. */
    fun setPush(c: ChatConversation, on: Boolean) {
        viewModelScope.launch { repository.patch(c.id, ChatPatches.notify(on)) }
    }

    /** „Als gelesen markieren“: the grey number goes. */
    fun markRead(c: ChatConversation) {
        viewModelScope.launch { repository.markRead(c.id) }
    }
}

/** The bell of a channel is on (its posts come as a push). */
internal fun channelPush(c: ChatConversation): Boolean = c.me.notify != "none" && !c.muted

/**
 * The page „Kanäle“ (Messenger 1.20, draft „Kanäle“): back, „Kanäle“ with the services below, the search; the
 * channels like chats — picture with the service's logo, name, the time of the newest post, its text, a struck-through
 * bell while muted and the grey number of new posts; the note that their push is off; a long press for mute, archive
 * and „Als gelesen markieren“.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatChannelsScreen(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    selectedId: Long? = null,
    viewModel: ChatChannelsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val extra = GateControlTheme.extraColors
    var searching by rememberSaveable { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<ChatConversation?>(null) }
    BackHandler(enabled = searching) {
        searching = false
        viewModel.setQuery("")
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GcIconButton(GcIcons.Back, stringResource(R.string.chat_back), onClick = onBack, tint = MaterialTheme.colorScheme.onBackground)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(stringResource(R.string.chat_ch_title), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold), color = MaterialTheme.colorScheme.onBackground)
                if (state.networks.isNotEmpty()) {
                    Text(state.networks.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
            }
            GcIconButton(GcIcons.Search, stringResource(R.string.chat_ch_search), onClick = {
                searching = !searching
                if (!searching) viewModel.setQuery("")
            })
        }
        if (searching) {
            GcTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                label = stringResource(R.string.chat_ch_search),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        // Messenger 1.24: one chip per service once channels of two services are there (Alle · WhatsApp · Telegram)
        if (state.services.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp).testTag("channel-chips"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GcFilterChip(stringResource(R.string.chat_filter_all), state.net == null, { viewModel.setNet(null) })
                state.services.forEach { (id, label) -> GcFilterChip(label, state.net == id, { viewModel.setNet(id) }, leading = { ChatNetLogo(id, 16.dp) }) }
            }
        }
        PullToRefreshBox(isRefreshing = state.syncing && state.loaded, onRefresh = viewModel::refresh, modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().testTag("channel-list"), contentPadding = PaddingValues(bottom = 32.dp)) {
                items(state.rows, key = { it.id }) { c ->
                    ChannelRow(c, selected = c.id == selectedId, onClick = { onOpen(c.id) }, onLongClick = { menuFor = c })
                }
                if (state.archived.isNotEmpty()) {
                    item(key = "archived") {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = viewModel::toggleArchived).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(GcIcons.Archive, contentDescription = null, tint = extra.muted)
                            Text(stringResource(R.string.chat_archived_n, state.archived.size), style = MaterialTheme.typography.titleSmall, color = extra.muted, modifier = Modifier.weight(1f))
                            Icon(if (state.showArchived) GcIcons.ChevronUp else GcIcons.ChevronDown, contentDescription = null, tint = extra.faint)
                        }
                    }
                    if (state.showArchived) {
                        items(state.archived, key = { "a" + it.id }) { c ->
                            ChannelRow(c, selected = c.id == selectedId, onClick = { onOpen(c.id) }, onLongClick = { menuFor = c })
                        }
                    }
                }
                item(key = "note") {
                    Text(
                        stringResource(
                            when {
                                !state.loaded -> R.string.chat_loading
                                !state.any -> R.string.chat_ch_empty
                                state.rows.isEmpty() && state.query.isNotBlank() -> R.string.chat_empty_filtered
                                else -> R.string.chat_ch_note
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = extra.muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 20.dp),
                    )
                }
            }
        }
    }
    menuFor?.let { c ->
        ModalBottomSheet(onDismissRequest = { menuFor = null }, containerColor = MaterialTheme.colorScheme.surface) {
            val ctx = LocalContext.current
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(ChatText.title(ctx, c), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                val push = channelPush(c)
                MenuRow(if (push) GcIcons.BellOff else GcIcons.Bell, stringResource(if (push) R.string.chat_ch_mute_on else R.string.chat_ch_mute_off)) { menuFor = null; viewModel.setPush(c, !push) }
                if (c.unread > 0) MenuRow(GcIcons.Check, stringResource(R.string.chat_ch_mark_read)) { menuFor = null; viewModel.markRead(c) }
                MenuRow(GcIcons.Archive, stringResource(if (c.archived) R.string.chat_unarchive else R.string.chat_archive)) { menuFor = null; viewModel.archive(c) }
            }
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = GateControlTheme.extraColors.muted)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** A channel as a row (like a muted chat: its number grey). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(c: ChatConversation, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    val last = c.lastMessage
    Row(
        Modifier.fillMaxWidth().heightIn(min = 74.dp)
            .then(if (selected) Modifier.background(extra.accentBg) else Modifier)
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick, onLongClickLabel = stringResource(R.string.chat_actions))
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
                    modifier = Modifier.weight(1f),
                )
                Text(
                    ChatText.listTime(ctx, last?.createdAt ?: c.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(GcIcons.Tv, contentDescription = null, tint = extra.faint, modifier = Modifier.size(14.dp))
                Text(
                    last?.let { ChatText.content(ctx, it, c, null) } ?: stringResource(R.string.chat_no_messages),
                    style = MaterialTheme.typography.bodyMedium,
                    color = extra.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!channelPush(c)) Icon(GcIcons.BellOff, contentDescription = stringResource(R.string.chat_muted), tint = extra.faint, modifier = Modifier.size(16.dp))
                if (c.unread > 0) {
                    Spacer(Modifier.width(2.dp))
                    Box(
                        Modifier.height(22.dp).widthIn(min = 22.dp).clip(RoundedCornerShape(11.dp)).background(extra.panel2).padding(horizontal = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (c.unread > 99) "99+" else c.unread.toString(), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold), color = extra.muted)
                    }
                }
            }
        }
    }
}
