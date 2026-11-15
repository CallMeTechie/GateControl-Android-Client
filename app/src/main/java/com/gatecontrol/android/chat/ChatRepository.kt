package com.gatecontrol.android.chat

import android.content.Context
import com.gatecontrol.android.chat.db.ChatDao
import com.gatecontrol.android.chat.db.ChatDatabase
import com.gatecontrol.android.chat.db.ConversationEntity
import com.gatecontrol.android.chat.db.KvEntity
import com.gatecontrol.android.chat.db.MessageEntity
import com.gatecontrol.android.chat.db.OutboxEntity
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.notify.PushClient
import com.gatecontrol.android.notify.PushStatus
import com.gatecontrol.android.support.CrashReports
import com.google.gson.JsonObject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of [ChatRepository.jumpTo]. */
enum class ChatJump {
    /** In the cache now: scroll to it. */
    Found,

    /** The server does not show it (hidden for the caller, deleted for good). */
    Missing,

    /** A server before Messenger 1.1.0 (no `around=`): load it by scrolling up. */
    Unsupported,
}

/** Where the device's copy of the chat list stands. */
sealed interface ChatSync {
    data object Idle : ChatSync
    data object Syncing : ChatSync
    data object Synced : ChatSync
    data object Offline : ChatSync
    data class Failed(val code: String) : ChatSync
}

/**
 * The chats of the messenger plugin: an offline-first copy in Room
 * ([ChatDatabase]) that the screens observe, kept up to date by the API
 * (`conversations?since=`, `messages?after=` / `changedSince=`) and the
 * plugin's live events on the push stream ([onEvent], [onStreamOpen]).
 * Without a live stream (push off) open screens poll instead.
 *
 * Sending goes through the outbox: the message is stored locally first and
 * shown as pending, then sent with its `clientId` — retried (WorkManager
 * when offline) without ever arriving twice.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Singleton
class ChatRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ChatApi,
    private val media: ChatMediaStore,
    private val setupRepository: SetupRepository,
    private val pluginDirectory: PluginDirectory,
    private val pushClient: PushClient,
    private val notifier: dagger.Lazy<ChatNotifier>,
    private val shortcuts: ChatShortcuts,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)
    private val db: ChatDatabase by lazy { ChatDatabase.build(context) }
    private val dao: ChatDao get() = db.dao()
    private val ownerMutex = Mutex()
    private val syncMutex = Mutex()
    private val outboxMutex = Mutex()

    /** Replaceable in tests. */
    internal var clock: () -> Long = System::currentTimeMillis

    /** The messenger is usable for this login (listed with an app tab). */
    val available: StateFlow<Boolean> = pluginDirectory.state.map { it.messengerTab }
        .stateIn(scope, SharingStarted.Eagerly, pluginDirectory.state.value.messengerTab)

    private val _profile = MutableStateFlow<ChatProfile?>(null)
    /** `GET me` (cached): the caller, preferences, features and limits. */
    val profile: StateFlow<ChatProfile?> = _profile.asStateFlow()

    private val _sync = MutableStateFlow<ChatSync>(ChatSync.Idle)
    val sync: StateFlow<ChatSync> = _sync.asStateFlow()

    /** conversation → user → "schreibt …" until (epoch ms). */
    private val _typing = MutableStateFlow<Map<Long, Map<Long, Long>>>(emptyMap())
    val typing: StateFlow<Map<Long, Map<Long, Long>>> = _typing.asStateFlow()

    private val _contactTyping = MutableStateFlow<Map<Long, ChatContactTyping>>(emptyMap())
    /** Messenger 1.14: conversation → the contacts of a bridged chat who type right now. */
    val contactTyping: StateFlow<Map<Long, ChatContactTyping>> = _contactTyping.asStateFlow()

    private val _active = MutableStateFlow<Long?>(null)
    /** The conversation open and visible right now (no notification for it). */
    val activeConversation: StateFlow<Long?> = _active.asStateFlow()

    private val _dropped = MutableStateFlow<Set<Long>>(emptySet())
    /**
     * Conversations dropped in this process (left, removed, gone from the
     * list): open screens and a selected detail pane let go of them.
     */
    val dropped: StateFlow<Set<Long>> = _dropped.asStateFlow()

    /** conversation → ranges of its cache that may miss messages (after a jump far back). */
    private val _gaps = MutableStateFlow<Map<Long, List<ChatGap>>>(emptyMap())

    private val _statusEvents = kotlinx.coroutines.flow.MutableSharedFlow<ChatEvent.StatusChanged>(extraBufferCapacity = 32)
    /** Messenger 1.4: status / story changes of a person ([ChatExtrasRepository] refreshes the feed). */
    val statusEvents: kotlinx.coroutines.flow.SharedFlow<ChatEvent.StatusChanged> = _statusEvents

    private val _accountEvents = kotlinx.coroutines.flow.MutableSharedFlow<ChatEvent.AccountChanged>(extraBufferCapacity = 8)
    /** Messenger 1.4: lists / reminders changed on another device. */
    val accountEvents: kotlinx.coroutines.flow.SharedFlow<ChatEvent.AccountChanged> = _accountEvents

    /** Live events arrive (push stream open): no polling needed. */
    val live: Boolean get() = pushClient.status.value is PushStatus.Connected

    @Volatile private var lastListSyncMs = 0L
    @Volatile private var lastProfileMs = 0L
    private val pending = ConcurrentHashMap<String, Job>()
    private var activeJob: Job? = null
    private var outboxJob: Job? = null
    private val lastTypingSent = ConcurrentHashMap<Long, Long>()

    init {
        // Another server or token: the cache belongs to the old login.
        scope.launch {
            setupRepository.revision.drop(1).collect {
                runCatching { ensureOwner() }
                api.channels = false
                api.people = false
                _profile.value = null
                lastListSyncMs = 0L
                lastProfileMs = 0L
            }
        }
        scope.launch {
            available.drop(1).distinctUntilChanged().collect { on -> if (on) refreshAll() else shortcuts.removeAll() }
        }
        // The share sheet entry ("GateDrop" / "An Chat senden") is switched by GateDropStartup.
        scope.launch { runCatching { ChatShareActivity.pruneCache(context) } }
        scope.launch {
            runCatching {
                ensureOwner()
                dao.kv(KV_PROFILE)?.let { raw -> parseObj(raw)?.let { ChatJson.profile(it) } }?.let {
                    if (_profile.value == null) {
                        api.channels = ChatChannels.on(it.features)
                        api.people = ChatPeople.on(it.features)
                        _profile.value = it
                    }
                }
                if (dao.outbox().any { it.state == OutboxEntity.STATE_QUEUED }) kickOutbox()
            }.onFailure { Timber.w("Chats: cache not opened (%s)", it.javaClass.simpleName) }
        }
    }

    private fun parseObj(raw: String): JsonObject? = ChatJsonPatch.parse(raw)

    private suspend fun ensureOwner() = ownerMutex.withLock {
        val owner = PluginDirectory.ownerKey(setupRepository.getServerUrl(), setupRepository.getApiToken())
        if (dao.kv(KV_OWNER) != owner) {
            dao.clearAll()
            dao.putKv(KvEntity(KV_OWNER, owner))
            _typing.value = emptyMap()
            _contactTyping.value = emptyMap()
            _dropped.value = emptySet()
            _gaps.value = emptyMap()
            // The old login's conversations are no shortcuts any more.
            shortcuts.removeAll()
        }
    }

    /**
     * Conversations gone for the caller: out of the cache, their shortcuts
     * and notification gone, open screens told ([dropped]).
     */
    private suspend fun drop(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        dao.dropConversations(ids.toList())
        ids.forEach { dao.deleteKv(KV_GAPS + it) }
        _gaps.update { it - ids.toSet() }
        _dropped.update { it + ids }
        runCatching { shortcuts.remove(ids) }
        ids.forEach { notifier.get().conversationGone(it) }
    }

    // ─── observing ──────────────────────────────────────────────────

    /** Every cached conversation, the channels too (Messenger 1.20). */
    val allConversations: Flow<List<ChatConversation>> = dao.observeConversations().map { list ->
        list.mapNotNull { e -> parseObj(e.json)?.let { ChatJson.conversation(it) } }
    }

    /**
     * Messenger 1.20 (`features.bridge_channels`): channels have a page of their own — the app sends `?channels=1`
     * and keeps them out of the list, „Ungelesen“ and the badge. Without the word: as before.
     */
    val channelsOn: StateFlow<Boolean> = _profile.map { ChatChannels.on(it?.features) }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Messenger 1.21 (`features.chat_people`): persons — the app sends `?people=1`, the list holds one entry per person
     * and the other ways leave it (they stay in the cache with their messages). Without the word: as before.
     */
    val peopleOn: StateFlow<Boolean> = _profile.map { ChatPeople.on(it?.features) }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** The chats of the list: without the channels once they have a page of their own, without the folded ways of persons. */
    val conversations: Flow<List<ChatConversation>> = combine(allConversations, channelsOn, peopleOn) { list, on, people ->
        ChatPeople.inList(if (on) list.filter { ChatChannels.inList(it, true) } else list, people)
    }

    /** Messenger 1.20: the cached channels (the page „Kanäle“); none without `bridge_channels`. */
    val channels: Flow<List<ChatConversation>> = combine(allConversations, channelsOn) { list, on ->
        if (on) list.filter { it.bridge?.channel == true } else emptyList()
    }

    init {
        // Shortcuts of the recent conversations (launcher, share sheet, notifications).
        scope.launch {
            combine(available, shortcuts.enabled) { on, sc -> on && sc }.distinctUntilChanged().collectLatest { on ->
                if (!on) return@collectLatest
                // Republished too when the share sheet entry changes ("GateDrop" / "An Chat senden"):
                // the sharing shortcuts carry its category.
                combine(
                    conversations.map { ChatShortcutLogic.recent(it) }.distinctUntilChanged(),
                    pluginDirectory.state.map { ChatShareTargets.choose(gatedrop = it.gatedrop, chats = it.messengerTab) }.distinctUntilChanged(),
                ) { recent, _ -> recent }
                    .debounce(SHORTCUT_DEBOUNCE_MS)
                    .collect { runCatching { shortcuts.publish(it) } }
            }
        }
    }

    fun conversation(id: Long): Flow<ChatConversation?> =
        dao.observeConversation(id).map { e -> e?.let { parseObj(it.json) }?.let { ChatJson.conversation(it) } }

    fun messages(conversationId: Long): Flow<List<ChatMessage>> =
        dao.observeMessages(conversationId).map { list -> list.mapNotNull { e -> parseObj(e.json)?.let { ChatJson.message(it) } } }

    /** Ranges of [conversationId]'s cache that may miss messages ([TimelineItem.Gap]). */
    fun gaps(conversationId: Long): Flow<List<ChatGap>> =
        _gaps.map { it[conversationId].orEmpty() }.onStart { gapsOf(conversationId) }.distinctUntilChanged()

    private suspend fun gapsOf(id: Long): List<ChatGap> {
        _gaps.value[id]?.let { return it }
        val g = ChatGaps.parse(dao.kv(KV_GAPS + id))
        _gaps.update { if (id in it) it else it + (id to g) }
        return _gaps.value[id].orEmpty()
    }

    private suspend fun setGaps(id: Long, gaps: List<ChatGap>) {
        _gaps.update { it + (id to gaps) }
        val raw = ChatGaps.format(gaps)
        if (raw == null) dao.deleteKv(KV_GAPS + id) else dao.putKv(KvEntity(KV_GAPS + id, raw))
    }

    /** The cache of [id] starts over (too far behind): no gaps left. */
    private suspend fun clearMessages(id: Long) {
        dao.deleteMessagesOf(listOf(id))
        setGaps(id, emptyList())
    }

    fun outbox(conversationId: Long): Flow<List<ChatPending>> =
        dao.observeOutbox(conversationId).map { list -> list.map { ChatPending.of(it) } }

    suspend fun cachedConversation(id: Long): ChatConversation? =
        dao.conversation(id)?.let { parseObj(it.json) }?.let { ChatJson.conversation(it) }

    suspend fun cachedMessage(id: Long): ChatMessage? = dao.message(id)?.let { parseObj(it.json) }?.let { ChatJson.message(it) }

    // ─── sync ───────────────────────────────────────────────────────

    /** Screen opened, app in front: profile and list unless just done. */
    fun refreshIfStale() {
        if (!available.value) return
        val now = clock()
        scope.launch {
            if (now - lastProfileMs > PROFILE_TTL_MS) refreshProfile()
            if (now - lastListSyncMs > LIST_TTL_MS) syncConversations()
            if (dao.outbox().any { it.state == OutboxEntity.STATE_QUEUED }) kickOutbox()
        }
    }

    private suspend fun refreshAll() {
        refreshProfile()
        syncConversations(full = true)
        syncChannels(full = true)
    }

    suspend fun refreshProfile(): ChatResult<ChatProfile> {
        val r = api.me()
        if (r is ChatResult.Ok) {
            lastProfileMs = clock()
            val wasOn = api.channels
            val peopleWas = api.people
            api.channels = ChatChannels.on(r.value.features)
            api.people = ChatPeople.on(r.value.features)
            _profile.value = r.value
            // channels got a page of their own: they leave the list (the next full list no longer names them)
            if (api.channels && !wasOn) coalesce("channels", 500L) { syncChannels(full = true) }
            // Messenger 1.21: persons came (or went) — the list is one entry per person now (or each way again)
            if (api.people != peopleWas) coalesce("list-full", 500L) { syncConversations(full = true) }
            runCatching {
                ensureOwner()
                dao.putKv(KvEntity(KV_PROFILE, ChatSerializer.profile(r.value).toString()))
            }
        }
        return r
    }

    /**
     * `GET conversations?since=` (everything when [full] or nothing synced
     * yet): changed conversations upserted, removed ones dropped.
     */
    suspend fun syncConversations(full: Boolean = false): ChatSync = syncMutex.withLock {
        ensureOwner()
        _sync.value = ChatSync.Syncing
        val since = if (full) null else dao.kv(KV_LIST_SINCE)
        val result = when (val r = api.conversations(since)) {
            is ChatResult.Ok -> {
                val list = r.value
                if (since == null) {
                    // A full list: what is missing is gone for the caller — the channels are not in it (Messenger 1.20).
                    // Messenger 1.21: the ways of persons are not in it either (`folded`) — they keep their messages
                    val keep = list.conversations.map { it.id }.toSet() + list.folded + list.conversations.flatMap { it.person?.wayIds.orEmpty() }
                    val gone = dao.conversations().filter { it.id !in keep && !(api.channels && isChannel(it.json)) }.map { it.id }
                    drop(gone)
                }
                storeConversations(list.conversations, Store.Entry)
                drop(list.removed)
                list.serverTime?.let { dao.putKv(KvEntity(KV_LIST_SINCE, it)) }
                // Messenger 1.20: the channels' numbers moved (a post, read elsewhere) — their page catches up
                list.channels?.let { s ->
                    val cached = ChatChannels.summary(cachedChannels())
                    if (s != cached || dao.kv(KV_CHANNELS_SINCE) == null) coalesce("channels", 300L) { syncChannels() }
                }
                lastListSyncMs = clock()
                ChatSync.Synced
            }
            is ChatResult.Failed -> ChatSync.Failed(r.code)
            ChatResult.Offline -> ChatSync.Offline
            ChatResult.NotConfigured -> ChatSync.Idle
        }
        _sync.value = result
        result
    }

    private fun isChannel(json: String): Boolean = parseObj(json)?.let { ChatJson.conversation(it) }?.bridge?.channel == true

    private suspend fun cachedChannels(): List<ChatConversation> =
        dao.conversations().mapNotNull { e -> parseObj(e.json)?.let { ChatJson.conversation(it) } }.filter { it.bridge?.channel == true }

    /**
     * Messenger 1.20 `GET channels?since=` (everything when [full] or never loaded): the page „Kanäle“ — changed
     * channels upserted, removed ones dropped; a full answer drops the cached channels it does not name.
     */
    suspend fun syncChannels(full: Boolean = false): ChatSync {
        if (!api.channels) return ChatSync.Idle
        ensureOwner()
        val since = if (full) null else dao.kv(KV_CHANNELS_SINCE)
        return when (val r = api.channels(since)) {
            is ChatResult.Ok -> {
                val list = r.value
                if (since == null) {
                    val keep = list.channels.map { it.id }.toSet()
                    drop(cachedChannels().map { it.id }.filter { it !in keep })
                }
                storeConversations(list.channels, Store.Entry)
                drop(list.removed)
                list.serverTime?.let { dao.putKv(KvEntity(KV_CHANNELS_SINCE, it)) }
                ChatSync.Synced
            }
            is ChatResult.Failed -> ChatSync.Failed(r.code)
            ChatResult.Offline -> ChatSync.Offline
            ChatResult.NotConfigured -> ChatSync.Idle
        }
    }

    /**
     * How a conversation that arrives is stored (Messenger 1.21, persons): [Entry] — a list entry as the server built it
     * (`GET conversations`, `persons/…`); [Fresh] — `GET conversations/:id`, whose `person` says whether it is a way of a
     * person now; [Plain] — the view an action answers with (no `person`).
     */
    internal enum class Store { Entry, Fresh, Plain }

    /** Stored in the server's shape (ChatSerializer), parsed again on every read. */
    private suspend fun storeConversations(list: List<ChatConversation>, mode: Store = Store.Plain) {
        if (list.isEmpty()) return
        val items = if (mode == Store.Entry) list else list.map { c ->
            val old = dao.conversation(c.id)?.let { parseObj(it.json) }?.let { ChatJson.conversation(it) }
            val kept = ChatPeopleStore.keep(c, old, fresh = mode == Store.Fresh)
            // the lead's own view came: the person's face follows from the server
            if (kept.person != null && kept.person.conversationId == c.id && mode == Store.Fresh) coalesce("person:${kept.person.id}", 300L) { refreshPerson(kept.person.id) }
            kept
        }
        dao.upsertConversations(items.map { ConversationEntity(it.id, ChatSerializer.conversation(it).toString(), it.activityAt) })
        // Joined again (or added back): no longer gone.
        if (_dropped.value.isNotEmpty()) _dropped.update { d -> d - list.map { it.id }.toSet() }
    }

    internal suspend fun storeConversation(c: ChatConversation) = storeConversations(listOf(c))

    internal suspend fun storeMessages(list: List<ChatMessage>) {
        if (list.isEmpty()) return
        dao.upsertMessages(list.map { MessageEntity(it.id, it.conversationId, it.clientId, ChatSerializer.message(it).toString()) })
        // Sent from here: the outbox entry is done once the server has it.
        val clientIds = list.mapNotNull { if (it.mine) it.clientId else null }
        for (cid in clientIds) {
            val o = dao.outboxItem(cid) ?: continue
            dao.deleteOutbox(cid)
            media.dropOutboxFiles(o.filePath, o.thumbPath)
        }
    }

    suspend fun refreshConversation(id: Long): ChatResult<ChatConversation> {
        val r = api.conversation(id)
        when {
            r is ChatResult.Ok -> storeConversations(listOf(r.value), Store.Fresh)
            r is ChatResult.Failed && r.http == 404 -> drop(listOf(id))
        }
        return r
    }

    /**
     * A conversation opened: catch up from the cache's newest message
     * (`after=`), its changes (`changedSince=`) — or load the newest page.
     */
    suspend fun openConversation(id: Long) {
        ensureOwner()
        // Changes first: their mark is from the last look, before the new messages move it.
        fetchChanged(id)
        fetchNewer(id)
    }

    /** Newer messages than the cache holds; a gap too large starts over with the newest page. */
    suspend fun fetchNewer(id: Long): ChatResult<Unit> {
        val maxId = dao.maxMessageId(id)
        if (maxId == null) return loadNewest(id)
        var cursor: Long = maxId
        repeat(MAX_CATCHUP_PAGES) {
            val r = api.messages(id, after = cursor, limit = PAGE)
            if (r !is ChatResult.Ok) return handlePageFailure(id, r)
            val page = r.value
            applyPage(id, page, from = cursor + 1)
            if (!page.hasMore) return ChatResult.Ok(Unit)
            cursor = page.messages.maxOfOrNull { it.id } ?: return ChatResult.Ok(Unit)
        }
        // Too far behind: the newest page, older ones on demand.
        clearMessages(id)
        return loadNewest(id)
    }

    private suspend fun loadNewest(id: Long): ChatResult<Unit> {
        val r = api.messages(id, limit = PAGE)
        if (r !is ChatResult.Ok) return handlePageFailure(id, r)
        val page = r.value
        applyPage(id, page, from = page.messages.minOfOrNull { it.id } ?: Long.MAX_VALUE)
        dao.putKv(KvEntity(KV_OLDER + id, if (page.hasMore) "1" else "0"))
        noteChangedSince(id, page.serverTime)
        return ChatResult.Ok(Unit)
    }

    private suspend fun handlePageFailure(id: Long, r: ChatResult<*>): ChatResult<Unit> {
        if (r is ChatResult.Failed && r.http == 404) drop(listOf(id))
        return if (r is ChatResult.Ok) ChatResult.Ok(Unit) else r.asFailure()
    }

    /** Stores a page; cached ids within its range it does not hold are gone (hidden, expired). */
    private suspend fun applyPage(id: Long, page: ChatMessagePage, from: Long) {
        val to = page.messages.maxOfOrNull { it.id }
        if (to != null && from <= to) {
            val gone = ChatTimeline.vanished(dao.messageIdsBetween(id, from, to), page.messages)
            if (gone.isNotEmpty()) dao.deleteMessages(gone)
        }
        storeMessages(page.messages)
        updateReceipts(id, page.readUpTo, page.deliveredUpTo)
    }

    private suspend fun updateReceipts(id: Long, readUpTo: Long?, deliveredUpTo: Long?) {
        val e = dao.conversation(id) ?: return
        val o = parseObj(e.json) ?: return
        val c = ChatJson.conversation(o) ?: return
        if (c.readUpTo == readUpTo && c.deliveredUpTo == deliveredUpTo) return
        storeConversation(c.copy(readUpTo = readUpTo, deliveredUpTo = deliveredUpTo))
    }

    /**
     * The next `changedSince` of [id]: the answer's [serverTime] (Messenger
     * 1.1.0), else an estimate of the server's clock.
     */
    private suspend fun noteChangedSince(id: Long, serverTime: String? = null) {
        if (serverTime != null) {
            dao.putKv(KvEntity(KV_CHANGED + id, serverTime))
            return
        }
        // The device clock is good enough as a lower bound when the server time is unknown.
        val serverMs = profile.value?.serverTime?.let { ChatTimeline.epochMs(it) }
        val skew = if (serverMs != null && lastProfileMs > 0) serverMs - lastProfileMs else 0L
        val since = Instant.ofEpochMilli(clock() + skew - CHANGED_SLACK_MS).toString()
        dao.putKv(KvEntity(KV_CHANGED + id, since))
    }

    /** Edits, deletions and reactions since the last look (`changedSince=`). */
    suspend fun fetchChanged(id: Long): ChatResult<Unit> {
        val since = dao.kv(KV_CHANGED + id) ?: return ChatResult.Ok(Unit)
        val r = api.messages(id, changedSince = since, limit = 100)
        if (r !is ChatResult.Ok) return handlePageFailure(id, r)
        val page = r.value
        val minCached = dao.minMessageId(id)
        val gaps = gapsOf(id)
        // Only messages the cache covers (older ones and those of a gap load on demand anyway).
        storeMessages(page.messages.filter { minCached != null && it.id >= minCached && !ChatGaps.inGap(gaps, it.id) })
        updateReceipts(id, page.readUpTo, page.deliveredUpTo)
        if (page.hasMore) {
            clearMessages(id)
            loadNewest(id)
        } else {
            noteChangedSince(id, page.serverTime)
        }
        return ChatResult.Ok(Unit)
    }

    /**
     * Brings message [messageId] of conversation [id] into the cache — a
     * search result or a quoted message not loaded yet — with its
     * neighbours (`around=`, Messenger 1.1.0). What lies between that page
     * and the cached messages becomes a gap ([gaps]) the conversation loads
     * when it is scrolled to.
     */
    suspend fun jumpTo(id: Long, messageId: Long): ChatResult<ChatJump> {
        if (dao.message(messageId)?.conversationId == id) return ChatResult.Ok(ChatJump.Found)
        val r = api.messages(id, around = messageId, limit = AROUND_PAGE)
        if (r !is ChatResult.Ok) return handlePageFailure(id, r).asFailure()
        val page = r.value
        // Servers before 1.1.0 ignore `around` and send the newest page.
        if (!page.isAround) return ChatResult.Ok(ChatJump.Unsupported)
        val min = page.messages.minOfOrNull { it.id } ?: return ChatResult.Ok(ChatJump.Missing)
        val max = page.messages.maxOf { it.id }
        val cachedMin = dao.minMessageId(id)
        val cachedMax = dao.maxMessageId(id)
        val gaps = ChatGaps.afterPage(gapsOf(id), cachedMin, cachedMax, min, max, page.hasMoreBefore ?: true, page.hasMoreAfter ?: true)
        applyPage(id, page, from = min)
        setGaps(id, gaps)
        if (cachedMin == null || min < cachedMin) dao.putKv(KvEntity(KV_OLDER + id, if (page.hasMoreBefore != false) "1" else "0"))
        return ChatResult.Ok(if (page.messages.any { it.id == messageId }) ChatJump.Found else ChatJump.Missing)
    }

    /** Loads (part of) [gap] of conversation [id] (`after=`); what is still missing stays a gap. */
    suspend fun fillGap(id: Long, gap: ChatGap): ChatResult<Unit> {
        val r = api.messages(id, after = gap.after, limit = PAGE)
        if (r !is ChatResult.Ok) return handlePageFailure(id, r)
        val page = r.value
        val max = page.messages.maxOfOrNull { it.id } ?: gap.after
        val gaps = ChatGaps.afterPage(gapsOf(id), dao.minMessageId(id), dao.maxMessageId(id), gap.after, max, true, page.hasMore)
        applyPage(id, page, from = gap.after + 1)
        setGaps(id, gaps)
        return ChatResult.Ok(Unit)
    }

    /** Older messages (`before=` the oldest cached). Returns whether there are still older ones. */
    suspend fun loadOlder(id: Long): ChatResult<Boolean> {
        val minId = dao.minMessageId(id)
        if (minId == null) {
            val r = loadNewest(id)
            return if (r is ChatResult.Ok) ChatResult.Ok(hasOlder(id)) else r.asFailure()
        }
        val r = api.messages(id, before = minId, limit = PAGE)
        if (r !is ChatResult.Ok) return handlePageFailure(id, r).asFailure()
        val page = r.value
        val from = page.messages.minOfOrNull { it.id }
        if (from != null) applyPage(id, page, from = from)
        dao.putKv(KvEntity(KV_OLDER + id, if (page.hasMore) "1" else "0"))
        return ChatResult.Ok(page.hasMore)
    }

    suspend fun hasOlder(id: Long): Boolean = dao.kv(KV_OLDER + id) != "0"

    // ─── live events ────────────────────────────────────────────────

    /** The push stream (re)opened: events of the time offline are missing. */
    fun onStreamOpen() {
        if (!available.value) return
        scope.launch {
            syncConversations()
            _active.value?.let { openConversation(it) }
        }
    }

    fun onEvent(event: ChatEvent) {
        if (!available.value) return
        val conv = event.conversationId
        when (event) {
            is ChatEvent.MessageNew -> {
                event.senderId?.let { clearTyping(conv, it) }
                if (event.bridge != null) {
                    // A contact's message ends their "schreibt …". The first chat over a bridge: the server names the
                    // feature from now on (`features.bridge_chats`).
                    if (event.senderId == null) _contactTyping.update { it - conv }
                    if (profile.value?.features?.tokens?.contains(ChatBridgeChat.FEATURE) != true) coalesce("profile", 1_000L) { refreshProfile() }
                }
                coalesce("new:$conv") { if (hasCache(conv)) fetchNewer(conv) }
                coalesce("list") { syncConversations() }
                // Messenger 1.20: a post of a channel (or of a chat not in the list yet) — not in `GET conversations`
                if (api.channels) scope.launch {
                    val cached = cachedConversation(conv)
                    if (cached == null || cached.bridge?.channel == true) coalesce("conv:$conv", 300L) { refreshConversation(conv) }
                }
            }
            is ChatEvent.MessageUpdate -> coalesce("changed:$conv") { if (hasCache(conv)) fetchChanged(conv) }
            is ChatEvent.MessageDelete -> scope.launch {
                if (event.tombstone) {
                    for (mid in event.messageIds) {
                        val e = dao.message(mid) ?: continue
                        val o = parseObj(e.json) ?: continue
                        dao.upsertMessages(listOf(e.copy(json = ChatJsonPatch.tombstone(o, Instant.ofEpochMilli(clock()).toString()).toString())))
                    }
                } else {
                    dao.deleteMessages(event.messageIds)
                }
                coalesce("list") { syncConversations() }
            }
            is ChatEvent.Reaction -> scope.launch {
                val e = dao.message(event.messageId) ?: return@launch
                val o = parseObj(e.json) ?: return@launch
                dao.upsertMessages(listOf(e.copy(json = ChatJsonPatch.reactions(o, event.reactions).toString())))
            }
            is ChatEvent.Read -> {
                if (event.self) notifier.get().conversationRead(conv)
                // Own devices: unread and read mark. Since Messenger 1.1.0 the event carries
                // readUpTo / deliveredUpTo: the ticks change right here, no GET needed.
                if (event.self || event.upTo != null) {
                    scope.launch {
                        val e = dao.conversation(conv) ?: return@launch
                        val o = parseObj(e.json) ?: return@launch
                        val patched = ChatJsonPatch.readEvent(o, event)
                        if (patched != o) dao.upsertConversations(listOf(e.copy(json = patched.toString())))
                        // Messenger 1.21: a way of a person — the entry counts all ways (the list knows them)
                        if (event.self && ChatJson.conversation(o)?.person != null) coalesce("list") { syncConversations() }
                    }
                }
                if (!event.self && event.upTo == null) {
                    // Older servers: receipts of others only through the conversation.
                    coalesce("conv:$conv", 1_200L) { if (dao.conversation(conv) != null) refreshConversation(conv) }
                }
            }
            is ChatEvent.Typing -> setTyping(conv, event.userId, event.untilMs)
            is ChatEvent.ContactTyping -> {
                val until = event.untilMs
                if (until == null || until <= clock() || event.contacts.isEmpty()) {
                    _contactTyping.update { it - conv }
                } else {
                    _contactTyping.update { it + (conv to ChatContactTyping(event.contacts.map { c -> c.name }, until)) }
                    coalesce("ctyping:$conv:$until", until - clock() + 100) { prune() }
                }
            }
            is ChatEvent.ConversationUpdate -> {
                // Messenger 1.21: merged, renamed, separated — the list anew (without `since`)
                if (ChatPeople.REASON_PERSON in event.reasons) coalesce("list-full", 300L) { syncConversations(full = true) }
                coalesce("conv:$conv", 300L) { refreshConversation(conv) }
                // Released by its plugin (managedBy null): the list shows it as a normal group.
                if (event.released) coalesce("list") { syncConversations() }
                // Messenger 1.17: older messages of a bridged chat were filed (no push, already read) — the open chat
                // loads them where it stands, any other drops its cache and loads anew when it opens.
                if (event.history) coalesce("history:$conv", 600L) { historyArrived(conv) }
            }
            is ChatEvent.ConversationRemove -> scope.launch { drop(listOf(conv)) }
            is ChatEvent.StatusChanged -> _statusEvents.tryEmit(event)
            is ChatEvent.AccountChanged -> _accountEvents.tryEmit(event)
        }
    }

    /**
     * Messenger 1.17 `conversation.update { reason: "history" }`: older messages came into [conv]. They stand at their
     * own place by `id`; `changedSince=` finds them by their `updatedAt` (the time they were filed). Open: the changes
     * and the newer messages, the cache stays (the list stays where it is) and older pages are asked for again. Not
     * open: the cache is dropped.
     */
    internal suspend fun historyArrived(conv: Long) {
        if (conv == _active.value) {
            fetchChanged(conv)
            fetchNewer(conv)
            dao.putKv(KvEntity(KV_OLDER + conv, "1"))
        } else {
            clearMessages(conv)
        }
    }

    /**
     * A chat was made sure of elsewhere ("Wo Mitteilungen erscheinen" → own
     * chat of a source): fetched into the list right away, without waiting
     * for its live event (coalesced with it when that arrives too).
     */
    fun conversationEnsured(id: Long?) {
        if (!available.value) return
        if (id != null) coalesce("conv:$id", 300L) { refreshConversation(id) }
        coalesce("list") { syncConversations() }
    }

    private suspend fun hasCache(conv: Long): Boolean = conv == _active.value || conv in _activeWays.value || dao.maxMessageId(conv) != null

    /** Runs [block] once after [delayMs], however often it is asked for meanwhile. */
    private fun coalesce(key: String, delayMs: Long = COALESCE_MS, block: suspend () -> Unit) {
        if (pending[key]?.isActive == true) return
        pending[key] = scope.launch {
            delay(delayMs)
            pending.remove(key)
            runCatching { block() }.onFailure { Timber.d("Chats: %s failed (%s)", key.substringBefore(':'), it.javaClass.simpleName) }
        }
    }

    private fun setTyping(conv: Long, userId: Long, untilMs: Long?) {
        if (untilMs == null || untilMs <= clock()) {
            clearTyping(conv, userId)
            return
        }
        _typing.update { m -> m + (conv to (m[conv].orEmpty() + (userId to untilMs))) }
        coalesce("typing:$conv:$userId", untilMs - clock() + 100) { prune() }
    }

    private fun clearTyping(conv: Long, userId: Long) {
        _typing.update { m ->
            val inner = m[conv].orEmpty() - userId
            if (inner.isEmpty()) m - conv else m + (conv to inner)
        }
    }

    private fun prune() {
        val now = clock()
        _typing.update { m -> m.mapValues { (_, v) -> v.filterValues { it > now } }.filterValues { it.isNotEmpty() } }
        _contactTyping.update { m -> m.filterValues { it.untilMs > now } }
    }

    // ─── presence in a conversation ─────────────────────────────────

    /**
     * The conversation [id] is open and visible (null: none): `active`
     * every 20 s, so the server sends no push for it; `active:false` on leaving.
     */
    fun setActive(id: Long?) = activate(id, emptyList())

    /**
     * Messenger 1.21: the chat of a person is open — `active` for each of its [ways] (else a push comes though the chat
     * is open), their notifications go.
     */
    fun setActivePerson(id: Long, ways: List<Long>) = activate(id, ways.filter { it != id })

    private val _activeWays = MutableStateFlow<Set<Long>>(emptySet())
    /** Messenger 1.21: the other ways of the person open right now (no notification for them either). */
    val activeWays: StateFlow<Set<Long>> = _activeWays.asStateFlow()

    private fun activate(id: Long?, ways: List<Long>) {
        val before = _active.value
        val beforeWays = _activeWays.value
        if (before == id && beforeWays == ways.toSet()) return
        _active.value = id
        _activeWays.value = ways.toSet()
        activeJob?.cancel()
        if (before != null && before != id) scope.launch { api.active(before, active = false) }
        (beforeWays - ways.toSet()).forEach { w -> scope.launch { api.active(w, active = false) } }
        if (id == null) return
        notifier.get().conversationRead(id)
        ways.forEach { notifier.get().conversationRead(it) }
        activeJob = scope.launch {
            var ticks = 0
            while (isActive) {
                api.active(id)
                ways.forEach { api.active(it) }
                delay(ACTIVE_EVERY_MS)
                ticks++
                // Without live events: edits, reactions and receipts once a minute.
                if (!live && ticks % 3 == 0) {
                    fetchChanged(id)
                    refreshConversation(id)
                }
            }
        }
        // Without live events: new messages every few seconds while the chat is open.
        if (before != id) scope.launch {
            while (_active.value == id) {
                delay(POLL_MS)
                if (_active.value == id && !live) {
                    fetchNewer(id)
                    _activeWays.value.forEach { fetchNewer(it) }
                }
            }
        }
    }

    /** Typing in [id]: at most every 3 s (the server's limit). */
    fun typingPing(id: Long) {
        if (profile.value?.prefs?.typing == false) return
        val now = clock()
        val every = (profile.value?.limits?.typingEveryS ?: 3) * 1000L
        val last = lastTypingSent[id] ?: 0L
        if (now - last < every) return
        lastTypingSent[id] = now
        scope.launch { api.typing(id) }
    }

    fun typingStop(id: Long) {
        if (lastTypingSent.remove(id) == null) return
        scope.launch { api.typing(id, stop = true) }
    }

    // ─── reading ────────────────────────────────────────────────────

    /** Everything up to the newest stored message is read (the server never moves back). */
    suspend fun markRead(id: Long) {
        val e = dao.conversation(id) ?: return
        val o = parseObj(e.json) ?: return
        val c = ChatJson.conversation(o) ?: return
        val newest = listOfNotNull(dao.maxMessageId(id), c.lastMessage?.id).maxOrNull() ?: return
        if (c.unread == 0 && (c.me.lastReadId ?: 0L) >= newest) return
        dao.upsertConversations(listOf(e.copy(json = ChatJsonPatch.read(o, 0, newest).toString())))
        val r = api.read(id, newest)
        if (r is ChatResult.Ok) {
            val now = dao.conversation(id) ?: return
            parseObj(now.json)?.let { dao.upsertConversations(listOf(now.copy(json = ChatJsonPatch.read(it, r.value ?: 0, newest).toString()))) }
        }
    }

    // ─── sending (outbox) ───────────────────────────────────────────

    fun newClientId(): String = UUID.randomUUID().toString().replace("-", "")

    suspend fun sendText(conversationId: Long, text: String, replyTo: Long?) {
        val t = ChatJson.text(text.trim(), profile.value?.limits?.textMax ?: ChatJson.TEXT_MAX) ?: return
        typingStop(conversationId)
        enqueue(outboxEntity(conversationId, ChatMessage.KIND_TEXT, text = t, replyTo = replyTo))
    }

    suspend fun sendImage(conversationId: Long, image: PreparedImage, caption: String?, replyTo: Long?, clientId: String) {
        enqueue(
            outboxEntity(
                conversationId, ChatMessage.KIND_IMAGE, text = caption?.trim()?.takeIf { it.isNotEmpty() }, replyTo = replyTo,
                filePath = image.file.path, mime = image.mime, thumbPath = image.thumb?.path,
                meta = ChatMeta(w = image.width, h = image.height), clientId = clientId,
            ),
        )
    }

    /**
     * A file; a video's length and size wait in the outbox's `meta` and go
     * out as `video` ([ChatPreview.sendFields]), its preview as `thumbMediaId`.
     */
    suspend fun sendFile(conversationId: Long, file: PreparedFile, caption: String?, replyTo: Long?, clientId: String) {
        val v = file.video
        enqueue(
            outboxEntity(
                conversationId, ChatMessage.KIND_FILE, text = caption?.trim()?.takeIf { it.isNotEmpty() }, replyTo = replyTo,
                filePath = file.file.path, mime = file.mime, thumbPath = file.thumb?.path,
                meta = ChatMeta(fileName = file.name, durationMs = v?.durationMs, w = v?.width, h = v?.height), clientId = clientId,
            ),
        )
    }

    suspend fun sendVoice(conversationId: Long, file: File, mime: String, durationMs: Long, waveform: List<Int>, replyTo: Long?, clientId: String) {
        enqueue(
            outboxEntity(
                conversationId, ChatMessage.KIND_VOICE, text = null, replyTo = replyTo,
                filePath = file.path, mime = mime, meta = ChatMeta(durationMs = durationMs, waveform = waveform), clientId = clientId,
            ),
        )
    }

    suspend fun sendLocation(conversationId: Long, location: ChatLocation, replyTo: Long?) {
        enqueue(outboxEntity(conversationId, ChatMessage.KIND_LOCATION, text = null, replyTo = replyTo, location = location))
    }

    /** An answer typed into a chat notification: through the outbox too (retried, never twice). */
    suspend fun sendFromNotification(conversationId: Long, text: String, clientId: String): ChatResult<ChatMessage> {
        val t = ChatJson.text(text.trim()) ?: return ChatResult.Failed("invalid", 400)
        val entity = outboxEntity(conversationId, ChatMessage.KIND_TEXT, text = t, replyTo = null, clientId = clientId, source = SOURCE_NOTIFICATION)
        dao.upsertOutbox(entity)
        val r = sendOne(entity)
        if (r !is ChatResult.Ok) kickOutbox()
        return r
    }

    private fun outboxEntity(
        conversationId: Long,
        kind: String,
        text: String?,
        replyTo: Long?,
        filePath: String? = null,
        mime: String? = null,
        thumbPath: String? = null,
        meta: ChatMeta? = null,
        location: ChatLocation? = null,
        clientId: String = newClientId(),
        source: String? = null,
    ) = OutboxEntity(
        clientId = clientId,
        conversationId = conversationId,
        createdAtMs = clock(),
        kind = kind,
        text = text,
        replyToId = replyTo,
        filePath = filePath,
        mime = mime,
        thumbPath = thumbPath,
        metaJson = ChatOutboxLogic.metaJson(meta),
        locationJson = ChatOutboxLogic.locationJson(location),
        mediaId = null,
        thumbMediaId = null,
        state = OutboxEntity.STATE_QUEUED,
        attempts = 0,
        error = null,
        nextAttemptMs = 0L,
        source = source,
    )

    private suspend fun enqueue(entity: OutboxEntity) {
        ensureOwner()
        dao.upsertOutbox(entity)
        kickOutbox()
    }

    fun retry(clientId: String) {
        scope.launch {
            val e = dao.outboxItem(clientId) ?: return@launch
            dao.upsertOutbox(e.copy(state = OutboxEntity.STATE_QUEUED, attempts = 0, error = null, nextAttemptMs = 0L))
            kickOutbox()
        }
    }

    fun discard(clientId: String) {
        scope.launch {
            val e = dao.outboxItem(clientId) ?: return@launch
            dao.deleteOutbox(clientId)
            media.dropOutboxFiles(e.filePath, e.thumbPath)
        }
    }

    /** Sends what is due now; schedules the rest (WorkManager when offline). */
    fun kickOutbox() {
        if (outboxJob?.isActive == true) return
        outboxJob = scope.launch { processOutbox() }
    }

    /**
     * One pass over the outbox; true when nothing is left queued. With
     * [schedule] a background job takes over what waits (offline, server busy).
     */
    suspend fun processOutbox(schedule: Boolean = true): Boolean = outboxMutex.withLock {
        // Each entry at most once per pass. The background job (network back)
        // tries every queued entry, whatever its next attempt time says.
        // A conversation whose oldest entry failed waits as a whole (order is kept).
        val tried = HashSet<String>()
        val blocked = HashSet<Long>()
        while (true) {
            val items = dao.outbox().filter { it.clientId !in tried && it.conversationId !in blocked }
            val now = if (schedule) clock() else Long.MAX_VALUE
            val next = ChatOutboxLogic.next(items, now) ?: break
            tried += next.clientId
            val r = sendOne(next)
            if (r !is ChatResult.Ok) blocked += next.conversationId
            // Offline: the rest would fail the same way.
            if (r == ChatResult.Offline) break
        }
        val due = ChatOutboxLogic.nextDueMs(dao.outbox())
        if (due != null && schedule) ChatOutboxWorker.enqueue(context)
        due == null
    }

    /** Uploads (when needed) and sends one entry; stores the outcome. */
    private suspend fun sendOne(entry: OutboxEntity): ChatResult<ChatMessage> {
        var e = entry.copy(attempts = entry.attempts + 1)
        dao.upsertOutbox(e)
        if (e.filePath != null && !File(e.filePath).isFile) return giveUp(e, "file_missing")
        // The preview first, then the file: the media ids are kept, a retry does not upload again.
        // A preview that does not go up is left out (the message goes without it).
        if (e.thumbPath != null && e.thumbMediaId == null && e.mediaId == null) {
            val t = File(e.thumbPath)
            if (t.isFile) {
                val up = api.upload(t, "image/webp")
                if (up is ChatResult.Ok) {
                    e = e.copy(thumbMediaId = up.value)
                    dao.upsertOutbox(e)
                } else if (up == ChatResult.Offline) {
                    return afterFailure(e, up, hadUpload = false)
                }
            }
        }
        if (e.filePath != null && e.mediaId == null) {
            val up = api.upload(File(e.filePath), e.mime ?: "application/octet-stream")
            if (up !is ChatResult.Ok) return afterFailure(e, up, hadUpload = false)
            e = e.copy(mediaId = up.value)
            dao.upsertOutbox(e)
        }
        val (meta, video) = ChatPreview.sendFields(e.kind, e.mime, e.metaJson?.let { ChatOutboxLogic.metaOf(it) })
        val body = ChatJson.sendBody(
            clientId = e.clientId,
            kind = e.kind,
            text = e.text,
            replyTo = e.replyToId,
            mediaId = e.mediaId,
            thumbMediaId = e.thumbMediaId,
            location = e.locationJson?.let { ChatOutboxLogic.locationOf(it) },
            meta = meta,
            source = e.source,
            video = video,
        )
        val r = api.send(e.conversationId, body)
        if (r is ChatResult.Ok) {
            val msg = r.value
            storeMessages(listOf(msg))
            dao.deleteOutbox(e.clientId)
            media.dropOutboxFiles(e.filePath, e.thumbPath)
            // The list shows it right away; the next sync brings the server's view.
            dao.conversation(e.conversationId)?.let { ce ->
                parseObj(ce.json)?.let { o ->
                    val patched = ChatJsonPatch.read(ChatJsonPatch.lastMessage(o, ChatSerializer.message(msg)), 0, msg.id)
                    dao.upsertConversations(listOf(ce.copy(json = patched.toString(), activityAt = msg.createdAt)))
                    // Messenger 1.21: a way of a person — its entry and the way of the next answer follow
                    ChatJson.conversation(o)?.person?.let { p ->
                        personSent(p.id)
                        if (p.conversationId != e.conversationId) coalesce("list") { syncConversations() }
                    }
                }
            }
            return r
        }
        return afterFailure(e, r, hadUpload = e.mediaId != null)
    }

    private suspend fun afterFailure(e: OutboxEntity, r: ChatResult<*>, hadUpload: Boolean): ChatResult<ChatMessage> {
        val step = ChatOutboxLogic.step(r, e.attempts, hadUpload, clock())
        when (step) {
            OutboxStep.Done -> Unit
            is OutboxStep.Retry -> dao.upsertOutbox(e.copy(nextAttemptMs = step.atMs, error = codeOf(r)))
            OutboxStep.Reupload -> dao.upsertOutbox(e.copy(mediaId = null, thumbMediaId = null, nextAttemptMs = 0L))
            is OutboxStep.GiveUp -> return giveUp(e, step.code)
        }
        return r.asFailure()
    }

    private suspend fun giveUp(e: OutboxEntity, code: String): ChatResult<ChatMessage> {
        Timber.w("Chats: message not sent (%s)", code)
        dao.upsertOutbox(e.copy(state = OutboxEntity.STATE_FAILED, error = code))
        // Messenger 1.20: a channel or a group where only the admins write — the chat says so once it is fresh
        if (code == ChatBridgeChat.READ_ONLY) coalesce("conv:${e.conversationId}", 300L) { refreshConversation(e.conversationId) }
        return ChatResult.Failed(code, 0)
    }

    private fun codeOf(r: ChatResult<*>): String? = when (r) {
        is ChatResult.Failed -> r.code
        ChatResult.Offline -> "offline"
        else -> null
    }

    // ─── message actions ────────────────────────────────────────────

    suspend fun edit(message: ChatMessage, text: String): ChatResult<ChatMessage> {
        val t = ChatJson.text(text.trim()) ?: return ChatResult.Failed("invalid", 400)
        val r = api.edit(message.id, t)
        if (r is ChatResult.Ok) storeMessages(listOf(r.value))
        return r
    }

    /**
     * Messenger 1.14: an own message of a bridged chat that did not go out, again. The answer is the message as it
     * stands now — sent, or failed again with its reason (an Ok either way).
     */
    suspend fun resend(message: ChatMessage): ChatResult<ChatMessage> {
        val r = api.resend(message.id)
        if (r is ChatResult.Ok) storeSent(r.value)
        return r
    }

    /**
     * Messenger 1.17: a file of the older history of a bridged chat, asked for now ("Laden"). The answer is the message
     * with its placeholder `loading`; the file follows with `message.update`. `400 invalid`: nothing to fetch (any
     * more) — the message is asked for again, so it shows what it is now.
     */
    suspend fun fetchBridged(message: ChatMessage): ChatResult<ChatMessage> {
        val r = api.fetchBridged(message.id)
        when {
            r is ChatResult.Ok -> storeMessages(listOf(r.value))
            r is ChatResult.Failed && r.http == 400 -> coalesce("changed:${message.conversationId}") { fetchChanged(message.conversationId) }
        }
        return r
    }

    suspend fun delete(message: ChatMessage, forAll: Boolean): ChatResult<ChatDeleted> {
        val r = api.delete(message.id, forAll)
        if (r is ChatResult.Ok) {
            val tomb = r.value.tombstone
            if (forAll && tomb != null) storeMessages(listOf(tomb)) else dao.deleteMessages(listOf(message.id))
            coalesce("list") { syncConversations() }
        }
        return r
    }

    suspend fun react(message: ChatMessage, emoji: String?): ChatResult<List<ChatReaction>> {
        val r = api.react(message.id, emoji)
        if (r is ChatResult.Ok) {
            dao.message(message.id)?.let { e ->
                parseObj(e.json)?.let { dao.upsertMessages(listOf(e.copy(json = ChatJsonPatch.reactions(it, r.value).toString()))) }
            }
        }
        // Messenger 1.20: reacting in channels went off (the bridge refused one) — `can.react` follows with the chat
        if (r is ChatResult.Failed && r.code == ChatBridgeChat.READ_ONLY) coalesce("conv:${message.conversationId}", 300L) { refreshConversation(message.conversationId) }
        return r
    }

    suspend fun search(conversationId: Long, q: String): ChatResult<ChatSearchResult> = api.search(conversationId, q)

    /** A page of the media overview (`GET conversations/:id/media`, newest first). */
    suspend fun media(conversationId: Long, kind: String, before: Long?, limit: Int): ChatResult<ChatMediaPage> {
        val r = api.media(conversationId, kind, before, limit)
        if (r is ChatResult.Failed && r.http == 404) drop(listOf(conversationId))
        return r
    }

    // ─── conversations ──────────────────────────────────────────────

    suspend fun people(): ChatResult<List<ChatPerson>> = api.people()

    /**
     * Messenger 1.15: the chat with a phone number over a bridge, made or found. It is not in `GET conversations`
     * before something was written, so it is stored here and opened by its id.
     */
    suspend fun openNumberChat(network: String, e164: String): ChatResult<ChatNumberChat> {
        val r = api.bridgeNewChat(network, e164)
        if (r is ChatResult.Ok) storeConversation(r.value.conversation)
        return r
    }

    /** Messenger 1.18: "Neuer Chat" with contacts (`GET contacts/new-chat`); [q] the search ("" for all). */
    suspend fun newChatContacts(network: String, q: String): ChatResult<ChatNewChatContacts> = api.newChatContacts(network, q)

    /** Messenger 1.18: the chat with a WhatsApp contact ([ref] of the list), made or found — stored like [openNumberChat]. */
    suspend fun openContactChat(network: String, ref: String): ChatResult<ChatNumberChat> {
        val r = api.bridgeContactChat(network, ref)
        if (r is ChatResult.Ok) storeConversation(r.value.conversation)
        return r
    }

    /** Messenger 1.18: the chat with a number of the address book ([phoneKey] of the contact), made or found. */
    suspend fun openBookChat(network: String, contactId: Long, phoneKey: String): ChatResult<ChatNumberChat> {
        val r = api.bridgeBookChat(network, contactId, phoneKey)
        if (r is ChatResult.Ok) storeConversation(r.value.conversation)
        return r
    }

    suspend fun openDirect(peerId: Long): ChatResult<ChatConversation> {
        val r = api.createDirect(peerId)
        if (r is ChatResult.Ok) storeConversation(r.value)
        return r
    }

    suspend fun createGroup(title: String, userIds: List<Long>): ChatResult<ChatConversation> {
        val r = api.createGroup(title, userIds)
        if (r is ChatResult.Ok) storeConversation(r.value)
        return r
    }

    suspend fun patch(id: Long, body: JsonObject): ChatResult<ChatPatch> {
        val r = api.patchConversation(id, body)
        if (r is ChatResult.Ok) {
            val c = r.value.conversation
            if (c != null) storeConversation(c)
            if (r.value.left) drop(listOf(id))
        }
        return r
    }

    /**
     * New group picture: [file] (a square picture, [mime]) uploaded through
     * the plugin's media route, then set as `avatarMediaId`.
     */
    suspend fun setGroupAvatar(id: Long, file: File, mime: String): ChatResult<ChatPatch> {
        val up = api.upload(file, mime)
        if (up !is ChatResult.Ok) return up.asFailure()
        // Shown right away: no download of what was just uploaded.
        media.prime(up.value, file)
        return patch(id, ChatPatches.avatar(up.value))
    }

    suspend fun removeGroupAvatar(id: Long): ChatResult<ChatPatch> = patch(id, ChatPatches.avatar(null))

    /** The conversation was opened here: its shortcut ranks higher (launcher, share sheet). */
    fun opened(id: Long) = shortcuts.reportUsed(id)

    private val leftHere = ConcurrentHashMap.newKeySet<Long>()

    suspend fun leave(id: Long): ChatResult<ChatPatch> {
        val me = profile.value?.user?.userId ?: return ChatResult.Failed("invalid", 0)
        leftHere += id
        val r = patch(id, ChatPatches.remove(listOf(me)))
        if (r !is ChatResult.Ok) leftHere -= id
        return r
    }

    /** The caller left [id] on this device (not removed by others). */
    fun leftHere(id: Long): Boolean = id in leftHere

    /** Messenger 1.4: preferences changed elsewhere ([ChatExtrasRepository.putPrefs]). */
    fun updatePrefs(prefs: ChatPrefs, prefs14: ChatPrefs14) {
        _profile.update { it?.copy(prefs = prefs, prefs14 = prefs14) }
        val p = _profile.value ?: return
        scope.launch { runCatching { dao.putKv(KvEntity(KV_PROFILE, ChatSerializer.profile(p).toString())) } }
    }

    /** Messenger 1.4: the own status changed here. */
    fun updateOwnStatus(status: ChatStatus?) {
        _profile.update { it?.copy(status = status) }
    }

    /** A message stored after an action on it, with the conversation's preview when it is the newest. */
    suspend fun storeSent(msg: ChatMessage) {
        storeMessages(listOf(msg))
        dao.conversation(msg.conversationId)?.let { ce ->
            parseObj(ce.json)?.let { o ->
                val patched = ChatJsonPatch.read(ChatJsonPatch.lastMessage(o, ChatSerializer.message(msg)), 0, msg.id)
                dao.upsertConversations(listOf(ce.copy(json = patched.toString(), activityAt = msg.createdAt)))
            }
        }
    }

    suspend fun putPrefs(readReceipts: Boolean? = null, typing: Boolean? = null, preview: Boolean? = null): ChatResult<ChatPrefs> {
        val r = api.putPrefs(readReceipts, typing, preview)
        if (r is ChatResult.Ok) _profile.update { it?.copy(prefs = r.value) }
        return r
    }

    // ─── Messenger 1.21: persons ────────────────────────────────────

    /** The messages of a person's ways together (the screen mixes them by time). */
    fun personMessages(ways: List<Long>): Flow<List<ChatMessage>> =
        dao.observeMessagesIn(ways).map { list -> list.mapNotNull { e -> parseObj(e.json)?.let { ChatJson.message(it) } } }

    fun personOutbox(ways: List<Long>): Flow<List<ChatPending>> =
        dao.observeOutboxIn(ways).map { list -> list.map { ChatPending.of(it) } }

    /** The cached views of a person's ways (the bubbles, ticks and composer of each way). */
    fun wayConversations(ways: List<Long>): Flow<Map<Long, ChatConversation>> =
        allConversations.map { list -> list.filter { it.id in ways }.associateBy { it.id } }

    /** The ways with older messages on the server than the cache holds. */
    suspend fun olderWays(ways: List<Long>): Set<Long> = ways.filter { hasOlder(it) }.toSet()

    /**
     * Where [id] is a way of a person with another lead, that lead (push, shortcut, an older link): the person opens
     * instead. Null: [id] is shown as it is.
     */
    suspend fun personLeadOf(id: Long): Long? {
        if (!api.people) return null
        val all = dao.conversations().mapNotNull { e -> parseObj(e.json)?.let { ChatJson.conversation(it) } }
        all.firstOrNull { it.id == id }?.person?.let { p -> if (p.conversationId != id) return p.conversationId }
        return all.firstOrNull { ChatPeople.isEntry(it) && it.id != id && id in it.person!!.wayIds }?.id
    }

    /**
     * The chat of a person opened: each way catches up like a chat of its own (`after=`, `changedSince=`), the views
     * of the folded ways (bubbles, ticks) and the person itself (the way of an answer) fresh.
     */
    suspend fun openPerson(entry: ChatConversation) {
        val p = entry.person ?: return
        ensureOwner()
        for (w in p.wayIds) {
            if (w != entry.id) refreshConversation(w)
            openConversation(w)
        }
        refreshPerson(p.id)
    }

    /** `GET persons/:id` → the entry stored; gone (404): the list anew. */
    suspend fun refreshPerson(personId: Long): ChatResult<ChatConversation> {
        val r = api.person(personId)
        when {
            r is ChatResult.Ok -> storeConversations(listOf(r.value), Store.Entry)
            r is ChatResult.Failed && r.http == 404 -> coalesce("list-full", 300L) { syncConversations(full = true) }
        }
        return r
    }

    /** After a message to a way of a person: the list and the way of the next answer follow. */
    fun personSent(personId: Long) {
        coalesce("person:$personId", 800L) { refreshPerson(personId) }
    }

    /** Everything of the person up to [upTo] (the newest message shown) is read — on every way. */
    suspend fun markPersonRead(entry: ChatConversation, upTo: Long) {
        val p = entry.person ?: return
        if (entry.unread == 0 && p.ways.all { it.unread == 0 }) return
        dao.conversation(entry.id)?.let { e -> parseObj(e.json)?.let { dao.upsertConversations(listOf(e.copy(json = ChatJsonPatch.read(it, 0, null).toString()))) } }
        val r = api.personRead(p.id, upTo)
        if (r !is ChatResult.Ok) return
        for ((cid, lastRead, unread) in r.value.ways) {
            if (cid == entry.id) continue
            val e = dao.conversation(cid) ?: continue
            parseObj(e.json)?.let { dao.upsertConversations(listOf(e.copy(json = ChatJsonPatch.read(it, unread, lastRead).toString()))) }
        }
        val lead = r.value.ways.firstOrNull { it.first == entry.id }
        dao.conversation(entry.id)?.let { e ->
            parseObj(e.json)?.let { o ->
                val c = ChatJson.conversation(o)
                // the person's ways each with their unread (the entry counts them together)
                val ways = c?.person?.let { pp -> pp.copy(ways = pp.ways.map { w -> r.value.ways.firstOrNull { it.first == w.conversationId }?.let { x -> w.copy(unread = x.third) } ?: w }) }
                val patched = ChatJsonPatch.read(o, r.value.unread, lead?.second)
                if (ways != null) patched.add("person", ChatPeople.write(ways))
                dao.upsertConversations(listOf(e.copy(json = patched.toString())))
            }
        }
        p.wayIds.forEach { notifier.get().conversationRead(it) }
    }

    /** The search over all ways of a person. */
    suspend fun searchPerson(personId: Long, q: String): ChatResult<ChatSearchResult> = api.personSearch(personId, q)

    @Volatile private var lastSuggestMs = 0L
    private val _suggestions = MutableStateFlow<List<ChatPersonSuggestion>>(emptyList())

    /** Messenger 1.21: the suggestions „… ist vielleicht …“ (the card above the list shows the first). */
    val suggestions: StateFlow<List<ChatPersonSuggestion>> = _suggestions.asStateFlow()

    /** `GET persons/suggestions` when „Chats“ opens, then at most every 15 minutes ([force]: now). */
    suspend fun loadSuggestions(force: Boolean = false) {
        if (!api.people) {
            _suggestions.value = emptyList()
            return
        }
        val now = clock()
        if (!force && lastSuggestMs > 0 && now - lastSuggestMs < ChatPeople.SUGGEST_EVERY_MS) return
        lastSuggestMs = now
        (api.personSuggestions() as? ChatResult.Ok)?.let { _suggestions.value = it.value }
    }

    /** „Nicht jetzt“ (7 days) or „Nicht mehr vorschlagen“: the card goes. */
    suspend fun answerSuggestion(s: ChatPersonSuggestion, action: String): ChatResult<Unit> {
        val r = api.answerSuggestion(s.id, action)
        if (r is ChatResult.Ok) _suggestions.update { l -> l.filter { it.id != s.id } }
        return r
    }

    /**
     * `POST persons`: [conversationIds] (and the account [userId]) one person, named after [name] (`account` or a
     * conversation's id) → the entry, stored; the list follows without `since` (the ways leave it).
     */
    suspend fun mergePerson(conversationIds: List<Long>, userId: Long?, name: String?): ChatResult<ChatConversation> {
        val r = api.merge(conversationIds, userId, name)
        if (r is ChatResult.Ok) {
            storeConversations(listOf(r.value), Store.Entry)
            _suggestions.update { l -> l.filter { it.conversationId !in conversationIds && (userId == null || it.userId != userId) } }
            coalesce("list-full", 300L) { syncConversations(full = true) }
        }
        return r
    }

    /** „Trennen“: [conversationId] its own chat again — nothing is lost; the entries stored, the list anew. */
    suspend fun splitWay(personId: Long, conversationId: Long): ChatResult<ChatPersonSplit> {
        val r = api.splitWay(personId, conversationId)
        if (r is ChatResult.Ok) {
            storeConversations(r.value.conversations, Store.Entry)
            coalesce("list-full", 300L) { syncConversations(full = true) }
        }
        return r
    }

    /** `PATCH persons/:id` (the name in the list) → the entry. */
    suspend fun patchPerson(personId: Long, body: JsonObject): ChatResult<ChatConversation> {
        val r = api.patchPerson(personId, body)
        if (r is ChatResult.Ok) storeConversations(listOf(r.value), Store.Entry)
        return r
    }

    /** `POST persons/:id/archive`: every way archived (or back). */
    suspend fun archivePerson(personId: Long, archived: Boolean): ChatResult<ChatConversation> {
        val r = api.archivePerson(personId, archived)
        if (r is ChatResult.Ok) storeConversations(listOf(r.value), Store.Entry)
        return r
    }

    /** `GET persons/candidates`: who [conversationId] can be merged with. */
    suspend fun personCandidates(conversationId: Long): ChatResult<ChatPersonCandidates> = api.personCandidates(conversationId)

    companion object {
        const val SOURCE_NOTIFICATION = "notification"
        private const val KV_OWNER = "owner"
        private const val KV_PROFILE = "me"
        private const val KV_LIST_SINCE = "list_since"
        private const val KV_CHANNELS_SINCE = "channels_since"
        private const val KV_CHANGED = "changed:"
        private const val KV_OLDER = "older:"
        private const val KV_GAPS = "gaps:"
        private const val PAGE = 50
        private const val AROUND_PAGE = 50
        private const val MAX_CATCHUP_PAGES = 6
        private const val COALESCE_MS = 250L
        private const val CHANGED_SLACK_MS = 5_000L
        private const val PROFILE_TTL_MS = 10 * 60_000L
        private const val LIST_TTL_MS = 30_000L
        const val ACTIVE_EVERY_MS = 20_000L
        private const val POLL_MS = 6_000L
        private const val SHORTCUT_DEBOUNCE_MS = 1_500L
    }
}
