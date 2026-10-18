package com.gatecontrol.android.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.chat.ChatApi
import com.gatecontrol.android.chat.ChatApproverChoice
import com.gatecontrol.android.chat.ChatExtrasRepository
import com.gatecontrol.android.chat.ChatHomeAction
import com.gatecontrol.android.chat.ChatHomeJson
import com.gatecontrol.android.chat.ChatMessage
import com.gatecontrol.android.chat.ChatRepository
import com.gatecontrol.android.chat.ChatResult
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.familie.errorCode
import com.gatecontrol.android.familie.places.RulePlaceWatcher
import com.gatecontrol.android.familie.shopping.ShoppingList
import com.gatecontrol.android.familie.shopping.ShoppingLogic
import com.gatecontrol.android.familie.shopping.ShoppingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** What the Familie plugin adds to the attach sheet right now. */
data class ChatFamilieOffer(
    /** The Familie plugin is there for the caller. */
    val present: Boolean = false,
    val tasks: Boolean = false,
    val shopping: Boolean = false,
    /** "Orte für Regeln teilen" is on (live arrival hint); null: Familie without places for rules. */
    val placesShared: Boolean? = null,
)

/** A shopping list with its open entries (the sheet "Einkaufsliste"). */
data class ChatShoppingEntry(val list: ShoppingList, val open: Int, val preview: List<String>)

/** One-shot feedback of the home sheets. */
sealed interface ChatHomeNotice {
    data class Done(val res: Int, val arg: String? = null) : ChatHomeNotice
    /** A chat error code (ChatText.actionError). */
    data class ChatError(val code: String?) : ChatHomeNotice
    /** A Familie error code (familieErrorText). */
    data class FamilieError(val code: String?) : ChatHomeNotice
}

/**
 * The attach sheet's home part (mockups ChatAttach, ChatDoorRequest,
 * ChatShareDevice, ChatTask, ShoppingChat): door requests to chosen
 * approvers (Messenger C2), shared devices that switch off at the end (C3),
 * tasks (Familie B4) and shopping lists (B3) from the chat.
 */
@HiltViewModel
class ChatHomeViewModel @Inject constructor(
    private val api: ChatApi,
    private val extras: ChatExtrasRepository,
    private val repository: ChatRepository,
    private val familieApi: FamilieApi,
    private val familie: FamilieRepository,
    private val shopping: ShoppingRepository,
    plugins: PluginDirectory,
    rulePlaces: RulePlaceWatcher,
) : ViewModel() {

    val offer: StateFlow<ChatFamilieOffer> = combine(plugins.state, familie.profile, rulePlaces.share, rulePlaces.supported) { s, p, share, supported ->
        val on = s.familieApi && p != null
        ChatFamilieOffer(
            present = on,
            tasks = on && p?.modules?.tasks == true,
            shopping = on && p?.modules?.shopping == true,
            placesShared = if (on && supported) share else null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatFamilieOffer())

    val shoppingLists: StateFlow<List<ChatShoppingEntry>> = combine(shopping.data, familie.profile) { data, p ->
        val view = ShoppingLogic.view(data, checkedBy = p?.me?.userId?.toString())
        val counts = ShoppingLogic.openCounts(view)
        view.lists.map { l -> ChatShoppingEntry(l, counts[l.id] ?: 0, view.items.filter { it.listId == l.id && !it.checked }.take(4).map { it.text }) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Familie user id of the caller (to leave oneself out of the people chips). */
    val familieMe: StateFlow<Int?> = familie.profile.map { it?.me?.userId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _requestable = MutableStateFlow<com.gatecontrol.android.chat.ChatRequestable?>(null)
    /** What the caller may ask for, with the approvers of the chosen group; null while loading. */
    val requestable: StateFlow<com.gatecontrol.android.chat.ChatRequestable?> = _requestable.asStateFlow()

    private val _requestableFallback = MutableStateFlow(false)
    /** The server has no `actions/requestable` (older messenger): only the own actions are offered. */
    val requestableFallback: StateFlow<Boolean> = _requestableFallback.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val notices = Channel<ChatHomeNotice>(Channel.BUFFERED)
    val notice: Flow<ChatHomeNotice> = notices.receiveAsFlow()

    init {
        viewModelScope.launch { familie.cachedProfile() }
    }

    /** Loads the Familie membership when the sheet opens (tasks / shopping) and the shopping lists. */
    fun prepare() {
        viewModelScope.launch {
            if (familie.profile.value == null) familie.refreshProfile()
            if (offer.value.shopping) shopping.syncAsync()
        }
    }

    private var requestJob: kotlinx.coroutines.Job? = null

    /** Loads (again, when the approvers change) what [conversationId]'s caller may ask for. */
    fun loadRequestable(conversationId: Long, approvers: ChatApproverChoice? = null) {
        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            when (val r = api.requestableActions(approvers?.let(ChatHomeJson::approversQuery), conversationId)) {
                is ChatResult.Ok -> {
                    _requestableFallback.value = false
                    _requestable.value = r.value
                }
                else -> {
                    // Older messenger (404) or no answer: the own actions, with a hint.
                    _requestableFallback.value = true
                    extras.refreshActions()
                    _requestable.value = com.gatecontrol.android.chat.ChatRequestable(
                        actions = extras.actions.value.orEmpty().map { com.gatecontrol.android.chat.ChatRequestableAction(it, emptyList(), self = true) },
                        approvers = null, approversDefault = null, canRequest = null, requestTtlMin = null,
                    )
                }
            }
        }
    }

    private fun run(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }

    private fun chatCode(r: ChatResult<*>): String? = when (r) {
        is ChatResult.Failed -> r.code
        ChatResult.Offline -> "offline"
        else -> null
    }

    /** "Anfrage senden": a request to [approvers]; an older messenger that drops the [note] gets it as a text message after. */
    fun sendRequest(conversationId: Long, action: ChatHomeAction, approvers: ChatApproverChoice, note: String?, onDone: () -> Unit) = run {
        val r = extras.sendStructured(conversationId, ChatMessage.KIND_ACTION, "action", ChatHomeJson.requestBody(action.id, action.plugin, approvers, note))
        if (r is ChatResult.Ok) {
            val text = note?.trim().orEmpty()
            if (text.isNotEmpty() && r.value.action?.note == null) repository.sendText(conversationId, text, r.value.id)
            onDone()
        } else {
            notices.trySend(ChatHomeNotice.ChatError(chatCode(r)))
        }
    }

    /** "Mit <Name> teilen". */
    fun sendGrant(conversationId: Long, action: ChatHomeAction, toUserId: Long, untilMs: Long, offAtEnd: Boolean, onDone: () -> Unit) = run {
        val body = ChatHomeJson.grantBody(action.id, action.plugin, toUserId, Instant.ofEpochMilli(untilMs).toString(), offAtEnd)
        val r = extras.sendStructured(conversationId, ChatMessage.KIND_ACTION, "action", body)
        if (r is ChatResult.Ok) onDone() else notices.trySend(ChatHomeNotice.ChatError(chatCode(r)))
    }

    /** "Aufgabe senden" (Familie B4): the plugin posts the card into this chat. */
    fun sendTask(conversationId: Long, title: String, assigneeId: Long?, dueMs: Long?, repeat: String?, onDone: () -> Unit) = run {
        val r = familieApi.quickTask(title, assigneeId?.toInt(), dueMs?.let { Instant.ofEpochMilli(it).toString() }, repeat, conversationId)
        if (r is FamilieResult.Ok) {
            onDone()
            // The task exists; its card may still have failed (`card.ok: false`).
            if (r.value.ok == false) notices.trySend(ChatHomeNotice.Done(com.gatecontrol.android.R.string.chat_task_no_card, r.value.code ?: "?"))
            else notices.trySend(ChatHomeNotice.Done(com.gatecontrol.android.R.string.chat_task_sent))
            repository.fetchChanged(conversationId)
        } else {
            notices.trySend(ChatHomeNotice.FamilieError(r.errorCode()))
        }
    }

    /** "Ich gehe einkaufen" from the chat. */
    fun shoppingGoing(conversationId: Long, listId: String, onDone: () -> Unit) = run {
        shopping.sync()
        val r = shopping.going(listId)
        if (r is FamilieResult.Ok) {
            onDone()
            notices.trySend(ChatHomeNotice.Done(com.gatecontrol.android.R.string.familie_shopping_going_sent))
            repository.fetchChanged(conversationId)
        } else {
            notices.trySend(ChatHomeNotice.FamilieError(r.errorCode()))
        }
    }

    /** "<Name> bitten" from the chat; [notify] direct (a direct chat) or family. */
    fun shoppingAssign(conversationId: Long, listId: String, userId: Long, notify: String, onDone: () -> Unit) = run {
        val r = shopping.assign(listId, userId.toInt(), notify)
        if (r is FamilieResult.Ok) {
            onDone()
            if (r.value.ok == false) notices.trySend(ChatHomeNotice.Done(com.gatecontrol.android.R.string.chat_shop_not_delivered, r.value.code ?: "?"))
            repository.fetchChanged(conversationId)
        } else {
            notices.trySend(ChatHomeNotice.FamilieError(r.errorCode()))
        }
    }
}

