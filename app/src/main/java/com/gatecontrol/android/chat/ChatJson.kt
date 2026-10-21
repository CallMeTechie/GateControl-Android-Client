package com.gatecontrol.android.chat

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * Parses the messenger plugin's app API (README "App-API") and builds its
 * request bodies. Everything the server sends is untrusted: ids must be
 * positive, kinds known, texts plain and capped; a malformed conversation
 * or message is dropped, a malformed answer is null. Pure (Gson tree
 * only), unit-tested — and R8-safe, no reflection.
 */
object ChatJson {

    private val CONTROL_RE = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
    internal val LINE_CONTROL_RE = Regex("[\\u0000-\\u001F\\u007F]")
    private val TIME_RE = Regex("^[0-9TZ:+.-]{10,40}$")
    private val MEDIA_ID_RE = Regex("^[0-9a-f]{32}$")
    private val CLIENT_ID_RE = Regex("^[A-Za-z0-9_-]{8,64}$")
    internal val TOKEN_RE = Regex("^[a-z][a-z0-9_.-]{0,63}$")
    internal val COLOR_RE = Regex("^#[0-9A-Fa-f]{6}$")
    private val MIME_RE = Regex("^[a-z0-9.+-]{1,40}/[A-Za-z0-9.+-]{1,120}$")
    /** `/avatars/…` or `/plugin-media/…` of the own server, nothing else. */
    private val AVATAR_PATH_RE = Regex("^/(?:avatars|plugin-media)/[A-Za-z0-9._~%-]{1,200}$")
    private val STATUSES = setOf(ChatMessage.STATUS_SENT, ChatMessage.STATUS_DELIVERED, ChatMessage.STATUS_READ)
    const val TEXT_MAX = 4000
    private const val MAX_MEMBERS = 300
    private const val MAX_REACTIONS = 32
    private const val MAX_IDS = 1000
    private const val MAX_FEATURES = 64
    private val FEATURE_FLAGS = setOf("direct", "groups", "voice", "files", "location", "reactions", "edit", "search", "disappearing")

    // ─── small helpers ──────────────────────────────────────────────

    fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    internal fun JsonObject.prim(key: String): JsonPrimitive? = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive

    fun JsonObject.str(key: String): String? = prim(key)?.takeIf { it.isString }?.asString

    /** Positive integer id (number or digit string — push payloads send conversation ids as strings). */
    fun JsonObject.id(key: String): Long? = idOf(get(key))

    fun idOf(e: JsonElement?): Long? {
        val p = e?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        val n = when {
            p.isNumber -> runCatching {
                p.asBigDecimal.takeIf { it.signum() > 0 && it.stripTrailingZeros().scale() <= 0 && it.toBigInteger().bitLength() < 53 }?.toLong()
            }.getOrNull()
            p.isString -> p.asString.takeIf { it.length in 1..15 && it.all(Char::isDigit) }?.toLong()
            else -> null
        }
        return n?.takeIf { it > 0 }
    }

    internal fun JsonObject.int(key: String): Int? {
        val p = prim(key)?.takeIf { it.isNumber } ?: return null
        val d = runCatching { p.asDouble }.getOrNull() ?: return null
        return if (d.isFinite() && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE) d.toInt() else null
    }

    internal fun JsonObject.long(key: String): Long? {
        val p = prim(key)?.takeIf { it.isNumber } ?: return null
        val d = runCatching { p.asDouble }.getOrNull() ?: return null
        return if (d.isFinite() && d >= 0 && d <= 9.0E15) d.toLong() else null
    }

    internal fun JsonObject.double(key: String): Double? {
        val p = prim(key)?.takeIf { it.isNumber } ?: return null
        return runCatching { p.asDouble }.getOrNull()?.takeIf { it.isFinite() }
    }

    /** true/false, also 1/0 (SQLite flags). */
    fun JsonObject.flag(key: String): Boolean? {
        val p = prim(key) ?: return null
        return when {
            p.isBoolean -> p.asBoolean
            p.isNumber -> p.asDouble != 0.0
            else -> null
        }
    }

    internal fun JsonObject.arr(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    internal fun JsonElement.objOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

    internal fun JsonObject.ids(key: String): List<Long> =
        arr(key)?.mapNotNull { idOf(it) }?.distinct()?.take(MAX_IDS).orEmpty()

    /** Plain single-line text, capped; blank → null. */
    fun line(value: String?, max: Int = 120): String? {
        if (value == null) return null
        return LINE_CONTROL_RE.replace(value, " ").trim().take(max).takeIf { it.isNotBlank() }
    }

    /** Message text: newlines kept, other control characters removed, capped; blank → null. */
    fun text(value: String?, max: Int = TEXT_MAX): String? {
        if (value == null) return null
        return CONTROL_RE.replace(value.replace("\r\n", "\n"), "").take(max).takeIf { it.isNotBlank() }
    }

    fun time(value: String?): String? = value?.trim()?.takeIf { TIME_RE.matches(it) }

    fun mediaId(value: String?): String? = value?.takeIf { MEDIA_ID_RE.matches(it) }

    fun avatarPath(value: String?): String? = value?.trim()?.takeIf { AVATAR_PATH_RE.matches(it) && !it.contains("..") }

    internal fun token(value: String?): String? = value?.takeIf { TOKEN_RE.matches(it) }

    fun isClientId(value: String?): Boolean = value != null && CLIENT_ID_RE.matches(value)

    // ─── errors ─────────────────────────────────────────────────────

    /** `{ ok:false, code }` → code. */
    fun errorCode(o: JsonObject?): String? =
        o?.str("code")?.takeIf { it.length <= 40 && it.all { c -> c.isLetterOrDigit() || c == '_' } }

    // ─── people ─────────────────────────────────────────────────────

    /** A person: `userId` (members, peer) or `id` (people). */
    fun person(o: JsonObject?): ChatPerson? {
        if (o == null) return null
        val id = o.id("userId") ?: o.id("id") ?: return null
        val name = line(o.str("name"), 80)
        return ChatPerson(
            userId = id,
            name = name,
            avatarUrl = avatarPath(o.str("avatarUrl")),
            initials = line(o.str("initials"), 3),
            colorHex = o.str("colorHex")?.takeIf { COLOR_RE.matches(it) },
            gone = o.flag("gone") == true,
            role = o.str("role")?.takeIf { it == ChatPerson.ROLE_ADMIN || it == ChatPerson.ROLE_MEMBER },
            online = o.flag("online"),
            lastSeenAt = time(o.str("lastSeenAt")),
            blocked = o.flag("blocked") == true,
        )
    }

    fun people(o: JsonObject): List<ChatPerson>? =
        o.arr("people")?.mapNotNull { person(it.objOrNull()) }?.distinctBy { it.userId }

    // ─── me / prefs ─────────────────────────────────────────────────

    fun prefs(o: JsonObject?): ChatPrefs {
        if (o == null) return ChatPrefs()
        return ChatPrefs(
            readReceipts = o.flag("readReceipts") ?: true,
            typing = o.flag("typing") ?: true,
            preview = o.flag("preview") ?: true,
        )
    }

    fun profile(o: JsonObject): ChatProfile? {
        val u = o.obj("user") ?: return null
        val user = person(u) ?: return null
        val l = o.obj("limits")
        val features = features(o.get("features"))
        val d = ChatLimits()
        val limits = if (l == null) d else ChatLimits(
            maxBytes = l.long("maxBytes")?.takeIf { it > 0 },
            types = l.arr("types")?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString?.lowercase()?.takeIf { MIME_RE.matches(it) } }?.distinct()?.take(100).orEmpty(),
            quotaFull = l.flag("quotaFull") ?: false,
            editWindowS = l.int("editWindowS")?.coerceIn(0, 30 * 86_400) ?: d.editWindowS,
            maxVoiceS = l.int("maxVoiceS")?.coerceIn(5, 3600) ?: d.maxVoiceS,
            textMax = l.int("textMax")?.coerceIn(1, TEXT_MAX) ?: d.textMax,
            thumbMaxBytes = l.int("thumbMaxBytes")?.coerceIn(1_024, 1_048_576) ?: d.thumbMaxBytes,
            thumbMaxSide = l.int("thumbMaxSide")?.coerceIn(64, 2048) ?: d.thumbMaxSide,
            imageMaxSide = l.int("imageMaxSide")?.coerceIn(320, 8192) ?: d.imageMaxSide,
            groupMax = l.int("groupMax")?.coerceIn(2, 10_000) ?: d.groupMax,
            titleMax = l.int("titleMax")?.coerceIn(1, 200) ?: d.titleMax,
            pageMax = l.int("pageMax")?.coerceIn(10, 500) ?: d.pageMax,
            typingS = l.int("typingS")?.coerceIn(1, 60) ?: d.typingS,
            typingEveryS = l.int("typingEveryS")?.coerceIn(1, 60) ?: d.typingEveryS,
            activeS = l.int("activeS")?.coerceIn(5, 600) ?: d.activeS,
            usage = ChatJson14.usage(l),
            disappearingS = l.arr("disappearingS")?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt?.takeIf { it >= 0 } }
                ?.distinct()?.take(10)?.takeIf { it.isNotEmpty() } ?: d.disappearingS,
        )
        val lic = o.obj("license")
        return ChatProfile(
            user = user,
            prefs = prefs(o.obj("prefs")),
            licensed = lic?.flag("licensed"),
            features = features,
            limits = limits,
            serverTime = time(o.str("serverTime")),
            prefs14 = ChatJson14.prefs14(o.obj("prefs")),
            status = ChatJson14.status(o.obj("status")),
            familie = ChatJson14.familie(o.obj("integrations")),
            home = ChatHomeJson.home(o),
        )
    }

    /**
     * `features` of `GET me`: the object of flags (`{ direct, groups, … }`);
     * further keys set to `true` (`video_meta`, `file_thumbs` of Messenger
     * 1.3.0, and whatever newer versions add) become [ChatFeatures.tokens].
     */
    fun features(e: JsonElement?): ChatFeatures {
        val f = e?.objOrNull() ?: return ChatFeatures()
        val tokens = f.entrySet().filter { (k, v) ->
            k !in FEATURE_FLAGS && token(k) != null && v.isJsonPrimitive && v.asJsonPrimitive.isBoolean && v.asBoolean
        }.map { it.key }.take(MAX_FEATURES).toSet()
        return ChatFeatures(
            direct = f.flag("direct") ?: true,
            groups = f.flag("groups") ?: true,
            voice = f.flag("voice") ?: true,
            files = f.flag("files") ?: true,
            location = f.flag("location") ?: true,
            reactions = f.flag("reactions") ?: true,
            edit = f.flag("edit") ?: true,
            search = f.flag("search") ?: true,
            disappearing = f.flag("disappearing") ?: false,
            tokens = tokens,
        )
    }

    // ─── conversations ──────────────────────────────────────────────

    fun conversation(o: JsonObject?): ChatConversation? {
        if (o == null) return null
        val id = o.id("id") ?: return null
        val kind = o.str("kind")?.takeIf { it == ChatConversation.KIND_DIRECT || it == ChatConversation.KIND_GROUP } ?: return null
        val me = o.obj("me")
        val can = o.obj("can")
        val members = o.arr("members")?.mapNotNull { person(it.objOrNull()) }?.distinctBy { it.userId }?.take(MAX_MEMBERS).orEmpty()
        return ChatConversation(
            id = id,
            kind = kind,
            title = line(o.str("title"), 120),
            peer = person(o.obj("peer")),
            avatarUrl = avatarPath(o.str("avatarUrl")),
            avatarMediaId = mediaId(o.str("avatarMediaId")),
            managedBy = token(o.str("managedBy")),
            readonly = o.flag("readonly") ?: false,
            disappearingS = o.int("disappearingS")?.coerceAtLeast(0) ?: 0,
            createdAt = time(o.str("createdAt")),
            updatedAt = time(o.str("updatedAt")),
            lastMessageId = o.id("lastMessageId"),
            members = members,
            me = ChatMembership(
                role = me?.str("role"),
                mutedUntil = time(me?.str("mutedUntil")),
                notify = if (me?.str("notify") == "none") "none" else "all",
                lastReadId = me?.id("lastReadId"),
                lastDeliveredId = me?.id("lastDeliveredId"),
            ),
            muted = o.flag("muted") ?: false,
            unread = o.int("unread")?.coerceAtLeast(0) ?: 0,
            lastMessage = message(o.obj("lastMessage")),
            typing = o.ids("typing"),
            readUpTo = o.id("readUpTo"),
            deliveredUpTo = o.id("deliveredUpTo"),
            receipts = receipts(o),
            can = ChatCan(
                send = can?.flag("send") ?: true,
                editInfo = can?.flag("editInfo") ?: false,
                manageMembers = can?.flag("manageMembers") ?: false,
                leave = can?.flag("leave") ?: false,
                setDisappearing = can?.flag("setDisappearing") ?: false,
                pin = can?.flag("pin") ?: true,
            ),
            archived = o.flag("archived") == true,
            pinned = o.ids("pinned").take(10),
            nextEvent = ChatJson14.nextEvent(o.obj("nextEvent")),
            listPinned = o.flag("listPinned") == true,
            notice = notice(o.obj("notice")),
        )
    }

    /** `notice { source, label }` of a notice channel (Messenger 1.6); anything else than an object → none. */
    fun notice(o: JsonObject?): ChatNotice? {
        if (o == null) return null
        return ChatNotice(
            source = o.str("source")?.trim()?.takeIf { it.length <= 80 && ChatNotice.SOURCE_RE.matches(it) },
            label = line(o.str("label"), 60),
        )
    }

    private fun receipts(o: JsonObject): List<ChatReceipt> =
        o.arr("receipts")?.mapNotNull { e ->
            val r = e.objOrNull() ?: return@mapNotNull null
            val uid = r.id("userId") ?: return@mapNotNull null
            ChatReceipt(uid, r.id("lastReadId"), r.id("lastDeliveredId"))
        }?.distinctBy { it.userId }?.take(MAX_MEMBERS).orEmpty()

    fun conversationList(o: JsonObject): ChatConversationList? {
        val arr = o.arr("conversations") ?: return null
        return ChatConversationList(
            conversations = arr.mapNotNull { conversation(it.objOrNull()) }.distinctBy { it.id },
            removed = o.ids("removed"),
            serverTime = time(o.str("serverTime")),
        )
    }

    /** `{ conversation }` (null after leaving). */
    fun conversationOf(o: JsonObject): ChatConversation? = conversation(o.obj("conversation"))

    // ─── messages ───────────────────────────────────────────────────

    fun message(o: JsonObject?): ChatMessage? {
        if (o == null) return null
        val id = o.id("id") ?: return null
        val conv = o.id("conversationId") ?: return null
        // Kinds of newer messengers are kept (shown as "needs a newer app"); only the shape is checked.
        val kind = token(o.str("kind")) ?: return null
        val createdAt = time(o.str("createdAt")) ?: return null
        val deleted = o.flag("deleted") == true
        val mine = o.flag("mine") == true
        return ChatMessage(
            id = id,
            conversationId = conv,
            senderId = o.id("senderId"),
            clientId = o.str("clientId")?.takeIf { CLIENT_ID_RE.matches(it) },
            kind = kind,
            via = token(o.str("via")),
            mine = mine,
            createdAt = createdAt,
            editedAt = time(o.str("editedAt")),
            deletedAt = time(o.str("deletedAt")),
            expiresAt = time(o.str("expiresAt")),
            deleted = deleted,
            undecryptable = o.flag("undecryptable") == true,
            text = if (deleted) null else text(o.str("text")),
            replyTo = if (deleted) null else replyRef(o.obj("replyTo")),
            media = if (deleted) null else media(o.obj("media")),
            meta = if (deleted) null else meta(o.obj("meta")),
            location = if (deleted) null else location(o.obj("location")),
            system = if (kind == ChatMessage.KIND_SYSTEM) system(o.obj("system")) else null,
            reactions = if (deleted) emptyList() else reactions(o.arr("reactions")),
            status = if (mine) o.str("status")?.takeIf { it in STATUSES } else null,
            video = if (deleted) null else video(o.obj("video")),
            poll = if (deleted || kind != ChatMessage.KIND_POLL) null else ChatJson14.poll(o.obj("poll")),
            event = if (deleted || kind != ChatMessage.KIND_EVENT) null else ChatJson14.eventInfo(o.obj("event")),
            arrival = if (deleted || kind != ChatMessage.KIND_ARRIVAL) null else ChatJson14.arrival(o.obj("arrival")),
            action = if (deleted || kind != ChatMessage.KIND_ACTION) null else ChatJson14.action(o.obj("action")),
            card = if (deleted || kind != ChatMessage.KIND_CARD) null else ChatJson14.card(o.obj("card"), media(o.obj("media"))?.id),
            replyStory = if (deleted) null else ChatJson14.replyStory(o.obj("replyStory")),
        )
    }

    internal fun video(o: JsonObject?): ChatVideo? {
        if (o == null) return null
        val v = ChatVideo(
            durationMs = o.long("durationMs")?.coerceAtMost(24 * 3_600_000L),
            width = o.int("width")?.takeIf { it in 1..20_000 },
            height = o.int("height")?.takeIf { it in 1..20_000 },
        )
        return v.takeIf { it.durationMs != null || it.width != null || it.height != null }
    }

    private fun replyRef(o: JsonObject?): ChatReplyRef? {
        if (o == null) return null
        val id = o.id("id") ?: return null
        return ChatReplyRef(
            id = id,
            senderId = o.id("senderId"),
            kind = token(o.str("kind")),
            text = text(o.str("text"), 200),
            deleted = o.flag("deleted") == true,
            missing = o.flag("missing") == true,
        )
    }

    private fun media(o: JsonObject?): ChatMedia? {
        if (o == null) return null
        val id = mediaId(o.str("id")) ?: return null
        return ChatMedia(
            id = id,
            mime = o.str("mime")?.lowercase()?.takeIf { MIME_RE.matches(it) },
            bytes = o.long("bytes"),
            thumbId = mediaId(o.str("thumbId")),
        )
    }

    private fun meta(o: JsonObject?): ChatMeta? {
        if (o == null) return null
        return ChatMeta(
            durationMs = o.long("durationMs")?.coerceAtMost(24 * 3_600_000L),
            waveform = o.arr("waveform")?.take(64)?.mapNotNull { e ->
                e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble?.takeIf { it.isFinite() }?.toInt()?.coerceIn(0, 255)
            }.orEmpty(),
            w = o.int("w")?.takeIf { it in 1..20_000 },
            h = o.int("h")?.takeIf { it in 1..20_000 },
            fileName = line(o.str("fileName"), 200)?.replace('/', '_')?.replace('\\', '_'),
        )
    }

    private fun location(o: JsonObject?): ChatLocation? {
        if (o == null) return null
        val lat = o.double("lat")?.takeIf { it in -90.0..90.0 } ?: return null
        val lon = o.double("lon")?.takeIf { it in -180.0..180.0 } ?: return null
        return ChatLocation(lat, lon, line(o.str("label"), 200), o.int("accuracy")?.takeIf { it >= 0 })
    }

    private fun system(o: JsonObject?): ChatSystem? {
        if (o == null) return null
        val event = token(o.str("event")) ?: return null
        return ChatSystem(
            event = event,
            by = o.id("by") ?: o.id("byUserId"),
            title = line(o.str("title") ?: o.str("question"), 200),
            userIds = o.ids("userIds"),
            userId = o.id("userId"),
            seconds = o.int("seconds")?.coerceAtLeast(0),
            label = line(
                o.str("label") ?: o.str("winner") ?: o.str("option") ?: o.str("place")
                    ?: o.arr("winners")?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString }?.takeIf { it.isNotEmpty() }?.joinToString(", "),
                120,
            ),
            messageId = o.id("messageId"),
            answer = o.str("answer")?.takeIf { it == "yes" || it == "maybe" || it == "no" },
            guests = o.int("guests")?.coerceIn(0, 99),
            otherUserId = o.id("asUserId") ?: o.id("requesterId") ?: o.id("toUserId"),
            ok = o.flag("ok"),
        )
    }

    fun reactions(arr: JsonArray?): List<ChatReaction> =
        arr?.mapNotNull { e ->
            val r = e.objOrNull() ?: return@mapNotNull null
            val emoji = r.str("emoji")?.takeIf { it.isNotBlank() && it.length <= 16 && !LINE_CONTROL_RE.containsMatchIn(it) } ?: return@mapNotNull null
            val users = r.ids("userIds")
            val count = r.int("count")?.coerceAtLeast(0) ?: users.size
            if (count <= 0) null else ChatReaction(emoji, count, users)
        }?.distinctBy { it.emoji }?.take(MAX_REACTIONS).orEmpty()

    fun messagePage(o: JsonObject): ChatMessagePage? {
        val arr = o.arr("messages") ?: return null
        return ChatMessagePage(
            messages = arr.mapNotNull { message(it.objOrNull()) }.distinctBy { it.id },
            hasMore = o.flag("hasMore") == true,
            readUpTo = o.id("readUpTo"),
            deliveredUpTo = o.id("deliveredUpTo"),
            receipts = receipts(o),
            hasMoreBefore = o.flag("hasMoreBefore"),
            hasMoreAfter = o.flag("hasMoreAfter"),
            serverTime = time(o.str("serverTime")),
        )
    }

    /** `{ message, duplicate }` of a send / edit / delete-for-all. */
    fun messageOf(o: JsonObject): ChatMessage? = message(o.obj("message"))

    fun search(o: JsonObject): ChatSearchResult? {
        val arr = o.arr("results") ?: return null
        return ChatSearchResult(arr.mapNotNull { message(it.objOrNull()) }, o.flag("truncated") == true)
    }

    /**
     * `GET conversations/:id/media` → `{ items (newest first), counts: { image, voice, file, video? }, hasMore }`.
     * Only messages that carry a file are kept (deleted ones have none).
     */
    fun mediaPage(o: JsonObject): ChatMediaPage? {
        val arr = o.arr("items") ?: return null
        val counts = o.obj("counts")
        return ChatMediaPage(
            items = arr.mapNotNull { message(it.objOrNull()) }.filter { it.media != null && !it.deleted }.distinctBy { it.id },
            images = counts?.int("image")?.coerceAtLeast(0),
            voice = counts?.int("voice")?.coerceAtLeast(0),
            files = counts?.int("file")?.coerceAtLeast(0),
            hasMore = o.flag("hasMore") == true,
            videos = counts?.int("video")?.coerceAtLeast(0),
        )
    }

    /** `PUT messages/:id/reaction` → the message's reactions. */
    fun reactionsOf(o: JsonObject): List<ChatReaction>? = o.arr("reactions")?.let { reactions(it) }

    /** `POST conversations/:id/read` → unread left. */
    fun unreadOf(o: JsonObject): Int? = o.int("unread")?.coerceAtLeast(0)

    /** `PUT …/media` → `{ mediaId, mime, bytes }`. */
    fun uploadedMediaId(o: JsonObject): String? = mediaId(o.str("mediaId"))

    // ─── request bodies ─────────────────────────────────────────────

    /** Body of `POST conversations/:id/messages`. */
    fun sendBody(
        clientId: String,
        kind: String,
        text: String?,
        replyTo: Long? = null,
        mediaId: String? = null,
        thumbMediaId: String? = null,
        location: ChatLocation? = null,
        meta: ChatMeta? = null,
        source: String? = null,
        video: ChatVideo? = null,
    ): JsonObject = JsonObject().apply {
        addProperty("clientId", clientId)
        addProperty("kind", kind)
        text?.takeIf { it.isNotBlank() }?.let { addProperty("text", it.take(TEXT_MAX)) }
        replyTo?.let { addProperty("replyTo", it) }
        mediaId?.let { addProperty("mediaId", it) }
        thumbMediaId?.let { addProperty("thumbMediaId", it) }
        location?.let { loc ->
            add("location", JsonObject().apply {
                addProperty("lat", loc.lat)
                addProperty("lon", loc.lon)
                loc.label?.let { addProperty("label", it.take(200)) }
                loc.accuracy?.let { addProperty("accuracy", it) }
            })
        }
        meta?.let { m ->
            val mo = JsonObject()
            m.durationMs?.let { mo.addProperty("durationMs", it) }
            if (m.waveform.isNotEmpty()) mo.add("waveform", JsonArray().apply { m.waveform.take(64).forEach { add(it.coerceIn(0, 255)) } })
            m.w?.let { mo.addProperty("w", it) }
            m.h?.let { mo.addProperty("h", it) }
            m.fileName?.let { mo.addProperty("fileName", it.take(200)) }
            if (mo.size() > 0) add("meta", mo)
        }
        video?.let { v ->
            val vo = JsonObject()
            v.durationMs?.let { vo.addProperty("durationMs", it) }
            v.width?.let { vo.addProperty("width", it) }
            v.height?.let { vo.addProperty("height", it) }
            if (vo.size() > 0) add("video", vo)
        }
        source?.let { addProperty("source", it) }
    }

    fun createDirectBody(peerId: Long): JsonObject = JsonObject().apply {
        addProperty("kind", ChatConversation.KIND_DIRECT)
        add("userIds", JsonArray().apply { add(peerId) })
    }

    fun createGroupBody(title: String, userIds: List<Long>, avatarMediaId: String? = null): JsonObject = JsonObject().apply {
        addProperty("kind", ChatConversation.KIND_GROUP)
        addProperty("title", title)
        add("userIds", JsonArray().apply { userIds.distinct().forEach { add(it) } })
        avatarMediaId?.let { addProperty("avatarMediaId", it) }
    }

    /** A JSON `null` property (removes a value: mutedUntil, emoji). */
    fun JsonObject.addNull(key: String) = add(key, JsonNull.INSTANCE)

    fun readBody(upTo: Long): JsonObject = JsonObject().apply { addProperty("upTo", upTo) }

    fun reactionBody(emoji: String?): JsonObject = JsonObject().apply {
        if (emoji == null) addNull("emoji") else addProperty("emoji", emoji)
    }
}
