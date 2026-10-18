package com.gatecontrol.android.chat

/*
 * Models of Messenger 1.4 (status, stories, polls, events, live arrival,
 * home actions, plugin cards, message tools, privacy). Every part is
 * switched on by a key of `GET me` → `features` ([ChatFeatures14]); the
 * parsers live in [ChatJson14].
 */

/** The Messenger 1.4 keys of `features` (an object of flags). */
object ChatFeatures14 {
    const val STATUS = "status"
    const val STORIES = "stories"
    const val POLLS = "polls"
    const val EVENTS = "events"
    const val ARRIVAL = "arrival"
    const val ACTIONS = "actions"
    const val MSG_TOOLS = "msg_tools"
    const val PRIVACY2 = "privacy2"

    val ALL: Set<String> = setOf(STATUS, STORIES, POLLS, EVENTS, ARRIVAL, ACTIONS, MSG_TOOLS, PRIVACY2)
}

val ChatFeatures.status: Boolean get() = ChatFeatures14.STATUS in tokens
val ChatFeatures.stories: Boolean get() = ChatFeatures14.STORIES in tokens
val ChatFeatures.polls: Boolean get() = ChatFeatures14.POLLS in tokens
val ChatFeatures.events: Boolean get() = ChatFeatures14.EVENTS in tokens
val ChatFeatures.arrival: Boolean get() = ChatFeatures14.ARRIVAL in tokens
val ChatFeatures.actions: Boolean get() = ChatFeatures14.ACTIONS in tokens
val ChatFeatures.msgTools: Boolean get() = ChatFeatures14.MSG_TOOLS in tokens
val ChatFeatures.privacy2: Boolean get() = ChatFeatures14.PRIVACY2 in tokens

// ─── status and stories ─────────────────────────────────────────────

/** A person's status line (`PUT status`). */
data class ChatStatus(
    val text: String?,
    /** none | homeoffice | away | dnd | auto_place. */
    val preset: String,
    val until: String?,
    /** all | family | custom. */
    val visibility: String,
    val userIds: List<Long> = emptyList(),
    /** `away`: expected arrival and where to. */
    val eta: String? = null,
    val destLabel: String? = null,
    /** `auto_place`: the Familie place ("Zuhause", "Schule"), with since when. */
    val place: String? = null,
    val since: String? = null,
    /** The own status only: the typed text (`text` may show the place instead). */
    val ownText: String? = null,
) {
    companion object {
        const val PRESET_NONE = "none"
        const val PRESET_HOMEOFFICE = "homeoffice"
        const val PRESET_AWAY = "away"
        const val PRESET_DND = "dnd"
        const val PRESET_AUTO_PLACE = "auto_place"
        val PRESETS = listOf(PRESET_NONE, PRESET_HOMEOFFICE, PRESET_AWAY, PRESET_DND, PRESET_AUTO_PLACE)

        const val VIS_ALL = "all"
        const val VIS_FAMILY = "family"
        const val VIS_CUSTOM = "custom"
        val VISIBILITIES = listOf(VIS_ALL, VIS_FAMILY, VIS_CUSTOM)

        const val TEXT_MAX = 60
    }
}

/** One story (24 h). [id] is path-safe (`[A-Za-z0-9_-]`). */
data class ChatStory(
    val id: String,
    /** text | image | video. */
    val kind: String,
    val text: String?,
    val bg: String?,
    /** sans | serif | mono. */
    val font: String?,
    val mediaId: String?,
    val thumbMediaId: String?,
    val video: ChatVideo?,
    val createdAt: String?,
    val expiresAt: String?,
    val seen: Boolean,
    val place: String? = null,
    /** Own stories: how many have seen it. */
    val viewerCount: Int? = null,
    val visibility: String? = null,
    val eventMessageId: Long? = null,
) {
    companion object {
        const val KIND_TEXT = "text"
        const val KIND_IMAGE = "image"
        const val KIND_VIDEO = "video"
        const val FONT_SANS = "sans"
        const val FONT_SERIF = "serif"
        const val FONT_MONO = "mono"
        /** What the composer offers (the server takes up to [SERVER_TEXT_MAX]). */
        const val TEXT_MAX = 140
        const val SERVER_TEXT_MAX = 700
        const val DEFAULT_BG = "#0F766E"
        val FONTS = listOf(FONT_SANS, FONT_SERIF, FONT_MONO)
    }
}

/** One person of `GET status/feed`: their status and stories. */
data class ChatFeedItem(
    val user: ChatPerson,
    val status: ChatStatus?,
    val stories: List<ChatStory>,
    val unseen: Boolean,
)

/** `GET status/feed`: others (unseen first) and the caller. */
data class ChatStatusFeed(val items: List<ChatFeedItem>, val me: ChatFeedItem?)

/** Who saw an own story (`GET status/stories/:id/viewers`). */
data class ChatStoryViewer(val user: ChatPerson, val seenAt: String?, val reaction: String?)

/** `replyStory` of a direct message answering a story. */
data class ChatReplyStory(
    val storyId: String,
    val ownerId: Long?,
    val kind: String?,
    val text: String?,
    val thumbMediaId: String?,
)

// ─── preferences and privacy ────────────────────────────────────────

data class ChatPrivacy(
    val lastSeen: String = AUDIENCE_ALL,
    val avatar: String = AUDIENCE_ALL,
    val statusDefault: String = ChatStatus.VIS_ALL,
    val placeInStatus: String = AUDIENCE_FAMILY,
    val groupAdd: String = AUDIENCE_ALL,
    val defaultDisappearingS: Int = 0,
) {
    companion object {
        const val AUDIENCE_ALL = "all"
        const val AUDIENCE_FAMILY = "family"
        const val AUDIENCE_NOBODY = "nobody"
        val AUDIENCES = listOf(AUDIENCE_ALL, AUDIENCE_FAMILY, AUDIENCE_NOBODY)
        val DISAPPEARING = listOf(0, 86_400, 604_800, 2_592_000)
    }
}

data class ChatNotifyPrefs(
    val groupsMentionsOnly: Boolean = false,
    val reactions: Boolean = true,
    val statusDigest: Boolean = false,
    val eventReminderMin: Int = 60,
) {
    companion object {
        val REMINDER_MIN = listOf(0, 15, 60, 120, 1440)
    }
}

/** Server-side preferences beyond 1.0's (`GET/PUT prefs`). */
data class ChatPrefs14(
    val privacy: ChatPrivacy = ChatPrivacy(),
    val notify: ChatNotifyPrefs = ChatNotifyPrefs(),
    /** `chats.keepArchived`: archived chats stay archived on new messages. */
    val keepArchived: Boolean = true,
)

/** A custom chat filter (`GET/PUT lists`). */
data class ChatList(val id: String, val name: String, val conversationIds: List<Long>) {
    companion object {
        const val MAX = 20
        const val NAME_MAX = 40
    }
}

/** A "remind me" (`GET reminders`). */
data class ChatReminder(
    val id: String,
    val messageId: Long?,
    val conversationId: Long?,
    val at: String,
    val preview: String?,
)

/** `GET messages/:id/suggest`: shopping-like items and dates found in the text. */
data class ChatSuggestion(val items: List<String>, val dates: List<ChatSuggestedDate>) {
    val isEmpty: Boolean get() = items.isEmpty() && dates.isEmpty()
}

data class ChatSuggestedDate(val startsAt: String, val label: String?, val title: String?)

/** A Familie place (`GET familie/places`). */
data class ChatPlace(val id: String, val name: String, val lat: Double?, val lon: Double?, val radiusM: Int?)

/** A home action the caller may run (`GET actions`). */
data class ChatHomeAction(
    val id: String,
    val plugin: String?,
    val label: String,
    /** SVG path of the quick action's icon (QuickActionIcons), or null. */
    val icon: String?,
    val kind: String?,
    val needsConfirm: Boolean,
)

/** Storage use of the messenger on the server (`me.limits.usage`), when sent. */
data class ChatUsage(val usedBytes: Long?, val quotaBytes: Long?)

// ─── message kinds ──────────────────────────────────────────────────

/** `poll` of a `kind: poll` message. */
data class ChatPoll(
    val question: String,
    val options: List<ChatPollOption>,
    val multi: Boolean,
    val anonymous: Boolean,
    val allowAdd: Boolean,
    val closesAt: String?,
    val closed: Boolean,
    val voterCount: Int,
    val memberCount: Int?,
    val myVotes: List<String>,
    /** Who created it (may close it and remind the others). */
    val creatorId: Long? = null,
) {
    val totalVotes: Int get() = options.sumOf { it.count }

    companion object {
        const val QUESTION_MAX = 200
        const val OPTION_MAX = 80
        const val MIN_OPTIONS = 2
        const val MAX_OPTIONS = 12
    }
}

data class ChatPollOption(
    val id: String,
    val text: String,
    val count: Int,
    /** Null when the poll is anonymous. */
    val voters: List<Long>?,
    val addedBy: Long?,
)

/** A place of an event or of an arrival's destination. */
data class ChatEventPlace(
    val familiePlaceId: String?,
    val label: String,
    val lat: Double?,
    val lon: Double?,
    val radiusM: Int? = null,
)

data class ChatChecklistItem(val id: String, val text: String, val doneBy: Long?)

data class ChatRsvpEntry(val userId: Long, val guests: Int)

data class ChatRsvp(
    val yes: List<ChatRsvpEntry>,
    val maybe: List<ChatRsvpEntry>,
    val no: List<ChatRsvpEntry>,
    /** yes | maybe | no | null. */
    val mine: String?,
    val myGuests: Int = 0,
) {
    /** Yes answers plus their guests. */
    val yesCount: Int get() = yes.sumOf { 1 + it.guests }

    companion object {
        const val YES = "yes"
        const val MAYBE = "maybe"
        const val NO = "no"
    }
}

/** `event` of a `kind: event` message. */
data class ChatEventInfo(
    val title: String,
    val startsAt: String,
    val endsAt: String?,
    val allDay: Boolean,
    val place: ChatEventPlace?,
    val description: String?,
    val checklist: List<ChatChecklistItem>,
    /** Null when the event asks for no answers. */
    val rsvp: ChatRsvp?,
    val guests: Boolean,
    val reminderMin: Int,
    val familyCalendar: Boolean,
    val departureHint: Boolean,
    val cancelled: Boolean,
    val creatorId: Long? = null,
) {
    companion object {
        const val GUESTS_MAX = 20
        const val CHECK_TEXT_MAX = 80
        const val TITLE_MAX = 120
        const val DESCRIPTION_MAX = 1000
        const val CHECKLIST_MAX = 20
    }
}

/** `nextEvent` of a conversation: the soonest upcoming event, pinned at the top. */
data class ChatNextEvent(val messageId: Long, val title: String, val startsAt: String, val yesCount: Int)

/** `arrival` of a `kind: arrival` message (live arrival). */
data class ChatArrival(
    val dest: ChatEventPlace,
    /** live | arrived | stopped. */
    val state: String,
    val etaAt: String?,
    val distanceM: Int?,
    val progress: Float?,
    val lastAt: String?,
    val lat: Double?,
    val lon: Double?,
    val onArriveActionId: String?,
    val onArriveLabel: String? = null,
) {
    val live: Boolean get() = state == STATE_LIVE

    companion object {
        const val STATE_LIVE = "live"
        const val STATE_ARRIVED = "arrived"
        const val STATE_STOPPED = "stopped"
        const val DEFAULT_RADIUS_M = 150
    }
}

/** `action` of a `kind: action` message (run / request / grant). */
data class ChatActionCard(
    val mode: String,
    val actionId: String,
    val label: String,
    val icon: String?,
    val plugin: String?,
    val approverIds: List<Long>,
    val grantTo: Long?,
    val grantUntil: String?,
    /** open | done | denied | expired. */
    val state: String,
    val result: String?,
    val by: Long?,
    val needsConfirm: Boolean = false,
    /** What the server lets the caller do with it right now (`can`). */
    val canRun: Boolean = false,
    val canApprove: Boolean = false,
    val canDeny: Boolean = false,
    /** Whether the last run worked (null: not run or unknown). */
    val resultOk: Boolean? = null,
) {
    companion object {
        const val MODE_RUN = "run"
        const val MODE_REQUEST = "request"
        const val MODE_GRANT = "grant"
        const val STATE_OPEN = "open"
        const val STATE_DONE = "done"
        const val STATE_DENIED = "denied"
        const val STATE_EXPIRED = "expired"
    }
}

/** A button of a plugin card. [route] an app route ("live view") instead of an action. */
data class ChatCardButton(
    val actionId: String?,
    val label: String?,
    val icon: String?,
    val route: String? = null,
    val plugin: String? = null,
    val needsConfirm: Boolean = false,
    /** The caller may press it (an action they may run, or a link). */
    val canRun: Boolean = true,
)

/** `card` of a `kind: card` message (doorbell, notice — sent by plugins). */
data class ChatPluginCard(
    /** doorbell | notice. */
    val kind: String,
    val title: String?,
    val text: String?,
    val imageMediaId: String?,
    val buttons: List<ChatCardButton>,
    /** The picture inline (`image { mime, data }`, base64, ≤ 300 KB). */
    val imageBase64: String? = null,
    val imageMime: String? = null,
    /** Who pressed a button last, and whether it worked. */
    val lastRunBy: Long? = null,
    val lastRunOk: Boolean? = null,
)

/** One part of a chat export (`GET conversations/:id/export`). */
data class ChatExportPart(val text: String, val fileName: String?, val truncated: Boolean, val before: Long?)

/** A message to show once its conversation opens (a tapped push: poll, event, reminder, departure hint). */
data class ChatDeepLinkTarget(val conversationId: Long, val messageId: Long?, val open: String?)

/** Hand-over of a push's message from the navigation to the conversation screen. */
object ChatDeepLink {
    private val _pending = kotlinx.coroutines.flow.MutableStateFlow<ChatDeepLinkTarget?>(null)
    val pending: kotlinx.coroutines.flow.StateFlow<ChatDeepLinkTarget?> = _pending

    fun request(conversationId: Long, messageId: Long?, open: String?) {
        _pending.value = ChatDeepLinkTarget(conversationId, messageId, open)
    }

    /** The target of [conversationId], once. */
    fun take(conversationId: Long): ChatDeepLinkTarget? {
        val t = _pending.value?.takeIf { it.conversationId == conversationId } ?: return null
        return if (_pending.compareAndSet(t, null)) t else null
    }
}

/** Bytes of the chats on this device ("Speicher und Daten"). */
data class ChatStorageUsage(val photos: Long, val videos: Long, val voice: Long, val files: Long, val outbox: Long) {
    val total: Long get() = photos + videos + voice + files + outbox
}

/** Pure: the kind of a cached file by its first bytes. Unit-tested. */
object ChatStorageLogic {
    const val PHOTO = "photo"
    const val VIDEO = "video"
    const val VOICE = "voice"
    const val FILE = "file"

    fun kindOf(head: ByteArray): String {
        fun at(i: Int) = head.getOrNull(i)?.toInt()?.and(0xFF) ?: -1
        fun ascii(from: Int, len: Int) = if (head.size >= from + len) String(head, from, len, Charsets.ISO_8859_1) else ""
        return when {
            at(0) == 0xFF && at(1) == 0xD8 -> PHOTO
            ascii(1, 3) == "PNG" -> PHOTO
            ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" -> PHOTO
            ascii(0, 3) == "GIF" -> PHOTO
            ascii(4, 4) == "ftyp" -> if (ascii(8, 3) == "M4A" || ascii(8, 4) == "M4B ") VOICE else VIDEO
            ascii(0, 4) == "OggS" -> VOICE
            at(0) == 0x1A && at(1) == 0x45 && at(2) == 0xDF && at(3) == 0xA3 -> VIDEO
            else -> FILE
        }
    }
}
