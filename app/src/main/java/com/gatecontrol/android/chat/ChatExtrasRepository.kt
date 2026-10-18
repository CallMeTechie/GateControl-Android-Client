package com.gatecontrol.android.chat

import android.net.Uri
import com.gatecontrol.android.support.CrashReports
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Messenger 1.4 beyond the messages themselves: status and stories (feed,
 * own status, stories with media), server-side preferences (privacy,
 * notify, chats), blocks, custom lists, home actions, Familie places,
 * reminders and the actions on poll / event / arrival / action messages.
 * Every answer that carries the changed message or conversation is stored
 * in [ChatRepository]'s cache right away; otherwise the change is fetched.
 */
@Singleton
class ChatExtrasRepository @Inject constructor(
    private val repository: ChatRepository,
    private val api: ChatApi,
    private val media: ChatMediaStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

    private val _feed = MutableStateFlow<ChatStatusFeed?>(null)
    /** `GET status/feed` (null until loaded or without `status`/`stories`). */
    val feed: StateFlow<ChatStatusFeed?> = _feed.asStateFlow()

    private val _lists = MutableStateFlow<List<ChatList>>(emptyList())
    val lists: StateFlow<List<ChatList>> = _lists.asStateFlow()

    private val _blocks = MutableStateFlow<List<ChatPerson>?>(null)
    val blocks: StateFlow<List<ChatPerson>?> = _blocks.asStateFlow()

    private val _actions = MutableStateFlow<List<ChatHomeAction>?>(null)
    /** Home actions the caller may run (`GET actions`); null until loaded. */
    val actions: StateFlow<List<ChatHomeAction>?> = _actions.asStateFlow()

    private val _places = MutableStateFlow<List<ChatPlace>?>(null)
    val places: StateFlow<List<ChatPlace>?> = _places.asStateFlow()

    private var feedJob: Job? = null
    @Volatile private var feedAtMs = 0L

    private val features: ChatFeatures? get() = repository.profile.value?.features

    init {
        scope.launch {
            repository.statusEvents.collect { refreshFeedSoon() }
        }
        scope.launch {
            repository.accountEvents.collect { e -> if (e.type == "lists.update") refreshLists() }
        }
        // Another login: nothing of the old one stays.
        scope.launch {
            repository.available.drop(1).collect { on ->
                if (!on) clear()
            }
        }
    }

    private fun clear() {
        _feed.value = null
        _lists.value = emptyList()
        _blocks.value = null
        _actions.value = null
        _places.value = null
        feedAtMs = 0L
    }

    // ─── status and stories ─────────────────────────────────────────

    /** The feed again in a moment (several live events at once fetch once). */
    fun refreshFeedSoon(delayMs: Long = 400L) {
        if (feedJob?.isActive == true) return
        feedJob = scope.launch {
            delay(delayMs)
            refreshFeed()
        }
    }

    /** The feed unless it was fetched within [maxAgeMs]. */
    fun refreshFeedIfStale(maxAgeMs: Long = 60_000L) {
        if (System.currentTimeMillis() - feedAtMs > maxAgeMs) refreshFeedSoon(0L)
    }

    suspend fun refreshFeed(): ChatResult<ChatStatusFeed> {
        val f = features
        if (f != null && !f.status && !f.stories) return ChatResult.Failed("unsupported", 0)
        val r = api.feed()
        if (r is ChatResult.Ok) {
            _feed.value = r.value
            feedAtMs = System.currentTimeMillis()
            r.value.me?.status?.let { repository.updateOwnStatus(it) }
        }
        return r
    }

    /** The caller's own status: from the feed, else from `GET me`. */
    fun ownStatus(): ChatStatus? = _feed.value?.me?.status ?: repository.profile.value?.status

    suspend fun setStatus(status: ChatStatus): ChatResult<ChatStatus?> {
        val r = api.putStatus(status)
        if (r is ChatResult.Ok) {
            val s = r.value?.takeIf { it.text != null || it.preset != ChatStatus.PRESET_NONE } ?: status
            repository.updateOwnStatus(s)
            _feed.update { f -> f?.copy(me = (f.me ?: ChatFeedItem(ChatPerson(0, null, null, null, null), null, emptyList(), false)).copy(status = s)) }
            refreshFeedSoon()
        }
        return r
    }

    suspend fun clearStatus(): ChatResult<Unit> {
        val r = api.deleteStatus()
        if (r is ChatResult.Ok) {
            repository.updateOwnStatus(null)
            _feed.update { f -> f?.copy(me = f.me?.copy(status = null)) }
        }
        return r
    }

    /** A text story. */
    suspend fun postTextStory(text: String, bg: String, font: String, visibility: String, userIds: List<Long>, eventMessageId: Long?): ChatResult<ChatStory?> =
        afterStory(api.postStory(ChatJson14.storyBody(ChatStory.KIND_TEXT, text, bg, font, null, null, null, visibility, userIds, eventMessageId)))

    /** A photo story (scaled like a chat picture, with its preview). */
    suspend fun postImageStory(uri: Uri, caption: String?, visibility: String, userIds: List<Long>, eventMessageId: Long?): ChatResult<ChatStory?> {
        val limits = repository.profile.value?.limits ?: ChatLimits()
        val clientId = repository.newClientId()
        val img = media.prepareImage(uri, clientId, limits.imageMaxSide, limits.thumbMaxBytes) ?: return ChatResult.Failed("unreadable", 0)
        try {
            val thumbId = img.thumb?.let { t -> (api.upload(t, "image/webp") as? ChatResult.Ok)?.value }
            val up = api.upload(img.file, img.mime)
            if (up !is ChatResult.Ok) return up.asFailure()
            media.prime(up.value, img.file)
            return afterStory(api.postStory(ChatJson14.storyBody(ChatStory.KIND_IMAGE, caption, null, null, up.value, thumbId, null, visibility, userIds, eventMessageId)))
        } finally {
            media.dropOutboxFiles(img.file.path, img.thumb?.path)
        }
    }

    /** A video story (a frame as preview, length and size). */
    suspend fun postVideoStory(uri: Uri, caption: String?, visibility: String, userIds: List<Long>, eventMessageId: Long?): ChatResult<ChatStory?> {
        val profile = repository.profile.value
        val limits = profile?.limits ?: ChatLimits()
        val clientId = repository.newClientId()
        val prepared = media.prepareFile(uri, clientId, limits.maxBytes, limits.types).getOrElse { e ->
            return ChatResult.Failed(
                when ((e as? PrepareException)?.error) {
                    is PrepareError.TooLarge -> "too_large"
                    PrepareError.TypeNotAllowed -> "unsupported_type"
                    else -> "unreadable"
                },
                0,
            )
        }
        val file = media.withPreview(prepared, clientId, thumbs = true, videoMeta = true, thumbMax = limits.thumbMaxBytes, maxSide = limits.thumbMaxSide)
        try {
            val thumbId = file.thumb?.let { t -> (api.upload(t, "image/webp") as? ChatResult.Ok)?.value }
            val up = api.upload(file.file, file.mime)
            if (up !is ChatResult.Ok) return up.asFailure()
            return afterStory(api.postStory(ChatJson14.storyBody(ChatStory.KIND_VIDEO, caption, null, null, up.value, thumbId, file.video, visibility, userIds, eventMessageId)))
        } finally {
            media.dropOutboxFiles(file.file.path, file.thumb?.path)
        }
    }

    private fun afterStory(r: ChatResult<ChatStory?>): ChatResult<ChatStory?> {
        if (r is ChatResult.Ok) refreshFeedSoon(0L)
        return r
    }

    suspend fun deleteStory(id: String): ChatResult<Unit> {
        val r = api.deleteStory(id)
        if (r is ChatResult.Ok) {
            _feed.update { f -> f?.copy(me = f.me?.copy(stories = f.me.stories.filter { it.id != id })) }
            refreshFeedSoon()
        }
        return r
    }

    /** Seen: marked here at once, told to the server once. */
    fun markSeen(userId: Long, story: ChatStory) {
        if (story.seen) return
        _feed.update { f -> f?.let { ChatStatusLogic.markSeen(it, userId, story.id) } }
        scope.launch { api.storySeen(story.id) }
    }

    suspend fun viewers(storyId: String): ChatResult<List<ChatStoryViewer>> = api.storyViewers(storyId)

    suspend fun react(story: ChatStory, emoji: String): ChatResult<Unit> = api.storyReact(story.id, emoji)

    /** An answer to [story] of [owner]: a direct message with `replyStory` (the chat is opened or created). */
    suspend fun replyToStory(owner: ChatPerson, story: ChatStory, text: String): ChatResult<ChatMessage> {
        val conv = repository.openDirect(owner.userId)
        if (conv !is ChatResult.Ok) return conv.asFailure()
        val ref = ChatReplyStory(story.id, owner.userId, story.kind, story.text, story.thumbMediaId ?: story.mediaId.takeIf { story.kind == ChatStory.KIND_IMAGE })
        return sendStructured(conv.value.id, ChatMessage.KIND_TEXT, "replyStory", ChatJson14.writeReplyStory(ref), text)
    }

    // ─── preferences ────────────────────────────────────────────────

    /** `PUT prefs` with the 1.4 keys (and the 1.0 ones unchanged). */
    suspend fun putPrefs(prefs14: ChatPrefs14): ChatResult<Unit> {
        val body = ChatJson14.writePrefs14(JsonObject(), prefs14)
        val r = api.putPrefsBody(body)
        if (r is ChatResult.Ok) repository.updatePrefs(r.value.first, r.value.second)
        return if (r is ChatResult.Ok) ChatResult.Ok(Unit) else r.asFailure()
    }

    // ─── blocks, archive, pins, lists ───────────────────────────────

    suspend fun refreshBlocks(): ChatResult<List<ChatPerson>> {
        val r = api.blocks()
        if (r is ChatResult.Ok) _blocks.value = r.value
        return r
    }

    suspend fun block(person: ChatPerson): ChatResult<Unit> {
        val r = api.block(person.userId)
        if (r is ChatResult.Ok) _blocks.value = r.value ?: (_blocks.value.orEmpty() + person).distinctBy { p -> p.userId }
        return if (r is ChatResult.Ok) ChatResult.Ok(Unit) else r.asFailure()
    }

    suspend fun unblock(userId: Long): ChatResult<Unit> {
        val r = api.unblock(userId)
        if (r is ChatResult.Ok) _blocks.value = r.value ?: _blocks.value?.filter { p -> p.userId != userId }
        return if (r is ChatResult.Ok) ChatResult.Ok(Unit) else r.asFailure()
    }

    fun isBlocked(userId: Long): Boolean = _blocks.value?.any { it.userId == userId } == true

    suspend fun archive(conversationId: Long, archived: Boolean): ChatResult<Unit> {
        val r = api.archive(conversationId, archived)
        if (r is ChatResult.Ok) {
            val c = r.value ?: repository.cachedConversation(conversationId)?.copy(archived = archived)
            if (c != null) repository.storeConversation(c)
        }
        return if (r is ChatResult.Ok) ChatResult.Ok(Unit) else r.asFailure()
    }

    suspend fun pin(conversationId: Long, messageId: Long, pin: Boolean): ChatResult<Unit> {
        val r = if (pin) api.pin(conversationId, messageId) else api.unpin(conversationId, messageId)
        if (r is ChatResult.Ok) {
            val c = r.value
            if (c != null) {
                repository.storeConversation(c)
            } else {
                repository.cachedConversation(conversationId)?.let { cached ->
                    val pinned = if (pin) ChatToolsLogic.pinAdd(cached.pinned, messageId) else cached.pinned - messageId
                    repository.storeConversation(cached.copy(pinned = pinned))
                }
                repository.refreshConversation(conversationId)
            }
        }
        return if (r is ChatResult.Ok) ChatResult.Ok(Unit) else r.asFailure()
    }

    suspend fun refreshLists(): ChatResult<List<ChatList>> {
        val r = api.lists()
        if (r is ChatResult.Ok) _lists.value = r.value
        return r
    }

    suspend fun saveLists(lists: List<ChatList>): ChatResult<List<ChatList>> {
        val before = _lists.value
        _lists.value = lists
        val r = api.putLists(lists)
        if (r is ChatResult.Ok) _lists.value = r.value else _lists.value = before
        return r
    }

    // ─── message tools ──────────────────────────────────────────────

    suspend fun export(conversationId: Long): ChatResult<ChatExportPart> = api.export(conversationId)

    suspend fun remind(messageId: Long, atIso: String): ChatResult<Unit> = api.remind(messageId, atIso)

    suspend fun reminders(): ChatResult<List<ChatReminder>> = api.reminders()

    suspend fun deleteReminder(id: String): ChatResult<Unit> = api.deleteReminder(id)

    suspend fun suggest(messageId: Long): ChatResult<ChatSuggestion> = api.suggest(messageId)

    suspend fun toList(messageId: Long, items: List<String>): ChatResult<Unit> = api.toList(messageId, items)

    suspend fun toCalendar(messageId: Long, date: ChatSuggestedDate, fallbackTitle: String): ChatResult<Unit> =
        api.toCalendar(messageId, date.title ?: fallbackTitle, date.startsAt, null)

    suspend fun refreshPlaces(): ChatResult<List<ChatPlace>> {
        val r = api.places()
        if (r is ChatResult.Ok) _places.value = r.value
        else if (_places.value == null) _places.value = emptyList()
        return r
    }

    suspend fun refreshActions(): ChatResult<List<ChatHomeAction>> {
        if (features?.actions == false) {
            _actions.value = emptyList()
            return ChatResult.Ok(emptyList())
        }
        val r = api.homeActions()
        if (r is ChatResult.Ok) _actions.value = r.value
        else if (_actions.value == null) _actions.value = emptyList()
        return r
    }

    // ─── sending structured messages ────────────────────────────────

    /**
     * A message with a 1.4 object ([field] = [obj]): sent directly (not
     * through the outbox — it cannot be shown before the server made it),
     * retried once with the same `clientId` when the network drops.
     */
    suspend fun sendStructured(conversationId: Long, kind: String, field: String, obj: JsonObject, text: String? = null): ChatResult<ChatMessage> {
        val clientId = repository.newClientId()
        val body = ChatJson.sendBody(clientId, kind, text?.trim()?.takeIf { it.isNotEmpty() }).apply { add(field, obj) }
        var r = api.send(conversationId, body)
        if (r == ChatResult.Offline) {
            delay(1_500)
            r = api.send(conversationId, body)
        }
        if (r is ChatResult.Ok) repository.storeSent(r.value)
        return r
    }

    suspend fun sendPoll(conversationId: Long, question: String, options: List<String>, multi: Boolean, anonymous: Boolean, allowAdd: Boolean, closesAt: String?) =
        sendStructured(conversationId, ChatMessage.KIND_POLL, "poll", ChatJson14.pollSendBody(question, options, multi, anonymous, allowAdd, closesAt))

    suspend fun sendEvent(conversationId: Long, event: JsonObject) = sendStructured(conversationId, ChatMessage.KIND_EVENT, "event", event)

    suspend fun sendArrival(conversationId: Long, dest: ChatEventPlace, onArriveActionId: String?, onArrivePlugin: String? = null) =
        sendStructured(conversationId, ChatMessage.KIND_ARRIVAL, "arrival", ChatJson14.arrivalSendBody(dest, onArriveActionId, onArrivePlugin))

    suspend fun sendAction(conversationId: Long, mode: String, action: ChatHomeAction, grantTo: Long? = null, until: String? = null) =
        sendStructured(conversationId, ChatMessage.KIND_ACTION, "action", ChatJson14.actionSendBody(mode, action.id, grantTo, until, action.plugin))

    // ─── actions on messages ────────────────────────────────────────

    /** The changed message stored when the answer carries it; else its change is fetched. */
    private suspend fun afterMessage(conversationId: Long, r: ChatResult<ChatMessage?>): ChatResult<Unit> {
        if (r is ChatResult.Ok) {
            val m = r.value
            if (m != null) repository.storeMessages(listOf(m)) else repository.fetchChanged(conversationId)
            return ChatResult.Ok(Unit)
        }
        return r.asFailure()
    }

    suspend fun vote(m: ChatMessage, optionIds: List<String>) = afterMessage(m.conversationId, api.vote(m.id, optionIds))

    suspend fun addOption(m: ChatMessage, text: String) = afterMessage(m.conversationId, api.addOption(m.id, text))

    suspend fun closePoll(m: ChatMessage) = afterMessage(m.conversationId, api.closePoll(m.id))

    suspend fun nudge(m: ChatMessage) = afterMessage(m.conversationId, api.nudge(m.id))

    suspend fun rsvp(m: ChatMessage, answer: String?, guests: Int?) = afterMessage(m.conversationId, api.rsvp(m.id, answer, guests))

    suspend fun checklist(m: ChatMessage, itemId: String, done: Boolean) = afterMessage(m.conversationId, api.checklist(m.id, itemId, done))

    suspend fun cancelEvent(m: ChatMessage) =
        afterMessage(m.conversationId, api.patchEvent(m.id, JsonObject().apply { addProperty("cancelled", true) }))

    /** → whether the destination is reached now (then the message is fetched again). */
    suspend fun arrivalPosition(conversationId: Long, messageId: Long, body: JsonObject): ChatResult<Boolean> {
        val r = api.arrivalPosition(messageId, body)
        if (r is ChatResult.Ok && r.value) repository.fetchChanged(conversationId)
        return r
    }

    suspend fun arrivalStop(conversationId: Long, messageId: Long) = afterMessage(conversationId, api.arrivalStop(messageId))

    /** A run's answer: the message stored; a run that did not work answers its text ([ChatRunResult.ok] false). */
    private suspend fun afterRun(conversationId: Long, r: ChatResult<ChatRunResult>): ChatResult<ChatRunResult> {
        if (r is ChatResult.Ok) {
            val m = r.value.message
            if (m != null) repository.storeMessages(listOf(m)) else repository.fetchChanged(conversationId)
        }
        return r
    }

    suspend fun actionRun(m: ChatMessage, actionId: String? = null, plugin: String? = null) = afterRun(m.conversationId, api.actionRun(m.id, actionId, plugin))

    suspend fun actionApprove(m: ChatMessage) = afterRun(m.conversationId, api.actionApprove(m.id))

    suspend fun actionDeny(m: ChatMessage) = afterRun(m.conversationId, api.actionDeny(m.id))

    private val cardImages = android.util.LruCache<Long, String>(8)

    /** A plugin card's picture (base64), cached in memory. */
    suspend fun cardImage(messageId: Long): String? {
        cardImages.get(messageId)?.let { return it }
        val r = api.cardImage(messageId)
        return (r as? ChatResult.Ok)?.value?.also { cardImages.put(messageId, it) }
    }

    /** The cached message [id] (an arrival being shared). */
    suspend fun message(id: Long): ChatMessage? = repository.cachedMessage(id)

    /** Files of a chat export (text) written for sharing. */
    fun exportFile(dir: File, name: String, text: String): File? = runCatching {
        dir.mkdirs()
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L }?.forEach { it.delete() }
        File(dir, ChatMime.safeName(name, "txt")).apply { writeText(text) }
    }.onFailure { Timber.w("Chats: export not written (%s)", it.javaClass.simpleName) }.getOrNull()
}

/** Pure helpers of the message tools. Unit-tested. */
object ChatToolsLogic {
    const val MAX_PINS = 3

    /** [messageId] pinned: at the end, the oldest dropped beyond [MAX_PINS]. */
    fun pinAdd(pinned: List<Long>, messageId: Long): List<Long> = (pinned - messageId + messageId).takeLast(MAX_PINS)

    /** Conversations of the list [listId] among [conversations] (null list id: all). */
    fun inList(conversations: List<ChatConversation>, lists: List<ChatList>, listId: String?): List<ChatConversation> {
        if (listId == null) return conversations
        val ids = lists.firstOrNull { it.id == listId }?.conversationIds?.toSet() ?: return emptyList()
        return conversations.filter { it.id in ids }
    }

    /** [list] with [conversationId] added or removed. */
    fun toggle(list: ChatList, conversationId: Long): ChatList =
        list.copy(conversationIds = if (conversationId in list.conversationIds) list.conversationIds - conversationId else list.conversationIds + conversationId)

    /** A new list id (not in [existing]). */
    fun newListId(existing: List<ChatList>, nowMs: Long): String {
        var n = nowMs % 1_000_000_000L
        val ids = existing.map { it.id }.toSet()
        while ("l$n" in ids) n++
        return "l$n"
    }

    /** File name of a chat export: "Chat-Anna-2026-10-18.txt". */
    fun exportName(title: String, date: java.time.LocalDate): String {
        val safe = title.replace(Regex("[^\\p{L}\\p{N}_-]+"), "-").trim('-').take(40).ifEmpty { "Chat" }
        return "Chat-$safe-$date.txt"
    }
}
