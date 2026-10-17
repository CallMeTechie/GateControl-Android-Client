package com.gatecontrol.android.chat

import com.gatecontrol.android.chat.db.OutboxEntity
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A message waiting in the outbox, as the conversation shows it. */
data class ChatPending(
    val clientId: String,
    val conversationId: Long,
    val createdAtMs: Long,
    val kind: String,
    val text: String?,
    val replyToId: Long?,
    val localPath: String?,
    val mime: String?,
    val meta: ChatMeta?,
    val location: ChatLocation?,
    val failed: Boolean,
    val error: String?,
) {
    companion object {
        fun of(e: OutboxEntity): ChatPending = ChatPending(
            clientId = e.clientId,
            conversationId = e.conversationId,
            createdAtMs = e.createdAtMs,
            kind = e.kind,
            text = e.text,
            replyToId = e.replyToId,
            localPath = e.filePath,
            mime = e.mime,
            meta = e.metaJson?.let { ChatOutboxLogic.metaOf(it) },
            location = e.locationJson?.let { ChatOutboxLogic.locationOf(it) },
            failed = e.state == OutboxEntity.STATE_FAILED,
            error = e.error,
        )
    }
}

/** One row of the conversation screen. */
sealed interface TimelineItem {
    val key: String

    /** Day separator ("Heute", "Gestern", a date). */
    data class Day(val date: LocalDate) : TimelineItem {
        override val key: String get() = "d:$date"
    }

    /**
     * A stored message. [status] own messages only (ticks). [showSender]:
     * a group's message by someone else after another sender (or a day).
     */
    data class Message(
        val message: ChatMessage,
        val status: String?,
        val showSender: Boolean,
    ) : TimelineItem {
        override val key: String get() = "m:${message.id}"
    }

    data class Pending(val pending: ChatPending) : TimelineItem {
        override val key: String get() = "c:${pending.clientId}"
    }
}

/** Pure helpers of the conversation screen and the sync. Unit-tested. */
object ChatTimeline {

    /** Tick of an own message: the newer of the message's status and the conversation's receipts. */
    fun status(message: ChatMessage, readUpTo: Long?, deliveredUpTo: Long?): String? {
        if (!message.mine || message.isSystem || message.deleted) return null
        val fromReceipts = when {
            readUpTo != null && message.id <= readUpTo -> ChatMessage.STATUS_READ
            deliveredUpTo != null && message.id <= deliveredUpTo -> ChatMessage.STATUS_DELIVERED
            else -> ChatMessage.STATUS_SENT
        }
        return listOf(message.status ?: ChatMessage.STATUS_SENT, fromReceipts).maxBy { rank(it) }
    }

    private fun rank(status: String): Int = when (status) {
        ChatMessage.STATUS_READ -> 2
        ChatMessage.STATUS_DELIVERED -> 1
        else -> 0
    }

    fun epochMs(iso: String?): Long? = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    /**
     * Rows of a conversation: stored messages (ascending ids), then the
     * outbox items not stored yet (a stored message with the same clientId
     * replaces its outbox entry), with day separators.
     */
    fun build(
        messages: List<ChatMessage>,
        pending: List<ChatPending>,
        conversation: ChatConversation?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<TimelineItem> {
        val stored = messages.sortedBy { it.id }
        val storedClientIds = stored.mapNotNullTo(HashSet()) { it.clientId }
        val waiting = pending.filter { it.clientId !in storedClientIds }.sortedBy { it.createdAtMs }
        val out = ArrayList<TimelineItem>(stored.size + waiting.size + 8)
        var lastDay: LocalDate? = null
        var lastSender: Long? = null
        var lastWasSystem = true
        for (m in stored) {
            val day = epochMs(m.createdAt)?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            if (day != null && day != lastDay) {
                out += TimelineItem.Day(day)
                lastDay = day
                lastWasSystem = true
            }
            val showSender = conversation?.isGroup == true && !m.mine && !m.isSystem && (lastWasSystem || lastSender != m.senderId)
            out += TimelineItem.Message(m, status(m, conversation?.readUpTo, conversation?.deliveredUpTo), showSender)
            lastSender = m.senderId
            lastWasSystem = m.isSystem
        }
        for (p in waiting) {
            val day = Instant.ofEpochMilli(p.createdAtMs).atZone(zone).toLocalDate()
            if (day != lastDay) {
                out += TimelineItem.Day(day)
                lastDay = day
            }
            out += TimelineItem.Pending(p)
        }
        return out
    }

    /** Outbox entries that are stored on the server already (same clientId): to be removed. */
    fun delivered(messages: List<ChatMessage>, pending: List<ChatPending>): List<String> {
        val ids = messages.mapNotNullTo(HashSet()) { it.clientId }
        return pending.filter { it.clientId in ids }.map { it.clientId }
    }

    /**
     * Cached ids a page proves gone (hidden for me, expired, removed by
     * retention): those in the page's id range [from]…[to] it does not
     * contain. A page of `after=` starts after its cursor.
     */
    fun vanished(cachedInRange: List<Long>, page: List<ChatMessage>): List<Long> {
        val have = page.mapTo(HashSet()) { it.id }
        return cachedInRange.filter { it !in have }
    }

    /** May the caller still edit / delete for everyone? (sender only, within the window). */
    fun withinWindow(message: ChatMessage, editWindowS: Int, nowMs: Long): Boolean {
        if (!message.mine || message.deleted || message.isSystem || editWindowS <= 0) return false
        val created = epochMs(message.createdAt) ?: return false
        return nowMs - created <= editWindowS * 1000L
    }

    fun canEdit(message: ChatMessage, editWindowS: Int, nowMs: Long): Boolean =
        withinWindow(message, editWindowS, nowMs) &&
            message.kind in setOf(ChatMessage.KIND_TEXT, ChatMessage.KIND_IMAGE, ChatMessage.KIND_FILE)

    /** Total unread over the conversations that are not muted (tab badge). */
    fun badge(conversations: List<ChatConversation>): Int =
        conversations.filter { !it.muted }.sumOf { it.unread }.coerceAtMost(999)

    /** Who types in [conversationId] right now, without the caller. */
    fun typingNow(typing: Map<Long, Map<Long, Long>>, conversationId: Long, myId: Long?, nowMs: Long): List<Long> =
        typing[conversationId].orEmpty().filter { (uid, until) -> until > nowMs && uid != myId }.keys.toList()
}

/** Outcome of one outbox attempt. */
sealed interface OutboxStep {
    data object Done : OutboxStep

    /** Try again at [atMs] (network, server busy). */
    data class Retry(val atMs: Long) : OutboxStep

    /** The upload went stale (unsent uploads are removed after 24 h): upload again, then send. */
    data object Reupload : OutboxStep

    /** The server will never take it: kept as failed until the person retries or deletes it. */
    data class GiveUp(val code: String) : OutboxStep
}

/** Pure decisions of the outbox. Unit-tested. */
object ChatOutboxLogic {
    const val MAX_ATTEMPTS = 12
    private const val BASE_DELAY_MS = 5_000L
    private const val MAX_DELAY_MS = 15 * 60_000L

    fun backoffMs(attempts: Int): Long {
        val shift = (attempts - 1).coerceIn(0, 10)
        return (BASE_DELAY_MS shl shift).coerceAtMost(MAX_DELAY_MS)
    }

    /** What to do after the server's answer [result] to attempt number [attempts] (1-based). */
    fun step(result: ChatResult<*>, attempts: Int, hadUpload: Boolean, nowMs: Long): OutboxStep = when (result) {
        is ChatResult.Ok -> OutboxStep.Done
        ChatResult.Offline, ChatResult.NotConfigured ->
            if (attempts >= MAX_ATTEMPTS) OutboxStep.GiveUp("offline") else OutboxStep.Retry(nowMs + backoffMs(attempts))
        is ChatResult.Failed -> when {
            result.code == "media_invalid" && hadUpload && attempts < 3 -> OutboxStep.Reupload
            result.http == 429 || result.http == 408 || result.http >= 500 || result.http == 0 && result.code != ChatApi.CODE_INVALID ->
                if (attempts >= MAX_ATTEMPTS) OutboxStep.GiveUp(result.code) else OutboxStep.Retry(nowMs + backoffMs(attempts))
            // 400 invalid, 403 readonly / forbidden, 404 gone, 409 conflicts, 413 / 415 / 507 media.
            else -> OutboxStep.GiveUp(result.code)
        }
    }

    /** The next entry to send now: queued, due, oldest first (one conversation's order is kept). */
    fun next(items: List<OutboxEntity>, nowMs: Long): OutboxEntity? {
        val queued = items.filter { it.state == OutboxEntity.STATE_QUEUED }.sortedBy { it.createdAtMs }
        // Within a conversation the oldest goes first, even if a newer one would be due earlier.
        val heads = queued.groupBy { it.conversationId }.values.map { it.first() }
        return heads.filter { it.nextAttemptMs <= nowMs }.minByOrNull { it.createdAtMs }
    }

    /** When the next queued entry is due (null: nothing queued). */
    fun nextDueMs(items: List<OutboxEntity>): Long? =
        items.filter { it.state == OutboxEntity.STATE_QUEUED }.minOfOrNull { it.nextAttemptMs }

    fun metaJson(meta: ChatMeta?): String? = meta?.let { m ->
        ChatJson.sendBody("xxxxxxxx", ChatMessage.KIND_FILE, null, meta = m).get("meta")?.toString()
    }

    fun metaOf(json: String): ChatMeta? = runCatching {
        val o = JsonParser.parseString(json).asJsonObject
        // Reuse the server parser on a minimal message.
        ChatJson.message(
            JsonObject().apply {
                addProperty("id", 1)
                addProperty("conversationId", 1)
                addProperty("kind", ChatMessage.KIND_FILE)
                addProperty("createdAt", "2026-01-01T00:00:00.000Z")
                add("meta", o)
            },
        )?.meta
    }.getOrNull()

    fun locationJson(location: ChatLocation?): String? = location?.let {
        ChatJson.sendBody("xxxxxxxx", ChatMessage.KIND_LOCATION, null, location = it).get("location")?.toString()
    }

    fun locationOf(json: String): ChatLocation? = runCatching {
        val o = JsonParser.parseString(json).asJsonObject
        ChatJson.message(
            JsonObject().apply {
                addProperty("id", 1)
                addProperty("conversationId", 1)
                addProperty("kind", ChatMessage.KIND_LOCATION)
                addProperty("createdAt", "2026-01-01T00:00:00.000Z")
                add("location", o)
            },
        )?.location
    }.getOrNull()
}

/** Changes the app makes to cached server JSON (live events, own actions). Pure. */
object ChatJsonPatch {

    fun parse(json: String): JsonObject? = runCatching { JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()

    /** Deleted for everyone: the tombstone the server would send. */
    fun tombstone(o: JsonObject, deletedAt: String?): JsonObject = o.deepCopy().apply {
        addProperty("deleted", true)
        deletedAt?.let { addProperty("deletedAt", it) }
        for (k in listOf("text", "replyTo", "media", "meta", "location")) add(k, JsonNull.INSTANCE)
        add("reactions", JsonArray())
    }

    fun reactions(o: JsonObject, reactions: List<ChatReaction>): JsonObject = o.deepCopy().apply {
        add("reactions", JsonArray().apply {
            reactions.forEach { r ->
                add(JsonObject().apply {
                    addProperty("emoji", r.emoji)
                    addProperty("count", r.count)
                    add("userIds", JsonArray().apply { r.userIds.forEach { add(it) } })
                })
            }
        })
    }

    /** The caller read up to [lastReadId]: [unread] left. */
    fun read(o: JsonObject, unread: Int, lastReadId: Long?): JsonObject = o.deepCopy().apply {
        addProperty("unread", unread)
        if (lastReadId != null) {
            val me = get("me")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().also { add("me", it) }
            val current = ChatJson.idOf(me.get("lastReadId")) ?: 0L
            if (lastReadId > current) me.addProperty("lastReadId", lastReadId)
        }
    }

    /** Newest message of a conversation shown in the list (sent from this device). */
    fun lastMessage(conversation: JsonObject, message: JsonObject): JsonObject = conversation.deepCopy().apply {
        val current = get("lastMessage")?.takeIf { it.isJsonObject }?.asJsonObject?.let { ChatJson.idOf(it.get("id")) } ?: 0L
        val id = ChatJson.idOf(message.get("id")) ?: 0L
        if (id >= current) {
            add("lastMessage", message)
            addProperty("lastMessageId", id)
        }
    }
}
