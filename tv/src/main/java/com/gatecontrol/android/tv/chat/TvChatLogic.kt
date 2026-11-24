package com.gatecontrol.android.tv.chat

import com.gatecontrol.android.chat.ChatBridgeChat
import com.gatecontrol.android.chat.ChatBridges
import com.gatecontrol.android.chat.ChatConversation
import com.gatecontrol.android.chat.ChatMessage
import com.gatecontrol.android.chat.ChatResult
import java.util.UUID

/** What a message shows on the TV: text, a photo's preview, or a line for what the TV does not play or show. */
enum class TvChatShow { TEXT, PHOTO, VOICE, VIDEO, FILE, LOCATION, OTHER, SYSTEM, DELETED }

/** Why nothing can be sent into a chat from the TV (the line above the answer instead of „Antworten“). */
enum class TvChatBlock {
    /** A channel, or a group where only the admins write (`can.sendReason`). */
    READ_ONLY,

    /** The account is no longer in this chat at the service (`bridge.closed`). */
    CLOSED,

    /** The bridge is not „ok“ (unreachable, logged out, off …): the server takes nothing, there is no queue. */
    BRIDGE_DOWN,

    /** `can.send` false for another reason. */
    NO_RIGHT,
}

/** Why a send did not go: the line under „Nicht gesendet“. */
enum class TvChatSendError {
    /** `409 bridge_text_only`: the bridge takes only text. */
    TEXT_ONLY,

    /** Another refusal around the bridge (unlinked, off, unreachable, failed, locked, read only). */
    BRIDGE,

    /** No answer: network or VPN. */
    OFFLINE,

    /** Anything else the server said no to. */
    FAILED,
}

/** A text the TV is sending or could not send; its [clientId] stays the same for „Erneut versuchen“. */
data class TvChatPending(
    val clientId: String,
    val conversationId: Long,
    val text: String,
    val replyTo: Long?,
    val createdAtMs: Long,
    /** null: on its way; else why it was not sent. */
    val error: TvChatSendError? = null,
) {
    val failed: Boolean get() = error != null
}

/** The status at an own message: what the server says, or the TV's own while it sends. */
enum class TvChatStatus { SENDING, SENT, DELIVERED, READ, FAILED }

/** A chat with news for the start row „Neue Nachrichten“: only who and how many, never the text. */
data class TvChatNews(val conversationId: Long, val name: String, val count: Int, val bridgeMark: Char?)

/**
 * The rules of „Nachrichten › Chats“ on GateControl TV (stage 3e). Pure (no Android), unit-tested. The bridge rules
 * themselves are the phone's ([ChatBridgeChat]); the TV adds that it sends only text and never from a bridge that is
 * not „ok“.
 */
object TvChatLogic {
    /** Messages per page of the history. */
    const val PAGE = 40

    /** The reactions of the TV (Frage: 👍 ❤️ 😂). */
    val REACTIONS: List<String> = listOf("👍", "❤️", "😂")

    /** At most this many chats in the start row. */
    const val NEWS_MAX = 8

    // ─── which chats the TV shows ──────────────────────────────────

    /** A group of the family (the one the Familie plugin keeps, pinned to the list): shown by default. */
    fun familyGroup(c: ChatConversation): Boolean =
        c.isGroup && !c.isNotice && (c.listPinned || c.managedBy?.contains(FAMILIE) == true)

    /**
     * The chats the TV shows: the chosen ones ([chosen], Einstellungen › Nachrichten) or, before anything was chosen
     * (null), only the family's groups (Frage 14 a). Archived chats and notice channels never.
     */
    fun visible(all: List<ChatConversation>, chosen: Set<Long>?): List<ChatConversation> =
        all.filter { c -> !c.archived && !c.isNotice && (if (chosen == null) familyGroup(c) else c.id in chosen) }
            .sortedByDescending { it.activityAt }

    /** What the settings' list starts from: the chosen ids or, before a choice, the family's groups. */
    fun chosenOrDefault(all: List<ChatConversation>, chosen: Set<Long>?): Set<Long> =
        chosen ?: all.filter(::familyGroup).map { it.id }.toSet()

    /** The chats the settings offer: everything but archived chats and notice channels, the newest first. */
    fun choosable(all: List<ChatConversation>): List<ChatConversation> =
        all.filter { !it.archived && !it.isNotice }.sortedByDescending { it.activityAt }

    /** [chosen] with [id] switched to [on] (the first switch starts from the default). */
    fun choose(all: List<ChatConversation>, chosen: Set<Long>?, id: Long, on: Boolean): Set<Long> {
        val base = chosenOrDefault(all, chosen)
        return if (on) base + id else base - id
    }

    // ─── names and marks ───────────────────────────────────────────

    /** The chat's name: its title, else the other person's name; [fallback] when the server names nobody. */
    fun title(c: ChatConversation, fallback: String): String =
        c.title?.takeIf { it.isNotBlank() } ?: c.peer?.name?.takeIf { it.isNotBlank() } ?: fallback

    /** The small mark of a chat over a bridge: W (WhatsApp), S (Signal), T (Telegram), else the first letter. */
    fun bridgeMark(network: String?): Char? = when (network) {
        null -> null
        ChatBridges.WHATSAPP -> 'W'
        ChatBridges.SIGNAL -> 'S'
        ChatBridges.TELEGRAM -> 'T'
        else -> network.firstOrNull { it.isLetter() }?.uppercaseChar()
    }

    /** Who wrote [m] in [c]: the bridge's contact, a member, or null (the person themselves, a system line). */
    fun senderName(c: ChatConversation?, m: ChatMessage): String? {
        if (m.mine || m.isSystem) return null
        m.bridge?.senderName?.let { return it }
        val id = m.senderId ?: return null
        return c?.members?.firstOrNull { it.userId == id }?.name ?: c?.peer?.takeIf { it.userId == id }?.name
    }

    // ─── what a message shows ──────────────────────────────────────

    fun show(m: ChatMessage): TvChatShow {
        if (m.deleted || m.undecryptable) return TvChatShow.DELETED
        if (m.isSystem) return TvChatShow.SYSTEM
        m.bridge?.placeholder?.let { p ->
            return when (p.kind) {
                // a picture that has not crossed (yet): a line, no preview
                "image", "sticker" -> TvChatShow.OTHER
                "video" -> TvChatShow.VIDEO
                "audio", "voice" -> TvChatShow.VOICE
                "file" -> TvChatShow.FILE
                ChatBridgeChat.PH_LOCATION -> TvChatShow.LOCATION
                else -> TvChatShow.OTHER
            }
        }
        return when {
            m.kind == ChatMessage.KIND_TEXT -> TvChatShow.TEXT
            m.kind == ChatMessage.KIND_IMAGE -> if (m.media?.blocked == false) TvChatShow.PHOTO else TvChatShow.OTHER
            m.kind == ChatMessage.KIND_VOICE -> TvChatShow.VOICE
            m.isVideo -> TvChatShow.VIDEO
            m.kind == ChatMessage.KIND_FILE -> TvChatShow.FILE
            m.kind == ChatMessage.KIND_LOCATION -> TvChatShow.LOCATION
            else -> TvChatShow.OTHER
        }
    }

    /** The photos of a history in order (for the viewer: ◀ ▶ goes through them). */
    fun photos(messages: List<ChatMessage>): List<ChatMessage> =
        messages.filter { show(it) == TvChatShow.PHOTO && it.media != null }

    // ─── sending ───────────────────────────────────────────────────

    /**
     * Why nothing can be sent into [c] (null: it can). Over a bridge only while it is „ok“ and the chat not closed
     * (`can.send` already says so on newer servers; the TV checks the state itself as well).
     */
    fun block(c: ChatConversation): TvChatBlock? {
        val b = c.bridge
        if (ChatBridgeChat.readOnly(c) != null || c.readonly || b?.readOnly == true) return TvChatBlock.READ_ONLY
        if (b != null && b.closed) return TvChatBlock.CLOSED
        if (b != null && b.state != ChatBridgeChat.OK) return TvChatBlock.BRIDGE_DOWN
        if (!c.can.send) return TvChatBlock.NO_RIGHT
        return null
    }

    /** OK on a message offers „Antworten“: sending works and the message is at the other side. */
    fun canReply(c: ChatConversation, m: ChatMessage): Boolean =
        block(c) == null && m.id > 0 && !m.isSystem && !m.deleted && ChatBridgeChat.actsOut(c, m)

    /** A reaction is offered: the phone's rule ([ChatBridgeChat.reacts]), not on system lines or deleted messages. */
    fun canReact(c: ChatConversation, m: ChatMessage): Boolean =
        m.id > 0 && !m.isSystem && !m.deleted && c.can.react && ChatBridgeChat.reacts(c, m) &&
            (c.bridge == null || block(c) == null || ChatBridgeChat.readOnly(c) != null)

    /** The reaction the person gave [m] already (OK on it again takes it back). */
    fun ownReaction(m: ChatMessage, myId: Long?): String? =
        myId?.let { id -> m.reactions.firstOrNull { id in it.userIds }?.emoji }

    /** A new key for one message; „Erneut versuchen“ keeps it, so the server never stores it twice. */
    fun newClientId(): String = "tv" + UUID.randomUUID().toString().replace("-", "")

    /** The text the TV sends: trimmed, at most the messenger's length. */
    fun text(raw: String): String? = raw.trim().take(TEXT_MAX).takeIf { it.isNotEmpty() }

    fun sendError(r: ChatResult<*>): TvChatSendError = when (r) {
        is ChatResult.Ok -> TvChatSendError.FAILED
        ChatResult.Offline, ChatResult.NotConfigured -> TvChatSendError.OFFLINE
        is ChatResult.Failed -> when {
            r.code == TEXT_ONLY -> TvChatSendError.TEXT_ONLY
            r.code in ChatBridgeChat.REFUSALS -> TvChatSendError.BRIDGE
            else -> TvChatSendError.FAILED
        }
    }

    /** The status at an own stored message (a bridged one that failed reads failed). */
    fun status(m: ChatMessage, readUpTo: Long?, deliveredUpTo: Long?): TvChatStatus? {
        if (!m.mine || m.isSystem) return null
        if (m.status == ChatMessage.STATUS_FAILED || m.bridge?.failed == true) return TvChatStatus.FAILED
        if (m.status == ChatMessage.STATUS_SENDING || m.bridge?.state == ChatBridgeChat.STATE_PENDING) return TvChatStatus.SENDING
        return when {
            m.status == ChatMessage.STATUS_READ || (readUpTo != null && m.id <= readUpTo) -> TvChatStatus.READ
            m.status == ChatMessage.STATUS_DELIVERED || (deliveredUpTo != null && m.id <= deliveredUpTo) -> TvChatStatus.DELIVERED
            else -> TvChatStatus.SENT
        }
    }

    // ─── the history ───────────────────────────────────────────────

    /** [old] and [new] messages by id, the newer copy wins, oldest first; at most [max] (the newest). */
    fun merge(old: List<ChatMessage>, new: List<ChatMessage>, max: Int = MAX_KEPT): List<ChatMessage> {
        val byId = LinkedHashMap<Long, ChatMessage>()
        for (m in old) byId[m.id] = m
        for (m in new) byId[m.id] = m
        return byId.values.sortedBy { it.id }.takeLast(max)
    }

    /** The sends still shown in [conversationId]: not yet stored (a stored message with the same clientId ends one). */
    fun pendingIn(pending: List<TvChatPending>, stored: List<ChatMessage>, conversationId: Long): List<TvChatPending> {
        val ids = stored.mapNotNullTo(HashSet()) { it.clientId }
        return pending.filter { it.conversationId == conversationId && it.clientId !in ids }.sortedBy { it.createdAtMs }
    }

    // ─── news ──────────────────────────────────────────────────────

    /** Unread messages over [visible] chats (the number at the rail and on the start page). */
    fun unread(visible: List<ChatConversation>): Int = visible.sumOf { it.unread.coerceAtLeast(0) }

    /** The start row „Neue Nachrichten“: chats with unread messages, the newest first — only name and count. */
    fun news(visible: List<ChatConversation>, fallback: String): List<TvChatNews> =
        visible.filter { it.unread > 0 }
            .sortedByDescending { it.activityAt }
            .take(NEWS_MAX)
            .map { TvChatNews(it.id, title(it, fallback), it.unread, bridgeMark(it.bridge?.network)) }

    /** The rail's and the row's count as text: 1 … 99, then „99+“. */
    fun badge(count: Int): String? = when {
        count <= 0 -> null
        count > 99 -> "99+"
        else -> count.toString()
    }

    // ─── the hint over other apps (Frage 18 a) ────────────────────

    /**
     * „Neue Nachricht von …“ over other apps: switched on, a chat the TV shows, GateControl not in front (there the
     * rail's count says it), no ring at the door just now (the doorbell always comes first), not in the quiet hours and
     * not again for the same chat within [HINT_GAP_MS].
     */
    fun hintShows(
        settings: com.gatecontrol.android.tv.TvChatHintSettings,
        visible: Boolean,
        appInFront: Boolean,
        doorbellRecent: Boolean,
        minuteOfDay: Int,
        lastHintAt: Long?,
        now: Long,
    ): Boolean {
        if (!settings.on || !visible || appInFront || doorbellRecent) return false
        if (settings.quiet && com.gatecontrol.android.tv.doorbell.DoorbellRules.inQuietHours(minuteOfDay, settings.quietFrom, settings.quietTo)) return false
        return lastHintAt == null || now - lastHintAt >= HINT_GAP_MS
    }

    /** The name in the hint: who wrote (a group: the member), else the chat's name — never any text. */
    fun hintName(c: ChatConversation, messageId: Long, fallback: String): String {
        val m = c.lastMessage?.takeIf { it.id == messageId }
        return m?.let { senderName(c, it) } ?: title(c, fallback)
    }

    const val HINT_GAP_MS = 60_000L

    /** A ring at the door this recently keeps the hint away. */
    const val DOORBELL_FIRST_MS = 90_000L

    private const val FAMILIE = "familie"
    const val TEXT_ONLY = "bridge_text_only"

    /** The messenger's longest text. */
    const val TEXT_MAX = 4_000

    /** Messages kept per open chat in memory. */
    const val MAX_KEPT = 200
}
