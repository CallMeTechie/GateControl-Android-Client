package com.gatecontrol.android.chat

/** Plugin id of the messenger (README "App-API"). */
const val MESSENGER_PLUGIN = "gatecontrol-messenger"

/** App route of the messenger's chat notifications (`data.route`). */
const val MESSENGER_ROUTE = "plg-gatecontrol-messenger"

/** Topic of the messenger's chat notifications. */
const val MESSENGER_TOPIC = "plugin:gatecontrol-messenger:message"

/**
 * A person of a conversation or of `people`. [name] null with [gone] =
 * the account no longer exists ("Gelöschtes Konto"). [role] only for
 * conversation members (`admin` | `member`).
 */
data class ChatPerson(
    val userId: Long,
    val name: String?,
    val avatarUrl: String?,
    val initials: String?,
    val colorHex: String?,
    val gone: Boolean = false,
    val role: String? = null,
) {
    val isAdmin: Boolean get() = role == ROLE_ADMIN

    companion object {
        const val ROLE_ADMIN = "admin"
        const val ROLE_MEMBER = "member"

        /** Up to two initials of [name] (the server's when it sent some). */
        fun initialsOf(name: String?): String {
            val parts = name.orEmpty().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return when {
                parts.isEmpty() -> "?"
                parts.size == 1 -> parts[0].take(2).uppercase()
                else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
            }
        }
    }
}

/** The caller's own membership (`me` of a conversation). */
data class ChatMembership(
    val role: String?,
    val mutedUntil: String?,
    val notify: String,
    val lastReadId: Long?,
    val lastDeliveredId: Long?,
)

/** What the caller may do in a conversation (`can`). */
data class ChatCan(
    val send: Boolean,
    val editInfo: Boolean,
    val manageMembers: Boolean,
    val leave: Boolean,
    val setDisappearing: Boolean,
)

data class ChatReceipt(val userId: Long, val lastReadId: Long?, val lastDeliveredId: Long?)

/** One conversation as the server describes it to the caller. */
data class ChatConversation(
    val id: Long,
    val kind: String,
    /** Group title; direct: the other person's name (null when gone). */
    val title: String?,
    val peer: ChatPerson?,
    val avatarUrl: String?,
    val avatarMediaId: String?,
    val managedBy: String?,
    val readonly: Boolean,
    val disappearingS: Int,
    val createdAt: String?,
    val updatedAt: String?,
    val lastMessageId: Long?,
    val members: List<ChatPerson>,
    val me: ChatMembership,
    val muted: Boolean,
    val unread: Int,
    val lastMessage: ChatMessage?,
    val typing: List<Long>,
    val readUpTo: Long?,
    val deliveredUpTo: Long?,
    val receipts: List<ChatReceipt>,
    val can: ChatCan,
) {
    val isGroup: Boolean get() = kind == KIND_GROUP

    /** Newest activity for sorting: the last message's time, else the change time. */
    val activityAt: String get() = lastMessage?.createdAt ?: updatedAt ?: createdAt ?: ""

    companion object {
        const val KIND_DIRECT = "direct"
        const val KIND_GROUP = "group"
    }
}

/** File of a message; [thumbId] a small preview picture (pictures). */
data class ChatMedia(val id: String, val mime: String?, val bytes: Long?, val thumbId: String?)

data class ChatMeta(
    val durationMs: Long? = null,
    val waveform: List<Int> = emptyList(),
    val w: Int? = null,
    val h: Int? = null,
    val fileName: String? = null,
)

data class ChatLocation(val lat: Double, val lon: Double, val label: String?, val accuracy: Int? = null)

/** The quoted message of a reply (text ≤ 200). */
data class ChatReplyRef(
    val id: Long,
    val senderId: Long?,
    val kind: String?,
    val text: String?,
    val deleted: Boolean,
    val missing: Boolean,
)

data class ChatReaction(val emoji: String, val count: Int, val userIds: List<Long>)

/** `system` of a system message: [event] plus what it names. */
data class ChatSystem(
    val event: String,
    val by: Long?,
    val title: String? = null,
    val userIds: List<Long> = emptyList(),
    val userId: Long? = null,
    val seconds: Int? = null,
)

/** One message. Ids are server ids; [clientId] the sender's idempotency key. */
data class ChatMessage(
    val id: Long,
    val conversationId: Long,
    val senderId: Long?,
    val clientId: String?,
    val kind: String,
    val via: String?,
    val mine: Boolean,
    val createdAt: String,
    val editedAt: String?,
    val deletedAt: String?,
    val expiresAt: String?,
    val deleted: Boolean,
    val undecryptable: Boolean,
    val text: String?,
    val replyTo: ChatReplyRef?,
    val media: ChatMedia?,
    val meta: ChatMeta?,
    val location: ChatLocation?,
    val system: ChatSystem?,
    val reactions: List<ChatReaction>,
    /** Own messages only: sent | delivered | read. */
    val status: String?,
) {
    val isSystem: Boolean get() = kind == KIND_SYSTEM

    companion object {
        const val KIND_TEXT = "text"
        const val KIND_IMAGE = "image"
        const val KIND_VOICE = "voice"
        const val KIND_FILE = "file"
        const val KIND_LOCATION = "location"
        const val KIND_SYSTEM = "system"

        const val STATUS_SENT = "sent"
        const val STATUS_DELIVERED = "delivered"
        const val STATUS_READ = "read"
    }
}

/** A page of `GET conversations/:id/messages`. */
data class ChatMessagePage(
    val messages: List<ChatMessage>,
    val hasMore: Boolean,
    val readUpTo: Long?,
    val deliveredUpTo: Long?,
    val receipts: List<ChatReceipt>,
)

/** `GET conversations` (with or without `since`). */
data class ChatConversationList(
    val conversations: List<ChatConversation>,
    val removed: List<Long>,
    val serverTime: String?,
)

data class ChatPrefs(val readReceipts: Boolean = true, val typing: Boolean = true, val preview: Boolean = true)

data class ChatFeatures(
    val direct: Boolean = true,
    val groups: Boolean = true,
    val voice: Boolean = true,
    val files: Boolean = true,
    val location: Boolean = true,
    val reactions: Boolean = true,
    val edit: Boolean = true,
    val search: Boolean = true,
    val disappearing: Boolean = false,
)

data class ChatLimits(
    val maxBytes: Long? = null,
    val types: List<String> = emptyList(),
    val quotaFull: Boolean = false,
    val editWindowS: Int = 900,
    val maxVoiceS: Int = 120,
    val textMax: Int = 4000,
    val thumbMaxBytes: Int = 65_536,
    val imageMaxSide: Int = 2048,
    val groupMax: Int = 256,
    val titleMax: Int = 80,
    val pageMax: Int = 100,
    val typingS: Int = 6,
    val typingEveryS: Int = 3,
    val activeS: Int = 30,
    val disappearingS: List<Int> = listOf(0, 86_400, 604_800, 2_592_000),
)

/** `GET me`: who the caller is and what the server allows. */
data class ChatProfile(
    val user: ChatPerson,
    val prefs: ChatPrefs,
    /** null: the server did not say. */
    val licensed: Boolean?,
    val features: ChatFeatures,
    val limits: ChatLimits,
    val serverTime: String?,
)

/** `GET conversations/:id/search`. */
data class ChatSearchResult(val results: List<ChatMessage>, val truncated: Boolean)
