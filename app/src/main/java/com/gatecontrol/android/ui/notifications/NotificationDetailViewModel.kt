package com.gatecontrol.android.ui.notifications

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.notify.PushPrefsStore
import com.gatecontrol.android.notify.PushRepository
import com.gatecontrol.android.notify.model.PushAction
import com.gatecontrol.android.notify.model.PushActionType
import com.gatecontrol.android.notify.model.PushMessage
import com.gatecontrol.android.push.PortalLinkResolver
import com.gatecontrol.android.push.PushNavTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotificationDetailUiState(
    val loaded: Boolean = false,
    val message: PushMessage? = null,
    /** Server host ("von gc.example.com"). */
    val host: String = "",
    /** Label of a plugin topic from the server. */
    val topicLabel: String? = null,
    val busyAction: String? = null,
)

/** One-shot effects of an action (navigation, browser, toast). */
sealed interface DetailEffect {
    data class Navigate(val target: PushNavTarget) : DetailEffect
    data class OpenUrl(val url: String) : DetailEffect
    data object PortalUnavailable : DetailEffect
    data class Muted(val topic: String) : DetailEffect
    data object Done : DetailEffect
}

@HiltViewModel
class NotificationDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: PushRepository,
    private val portalLinkResolver: PortalLinkResolver,
    setupRepository: SetupRepository,
    store: PushPrefsStore,
) : ViewModel() {

    companion object {
        const val ARG_ID = "id"
    }

    private val id: Long = savedStateHandle.get<Long>(ARG_ID) ?: savedStateHandle.get<String>(ARG_ID)?.toLongOrNull() ?: -1L
    private val host = runCatching { java.net.URI(setupRepository.getServerUrl()).host }.getOrNull().orEmpty()
    private val loaded = MutableStateFlow(false)
    private val busy = MutableStateFlow<String?>(null)
    private val effects = Channel<DetailEffect>(Channel.BUFFERED)
    val effect: Flow<DetailEffect> = effects.receiveAsFlow()

    val uiState: StateFlow<NotificationDetailUiState> = combine(
        repository.inbox.items, store.topics, loaded, busy,
    ) { items, topics, isLoaded, busyAction ->
        val m = items.firstOrNull { it.id == id }
        NotificationDetailUiState(
            loaded = isLoaded,
            message = m,
            host = host,
            topicLabel = m?.let { msg -> topics.firstOrNull { it.id == msg.topic }?.label },
            busyAction = busyAction,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, NotificationDetailUiState())

    init {
        viewModelScope.launch {
            repository.inbox.load()
            loaded.value = true
            // Opening the message marks it read here, on the server and on the person's other devices.
            if (repository.inbox.get(id)?.isUnread == true) repository.markRead(listOf(id))
        }
    }

    fun runAction(action: PushAction) {
        if (busy.value != null) return
        viewModelScope.launch {
            busy.value = action.id
            try {
                when (action.type) {
                    PushActionType.OPEN_APP_ROUTE -> {
                        repository.markRead(listOf(id), action.id)
                        // Routes without a screen in the app (gateways, plugins) end here, on the message.
                        val target = PushNavTarget.forRoute(action.target, id)
                        if (target != PushNavTarget.Detail(id)) effects.send(DetailEffect.Navigate(target))
                    }
                    PushActionType.OPEN_PORTAL -> {
                        repository.markRead(listOf(id), action.id)
                        val url = portalLinkResolver.resolve(action.target)
                        effects.send(if (url != null) DetailEffect.OpenUrl(url) else DetailEffect.PortalUnavailable)
                    }
                    PushActionType.MUTE_1H -> {
                        val topic = repository.inbox.get(id)?.topic
                        repository.runAction(id, action)
                        if (topic != null) effects.send(DetailEffect.Muted(topic))
                    }
                    else -> {
                        repository.runAction(id, action)
                        effects.send(DetailEffect.Done)
                    }
                }
            } finally {
                busy.value = null
            }
        }
    }

    /** "1 Stunde stumm schalten" for messages without their own mute action. */
    fun muteTopic() {
        runAction(PushAction(PushActionType.MUTE_1H, PushActionType.MUTE_1H, PushActionType.MUTE_1H))
    }
}
