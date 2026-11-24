package com.gatecontrol.android.chat

import com.gatecontrol.android.chat.ChatJson.flag
import com.gatecontrol.android.chat.ChatJson.id
import com.gatecontrol.android.chat.ChatJson.obj
import com.gatecontrol.android.chat.ChatJson.str
import com.google.gson.JsonObject

/*
 * A new chat by phone number over a bridge (Messenger 1.15.0 API, 1.16.0 `newChat`; draft "Neuer WhatsApp-Chat per
 * Nummer", approved 15.11.): "Neuer Chat" → "WhatsApp-Chat mit Nummer" → type (or pick from the phone's contacts) →
 * "Prüfen" (`POST bridges/:network/resolve`) → "Chat starten" (`POST bridges/:network/chats`) → the chat opens.
 *
 * Every check is a question to WhatsApp, so the app asks only on "Prüfen", never while typing. The number passes
 * through memory only: no log, no preference, no saved state, not in `toString()` — the server does not keep it either.
 */

/**
 * `newChat` of a service in `GET bridges` (Messenger 1.16.0): whether a new chat by number works for the person
 * ([reason] why not), and how many checks are [left] of [perHour] ([retryAfterS] until the next one).
 */
data class ChatBridgeNewChat(
    val available: Boolean,
    val reason: String? = null,
    val perHour: Int? = null,
    val left: Int? = null,
    val retryAfterS: Int? = null,
)

/** The answer of `resolve`: on the service or not, its [name] there, the chat the person has with it already. */
data class ChatNumberLookup(val found: Boolean, val name: String?, val existingConversationId: Long?) {
    override fun toString(): String = "ChatNumberLookup(found=$found)"
}

/**
 * The first line of a chat just started by number (Messenger 1.16 `nc.first_line`, as the portal): "Neuer Chat über
 * WhatsApp mit +49 …" — since 1.18 also with a WhatsApp contact (`nc.first_line_contact`, no number) or a number of the
 * address book (`nc.first_line_book`). It may name the number, so it lives in this process's memory only — never
 * stored — and shows while the new chat has no message yet.
 */
object ChatNumberStarted {
    enum class Kind { NUMBER, CONTACT, BOOK }

    class Line(val display: String?, val name: String?, val kind: Kind = Kind.NUMBER) {
        override fun toString(): String = "Line($kind)"
    }

    private val lines = java.util.concurrent.ConcurrentHashMap<Long, Line>()

    /** Started by a typed number ([display] as checked, [name] at the service). */
    fun put(conversationId: Long, display: String, name: String?) = put(conversationId, Line(display, name))

    /** Started with a WhatsApp contact of the list (Messenger 1.18): its name, no number. */
    fun putContact(conversationId: Long, name: String) = put(conversationId, Line(null, name, Kind.CONTACT))

    /** Started with a number of the address book (Messenger 1.18): the contact's name, the number as listed. */
    fun putBook(conversationId: Long, name: String, number: String) = put(conversationId, Line(number, name, Kind.BOOK))

    private fun put(conversationId: Long, line: Line) {
        if (lines.size > 20) lines.clear()
        lines[conversationId] = line
    }

    fun get(conversationId: Long): Line? = lines[conversationId]

    fun clear() = lines.clear()
}

/** The answer of `chats`: the direct chat with the number (made, or the one there was). */
data class ChatNumberChat(val conversation: ChatConversation, val created: Boolean)

/** A country of the number field: [iso] shown ("DE"), [code] the calling code without `+` ("49"). */
data class ChatCountry(val iso: String, val code: String)

/** A number as typed, read: one to send ([e164], shown as [display]) or why not ([invalid]). */
class ChatNumber private constructor(val e164: String?, val display: String?, val invalid: Invalid?) {
    enum class Invalid { EMPTY, CHARS, SHORT, SHORT_MOBILE_DE, LONG }

    val ok: Boolean get() = e164 != null

    override fun toString(): String = "ChatNumber(ok=$ok, invalid=$invalid)"

    override fun equals(other: Any?): Boolean = other is ChatNumber && other.e164 == e164 && other.invalid == invalid
    override fun hashCode(): Int = (e164?.hashCode() ?: 0) * 31 + (invalid?.hashCode() ?: 0)

    companion object {
        internal fun ok(e164: String, display: String) = ChatNumber(e164, display, null)
        internal fun bad(why: Invalid) = ChatNumber(null, null, why)
    }
}

/** Pure parts of the new chat by number. Unit-tested. */
object ChatNewChat {
    // newChat.reason (Messenger 1.16.0)
    const val R_UNLINKED = "unlinked"
    const val R_LOGGED_OUT = "logged_out"
    const val R_UNREACHABLE = "unreachable"
    const val R_CHILD_LOCKED = "child_locked"
    const val R_CHILD_NEW_CHAT_OFF = "child_new_chat_off"
    const val R_OFF = "off"
    private val REASONS = setOf(R_UNLINKED, R_LOGGED_OUT, R_UNREACHABLE, R_CHILD_LOCKED, R_CHILD_NEW_CHAT_OFF, R_OFF)

    /** The countries of the field's picker, Germany first (the default). */
    val COUNTRIES: List<ChatCountry> = listOf(
        ChatCountry("DE", "49"), ChatCountry("AT", "43"), ChatCountry("CH", "41"), ChatCountry("NL", "31"),
        ChatCountry("BE", "32"), ChatCountry("LU", "352"), ChatCountry("FR", "33"), ChatCountry("IT", "39"),
        ChatCountry("ES", "34"), ChatCountry("PL", "48"), ChatCountry("CZ", "420"), ChatCountry("DK", "45"),
        ChatCountry("GB", "44"), ChatCountry("TR", "90"), ChatCountry("US", "1"),
    )
    val DEFAULT_COUNTRY: ChatCountry = COUNTRIES.first()

    private val FILL_RE = Regex("[\\s()./\\u00A0-]")
    private val DIGITS_RE = Regex("^\\+?[0-9]*$")
    // as the portal (Messenger 1.16 ui/newchat.js): at least 8 digits with the code, 5 without
    private const val MIN_NATIONAL = 5
    private const val MIN_E164 = 8
    private const val MAX_E164 = 15
    private val DE_MOBILE_RE = Regex("^1[5-7]")
    private const val DE_MOBILE_MIN = 10

    /**
     * Reads what was typed: spaces, dashes, brackets, dots and slashes go; `+` or `00` start a number with its own
     * country code, anything else is a number of [country] whose leading zeros (the trunk prefix) go.
     */
    fun number(raw: String, country: ChatCountry = DEFAULT_COUNTRY): ChatNumber {
        val t = raw.replace(FILL_RE, "")
        if (t.isEmpty()) return ChatNumber.bad(ChatNumber.Invalid.EMPTY)
        if (!DIGITS_RE.matches(t) || t == "+") return ChatNumber.bad(ChatNumber.Invalid.CHARS)
        val explicit = t.startsWith("+") || t.startsWith("00")
        val digits = when {
            t.startsWith("+") -> t.substring(1)
            t.startsWith("00") -> t.substring(2)
            else -> country.code + t.trimStart('0')
        }
        if (digits.isEmpty() || digits.startsWith("0")) return ChatNumber.bad(ChatNumber.Invalid.SHORT)
        if (digits.length > MAX_E164) return ChatNumber.bad(ChatNumber.Invalid.LONG)
        val cc = if (explicit) COUNTRIES.map { it.code }.filter { digits.startsWith(it) }.maxByOrNull { it.length } else country.code
        val national = if (cc != null) digits.substring(cc.length) else digits
        if (cc == "49" && DE_MOBILE_RE.containsMatchIn(national) && national.length < DE_MOBILE_MIN) return ChatNumber.bad(ChatNumber.Invalid.SHORT_MOBILE_DE)
        if (national.length < MIN_NATIONAL || digits.length < MIN_E164) return ChatNumber.bad(ChatNumber.Invalid.SHORT)
        val display = if (cc == null) "+$digits" else "+$cc " + if (national.length > 3) national.substring(0, 3) + " " + national.substring(3) else national
        return ChatNumber.ok("+$digits", display)
    }

    /** The country a picked or pasted number starts with (`+43 …`, `0043 …`); null when it has none of the list. */
    fun countryOf(raw: String): ChatCountry? {
        val t = raw.replace(FILL_RE, "")
        val digits = when {
            t.startsWith("+") -> t.substring(1)
            t.startsWith("00") -> t.substring(2)
            else -> return null
        }
        return COUNTRIES.filter { digits.startsWith(it.code) }.maxByOrNull { it.code.length }
    }

    // ─── parsing ────────────────────────────────────────────────────

    /** `newChat: { available, reason, limit: { perHour, left, retryAfterS } }`; null when the server sends none. */
    fun newChat(o: JsonObject?): ChatBridgeNewChat? {
        if (o == null) return null
        val limit = o.obj("limit")
        fun count(key: String) = limit?.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
            ?.takeIf { it.isFinite() && it >= 0 }?.toInt()?.coerceAtMost(100_000)
        return ChatBridgeNewChat(
            available = o.flag("available") == true,
            // a reason this app does not know: not available, said like "off"
            reason = o.str("reason")?.let { r -> if (r in REASONS) r else R_OFF },
            perHour = count("perHour"),
            left = count("left"),
            retryAfterS = count("retryAfterS")?.coerceAtMost(86_400),
        )
    }

    /** `{ ok, found, name, existing: { conversationId } | null }` of `resolve`. */
    fun lookup(o: JsonObject?): ChatNumberLookup? {
        if (o == null) return null
        val found = o.flag("found") ?: return null
        return ChatNumberLookup(
            found = found,
            name = if (found) ChatJson.line(o.str("name"), 120) else null,
            existingConversationId = if (found) o.obj("existing")?.id("conversationId") else null,
        )
    }

    /** `{ ok, conversation, created }` of `chats`. */
    fun chat(o: JsonObject?): ChatNumberChat? {
        val c = ChatJson.conversation(o?.obj("conversation")) ?: return null
        return ChatNumberChat(c, o?.flag("created") != false)
    }

    // ─── what the screens show ──────────────────────────────────────

    /**
     * "WhatsApp-Chat mit Nummer" in "Neuer Chat": the server can ([ChatBridgeChat.FEATURE_NEW_CHAT]) and the person
     * linked the service — even when it is signed out, away or locked for a child (the page then says why) — but not
     * for a child whose parents keep new chats by number off.
     */
    fun offered(features: ChatFeatures?, n: ChatBridgeNetwork): Boolean =
        features?.tokens?.contains(ChatBridgeChat.FEATURE_NEW_CHAT) == true &&
            n.logins.isNotEmpty() &&
            n.newChat?.reason != R_CHILD_NEW_CHAT_OFF

    /** The services that get the row (in the server's order). */
    fun networks(features: ChatFeatures?, info: ChatBridgesInfo?): List<ChatBridgeNetwork> =
        if (info == null || !info.available) emptyList() else info.networks.filter { offered(features, it) }

    /** Why the page cannot check right now (null: it can). A server without `newChat` (1.15.0) is read from the link. */
    fun blocked(n: ChatBridgeNetwork): Blocked? {
        val nc = n.newChat
        if (nc != null) {
            if (nc.available) return null
            return when (nc.reason) {
                R_UNLINKED -> Blocked.UNLINKED
                R_LOGGED_OUT -> Blocked.LOGGED_OUT
                R_UNREACHABLE -> Blocked.UNREACHABLE
                R_CHILD_LOCKED, R_CHILD_NEW_CHAT_OFF -> Blocked.LOCKED
                else -> Blocked.OFF
            }
        }
        return when (ChatBridges.row(n)) {
            ChatBridgeRow.LOGGED_OUT, ChatBridgeRow.ERROR -> Blocked.LOGGED_OUT
            ChatBridgeRow.UNREACHABLE, ChatBridgeRow.STARTING -> Blocked.UNREACHABLE
            ChatBridgeRow.LOCKED -> Blocked.LOCKED
            // Messenger 1.24: Telegram not set up by an administrator
            ChatBridgeRow.NOT_SET_UP -> Blocked.OFF
            ChatBridgeRow.NONE -> Blocked.UNLINKED
            ChatBridgeRow.LINKED -> if (!n.allowed) Blocked.LOCKED else null
        }
    }

    enum class Blocked { UNLINKED, LOGGED_OUT, UNREACHABLE, LOCKED, OFF }

    /** What a refusal of `resolve` / `chats` means for the page. */
    enum class Refusal { INVALID, NOT_FOUND, RATE_LIMITED, UNLINKED, LOGGED_OUT, UNREACHABLE, LOCKED, OFF, OFFLINE, FAILED }

    fun refusal(r: ChatResult<*>): Refusal = when (r) {
        is ChatResult.Ok, ChatResult.NotConfigured -> Refusal.FAILED
        ChatResult.Offline -> Refusal.OFFLINE
        is ChatResult.Failed -> when {
            r.code == "invalid_number" -> Refusal.INVALID
            r.code == "rate_limited" || r.http == 429 -> Refusal.RATE_LIMITED
            r.code == "not_found" && r.reason == "not_on_network" -> Refusal.NOT_FOUND
            r.code == "bridge_logged_out" || (r.code == "bridge_unlinked" && r.reason == R_LOGGED_OUT) -> Refusal.LOGGED_OUT
            r.code == "bridge_unlinked" -> Refusal.UNLINKED
            r.code == "bridge_unreachable" || r.http == 503 -> Refusal.UNREACHABLE
            r.code == "bridge_locked" || r.code == R_CHILD_NEW_CHAT_OFF -> Refusal.LOCKED
            r.code == "bridge_off" || r.code == "feature_off" -> Refusal.OFF
            else -> Refusal.FAILED
        }
    }

    /** The path of `resolve` / `chats` for [network]; null for a service this app has no page for. */
    fun path(network: String, what: String): String? = ChatBridges.path(network, what)
}
