package com.gatecontrol.android.tv.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.gatecontrol.android.chat.ChatApi
import com.gatecontrol.android.chat.ChatConversation
import com.gatecontrol.android.chat.ChatEvent
import com.gatecontrol.android.chat.ChatJson
import com.gatecontrol.android.chat.ChatMessage
import com.gatecontrol.android.chat.ChatResult
import com.gatecontrol.android.chat.ChatSuggestion
import com.gatecontrol.android.drive.DriveMediaLogic
import com.gatecontrol.android.tv.zuhause.TvSourceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** What the TV holds of the chats (only in memory: a restart asks again). */
data class TvChatsData(
    /** Every chat of the person (the settings choose from them; the page shows the chosen ones). */
    val conversations: List<ChatConversation> = emptyList(),
    val state: TvSourceState = TvSourceState.LOADING,
    /** The person's own account id (`GET me`), for the own reaction; null until known. */
    val myId: Long? = null,
    /** Per chat: the loaded history, oldest first. */
    val messages: Map<Long, List<ChatMessage>> = emptyMap(),
    /** Per chat: older messages exist. */
    val more: Map<Long, Boolean> = emptyMap(),
    /** Per chat: how its history loaded. */
    val history: Map<Long, TvSourceState> = emptyMap(),
    /** Per chat: `readUpTo` / `deliveredUpTo` of the other side. */
    val readUpTo: Map<Long, Long?> = emptyMap(),
    val deliveredUpTo: Map<Long, Long?> = emptyMap(),
    /** Texts on their way or not sent (no outbox: they stay only until sent, retried or dropped). */
    val pending: List<TvChatPending> = emptyList(),
)

/** A message of another person arrived in [conversationId] (for the hint over other apps; never its text). */
data class TvChatArrival(val conversationId: Long, val messageId: Long)

/**
 * „Nachrichten › Chats“ of GateControl TV: the messenger's app API ([ChatApi], the phone's, always with `?bridged=1`)
 * with the rights of the person who paired the TV. A slim repository — no Room, no outbox, no worker: the list and the
 * open chat's history live in memory, realtime events ([onEvent]) load what changed. A text goes out once; when it
 * fails it stays as „Nicht gesendet“ until „Erneut versuchen“ sends it again with the same clientId.
 */
@Singleton
class TvChats @Inject constructor(private val api: ChatApi) {

    /** Replaceable in tests. */
    internal var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listMutex = Mutex()

    private val _data = MutableStateFlow(TvChatsData())
    val data: StateFlow<TvChatsData> = _data.asStateFlow()

    private val _arrivals = MutableSharedFlow<TvChatArrival>(extraBufferCapacity = 16)

    /** Messages of others as they arrive in realtime (the hint decides whether and how to show them). */
    val arrivals: SharedFlow<TvChatArrival> = _arrivals.asSharedFlow()

    /** The chat shown on the screen right now (page open, app in front): only it sends read receipts. */
    @Volatile
    var reading: Long? = null
        private set

    @Volatile
    private var lastList = 0L

    /** Small previews of photos by media id (the large picture is the viewer's). */
    private val thumbs = object : LruCache<String, Bitmap>(THUMB_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Replaceable in tests. */
    internal var clock: () -> Long = System::currentTimeMillis

    // ─── the list ──────────────────────────────────────────────────

    /** All chats; at most every [MIN_GAP_MS] unless [force]d. */
    suspend fun refresh(force: Boolean = false) = listMutex.withLock {
        val now = clock()
        if (!force && _data.value.state == TvSourceState.OK && now - lastList < MIN_GAP_MS) return@withLock
        lastList = now
        if (_data.value.myId == null) {
            (api.me() as? ChatResult.Ok)?.value?.user?.userId?.let { id -> _data.update { it.copy(myId = id) } }
        }
        when (val r = api.conversations(null)) {
            is ChatResult.Ok -> _data.update { it.copy(conversations = r.value.conversations, state = TvSourceState.OK) }
            else -> _data.update { it.copy(state = state(r)) }
        }
    }

    fun refreshAsync(force: Boolean = false) {
        scope.launch { refresh(force) }
    }

    /** One chat again (an event named it): replaced in the list, or added. */
    private suspend fun reloadConversation(id: Long): ChatConversation? {
        val r = api.conversation(id)
        if (r is ChatResult.Failed && r.http == 404) {
            _data.update { d -> d.copy(conversations = d.conversations.filterNot { it.id == id }) }
            return null
        }
        val c = (r as? ChatResult.Ok)?.value ?: return null
        _data.update { d ->
            val list = if (d.conversations.any { it.id == id }) d.conversations.map { if (it.id == id) c else it } else d.conversations + c
            d.copy(conversations = list)
        }
        return c
    }

    // ─── the history ───────────────────────────────────────────────

    /** The newest page of [conversationId], merged into what is there. */
    suspend fun load(conversationId: Long) {
        val r = api.messages(conversationId, limit = TvChatLogic.PAGE)
        _data.update { d ->
            if (r is ChatResult.Ok) {
                val page = r.value
                val keep = d.messages[conversationId].orEmpty()
                d.copy(
                    messages = d.messages + (conversationId to TvChatLogic.merge(keep, page.messages)),
                    more = if (keep.isEmpty()) d.more + (conversationId to page.hasMore) else d.more,
                    history = d.history + (conversationId to TvSourceState.OK),
                    readUpTo = d.readUpTo + (conversationId to page.readUpTo),
                    deliveredUpTo = d.deliveredUpTo + (conversationId to page.deliveredUpTo),
                )
            } else {
                d.copy(history = d.history + (conversationId to state(r)))
            }
        }
        if (r is ChatResult.Ok) markRead(conversationId)
    }

    fun loadAsync(conversationId: Long) {
        scope.launch { load(conversationId) }
    }

    /** Older messages of [conversationId] (▲ at the top of the history). */
    suspend fun loadOlder(conversationId: Long) {
        val first = _data.value.messages[conversationId]?.firstOrNull()?.id ?: return
        val r = api.messages(conversationId, before = first, limit = TvChatLogic.PAGE)
        if (r !is ChatResult.Ok) return
        _data.update { d ->
            d.copy(
                messages = d.messages + (conversationId to TvChatLogic.merge(r.value.messages, d.messages[conversationId].orEmpty(), max = Int.MAX_VALUE)),
                more = d.more + (conversationId to r.value.hasMore),
            )
        }
    }

    /**
     * The chat [conversationId] is on the screen ([open] true) or not any more. Only while one is, a new message in it
     * sends the read receipt; a chat in the list is not read.
     */
    fun setReading(conversationId: Long?) {
        reading = conversationId
        if (conversationId != null) scope.launch { markRead(conversationId) }
    }

    private suspend fun markRead(conversationId: Long) {
        if (reading != conversationId) return
        val d = _data.value
        val c = d.conversations.firstOrNull { it.id == conversationId } ?: return
        val last = d.messages[conversationId]?.lastOrNull { !it.mine }?.id ?: return
        if (c.unread <= 0 && (c.me.lastReadId ?: 0L) >= last) return
        val r = api.read(conversationId, last)
        if (r is ChatResult.Ok) {
            _data.update { s -> s.copy(conversations = s.conversations.map { if (it.id == conversationId) it.copy(unread = r.value ?: 0) else it }) }
        }
    }

    // ─── sending ───────────────────────────────────────────────────

    /**
     * Sends [raw] into [conversationId] (with [replyTo]) under [clientId] — the same id for a retry. true: stored on
     * the server; false: „Nicht gesendet“ stays with its reason.
     */
    suspend fun send(conversationId: Long, raw: String, replyTo: Long? = null, clientId: String = TvChatLogic.newClientId()): Boolean {
        val text = TvChatLogic.text(raw) ?: return false
        val c = _data.value.conversations.firstOrNull { it.id == conversationId }
        if (c != null && TvChatLogic.block(c) != null) return false
        val entry = TvChatPending(clientId, conversationId, text, replyTo, clock())
        _data.update { d -> d.copy(pending = d.pending.filterNot { it.clientId == clientId } + entry) }
        val r = api.send(conversationId, ChatJson.sendBody(clientId, ChatMessage.KIND_TEXT, text, replyTo = replyTo))
        if (r is ChatResult.Ok) {
            stored(r.value)
            return true
        }
        val error = TvChatLogic.sendError(r)
        Timber.d("TV chats: not sent (%s)", error)
        _data.update { d -> d.copy(pending = d.pending.map { if (it.clientId == clientId) it.copy(error = error) else it }) }
        return false
    }

    /** „Erneut versuchen“ on a text the TV could not send: the same clientId (the server stores it once). */
    suspend fun retry(clientId: String): Boolean {
        val p = _data.value.pending.firstOrNull { it.clientId == clientId } ?: return false
        return send(p.conversationId, p.text, p.replyTo, p.clientId)
    }

    /** „Erneut versuchen“ on an own message a bridge did not take (`POST messages/:id/resend`). */
    suspend fun resend(message: ChatMessage): Boolean {
        val r = api.resend(message.id)
        if (r is ChatResult.Ok) stored(r.value)
        return r is ChatResult.Ok
    }

    /** „Verwerfen“ on a text that was not sent. */
    fun drop(clientId: String) = _data.update { d -> d.copy(pending = d.pending.filterNot { it.clientId == clientId }) }

    private fun stored(m: ChatMessage) = _data.update { d ->
        d.copy(
            pending = d.pending.filterNot { it.clientId == m.clientId },
            messages = d.messages + (m.conversationId to TvChatLogic.merge(d.messages[m.conversationId].orEmpty(), listOf(m))),
            conversations = d.conversations.map { if (it.id == m.conversationId) it.copy(lastMessage = m) else it },
        )
    }

    /** 👍 ❤️ 😂 on [message] — the same again takes it back. */
    suspend fun react(message: ChatMessage, emoji: String): Boolean {
        val again = TvChatLogic.ownReaction(message, _data.value.myId) == emoji
        val r = api.react(message.id, if (again) null else emoji)
        if (r is ChatResult.Ok) {
            _data.update { d ->
                val list = d.messages[message.conversationId] ?: return@update d
                d.copy(messages = d.messages + (message.conversationId to list.map { if (it.id == message.id) it.copy(reactions = r.value) else it }))
            }
        }
        return r is ChatResult.Ok
    }

    /** The messenger's quick answers to [messageId] (`GET messages/:id/suggest`); none when it has none. */
    suspend fun suggestions(messageId: Long): List<String> =
        (api.suggest(messageId) as? ChatResult.Ok<ChatSuggestion>)?.value?.items.orEmpty()

    // ─── photos ────────────────────────────────────────────────────

    /** A photo's small preview ([ChatMessage.media]'s thumb, else the file itself, made small); null when not to be had. */
    suspend fun thumb(m: ChatMessage): Bitmap? {
        val media = m.media ?: return null
        val id = media.thumbId ?: media.id
        thumbs.get(id)?.let { return it }
        val b = picture(id, THUMB_SIDE_PX) ?: return null
        thumbs.put(id, b)
        return b
    }

    fun peekThumb(m: ChatMessage): Bitmap? = m.media?.let { thumbs.get(it.thumbId ?: it.id) }

    /** A file of the messenger as a picture no larger than [sidePx]. */
    suspend fun picture(mediaId: String, sidePx: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val response = api.download(mediaId) ?: return@withContext null
            response.body()?.use { body ->
                if (!response.isSuccessful) return@withContext null
                val bytes = body.bytes()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                val opts = BitmapFactory.Options().apply { inSampleSize = DriveMediaLogic.sampleSize(bounds.outWidth, bounds.outHeight, sidePx) }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("TV chats: picture not loaded (%s)", e.javaClass.simpleName)
            null
        }
    }

    // ─── realtime ──────────────────────────────────────────────────

    /** A messenger event of the push stream (only message.new / message.update and what changes a chat). */
    fun onEvent(e: ChatEvent) {
        scope.launch {
            when (e) {
                is ChatEvent.MessageNew -> {
                    val c = reloadConversation(e.conversationId)
                    if (isLoaded(e.conversationId)) load(e.conversationId)
                    val mine = c?.lastMessage?.takeIf { it.id == e.messageId }?.mine == true ||
                        (e.clientId != null && _data.value.pending.any { it.clientId == e.clientId })
                    if (!mine) _arrivals.tryEmit(TvChatArrival(e.conversationId, e.messageId))
                }
                is ChatEvent.MessageUpdate, is ChatEvent.MessageDelete, is ChatEvent.Reaction -> if (isLoaded(e.conversationId)) load(e.conversationId)
                is ChatEvent.Read -> if (e.self) reloadConversation(e.conversationId) else e.upTo?.let { up ->
                    _data.update { d ->
                        d.copy(
                            readUpTo = d.readUpTo + (e.conversationId to (up.readUpTo ?: d.readUpTo[e.conversationId])),
                            deliveredUpTo = d.deliveredUpTo + (e.conversationId to (up.deliveredUpTo ?: d.deliveredUpTo[e.conversationId])),
                        )
                    }
                }
                is ChatEvent.ConversationUpdate -> {
                    reloadConversation(e.conversationId)
                    if (e.history && isLoaded(e.conversationId)) load(e.conversationId)
                }
                is ChatEvent.ConversationRemove -> _data.update { d ->
                    d.copy(conversations = d.conversations.filterNot { it.id == e.conversationId }, messages = d.messages - e.conversationId)
                }
                // no typing on the TV, nothing of status and lists
                else -> Unit
            }
        }
    }

    /** The push stream (re)opened: what happened meanwhile is loaded again. */
    fun onStreamOpen() {
        scope.launch {
            refresh(force = true)
            reading?.let { load(it) }
        }
    }

    private fun isLoaded(conversationId: Long): Boolean = _data.value.messages.containsKey(conversationId)

    /** Unpaired: nothing of this login stays. */
    fun clear() {
        _data.value = TvChatsData()
        lastList = 0L
        reading = null
        thumbs.evictAll()
    }

    private fun state(r: ChatResult<*>): TvSourceState = when (r) {
        is ChatResult.Ok -> TvSourceState.OK
        ChatResult.Offline, ChatResult.NotConfigured -> TvSourceState.OFFLINE
        is ChatResult.Failed -> TvSourceState.MISSING
    }

    companion object {
        const val MIN_GAP_MS = 30_000L
        const val THUMB_SIDE_PX = 360
        private const val THUMB_CACHE_BYTES = 12 * 1024 * 1024
    }
}
