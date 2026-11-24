package com.gatecontrol.android.chat

import com.gatecontrol.android.chat.ChatJson.arr
import com.gatecontrol.android.chat.ChatJson.flag
import com.gatecontrol.android.chat.ChatJson.id
import com.gatecontrol.android.chat.ChatJson.obj
import com.gatecontrol.android.chat.ChatJson.objOrNull
import com.gatecontrol.android.chat.ChatJson.str

/*
 * "Neuer Chat" with contacts (Messenger 1.18.0, `features.bridge_contacts`; draft "Neuer Chat mit Kontakten", approved
 * 15.11.): one search field, three sections — GateControl, WhatsApp, "Aus deinem Adressbuch" — and at the bottom
 * "Nicht dabei? Nummer eingeben" (the page of 1.16.0). `GET contacts/new-chat?network=&q=`.
 *
 * A WhatsApp contact comes without a number (a sealed `ref` only) and starts its chat at once
 * (`POST bridges/:network/chats { ref }`); an address book number is asked about only when it is tapped
 * (`{ contactId, phoneKind }`, counted in the brake). Contacts come from GateControl only — nothing from the phone.
 * Names and numbers stay out of `toString()` and every log.
 */

/** An account on the server (as `GET people`), with the direct chat there is ([conversationId]). */
data class ChatGcContact(val person: ChatPerson, val conversationId: Long?) {
    override fun toString(): String = "ChatGcContact(${person.userId}, open=${conversationId != null})"
}

/** A WhatsApp contact at the bridge: [ref] (sealed, ~30 min), the name, a picture GateControl already fetched. */
data class ChatWaContact(
    val ref: String,
    val name: String,
    val avatarMediaId: String?,
    val conversationId: Long?,
    val alsoInAddressBook: Boolean,
) {
    override fun toString(): String = "ChatWaContact(open=${conversationId != null}, book=$alsoInAddressBook)"
}

/** One number of an address book contact, as Mail delivers it ([number] shown as is). */
data class ChatBookPhone(
    val key: String,
    val kind: String,
    val number: String,
    val landline: Boolean,
    val notOnNetwork: Boolean,
) {
    override fun toString(): String = "ChatBookPhone($key, landline=$landline, notOn=$notOnNetwork)"
}

/** A contact of the address book with at least one number (Mail ≥ 1.16.0). */
data class ChatBookContact(val contactId: Long, val name: String, val conversationId: Long?, val phones: List<ChatBookPhone>) {
    override fun toString(): String = "ChatBookContact($contactId, phones=${phones.size}, open=${conversationId != null})"
}

/** A section of the list: its [state] (`ok`, `unreachable`, `logged_out`), the [items] (all, or the hits of a search). */
data class ChatContactSection<T>(val state: String, val total: Int, val items: List<T>) {
    val ok: Boolean get() = state == ChatNewContacts.STATE_OK
}

/** The search looks like a number: "<show> bei WhatsApp prüfen" (or the chat with it). */
data class ChatNumberHit(val e164: String, val show: String, val conversationId: Long?) {
    override fun toString(): String = "ChatNumberHit(open=${conversationId != null})"
}

/** The answer of `GET contacts/new-chat`. [whatsapp] / [addressBook] null: the section is not there. */
data class ChatNewChatContacts(
    val network: String,
    val gatecontrol: List<ChatGcContact>,
    val whatsapp: ChatContactSection<ChatWaContact>?,
    val addressBook: ChatContactSection<ChatBookContact>?,
    val number: ChatNumberHit?,
    val numberEntry: Boolean,
    /** The checks per hour (the brake shared with "Nummer eingeben"); null when the server sent none. */
    val perHour: Int?,
) {
    override fun toString(): String =
        "ChatNewChatContacts(gc=${gatecontrol.size}, wa=${whatsapp?.state}/${whatsapp?.items?.size}, book=${addressBook?.state}/${addressBook?.items?.size}, number=${number != null}, entry=$numberEntry)"
}

/** Pure parts of "Neuer Chat" with contacts. Unit-tested. */
object ChatNewContacts {
    const val STATE_OK = "ok"
    const val STATE_UNREACHABLE = "unreachable"
    const val STATE_LOGGED_OUT = "logged_out"
    private val STATES = setOf(STATE_OK, STATE_UNREACHABLE, STATE_LOGGED_OUT)

    /** Rows of a long section before "Alle … anzeigen" (without a search). */
    const val FIRST = 3

    /** The search as the server takes it. */
    const val QUERY_MAX = 100

    /** The wait after typing before the list is asked anew. */
    const val DEBOUNCE_MS = 250L

    // kinds of an address book number, in the order of the choice
    const val KIND_MOBILE = "mobile"
    const val KIND_WORK = "work"
    const val KIND_HOME = "home"
    const val KIND_OTHER = "other"
    private val KINDS = listOf(KIND_MOBILE, KIND_WORK, KIND_HOME, KIND_OTHER)
    private val KEY_RE = Regex("^(mobile|work|home|other)(-[2-9])?$")
    private val REF_RE = Regex("^k[A-Za-z0-9_-]{40,1400}$")
    private val E164_RE = Regex("^\\+[1-9][0-9]{6,14}$")

    /** Whether "Neuer Chat" shows the three sections (else the page of 1.16.0: people and "… mit Nummer"). */
    fun enabled(features: ChatFeatures?): Boolean = features?.tokens?.contains(ChatBridgeChat.FEATURE_CONTACTS) == true

    /** The search as it is sent: control characters out, trimmed, at most [QUERY_MAX]. */
    fun query(raw: String): String = raw.replace(Regex("[\\u0000-\\u001f\\u007f]"), " ").trim().take(QUERY_MAX)

    /** The kind of a number ([KINDS]); a key this app does not know is "other". */
    fun kindOf(p: ChatBookPhone): String = p.key.substringBefore('-').takeIf { it in KINDS } ?: KIND_OTHER

    /** The numbers of a contact in the order of the choice: mobile first, then work, home, other (stable). */
    fun ordered(phones: List<ChatBookPhone>): List<ChatBookPhone> = phones.sortedBy { KINDS.indexOf(kindOf(it)) }

    /** The number of a contact that has only one (tapped right away); null: the row opens the choice. */
    fun single(c: ChatBookContact): ChatBookPhone? = if (c.phones.size == 1) c.phones.first() else null

    /** The first name of a contact, for "Hat Paul eine andere Nummer …". */
    fun firstName(name: String): String = name.trim().split(Regex("\\s+")).firstOrNull().orEmpty().ifEmpty { name }

    /** What is left of a section without a search: all with [expanded] or a search, else [FIRST]. */
    fun <T> visible(items: List<T>, searching: Boolean, expanded: Boolean): List<T> =
        if (searching || expanded) items else items.take(FIRST)

    /** Whether a section gets "Alle … anzeigen". */
    fun hasMore(items: List<*>, searching: Boolean, expanded: Boolean): Boolean = !searching && !expanded && items.size > FIRST

    // ─── parsing ────────────────────────────────────────────────────

    fun list(o: com.google.gson.JsonObject?): ChatNewChatContacts? {
        if (o == null) return null
        val gc = o.arr("gatecontrol")?.mapNotNull { e ->
            val x = e.objOrNull() ?: return@mapNotNull null
            val p = ChatJson.person(x) ?: return@mapNotNull null
            ChatGcContact(p, x.id("conversationId"))
        }?.distinctBy { it.person.userId }.orEmpty()
        val limit = o.obj("limit")
        val perHour = limit?.get("perHour")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
            ?.takeIf { it.isFinite() && it >= 1 }?.toInt()?.coerceAtMost(1000)
        val wa = o.obj("whatsapp")?.let { w ->
            section(w) { x ->
                val ref = x.str("ref")?.takeIf { REF_RE.matches(it) } ?: return@section null
                ChatWaContact(
                    ref = ref,
                    name = ChatJson.line(x.str("name"), 120) ?: return@section null,
                    avatarMediaId = ChatJson.mediaId(x.str("avatarMediaId")),
                    conversationId = x.id("conversationId"),
                    alsoInAddressBook = x.flag("alsoInAddressBook") == true,
                )
            }
        }
        // without WhatsApp a number can do nothing: no address book either (the server sends none then)
        val book = if (wa == null) null else o.obj("addressBook")?.let { b ->
            section(b, always = true) { x ->
                val id = x.id("contactId") ?: return@section null
                val phones = x.arr("phones")?.mapNotNull { pe ->
                    val p = pe.objOrNull() ?: return@mapNotNull null
                    val key = p.str("key")?.takeIf { KEY_RE.matches(it) } ?: return@mapNotNull null
                    val number = ChatJson.line(p.str("number"), 40) ?: return@mapNotNull null
                    ChatBookPhone(key, p.str("kind")?.takeIf { it.length <= 20 } ?: key, number, p.flag("landline") == true, p.flag("notOnNetwork") == true)
                }?.distinctBy { it.key }.orEmpty()
                if (phones.isEmpty()) return@section null
                ChatBookContact(id, ChatJson.line(x.str("name"), 120) ?: phones.first().number, x.id("conversationId"), phones)
            }
        }?.takeIf { it.items.isNotEmpty() }
        val number = o.obj("number")?.let { n ->
            val e164 = n.str("e164")?.takeIf { E164_RE.matches(it) } ?: return@let null
            ChatNumberHit(e164, ChatJson.line(n.str("show"), 40) ?: e164, n.id("conversationId"))
        }
        return ChatNewChatContacts(
            network = o.str("network") ?: ChatBridges.WHATSAPP,
            gatecontrol = gc,
            whatsapp = wa,
            addressBook = book,
            number = if (wa == null) null else number,
            numberEntry = wa != null && o.flag("numberEntry") == true,
            perHour = perHour,
        )
    }

    /** [always]: the items also when the state is not `ok` (the address book: shown, greyed). */
    private fun <T> section(o: com.google.gson.JsonObject, always: Boolean = false, item: (com.google.gson.JsonObject) -> T?): ChatContactSection<T> {
        // a state this app does not know: like "not reachable" (nothing tappable, "Erneut versuchen")
        val state = o.str("state")?.let { if (it in STATES) it else STATE_UNREACHABLE } ?: STATE_OK
        val items = if (always || state == STATE_OK) o.arr("items")?.mapNotNull { e -> e.objOrNull()?.let(item) }.orEmpty() else emptyList()
        val total = o.get("total")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
            ?.takeIf { it.isFinite() && it >= 0 }?.toInt()?.coerceAtMost(1_000_000) ?: items.size
        return ChatContactSection(state, maxOf(total, items.size), items)
    }

    // ─── a tap ──────────────────────────────────────────────────────

    /** What a refusal of `POST bridges/:network/chats { ref | contactId }` means for the list. */
    enum class Refusal {
        /** `404 not_found` + `not_on_network`: the number has no WhatsApp ("nicht bei WhatsApp"). */
        NOT_ON,
        /** `429 rate_limited`: too many checks ("ab HH:MM"). */
        RATE_LIMITED,
        /** `503 bridge_unreachable` / `409 bridge_off`: "Brücke nicht erreichbar". */
        UNREACHABLE,
        /** `409 contact_expired`, `409 bridge_logged_out`, `404 contact_gone`: the list anew. */
        RELOAD,
        /** `409 mail_missing`: the address book is not there any more. */
        MAIL_MISSING,
        OFFLINE,
        FAILED,
    }

    fun refusal(r: ChatResult<*>): Refusal = when (r) {
        is ChatResult.Ok, ChatResult.NotConfigured -> Refusal.FAILED
        ChatResult.Offline -> Refusal.OFFLINE
        is ChatResult.Failed -> when {
            r.code == "contact_expired" || r.code == "bridge_logged_out" -> Refusal.RELOAD
            r.code == "not_found" && r.reason == "not_on_network" -> Refusal.NOT_ON
            r.code == "not_found" -> Refusal.RELOAD
            r.code == "rate_limited" || r.http == 429 -> Refusal.RATE_LIMITED
            r.code == "bridge_unreachable" || r.code == "bridge_off" || r.http == 503 -> Refusal.UNREACHABLE
            r.code == "mail_missing" -> Refusal.MAIL_MISSING
            else -> Refusal.FAILED
        }
    }
}
