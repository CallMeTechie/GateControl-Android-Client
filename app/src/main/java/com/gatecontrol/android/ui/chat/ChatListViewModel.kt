package com.gatecontrol.android.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.chat.ChatConversation
import com.gatecontrol.android.chat.ChatPrefs
import com.gatecontrol.android.chat.ChatProfile
import com.gatecontrol.android.chat.ChatRepository
import com.gatecontrol.android.chat.ChatResult
import com.gatecontrol.android.chat.ChatShareHandover
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

enum class ChatFilter { All, Unread, Groups }

data class ChatListUiState(
    val conversations: List<ChatConversation> = emptyList(),
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
)

/** The chat list: cached conversations, the pinned GateControl inbox, filters and the chat preferences. */
@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val repository: ChatRepository,
    pushRepository: PushRepository,
) : ViewModel() {

    private val filter = MutableStateFlow(ChatFilter.All)
    private val query = MutableStateFlow("")
    private val loaded = MutableStateFlow(false)
    private val now = MutableStateFlow(System.currentTimeMillis())

    private val inbox = pushRepository.inbox.items.map { items ->
        items.firstOrNull() to items.count { it.isUnread }
    }

    private val base = combine(repository.conversations, filter, query, loaded) { convs, f, q, l -> Base(convs, f, q, l) }

    private data class Base(val convs: List<ChatConversation>, val filter: ChatFilter, val query: String, val loaded: Boolean)

    val uiState: StateFlow<ChatListUiState> = combine(
        base,
        inbox,
        combine(repository.profile, repository.sync) { p, s -> p to s },
        combine(repository.typing, now) { t, n -> t to n },
        ChatShareHandover.pending,
    ) { b, (latest, inboxUnread), (profile, sync), (typing, nowMs), share ->
        val myId = profile?.user?.userId
        val sorted = b.convs.sortedByDescending { it.activityAt }
        val q = b.query.trim().lowercase()
        val visible = sorted.filter { c ->
            when (b.filter) {
                ChatFilter.All -> true
                ChatFilter.Unread -> c.unread > 0
                ChatFilter.Groups -> c.isGroup
            }
        }.filter { c ->
            q.isEmpty() || (c.title ?: c.peer?.name ?: "").lowercase().contains(q) ||
                c.members.any { (it.name ?: "").lowercase().contains(q) }
        }
        ChatListUiState(
            conversations = visible,
            filter = b.filter,
            query = b.query,
            unreadChats = sorted.count { it.unread > 0 },
            inboxLatest = latest,
            inboxUnread = inboxUnread,
            profile = profile,
            sync = sync,
            typing = sorted.associate { it.id to ChatTimeline.typingNow(typing, it.id, myId, nowMs) }.filterValues { it.isNotEmpty() },
            loaded = b.loaded || sorted.isNotEmpty(),
            sharing = share != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatListUiState())

    init {
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

    fun setFilter(f: ChatFilter) {
        filter.value = f
    }

    fun setQuery(q: String) {
        query.value = q.take(80)
    }

    fun refresh() {
        viewModelScope.launch {
            repository.refreshProfile()
            repository.syncConversations()
        }
    }

    fun cancelShare() = ChatShareHandover.clear()

    /** Chat preferences (read receipts, "schreibt …", content in notifications). */
    fun setPrefs(prefs: ChatPrefs, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val r = repository.putPrefs(prefs.readReceipts, prefs.typing, prefs.preview)
            onResult(r is ChatResult.Ok)
        }
    }
}
