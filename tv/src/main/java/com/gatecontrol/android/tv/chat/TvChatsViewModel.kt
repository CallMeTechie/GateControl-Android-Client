package com.gatecontrol.android.tv.chat

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.chat.ChatConversation
import com.gatecontrol.android.chat.ChatMessage
import com.gatecontrol.android.tv.TvSettings
import com.gatecontrol.android.tv.zuhause.TvSourceState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What „Nachrichten › Chats“, the rail's count and the start row show. */
data class TvChatsUi(
    val state: TvSourceState = TvSourceState.LOADING,
    /** The chats the TV shows (the chosen ones, else the family's groups), the newest first. */
    val chats: List<ChatConversation> = emptyList(),
    /** The chat open on the right; null: none chosen. */
    val open: ChatConversation? = null,
    val messages: List<ChatMessage> = emptyList(),
    val pending: List<TvChatPending> = emptyList(),
    val history: TvSourceState = TvSourceState.LOADING,
    val more: Boolean = false,
    val readUpTo: Long? = null,
    val deliveredUpTo: Long? = null,
    val myId: Long? = null,
    /** Unread messages over the shown chats (the rail, the start page). */
    val unread: Int = 0,
    /** The start row „Neue Nachrichten“: who and how many. */
    val news: List<TvChatNews> = emptyList(),
    /** Einstellungen › Nachrichten: every chat to choose from, and the chosen ones. */
    val choosable: List<ChatConversation> = emptyList(),
    val chosen: Set<Long> = emptySet(),
)

/** „Nachrichten › Chats“ (stage 3e): the list, the open chat's history, answers and reactions. */
@HiltViewModel
class TvChatsViewModel @Inject constructor(
    private val chats: TvChats,
    private val settings: TvSettings,
) : ViewModel() {

    private val _open = MutableStateFlow<Long?>(null)

    val ui: StateFlow<TvChatsUi> = combine(chats.data, settings.chats, _open) { d, chosen, openId ->
        val visible = TvChatLogic.visible(d.conversations, chosen)
        val open = openId?.let { id -> visible.firstOrNull { it.id == id } }
        val messages = open?.let { d.messages[it.id] }.orEmpty()
        TvChatsUi(
            state = d.state,
            chats = visible,
            open = open,
            messages = messages,
            pending = open?.let { TvChatLogic.pendingIn(d.pending, messages, it.id) }.orEmpty(),
            history = open?.let { d.history[it.id] } ?: TvSourceState.LOADING,
            more = open?.let { d.more[it.id] } == true,
            readUpTo = open?.let { d.readUpTo[it.id] },
            deliveredUpTo = open?.let { d.deliveredUpTo[it.id] },
            myId = d.myId,
            unread = TvChatLogic.unread(visible),
            news = TvChatLogic.news(visible, ""),
            choosable = TvChatLogic.choosable(d.conversations),
            chosen = TvChatLogic.chosenOrDefault(d.conversations, chosen),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TvChatsUi())

    /** The page, the start page or the settings show: the list (at most every half minute unless [force]d). */
    fun refresh(force: Boolean = false) = chats.refreshAsync(force)

    /** A chat of the list on the right: its newest messages. */
    fun open(conversationId: Long) {
        _open.value = conversationId
        chats.loadAsync(conversationId)
    }

    /**
     * The open chat is on the screen ([on]: the page shows, GateControl in front) — only then a new message in it is
     * marked read. Leaving the page, the app or the chat ends it.
     */
    fun setReading(on: Boolean) = chats.setReading(if (on) _open.value else null)

    fun loadOlder() {
        val id = _open.value ?: return
        viewModelScope.launch { chats.loadOlder(id) }
    }

    /** Sends [text] into the open chat; [clientId] stays the same when the composer sends it again. */
    suspend fun send(text: String, replyTo: Long?, clientId: String): Boolean {
        val id = _open.value ?: return false
        return chats.send(id, text, replyTo, clientId)
    }

    fun retry(clientId: String) {
        viewModelScope.launch { chats.retry(clientId) }
    }

    fun resend(m: ChatMessage) {
        viewModelScope.launch { chats.resend(m) }
    }

    fun drop(clientId: String) = chats.drop(clientId)

    fun react(m: ChatMessage, emoji: String) {
        viewModelScope.launch { chats.react(m, emoji) }
    }

    suspend fun suggestions(messageId: Long): List<String> = chats.suggestions(messageId)

    suspend fun thumb(m: ChatMessage): Bitmap? = chats.thumb(m)

    fun peekThumb(m: ChatMessage): Bitmap? = chats.peekThumb(m)

    /** Einstellungen › Nachrichten: [id] shown on the TV or not. */
    fun choose(id: Long, on: Boolean) = settings.setChats(TvChatLogic.choose(chats.data.value.conversations, settings.chats.value, id, on))

    /** Back to the default (only the family's groups). */
    fun chooseDefault() = settings.setChats(null)
}
