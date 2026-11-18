package com.gatecontrol.android.familie.places

import com.google.gson.JsonObject
import java.util.Locale

/*
 * The pattern of the Ortsnachrichten (ChatPlaceWatch, PR #255) for every geofence of the app — Familie reminders and rule
 * places, the places of Notizen tasks. Pure, unit-tested.
 *
 * The fences are replaced in place (the same request id), never all removed first, and added with
 * `INITIAL_TRIGGER_ENTER | _EXIT`: Android says at once which side the phone is on. The phone keeps the last side of
 * each fence; adding them again (each start, a boot, an update, a changed place) is then harmless — the report on adding
 * is an arrival only when the phone was outside before (an arrival the old fence had not reported yet is not lost), and
 * nothing when it was already there. A new or changed fence only learns its side.
 */

/** The side of a fence the phone was last on; [side] null: added, not reported yet. [sig]: what makes it the same fence. */
data class FenceSide(val sig: String, val side: String?, val sinceMs: Long)

object FenceSides {
    const val IN = "in"
    const val OUT = "out"

    /**
     * The smallest radius the phone watches. Google asks for at least 100–150 m and inside a building a position is often
     * only good to 100 m or worse: a smaller circle may never report the arrival. The saved radius stays as it is.
     */
    const val MIN_RADIUS_M = 150

    fun radius(m: Int): Int = m.coerceAtLeast(MIN_RADIUS_M)

    /** What makes a fence the same one: the point, the (watched) radius and what it waits for. */
    fun signature(lat: Double, lon: Double, radiusM: Int, what: String = ""): String =
        "%.6f|%.6f|%d|%s".format(Locale.ROOT, lat, lon, radius(radiusM), what)

    /**
     * After adding [fences] (request id → signature): the kept side of each that is the same fence as before; a new or
     * changed one starts unknown; those no longer watched go.
     */
    fun afterRegister(prev: Map<String, FenceSide>, fences: Map<String, String>, nowMs: Long): Map<String, FenceSide> =
        fences.mapValues { (id, sig) -> prev[id]?.takeIf { it.sig == sig } ?: FenceSide(sig, null, nowMs) }

    /**
     * Android reports [side] for a fence whose state was [state]: the new state and whether this is a transition to act
     * on. Unknown fence (none kept — registered by an older version of the app): a transition, as it was then.
     */
    fun observe(state: FenceSide?, side: String, nowMs: Long): Pair<FenceSide?, Boolean> = when {
        state == null -> null to true
        state.side == null -> state.copy(side = side, sinceMs = nowMs) to false
        state.side == side -> state to false
        else -> state.copy(side = side, sinceMs = nowMs) to true
    }

    /**
     * What to remove before adding [wanted]: the ones registered before ([before]) that are no longer wanted. null
     * [before] (the list of an older version of the app, which did not keep it): everything of the receiver, once.
     */
    fun toRemove(before: Collection<String>?, wanted: Collection<String>): Set<String>? = before?.toSet()?.minus(wanted.toSet())

    fun sideOf(enter: Boolean): String = if (enter) IN else OUT

    // ─── storage ────────────────────────────────────────────────────

    fun write(m: Map<String, FenceSide>): JsonObject = JsonObject().apply {
        m.forEach { (id, f) ->
            add(id, JsonObject().apply { addProperty("sig", f.sig); f.side?.let { addProperty("side", it) }; addProperty("since", f.sinceMs) })
        }
    }

    fun read(o: JsonObject?): Map<String, FenceSide> {
        if (o == null) return emptyMap()
        return o.entrySet().mapNotNull { (id, v) ->
            val f = v.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val sig = f.get("sig")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
            val side = f.get("side")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it == IN || it == OUT }
            id to FenceSide(sig, side, f.get("since")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong ?: 0L)
        }.toMap()
    }
}
