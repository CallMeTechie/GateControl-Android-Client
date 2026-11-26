package com.gatecontrol.android.media.cast

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.Locale

/**
 * A cast message as the plugin „Medien“ delivers it (`data = { kind: "media.cast", from, fromName, to, session, body }`):
 * from the GateControl device [from] to the device [to], both of the same person (the plugin checked that).
 */
data class CastEnvelope(
    val from: String,
    val fromName: String?,
    val to: String,
    val session: String,
    val message: CastMessage,
) {
    /** One message may come twice (event and silent push): the same sender, session and seq is the same message. */
    val key: String get() = "$from|$session|${message.seq}"
}

/** An own device that can play (`GET v1/cast/targets`): GateControl TV or GateControl for Windows. */
data class CastTarget(val id: String, val name: String, val kind: Kind, val online: Boolean) {
    enum class Kind { TV, PC }
}

/** `GET v1/cast/targets`: the own TVs and PCs and, from plugin 1.4.0, this device's own id ([self]; null: not told). */
data class CastTargetList(val self: String?, val devices: List<CastTarget>)

/** The JSON of the plugin's cast routes and deliveries. Pure, unit-tested. */
object CastEnvelopes {
    const val KIND = "media.cast"

    /** GateControl device ids (the plugin's `deviceIdOf`: the token id, digits; a little wider to stay lenient). */
    private val DEVICE_RE = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun validDevice(id: String?): Boolean = id != null && DEVICE_RE.matches(id)

    /** The delivery of an event `media.cast` or a silent push with `data.kind = "media.cast"`; null for anything else. */
    fun parse(data: JsonElement?): CastEnvelope? {
        val o = data?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        if (o.text("kind") != KIND) return null
        val from = o.id("from")?.takeIf(::validDevice) ?: return null
        val to = o.id("to")?.takeIf(::validDevice) ?: return null
        if (from == to) return null
        val session = o.text("session")?.takeIf(CastProtocol::validSession) ?: return null
        val message = CastWire.parse(o.get("body")) ?: return null
        if (message.session != session) return null
        val name = o.text("fromName")?.trim()?.filter { !it.isISOControl() }?.take(80)?.takeIf { it.isNotEmpty() }
        return CastEnvelope(from, name, to, session, message)
    }

    /** The body of `POST v1/cast/{session}/message`. */
    fun request(to: String, message: CastMessage): JsonObject = JsonObject().apply {
        addProperty("to", to)
        add("body", CastWire.toJson(message))
    }

    /**
     * This device's own GateControl id as the plugin tells it (`deviceId` of `GET v1/sources` and `GET v1/cast/targets`,
     * plugin 1.4.0): the same as `devices[].id` and the deliveries' `to` / `from`. Null: not told (an older plugin, or a
     * request without a device).
     */
    fun self(o: JsonObject?): String? = o?.id("deviceId")?.takeIf(::validDevice)

    /** `GET v1/cast/targets` with this device's own id. */
    fun targetList(o: JsonObject?): CastTargetList? = targets(o)?.let { CastTargetList(self(o), it) }

    /** `GET v1/cast/targets` → the own TVs and PCs, online first, then by name. */
    fun targets(o: JsonObject?): List<CastTarget>? {
        val list = o?.get("devices")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        return list.asSequence()
            .mapNotNull { e ->
                val d = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val id = d.id("id")?.takeIf(::validDevice) ?: return@mapNotNull null
                val kind = when (d.text("kind")) {
                    "tv" -> CastTarget.Kind.TV
                    "pc" -> CastTarget.Kind.PC
                    else -> return@mapNotNull null
                }
                val name = d.text("name")?.trim()?.filter { !it.isISOControl() }?.take(80)?.takeIf { it.isNotEmpty() } ?: id
                CastTarget(id, name, kind, d.get("online")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true)
            }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<CastTarget> { it.online }.thenBy { it.name.lowercase(Locale.ROOT) })
            .toList()
    }

    private fun JsonObject.text(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    /** Ids are text, also when the server sends a number. */
    private fun JsonObject.id(k: String): String? {
        val p = get(k)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        return when {
            p.isString -> p.asString
            p.isNumber -> p.asNumber.toString().takeIf { s -> s.all { it.isDigit() } }
            else -> null
        }
    }
}

/**
 * Which deliveries of `media.cast` are for this device and new (pure state, unit-tested; [MediaCastChannel] holds one).
 *
 * - The plugin tells this device its own id ([self]) with `deviceId` of `GET v1/sources` / `GET v1/cast/targets`
 *   (plugin 1.4.0, [learn]); with an older plugin the first silent push does: it goes to exactly the addressed device,
 *   so it is ours, and its `to` is this device's id.
 * - The realtime event goes to every open app of the person: only `to == self` counts. As long as this device does
 *   not know its id yet, an event is only taken for a session this device runs itself ([CastInbox.take]'s `mine`) —
 *   never a start (a start always comes as a silent push too).
 * - Each message once ([CastEnvelope.key]: sender, session, seq), whichever way came first.
 */
class CastInbox(self: String? = null, private val capacity: Int = 400) {

    @Volatile
    var self: String? = self?.takeIf(CastEnvelopes::validDevice)
        private set

    private val seen = LinkedHashSet<String>()

    /** What happened to a delivery. */
    enum class Verdict { TAKE, NOT_MINE, DUPLICATE }

    /**
     * [e] as it came: [push] (silent push) or the event. [mine]: whether this device runs the session [e] names (the
     * controller's own session or the receiver's current one). [onSelf]: this device learned (or changed) its id.
     */
    @Synchronized
    fun take(e: CastEnvelope, push: Boolean, mine: (String) -> Boolean = { false }, onSelf: (String) -> Unit = {}): Verdict {
        if (push) {
            if (self != e.to) {
                self = e.to
                onSelf(e.to)
            }
        } else {
            val me = self
            val ours = if (me != null) e.to == me else e.message !is CastMessage.Play && mine(e.session)
            if (!ours) return Verdict.NOT_MINE
        }
        if (!seen.add(e.key)) return Verdict.DUPLICATE
        while (seen.size > capacity) seen.remove(seen.first())
        return Verdict.TAKE
    }

    /**
     * The plugin told this device its own id ([CastEnvelopes.self]); [onSelf] when it is new or another. False when it
     * was known already or is no id.
     */
    @Synchronized
    fun learn(id: String?, onSelf: (String) -> Unit = {}): Boolean {
        val me = id?.takeIf(CastEnvelopes::validDevice) ?: return false
        if (self == me) return false
        self = me
        onSelf(me)
        return true
    }

    @Synchronized
    fun clear() {
        seen.clear()
        self = null
    }
}
