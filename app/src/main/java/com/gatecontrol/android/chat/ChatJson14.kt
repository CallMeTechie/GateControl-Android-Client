package com.gatecontrol.android.chat

import com.gatecontrol.android.chat.ChatJson.arr
import com.gatecontrol.android.chat.ChatJson.double
import com.gatecontrol.android.chat.ChatJson.flag
import com.gatecontrol.android.chat.ChatJson.id
import com.gatecontrol.android.chat.ChatJson.ids
import com.gatecontrol.android.chat.ChatJson.int
import com.gatecontrol.android.chat.ChatJson.long
import com.gatecontrol.android.chat.ChatJson.obj
import com.gatecontrol.android.chat.ChatJson.objOrNull
import com.gatecontrol.android.chat.ChatJson.prim
import com.gatecontrol.android.chat.ChatJson.str
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * Parsers and writers of the Messenger 1.4 objects (status, stories,
 * polls, events, arrival, actions, cards, preferences, tools). Like
 * [ChatJson]: everything from the server is untrusted, capped and checked;
 * the writers give back the server's shape, so the Room cache round-trips
 * (`parse(write(x)) == x`). Pure, unit-tested, no reflection (R8-safe).
 */
object ChatJson14 {

    /** Ids the server may send as numbers or strings (story, option, action, list, place, reminder). */
    private val KEY_RE = Regex("^[A-Za-z0-9_.:/-]{1,128}$")
    /** Ids that go into a URL path. */
    private val PATH_KEY_RE = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val DIGITS_RE = Regex("^[0-9]{1,15}$")
    private const val MAX_ITEMS = 200
    private const val ICON_MAX = 4000

    // ─── helpers ────────────────────────────────────────────────────

    fun keyOf(e: JsonElement?): String? {
        val p = e?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        return when {
            p.isString -> p.asString.takeIf { KEY_RE.matches(it) }
            p.isNumber -> ChatJson.idOf(p)?.toString()
            else -> null
        }
    }

    private fun JsonObject.key(name: String): String? = keyOf(get(name))

    /** A key that may go into a route (`status/stories/<id>`). */
    fun pathKey(value: String?): String? = value?.takeIf { PATH_KEY_RE.matches(it) }

    /** A key back as the server sent it: digits as a number, else a string. */
    fun keyElement(key: String): JsonElement =
        if (DIGITS_RE.matches(key)) JsonPrimitive(key.toLong()) else JsonPrimitive(key)

    private fun JsonObject.putKey(name: String, key: String?) = if (key == null) add(name, JsonNull.INSTANCE) else add(name, keyElement(key))

    private fun JsonObject.put(name: String, value: String?) = if (value == null) add(name, JsonNull.INSTANCE) else addProperty(name, value)

    private fun JsonObject.put(name: String, value: Number?) = if (value == null) add(name, JsonNull.INSTANCE) else addProperty(name, value)

    private fun idsArray(list: List<Long>) = JsonArray().apply { list.forEach { add(it) } }

    private fun JsonObject.list(vararg names: String): JsonArray? = names.firstNotNullOfOrNull { arr(it) }

    private fun icon(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() && it.length <= ICON_MAX && !ChatJson.LINE_CONTROL_RE.containsMatchIn(it) }

    private fun color(value: String?): String? = value?.takeIf { ChatJson.COLOR_RE.matches(it) }

    private fun lat(o: JsonObject, name: String = "lat"): Double? = o.double(name)?.takeIf { it in -90.0..90.0 }

    private fun lon(o: JsonObject, name: String = "lon"): Double? = o.double(name)?.takeIf { it in -180.0..180.0 }

    // ─── status ─────────────────────────────────────────────────────

    fun status(o: JsonObject?): ChatStatus? {
        if (o == null) return null
        val preset = o.str("preset")?.takeIf { it in ChatStatus.PRESETS } ?: ChatStatus.PRESET_NONE
        val text = ChatJson.line(o.str("text"), ChatStatus.TEXT_MAX)
        val place = ChatJson.line(o.str("place"), 80)
        if (text == null && preset == ChatStatus.PRESET_NONE && place == null) return null
        return ChatStatus(
            text = text,
            preset = preset,
            until = ChatJson.time(o.str("until")),
            visibility = o.str("visibility")?.takeIf { it in ChatStatus.VISIBILITIES } ?: ChatStatus.VIS_ALL,
            userIds = o.ids("userIds").take(ChatJson14.MAX_ITEMS),
            eta = ChatJson.time(o.str("eta")),
            destLabel = ChatJson.line(o.str("destLabel"), 80),
            place = place,
            since = ChatJson.time(o.str("since")) ?: ChatJson.time(o.str("updatedAt")),
            ownText = ChatJson.line(o.str("ownText"), ChatStatus.TEXT_MAX),
        )
    }

    fun writeStatus(s: ChatStatus): JsonObject = JsonObject().apply {
        put("text", s.text)
        addProperty("preset", s.preset)
        put("until", s.until)
        addProperty("visibility", s.visibility)
        if (s.userIds.isNotEmpty()) add("userIds", idsArray(s.userIds))
        s.eta?.let { addProperty("eta", it) }
        s.destLabel?.let { addProperty("destLabel", it) }
        s.place?.let { addProperty("place", it) }
        s.since?.let { addProperty("since", it) }
        s.ownText?.let { addProperty("ownText", it) }
    }

    /** Body of `PUT status`. */
    fun statusBody(s: ChatStatus): JsonObject = JsonObject().apply {
        s.text?.takeIf { it.isNotBlank() }?.let { addProperty("text", it.take(ChatStatus.TEXT_MAX)) }
        addProperty("preset", s.preset)
        put("until", s.until)
        addProperty("visibility", s.visibility)
        if (s.visibility == ChatStatus.VIS_CUSTOM) add("userIds", idsArray(s.userIds))
        if (s.preset == ChatStatus.PRESET_AWAY) {
            s.eta?.let { addProperty("eta", it) }
            s.destLabel?.let { addProperty("destLabel", it) }
        }
    }

    // ─── stories ────────────────────────────────────────────────────

    private val STORY_KINDS = setOf(ChatStory.KIND_TEXT, ChatStory.KIND_IMAGE, ChatStory.KIND_VIDEO)

    fun story(o: JsonObject?): ChatStory? {
        if (o == null) return null
        val id = pathKey(o.key("id")) ?: return null
        val kind = o.str("kind")?.takeIf { it in STORY_KINDS } ?: return null
        val viewers = o.prim("viewers")?.takeIf { it.isNumber }?.let { o.int("viewers") } ?: o.int("viewerCount")
        return ChatStory(
            id = id,
            kind = kind,
            text = ChatJson.text(o.str("text"), ChatStory.SERVER_TEXT_MAX),
            bg = color(o.str("bg")),
            font = o.str("font")?.takeIf { it in ChatStory.FONTS },
            mediaId = ChatJson.mediaId(o.str("mediaId")),
            thumbMediaId = ChatJson.mediaId(o.str("thumbMediaId")),
            video = ChatJson.video(o.obj("video")),
            createdAt = ChatJson.time(o.str("createdAt")),
            expiresAt = ChatJson.time(o.str("expiresAt")),
            seen = o.flag("seen") == true,
            place = ChatJson.line(o.str("place"), 80),
            viewerCount = viewers?.coerceAtLeast(0),
            visibility = o.str("visibility")?.takeIf { it in ChatStatus.VISIBILITIES },
            eventMessageId = o.id("eventMessageId"),
        )
    }

    fun writeStory(s: ChatStory): JsonObject = JsonObject().apply {
        add("id", keyElement(s.id))
        addProperty("kind", s.kind)
        put("text", s.text)
        put("bg", s.bg)
        put("font", s.font)
        put("mediaId", s.mediaId)
        put("thumbMediaId", s.thumbMediaId)
        s.video?.let { v ->
            add("video", JsonObject().apply {
                v.durationMs?.let { addProperty("durationMs", it) }
                v.width?.let { addProperty("width", it) }
                v.height?.let { addProperty("height", it) }
            })
        }
        put("createdAt", s.createdAt)
        put("expiresAt", s.expiresAt)
        addProperty("seen", s.seen)
        s.place?.let { addProperty("place", it) }
        s.viewerCount?.let { addProperty("viewerCount", it) }
        s.visibility?.let { addProperty("visibility", it) }
        s.eventMessageId?.let { addProperty("eventMessageId", it) }
    }

    /** Body of `POST status/stories`. */
    fun storyBody(
        kind: String,
        text: String?,
        bg: String?,
        font: String?,
        mediaId: String?,
        thumbMediaId: String?,
        video: ChatVideo?,
        visibility: String,
        userIds: List<Long>,
        eventMessageId: Long?,
    ): JsonObject = JsonObject().apply {
        addProperty("kind", kind)
        text?.takeIf { it.isNotBlank() }?.let { addProperty("text", it.take(ChatStory.TEXT_MAX)) }
        bg?.let { color(it)?.let { c -> addProperty("bg", c) } }
        font?.takeIf { it in ChatStory.FONTS }?.let { addProperty("font", it) }
        mediaId?.let { addProperty("mediaId", it) }
        thumbMediaId?.let { addProperty("thumbMediaId", it) }
        video?.let { v ->
            add("video", JsonObject().apply {
                v.durationMs?.let { addProperty("durationMs", it) }
                v.width?.let { addProperty("width", it) }
                v.height?.let { addProperty("height", it) }
            })
        }
        addProperty("visibility", visibility)
        if (visibility == ChatStatus.VIS_CUSTOM) add("userIds", idsArray(userIds))
        eventMessageId?.let { addProperty("eventMessageId", it) }
    }

    private fun feedItem(o: JsonObject?, allowNoUser: Boolean = false): ChatFeedItem? {
        if (o == null) return null
        val user = ChatJson.person(o.obj("user")) ?: if (allowNoUser) ChatPerson(0, null, null, null, null) else return null
        val stories = o.arr("stories")?.mapNotNull { story(it.objOrNull()) }?.distinctBy { it.id }?.take(50).orEmpty()
        return ChatFeedItem(
            user = user,
            status = status(o.obj("status")),
            stories = stories.sortedBy { it.createdAt.orEmpty() },
            unseen = o.flag("unseen") ?: stories.any { !it.seen },
        )
    }

    /** `GET status/feed` → `{ items, me }`; unseen first, then the newest story. */
    fun feed(o: JsonObject): ChatStatusFeed? {
        val arr = o.arr("items") ?: return null
        val items = arr.mapNotNull { feedItem(it.objOrNull()) }.distinctBy { it.user.userId }.take(MAX_ITEMS)
        return ChatStatusFeed(ChatStatusLogic.order(items), feedItem(o.obj("me"), allowNoUser = true))
    }

    fun viewers(o: JsonObject): List<ChatStoryViewer>? {
        val arr = o.list("viewers", "items") ?: return null
        return arr.mapNotNull { e ->
            val v = e.objOrNull() ?: return@mapNotNull null
            val user = ChatJson.person(v.obj("user")) ?: ChatJson.person(v) ?: return@mapNotNull null
            ChatStoryViewer(user, ChatJson.time(v.str("seenAt")), v.str("reaction")?.takeIf { it.isNotBlank() && it.length <= 16 })
        }.distinctBy { it.user.userId }.take(MAX_ITEMS)
    }

    fun replyStory(o: JsonObject?): ChatReplyStory? {
        if (o == null) return null
        val id = pathKey(o.key("storyId")) ?: return null
        val p = o.obj("preview")
        return ChatReplyStory(
            storyId = id,
            ownerId = o.id("ownerId"),
            kind = p?.str("kind")?.takeIf { it in STORY_KINDS },
            text = ChatJson.text(p?.str("text"), ChatStory.SERVER_TEXT_MAX),
            thumbMediaId = ChatJson.mediaId(p?.str("thumbMediaId")),
        )
    }

    fun writeReplyStory(r: ChatReplyStory): JsonObject = JsonObject().apply {
        add("storyId", keyElement(r.storyId))
        put("ownerId", r.ownerId)
        add("preview", JsonObject().apply {
            put("kind", r.kind)
            put("text", r.text)
            put("thumbMediaId", r.thumbMediaId)
        })
    }

    // ─── preferences ────────────────────────────────────────────────

    private fun audience(value: String?, default: String) = value?.takeIf { it in ChatPrivacy.AUDIENCES } ?: default

    fun prefs14(o: JsonObject?): ChatPrefs14 {
        if (o == null) return ChatPrefs14()
        val p = o.obj("privacy")
        val n = o.obj("notify")
        val d = ChatPrivacy()
        val dn = ChatNotifyPrefs()
        return ChatPrefs14(
            privacy = if (p == null) d else ChatPrivacy(
                lastSeen = audience(p.str("lastSeen"), d.lastSeen),
                avatar = audience(p.str("avatar"), d.avatar),
                statusDefault = p.str("statusDefault")?.takeIf { it in ChatStatus.VISIBILITIES } ?: d.statusDefault,
                placeInStatus = audience(p.str("placeInStatus"), d.placeInStatus),
                groupAdd = audience(p.str("groupAdd"), d.groupAdd),
                defaultDisappearingS = p.int("defaultDisappearingS")?.takeIf { it >= 0 } ?: d.defaultDisappearingS,
            ),
            notify = if (n == null) dn else ChatNotifyPrefs(
                groupsMentionsOnly = n.flag("groupsMentionsOnly") ?: dn.groupsMentionsOnly,
                reactions = n.flag("reactions") ?: dn.reactions,
                statusDigest = n.flag("statusDigest") ?: dn.statusDigest,
                eventReminderMin = n.int("eventReminderMin")?.takeIf { it >= 0 } ?: dn.eventReminderMin,
            ),
            keepArchived = o.obj("chats")?.flag("keepArchived") ?: true,
        )
    }

    fun writePrivacy(p: ChatPrivacy): JsonObject = JsonObject().apply {
        addProperty("lastSeen", p.lastSeen)
        addProperty("avatar", p.avatar)
        addProperty("statusDefault", p.statusDefault)
        addProperty("placeInStatus", p.placeInStatus)
        addProperty("groupAdd", p.groupAdd)
        addProperty("defaultDisappearingS", p.defaultDisappearingS)
    }

    fun writeNotify(n: ChatNotifyPrefs): JsonObject = JsonObject().apply {
        addProperty("groupsMentionsOnly", n.groupsMentionsOnly)
        addProperty("reactions", n.reactions)
        addProperty("statusDigest", n.statusDigest)
        addProperty("eventReminderMin", n.eventReminderMin)
    }

    /** The 1.4 part of `prefs` (added to the 1.0 keys). */
    fun writePrefs14(into: JsonObject, p: ChatPrefs14): JsonObject = into.apply {
        add("privacy", writePrivacy(p.privacy))
        add("notify", writeNotify(p.notify))
        add("chats", JsonObject().apply { addProperty("keepArchived", p.keepArchived) })
    }

    fun usage(limits: JsonObject?): ChatUsage? {
        if (limits == null) return null
        val u = limits.obj("usage")
        val used = u?.long("usedBytes") ?: u?.long("bytes") ?: limits.long("usedBytes")
        val quota = u?.long("quotaBytes") ?: u?.long("quota") ?: limits.long("quotaBytes")
        return if (used == null && quota == null) null else ChatUsage(used, quota?.takeIf { it > 0 })
    }

    fun writeUsage(into: JsonObject, u: ChatUsage?) {
        if (u == null) return
        into.add("usage", JsonObject().apply {
            u.usedBytes?.let { addProperty("usedBytes", it) }
            u.quotaBytes?.let { addProperty("quotaBytes", it) }
        })
    }

    // ─── tools ──────────────────────────────────────────────────────

    fun lists(o: JsonObject): List<ChatList>? {
        val arr = o.list("lists", "items") ?: return null
        return arr.mapNotNull { e ->
            val l = e.objOrNull() ?: return@mapNotNull null
            val id = l.key("id") ?: return@mapNotNull null
            val name = ChatJson.line(l.str("name"), ChatList.NAME_MAX) ?: return@mapNotNull null
            ChatList(id, name, l.ids("conversationIds"))
        }.distinctBy { it.id }.take(ChatList.MAX)
    }

    /** Body of `PUT lists`. */
    fun listsBody(lists: List<ChatList>): JsonObject = JsonObject().apply {
        add("lists", JsonArray().apply {
            lists.take(ChatList.MAX).forEach { l ->
                add(JsonObject().apply {
                    // List ids are strings (`[A-Za-z0-9_-]{1,40}`), even when they look like numbers.
                    addProperty("id", l.id)
                    addProperty("name", l.name.take(ChatList.NAME_MAX))
                    add("conversationIds", idsArray(l.conversationIds.distinct()))
                })
            }
        })
    }

    fun reminders(o: JsonObject): List<ChatReminder>? {
        val arr = o.list("reminders", "items") ?: return null
        return arr.mapNotNull { e ->
            val r = e.objOrNull() ?: return@mapNotNull null
            val id = pathKey(r.key("id")) ?: return@mapNotNull null
            val at = ChatJson.time(r.str("at")) ?: return@mapNotNull null
            ChatReminder(id, r.id("messageId"), r.id("conversationId"), at, ChatJson.line(r.str("preview"), 200))
        }.distinctBy { it.id }.take(MAX_ITEMS)
    }

    fun suggestion(o: JsonObject): ChatSuggestion? {
        val items = o.arr("items")?.mapNotNull { e ->
            e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.let { ChatJson.line(it, 80) }
        }?.distinct()?.take(30)
        val dates = o.arr("dates")?.mapNotNull { e ->
            val d = e.objOrNull() ?: return@mapNotNull null
            val start = ChatJson.time(d.str("startsAt")) ?: return@mapNotNull null
            ChatSuggestedDate(start, ChatJson.line(d.str("label"), 60), ChatJson.line(d.str("title"), ChatEventInfo.TITLE_MAX))
        }?.take(10)
        if (items == null && dates == null) return null
        return ChatSuggestion(items.orEmpty(), dates.orEmpty())
    }

    fun places(o: JsonObject): List<ChatPlace>? {
        val arr = o.list("places", "items") ?: return null
        return arr.mapNotNull { e ->
            val p = e.objOrNull() ?: return@mapNotNull null
            val id = p.key("id") ?: return@mapNotNull null
            val name = ChatJson.line(p.str("name") ?: p.str("label"), 80) ?: return@mapNotNull null
            ChatPlace(id, name, lat(p), lon(p), p.int("radiusM")?.coerceIn(10, 100_000))
        }.distinctBy { it.id }.take(MAX_ITEMS)
    }

    fun homeActions(o: JsonObject): List<ChatHomeAction>? {
        val arr = o.list("actions", "items") ?: return null
        return arr.mapNotNull { e ->
            val a = e.objOrNull() ?: return@mapNotNull null
            val id = a.key("id") ?: return@mapNotNull null
            val label = ChatJson.line(a.str("label"), 80) ?: return@mapNotNull null
            ChatHomeAction(id, ChatJson.token(a.str("plugin")), label, icon(a.str("icon")), ChatJson.token(a.str("kind")), a.flag("needsConfirm") == true)
        }.distinctBy { it.id }.take(MAX_ITEMS)
    }

    fun blocks(o: JsonObject): List<ChatPerson>? {
        val arr = o.list("blocks", "users", "items") ?: return null
        return arr.mapNotNull { e ->
            val b = e.objOrNull() ?: return@mapNotNull null
            ChatJson.person(b.obj("user")) ?: ChatJson.person(b)
        }.distinctBy { it.userId }.take(MAX_ITEMS)
    }

    // ─── polls ──────────────────────────────────────────────────────

    fun poll(o: JsonObject?): ChatPoll? {
        if (o == null) return null
        val question = ChatJson.line(o.str("question"), ChatPoll.QUESTION_MAX) ?: return null
        val anonymous = o.flag("anonymous") == true
        val options = o.arr("options")?.mapNotNull { e ->
            val op = e.objOrNull() ?: return@mapNotNull null
            val id = op.key("id") ?: return@mapNotNull null
            val text = ChatJson.line(op.str("text"), ChatPoll.OPTION_MAX) ?: return@mapNotNull null
            val voters = if (anonymous || !op.has("voters")) null else op.ids("voters")
            ChatPollOption(id, text, op.int("count")?.coerceAtLeast(0) ?: voters?.size ?: 0, voters, op.id("addedBy"))
        }?.distinctBy { it.id }?.take(50).orEmpty()
        if (options.isEmpty()) return null
        val ids = options.map { it.id }.toSet()
        return ChatPoll(
            question = question,
            options = options,
            multi = o.flag("multi") == true,
            anonymous = anonymous,
            allowAdd = o.flag("allowAdd") == true,
            closesAt = ChatJson.time(o.str("closesAt")),
            closed = o.flag("closed") == true,
            voterCount = o.int("voterCount")?.coerceAtLeast(0) ?: 0,
            memberCount = o.int("memberCount")?.coerceAtLeast(0),
            myVotes = o.arr("myVotes")?.mapNotNull { keyOf(it) }?.filter { it in ids }?.distinct().orEmpty(),
            creatorId = o.id("creatorId"),
        )
    }

    fun writePoll(p: ChatPoll): JsonObject = JsonObject().apply {
        addProperty("question", p.question)
        add("options", JsonArray().apply {
            p.options.forEach { op ->
                add(JsonObject().apply {
                    add("id", keyElement(op.id))
                    addProperty("text", op.text)
                    addProperty("count", op.count)
                    op.voters?.let { add("voters", idsArray(it)) }
                    op.addedBy?.let { addProperty("addedBy", it) }
                })
            }
        })
        addProperty("multi", p.multi)
        addProperty("anonymous", p.anonymous)
        addProperty("allowAdd", p.allowAdd)
        put("closesAt", p.closesAt)
        addProperty("closed", p.closed)
        addProperty("voterCount", p.voterCount)
        p.memberCount?.let { addProperty("memberCount", it) }
        add("myVotes", JsonArray().apply { p.myVotes.forEach { add(keyElement(it)) } })
        p.creatorId?.let { addProperty("creatorId", it) }
    }

    /** `poll` of a send body. */
    fun pollSendBody(question: String, options: List<String>, multi: Boolean, anonymous: Boolean, allowAdd: Boolean, closesAt: String?): JsonObject =
        JsonObject().apply {
            addProperty("question", question.trim().take(ChatPoll.QUESTION_MAX))
            add("options", JsonArray().apply {
                options.map { it.trim().take(ChatPoll.OPTION_MAX) }.filter { it.isNotEmpty() }.take(ChatPoll.MAX_OPTIONS).forEach { add(it) }
            })
            addProperty("multi", multi)
            addProperty("anonymous", anonymous)
            addProperty("allowAdd", allowAdd)
            put("closesAt", closesAt)
        }

    fun voteBody(optionIds: List<String>): JsonObject = JsonObject().apply {
        add("optionIds", JsonArray().apply { optionIds.distinct().forEach { add(keyElement(it)) } })
    }

    // ─── events ─────────────────────────────────────────────────────

    fun place(o: JsonObject?): ChatEventPlace? {
        if (o == null) return null
        val label = ChatJson.line(o.str("label") ?: o.str("name"), 120) ?: return null
        return ChatEventPlace(o.key("familiePlaceId"), label, lat(o), lon(o), o.int("radiusM")?.coerceIn(10, 100_000))
    }

    fun writePlace(p: ChatEventPlace): JsonObject = JsonObject().apply {
        p.familiePlaceId?.let { add("familiePlaceId", keyElement(it)) }
        addProperty("label", p.label)
        p.lat?.let { addProperty("lat", it) }
        p.lon?.let { addProperty("lon", it) }
        p.radiusM?.let { addProperty("radiusM", it) }
    }

    private val RSVP_ANSWERS = setOf(ChatRsvp.YES, ChatRsvp.MAYBE, ChatRsvp.NO)

    private fun rsvpList(arr: JsonArray?): List<ChatRsvpEntry> =
        arr?.mapNotNull { e ->
            val r = e.objOrNull()
            when {
                r != null -> r.id("userId")?.let { ChatRsvpEntry(it, r.int("guests")?.coerceIn(0, 99) ?: 0) }
                else -> ChatJson.idOf(e)?.let { ChatRsvpEntry(it, 0) }
            }
        }?.distinctBy { it.userId }?.take(1000).orEmpty()

    fun rsvp(o: JsonObject?): ChatRsvp? {
        if (o == null) return null
        return ChatRsvp(
            yes = rsvpList(o.arr("yes")),
            maybe = rsvpList(o.arr("maybe")),
            no = rsvpList(o.arr("no")),
            mine = o.str("mine")?.takeIf { it in RSVP_ANSWERS },
            myGuests = o.int("myGuests")?.coerceIn(0, 99) ?: 0,
        )
    }

    private fun writeRsvpList(list: List<ChatRsvpEntry>) = JsonArray().apply {
        list.forEach { r -> add(JsonObject().apply { addProperty("userId", r.userId); addProperty("guests", r.guests) }) }
    }

    fun eventInfo(o: JsonObject?): ChatEventInfo? {
        if (o == null) return null
        val title = ChatJson.line(o.str("title"), ChatEventInfo.TITLE_MAX) ?: return null
        val starts = ChatJson.time(o.str("startsAt")) ?: return null
        val checklist = o.arr("checklist")?.mapIndexedNotNull { i, e ->
            val c = e.objOrNull()
            if (c == null) {
                // A plain text (as sent) before the server gave the items ids.
                e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.let { ChatJson.line(it, 200) }
                    ?.let { ChatChecklistItem("i$i", it, null) }
            } else {
                val id = c.key("id") ?: return@mapIndexedNotNull null
                val text = ChatJson.line(c.str("text"), 200) ?: return@mapIndexedNotNull null
                ChatChecklistItem(id, text, c.id("doneBy"))
            }
        }?.distinctBy { it.id }?.take(ChatEventInfo.CHECKLIST_MAX).orEmpty()
        return ChatEventInfo(
            title = title,
            startsAt = starts,
            endsAt = ChatJson.time(o.str("endsAt")),
            allDay = o.flag("allDay") == true,
            place = place(o.obj("place")),
            description = ChatJson.text(o.str("description"), ChatEventInfo.DESCRIPTION_MAX),
            checklist = checklist,
            // The server always sends the answers; `rsvpEnabled: false` = the event asks for none.
            rsvp = if (o.flag("rsvpEnabled") == false) null else rsvp(o.obj("rsvp")),
            guests = o.flag("guests") == true,
            reminderMin = o.int("reminderMin")?.coerceAtLeast(0) ?: 0,
            familyCalendar = o.flag("familyCalendar") == true,
            departureHint = o.flag("departureHint") == true,
            cancelled = o.flag("cancelled") == true,
            creatorId = o.id("creatorId"),
        )
    }

    fun writeEventInfo(e: ChatEventInfo): JsonObject = JsonObject().apply {
        addProperty("title", e.title)
        addProperty("startsAt", e.startsAt)
        put("endsAt", e.endsAt)
        addProperty("allDay", e.allDay)
        add("place", e.place?.let { writePlace(it) } ?: JsonNull.INSTANCE)
        put("description", e.description)
        add("checklist", JsonArray().apply {
            e.checklist.forEach { c ->
                add(JsonObject().apply {
                    add("id", keyElement(c.id))
                    addProperty("text", c.text)
                    c.doneBy?.let { addProperty("doneBy", it) }
                })
            }
        })
        add("rsvp", e.rsvp?.let { r ->
            JsonObject().apply {
                add("yes", writeRsvpList(r.yes))
                add("maybe", writeRsvpList(r.maybe))
                add("no", writeRsvpList(r.no))
                put("mine", r.mine)
                addProperty("myGuests", r.myGuests)
            }
        } ?: JsonNull.INSTANCE)
        addProperty("rsvpEnabled", e.rsvp != null)
        e.creatorId?.let { addProperty("creatorId", it) }
        addProperty("guests", e.guests)
        addProperty("reminderMin", e.reminderMin)
        addProperty("familyCalendar", e.familyCalendar)
        addProperty("departureHint", e.departureHint)
        addProperty("cancelled", e.cancelled)
    }

    /** `event` of a send body. */
    fun eventSendBody(
        title: String,
        startsAt: String,
        endsAt: String?,
        allDay: Boolean,
        place: ChatEventPlace?,
        description: String?,
        checklist: List<String>,
        rsvp: Boolean,
        guests: Boolean,
        reminderMin: Int,
        familyCalendar: Boolean,
        departureHint: Boolean,
    ): JsonObject = JsonObject().apply {
        addProperty("title", title.trim().take(ChatEventInfo.TITLE_MAX))
        addProperty("startsAt", startsAt)
        endsAt?.let { addProperty("endsAt", it) }
        if (allDay) addProperty("allDay", true)
        place?.let { add("place", writePlace(it)) }
        description?.trim()?.takeIf { it.isNotEmpty() }?.let { addProperty("description", it.take(ChatEventInfo.DESCRIPTION_MAX)) }
        val items = checklist.map { it.trim().take(200) }.filter { it.isNotEmpty() }.take(ChatEventInfo.CHECKLIST_MAX)
        if (items.isNotEmpty()) add("checklist", JsonArray().apply { items.forEach { add(it) } })
        addProperty("rsvp", rsvp)
        addProperty("guests", guests && rsvp)
        addProperty("reminderMin", reminderMin)
        addProperty("familyCalendar", familyCalendar)
        addProperty("departureHint", departureHint)
    }

    fun nextEvent(o: JsonObject?): ChatNextEvent? {
        if (o == null) return null
        val id = o.id("messageId") ?: return null
        val title = ChatJson.line(o.str("title"), ChatEventInfo.TITLE_MAX) ?: return null
        val starts = ChatJson.time(o.str("startsAt")) ?: return null
        return ChatNextEvent(id, title, starts, o.int("yesCount")?.coerceAtLeast(0) ?: 0)
    }

    fun writeNextEvent(n: ChatNextEvent): JsonObject = JsonObject().apply {
        addProperty("messageId", n.messageId)
        addProperty("title", n.title)
        addProperty("startsAt", n.startsAt)
        addProperty("yesCount", n.yesCount)
    }

    // ─── arrival ────────────────────────────────────────────────────

    private val ARRIVAL_STATES = setOf(ChatArrival.STATE_LIVE, ChatArrival.STATE_ARRIVED, ChatArrival.STATE_STOPPED)

    fun arrival(o: JsonObject?): ChatArrival? {
        if (o == null) return null
        val dest = place(o.obj("dest")) ?: return null
        val pos = o.obj("position")
        val onArrive = o.obj("onArrive")
        return ChatArrival(
            dest = dest,
            state = o.str("state")?.takeIf { it in ARRIVAL_STATES } ?: ChatArrival.STATE_STOPPED,
            etaAt = ChatJson.time(o.str("etaAt")),
            distanceM = o.int("distanceM")?.coerceAtLeast(0),
            progress = o.double("progress")?.coerceIn(0.0, 1.0)?.toFloat(),
            lastAt = ChatJson.time(o.str("lastAt")),
            lat = pos?.let { lat(it) },
            lon = pos?.let { lon(it) },
            onArriveActionId = onArrive?.key("actionId"),
            onArriveLabel = ChatJson.line(onArrive?.str("label"), 80),
        )
    }

    fun writeArrival(a: ChatArrival): JsonObject = JsonObject().apply {
        add("dest", writePlace(a.dest))
        addProperty("state", a.state)
        put("etaAt", a.etaAt)
        put("distanceM", a.distanceM)
        put("progress", a.progress?.toDouble())
        put("lastAt", a.lastAt)
        if (a.lat != null && a.lon != null) add("position", JsonObject().apply { addProperty("lat", a.lat); addProperty("lon", a.lon) })
        if (a.onArriveActionId != null) {
            add("onArrive", JsonObject().apply {
                add("actionId", keyElement(a.onArriveActionId))
                a.onArriveLabel?.let { addProperty("label", it) }
            })
        }
    }

    /** `arrival` of a send body. */
    fun arrivalSendBody(dest: ChatEventPlace, onArriveActionId: String?): JsonObject = JsonObject().apply {
        add("dest", writePlace(dest.copy(radiusM = dest.radiusM ?: ChatArrival.DEFAULT_RADIUS_M)))
        onArriveActionId?.let { add("onArrive", JsonObject().apply { add("actionId", keyElement(it)) }) }
    }

    /** Body of `POST messages/:id/arrival`. */
    fun positionBody(lat: Double, lon: Double, accuracy: Float?, speedMps: Float?): JsonObject = JsonObject().apply {
        addProperty("lat", lat)
        addProperty("lon", lon)
        accuracy?.takeIf { it.isFinite() && it >= 0 }?.let { addProperty("accuracy", it.toInt()) }
        speedMps?.takeIf { it.isFinite() && it >= 0 }?.let { addProperty("speedMps", it) }
    }

    // ─── actions and cards ──────────────────────────────────────────

    private val ACTION_MODES = setOf(ChatActionCard.MODE_RUN, ChatActionCard.MODE_REQUEST, ChatActionCard.MODE_GRANT)
    private val ACTION_STATES = setOf(ChatActionCard.STATE_OPEN, ChatActionCard.STATE_DONE, ChatActionCard.STATE_DENIED, ChatActionCard.STATE_EXPIRED)

    fun action(o: JsonObject?): ChatActionCard? {
        if (o == null) return null
        val mode = o.str("mode")?.takeIf { it in ACTION_MODES } ?: return null
        val actionId = o.key("actionId") ?: return null
        val grant = o.obj("grant")
        val resultObj = o.obj("result")
        val result = o.get("result")?.let { r ->
            when {
                r.isJsonPrimitive && r.asJsonPrimitive.isString -> ChatJson.line(r.asString, 200)
                r.isJsonObject -> ChatJson.line(r.asJsonObject.str("message") ?: r.asJsonObject.str("text"), 200)
                else -> null
            }
        }
        val can = o.obj("can")
        return ChatActionCard(
            mode = mode,
            actionId = actionId,
            label = ChatJson.line(o.str("label"), 80) ?: actionId,
            icon = icon(o.str("icon")),
            plugin = ChatJson.token(o.str("plugin")),
            approverIds = o.ids("approverIds"),
            grantTo = grant?.id("toUserId"),
            grantUntil = ChatJson.time(grant?.str("until")),
            state = o.str("state")?.takeIf { it in ACTION_STATES } ?: ChatActionCard.STATE_OPEN,
            result = result,
            by = o.id("by"),
            needsConfirm = o.flag("needsConfirm") == true,
            canRun = can?.flag("run") == true,
            canApprove = can?.flag("approve") == true,
            canDeny = can?.flag("deny") == true,
            resultOk = resultObj?.flag("ok"),
        )
    }

    fun writeAction(a: ChatActionCard): JsonObject = JsonObject().apply {
        addProperty("mode", a.mode)
        add("actionId", keyElement(a.actionId))
        addProperty("label", a.label)
        put("icon", a.icon)
        put("plugin", a.plugin)
        if (a.approverIds.isNotEmpty()) add("approverIds", idsArray(a.approverIds))
        if (a.grantTo != null || a.grantUntil != null) {
            add("grant", JsonObject().apply {
                put("toUserId", a.grantTo)
                put("until", a.grantUntil)
            })
        }
        addProperty("state", a.state)
        if (a.result != null || a.resultOk != null) {
            add("result", JsonObject().apply {
                a.resultOk?.let { addProperty("ok", it) }
                put("message", a.result)
            })
        }
        put("by", a.by)
        addProperty("needsConfirm", a.needsConfirm)
        add("can", JsonObject().apply {
            addProperty("run", a.canRun)
            addProperty("approve", a.canApprove)
            addProperty("deny", a.canDeny)
        })
    }

    /** `action` of a send body: run / request / grant (to [grantTo] until [until]); [plugin] of the action. */
    fun actionSendBody(mode: String, actionId: String, grantTo: Long? = null, until: String? = null, plugin: String? = null): JsonObject = JsonObject().apply {
        addProperty("mode", mode)
        addProperty("actionId", actionId)
        plugin?.let { addProperty("plugin", it) }
        if (mode == ChatActionCard.MODE_GRANT && grantTo != null) {
            add("grant", JsonObject().apply {
                addProperty("toUserId", grantTo)
                put("until", until)
            })
        }
    }

    private val ROUTE_RE = Regex("^(?:vpn|services|gateways|inbox|plg-[a-z0-9]+(?:-[a-z0-9]+)*)$")
    private val BASE64_RE = Regex("^[A-Za-z0-9+/]+={0,2}$")
    private val IMAGE_MIMES = setOf("image/jpeg", "image/webp", "image/png")
    /** 300 KB as base64. */
    private const val IMAGE_B64_MAX = 410_000

    private fun route(value: String?): String? = value?.takeIf { ROUTE_RE.matches(it) }

    fun card(o: JsonObject?, messageMediaId: String? = null): ChatPluginCard? {
        if (o == null) return null
        val buttons = (o.arr("buttons") ?: o.arr("actions"))?.mapNotNull { e ->
            val b = e.objOrNull()
            if (b == null) {
                keyOf(e)?.let { ChatCardButton(it, null, null) }
            } else {
                val id = b.key("actionId") ?: b.key("id")
                val r = route(b.str("route"))
                if (id == null && r == null) null
                else ChatCardButton(
                    actionId = id,
                    label = ChatJson.line(b.str("label"), 40),
                    icon = icon(b.str("icon")),
                    route = r,
                    plugin = ChatJson.token(b.str("plugin")),
                    needsConfirm = b.flag("needsConfirm") == true,
                    canRun = b.flag("canRun") ?: true,
                )
            }
        }?.take(4).orEmpty()
        // `link { label, route }`: a button that opens a screen of the app ("Live-Bild").
        val link = o.obj("link")?.let { l -> route(l.str("route"))?.let { r -> ChatCardButton(null, ChatJson.line(l.str("label"), 24), null, r) } }
        val image = o.obj("image")
        val b64 = image?.str("data")?.takeIf { it.length <= IMAGE_B64_MAX && BASE64_RE.matches(it) }
        val lastRun = o.obj("lastRun")
        return ChatPluginCard(
            kind = ChatJson.token(o.str("kind")) ?: "notice",
            title = ChatJson.line(o.str("title"), 120),
            text = ChatJson.text(o.str("text"), 1000),
            imageMediaId = ChatJson.mediaId(o.str("imageMediaId")) ?: ChatJson.mediaId(o.str("mediaId")) ?: messageMediaId,
            buttons = buttons + listOfNotNull(link),
            imageBase64 = b64,
            imageMime = image?.str("mime")?.takeIf { b64 != null && it in IMAGE_MIMES },
            lastRunBy = lastRun?.id("by"),
            lastRunOk = lastRun?.flag("ok"),
        )
    }

    fun writeCard(c: ChatPluginCard): JsonObject = JsonObject().apply {
        addProperty("kind", c.kind)
        put("title", c.title)
        put("text", c.text)
        put("imageMediaId", c.imageMediaId)
        add("buttons", JsonArray().apply {
            c.buttons.forEach { b ->
                add(JsonObject().apply {
                    b.actionId?.let { add("actionId", keyElement(it)) }
                    b.label?.let { addProperty("label", it) }
                    b.icon?.let { addProperty("icon", it) }
                    b.route?.let { addProperty("route", it) }
                    b.plugin?.let { addProperty("plugin", it) }
                    addProperty("needsConfirm", b.needsConfirm)
                    addProperty("canRun", b.canRun)
                })
            }
        })
        if (c.imageBase64 != null) add("image", JsonObject().apply { put("mime", c.imageMime); addProperty("data", c.imageBase64) })
        if (c.lastRunBy != null || c.lastRunOk != null) {
            add("lastRun", JsonObject().apply {
                put("by", c.lastRunBy)
                c.lastRunOk?.let { addProperty("ok", it) }
            })
        }
    }

    /** Body of `POST messages/:id/action/run` (a card's button names its action and plugin). */
    fun actionRunBody(actionId: String?, plugin: String? = null): JsonObject = JsonObject().apply {
        actionId?.let { addProperty("actionId", it) }
        plugin?.let { addProperty("plugin", it) }
    }

    /** `GET conversations/:id/export` → one part (newest first parts; `before` asks for the older one). */
    fun exportPart(o: JsonObject): ChatExportPart? {
        val text = o.str("text") ?: return null
        return ChatExportPart(
            text = text,
            fileName = ChatJson.line(o.str("fileName"), 120),
            truncated = o.flag("truncated") == true,
            before = o.id("before"),
        )
    }

    /** `POST messages/:id/arrival` → whether the destination was reached. */
    fun arrived(o: JsonObject): Boolean = o.flag("arrived") == true
}

/** Pure decisions of status and stories. Unit-tested. */
object ChatStatusLogic {

    /** Unseen first, then by the newest story (people without stories last, by name). */
    fun order(items: List<ChatFeedItem>): List<ChatFeedItem> =
        items.sortedWith(
            compareByDescending<ChatFeedItem> { it.unseen }
                .thenByDescending { it.stories.maxOfOrNull { s -> s.createdAt.orEmpty() }.orEmpty() }
                .thenBy { it.user.name.orEmpty().lowercase() },
        )

    /** People shown in the strip: with stories or a status. */
    fun strip(feed: ChatStatusFeed?): List<ChatFeedItem> =
        feed?.items.orEmpty().filter { it.stories.isNotEmpty() || it.status != null }

    /** The first story of [item] to show: the oldest unseen, else the first. */
    fun startIndex(item: ChatFeedItem): Int = item.stories.indexOfFirst { !it.seen }.takeIf { it >= 0 } ?: 0

    /** A status still applies at [nowMs] (no `until` or not passed). */
    fun active(status: ChatStatus?, nowMs: Long): Boolean {
        if (status == null) return false
        val until = ChatTimeline.epochMs(status.until) ?: return true
        return until > nowMs
    }

    /** [feed] with story [storyId] of [userId] marked seen (and `unseen` recomputed). */
    fun markSeen(feed: ChatStatusFeed, userId: Long, storyId: String): ChatStatusFeed {
        val items = feed.items.map { item ->
            if (item.user.userId != userId) item
            else {
                val stories = item.stories.map { if (it.id == storyId) it.copy(seen = true) else it }
                item.copy(stories = stories, unseen = stories.any { !it.seen })
            }
        }
        return feed.copy(items = items)
    }

    /**
     * End times of the "Löschen nach" choices: 30 min, 1 h, a time today
     * ([endOfWorkMs], e.g. 17:00 — when still ahead), end of today, never.
     */
    fun untilMs(choice: String, nowMs: Long, endOfDayMs: Long, endOfWorkMs: Long?): Long? = when (choice) {
        UNTIL_30M -> nowMs + 30 * 60_000L
        UNTIL_1H -> nowMs + 60 * 60_000L
        UNTIL_WORK -> endOfWorkMs?.takeIf { it > nowMs } ?: endOfDayMs
        UNTIL_TODAY -> endOfDayMs
        else -> null
    }

    const val UNTIL_30M = "30m"
    const val UNTIL_1H = "1h"
    const val UNTIL_WORK = "work"
    const val UNTIL_TODAY = "today"
    const val UNTIL_NEVER = "never"
    val UNTIL_CHOICES = listOf(UNTIL_30M, UNTIL_1H, UNTIL_WORK, UNTIL_TODAY, UNTIL_NEVER)
}
