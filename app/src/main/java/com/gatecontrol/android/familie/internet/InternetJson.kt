package com.gatecontrol.android.familie.internet

import com.gatecontrol.android.familie.FamilieJson
import com.gatecontrol.android.familie.FamilieJson.arr
import com.gatecontrol.android.familie.FamilieJson.bool
import com.gatecontrol.android.familie.FamilieJson.clean
import com.gatecontrol.android.familie.FamilieJson.int
import com.gatecontrol.android.familie.FamilieJson.obj
import com.gatecontrol.android.familie.FamilieJson.objects
import com.gatecontrol.android.familie.FamilieJson.stamp
import com.gatecontrol.android.familie.FamilieJson.str
import com.gatecontrol.android.familie.presence.PresenceState
import com.google.gson.JsonObject

/**
 * Parsers and bodies of „Internet zu Hause“ and the Wi-Fi detection —
 * hand-written like [FamilieJson]: a field an older plugin does not send
 * counts as "not there", malformed entries are dropped, never an exception.
 */
object InternetJson {

    private const val NAME_MAX = 60
    private const val MAX_CHILDREN = 50
    private const val MAX_DEVICES = 100
    private val CODE_RE = Regex("^[a-z0-9_]{1,40}$")
    private val ZONE_RE = Regex("^[A-Za-z0-9_+\\-/]{1,64}$")

    /** `GET internet` → `internet`; null when the answer has none (not this API). */
    fun payload(o: JsonObject): InternetPayload? {
        val i = o.obj("internet") ?: return null
        val available = i.bool("available") == true
        return InternetPayload(
            available = available,
            manage = i.bool("manage") == true,
            children = if (!available) emptyList() else i.arr("children")?.objects()?.mapNotNull(::child)?.distinctBy { it.userId }?.take(MAX_CHILDREN).orEmpty(),
            serverTime = stamp(i.str("serverTime")),
            timezone = i.str("timezone")?.takeIf { ZONE_RE.matches(it) },
        )
    }

    /** `POST internet/<childId>/<op>` → `child`. */
    fun childAnswer(o: JsonObject): InternetChild? = o.obj("child")?.let(::child)

    fun child(o: JsonObject): InternetChild? {
        val id = o.int("userId")?.takeIf { it > 0 } ?: return null
        val name = clean(o.str("name"), NAME_MAX).ifBlank { "#$id" }
        return InternetChild(
            userId = id,
            name = name,
            color = FamilieJson.memberColor(o),
            face = FamilieJson.face(o, name),
            presence = o.obj("presence")?.let(::presence),
            paused = stamp(o.str("paused")),
            pausedBy = o.obj("pausedBy")?.let(::by),
            extended = stamp(o.str("extended")),
            extendedBy = o.obj("extendedBy")?.let(::by),
            nextSchedule = o.obj("nextSchedule")?.let(::schedule),
            profile = o.str("profile")?.let { clean(it, NAME_MAX) }?.takeIf { it.isNotBlank() },
            devices = (o.int("devices") ?: 0).coerceIn(0, 999),
            filtered = o.bool("filtered") == true,
            error = o.str("error")?.takeIf { CODE_RE.matches(it) },
        )
    }

    private fun presence(o: JsonObject): InternetPresence? {
        val state = PresenceState.fromWire(o.str("state")) ?: return null
        return InternetPresence(state, stamp(o.str("since")), source(o.str("source")))
    }

    /** `wifi` | `app`, anything else null. */
    fun source(value: String?): String? =
        value?.takeIf { it == InternetPresence.SOURCE_WIFI || it == InternetPresence.SOURCE_APP }

    private fun by(o: JsonObject): InternetBy {
        val rule = o.obj("rule")
        val ruleId = rule?.int("id")?.takeIf { it > 0 }
        return InternetBy(
            userId = o.int("userId"),
            name = clean(o.str("name"), NAME_MAX),
            at = stamp(o.str("at")),
            ruleId = ruleId,
            ruleTitle = if (ruleId != null) clean(rule.str("title"), 80) else "",
        )
    }

    private fun schedule(o: JsonObject): InternetSchedule? {
        val from = stamp(o.str("from")) ?: return null
        val given = o.arr("categories")?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }.orEmpty().toSet()
        return InternetSchedule(
            from = from,
            to = stamp(o.str("to")),
            all = o.str("block") != "categories",
            categories = InternetSchedule.CATEGORIES.filter { it in given },
            active = o.bool("active") == true,
        )
    }

    // ─── bodies ──────────────────────────────────────────────────────

    fun pauseBody(request: PauseRequest): JsonObject = JsonObject().apply {
        when (request) {
            is PauseRequest.Minutes -> addProperty("minutes", request.minutes.coerceIn(1, 10_080))
            is PauseRequest.Until -> addProperty("until", request.time)
            is PauseRequest.Add -> addProperty("add", request.minutes.coerceIn(1, 1_440))
        }
    }

    /** `POST internet/<childId>/extend`: [minutes] 0 ends the extra time; [fromNow] instead of the blocked time's start. */
    fun extendBody(minutes: Int, fromNow: Boolean = false): JsonObject = JsonObject().apply {
        addProperty("minutes", minutes.coerceIn(0, 1_440))
        if (fromNow) addProperty("fromNow", true)
    }

    // ─── Wi-Fi detection ─────────────────────────────────────────────

    /** `GET presence/wifi`, `PUT presence/wifi`, `PUT presence/wifi/devices/<id>` → `wifi`. */
    fun wifi(o: JsonObject): WifiPresence? {
        val w = o.obj("wifi") ?: return null
        val available = w.bool("available") == true
        return WifiPresence(
            available = available,
            consent = available && w.bool("consent") == true,
            since = stamp(w.str("since")),
            devices = if (!available) emptyList() else w.arr("devices")?.objects()?.mapNotNull { d ->
                val id = d.int("id")?.takeIf { it > 0 } ?: return@mapNotNull null
                WifiDevice(
                    id = id,
                    name = clean(d.str("name"), NAME_MAX).ifBlank { "#$id" },
                    lastSeen = stamp(d.str("lastSeen")),
                    counts = d.bool("counts") == true,
                    gateway = d.str("gateway")?.let { clean(it, NAME_MAX) }?.takeIf { it.isNotBlank() },
                )
            }?.distinctBy { it.id }?.take(MAX_DEVICES).orEmpty(),
        )
    }

    fun wifiBody(enabled: Boolean): JsonObject = JsonObject().apply { addProperty("enabled", enabled) }

    fun wifiDeviceBody(counts: Boolean): JsonObject = JsonObject().apply { addProperty("counts", counts) }
}
