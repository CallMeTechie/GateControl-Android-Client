package com.gatecontrol.android.dnsprotect

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Reads the plugin's answers; lenient: a missing or odd field is its default, never an exception. */
object DnsJson {

    private fun JsonObject.el(key: String): JsonElement? = get(key)?.takeUnless { it.isJsonNull }
    private fun JsonObject.obj(key: String): JsonObject? = el(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.arr(key: String): JsonArray? = el(key)?.takeIf { it.isJsonArray }?.asJsonArray
    private fun JsonObject.str(key: String): String? =
        el(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.bool(key: String): Boolean =
        el(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false
    private fun JsonObject.num(key: String): Number? =
        el(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asNumber
    private fun JsonArray.objects(): List<JsonObject> = filter { it.isJsonObject }.map { it.asJsonObject }

    /** `GET status`, also the `status` of a `pause` / `resume` answer. Null without a `state`. */
    fun status(o: JsonObject): DnsStatus? {
        val state = o.str("state") ?: return null
        val c = o.obj("counters")
        return DnsStatus(
            state = state,
            device = o.obj("device")?.let { d ->
                val id = d.num("peerId")?.toInt() ?: return@let null
                DnsDevice(id, d.str("name").orEmpty(), d.str("state").orEmpty(), d.str("ownDns"))
            },
            profileName = o.obj("profile")?.str("name")?.takeIf { it.isNotBlank() },
            pause = o.obj("pause")?.let(::pause),
            outageSince = o.obj("outage")?.str("since"),
            counters = DnsCounters(
                scope = c?.str("scope").orEmpty(),
                queries = c?.num("queries")?.toLong() ?: 0,
                blocked = c?.num("blocked")?.toLong() ?: 0,
                percent = c?.num("percent")?.toDouble() ?: 0.0,
            ),
            top = o.arr("top")?.objects()?.mapNotNull { t ->
                val domain = t.str("domain")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                DnsTopDomain(domain, t.num("count")?.toLong() ?: 0)
            },
            may = may(o.obj("may")),
            today = o.arr("today")?.objects()?.mapNotNull { t ->
                val from = t.str("from") ?: return@mapNotNull null
                val to = t.str("to") ?: return@mapNotNull null
                DnsScheduleEntry(from, to, t.str("block").orEmpty(), strings(t.arr("categories")))
            }.orEmpty(),
            manage = o.bool("manage"),
            serverTime = o.str("serverTime"),
        )
    }

    /** The `status` inside a `pause` / `resume` answer. */
    fun changed(o: JsonObject): DnsStatus? = o.obj("status")?.let(::status)

    fun pause(o: JsonObject): DnsPause = DnsPause(
        scope = o.str("scope").orEmpty(),
        until = o.str("until"),
        openEnd = o.bool("openEnd"),
        since = o.str("since"),
        byName = o.obj("by")?.str("name")?.takeIf { it.isNotBlank() },
        byMe = o.bool("byMe"),
        remainingSeconds = o.num("remainingSeconds")?.toLong(),
    )

    fun may(o: JsonObject?): DnsMay = if (o == null) DnsMay() else DnsMay(
        pause = o.bool("pause"),
        resume = o.bool("resume"),
        extend = o.bool("extend"),
        reason = o.str("reason"),
        durations = o.arr("durations")
            ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt?.takeIf { it > 0 } }
            .orEmpty(),
        maxMinutes = o.num("maxMinutes")?.toInt() ?: 0,
        forever = o.bool("forever"),
    )

    /** `GET people`. */
    fun people(o: JsonObject): DnsPeople = DnsPeople(
        all = o.obj("all")?.let { a -> DnsAll(may(a.obj("may")), a.obj("pause")?.let(::pause)) },
        people = o.arr("people")?.objects()?.mapNotNull { p ->
            val id = p.num("userId")?.toInt() ?: return@mapNotNull null
            DnsPerson(
                userId = id,
                name = p.str("name").orEmpty(),
                self = p.bool("self"),
                may = may(p.obj("may")),
                devices = p.arr("devices")?.objects()?.mapNotNull { d ->
                    val peer = d.num("peerId")?.toInt() ?: return@mapNotNull null
                    DnsPersonDevice(
                        peerId = peer,
                        name = d.str("name").orEmpty(),
                        state = d.str("state").orEmpty(),
                        suspended = d.bool("suspended"),
                        pause = d.obj("pause")?.let(::pause),
                        may = may(d.obj("may")),
                    )
                }.orEmpty(),
            )
        }.orEmpty(),
    )

    /** Body of `POST pause`: exactly one of `minutes` / `forever`, and the target. */
    fun pauseBody(duration: DnsDuration, target: DnsTarget): JsonObject = targetBody(target).apply {
        when (duration) {
            is DnsDuration.Minutes -> addProperty("minutes", duration.minutes)
            DnsDuration.Forever -> addProperty("forever", true)
        }
    }

    /** Body of `POST resume`: `{}` for the asking device, else the target. */
    fun targetBody(target: DnsTarget): JsonObject = JsonObject().apply {
        when (target) {
            DnsTarget.Own -> Unit
            is DnsTarget.Peer -> addProperty("peerId", target.peerId)
            is DnsTarget.User -> addProperty("userId", target.userId)
            DnsTarget.All -> addProperty("all", true)
        }
    }

    /** `{ ok: false, code, error, reason }` of a refusal. */
    fun failed(body: JsonObject?, http: Int): DnsResult.Failed = DnsResult.Failed(
        code = body?.str("code")?.takeIf { it.isNotBlank() } ?: "http_$http",
        http = http,
        message = body?.str("error")?.takeIf { it.isNotBlank() }?.take(300),
        reason = body?.str("reason"),
    )

    private fun strings(a: JsonArray?): List<String> =
        a?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString }.orEmpty()
}
