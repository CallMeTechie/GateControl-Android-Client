package com.gatecontrol.android.chat

import android.content.Context
import androidx.core.content.edit
import com.gatecontrol.android.chat.ChatJson.arr
import com.gatecontrol.android.chat.ChatJson.flag
import com.gatecontrol.android.chat.ChatJson.id
import com.gatecontrol.android.chat.ChatJson.obj
import com.gatecontrol.android.chat.ChatJson.str
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Chats over a bridge (Messenger 1.14.0, README "Chats over a bridge"): what a linked account receives at another
 * messenger (first WhatsApp) shows up as chats of that one person. Such a chat has one member — the person — and no
 * `peer`; the people on the other side are contacts, never accounts.
 *
 * The app tells the server that it knows such chats with `?bridged=1` on every call ([ChatApi]); without that mark
 * they do not exist for a client. What crosses for now: text, replies, reactions, edits, deletions, "read" and
 * "schreibt …" — a file or another kind of message is a line that says something arrived.
 */

/**
 * `bridge` of a conversation: the service ([network], shown as [label]), where the link stands ([state]) and whether
 * the account is still in this chat over there ([closed]).
 */
data class ChatConvBridge(
    val network: String,
    val label: String,
    val state: String,
    val closed: Boolean = false,
    val supports: Set<String> = emptySet(),
)

/** A file or kind of message that does not cross yet ([kind] of [ChatBridgeChat.PLACEHOLDERS], the file's [name]). */
data class ChatBridgePlaceholder(val kind: String, val name: String?)

/**
 * `bridge` of a message in a bridged chat. A contact's message names the contact ([contactId], [senderName] — null
 * when the service knows no name); an own one says what became of it over there ([state], with [reason] when it
 * failed). [remote]: written in the service's own app, not here.
 */
data class ChatMessageBridge(
    val contactId: Long? = null,
    val senderName: String? = null,
    val remote: Boolean = false,
    val state: String? = null,
    val reason: String? = null,
    val placeholder: ChatBridgePlaceholder? = null,
) {
    /** A contact wrote it (not the person, not the bridge itself). */
    val fromContact: Boolean get() = contactId != null

    val failed: Boolean get() = state == ChatBridgeChat.STATE_FAILED

    /** Not at the other side (yet): nothing can be answered, changed or reacted to over there. */
    val notOut: Boolean get() = state == ChatBridgeChat.STATE_FAILED || state == ChatBridgeChat.STATE_PENDING
}

/** Pure parts of the bridged chats: the server's words, parsing, what the screens show and lock. Unit-tested. */
object ChatBridgeChat {
    /** `features.bridge_chats` of `GET me`: this person has a chat over a bridge. */
    const val FEATURE = "bridge_chats"

    /** The mark of a client that knows bridged chats (`?bridged=1`). */
    const val QUERY = "bridged"

    // conversation.bridge.state
    const val OK = "ok"
    const val UNREACHABLE = "unreachable"
    const val LOGGED_OUT = "logged_out"
    const val UNLINKED = "unlinked"
    const val LOCKED = "locked"
    const val OFF = "off"
    val STATES: Set<String> = setOf(OK, UNREACHABLE, LOGGED_OUT, UNLINKED, LOCKED, OFF)

    // message.bridge.state
    const val STATE_PENDING = "pending"
    const val STATE_SENT = "sent"
    const val STATE_DELIVERED = "delivered"
    const val STATE_READ = "read"
    const val STATE_FAILED = "failed"
    private val MESSAGE_STATES = setOf(STATE_PENDING, STATE_SENT, STATE_DELIVERED, STATE_READ, STATE_FAILED)

    const val PH_UNSUPPORTED = "unsupported"
    val PLACEHOLDERS: Set<String> = setOf("image", "video", "audio", "voice", "file", "sticker", "location", PH_UNSUPPORTED)

    /** `system.event` of a note the bridge itself wrote into the chat. */
    const val EVENT_NOTICE = "bridge_notice"

    /** The reasons a message did not go out that have a sentence of their own; any other word reads like [REASON_ERROR]. */
    const val REASON_ERROR = "bridge_error"
    val REASONS: Set<String> = setOf(
        "not_running", "unreachable", "timeout", "network_error", "no_permission", "too_old", "unsupported", "not_handled",
        "bridge_unavailable", "chat_closed", REASON_ERROR,
    )

    /** The server's refusals around a bridge: never retried by themselves — there is no queue on the other side. */
    val REFUSALS: Set<String> = setOf("bridge_text_only", "bridge_unlinked", "bridge_off", "bridge_unreachable", "bridge_failed", "bridge_locked")

    private val NETWORK_RE = Regex("^[a-z][a-z0-9_-]{0,31}$")
    private val REASON_RE = Regex("^[a-z][a-z0-9_]{0,39}$")
    private const val MAX_SUPPORTS = 32

    // ─── parsing ────────────────────────────────────────────────────

    /** `bridge` of a conversation; anything but an object with a service's id → none (a normal chat). */
    fun conversation(o: JsonObject?): ChatConvBridge? {
        if (o == null) return null
        val network = o.str("network")?.takeIf { NETWORK_RE.matches(it) } ?: return null
        return ChatConvBridge(
            network = network,
            label = ChatJson.line(o.str("label"), 40) ?: network,
            // a state this app does not know: nothing can be sent, the history stays readable
            state = o.str("state")?.takeIf { it in STATES } ?: OFF,
            closed = o.flag("closed") == true,
            supports = o.arr("supports")?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.let { ChatJson.token(it) } }
                ?.take(MAX_SUPPORTS)?.toSet().orEmpty(),
        )
    }

    fun write(b: ChatConvBridge): JsonObject = JsonObject().apply {
        addProperty("network", b.network)
        addProperty("label", b.label)
        addProperty("state", b.state)
        addProperty("closed", b.closed)
        add("supports", JsonArray().apply { b.supports.forEach { add(it) } })
    }

    /** `bridge` of a message; null outside a bridged chat. */
    fun message(o: JsonObject?): ChatMessageBridge? {
        if (o == null) return null
        val sender = o.obj("sender")
        val contactId = sender?.id("contactId")
        val ph = o.obj("placeholder")
        return ChatMessageBridge(
            contactId = contactId,
            senderName = if (contactId != null) ChatJson.line(sender.str("name"), 120) else null,
            remote = o.flag("remote") == true,
            state = o.str("state")?.takeIf { it in MESSAGE_STATES },
            reason = o.str("reason")?.takeIf { REASON_RE.matches(it) },
            // a kind this app does not know yet still says that something arrived
            placeholder = ph?.let { ChatBridgePlaceholder(it.str("kind")?.takeIf { k -> k in PLACEHOLDERS } ?: PH_UNSUPPORTED, ChatJson.line(it.str("name"), 200)) },
        )
    }

    fun write(b: ChatMessageBridge): JsonObject = JsonObject().apply {
        add("sender", b.contactId?.let { id ->
            JsonObject().apply {
                addProperty("contactId", id)
                if (b.senderName == null) add("name", JsonNull.INSTANCE) else addProperty("name", b.senderName)
            }
        } ?: JsonNull.INSTANCE)
        addProperty("remote", b.remote)
        if (b.state == null) add("state", JsonNull.INSTANCE) else addProperty("state", b.state)
        if (b.reason == null) add("reason", JsonNull.INSTANCE) else addProperty("reason", b.reason)
        add("placeholder", b.placeholder?.let { p ->
            JsonObject().apply {
                addProperty("kind", p.kind)
                if (p.name == null) add("name", JsonNull.INSTANCE) else addProperty("name", p.name)
            }
        } ?: JsonNull.INSTANCE)
    }

    // ─── what the screens show ──────────────────────────────────────

    /** The person has a chat over a bridge: the server says so, or the list holds one. */
    fun any(features: ChatFeatures?, conversations: List<ChatConversation>): Boolean =
        features?.tokens?.contains(FEATURE) == true || conversations.any { it.bridge != null }

    /** The services of the listed chats (id → name), in the order of their first chat. */
    fun networks(conversations: List<ChatConversation>): List<Pair<String, String>> =
        conversations.mapNotNull { it.bridge }.distinctBy { it.network }.map { it.network to it.label }

    /** The list's filter per service: [net] null = all, [NET_GC] = only chats inside GateControl, else that service. */
    const val NET_GC = "gc"

    fun inNet(c: ChatConversation, net: String?): Boolean = when (net) {
        null -> true
        NET_GC -> c.bridge == null
        else -> c.bridge?.network == net
    }

    /** The mark at a profile picture: "WA" for WhatsApp, else the first two letters of the service's name. */
    fun mark(b: ChatConvBridge): String = if (b.network == ChatBridges.WHATSAPP) "WA" else b.label.take(2).uppercase()

    /**
     * What the bar above the input says about the link: [closed] before everything else, then the state — null while
     * all is well.
     */
    enum class Bar { Closed, Unreachable, LoggedOut, Unlinked, Locked, Off }

    fun bar(b: ChatConvBridge): Bar? = when {
        b.closed -> Bar.Closed
        b.state == UNREACHABLE -> Bar.Unreachable
        b.state == LOGGED_OUT -> Bar.LoggedOut
        b.state == UNLINKED -> Bar.Unlinked
        b.state == LOCKED -> Bar.Locked
        b.state == OFF -> Bar.Off
        else -> null
    }

    /**
     * Whether [m] of bridged chat [c] can be answered, changed or reacted to: only while sending works at all and the
     * message is at the other side (an own one that failed or still waits is not).
     */
    fun actsOut(c: ChatConversation, m: ChatMessage): Boolean {
        if (c.bridge == null) return true
        return c.can.send && !(m.mine && m.bridge?.notOut == true)
    }

    /** An own text can be changed over there: not what was written in the service's own app, not a placeholder. */
    fun editable(m: ChatMessage): Boolean = m.bridge?.let { !it.remote && it.placeholder == null && !it.notOut } ?: true

    /** The word of a failed message's reason that has a sentence ([REASON_ERROR] for everything else). */
    fun reason(reason: String?): String = reason?.takeIf { it in REASONS } ?: REASON_ERROR

    /** The key of a message's sender for grouping rows: an account, a contact, or nobody ("GateControl" / the bridge). */
    fun senderKey(m: ChatMessage): String = when {
        m.senderId != null -> "u:${m.senderId}"
        m.bridge?.contactId != null -> "c:${m.bridge.contactId}"
        else -> "-"
    }
}

/** Which services' "Chat über …" note the person has read on this device (shown once, then the thin strip). */
@Singleton
class ChatBridgeHints @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _seen = MutableStateFlow(prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty().toSet())
    val seen: StateFlow<Set<String>> = _seen.asStateFlow()

    fun setSeen(network: String, seen: Boolean) {
        val next = if (seen) _seen.value + network else _seen.value - network
        prefs.edit { putStringSet(KEY_SEEN, next) }
        _seen.value = next
    }

    private companion object {
        const val PREFS = "chat_bridge_hints"
        const val KEY_SEEN = "seen"
    }
}
