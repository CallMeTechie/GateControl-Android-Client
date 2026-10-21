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
    /** Messenger 1.4 (`privacy2`): online now / last active — only when that person shares it. */
    val online: Boolean? = null,
    val lastSeenAt: String? = null,
    /** Messenger 1.4: the caller blocked this person (the peer of a direct chat). */
    val blocked: Boolean = false,
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
    /** Messenger 1.4: may pin messages. */
    val pin: Boolean = true,
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
    /** Messenger 1.4 (`msg_tools`): archived by the caller. */
    val archived: Boolean = false,
    /** Messenger 1.4 (`msg_tools`): pinned message ids (≤ 3, oldest first). */
    val pinned: List<Long> = emptyList(),
    /** Messenger 1.4 (`events`): the soonest upcoming event. */
    val nextEvent: ChatNextEvent? = null,
    /**
     * Messenger 1.6: shown under "Angeheftet" with the GateControl entry
     * (the family group the Familie plugin keeps).
     */
    val listPinned: Boolean = false,
    /**
     * Messenger 1.6: a notice channel (GateControl's notifications of one
     * source, e.g. "Schule", only for the caller) — read only, tagged
     * "Mitteilungen" in the list.
     */
    val notice: ChatNotice? = null,
) {
    val isGroup: Boolean get() = kind == KIND_GROUP

    /** A notice channel of a source (Messenger 1.6 `notice`). */
    val isNotice: Boolean get() = notice != null

    /** Newest activity for sorting: the last message's time, else the change time. */
    val activityAt: String get() = lastMessage?.createdAt ?: updatedAt ?: createdAt ?: ""

    companion object {
        const val KIND_DIRECT = "direct"
        const val KIND_GROUP = "group"
    }
}

/**
 * `notice` of a conversation (Messenger 1.6): the source whose
 * notifications the channel carries — `core` or `plugin:<id>` (null when
 * the server sent something else) — and its name ("Schule").
 */
data class ChatNotice(val source: String?, val label: String?) {
    companion object {
        /** `core` or `plugin:<id>`. */
        val SOURCE_RE = Regex("^(?:core|plugin:[a-z0-9]+(?:-[a-z0-9]+)*)$")
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

/**
 * `video` of a video file message (Messenger 1.3.0, `features: video_meta`):
 * length and picture size. The message stays `kind: "file"`.
 */
data class ChatVideo(val durationMs: Long? = null, val width: Int? = null, val height: Int? = null)

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
    /** Messenger 1.4: a label the event names (place, action, option). */
    val label: String? = null,
    /** Messenger 1.4: the message the event belongs to (a poll, an arrival). */
    val messageId: Long? = null,
    /** Messenger 1.4: `rsvp` answer (yes | maybe | no) and guests. */
    val answer: String? = null,
    val guests: Int? = null,
    /** Messenger 1.4: `action_run` as another person (a grant), `action_denied` the requester. */
    val otherUserId: Long? = null,
    /** Messenger 1.5 `grant_off`: whether switching off at the end worked. */
    val ok: Boolean? = null,
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
    /** Video files only (servers with `video_meta`). */
    val video: ChatVideo? = null,
    // Messenger 1.4 kinds and fields.
    val poll: ChatPoll? = null,
    val event: ChatEventInfo? = null,
    val arrival: ChatArrival? = null,
    val action: ChatActionCard? = null,
    val card: ChatPluginCard? = null,
    val replyStory: ChatReplyStory? = null,
) {
    val isSystem: Boolean get() = kind == KIND_SYSTEM

    /** A kind this app does not know (a newer messenger): shown as "needs a newer app". */
    val isUnknownKind: Boolean get() = kind !in KNOWN_KINDS

    /** A file message carrying a video (`kind` stays `file`). */
    val isVideo: Boolean get() = kind == KIND_FILE && media?.mime?.startsWith("video/") == true

    /** Length of a video: `video.durationMs`, else what `meta` says. */
    val videoDurationMs: Long? get() = video?.durationMs ?: meta?.durationMs

    companion object {
        const val KIND_TEXT = "text"
        const val KIND_IMAGE = "image"
        const val KIND_VOICE = "voice"
        const val KIND_FILE = "file"
        const val KIND_LOCATION = "location"
        const val KIND_SYSTEM = "system"
        const val KIND_POLL = "poll"
        const val KIND_EVENT = "event"
        const val KIND_ARRIVAL = "arrival"
        const val KIND_ACTION = "action"
        const val KIND_CARD = "card"

        val KNOWN_KINDS: Set<String> = setOf(
            KIND_TEXT, KIND_IMAGE, KIND_VOICE, KIND_FILE, KIND_LOCATION, KIND_SYSTEM,
            KIND_POLL, KIND_EVENT, KIND_ARRIVAL, KIND_ACTION, KIND_CARD,
        )

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
    /** `around=` (Messenger 1.1.0): older / newer messages exist; null when the server did not say. */
    val hasMoreBefore: Boolean? = null,
    val hasMoreAfter: Boolean? = null,
    /** The server's time of the answer (1.1.0): the next `changedSince`. */
    val serverTime: String? = null,
) {
    /** The server answered `around=` (servers before 1.1.0 ignore it and send the newest page). */
    val isAround: Boolean get() = hasMoreBefore != null || hasMoreAfter != null
}

/**
 * Messages of a conversation that may be missing in the cache: ids
 * strictly between [after] and [before] (both cached). Left by a jump to a
 * message far back (`around=`), filled with `after=`.
 */
data class ChatGap(val after: Long, val before: Long)

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
    /** Further capabilities the server names (`video_meta`, `file_thumbs`, …). */
    val tokens: Set<String> = emptySet(),
) {
    /** File messages may carry `video { durationMs, width, height }` (Messenger 1.3.0). */
    val videoMeta: Boolean get() = VIDEO_META in tokens

    /** File messages may carry a preview picture `thumbMediaId` (Messenger 1.3.0). */
    val fileThumbs: Boolean get() = FILE_THUMBS in tokens

    companion object {
        const val VIDEO_META = "video_meta"
        const val FILE_THUMBS = "file_thumbs"
    }
}

data class ChatLimits(
    val maxBytes: Long? = null,
    val types: List<String> = emptyList(),
    val quotaFull: Boolean = false,
    val editWindowS: Int = 900,
    val maxVoiceS: Int = 120,
    val textMax: Int = 4000,
    val thumbMaxBytes: Int = 65_536,
    /** Longer side of a preview picture (Messenger 1.3.0 `limits.thumbMaxSide`). */
    val thumbMaxSide: Int = 640,
    val imageMaxSide: Int = 2048,
    val groupMax: Int = 256,
    val titleMax: Int = 80,
    val pageMax: Int = 100,
    val typingS: Int = 6,
    val typingEveryS: Int = 3,
    val activeS: Int = 30,
    val disappearingS: List<Int> = listOf(0, 86_400, 604_800, 2_592_000),
    /** Messenger 1.4: the caller's storage on the server, when sent. */
    val usage: ChatUsage? = null,
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
    /** Messenger 1.4: privacy, notify and chat preferences. */
    val prefs14: ChatPrefs14 = ChatPrefs14(),
    /** Messenger 1.4 (`status`): the caller's own status, when `me` carries it. */
    val status: ChatStatus? = null,
    /** Messenger 1.4: what the Familie plugin offers (lists, calendar, places). */
    val familie: ChatFamilie = ChatFamilie(),
    /** Messenger C4: limits and defaults of door requests and shared devices, the doorbell. */
    val home: ChatHomeInfo = ChatHomeInfo(),
)

/** `GET conversations/:id/search`. */
data class ChatSearchResult(val results: List<ChatMessage>, val truncated: Boolean)

/** `GET conversations/:id/media`: one page (newest first) and the counts per kind (null: not sent). */
data class ChatMediaPage(
    val items: List<ChatMessage>,
    val images: Int?,
    val voice: Int?,
    val files: Int?,
    val hasMore: Boolean,
    /** Servers with `video_meta`: videos counted apart from the files. */
    val videos: Int? = null,
)
