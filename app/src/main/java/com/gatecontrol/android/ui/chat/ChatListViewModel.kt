package com.gatecontrol.android.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.chat.ChatConversation
import com.gatecontrol.android.chat.msgTools
import com.gatecontrol.android.chat.status
import com.gatecontrol.android.chat.stories
import com.gatecontrol.android.chat.ChatProfile
import com.gatecontrol.android.chat.ChatRepository
import com.gatecontrol.android.chat.ChatShareHandover
import com.gatecontrol.android.chat.ChatFeatureHint
import com.gatecontrol.android.chat.ChatUpdateHint
import com.gatecontrol.android.chat.ChatSync
import com.gatecontrol.android.chat.ChatTimeline
import com.gatecontrol.android.notify.PushRepository
import com.gatecontrol.android.notify.model.PushMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ChatFilter { All, Unread, Groups, Events }

data class ChatListUiState(
    /** Section "Chats" (while searching or sharing: every match, pinned ones too). */
    val conversations: List<ChatConversation> = emptyList(),
    /** Messenger 1.6: chats pinned to the list (`listPinned`), under "Angeheftet" after GateControl. */
    val pinned: List<ChatConversation> = emptyList(),
    val filter: ChatFilter = ChatFilter.All,
    val query: String = "",
    val unreadChats: Int = 0,
    /** Newest message of the GateControl inbox (pinned entry) and its unread count. */
    val inboxLatest: PushMessage? = null,
    val inboxUnread: Int = 0,
    val profile: ChatProfile? = null,
    val sync: ChatSync = ChatSync.Idle,
    /** conversation → users typing now. */
    val typing: Map<Long, List<Long>> = emptyMap(),
    val loaded: Boolean = false,
    /** Something was shared from another app: pick a chat for it. */
    val sharing: Boolean = false,
    /** Messenger 1.4: custom lists (filter chips), the one picked, archived chats. */
    val lists: List<com.gatecontrol.android.chat.ChatList> = emptyList(),
    val listId: String? = null,
    val archived: List<ChatConversation> = emptyList(),
    val showArchived: Boolean = false,
    /** Messenger 1.4: the status strip. */
    val feed: com.gatecontrol.android.chat.ChatStatusFeed? = null,
    /**
     * Messenger 1.14, chats over a bridge: the services of the listed chats (id → name; a filter each once there is
     * one), the filter picked ([com.gatecontrol.android.chat.ChatBridgeChat.NET_GC] = only GateControl), whether the
     * person has such chats at all, and conversation → the contacts typing there.
     */
    val networks: List<Pair<String, String>> = emptyList(),
    val net: String? = null,
    val bridged: Boolean = false,
    val contactTyping: Map<Long, List<String?>> = emptyMap(),
)

/** The chat list: cached conversations, the pinned GateControl inbox, filters and the chat preferences. */
@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val repository: ChatRepository,
    pushRepository: PushRepository,
    private val updateHint: ChatUpdateHint,
    private val extras: com.gatecontrol.android.chat.ChatExtrasRepository,
) : ViewModel() {

    private val listId = MutableStateFlow<String?>(null)
    private val showArchived = MutableStateFlow(false)
    private val net = MutableStateFlow<String?>(null)
    private val extra14 = combine(extras.lists, listId, showArchived, extras.feed, net) { l, id, a, f, n -> Extra14(l, id?.takeIf { i -> l.any { it.id == i } }, a, f, n) }

    private data class Extra14(
        val lists: List<com.gatecontrol.android.chat.ChatList>,
        val listId: String?,
        val showArchived: Boolean,
        val feed: com.gatecontrol.android.chat.ChatStatusFeed?,
        val net: String?,
    )

    /** The server offers chat features this app does not know yet (and the hint was not dismissed for them). */
    val showUpdateHint: StateFlow<Boolean> = combine(repository.profile, updateHint.dismissed) { p, d -> ChatFeatureHint.show(p?.features, d) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Hidden until the server names a further unknown feature. */
    fun dismissUpdateHint() = updateHint.dismiss(repository.profile.value?.features)

    private val filter = MutableStateFlow(ChatFilter.All)
    private val query = MutableStateFlow("")
    private val loaded = MutableStateFlow(false)
    private val now = MutableStateFlow(System.currentTimeMillis())

    private val inbox = pushRepository.inbox.items.map { items ->
        items.firstOrNull() to items.count { it.isUnread }
    }

    private val base = combine(repository.conversations, filter, query, loaded, extra14) { convs, f, q, l, e -> Base(convs, f, q, l, e) }

    private data class Base(val convs: List<ChatConversation>, val filter: ChatFilter, val query: String, val loaded: Boolean, val e: Extra14)

    val uiState: StateFlow<ChatListUiState> = combine(
        base,
        inbox,
        combine(repository.profile, repository.sync) { p, s -> p to s },
        combine(repository.typing, repository.contactTyping, now) { t, ct, n -> Triple(t, ct, n) },
        ChatShareHandover.pending,
    ) { b, (latest, inboxUnread), (profile, sync), (typing, contactTyping, nowMs), share ->
        val myId = profile?.user?.userId
        val listed = b.convs.sortedByDescending { it.activityAt }
        // Messenger 1.14: a chat over a bridge took text only — what is shared from another app (files) could not go there;
        // 1.15: it can where the service takes files (`bridge.supports`).
        val all = if (share != null) listed.filter { com.gatecontrol.android.chat.ChatBridgeChat.attach(it) } else listed
        val networks = com.gatecontrol.android.chat.ChatBridgeChat.networks(all)
        // a filter whose service has no chat any more is none
        val netNow = b.e.net?.takeIf { n -> networks.isNotEmpty() && (n == com.gatecontrol.android.chat.ChatBridgeChat.NET_GC || networks.any { it.first == n }) }
        // Messenger 1.4: archived chats live in their own section (search finds them too).
        val q = b.query.trim().lowercase()
        val sorted = if (q.isEmpty()) all.filter { !it.archived } else all
        val inList = com.gatecontrol.android.chat.ChatToolsLogic.inList(sorted, b.e.lists, b.e.listId)
        val visible = inList.filter { com.gatecontrol.android.chat.ChatBridgeChat.inNet(it, netNow) }.filter { c ->
            when (b.filter) {
                ChatFilter.All -> true
                ChatFilter.Unread -> c.unread > 0
                ChatFilter.Groups -> c.isGroup
                ChatFilter.Events -> c.nextEvent != null
            }
        }.filter { c ->
            q.isEmpty() || (c.title ?: c.peer?.name ?: "").lowercase().contains(q) ||
                c.members.any { (it.name ?: "").lowercase().contains(q) }
        }
        // Messenger 1.6: "Angeheftet" (GateControl, then the pinned chats) above "Chats" — not while searching or sharing.
        val sections = com.gatecontrol.android.chat.ChatListSections.split(visible, sectioned = q.isEmpty() && share == null)
        ChatListUiState(
            conversations = sections.chats,
            pinned = sections.pinned,
            filter = b.filter,
            query = b.query,
            unreadChats = sorted.count { it.unread > 0 },
            inboxLatest = latest,
            inboxUnread = inboxUnread,
            profile = profile,
            sync = sync,
            typing = sorted.associate { it.id to ChatTimeline.typingNow(typing, it.id, myId, nowMs) }.filterValues { it.isNotEmpty() },
            loaded = b.loaded || all.isNotEmpty(),
            sharing = share != null,
            lists = b.e.lists,
            listId = b.e.listId,
            archived = all.filter { it.archived },
            showArchived = b.e.showArchived,
            feed = b.e.feed,
            networks = networks,
            net = netNow,
            bridged = com.gatecontrol.android.chat.ChatBridgeChat.any(profile?.features, listed),
            contactTyping = sorted.mapNotNull { c -> ChatTimeline.contactsTypingNow(contactTyping, c.id, nowMs)?.let { c.id to it } }.toMap(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatListUiState())

    init {
        viewModelScope.launch {
            if (repository.profile.value == null) repository.refreshProfile()
            val f = repository.profile.value?.features
            if (f?.status == true || f?.stories == true) extras.refreshFeedIfStale()
            if (f?.msgTools == true) extras.refreshLists()
        }
        viewModelScope.launch {
            repository.refreshIfStale()
            if (repository.profile.value == null) repository.refreshProfile()
            repository.syncConversations()
            loaded.value = true
        }
        // "schreibt …" ends by itself; without live events the list is refreshed now and then.
        viewModelScope.launch {
            var ticks = 0
            while (isActive) {
                delay(1_000)
                now.value = System.currentTimeMillis()
                if (++ticks % 30 == 0 && !repository.live) repository.syncConversations()
            }
        }
    }

    /** Conversations dropped in this process (left, removed): the detail pane lets go of them. */
    val dropped: StateFlow<Set<Long>> = repository.dropped

    /** Ids of the cached conversations; null until the list has been loaded once. */
    val listedIds: StateFlow<Set<Long>?> = combine(repository.conversations, loaded) { convs, l ->
        if (l || convs.isNotEmpty()) convs.map { it.id }.toSet() else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setFilter(f: ChatFilter) {
        filter.value = f
    }

    /** A custom list as filter (null: none). */
    fun setList(id: String?) {
        listId.value = id
    }

    /** Messenger 1.14: only the chats of one service ([net] null: all, picked again: all). */
    fun setNet(n: String?) {
        net.value = if (net.value == n) null else n
    }

    fun toggleArchived() {
        showArchived.value = !showArchived.value
    }

    fun archive(c: ChatConversation, archived: Boolean) {
        viewModelScope.launch { extras.archive(c.id, archived) }
    }

    /** Adds [c] to list [l] (or takes it out). */
    fun toggleInList(l: com.gatecontrol.android.chat.ChatList, c: ChatConversation) {
        viewModelScope.launch { extras.saveLists(extras.lists.value.map { if (it.id == l.id) com.gatecontrol.android.chat.ChatToolsLogic.toggle(it, c.id) else it }) }
    }

    fun refreshFeed() = extras.refreshFeedSoon(0L)

    fun setQuery(q: String) {
        query.value = q.take(80)
    }

    fun refresh() {
        viewModelScope.launch {
            repository.refreshProfile()
            repository.syncConversations()
            extras.refreshFeedSoon(0L)
        }
    }

    fun cancelShare() = ChatShareHandover.clear()
}
