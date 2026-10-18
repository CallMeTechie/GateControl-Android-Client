package com.gatecontrol.android.chat

import com.gatecontrol.android.chat.ChatJson.flag
import com.gatecontrol.android.chat.ChatJson.id
import com.gatecontrol.android.chat.ChatJson.obj
import com.gatecontrol.android.chat.ChatJson.str
import com.gatecontrol.android.notify.model.PushMessage
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * What a chat push (`kind: "message"`, core 1.177+) carries in `data`
 * (core docs/feature-notification-center.md "Chat-Benachrichtigungen").
 * [replyPath] / [readPath] are plugin API paths, checked against the only
 * shapes the messenger sends.
 */
data class ChatPushPayload(
    val conversationId: Long,
    val messageId: Long?,
    val conversationTitle: String?,
    val isGroup: Boolean,
    /** Number, or null for "GateControl" (a system notice). */
    val senderId: Long?,
    val senderName: String?,
    val senderAvatarUrl: String?,
    val replyPath: String?,
    val replyLabel: String?,
    val readPath: String?,
    val readLabel: String?,
    /** The `mark_read` body (`{ upTo }`), as JSON text. */
    val readBody: String?,
    /**
     * Messenger 1.4: what the tap opens beyond the chat (`data.open`):
     * `arrival` (a departure hint: share the arrival), else the message.
     */
    val open: String? = null,
)

/** One message line of a conversation's notification. */
data class ChatNotifLine(
    /** Push delivery sequence (acks); 0 for the device's own reply. */
    val seq: Long,
    /** Notification id (`read` / `revoke` events); 0 for the own reply. */
    val pushId: Long,
    val senderKey: String,
    val senderName: String,
    val avatarUrl: String?,
    val text: String,
    val timeMs: Long,
    val mine: Boolean = false,
)

/** A conversation's notification as the device shows it. */
data class ChatNotifConversation(
    val conversationId: Long,
    val title: String?,
    val isGroup: Boolean,
    val replyPath: String?,
    val replyLabel: String?,
    val readPath: String?,
    val readLabel: String?,
    val readBody: String?,
    val lines: List<ChatNotifLine>,
    /** The newest pushed message (a tap jumps to it) and what to open with it. */
    val messageId: Long? = null,
    val open: String? = null,
) {
    /** Seqs of the pushes shown (what `read` acks name). */
    val seqs: List<Long> get() = lines.filter { !it.mine && it.seq > 0 }.map { it.seq }
}

/** Pure parts of the chat notifications. Unit-tested. */
object ChatNotifyLogic {
    /** Messages kept per conversation (MessagingStyle shows the newest). */
    const val MAX_LINES = 7

    fun payload(message: PushMessage): ChatPushPayload? {
        if (!message.isChat) return null
        val data = message.dataJson?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() } ?: return null
        return payload(data)
    }

    fun payload(data: JsonObject): ChatPushPayload? {
        val conv = data.obj("conversation")
        val convId = conv?.id("id") ?: data.id("conversationId") ?: return null
        val sender = data.obj("sender")
        var replyPath: String? = null
        var replyLabel: String? = null
        var readPath: String? = null
        var readLabel: String? = null
        var readBody: String? = null
        data.get("actions")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { e ->
            val a = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val path = a.str("path")
            when (a.str("type")) {
                "reply" -> if (path != null && ChatApi.REPLY_PATH_RE.matches(path) && pathConversation(path) == convId) {
                    replyPath = path
                    replyLabel = ChatJson.line(a.str("label"), 40)
                }
                "mark_read" -> if (path != null && ChatApi.READ_PATH_RE.matches(path) && pathConversation(path) == convId) {
                    readPath = path
                    readLabel = ChatJson.line(a.str("label"), 40)
                    readBody = a.obj("body")?.let { b -> b.id("upTo")?.let { ChatJson.readBody(it).toString() } }
                }
            }
        }
        return ChatPushPayload(
            conversationId = convId,
            messageId = data.id("messageId"),
            conversationTitle = ChatJson.line(conv?.str("title"), 120),
            isGroup = conv?.flag("isGroup") == true,
            senderId = sender?.id("id"),
            senderName = ChatJson.line(sender?.str("name"), 80),
            senderAvatarUrl = ChatJson.avatarPath(sender?.str("avatarUrl")),
            replyPath = replyPath,
            replyLabel = replyLabel,
            readPath = readPath,
            readLabel = readLabel,
            readBody = readBody,
            // `deepLink` (Messenger 1.4: `arrival` for a departure hint), `open` as an alias.
            open = (data.str("deepLink") ?: data.str("open"))?.takeIf { it in OPEN_TARGETS },
        )
    }

    /** `data.open` values of Messenger 1.4 pushes the app acts on. */
    val OPEN_TARGETS = setOf(OPEN_ARRIVAL, OPEN_MESSAGE)
    const val OPEN_ARRIVAL = "arrival"
    const val OPEN_MESSAGE = "message"

    /** The conversation id in `conversations/<id>/…`. */
    fun pathConversation(path: String): Long? = path.split('/').getOrNull(1)?.toLongOrNull()

    /** A new push of a conversation: appended (a replay of a seq already shown is not added twice). */
    fun add(current: ChatNotifConversation?, payload: ChatPushPayload, line: ChatNotifLine): ChatNotifConversation {
        val lines = (current?.lines.orEmpty().filter { it.seq != line.seq || line.seq == 0L } + line).takeLast(MAX_LINES)
        return ChatNotifConversation(
            conversationId = payload.conversationId,
            title = payload.conversationTitle ?: current?.title,
            isGroup = payload.isGroup,
            // The newest push's actions win (mark_read names the newest message).
            replyPath = payload.replyPath,
            replyLabel = payload.replyLabel ?: current?.replyLabel,
            readPath = payload.readPath ?: current?.readPath,
            readLabel = payload.readLabel ?: current?.readLabel,
            readBody = payload.readBody ?: current?.readBody,
            lines = lines,
            messageId = payload.messageId ?: current?.messageId,
            open = payload.open,
        )
    }

    /** The device's own answer (from the notification) appended. */
    fun addMine(current: ChatNotifConversation, name: String, text: String, timeMs: Long): ChatNotifConversation =
        current.copy(lines = (current.lines + ChatNotifLine(0, 0, "me", name, null, text, timeMs, mine = true)).takeLast(MAX_LINES))

    /** Pushes [pushIds] read elsewhere or withdrawn: removed; null when no message of others is left. */
    fun remove(current: ChatNotifConversation, pushIds: Collection<Long>): ChatNotifConversation? {
        val left = current.lines.filter { it.mine || it.pushId !in pushIds }
        return if (left.none { !it.mine }) null else current.copy(lines = left)
    }

    // ─── storage (SharedPreferences JSON) ───────────────────────────

    fun toJson(all: Map<Long, ChatNotifConversation>): String = JsonArray().apply {
        all.values.forEach { c ->
            add(JsonObject().apply {
                addProperty("id", c.conversationId)
                c.title?.let { addProperty("title", it) }
                addProperty("group", c.isGroup)
                c.replyPath?.let { addProperty("replyPath", it) }
                c.replyLabel?.let { addProperty("replyLabel", it) }
                c.readPath?.let { addProperty("readPath", it) }
                c.readLabel?.let { addProperty("readLabel", it) }
                c.readBody?.let { addProperty("readBody", it) }
                c.messageId?.let { addProperty("messageId", it) }
                c.open?.let { addProperty("open", it) }
                add("lines", JsonArray().apply {
                    c.lines.forEach { l ->
                        add(JsonObject().apply {
                            addProperty("seq", l.seq)
                            addProperty("pushId", l.pushId)
                            addProperty("key", l.senderKey)
                            addProperty("name", l.senderName)
                            l.avatarUrl?.let { addProperty("avatar", it) }
                            addProperty("text", l.text)
                            addProperty("time", l.timeMs)
                            addProperty("mine", l.mine)
                        })
                    }
                })
            })
        }
    }.toString()

    fun fromJson(raw: String?): Map<Long, ChatNotifConversation> {
        if (raw.isNullOrBlank()) return emptyMap()
        val arr = runCatching { JsonParser.parseString(raw).asJsonArray }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<Long, ChatNotifConversation>()
        for (e in arr) {
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val id = o.id("id") ?: continue
            val lines = o.get("lines")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { le ->
                val l = le.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                ChatNotifLine(
                    seq = l.get("seq")?.asLong ?: 0L,
                    pushId = l.get("pushId")?.asLong ?: 0L,
                    senderKey = l.str("key") ?: return@mapNotNull null,
                    senderName = l.str("name") ?: "",
                    avatarUrl = ChatJson.avatarPath(l.str("avatar")),
                    text = l.str("text") ?: "",
                    timeMs = l.get("time")?.asLong ?: 0L,
                    mine = l.flag("mine") == true,
                )
            }.orEmpty()
            if (lines.isEmpty()) continue
            out[id] = ChatNotifConversation(
                conversationId = id,
                title = o.str("title"),
                isGroup = o.flag("group") == true,
                replyPath = o.str("replyPath")?.takeIf { ChatApi.REPLY_PATH_RE.matches(it) },
                replyLabel = o.str("replyLabel"),
                readPath = o.str("readPath")?.takeIf { ChatApi.READ_PATH_RE.matches(it) },
                readLabel = o.str("readLabel"),
                readBody = o.str("readBody"),
                lines = lines.takeLast(MAX_LINES),
                messageId = o.id("messageId"),
                open = o.str("open")?.takeIf { it in OPEN_TARGETS },
            )
        }
        return out
    }
}
