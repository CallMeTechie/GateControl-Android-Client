package com.gatecontrol.android.chat

import com.gatecontrol.android.chat.ChatJson.arr
import com.gatecontrol.android.chat.ChatJson.flag
import com.gatecontrol.android.chat.ChatJson.id
import com.gatecontrol.android.chat.ChatJson.int
import com.gatecontrol.android.chat.ChatJson.obj
import com.gatecontrol.android.chat.ChatJson.objOrNull
import com.gatecontrol.android.chat.ChatJson.str
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject

/*
 * Messenger 1.21 (Chats v2, Teil B, `features.chat_people`): persons — the chat with a GateControl account and the
 * WhatsApp chat with the same human (later Signal, Telegram) as one entry with a shared history, only in the view of
 * the person who merged them. With the word the app sends `?people=1` with every call; the list then holds one entry
 * per person (the view of its lead chat with `person`), the other ways leave the list (`folded`) and stay in the cache
 * with their messages. The way of an answer is never chosen here: the server names it (`person.reply`). Pure,
 * unit-tested; the screens draw what these say.
 */

/** What a way allows for the composer (`ways[].can`). */
data class ChatWayCan(val send: Boolean, val sendReason: String? = null, val react: Boolean = true)

/** One way of a person: a conversation — [way] `gatecontrol` or the bridge's network. */
data class ChatPersonWay(
    val way: String,
    val label: String,
    val conversationId: Long,
    val title: String?,
    val avatarUrl: String? = null,
    val avatarMediaId: String? = null,
    val unread: Int = 0,
    val muted: Boolean = false,
    val archived: Boolean = false,
    val lastMessageAt: String? = null,
    val bridge: ChatConvBridge? = null,
    val can: ChatWayCan = ChatWayCan(send = true),
) {
    val gatecontrol: Boolean get() = way == ChatPeople.WAY_GC
}

/**
 * `person.reply`: the way an answer takes right now (the server chooses it). Messenger 1.27: [fallback] — the chosen way
 * (`replyPref`) cannot send just now, the answer goes over GateControl.
 */
data class ChatPersonReply(val way: String, val label: String, val conversationId: Long, val fallback: Boolean = false)

/**
 * `person` of a list entry: [conversationId] the lead chat (the entry's id), [nameFrom] `account` ([nameFromAccount])
 * or the conversation whose name holds ([nameFromId]), every name ([names] — the list's search finds each), the ways
 * (GateControl first) and the way of an answer.
 */
data class ChatMergedPerson(
    val id: Long,
    val name: String?,
    val nameFromAccount: Boolean,
    val nameFromId: Long?,
    val userId: Long?,
    val conversationId: Long,
    val names: List<String>,
    val ways: List<ChatPersonWay>,
    val reply: ChatPersonReply?,
    val createdAt: String? = null,
    /** Messenger 1.27 „Nachrichten senden über“: `auto`, `gatecontrol` or a service (`whatsapp`). */
    val replyPref: String = ChatPeople.REPLY_AUTO,
) {
    /** The conversations of all ways. */
    val wayIds: List<Long> get() = ways.map { it.conversationId }

    fun way(conversationId: Long): ChatPersonWay? = ways.firstOrNull { it.conversationId == conversationId }

    /** The kinds of ways, each once, in their order (`gatecontrol`, `whatsapp`, …) — the logos and the source chips. */
    val kinds: List<ChatPersonWay> get() = ways.distinctBy { it.way }
}

/** A suggestion of `GET persons/suggestions`: a bridged chat that is perhaps an account here. */
data class ChatPersonSuggestion(
    val id: String,
    val conversationId: Long,
    val userId: Long,
    val network: String,
    val label: String,
    val strong: Boolean,
    val via: String?,
    val contactName: String?,
    val contactAvatarUrl: String?,
    val contactAvatarMediaId: String?,
    val accountName: String?,
    val accountAvatarUrl: String?,
    val accountInitials: String?,
    val accountColorHex: String?,
    val text: String,
    val reason: String?,
)

/** An account of `GET persons/candidates` (only while the chat or its person has none). */
data class ChatCandidateAccount(val userId: Long, val name: String?, val avatarUrl: String?, val initials: String?, val colorHex: String?, val personId: Long?)

/** A bridged direct chat of `GET persons/candidates`. */
data class ChatCandidateChat(
    val conversationId: Long,
    val title: String?,
    val network: String,
    val label: String,
    val avatarUrl: String?,
    val avatarMediaId: String?,
    val personId: Long?,
)

data class ChatPersonCandidates(val accounts: List<ChatCandidateAccount>, val chats: List<ChatCandidateChat>)

/** `DELETE persons/:id/ways/:conversationId`: the person as it stands now (null: dissolved) and the entries to store. */
data class ChatPersonSplit(val person: ChatMergedPerson?, val conversations: List<ChatConversation>)

/** `POST persons/:id/read`: everything unread and each way's mark. */
data class ChatPersonRead(val unread: Int, val ways: List<Triple<Long, Long?, Int>>)

/** `GET bridges` → `recognition`: may others on this server recognise the person by the own WhatsApp? */
data class ChatRecognition(val available: Boolean, val network: String, val asked: Boolean, val allowed: Boolean) {
    /** Asked once right after the link (`available && !asked`). */
    val ask: Boolean get() = available && !asked
}

/** The name choice of the dialog „Zu einer Person zusammenführen?“: `account` or a conversation's id. */
data class ChatNameOption(val value: String, val label: String)

object ChatPeople {
    /** `features.chat_people` of `GET me` (Messenger 1.21). */
    const val FEATURE = "chat_people"

    /** The mark of a client that knows persons (`?people=1`). */
    const val QUERY = "people"

    const val WAY_GC = "gatecontrol"
    const val NAME_ACCOUNT = "account"

    /** Messenger 1.27 `replyPref`: the server picks the way (that of the newest message of the person). */
    const val REPLY_AUTO = "auto"

    /** `409 cannot_merge` (`reason`: group | channel | not_direct | two_accounts | one_way). */
    const val CANNOT_MERGE = "cannot_merge"

    /** `conversation.update { reason: "person" }`: merged, renamed, separated — the list loads anew. */
    const val REASON_PERSON = "person"

    /** Suggestions are asked for when „Chats“ opens and then at most every 15 minutes. */
    const val SUGGEST_EVERY_MS = 15 * 60_000L

    const val ACTION_LATER = "later"
    const val ACTION_NEVER = "never"

    private const val WAYS_MAX = 6
    private const val NAMES_MAX = 8
    private val WAY_RE = Regex("^[a-z][a-z0-9_-]{0,31}$")
    private val SUGGESTION_ID_RE = Regex("^[1-9][0-9]{0,14}-[1-9][0-9]{0,14}$")

    fun on(features: ChatFeatures?): Boolean = features?.tokens?.contains(FEATURE) == true

    // ─── parsing ────────────────────────────────────────────────────

    private fun wayOf(o: JsonObject?): ChatPersonWay? {
        if (o == null) return null
        val way = o.str("way")?.takeIf { WAY_RE.matches(it) } ?: return null
        val cid = o.id("conversationId") ?: return null
        val can = o.obj("can")
        return ChatPersonWay(
            way = way,
            label = ChatJson.line(o.str("label"), 40) ?: labelOf(way),
            conversationId = cid,
            title = ChatJson.line(o.str("title"), 120),
            avatarUrl = ChatJson.avatarPath(o.str("avatarUrl")),
            avatarMediaId = ChatJson.mediaId(o.str("avatarMediaId")),
            unread = o.int("unread")?.coerceAtLeast(0) ?: 0,
            muted = o.flag("muted") == true,
            archived = o.flag("archived") == true,
            lastMessageAt = ChatJson.time(o.str("lastMessageAt")),
            bridge = ChatBridgeChat.conversation(o.obj("bridge")),
            can = ChatWayCan(
                send = can?.flag("send") ?: true,
                sendReason = can?.str("sendReason")?.takeIf { it == ChatBridgeChat.READ_ONLY_CHANNEL || it == ChatBridgeChat.READ_ONLY_ADMINS },
                react = can?.flag("react") ?: true,
            ),
        )
    }

    /** `person` of a conversation; anything without an id, a lead and a way → none (a chat as before). */
    fun person(o: JsonObject?): ChatMergedPerson? {
        if (o == null) return null
        val id = o.id("id") ?: return null
        val lead = o.id("conversationId") ?: return null
        val ways = o.arr("ways")?.mapNotNull { wayOf(it.objOrNull()) }?.distinctBy { it.conversationId }?.take(WAYS_MAX).orEmpty()
        if (ways.isEmpty()) return null
        val nameFrom = o.get("nameFrom")
        val fromAccount = nameFrom != null && nameFrom.isJsonPrimitive && nameFrom.asJsonPrimitive.isString && nameFrom.asString == NAME_ACCOUNT
        val reply = o.obj("reply")?.let { r ->
            val w = r.str("way")?.takeIf { WAY_RE.matches(it) } ?: return@let null
            val cid = r.id("conversationId") ?: return@let null
            ChatPersonReply(w, ChatJson.line(r.str("label"), 40) ?: labelOf(w), cid, fallback = r.flag("fallback") == true)
        }
        return ChatMergedPerson(
            id = id,
            name = ChatJson.line(o.str("name"), 120),
            nameFromAccount = fromAccount,
            nameFromId = if (fromAccount) null else ChatJson.idOf(nameFrom),
            userId = o.id("userId"),
            conversationId = lead,
            names = o.arr("names")?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.let { ChatJson.line(it, 120) } }
                ?.distinct()?.take(NAMES_MAX).orEmpty(),
            // GateControl first, then the bridged ways (as the server orders them)
            ways = ways.sortedBy { if (it.gatecontrol) 0 else 1 },
            reply = reply?.takeIf { r -> ways.any { it.conversationId == r.conversationId } },
            createdAt = ChatJson.time(o.str("createdAt")),
            replyPref = o.str("replyPref")?.takeIf { WAY_RE.matches(it) && (it == REPLY_AUTO || ways.any { w -> w.way == it }) } ?: REPLY_AUTO,
        )
    }

    fun write(p: ChatMergedPerson): JsonObject = JsonObject().apply {
        addProperty("id", p.id)
        if (p.name == null) add("name", JsonNull.INSTANCE) else addProperty("name", p.name)
        when {
            p.nameFromAccount -> addProperty("nameFrom", NAME_ACCOUNT)
            p.nameFromId != null -> addProperty("nameFrom", p.nameFromId)
            else -> add("nameFrom", JsonNull.INSTANCE)
        }
        if (p.userId == null) add("userId", JsonNull.INSTANCE) else addProperty("userId", p.userId)
        addProperty("conversationId", p.conversationId)
        add("names", JsonArray().apply { p.names.forEach { add(it) } })
        add("ways", JsonArray().apply {
            p.ways.forEach { w ->
                add(JsonObject().apply {
                    addProperty("way", w.way)
                    addProperty("label", w.label)
                    addProperty("conversationId", w.conversationId)
                    w.title?.let { addProperty("title", it) }
                    w.avatarUrl?.let { addProperty("avatarUrl", it) }
                    w.avatarMediaId?.let { addProperty("avatarMediaId", it) }
                    addProperty("unread", w.unread)
                    addProperty("muted", w.muted)
                    addProperty("archived", w.archived)
                    w.lastMessageAt?.let { addProperty("lastMessageAt", it) }
                    add("bridge", w.bridge?.let { ChatBridgeChat.write(it) } ?: JsonNull.INSTANCE)
                    add("can", JsonObject().apply {
                        addProperty("send", w.can.send)
                        w.can.sendReason?.let { addProperty("sendReason", it) }
                        addProperty("react", w.can.react)
                    })
                })
            }
        })
        add("reply", p.reply?.let { r ->
            JsonObject().apply {
                addProperty("way", r.way)
                addProperty("label", r.label)
                addProperty("conversationId", r.conversationId)
                if (r.fallback) addProperty("fallback", true)
            }
        } ?: JsonNull.INSTANCE)
        p.createdAt?.let { addProperty("createdAt", it) }
        addProperty("replyPref", p.replyPref)
    }

    /** `{ conversation, person }` of `GET persons/:id`, a merge, a change → the list entry. */
    fun entryOf(o: JsonObject): ChatConversation? {
        val c = ChatJson.conversationOf(o) ?: return null
        return if (c.person == null) person(o.obj("person"))?.let { c.copy(person = it) } ?: c else c
    }

    fun suggestions(o: JsonObject): List<ChatPersonSuggestion>? {
        val arr = o.arr("suggestions") ?: return null
        return arr.mapNotNull { e ->
            val s = e.objOrNull() ?: return@mapNotNull null
            val id = s.str("id")?.takeIf { SUGGESTION_ID_RE.matches(it) } ?: return@mapNotNull null
            val network = s.str("network")?.takeIf { WAY_RE.matches(it) } ?: return@mapNotNull null
            val text = ChatJson.line(s.str("text"), 300) ?: return@mapNotNull null
            val contact = s.obj("contact")
            val account = s.obj("account")
            ChatPersonSuggestion(
                id = id,
                conversationId = s.id("conversationId") ?: return@mapNotNull null,
                userId = s.id("userId") ?: return@mapNotNull null,
                network = network,
                label = ChatJson.line(s.str("label"), 40) ?: labelOf(network),
                strong = s.str("strength") == "strong",
                via = ChatJson.token(s.str("via")),
                contactName = ChatJson.line(contact?.str("name"), 120),
                contactAvatarUrl = ChatJson.avatarPath(contact?.str("avatarUrl")),
                contactAvatarMediaId = ChatJson.mediaId(contact?.str("avatarMediaId")),
                accountName = ChatJson.line(account?.str("name"), 120),
                accountAvatarUrl = ChatJson.avatarPath(account?.str("avatarUrl")),
                accountInitials = com.gatecontrol.android.profile.AvatarInitials.clean(ChatJson.line(account?.str("initials"), 40)),
                accountColorHex = account?.str("colorHex")?.takeIf { COLOR_RE.matches(it) },
                text = text,
                reason = ChatJson.line(s.str("reason"), 400),
            )
        }.distinctBy { it.id }.sortedBy { if (it.strong) 0 else 1 }
    }

    private val COLOR_RE = Regex("^#[0-9a-fA-F]{6}$")

    fun candidates(o: JsonObject): ChatPersonCandidates? {
        val accounts = o.arr("accounts") ?: return null
        val chats = o.arr("chats") ?: return null
        return ChatPersonCandidates(
            accounts = accounts.mapNotNull { e ->
                val a = e.objOrNull() ?: return@mapNotNull null
                ChatCandidateAccount(
                    userId = a.id("userId") ?: return@mapNotNull null,
                    name = ChatJson.line(a.str("name"), 120),
                    avatarUrl = ChatJson.avatarPath(a.str("avatarUrl")),
                    initials = com.gatecontrol.android.profile.AvatarInitials.clean(ChatJson.line(a.str("initials"), 40)),
                    colorHex = a.str("colorHex")?.takeIf { COLOR_RE.matches(it) },
                    personId = a.id("personId"),
                )
            }.distinctBy { it.userId },
            chats = chats.mapNotNull { e ->
                val c = e.objOrNull() ?: return@mapNotNull null
                val network = c.str("network")?.takeIf { WAY_RE.matches(it) } ?: return@mapNotNull null
                ChatCandidateChat(
                    conversationId = c.id("conversationId") ?: return@mapNotNull null,
                    title = ChatJson.line(c.str("title"), 120),
                    network = network,
                    label = ChatJson.line(c.str("label"), 40) ?: labelOf(network),
                    avatarUrl = ChatJson.avatarPath(c.str("avatarUrl")),
                    avatarMediaId = ChatJson.mediaId(c.str("avatarMediaId")),
                    personId = c.id("personId"),
                )
            }.distinctBy { it.conversationId },
        )
    }

    fun split(o: JsonObject): ChatPersonSplit? {
        val arr = o.arr("conversations") ?: return null
        return ChatPersonSplit(
            person = person(o.obj("person")),
            conversations = arr.mapNotNull { ChatJson.conversation(it.objOrNull()) }.distinctBy { it.id },
        )
    }

    fun read(o: JsonObject): ChatPersonRead? {
        val unread = o.int("unread") ?: return null
        return ChatPersonRead(
            unread = unread.coerceAtLeast(0),
            ways = o.arr("ways")?.mapNotNull { e ->
                val w = e.objOrNull() ?: return@mapNotNull null
                Triple(w.id("conversationId") ?: return@mapNotNull null, w.id("lastReadId"), w.int("unread")?.coerceAtLeast(0) ?: 0)
            }.orEmpty(),
        )
    }

    /** `recognition` of `GET bridges` / `PUT bridges/recognition`; null: not offered (nothing is asked). */
    fun recognition(o: JsonObject?): ChatRecognition? {
        if (o == null) return null
        return ChatRecognition(
            available = o.flag("available") == true,
            network = o.str("network")?.takeIf { WAY_RE.matches(it) } ?: ChatBridges.WHATSAPP,
            asked = o.flag("asked") == true,
            allowed = o.flag("allowed") == true,
        )
    }

    fun mergeBody(conversationIds: List<Long>, userId: Long?, name: String?): JsonObject = JsonObject().apply {
        add("conversationIds", JsonArray().apply { conversationIds.distinct().forEach { add(it) } })
        userId?.let { addProperty("userId", it) }
        when {
            name == null -> Unit
            name == NAME_ACCOUNT -> addProperty("name", NAME_ACCOUNT)
            else -> name.toLongOrNull()?.let { addProperty("name", it) }
        }
    }

    // ─── the list ───────────────────────────────────────────────────

    /** The name of a way's service: GateControl, WhatsApp, Signal, Telegram, else the word itself. */
    fun labelOf(way: String): String = when (way) {
        WAY_GC -> "GateControl"
        ChatBridges.WHATSAPP -> "WhatsApp"
        ChatBridges.SIGNAL -> "Signal"
        "telegram" -> "Telegram"
        else -> way
    }

    /** [c] is the entry of a person (its lead). */
    fun isEntry(c: ChatConversation): Boolean = c.person?.conversationId == c.id

    /**
     * The ways that left the list: those of the cached persons' entries but their lead, and a way that says it belongs
     * to a person with another lead (`GET conversations/:id` of a way carries `person`).
     */
    fun folded(conversations: List<ChatConversation>): Set<Long> {
        val leads = conversations.filter { isEntry(it) }
        val out = HashSet<Long>()
        leads.forEach { e -> e.person?.wayIds?.forEach { if (it != e.id) out += it } }
        conversations.forEach { c -> c.person?.let { p -> if (p.conversationId != c.id) out += c.id } }
        return out - leads.map { it.id }.toSet()
    }

    /** The chats of the list with persons ([on]): the folded ways leave it. Without the word: everything as before. */
    fun inList(conversations: List<ChatConversation>, on: Boolean): List<ChatConversation> {
        if (!on) return conversations
        val folded = folded(conversations)
        return if (folded.isEmpty()) conversations else conversations.filter { it.id !in folded }
    }

    /** The search of the list: the title, the members, and every name of a person („Schatz“ and „Jenny“). */
    fun matches(c: ChatConversation, q: String): Boolean {
        if (q.isEmpty()) return true
        if ((c.title ?: c.peer?.name ?: "").lowercase().contains(q)) return true
        if (c.members.any { (it.name ?: "").lowercase().contains(q) }) return true
        val p = c.person ?: return false
        return p.names.any { it.lowercase().contains(q) } || p.ways.any { (it.title ?: "").lowercase().contains(q) }
    }

    /**
     * The list's chips per service: „WhatsApp“ shows persons with a WhatsApp way, „Nur GateControl“ persons with a
     * GateControl way; any other chat as [ChatBridgeChat.inNet].
     */
    fun inNet(c: ChatConversation, net: String?): Boolean {
        val p = c.person ?: return ChatBridgeChat.inNet(c, net)
        return when (net) {
            null -> true
            ChatBridgeChat.NET_GC -> p.ways.any { it.gatecontrol }
            else -> p.ways.any { it.way == net }
        }
    }

    /** The services of the listed chats with the bridged ways of persons (id → name), in the order of their first chat. */
    fun networks(conversations: List<ChatConversation>): List<Pair<String, String>> =
        conversations.flatMap { c ->
            val p = c.person
            if (p == null) listOfNotNull(c.bridge) else p.ways.mapNotNull { it.bridge }
        }.distinctBy { it.network }.map { it.network to it.label }

    /** A custom list names the entry or any of its ways: it counts for the person. */
    fun listed(c: ChatConversation, ids: Set<Long>): Boolean = c.id in ids || c.person?.wayIds?.any { it in ids } == true

    /** Who types in any way of [c] (the list and the head of a person). */
    fun typingOf(c: ChatConversation, typing: Map<Long, List<Long>>): List<Long> =
        c.person?.wayIds?.flatMap { typing[it].orEmpty() }?.distinct() ?: typing[c.id].orEmpty()

    // ─── the chat of a person ───────────────────────────────────────

    /** The logo's way of a message: its `way`, else the way of its conversation. */
    fun wayOf(m: ChatMessage, p: ChatMergedPerson?): String? = m.way ?: p?.way(m.conversationId)?.way

    /** The label of a way of [p] („WhatsApp“), for the logo's „über …“. */
    fun labelOf(p: ChatMergedPerson?, way: String?): String? = way?.let { w -> p?.ways?.firstOrNull { it.way == w }?.label ?: labelOf(w) }

    /**
     * The conversation a message goes to: an answer to a message ([replyTo]) back over that message's way while it can
     * send, everything else over `person.reply` (the server's choice), without one the lead.
     */
    fun sendTo(p: ChatMergedPerson, replyTo: ChatMessage?): Long {
        if (replyTo != null) p.way(replyTo.conversationId)?.takeIf { it.can.send }?.let { return it.conversationId }
        return p.reply?.conversationId ?: p.conversationId
    }

    /** The way of the composer's placeholder („Nachricht über WhatsApp“): of [sendTo]. */
    fun replyWay(p: ChatMergedPerson, replyTo: ChatMessage?): ChatPersonWay? = p.way(sendTo(p, replyTo))

    /**
     * „Nachrichten senden über“ (Messenger 1.27): Automatisch, then „Immer GateControl“ and „Immer <Dienst>“ — only the
     * kinds of ways the person has, GateControl first. A person with one way has nothing to choose.
     */
    fun replyChoices(p: ChatMergedPerson): List<String> {
        val kinds = p.kinds.map { it.way }
        return if (kinds.size < 2) emptyList() else listOf(REPLY_AUTO) + kinds
    }

    /** The body of `PUT persons/:id`: `{ replyPref }`. */
    fun replyPrefBody(pref: String): JsonObject = JsonObject().apply { addProperty("replyPref", pref) }

    /**
     * The service whose way was chosen but cannot send just now (`reply.fallback`): „WhatsApp nicht verbunden“; null:
     * the answer goes where it was meant to.
     */
    fun fallbackFrom(p: ChatMergedPerson?): String? {
        val r = p?.reply?.takeIf { it.fallback } ?: return null
        return p.replyPref.takeIf { it != REPLY_AUTO && it != r.way }?.let { labelOf(p, it) }
    }

    /** The way of GateControl of [p] (its conversation), null: the person has none. */
    fun gcWay(p: ChatMergedPerson): Long? = p.ways.firstOrNull { it.gatecontrol }?.conversationId

    /** The ways of one kind (a source chip: `gatecontrol`, `whatsapp`); null: all. */
    fun waysOf(p: ChatMergedPerson, way: String?): Set<Long>? = way?.let { w -> p.ways.filter { it.way == w }.map { it.conversationId }.toSet() }

    /**
     * The service the composer's placeholder names („Nachricht über GateControl“, „… über WhatsApp“): in a person's chat
     * always the way of the answer ([sendTo]) — its way, else `person.reply`'s label, else [bridgeLabel] (the chat the
     * composer writes to), else GateControl; a single chat over a bridge its service ([bridgeLabel]). Null: a plain
     * GateControl chat or a group — just „Nachricht“.
     */
    fun composerWay(p: ChatMergedPerson?, replyTo: ChatMessage?, bridgeLabel: String?): String? {
        if (p == null) return bridgeLabel
        val to = sendTo(p, replyTo)
        return p.way(to)?.label ?: p.reply?.takeIf { it.conversationId == to }?.label ?: bridgeLabel ?: labelOf(WAY_GC)
    }

    /**
     * The messages of the ways' caches, mixed by time (`createdAt`, a tie by `id`): those older than the newest point
     * every way with older messages still reaches are left out — the history has no holes. [older] holds the ways that
     * have older messages on the server; [only] filters to some ways (the source chips, null: all).
     */
    fun timeline(messages: List<ChatMessage>, older: Set<Long>, only: Set<Long>?): List<ChatMessage> {
        val list = if (only == null) messages else messages.filter { it.conversationId in only }
        val floor = floorOf(list, if (only == null) older else older.intersect(only))
        return list.filter { floor == null || it.createdAt >= floor }.sortedWith(compareBy<ChatMessage> { it.createdAt }.thenBy { it.id })
    }

    /**
     * The time from which the mixed history is complete: of each way with older messages on the server the oldest
     * cached one — the newest of those. Null: every way is there to its start.
     */
    fun floorOf(messages: List<ChatMessage>, older: Set<Long>): String? =
        messages.groupBy { it.conversationId }.filterKeys { it in older }.values.mapNotNull { l -> l.minByOrNull { it.id }?.createdAt }.maxOrNull()

    /** The way whose older messages load next: the one whose oldest cached message is the newest (or has none yet). */
    fun nextOlder(messages: List<ChatMessage>, older: Set<Long>): Long? {
        if (older.isEmpty()) return null
        val byWay = messages.groupBy { it.conversationId }
        older.firstOrNull { byWay[it].isNullOrEmpty() }?.let { return it }
        return older.maxByOrNull { id -> byWay[id]?.minByOrNull { it.id }?.createdAt ?: "" }
    }

    /** The choice of the name in the dialog: the account's name (first), then each bridged chat's — no name twice. */
    fun nameOptions(accountName: String?, chats: List<Pair<Long, String?>>): List<ChatNameOption> {
        val out = ArrayList<ChatNameOption>()
        if (!accountName.isNullOrBlank()) out += ChatNameOption(NAME_ACCOUNT, accountName)
        chats.forEach { (id, title) -> if (!title.isNullOrBlank()) out += ChatNameOption(id.toString(), title) }
        return out.distinctBy { it.label }
    }

    /** The names of a person's ways for „Name in deiner Liste“ in the chat info. */
    fun nameOptions(p: ChatMergedPerson): List<ChatNameOption> =
        nameOptions(if (p.userId != null) p.ways.firstOrNull { it.gatecontrol }?.title ?: p.name else null, p.ways.filter { !it.gatecontrol }.map { it.conversationId to it.title })

    /** The option that holds now: the account's, else the way's whose name the person carries. */
    fun currentName(p: ChatMergedPerson): String? = when {
        p.nameFromId != null -> p.nameFromId.toString()
        p.userId != null -> NAME_ACCOUNT
        else -> null
    }

    /**
     * „Mit Person zusammenführen …“: offered in the info of a chat with one person — a direct chat inside GateControl
     * with a peer, a bridged direct chat (no channel), the entry of a person; never a group, a notice channel or a
     * chat a plugin keeps.
     */
    fun canMerge(c: ChatConversation, on: Boolean): Boolean {
        if (!on) return false
        if (c.person != null) return true
        if (c.isGroup || c.isNotice || c.managedBy != null || c.readonly) return false
        val b = c.bridge
        return if (b != null) !b.channel else c.peer != null && !c.peer.gone
    }

    /**
     * The names of a merge by hand of [c] (a chat or a person's entry) with a picked account ([accountName]) or chat
     * ([chatId], [chatTitle]): the account's first (the picked one, the person's, the peer's), then the bridged chats'.
     */
    fun manualNames(c: ChatConversation, accountName: String?, chatId: Long?, chatTitle: String?): List<ChatNameOption> {
        val p = c.person?.takeIf { it.conversationId == c.id }
        val account = when {
            accountName != null -> accountName
            p != null && p.userId != null -> p.ways.firstOrNull { it.gatecontrol }?.title ?: p.name
            p == null && c.bridge == null && c.peer != null -> c.peer.name ?: c.title
            else -> null
        }
        val chats = ArrayList<Pair<Long, String?>>()
        if (p != null) p.ways.filter { !it.gatecontrol }.forEach { chats += it.conversationId to it.title } else if (c.bridge != null) chats += c.id to c.title
        if (chatId != null) chats += chatId to chatTitle
        return nameOptions(account, chats)
    }

    /** The conversations of a merge by hand: the chat (or the person's lead) and the picked chat. */
    fun manualIds(c: ChatConversation, chatId: Long?): List<Long> = listOfNotNull(c.person?.takeIf { it.conversationId == c.id }?.conversationId ?: c.id, chatId).distinct()

    /** The pairs a suggestion's dialog shows: who the contact is and the account (for the name choice). */
    fun suggestionNames(s: ChatPersonSuggestion): List<ChatNameOption> =
        nameOptions(s.accountName, listOf(s.conversationId to s.contactName))
}

/** What the cache keeps of a person when a conversation's own view arrives (Messenger 1.21). Pure, unit-tested. */
object ChatPeopleStore {
    /**
     * [new] (the view of an action, or of `GET conversations/:id` when [fresh]) over [old] (cached): a way of a person
     * stays a way (it keeps `person` and so stays out of the list); the lead keeps the person's face — name, picture,
     * unread of all ways, the newest message of any way — until the person is loaded again. A [fresh] view without
     * `person` says the person is gone.
     */
    fun keep(new: ChatConversation, old: ChatConversation?, fresh: Boolean): ChatConversation {
        val op = old?.person ?: return new
        if (fresh && new.person == null) return new
        val p = new.person ?: op
        if (p.conversationId != new.id) return new.copy(person = p)
        if (op.conversationId != new.id) return new.copy(person = p)
        return new.copy(
            title = old.title,
            avatarUrl = old.avatarUrl,
            avatarMediaId = old.avatarMediaId,
            unread = maxOf(old.unread, new.unread),
            lastMessage = newer(old.lastMessage, new.lastMessage),
            person = p,
        )
    }

    private fun newer(a: ChatMessage?, b: ChatMessage?): ChatMessage? = when {
        a == null -> b
        b == null -> a
        b.createdAt > a.createdAt || (b.createdAt == a.createdAt && b.id > a.id) -> b
        else -> a
    }
}
