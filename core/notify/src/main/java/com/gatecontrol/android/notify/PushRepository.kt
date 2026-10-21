package com.gatecontrol.android.notify

import com.gatecontrol.android.network.PushAckRequest
import com.gatecontrol.android.network.PushPrefsRequest
import com.gatecontrol.android.notify.model.PushAction
import com.gatecontrol.android.notify.model.PushActionType
import com.gatecontrol.android.notify.model.PushMessage
import com.gatecontrol.android.notify.model.PushPriority
import com.gatecontrol.android.notify.model.PushState
import com.gatecontrol.android.notify.model.PushTopic
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the notification center does besides holding the stream open:
 * reacting to stream events (inbox, shade, acks), user actions (read,
 * dismissed, actions), the server inbox, device preferences and the test
 * message. The server is the source of truth; [inbox] mirrors its last
 * 100 messages for offline display.
 */
@Singleton
class PushRepository @Inject constructor(
    private val endpoint: PushEndpoint,
    private val store: PushPrefsStore,
    val inbox: InboxCache,
    private val presenter: PushPresenter,
    private val platform: PushPlatform,
) {
    companion object {
        const val MUTE_MS = 60 * 60_000L
        private const val MAX_ACK_BATCH = 200
        private val TOPIC_RE = Regex(
            "^(?:security|devices|services|system|admin_notice|plugin:[a-z0-9]+(?:-[a-z0-9]+)*:[a-z][a-z0-9_-]{0,31})$",
        )
        private val ACK_ORDER = listOf(PushState.DELIVERED, PushState.READ, PushState.DISMISSED)
    }

    /** Replaceable in tests. */
    internal var clock: () -> Long = System::currentTimeMillis

    private val ackMutex = Mutex()
    private val gson = Gson()

    // ─── Stream events ──────────────────────────────────────────────────

    suspend fun onHello(hello: PushEvent.Hello) {
        store.saveTopics(hello.topics)
        store.saveQuiet(hello.quiet)
        runCatching { presenter.ensureTopicChannels(hello.topics) }
            .onFailure { Timber.w("Push: channels not created (%s)", it.javaClass.simpleName) }
    }

    /**
     * A new or updated message: into the inbox, into the shade (unless its
     * topic is switched off on this device), then remembered as processed
     * and acknowledged as delivered right away.
     */
    suspend fun onNotification(message: PushMessage) {
        // A data push (`kind: "data"`) or one an app feature handles by itself:
        // dispatched to the app, never in the inbox or the shade, just the ack.
        val consumed = runCatching { presenter.consume(message) }.getOrDefault(false)
        if (consumed || message.isData) {
            store.addPendingAcks(listOf(PendingAck(message.seq, PushState.DELIVERED)))
            store.saveLastSeq(message.seq)
            flushAcks()
            return
        }
        if (message.isChat) {
            onChat(message)
            return
        }
        inbox.load()
        val settings = store.current()
        val seenBefore = inbox.get(message.id)?.seq == message.seq
        inbox.upsert(message)
        val topicOff = message.topic in settings.mutedTopics &&
            message.priorityLevel != PushPriority.CRITICAL && message.topic != PushTopic.ADMIN_NOTICE
        // A replay of a message already read here (ack still pending) stays out of the shade.
        val unread = inbox.get(message.id)?.let { it.seq == message.seq && it.isUnread } ?: message.isUnread
        if (settings.enabled && !topicOff && unread) {
            val quiet = settings.isLocallyMuted(message.topic, clock())
            runCatching {
                presenter.show(message, PushChannels.notificationId(message), PushChannels.channelFor(message), quiet)
            }.onFailure { Timber.w("Push: notification not shown (%s)", it.javaClass.simpleName) }
        }
        if (!seenBefore && message.isUnread) {
            runCatching { presenter.onNew(message) }
                .onFailure { Timber.w("Push: message hook failed (%s)", it.javaClass.simpleName) }
        }
        store.addPendingAcks(listOf(PendingAck(message.seq, PushState.DELIVERED)))
        store.saveLastSeq(message.seq)
        flushAcks()
    }

    /**
     * A chat notification (core 1.177+): not in the inbox (the server keeps
     * it out of its inbox too), shown by the chat feature unless its topic
     * is switched off here, acknowledged as delivered like any other.
     */
    private suspend fun onChat(message: PushMessage) {
        val settings = store.current()
        val topicOff = message.topic in settings.mutedTopics
        if (settings.enabled && !topicOff && message.isUnread) {
            runCatching { presenter.showChat(message, settings.isLocallyMuted(message.topic, clock())) }
                .onFailure { Timber.w("Push: chat notification not shown (%s)", it.javaClass.simpleName) }
        }
        store.addPendingAcks(listOf(PendingAck(message.seq, PushState.DELIVERED)))
        store.saveLastSeq(message.seq)
        flushAcks()
    }

    /**
     * Chat notifications [seqs] were read (the conversation opened, "Als
     * gelesen markieren", an answer from the notification): ack `read` so
     * the person's other devices take them away too.
     */
    suspend fun ackChatRead(seqs: Collection<Long>, action: String? = null) {
        val valid = seqs.filter { it > 0 }.distinct()
        if (valid.isEmpty()) return
        val validAction = action?.takeIf { PushEventParser.ACK_ACTION_RE.matches(it) }
        queueAcks(valid.map { PendingAck(it, PushState.READ, validAction) })
    }

    /** Read on another device: mark read here and take it out of the shade. */
    suspend fun onRead(ids: List<Long>) {
        runCatching { presenter.chatGone(ids) }
        val before = inbox.items.value
        inbox.setState(ids, PushState.READ)
        cancelShown(ids, before)
    }

    /** Withdrawn by the server: gone from inbox and shade. */
    suspend fun onRevoke(ids: List<Long>) {
        runCatching { presenter.chatGone(ids) }
        val before = inbox.items.value
        inbox.remove(ids)
        cancelShown(ids, before)
    }

    /**
     * Cancels the notifications of [ids]. With a collapse key the shown
     * notification is shared: it stays when a newer unread message of the
     * same key is still there (it is the one being shown).
     */
    private fun cancelShown(ids: Collection<Long>, before: List<PushMessage>) {
        val after = inbox.items.value
        for (id in ids) {
            val msg = before.firstOrNull { it.id == id }
            val key = msg?.collapseKey
            if (key != null && after.any { it.collapseKey == key && it.id != id && it.isUnread && it.seq > msg.seq }) continue
            presenter.cancel(PushChannels.notificationId(id, key))
        }
    }

    // ─── User actions ───────────────────────────────────────────────────

    /** Opened / "Gesehen" / an action: read here, on the server and on the person's other devices. */
    suspend fun markRead(ids: Collection<Long>, action: String? = null) {
        inbox.load()
        val before = inbox.items.value
        val targets = before.filter { it.id in ids }
        inbox.setState(ids, PushState.READ)
        cancelShown(ids, before)
        val validAction = action?.takeIf { PushEventParser.ACK_ACTION_RE.matches(it) }
        queueAcks(targets.filter { it.isUnread || validAction != null }.map { PendingAck(it.seq, PushState.READ, validAction) })
    }

    suspend fun markAllRead() {
        markRead(inbox.items.value.filter { it.isUnread }.map { it.id })
    }

    /** Swiped away in the shade. */
    suspend fun dismissed(id: Long) {
        inbox.load()
        val msg = inbox.get(id) ?: return
        inbox.setState(listOf(id), PushState.DISMISSED)
        if (msg.isUnread) queueAcks(listOf(PendingAck(msg.seq, PushState.DISMISSED)))
    }

    /** "1 h stumm": the topic stays quiet on this device for an hour, plus ack with the action. */
    suspend fun muteForAnHour(id: Long, actionId: String = PushActionType.MUTE_1H) {
        inbox.load()
        val msg = inbox.get(id) ?: return
        val now = clock()
        store.muteLocally(msg.topic, now + MUTE_MS, now)
        markRead(listOf(id), actionId)
    }

    /** Runs a background action (mute_1h, done, ack) of message [id]. */
    suspend fun runAction(id: Long, action: PushAction) {
        when (action.type) {
            PushActionType.MUTE_1H -> muteForAnHour(id, action.id)
            else -> markRead(listOf(id), action.id)
        }
    }

    // ─── Server calls ───────────────────────────────────────────────────

    private suspend fun queueAcks(acks: List<PendingAck>) {
        if (acks.isEmpty()) return
        store.addPendingAcks(acks)
        flushAcks()
    }

    /**
     * Sends pending acks (grouped by state and action, ≤ 200 per call).
     * What fails stays pending and is retried after the next connect.
     */
    suspend fun flushAcks(): Boolean = ackMutex.withLock {
        val url = endpoint.serverUrl()
        if (url.isEmpty()) return@withLock false
        val pending = store.pendingAcks()
        if (pending.isEmpty()) return@withLock true
        val groups = pending.groupBy { it.state to it.action }
            .toList()
            .sortedBy { (k, _) -> ACK_ORDER.indexOf(k.first).let { i -> if (i < 0) 99 else i } }
        var allSent = true
        for ((key, acks) in groups) {
            for (chunk in acks.chunked(MAX_ACK_BATCH)) {
                try {
                    endpoint.api(url).pushAck(PushAckRequest(chunk.map { it.seq }.distinct(), key.first, key.second))
                    store.removePendingAcks(chunk)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HttpException) {
                    // 400 (unknown seq/state) never succeeds: drop it. Others: retry later.
                    if (e.code() == 400) store.removePendingAcks(chunk) else allSent = false
                    Timber.d("Push: ack %s failed (HTTP %d)", key.first, e.code())
                } catch (e: Exception) {
                    allSent = false
                    Timber.d("Push: ack %s failed (%s)", key.first, e.javaClass.simpleName)
                }
            }
        }
        allSent
    }

    /**
     * Server inbox replaces the cache; notifications read or withdrawn
     * meanwhile (missed `read` events while offline) leave the shade.
     */
    suspend fun refreshInbox(): Boolean {
        val url = endpoint.serverUrl()
        if (url.isEmpty()) return false
        return try {
            val response = endpoint.api(url).pushInbox(limit = InboxCache.MAX_ITEMS)
            val items = response.items.orEmpty().mapNotNull { PushEventParser.toMessage(it) }
            val before = inbox.replaceAll(items)
            val stillUnread = items.filter { it.isUnread }.map { it.id }.toSet()
            val gone = before.filter { it.isUnread && it.id !in stillUnread }.map { it.id }
            if (gone.isNotEmpty()) cancelShown(gone, before)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Push: inbox refresh failed (%s)", e.javaClass.simpleName)
            false
        }
    }

    /**
     * Reads the person's quiet hours (`GET push/prefs`, read only) for the
     * settings screen. Older servers without them (404, no `quiet` field)
     * leave what is known unchanged.
     */
    suspend fun refreshQuiet(): Boolean {
        val url = endpoint.serverUrl()
        if (url.isEmpty()) return false
        return try {
            val info = PushEventParser.quietInfo(endpoint.api(url).getPushPrefs())
            store.saveQuiet(info)
            info.known
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Push: prefs not read (%s)", e.javaClass.simpleName)
            false
        }
    }

    /** Asks the server for a test message to this device. Returns its seq, null on failure. */
    suspend fun requestTest(): Result<Long?> {
        val url = endpoint.serverUrl()
        if (url.isEmpty()) return Result.failure(IllegalStateException("not configured"))
        return try {
            val r = endpoint.api(url).pushTest()
            if (r.ok) Result.success(r.seq) else Result.failure(IllegalStateException(r.error ?: "failed"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Reports the device preferences (`PUT /push/prefs`) when they differ
     * from what the server last accepted: switch, mode, muted topics and
     * whether battery optimisation restricts the app. Nothing is sent
     * before push was switched on once on this device.
     */
    suspend fun syncPrefs(force: Boolean = false): Boolean {
        val settings = store.current()
        if (!settings.everEnabled) return true
        val url = endpoint.serverUrl()
        if (url.isEmpty()) return false
        val request = PushPrefsRequest(
            enabled = settings.enabled,
            mode = settings.mode.wire,
            mutedTopics = settings.mutedTopics.filter { TOPIC_RE.matches(it) && it != PushTopic.ADMIN_NOTICE }.sorted().take(100),
            restricted = platform.isBatteryRestricted(),
        )
        val key = gson.toJson(request)
        if (!force && key == store.syncedPrefs()) return true
        return try {
            endpoint.api(url).pushPrefs(request)
            store.setSyncedPrefs(key)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Push: prefs not synced (%s)", e.javaClass.simpleName)
            false
        }
    }

    /** After the stream opened: what may have piled up while offline. */
    suspend fun afterConnect() {
        syncPrefs()
        flushAcks()
        refreshInbox()
    }
}
