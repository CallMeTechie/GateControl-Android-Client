package com.gatecontrol.android.chat

import com.gatecontrol.android.chat.ChatJson.int
import com.gatecontrol.android.chat.ChatJson.objOrNull
import com.gatecontrol.android.chat.ChatJson.str
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject

/*
 * Messenger 1.20 (Chats v2, Teil A): channels of a service (WhatsApp „Aktuelles“) on a page of their own, the format of
 * a bridged message (`marks`), a link's preview on top of the bubble, and addresses in every chat as links. Pure,
 * unit-tested; the screens draw what these say.
 */

/** One mark of a message's text: [type] of [ChatFormat.TYPES], UTF-16 offsets [start] … [end] (exclusive), a link's [url]. */
data class ChatMark(val type: String, val start: Int, val end: Int, val url: String? = null)

/** `preview.image`: loads like any file of the messenger ([mediaId]); [state] `loading` | `failed` | null. */
data class ChatPreviewImage(val mediaId: String?, val mime: String?, val w: Int?, val h: Int?, val state: String?) {
    val loading: Boolean get() = state == ChatBridgeChat.PH_LOADING

    /** A picture to draw (none: the card without a picture). */
    val shown: Boolean get() = mediaId != null && state != ChatBridgeChat.PH_FAILED
}

/** `preview` of a text message: the card on top of the bubble, a tap opens [url]. */
data class ChatLinkPreview(
    val url: String,
    val title: String?,
    val description: String?,
    val site: String?,
    val image: ChatPreviewImage?,
)

/** `channels { count, unread }` of `GET conversations?channels=1`: channels with a post, their new posts. */
data class ChatChannelSummary(val count: Int, val unread: Int)

/** `GET channels`: the page „Kanäle“, newest post first. */
data class ChatChannelList(
    val channels: List<ChatConversation>,
    val removed: List<Long>,
    val unread: Int,
    val serverTime: String?,
)

/** Channels (Messenger 1.20): parsing, the page's rows, the TV symbol's dot. */
object ChatChannels {

    /** The person's channels are kept apart (`features.bridge_channels`). */
    fun on(features: ChatFeatures?): Boolean = features?.tokens?.contains(ChatBridgeChat.FEATURE_CHANNELS) == true

    /**
     * Whether [c] stands in the chat list: everything but a channel once channels have a page of their own ([on]).
     * Without the word a channel is a read-only chat like before.
     */
    fun inList(c: ChatConversation, on: Boolean): Boolean = !(on && c.bridge?.channel == true)

    fun summary(o: JsonObject?): ChatChannelSummary? {
        if (o == null) return null
        return ChatChannelSummary(o.int("count")?.coerceAtLeast(0) ?: 0, o.int("unread")?.coerceAtLeast(0) ?: 0)
    }

    /** What the cached channels say: channels with a post, new posts together. */
    fun summary(channels: List<ChatConversation>): ChatChannelSummary =
        ChatChannelSummary(channels.count { it.lastMessage != null || it.lastMessageId != null }, channels.sumOf { it.unread })

    fun channelList(o: JsonObject): ChatChannelList? {
        val arr = o.get("channels")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        return ChatChannelList(
            // only channels: a server that sent something else keeps it out of this page
            channels = arr.mapNotNull { ChatJson.conversation(it.objOrNull()) }.filter { it.bridge?.channel == true }.distinctBy { it.id },
            removed = o.get("removed")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { ChatJson.idOf(it) }.orEmpty(),
            unread = o.int("unread")?.coerceAtLeast(0) ?: 0,
            serverTime = ChatJson.time(o.str("serverTime")),
        )
    }

    /** The page's rows: newest post first, [query] on the name and the last post. */
    fun rows(channels: List<ChatConversation>, query: String): List<ChatConversation> {
        val q = query.trim().lowercase()
        return channels.sortedByDescending { it.activityAt }.filter { c ->
            q.isEmpty() || c.title.orEmpty().lowercase().contains(q) || c.lastMessage?.text.orEmpty().lowercase().contains(q)
        }
    }

    /** The services of the channels (their names), for the page's subtitle: „WhatsApp“. */
    fun networks(channels: List<ChatConversation>): List<String> = channels.mapNotNull { it.bridge?.label }.distinct()

    /** Messenger 1.24: the services of the channels, id → label (the filter chips „Alle · WhatsApp · Telegram“). */
    fun services(channels: List<ChatConversation>): List<Pair<String, String>> =
        channels.mapNotNull { c -> c.bridge?.let { it.network to it.label } }.distinctBy { it.first }

    /** Messenger 1.24: only the channels of [network] (null: all). */
    fun of(channels: List<ChatConversation>, network: String?): List<ChatConversation> =
        if (network == null) channels else channels.filter { it.bridge?.network == network }

    /** The TV symbol in the head of „Chats“: only once a channel has a post. */
    fun showTv(s: ChatChannelSummary?): Boolean = (s?.count ?: 0) > 0

    /** Its grey dot: new posts (no number). */
    fun dot(s: ChatChannelSummary?): Boolean = (s?.unread ?: 0) > 0

    // ─── link preview ───────────────────────────────────────────────

    private val PREVIEW_STATES = setOf(ChatBridgeChat.PH_LOADING, ChatBridgeChat.PH_FAILED)

    /** `preview` of a text message; anything without an http(s) address → none. */
    fun preview(o: JsonObject?): ChatLinkPreview? {
        if (o == null) return null
        val url = ChatFormat.safeUrl(o.str("url")) ?: return null
        val img = o.get("image")?.objOrNull()
        return ChatLinkPreview(
            url = url,
            title = ChatJson.line(o.str("title"), 200),
            description = ChatJson.line(o.str("description"), 400),
            site = ChatJson.line(o.str("site"), 100),
            image = img?.let { i ->
                ChatPreviewImage(
                    mediaId = ChatJson.mediaId(i.str("mediaId")),
                    mime = i.str("mime")?.takeIf { it.length <= 100 && it.startsWith("image/") },
                    w = i.int("w")?.takeIf { it in 1..20_000 },
                    h = i.int("h")?.takeIf { it in 1..20_000 },
                    state = i.str("state")?.takeIf { it in PREVIEW_STATES },
                )
            },
        )
    }

    fun write(p: ChatLinkPreview): JsonObject = JsonObject().apply {
        addProperty("url", p.url)
        p.title?.let { addProperty("title", it) }
        p.description?.let { addProperty("description", it) }
        p.site?.let { addProperty("site", it) }
        add("image", p.image?.let { i ->
            JsonObject().apply {
                if (i.mediaId == null) add("mediaId", JsonNull.INSTANCE) else addProperty("mediaId", i.mediaId)
                i.mime?.let { addProperty("mime", it) }
                i.w?.let { addProperty("w", it) }
                i.h?.let { addProperty("h", it) }
                if (i.state == null) add("state", JsonNull.INSTANCE) else addProperty("state", i.state)
            }
        } ?: JsonNull.INSTANCE)
    }
}

/**
 * A piece of a message's text and how it is drawn: [start] … [end] (UTF-16, exclusive) bold, italic, struck through,
 * as code, and a link to [url].
 */
data class ChatSpan(
    val start: Int,
    val end: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val url: String? = null,
)

/**
 * Format and links of a message's text (Messenger 1.20, README „Format und Links“): the `marks` of a bridged message,
 * and http(s) and `www.` addresses as links in every chat. Never HTML: the text is cut into pieces, each with its marks.
 */
object ChatFormat {
    const val BOLD = "bold"
    const val ITALIC = "italic"
    const val STRIKE = "strike"
    const val CODE = "code"
    const val LINK = "link"
    val TYPES: Set<String> = setOf(BOLD, ITALIC, STRIKE, CODE, LINK)

    private const val MAX_MARKS = 200
    private const val MAX_URL = 2000

    /** An address in the text: http(s):// or www., up to a space, `<`, `>` or `"`. */
    private val URL_RE = Regex("""\b(?:https?://|www\.)[^\s<>"]{2,2000}""", RegexOption.IGNORE_CASE)

    /** Punctuation at the end of an address belongs to the sentence. */
    private val TRAIL_RE = Regex("""[.,;:!?)\]}'"»“”]+$""")

    fun marks(arr: JsonArray?): List<ChatMark>? {
        if (arr == null) return null
        val out = arr.mapNotNull { e ->
            val o = e.objOrNull() ?: return@mapNotNull null
            val type = o.str("type")?.takeIf { it in TYPES } ?: return@mapNotNull null
            val start = o.int("start")?.takeIf { it >= 0 } ?: return@mapNotNull null
            val end = o.int("end")?.takeIf { it > start } ?: return@mapNotNull null
            val url = if (type == LINK) safeUrl(o.str("url")) ?: return@mapNotNull null else null
            ChatMark(type, start, end, url)
        }.take(MAX_MARKS)
        return out.takeIf { it.isNotEmpty() }
    }

    fun write(marks: List<ChatMark>): JsonArray = JsonArray().apply {
        marks.forEach { k ->
            add(JsonObject().apply {
                addProperty("type", k.type)
                addProperty("start", k.start)
                addProperty("end", k.end)
                k.url?.let { addProperty("url", it) }
            })
        }
    }

    /**
     * An address that may be opened: http or https with a host and without a name or password in it; `www.…` is taken
     * as https. Anything else → null.
     */
    fun safeUrl(raw: String?): String? {
        val t = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_URL } ?: return null
        if (t.any { it.isWhitespace() || it.isISOControl() }) return null
        val s = if (t.startsWith("www.", ignoreCase = true)) "https://$t" else t
        val lower = s.lowercase()
        val rest = when {
            lower.startsWith("https://") -> s.substring(8)
            lower.startsWith("http://") -> s.substring(7)
            else -> return null
        }
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty() || '@' in authority || authority.startsWith(':') || authority.startsWith('.')) return null
        return s
    }

    /** The addresses of [text] (`start` … `end` and where they lead), punctuation at their end left out. */
    fun links(text: String, from: Int = 0, to: Int = text.length): List<ChatSpan> {
        val out = ArrayList<ChatSpan>()
        val piece = text.substring(from, to)
        var pos = 0
        while (pos < piece.length) {
            val m = URL_RE.find(piece, pos) ?: break
            val raw = m.value.replace(TRAIL_RE, "")
            val url = safeUrl(raw)
            if (url == null || raw.isEmpty()) {
                pos = m.range.first + 1
                continue
            }
            out += ChatSpan(from + m.range.first, from + m.range.first + raw.length, url = url)
            pos = m.range.first + raw.length
        }
        return out
    }

    /**
     * [text] cut into pieces where a mark begins or ends; each piece carries the marks covering it, a link mark its
     * address — and within a piece without a link mark, its addresses become links. Marks outside the text are left
     * out. Pieces in order, together the whole text.
     */
    fun spans(text: String, marks: List<ChatMark>?): List<ChatSpan> {
        if (text.isEmpty()) return emptyList()
        val list = marks.orEmpty().filter { it.type in TYPES && it.start >= 0 && it.end <= text.length && it.end > it.start }
        val cuts = sortedSetOf(0, text.length)
        list.forEach { cuts += it.start; cuts += it.end }
        val points = cuts.toList()
        val out = ArrayList<ChatSpan>()
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val on = list.filter { it.start <= a && it.end >= b }
            val base = ChatSpan(
                a, b,
                bold = on.any { it.type == BOLD },
                italic = on.any { it.type == ITALIC },
                strike = on.any { it.type == STRIKE },
                code = on.any { it.type == CODE },
            )
            val link = on.firstOrNull { it.type == LINK }?.url
            if (link != null) {
                out += base.copy(url = link)
                continue
            }
            // plain addresses in the piece: links of their own
            var at = a
            for (l in links(text, a, b)) {
                if (l.start > at) out += base.copy(start = at, end = l.start)
                out += base.copy(start = l.start, end = l.end, url = l.url)
                at = l.end
            }
            if (at < b) out += base.copy(start = at, end = b)
        }
        return out
    }

    /** Anything to draw beyond plain text (a mark, an address). */
    fun rich(text: String, marks: List<ChatMark>?): Boolean = spans(text, marks).any { it.bold || it.italic || it.strike || it.code || it.url != null }
}
