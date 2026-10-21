package com.gatecontrol.android.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.chat.ChatRepository
import com.gatecontrol.android.push.routes.NotifyDest
import com.gatecontrol.android.push.routes.NotifyRouteChat
import com.gatecontrol.android.push.routes.NotifyRouteTopic
import com.gatecontrol.android.push.routes.NotifyRoutes
import com.gatecontrol.android.push.routes.NotifyRoutesRepository
import com.gatecontrol.android.push.routes.RoutesLoad
import com.gatecontrol.android.push.routes.RoutesSave
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotifyRoutesUiState(
    val loading: Boolean = true,
    val routes: NotifyRoutes? = null,
    /** An older server (no such page). */
    val unsupported: Boolean = false,
    /** The last load failed (shown with "Erneut versuchen" when nothing is known yet). */
    val failed: Boolean = false,
    /** The topic whose choice (bottom sheet) is open. */
    val picking: NotifyRouteTopic? = null,
    /** The topic being saved. */
    val saving: String? = null,
)

/** A short message of the screen after a change. */
sealed interface NotifyRoutesMessage

/** A change the server refused ([code] as NotifyRoutesRepository names it). */
data class RoutesNotice(val code: String) : NotifyRoutesMessage

/** Saved to the own chat of a source: [chat] was just created or an existing one is used. */
data class RoutesChatNotice(val chat: NotifyRouteChat) : NotifyRoutesMessage

/** Settings › Benachrichtigungen › "Wo Mitteilungen erscheinen" (and from the chat settings). */
@HiltViewModel
class NotifyRoutesViewModel @Inject constructor(
    private val repository: NotifyRoutesRepository,
    private val chats: ChatRepository,
) : ViewModel() {

    private val state = MutableStateFlow(NotifyRoutesUiState())
    val uiState: StateFlow<NotifyRoutesUiState> = state.asStateFlow()

    private val notices = Channel<NotifyRoutesMessage>(Channel.BUFFERED)
    val notice: Flow<NotifyRoutesMessage> = notices.receiveAsFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            state.update { it.copy(loading = true) }
            apply(repository.load())
        }
    }

    private fun apply(r: RoutesLoad) {
        state.update {
            when (r) {
                is RoutesLoad.Ok -> it.copy(loading = false, routes = r.routes, unsupported = false, failed = false)
                RoutesLoad.Unsupported -> it.copy(loading = false, unsupported = true, failed = false)
                RoutesLoad.Failed -> it.copy(loading = false, failed = true)
            }
        }
    }

    /** Opens the choice for [topicId]; locked topics and a server without chats have none. */
    fun pick(topicId: String) {
        val s = state.value
        val routes = s.routes ?: return
        val t = routes.topic(topicId) ?: return
        if (t.locked || !routes.sink || s.saving != null) return
        state.update { it.copy(picking = t) }
    }

    fun dismiss() {
        state.update { it.copy(picking = null) }
    }

    /**
     * [dest] chosen for the open topic: shown at once, sent to the server;
     * a refusal puts the topic back as it was and says why.
     */
    fun choose(dest: NotifyDest) {
        val s = state.value
        val t = s.picking ?: return
        val routes = s.routes ?: return
        state.update { it.copy(picking = null) }
        if (s.saving != null || dest == routes.effective(t) || !routes.editable(t) || dest !in routes.options(t)) return
        state.update { it.copy(routes = it.routes?.with(t.id, dest), saving = t.id) }
        viewModelScope.launch {
            when (val r = repository.set(t.id, dest)) {
                is RoutesSave.Ok -> {
                    if (dest == NotifyDest.OWN) {
                        // The source's own chat may be new: into the chat list now, not only with its live event.
                        chats.conversationEnsured(r.chat?.conversationId)
                        r.chat?.let { notices.send(RoutesChatNotice(it)) }
                    }
                    val next = r.routes ?: (repository.load() as? RoutesLoad.Ok)?.routes
                    state.update { it.copy(routes = next ?: it.routes, saving = null) }
                }
                is RoutesSave.Refused -> {
                    state.update { it.copy(routes = it.routes?.restore(t), saving = null) }
                    notices.send(RoutesNotice(r.code))
                    // What the server has now (another adult set the family chat, the messenger went away, …).
                    if (r.code != NotifyRoutesRepository.ERR_NETWORK) apply(repository.load())
                }
            }
        }
    }

    private fun NotifyRoutes.restore(t: NotifyRouteTopic): NotifyRoutes =
        copy(groups = groups.map { g -> g.copy(topics = g.topics.map { if (it.id == t.id) t else it }) })
}

/** Whether the entries to "Wo Mitteilungen erscheinen" show: only when the server has the page. */
@HiltViewModel
class NotifyRoutesEntryViewModel @Inject constructor(
    private val repository: NotifyRoutesRepository,
) : ViewModel() {

    val visible: StateFlow<Boolean> = repository.supported.map { it == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), repository.supported.value == true)

    init {
        viewModelScope.launch { repository.probe() }
    }
}
