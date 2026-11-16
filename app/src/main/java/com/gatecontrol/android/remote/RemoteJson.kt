package com.gatecontrol.android.remote

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant

/** The phone as the plugin knows it (`host` of `host/register`). */
data class ScreenHost(val deviceId: Long, val shareMode: String, val allowOpen: Boolean, val state: String)

/** A code to show: `482-917-305` and when it runs out (epoch ms). */
data class ScreenCode(val code: String, val expiresAt: Long)

/**
 * A helper's question (signal `remote.request` or `pending` of `host/state`). [control]: asked for „Ansehen und steuern“
 * (stage 6; only ever true in a build that can control — the person still chooses).
 */
data class ScreenRequest(val id: String, val fromName: String, val avatarUrl: String?, val expiresAt: Long, val control: Boolean = false)

/** `host/state` → the share mode and the questions still open. */
data class ScreenState(val shareMode: String, val allowOpen: Boolean, val pending: List<ScreenRequest>)

/** The sending side of an allowed request (`screen` of `POST host/requests/<id>`). */
data class ScreenTicket(val ref: String, val expiresAt: Long)

/** The answer to a request: the sending side when allowed, null when denied; [control]: the plugin granted control (stage 6). */
data class ScreenDecision(val ticket: ScreenTicket?, val control: Boolean = false)

/** A token of GateControl for the relay (`POST /api/v1/client/remote/screen-token`). */
data class ScreenToken(val token: String, val wsPath: String)

/** A silent signal of the plugin to this phone (topic `remote_signal`). */
sealed interface ScreenSignal {
    data class Request(val request: ScreenRequest) : ScreenSignal
    data class Cancel(val requestId: String) : ScreenSignal
    data object CodeVoid : ScreenSignal
    data class End(val requestId: String, val reason: String) : ScreenSignal
}

/**
 * Parsing of the plugin's answers and signals (contract „Fernzugriff Stufe 5“ §5). Untrusted input: every field is
 * checked, a missing or wrong one gives null. Pure Kotlin — unit tested.
 */
object RemoteJson {
    private val REQUEST_ID = Regex("^rq_[A-Za-z0-9_-]{8,40}$")
    private val CODE = Regex("^\\d{3}-\\d{3}-\\d{3}$")
    /** The base64url refs and tokens of GateControl (32 random bytes). */
    private val REF = Regex("^[A-Za-z0-9_-]{43}$")
    private val WS_PATH = Regex("^/api/v1/remote/[a-z-]{1,40}$")

    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.bool(k: String): Boolean? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
    private fun JsonObject.long(k: String): Long? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
    private fun JsonObject.obj(k: String): JsonObject? = get(k)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun time(v: String?): Long? = v?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    /** One line of text without control characters (names of people). */
    fun clean(v: String?, max: Int = 100): String? =
        v?.filter { it >= ' ' && it != '\u007f' }?.trim()?.take(max)?.takeIf { it.isNotEmpty() }

    fun host(body: JsonObject): ScreenHost? {
        val h = body.obj("host") ?: return null
        val id = h.long("deviceId")?.takeIf { it > 0 } ?: return null
        return ScreenHost(id, if (h.str("shareMode") == "open") "open" else "code", h.bool("allowOpen") ?: true, h.str("state") ?: "ready")
    }

    fun code(body: JsonObject): ScreenCode? {
        val c = body.str("code")?.takeIf { CODE.matches(it) } ?: return null
        val exp = time(body.str("expiresAt")) ?: return null
        return ScreenCode(c, exp)
    }

    fun request(o: JsonObject): ScreenRequest? {
        val id = (o.str("requestId") ?: o.str("id"))?.takeIf { REQUEST_ID.matches(it) } ?: return null
        // a phone is only watched in this version: a request of another kind is not ours
        if (o.has("kind") && o.str("kind") != "screen") return null
        val from = o.obj("from")
        val name = clean(from?.str("name")) ?: return null
        val avatar = from?.str("avatarUrl")?.takeIf { it.startsWith("/") && !it.startsWith("//") && it.length <= 300 }
        val exp = time(o.str("expiresAt")) ?: return null
        // stage 6: control only where this build can carry it out (the Play build only shows the screen)
        return ScreenRequest(id, name, avatar, exp, control = o.str("mode") == "control" && ScreenControl.supported)
    }

    fun state(body: JsonObject): ScreenState? {
        val pending = body.get("pending")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { e -> e.takeIf { it.isJsonObject }?.let { request(it.asJsonObject) } }.orEmpty()
        return ScreenState(if (body.str("shareMode") == "open") "open" else "code", body.bool("allowOpen") ?: true, pending)
    }

    fun shareMode(body: JsonObject): String? = body.str("shareMode")?.takeIf { it == "open" || it == "code" }

    /** `POST host/requests/<id>` with allow: the sending side; deny → null ticket with state denied. */
    fun ticket(body: JsonObject): ScreenTicket? {
        val s = body.obj("screen") ?: return null
        val ref = s.str("ref")?.takeIf { REF.matches(it) } ?: return null
        return ScreenTicket(ref, time(s.str("expiresAt")) ?: (System.currentTimeMillis() + 120_000L))
    }

    fun requestState(body: JsonObject): String? = body.obj("request")?.str("state")

    /** Stage 6: the plugin granted control for this request (its answer names the mode). */
    fun grantedControl(body: JsonObject): Boolean = body.obj("request")?.str("mode") == "control" && ScreenControl.supported

    fun token(body: JsonObject): ScreenToken? {
        if (body.bool("ok") == false) return null
        if (body.str("role") != null && body.str("role") != "sender") return null
        val token = body.str("token")?.takeIf { REF.matches(it) } ?: return null
        val path = body.str("wsPath")?.takeIf { WS_PATH.matches(it) } ?: return null
        return ScreenToken(token, path)
    }

    /** `{ ok: false, code }` (plugin) or `{ ok: false, error }` (core) → a short code. */
    fun errorCode(body: JsonObject?): String? =
        (body?.str("code") ?: body?.str("error"))?.takeIf { Regex("^[a-z_]{1,40}$").matches(it) }

    /** The `data` of a push on the plugin's signal topic → a signal or null. */
    fun signal(dataJson: String?): ScreenSignal? {
        val o = dataJson?.let { runCatching { JsonParser.parseString(it) }.getOrNull() }?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return null
        return when (o.str("type")) {
            "remote.request" -> request(o)?.let { ScreenSignal.Request(it) }
            "remote.request.cancel" -> o.str("requestId")?.takeIf { REQUEST_ID.matches(it) }?.let { ScreenSignal.Cancel(it) }
            "remote.code.void" -> ScreenSignal.CodeVoid
            "remote.screen.end" -> o.str("requestId")?.takeIf { REQUEST_ID.matches(it) }
                ?.let { ScreenSignal.End(it, ScreenProtocol.cleanCode(o.str("reason")) ?: "ended") }
            else -> null
        }
    }

    /** Topic of the plugin's signals in the push stream. */
    const val SIGNAL_TOPIC = "plugin:$REMOTE_PLUGIN:remote_signal"
}
