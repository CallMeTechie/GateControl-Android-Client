package com.gatecontrol.android.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.notify.PushPrefsStore
import com.gatecontrol.android.notify.PushRepository
import com.gatecontrol.android.notify.model.PushMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InboxUiState(
    /** Messages of the selected filter, newest first. */
    val items: List<PushMessage> = emptyList(),
    /** Selected topic; null = all. */
    val filter: String? = null,
    /** Topics that have messages, in order of the newest message (filter chips). */
    val topics: List<String> = emptyList(),
    /** Topic labels from the server (`hello`), for plugin topics. */
    val topicLabels: Map<String, String> = emptyMap(),
    val unread: Int = 0,
    /** Push is switched on on this device. */
    val enabled: Boolean = true,
    val refreshing: Boolean = false,
    /** The last refresh failed: the list is the saved one. */
    val offline: Boolean = false,
)

/** "Mitteilungen": cached inbox, refreshed from the server; filter by topic. */
private fun PushMessage.isExpired(now: java.time.Instant = java.time.Instant.now()): Boolean =
    expiresAt?.let { runCatching { !java.time.Instant.parse(it).isAfter(now) }.getOrDefault(false) } ?: false

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val repository: PushRepository,
    store: PushPrefsStore,
) : ViewModel() {

    private val filter = MutableStateFlow<String?>(null)
    private val refreshState = MutableStateFlow(false to false) // refreshing, offline

    /** Unread count for the bell on the start screen. */
    val unreadCount: StateFlow<Int> = repository.inbox.items
        .map { list -> list.count { it.isUnread && !it.isExpired() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val uiState: StateFlow<InboxUiState> = combine(
        repository.inbox.items,
        filter,
        store.settings,
        store.topics,
        refreshState,
    ) { all, f, settings, topics, (refreshing, offline) ->
        // Expired messages are gone on the server; the saved list may still hold them.
        val now = java.time.Instant.now()
        val items = all.filterNot { it.isExpired(now) }
        val present = items.map { it.topic }.distinct()
        val active = f?.takeIf { it in present }
        InboxUiState(
            items = if (active == null) items else items.filter { it.topic == active },
            filter = active,
            topics = present,
            topicLabels = topics.associate { it.id to it.label },
            unread = items.count { it.isUnread },
            enabled = settings.enabled,
            refreshing = refreshing,
            offline = offline,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, InboxUiState())

    init {
        viewModelScope.launch { repository.inbox.load() }
    }

    fun refresh() {
        if (refreshState.value.first) return
        viewModelScope.launch {
            refreshState.value = true to refreshState.value.second
            val ok = repository.refreshInbox()
            refreshState.value = false to !ok
        }
    }

    fun setFilter(topic: String?) {
        filter.value = topic
    }

    fun markAllRead() {
        viewModelScope.launch { repository.markAllRead() }
    }
}
