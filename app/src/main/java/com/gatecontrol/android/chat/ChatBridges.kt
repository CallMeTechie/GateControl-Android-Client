package com.gatecontrol.android.chat

import com.gatecontrol.android.chat.ChatJson.arr
import com.gatecontrol.android.chat.ChatJson.flag
import com.gatecontrol.android.chat.ChatJson.int
import com.gatecontrol.android.chat.ChatJson.obj
import com.gatecontrol.android.chat.ChatJson.str
import com.google.gson.JsonObject
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.URLEncoder

/**
 * „Verknüpfte Dienste“ (Messenger 1.12.0/1.13.0, README "Bridges to other
 * messengers"): a person links their OWN account at another messenger
 * (first WhatsApp) with the bridge that runs next to GateControl. These
 * stages link accounts only — no outside chat is read or written yet.
 *
 * The phone number and the pairing code pass through memory only: they are
 * never written to a log, a preference or the saved state, and the classes
 * that carry them keep them out of `toString()`.
 */
object ChatBridges {
    /** `features.linked_services` of `GET me`: the server offers a bridge and it is switched on. */
    const val FEATURE = "linked_services"

    const val WHATSAPP = "whatsapp"

    /** The services this app has a page for (others the server may name later are left out). */
    val NETWORKS: Set<String> = setOf(WHATSAPP)

    const val FLOW_PHONE = "phone"
    const val FLOW_QR = "qr"

    // a network as the server reports it
    const val NET_RUNNING = "running"
    const val NET_STARTING = "starting"
    const val NET_FAILED = "failed"
    const val NET_UNREACHABLE = "unreachable"

    // a login as the bridge reports it
    const val LOGIN_CONNECTED = "connected"
    const val LOGIN_CONNECTING = "connecting"
    const val LOGIN_LOGGED_OUT = "logged_out"
    const val LOGIN_ERROR = "error"
    const val LOGIN_UNKNOWN = "unknown"
    val LOGIN_STATES = listOf(LOGIN_CONNECTED, LOGIN_CONNECTING, LOGIN_ERROR, LOGIN_LOGGED_OUT, LOGIN_UNKNOWN)

    const val LOCK_CHILD = "child"
    const val FIELD_PHONE = "phone_number"

    /** The ids of a login process, a step, a field and a login (the server's own pattern). */
    val ID_RE = Regex("^[A-Za-z0-9._:=+-]{1,128}$")
    private val PHONE_RE = Regex("^\\+[0-9]{6,15}$")
    private val PHONE_FILL_RE = Regex("[\\s()/.-]")
    private val EXPIRED_RE = Regex("timeout|expired")

    private fun seg(id: String): String? = id.takeIf { ID_RE.matches(it) }?.let { URLEncoder.encode(it, "UTF-8") }

    /** `bridges/<network>/<tail…>` with [ids] as encoded path segments; null when one is not an id. */
    fun path(network: String, vararg parts: String): String? {
        if (network !in NETWORKS) return null
        val out = StringBuilder("bridges/").append(network)
        for (p in parts) {
            // fixed words of the route stay as they are, everything else is an id of the server
            out.append('/').append(if (p in WORDS) p else seg(p) ?: return null)
        }
        return out.toString()
    }

    private val WORDS = setOf("consent", "login", "logins")

    /**
     * A phone number as the bridge takes it: spaces, brackets, dots, slashes and dashes removed, `00` as `+`;
     * null when it is not a number with its country code.
     */
    fun phone(raw: String): String? {
        var v = raw.replace(PHONE_FILL_RE, "")
        if (v.startsWith("00")) v = "+" + v.substring(2)
        return v.takeIf { PHONE_RE.matches(it) }
    }

    /** The login of a service that matters most: a linked one, else one that failed or was signed out. */
    fun mainLogin(n: ChatBridgeNetwork): ChatBridgeLogin? = n.logins.minByOrNull { LOGIN_STATES.indexOf(it.state).let { i -> if (i < 0) LOGIN_STATES.size else i } }

    /** What the row of a service says (the portal's order: the bridge's own state first, then the login, then the lock). */
    fun row(n: ChatBridgeNetwork): ChatBridgeRow {
        val l = mainLogin(n)
        return when {
            n.state == NET_STARTING -> ChatBridgeRow.STARTING
            n.state == NET_UNREACHABLE || n.state == NET_FAILED -> ChatBridgeRow.UNREACHABLE
            l != null && (l.state == LOGIN_CONNECTED || l.state == LOGIN_CONNECTING) -> ChatBridgeRow.LINKED
            l != null && l.state == LOGIN_LOGGED_OUT -> ChatBridgeRow.LOGGED_OUT
            l != null -> ChatBridgeRow.ERROR
            !n.allowed -> ChatBridgeRow.LOCKED
            else -> ChatBridgeRow.NONE
        }
    }

    /** "Neu verknüpfen" shows: a login that was signed out or failed, and the bridge runs. */
    fun relinkOffered(n: ChatBridgeNetwork): Boolean {
        val l = mainLogin(n) ?: return false
        return n.state == NET_RUNNING && (l.state == LOGIN_LOGGED_OUT || l.state == LOGIN_ERROR || l.state == LOGIN_UNKNOWN)
    }

    /** Why a call failed, as the page words it. */
    fun error(r: ChatResult<*>): ChatBridgeError = when (r) {
        is ChatResult.Ok -> ChatBridgeError.GENERIC
        ChatResult.Offline -> ChatBridgeError.OFFLINE
        ChatResult.NotConfigured -> ChatBridgeError.GENERIC
        is ChatResult.Failed -> when (r.code) {
            "bridge_off" -> ChatBridgeError.OFF
            "bridge_unreachable" -> ChatBridgeError.UNREACHABLE
            "bridge_unsupported" -> ChatBridgeError.UNSUPPORTED
            "bridge_failed" -> if (EXPIRED_RE.containsMatchIn(r.reason.orEmpty())) ChatBridgeError.EXPIRED else ChatBridgeError.FAILED
            "bridge_locked" -> ChatBridgeError.LOCKED
            "consent_required" -> ChatBridgeError.CONSENT
            "rate_limited", "http_429" -> ChatBridgeError.RATE_LIMITED
            "forbidden" -> ChatBridgeError.FORBIDDEN
            else -> ChatBridgeError.GENERIC
        }
    }

    /** The modules of a QR code drawn from its content (the server sent no `matrix`): error correction M, no quiet zone. */
    fun qr(data: String): ChatQrMatrix? {
        if (data.isEmpty() || data.length > QR_MAX_CHARS) return null
        return runCatching {
            val m = QRCodeWriter().encode(
                data, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
            )
            ChatQrMatrix(m.width, (0 until m.height).map { y -> buildString(m.width) { for (x in 0 until m.width) append(if (m.get(x, y)) '1' else '0') } })
        }.getOrNull()?.takeIf { it.valid }
    }

    const val QR_MAX_CHARS = 2048
    const val QR_MAX_SIZE = 177
    const val CODE_MAX_CHARS = 32
}

val ChatFeatures.linkedServices: Boolean get() = ChatBridges.FEATURE in tokens

enum class ChatBridgeRow { STARTING, UNREACHABLE, LINKED, LOGGED_OUT, ERROR, LOCKED, NONE }

enum class ChatBridgeError { OFFLINE, OFF, UNREACHABLE, UNSUPPORTED, FAILED, EXPIRED, LOCKED, CONSENT, RATE_LIMITED, FORBIDDEN, GENERIC }

/** An account linked at a service; [name] is the person's own account there (WhatsApp: their number). */
class ChatBridgeLogin(val id: String, val name: String, val state: String, val since: String?) {
    override fun equals(other: Any?): Boolean = other is ChatBridgeLogin && other.id == id && other.name == name && other.state == state && other.since == since
    override fun hashCode(): Int = id.hashCode() * 31 + state.hashCode()

    /** Without [name]: a number does not belong in a log. */
    override fun toString(): String = "ChatBridgeLogin(state=$state)"
}

data class ChatBridgeNetwork(
    val id: String,
    /** [ChatBridges.NET_RUNNING] … — what the bridge itself does. */
    val state: String,
    /** False: a child whose parents did not allow this service ([lock] says so). */
    val allowed: Boolean = true,
    val lock: String? = null,
    /** The notes before the first link were confirmed. */
    val consent: Boolean = false,
    /** The bridge runs an older program than GateControl expects (the administrator updates it). */
    val outdated: Boolean = false,
    val version: String? = null,
    val wantVersion: String? = null,
    val logins: List<ChatBridgeLogin> = emptyList(),
)

data class ChatBridgesInfo(
    /** False: the bridges are off or not offered (no networks then). */
    val available: Boolean,
    /** Where the bridges run: on the server, or on a gateway. */
    val onServer: Boolean = true,
    val networks: List<ChatBridgeNetwork> = emptyList(),
)

/** The modules of a QR code without a quiet zone: [rows] of `0`/`1`, [size] long and wide. */
class ChatQrMatrix(val size: Int, val rows: List<String>) {
    val valid: Boolean get() = size in 1..ChatBridges.QR_MAX_SIZE && rows.size == size && rows.all { r -> r.length == size && r.all { it == '0' || it == '1' } }
    override fun toString(): String = "ChatQrMatrix($size)"
}

/** One step of a login, as the bridge asks for it. */
sealed interface ChatBridgeStep {
    /** The person's number is asked for ([fieldId] names it in the answer). */
    data class Phone(val id: String, val fieldId: String) : ChatBridgeStep

    /** A pairing code to type into the service's app. */
    class Code(val id: String, val code: String) : ChatBridgeStep {
        override fun toString(): String = "Code"
    }

    /** A QR code to scan with the service's app (from a second screen). */
    class Qr(val id: String, val matrix: ChatQrMatrix) : ChatBridgeStep {
        override fun toString(): String = "Qr"
    }

    data object Complete : ChatBridgeStep

    /** A step this app cannot show (e.g. a passkey): the other way has to do. */
    data object Unsupported : ChatBridgeStep
}

/** The answer of a login call: the next [step], or [waiting] (the phone has not confirmed yet — ask again). */
data class ChatBridgeLoginState(val processId: String, val waiting: Boolean, val step: ChatBridgeStep?)

/** Reads the answers of `bridges/…` (never throws; what is not understood is left out). */
object ChatBridgesJson {
    /** `{ bridges: { available, location, networks: […] } }`. */
    fun info(body: JsonObject?): ChatBridgesInfo? {
        val o = body?.obj("bridges") ?: return null
        if (o.flag("available") != true) return ChatBridgesInfo(available = false)
        val nets = o.arr("networks")?.mapNotNull { e -> network(e.takeIf { it.isJsonObject }?.asJsonObject) }.orEmpty()
        return ChatBridgesInfo(available = true, onServer = (o.str("location") ?: "server") == "server", networks = nets.take(MAX_NETWORKS))
    }

    private fun network(o: JsonObject?): ChatBridgeNetwork? {
        if (o == null) return null
        val id = o.str("id")?.takeIf { it in ChatBridges.NETWORKS } ?: return null
        val lock = o.str("lock")?.takeIf { it == ChatBridges.LOCK_CHILD }
        return ChatBridgeNetwork(
            id = id,
            state = o.str("state")?.take(40).orEmpty(),
            allowed = o.flag("allowed") ?: (lock == null),
            lock = lock,
            consent = !o.str("consent").isNullOrBlank(),
            outdated = o.flag("outdated") == true,
            version = o.str("version")?.take(40),
            wantVersion = o.str("wantVersion")?.take(40),
            logins = o.arr("logins")?.mapNotNull { e -> login(e.takeIf { it.isJsonObject }?.asJsonObject) }.orEmpty().take(MAX_LOGINS),
        )
    }

    private fun login(o: JsonObject?): ChatBridgeLogin? {
        if (o == null) return null
        val id = o.str("id")?.takeIf { ChatBridges.ID_RE.matches(it) } ?: return null
        return ChatBridgeLogin(
            id = id,
            name = o.str("name").orEmpty().take(80),
            state = o.str("state")?.takeIf { it in ChatBridges.LOGIN_STATES } ?: ChatBridges.LOGIN_UNKNOWN,
            since = ChatJson.time(o.str("since")),
        )
    }

    /** `{ login: { processId, step } | { processId, waiting: true } }`. */
    fun loginState(o: JsonObject?): ChatBridgeLoginState? {
        val l = o?.obj("login") ?: return null
        val pid = l.str("processId")?.takeIf { ChatBridges.ID_RE.matches(it) } ?: return null
        if (l.flag("waiting") == true) return ChatBridgeLoginState(pid, waiting = true, step = null)
        return ChatBridgeLoginState(pid, waiting = false, step = step(l.obj("step")))
    }

    private fun step(s: JsonObject?): ChatBridgeStep {
        if (s == null) return ChatBridgeStep.Unsupported
        val type = s.str("type")
        if (type == "complete") return ChatBridgeStep.Complete
        val id = s.str("id")?.takeIf { ChatBridges.ID_RE.matches(it) } ?: return ChatBridgeStep.Unsupported
        return when (type) {
            "user_input" -> {
                // the first field, as the portal does; a field that is no number (a password, a passkey) cannot be shown
                val f = s.arr("fields")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                val fieldId = if (f == null) ChatBridges.FIELD_PHONE else f.str("id")?.takeIf { ChatBridges.ID_RE.matches(it) }
                val fieldType = f?.str("type") ?: ChatBridges.FIELD_PHONE
                if (fieldId == null || fieldType != ChatBridges.FIELD_PHONE) ChatBridgeStep.Unsupported else ChatBridgeStep.Phone(id, fieldId)
            }
            "display_and_wait" -> {
                val d = s.obj("display")
                val data = d?.str("data").orEmpty()
                when (d?.str("type")) {
                    "code" -> if (data.isBlank() || data.length > ChatBridges.CODE_MAX_CHARS) ChatBridgeStep.Unsupported else ChatBridgeStep.Code(id, data.trim())
                    "qr" -> (matrix(d?.obj("matrix")) ?: ChatBridges.qr(data))?.let { ChatBridgeStep.Qr(id, it) } ?: ChatBridgeStep.Unsupported
                    else -> ChatBridgeStep.Unsupported
                }
            }
            else -> ChatBridgeStep.Unsupported
        }
    }

    /** `matrix: { size, rows: ["0101…"] }` (Messenger 1.13.0). */
    private fun matrix(o: JsonObject?): ChatQrMatrix? {
        if (o == null) return null
        val size = o.int("size") ?: return null
        val rows = o.arr("rows")?.map { e -> e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null } ?: return null
        return ChatQrMatrix(size, rows).takeIf { it.valid }
    }

    private const val MAX_NETWORKS = 8
    private const val MAX_LOGINS = 8
}
