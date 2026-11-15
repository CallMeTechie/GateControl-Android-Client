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
 * they do not exist for a client. What crosses: text, replies, reactions, edits, deletions, "read" and "schreibt …" —
 * and with Messenger 1.15.0 (`features.bridge_media`, `bridge.supports`) pictures, videos, voice messages and files in
 * both directions and the pictures of contacts and groups. A file first is a line ("wird geladen …", or why it did not
 * come), then a normal message; a kind of message that does not cross is a line that says something arrived.
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
    /**
     * Messenger 1.15: the types the service takes per kind (`image`, `video`, `voice`, `file`); a kind mapped to null
     * takes any type. Null: the server names none (then only what [supports] says, without a check of the type).
     */
    val mediaTypes: Map<String, List<String>?>? = null,
)

/**
 * A file or kind of message that has not crossed ([kind] of [ChatBridgeChat.PLACEHOLDERS], the file's [name]).
 * Messenger 1.15: [state] `loading` (the server fetches it), `failed` with [reason] (why it will not come), null
 * (nothing to fetch — a location with [lat]/[lon], an older server); 1.17: `on_request` (a file of the older history,
 * fetched when the person asks for it).
 */
data class ChatBridgePlaceholder(
    val kind: String,
    val name: String?,
    val state: String? = null,
    val reason: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
) {
    val loading: Boolean get() = state == ChatBridgeChat.PH_LOADING
    val failed: Boolean get() = state == ChatBridgeChat.PH_FAILED

    /** Messenger 1.17: a file of the older history, fetched on "Laden" ([ChatBridgeChat.PH_ON_REQUEST]). */
    val onRequest: Boolean get() = state == ChatBridgeChat.PH_ON_REQUEST

    /** A location with its place (shown on the existing map view). */
    val geo: Boolean get() = lat != null && lon != null
}

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
    /** Messenger 1.15: the contact's picture (`sender.avatarMediaId`), once the server fetched it. */
    val senderAvatarMediaId: String? = null,
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

    /** Messenger 1.15 `features.bridge_media`: files in both directions, pictures of contacts and groups. */
    const val FEATURE_MEDIA = "bridge_media"

    /** Messenger 1.15 `features.bridge_new_chat`: a new chat by phone number (`POST bridges/:network/resolve | chats`). */
    const val FEATURE_NEW_CHAT = "bridge_new_chat"

    /**
     * Messenger 1.17 `features.bridge_history`: older messages over a bridge — the choice in „Verknüpfte Dienste“
     * (`bridges.history`), files of the history on request (placeholder `on_request`), the phone warning.
     */
    const val FEATURE_HISTORY = "bridge_history"

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
    const val PH_LOCATION = "location"
    val PLACEHOLDERS: Set<String> = setOf("image", "video", "audio", "voice", "file", "sticker", PH_LOCATION, PH_UNSUPPORTED)

    // placeholder.state (Messenger 1.15)
    const val PH_LOADING = "loading"
    const val PH_FAILED = "failed"

    /** Messenger 1.17: a file of the older history — fetched only when the person asks for it ("Laden"). */
    const val PH_ON_REQUEST = "on_request"

    /** Why a file did not come (`placeholder.reason`); another word reads like [PH_REASON_UNAVAILABLE]. */
    const val PH_REASON_INFECTED = "infected"
    const val PH_REASON_UNAVAILABLE = "unavailable"
    val PH_REASONS: Set<String> = setOf("too_large", "type", PH_REASON_INFECTED, "quota", "gone", PH_REASON_UNAVAILABLE)

    // bridge.supports / bridge.mediaTypes (Messenger 1.15)
    const val TAKES_IMAGE = "image"
    const val TAKES_VIDEO = "video"
    const val TAKES_VOICE = "voice"
    const val TAKES_FILE = "file"

    /** `system.event` of a note the bridge itself wrote into the chat. */
    const val EVENT_NOTICE = "bridge_notice"

    /** The reasons a message did not go out that have a sentence of their own; any other word reads like [REASON_ERROR]. */
    const val REASON_ERROR = "bridge_error"
    val REASONS: Set<String> = setOf(
        "not_running", "unreachable", "timeout", "network_error", "no_permission", "too_old", "unsupported", "not_handled",
        "bridge_unavailable", "chat_closed", REASON_ERROR,
    )

    /** The server's refusals around a bridge: never retried by themselves — there is no queue on the other side. */
    val REFUSALS: Set<String> = setOf("bridge_text_only", "bridge_unlinked", "bridge_off", "bridge_unreachable", "bridge_failed", "bridge_locked", MEDIA_TYPE)

    /** Messenger 1.15 `409 bridge_media_type` (`reason`: image | voice | video): the service does not take this format. */
    const val MEDIA_TYPE = "bridge_media_type"

    private val NETWORK_RE = Regex("^[a-z][a-z0-9_-]{0,31}$")
    private val REASON_RE = Regex("^[a-z][a-z0-9_]{0,39}$")
    private const val MAX_SUPPORTS = 32
    private const val MAX_TYPES = 16
    private val MIME_RE = Regex("^[a-z][a-z0-9-]{0,30}/[a-z0-9][a-z0-9.+_-]{0,99}$")

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
            mediaTypes = mediaTypes(o.obj("mediaTypes")),
        )
    }

    /** `mediaTypes: { image: ["image/jpeg", …], file: null }` — kinds with a list or null (any type); else none. */
    private fun mediaTypes(o: JsonObject?): Map<String, List<String>?>? {
        if (o == null) return null
        val out = LinkedHashMap<String, List<String>?>()
        for ((k, v) in o.entrySet()) {
            if (k !in MEDIA_KINDS) continue
            out[k] = when {
                v == null || v.isJsonNull -> null
                v.isJsonArray -> v.asJsonArray.mapNotNull { e -> e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.lowercase()?.takeIf { MIME_RE.matches(it) } }.take(MAX_TYPES)
                else -> continue
            }
        }
        return out
    }

    private val MEDIA_KINDS = setOf(TAKES_IMAGE, TAKES_VIDEO, TAKES_VOICE, TAKES_FILE)

    fun write(b: ChatConvBridge): JsonObject = JsonObject().apply {
        addProperty("network", b.network)
        addProperty("label", b.label)
        addProperty("state", b.state)
        addProperty("closed", b.closed)
        add("supports", JsonArray().apply { b.supports.forEach { add(it) } })
        b.mediaTypes?.let { mt ->
            add("mediaTypes", JsonObject().apply { mt.forEach { (k, v) -> if (v == null) add(k, JsonNull.INSTANCE) else add(k, JsonArray().apply { v.forEach { add(it) } }) } })
        }
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
            placeholder = ph?.let { placeholder(it) },
            senderAvatarMediaId = if (contactId != null) sender.str("avatarMediaId")?.let { ChatJson.mediaId(it) } else null,
        )
    }

    private fun placeholder(o: JsonObject): ChatBridgePlaceholder {
        val state = o.str("state")?.takeIf { it == PH_LOADING || it == PH_FAILED || it == PH_ON_REQUEST }
        val geo = o.obj("geo")
        val lat = geo?.get("lat")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble?.takeIf { it.isFinite() && it in -90.0..90.0 }
        val lon = geo?.get("lon")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble?.takeIf { it.isFinite() && it in -180.0..180.0 }
        return ChatBridgePlaceholder(
            kind = o.str("kind")?.takeIf { k -> k in PLACEHOLDERS } ?: PH_UNSUPPORTED,
            name = ChatJson.line(o.str("name"), 200),
            state = state,
            // an unknown reason still says that it did not come
            reason = if (state == PH_FAILED) o.str("reason")?.takeIf { it in PH_REASONS } ?: PH_REASON_UNAVAILABLE else null,
            lat = if (lat != null && lon != null) lat else null,
            lon = if (lat != null && lon != null) lon else null,
        )
    }

    fun write(b: ChatMessageBridge): JsonObject = JsonObject().apply {
        add("sender", b.contactId?.let { id ->
            JsonObject().apply {
                addProperty("contactId", id)
                if (b.senderName == null) add("name", JsonNull.INSTANCE) else addProperty("name", b.senderName)
                b.senderAvatarMediaId?.let { addProperty("avatarMediaId", it) }
            }
        } ?: JsonNull.INSTANCE)
        addProperty("remote", b.remote)
        if (b.state == null) add("state", JsonNull.INSTANCE) else addProperty("state", b.state)
        if (b.reason == null) add("reason", JsonNull.INSTANCE) else addProperty("reason", b.reason)
        add("placeholder", b.placeholder?.let { p ->
            JsonObject().apply {
                addProperty("kind", p.kind)
                if (p.name == null) add("name", JsonNull.INSTANCE) else addProperty("name", p.name)
                if (p.state == null) add("state", JsonNull.INSTANCE) else addProperty("state", p.state)
                if (p.reason == null) add("reason", JsonNull.INSTANCE) else addProperty("reason", p.reason)
                if (p.lat != null && p.lon != null) add("geo", JsonObject().apply { addProperty("lat", p.lat); addProperty("lon", p.lon) })
            }
        } ?: JsonNull.INSTANCE)
    }

    // ─── what the screens show ──────────────────────────────────────

    // ─── files (Messenger 1.15) ────────────────────────────────────

    /** The chat takes [what] (`image`, `video`, `voice`, `file`) — `bridge.supports`; a chat inside GateControl all. */
    fun takes(c: ChatConversation?, what: String): Boolean = c?.bridge?.let { what in it.supports } ?: true

    /** The paper clip: in a bridged chat only with files (pictures, videos or files), and then only for those. */
    fun attach(c: ChatConversation?): Boolean = c?.bridge == null || takes(c, TAKES_FILE) || takes(c, TAKES_IMAGE) || takes(c, TAKES_VIDEO)

    /** The kind towards the service of a file: a picture, a voice message, a video (a file of type video/…), a file. */
    fun outKind(kind: String, mime: String?): String = when {
        kind == ChatMessage.KIND_IMAGE -> TAKES_IMAGE
        kind == ChatMessage.KIND_VOICE -> TAKES_VOICE
        mime.orEmpty().lowercase().startsWith("video/") -> TAKES_VIDEO
        else -> TAKES_FILE
    }

    /**
     * May a file of [kind] and [mime] go into [c]? Inside GateControl always; over a bridge only a kind it takes, in a
     * type of `mediaTypes` (none named, or null for the kind: any). A video the service does not take is refused here
     * — the server would answer `409 bridge_media_type`.
     */
    fun mediaOk(c: ChatConversation?, kind: String, mime: String?): Boolean {
        val b = c?.bridge ?: return true
        val k = outKind(kind, mime)
        // a video goes as a file: a service that takes files but names no video takes it as a file
        if (k !in b.supports && !(k == TAKES_VIDEO && TAKES_FILE in b.supports && b.mediaTypes?.containsKey(TAKES_VIDEO) != true)) return false
        val list = b.mediaTypes?.get(k) ?: return true
        return mime.orEmpty().substringBefore(';').trim().lowercase() in list
    }

    /** The microphone: in a bridged chat only with `voice` and when the recording's type ([mime]) is one it takes. */
    fun voice(c: ChatConversation?, mime: String): Boolean = c?.bridge == null || mediaOk(c, ChatMessage.KIND_VOICE, mime)

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

    /**
     * An own text can be changed over there: not what was written in the service's own app, not a placeholder, not
     * the caption of a file (Messenger 1.15: `bridge_text_only`).
     */
    fun editable(m: ChatMessage): Boolean = m.bridge?.let { !it.remote && it.placeholder == null && !it.notOut && m.kind == ChatMessage.KIND_TEXT } ?: true

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
