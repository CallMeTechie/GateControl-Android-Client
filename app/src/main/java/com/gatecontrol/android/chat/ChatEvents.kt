package com.gatecontrol.android.chat

import com.gatecontrol.android.chat.ChatJson.flag
import com.gatecontrol.android.chat.ChatJson.id
import com.gatecontrol.android.chat.ChatJson.str
import com.google.gson.JsonElement
import java.time.Instant

/**
 * A realtime event of the messenger (README "Live events"), arriving on the
 * push stream as `event: plugin`. Ephemeral: after a reconnect the app
 * catches up over the API.
 */
sealed interface ChatEvent {
    val conversationId: Long

    data class MessageNew(
        override val conversationId: Long,
        val messageId: Long,
        val senderId: Long?,
        val clientId: String?,
        /** Messenger 1.14: the message belongs to a chat over this service (`bridge: "whatsapp"`), else null. */
        val bridge: String? = null,
    ) : ChatEvent

    data class MessageUpdate(override val conversationId: Long, val messageId: Long) : ChatEvent

    /** [reason] deleted (tombstone) | hidden | expired | retention (remove). */
    data class MessageDelete(override val conversationId: Long, val messageIds: List<Long>, val reason: String) : ChatEvent {
        val tombstone: Boolean get() = reason == REASON_DELETED
    }

    data class Reaction(
        override val conversationId: Long,
        val messageId: Long,
        val reactions: List<ChatReaction>,
    ) : ChatEvent

    /**
     * Receipts: [lastReadId] null when that person does not share them.
     * [self] = read on one of the caller's own devices, with [unread] left.
     * [upTo] (Messenger 1.1.0): the conversation's `readUpTo` /
     * `deliveredUpTo` for the caller right now; null from older servers.
     * [userId] 0 (Messenger 1.14): the other side of a bridged chat read —
     * contacts are no accounts, only [upTo] counts.
     */
    data class Read(
        override val conversationId: Long,
        val userId: Long,
        val lastReadId: Long?,
        val lastDeliveredId: Long?,
        val self: Boolean,
        val unread: Int?,
        val upTo: UpTo? = null,
    ) : ChatEvent

    /** `readUpTo` / `deliveredUpTo` of a `read` event (each null when nobody shares / nothing delivered). */
    data class UpTo(val readUpTo: Long?, val deliveredUpTo: Long?)

    /** "schreibt …" until [untilMs] (epoch ms); null = stopped. */
    data class Typing(override val conversationId: Long, val userId: Long, val untilMs: Long?) : ChatEvent

    /**
     * Messenger 1.14: contacts of a bridged chat type (`typing` with `userId: null`) — [contacts] are those who
     * type now (their names null when the service knows none), until [untilMs]; empty or null = stopped.
     */
    data class ContactTyping(override val conversationId: Long, val contacts: List<TypingContact>, val untilMs: Long?) : ChatEvent

    data class ConversationUpdate(override val conversationId: Long, val reasons: Set<String>) : ChatEvent {
        /** No longer managed by a plugin (1.1.0): a normal group now, its info editable again. */
        val released: Boolean get() = REASON_RELEASED in reasons

        /**
         * Messenger 1.17: older messages came into the chat over a bridge (at night, without push and without
         * `message.new`, already read) — its messages are loaded again.
         */
        val history: Boolean get() = REASON_HISTORY in reasons
    }

    data class ConversationRemove(override val conversationId: Long) : ChatEvent

    /** Messenger 1.4: the caller's lists or reminders changed on another device. */
    data class AccountChanged(val type: String) : ChatEvent {
        override val conversationId: Long get() = 0L
    }

    /**
     * Messenger 1.4: a person's status or stories changed (`status.update`,
     * `story.new`, `story.remove`) — no conversation ([conversationId] 0).
     */
    data class StatusChanged(val userId: Long, val storyId: String?, val type: String) : ChatEvent {
        override val conversationId: Long get() = 0L
    }

    /** A contact of a bridged chat who types. */
    data class TypingContact(val contactId: Long, val name: String?)

    companion object {
        const val REASON_DELETED = "deleted"
        const val REASON_RELEASED = "released"
        const val REASON_HISTORY = "history"
    }
}

/** Pure: `{ plugin, type, data }` of the messenger → [ChatEvent]; anything else null. Unit-tested. */
object ChatEvents {

    private val STATUS_TYPES = setOf("status.update", "story.new", "story.remove")
    private val ACCOUNT_TYPES = setOf("lists.update", "reminders.update")
    private val DELETE_REASONS = setOf("deleted", "hidden", "expired", "retention")
    private val REASON_RE = Regex("^[a-z_]{1,32}$")
    /** "schreibt …" never shows longer than this, whatever the server says. */
    const val TYPING_MAX_MS = 15_000L
    private const val MAX_TYPING_CONTACTS = 20

    fun parse(plugin: String, type: String, data: JsonElement?, nowMs: Long = System.currentTimeMillis()): ChatEvent? {
        if (plugin != MESSENGER_PLUGIN) return null
        val o = data?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        // Messenger 1.4: status and stories belong to a person, not to a conversation.
        if (type in ACCOUNT_TYPES) return ChatEvent.AccountChanged(type)
        if (type in STATUS_TYPES) {
            return ChatEvent.StatusChanged(o.id("userId") ?: return null, ChatJson14.pathKey(ChatJson14.keyOf(o.get("storyId"))), type)
        }
        val conv = o.id("conversationId") ?: return null
        return when (type) {
            "message.new" -> ChatEvent.MessageNew(
                conversationId = conv,
                messageId = o.id("messageId") ?: return null,
                senderId = o.id("senderId"),
                clientId = o.str("clientId")?.takeIf { ChatJson.isClientId(it) },
                bridge = ChatJson.token(o.str("bridge")),
            )
            "message.update" -> ChatEvent.MessageUpdate(conv, o.id("messageId") ?: return null)
            // Messenger 1.4: a poll's votes, an event's answers, a live arrival moved — the message changed.
            "poll.update", "event.update", "arrival.update", "action.update" -> ChatEvent.MessageUpdate(conv, o.id("messageId") ?: return null)
            "message.delete" -> {
                val ids = o.get("messageIds")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { ChatJson.idOf(it) }?.distinct()?.take(500)
                    ?: return null
                if (ids.isEmpty()) return null
                val reason = o.str("reason")?.takeIf { it in DELETE_REASONS } ?: ChatEvent.REASON_DELETED
                ChatEvent.MessageDelete(conv, ids, reason)
            }
            "reaction" -> ChatEvent.Reaction(
                conversationId = conv,
                messageId = o.id("messageId") ?: return null,
                reactions = ChatJson.reactions(o.get("reactions")?.takeIf { it.isJsonArray }?.asJsonArray),
            )
            "read" -> ChatEvent.Read(
                conversationId = conv,
                // Messenger 1.14: `userId: null` — the other side of a bridged chat (only with the marks it carries)
                userId = o.id("userId") ?: if (o.get("userId")?.isJsonNull == true && (o.has("readUpTo") || o.has("deliveredUpTo"))) 0L else return null,
                lastReadId = o.id("lastReadId"),
                lastDeliveredId = o.id("lastDeliveredId"),
                self = o.flag("self") == true,
                unread = o.get("unread")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt?.coerceAtLeast(0),
                upTo = if (o.has("readUpTo") || o.has("deliveredUpTo")) ChatEvent.UpTo(o.id("readUpTo"), o.id("deliveredUpTo")) else null,
            )
            "typing" -> {
                val until = ChatJson.time(o.str("until"))?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }?.coerceAtMost(nowMs + TYPING_MAX_MS)
                val contacts = o.get("contacts")?.takeIf { it.isJsonArray }?.asJsonArray
                if (o.id("userId") == null && contacts != null) {
                    // Messenger 1.14: contacts of a bridged chat
                    val list = contacts.mapNotNull { e ->
                        val c = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                        ChatEvent.TypingContact(c.id("contactId") ?: return@mapNotNull null, ChatJson.line(c.str("name"), 120))
                    }.distinctBy { it.contactId }.take(MAX_TYPING_CONTACTS)
                    return ChatEvent.ContactTyping(conv, list, until)
                }
                ChatEvent.Typing(conv, o.id("userId") ?: return null, until)
            }
            "conversation.update" -> ChatEvent.ConversationUpdate(
                conversationId = conv,
                reasons = o.str("reason").orEmpty().split(',').map { it.trim() }.filter { REASON_RE.matches(it) }.toSet(),
            )
            "conversation.remove" -> ChatEvent.ConversationRemove(conv)
            else -> null
        }
    }
}
