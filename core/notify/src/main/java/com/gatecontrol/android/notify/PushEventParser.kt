package com.gatecontrol.android.notify

import com.gatecontrol.android.network.PushHelloDto
import com.gatecontrol.android.network.PushIdsDto
import com.gatecontrol.android.network.PushNotificationDto
import com.gatecontrol.android.notify.model.PushAction
import com.gatecontrol.android.notify.model.PushActionType
import com.gatecontrol.android.notify.model.PushMessage
import com.gatecontrol.android.notify.model.PushPriority
import com.gatecontrol.android.notify.model.PushState
import com.gatecontrol.android.notify.model.PushTopic
import com.gatecontrol.android.notify.model.QuietHours
import com.gatecontrol.android.notify.model.QuietInfo
import com.gatecontrol.android.notify.model.ServerControl
import com.gatecontrol.android.notify.model.ServerState
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** One parsed server-sent event of `GET /api/v1/client/push`. */
sealed interface PushEvent {
    data class Hello(
        val keepaliveS: Int,
        val via: String,
        val unread: Int,
        val topics: List<PushTopic>,
        /** `hello.state`; null on servers without control events. */
        val state: ServerState? = null,
        /** `hello.quiet`; unknown on servers that do not send it. */
        val quiet: QuietInfo = QuietInfo.UNKNOWN,
    ) : PushEvent

    data class Notification(val message: PushMessage) : PushEvent

    /** Read on another device of the same person: hide here. Notification ids. */
    data class Read(val ids: List<Long>) : PushEvent

    /** Withdrawn by the server (e.g. replaced by an all-clear). Notification ids. */
    data class Revoke(val ids: List<Long>) : PushEvent

    /** `event: control` (no id, not acked): something changed on the server. */
    data class Control(val control: ServerControl) : PushEvent

    /** Unknown or malformed event (future types, `policy`, …): ignored. */
    data class Ignored(val type: String?, val reason: String) : PushEvent
}

/**
 * Turns SSE frames (event type + data) into [PushEvent]s and server payloads
 * into [PushMessage]s. Everything the server sends is validated here: only
 * plain text is shown, actions come from the fixed list of the contract and
 * their targets must be app routes or portal paths.
 */
object PushEventParser {

    private val gson = Gson()

    const val DEFAULT_KEEPALIVE_S = 25
    private const val MAX_TITLE = 120
    private const val MAX_BODY = 1000
    private const val MAX_ACTIONS = 4
    private const val MAX_IDS = 1000
    /** The server caps `data` at 4 KB; JSON escaping may add a little. */
    private const val MAX_DATA_JSON = 8192

    private val APP_ROUTE_RE = Regex("^(?:vpn|services|gateways|inbox|plg-[a-z0-9]+(?:-[a-z0-9]+)*)$")
    private val TOPIC_RE = Regex(
        "^(?:security|devices|services|system|admin_notice|plugin:[a-z0-9]+(?:-[a-z0-9]+)*:[a-z][a-z0-9_-]{0,31})$",
    )
    /** Same shape the server accepts for the `action` of an ack. */
    val ACTION_ID_RE = Regex("^[A-Za-z0-9_.:-]{1,40}$")
    private val PORTAL_PATH_RE = Regex("^/[A-Za-z0-9._~!$&'()*+,;=:@%/?#-]{0,300}$")
    private val CONTROL_RE = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
    private val POLICY_VERSION_RE = Regex("^[A-Za-z0-9._:-]{1,64}$")
    private val CONFIG_HASH_RE = Regex("^[A-Za-z0-9+/=_.:-]{1,128}$")
    private val TIMESTAMP_RE = Regex("^[0-9TZ:+. -]{1,40}$")
    private val HHMM_RE = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
    private val TZ_RE = Regex("^[A-Za-z0-9_+/-]{1,64}$")

    fun parse(type: String?, id: String?, data: String): PushEvent = try {
        when (type) {
            "hello" -> hello(gson.fromJson(data, PushHelloDto::class.java), objectOrEmpty(data))
            "control" -> control(JsonParser.parseString(data).asJsonObject)
            "notification" -> notification(gson.fromJson(data, PushNotificationDto::class.java), id)
            "read" -> PushEvent.Read(ids(gson.fromJson(data, PushIdsDto::class.java)))
            "revoke" -> PushEvent.Revoke(ids(gson.fromJson(data, PushIdsDto::class.java)))
            else -> PushEvent.Ignored(type, "unknown_type")
        }
    } catch (e: Exception) {
        // Class name only: the payload may carry personal text.
        PushEvent.Ignored(type, "malformed_${e.javaClass.simpleName}")
    }

    private fun hello(dto: PushHelloDto?, raw: JsonObject): PushEvent.Hello {
        val topics = dto?.topics.orEmpty().mapNotNull { t ->
            val tid = t.id?.takeIf { TOPIC_RE.matches(it) } ?: return@mapNotNull null
            PushTopic(tid, clean(t.label, 80).ifBlank { tid })
        }.distinctBy { it.id }
        return PushEvent.Hello(
            keepaliveS = (dto?.keepaliveS ?: DEFAULT_KEEPALIVE_S).coerceIn(5, 600),
            via = if (dto?.via == "tunnel") "tunnel" else "direct",
            unread = (dto?.unread ?: 0).coerceAtLeast(0),
            topics = topics,
            state = raw.obj("state")?.let(::serverState),
            quiet = quietInfo(raw),
        )
    }

    /** `hello.state`; fields that are missing or malformed stay null (= unknown). */
    fun serverState(o: JsonObject): ServerState = ServerState(
        policyVersion = policyVersion(o.get("policy_version")),
        peerEnabled = o.bool("peer_enabled"),
        supportBundleRequested = o.bool("support_bundle_requested"),
        supportBundleRequestedAt = timestamp(o.str("support_bundle_requested_at")),
        configHash = o.str("config_hash")?.takeIf { CONFIG_HASH_RE.matches(it) },
    )

    private fun control(o: JsonObject): PushEvent {
        val control = when (o.str("type")) {
            "policy_changed" -> ServerControl.PolicyChanged(policyVersion(o.get("policy_version")))
            "support_bundle_requested" -> ServerControl.SupportBundleRequested(timestamp(o.str("requested_at")))
            "peer_state" -> ServerControl.PeerState(o.bool("enabled") ?: return PushEvent.Ignored("control", "invalid"))
            "config_changed" -> ServerControl.ConfigChanged
            else -> return PushEvent.Ignored("control", "unknown_control")
        }
        return PushEvent.Control(control)
    }

    /**
     * Quiet hours of `hello` or of the `GET push/prefs` answer ([o] is the
     * whole object): unknown when the field is missing (older server) or
     * malformed, none when it is null.
     */
    fun quietInfo(o: JsonObject): QuietInfo {
        if (!o.has("quiet")) return QuietInfo.UNKNOWN
        val q = o.get("quiet")
        if (q == null || q.isJsonNull) return QuietInfo.NONE
        if (!q.isJsonObject) return QuietInfo.UNKNOWN
        val qo = q.asJsonObject
        val from = qo.str("from")?.takeIf { HHMM_RE.matches(it) } ?: return QuietInfo.UNKNOWN
        val to = qo.str("to")?.takeIf { HHMM_RE.matches(it) } ?: return QuietInfo.UNKNOWN
        if (from == to) return QuietInfo.NONE
        return QuietInfo(
            known = true,
            hours = QuietHours(
                from = from,
                to = to,
                tz = qo.str("tz")?.takeIf { TZ_RE.matches(it) },
                criticalBypass = qo.bool("critical_bypass") ?: true,
            ),
        )
    }

    /**
     * Opaque version text (the server sends 16 hex characters, as in the
     * heartbeat and the policy ETag); a number is taken as its text.
     * Anything else is unknown. Never compared other than for equality.
     */
    private fun policyVersion(e: JsonElement?): String? {
        if (e == null || !e.isJsonPrimitive) return null
        val p = e.asJsonPrimitive
        val text = when {
            p.isNumber -> p.asBigDecimal.stripTrailingZeros().toPlainString()
            p.isString -> p.asString
            else -> return null
        }
        return text.takeIf { POLICY_VERSION_RE.matches(it) }
    }

    private fun objectOrEmpty(data: String): JsonObject =
        runCatching { JsonParser.parseString(data).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull() ?: JsonObject()

    private fun timestamp(value: String?): String? = value?.trim()?.takeIf { TIMESTAMP_RE.matches(it) }

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.bool(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun notification(dto: PushNotificationDto?, sseId: String?): PushEvent {
        val message = toMessage(dto, sseId?.toLongOrNull())
            ?: return PushEvent.Ignored("notification", "invalid")
        return PushEvent.Notification(message)
    }

    private fun ids(dto: PushIdsDto?): List<Long> =
        dto?.ids.orEmpty().filter { it > 0 }.distinct().take(MAX_IDS)

    /**
     * A server notification (stream event or inbox item) as [PushMessage];
     * null when it lacks what the contract guarantees (seq, id, title).
     */
    fun toMessage(dto: PushNotificationDto?, fallbackSeq: Long? = null, receivedAt: Long = 0L, via: String? = null): PushMessage? {
        if (dto == null) return null
        val seq = dto.seq ?: fallbackSeq ?: return null
        val id = dto.id ?: return null
        if (seq <= 0 || id <= 0) return null
        val title = clean(dto.title, MAX_TITLE)
        if (title.isBlank()) return null
        val topic = dto.topic?.takeIf { TOPIC_RE.matches(it) } ?: PushTopic.ADMIN_NOTICE
        val state = when (dto.state) {
            PushState.READ -> PushState.READ
            PushState.DISMISSED -> PushState.DISMISSED
            else -> PushState.DELIVERED
        }
        return PushMessage(
            seq = seq,
            id = id,
            eventId = clean(dto.eventId, 80),
            topic = topic,
            priority = PushPriority.fromWire(dto.priority).wire,
            title = title,
            body = clean(dto.body, MAX_BODY, keepNewlines = true),
            createdAt = dto.createdAt?.take(40),
            expiresAt = dto.expiresAt?.take(40),
            collapseKey = dto.collapseKey?.take(120)?.takeIf { it.isNotBlank() },
            silent = dto.silent == true,
            route = route(dto.data),
            actions = actions(dto.data),
            state = state,
            receivedAt = receivedAt,
            via = via,
            dataJson = dto.data?.toString()?.takeIf { it.length <= MAX_DATA_JSON },
        )
    }

    private fun route(data: JsonObject?): String? {
        val r = data?.get("route")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        return r.takeIf { APP_ROUTE_RE.matches(it) }
    }

    private fun actions(data: JsonObject?): List<PushAction> {
        val arr = data?.get("actions")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        val out = mutableListOf<PushAction>()
        for (el in arr) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            fun str(key: String): String? = o.get(key)?.takeIf { it.isJsonPrimitive }?.asString
            val type = str("type")?.takeIf { it in PushActionType.ALL } ?: continue
            val id = str("id")?.takeIf { ACTION_ID_RE.matches(it) } ?: type
            val label = clean(str("label"), 40)
            val target = str("target")
            val validTarget = when (type) {
                PushActionType.OPEN_APP_ROUTE -> target?.takeIf { APP_ROUTE_RE.matches(it) } ?: continue
                // A path on the own portal — never a URL to somewhere else.
                PushActionType.OPEN_PORTAL -> target?.takeIf { PORTAL_PATH_RE.matches(it) && !it.startsWith("//") } ?: continue
                else -> null
            }
            out += PushAction(id = id, label = label.ifBlank { id }, type = type, target = validTarget)
            if (out.size == MAX_ACTIONS) break
        }
        return out.distinctBy { it.id }
    }

    /** Plain text only: control characters removed, length capped. */
    private fun clean(value: String?, max: Int, keepNewlines: Boolean = false): String {
        if (value.isNullOrEmpty()) return ""
        var s = CONTROL_RE.replace(value, "")
        if (!keepNewlines) s = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
        return s.trim().take(max)
    }
}
