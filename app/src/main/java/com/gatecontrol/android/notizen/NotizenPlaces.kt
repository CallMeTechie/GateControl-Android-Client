package com.gatecontrol.android.notizen

import com.gatecontrol.android.notizen.NotizenJson.arr
import com.gatecontrol.android.notizen.NotizenJson.bool
import com.gatecontrol.android.notizen.NotizenJson.int
import com.gatecontrol.android.notizen.NotizenJson.long
import com.gatecontrol.android.notizen.NotizenJson.num
import com.gatecontrol.android.notizen.NotizenJson.obj
import com.gatecontrol.android.notizen.NotizenJson.str
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

// The places' answers and models (NPlace, NSpot, NWatch …, NotizenPlacesJson) are in :core:notizen-api; here the
// rules the phone applies itself with its own position (geofences, „in der Nähe“).

/** A task „Gerade in der Nähe“ (the card of „Mein Tag“, made on the phone). */
data class NNearNow(val fence: NFence, val task: NFenceTask, val distanceM: Int?)

/** The rules of stage 3 the phone applies itself (pure, unit-tested). */
object PlaceLogic {
    /** [v] rounded to [decimals] places — a position leaves the phone only like this (two: about one kilometre). */
    fun round(v: Double, decimals: Int): Double {
        val f = 10.0.pow(decimals.coerceIn(0, 6))
        return (v * f).roundToLong() / f
    }

    /** The rounded position for `places/search` and `places/watch`; null without a position. */
    fun near(lat: Double?, lon: Double?, decimals: Int): Pair<Double, Double>? =
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) null else round(lat, decimals) to round(lon, decimals)

    /** Metres as the crow flies. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val a = sin((p2 - p1) / 2).pow(2) + cos(p1) * cos(p2) * sin(Math.toRadians(lon2 - lon1) / 2).pow(2)
        return (2 * 6_371_000.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))).roundToInt()
    }

    /** The server's estimate (30 km/h over 1.3 times the distance) — no real travel time. */
    fun minutes(distanceM: Int): Int = maxOf(1, (distanceM * 1.3 / 500).roundToInt())

    /**
     * The hits measured from the phone's exact position ([lat], [lon] — it never leaves the phone) and sorted again: the
     * remembered one first, the others by distance. Without a position they stay as the server sent them.
     */
    fun measured(results: List<NSpot>, lat: Double?, lon: Double?): List<NSpot> {
        if (lat == null || lon == null) return results
        val m = results.map { s ->
            if (s.lat == null || s.lon == null) s else distanceM(lat, lon, s.lat!!, s.lon!!).let { d -> s.copy(distanceM = d, minutes = minutes(d)) }
        }
        return m.filter { it.fav } + m.filterNot { it.fav }.sortedBy { it.distanceM ?: Int.MAX_VALUE }
    }

    /** „300 m“ / „2,4 km“. */
    fun distanceText(m: Int, lang: String): String =
        if (m < 1000) "$m m" else String.format(if (lang == "en") java.util.Locale.ENGLISH else java.util.Locale.GERMAN, "%.1f km", m / 1000.0)

    /**
     * The title of a new task without the words of its place: the server's ([NPlaceSuggest.title]) while it read the
     * same line as the phone ([serverTitle] = [title]); else [word] cut out here. One word left: the title stays whole.
     */
    fun titleWithout(title: String, suggest: NPlaceSuggest?, serverTitle: String?): String {
        if (suggest == null) return title
        if (suggest.title != null && serverTitle != null && serverTitle.trim() == title.trim()) return suggest.title!!
        val word = suggest.word ?: return title
        val at = title.indexOf(word, ignoreCase = true)
        if (at < 0) return title
        val rest = (title.substring(0, at) + " " + title.substring(at + word.length)).trim().replace(Regex("\\s+"), " ")
        return if (rest.split(' ').count { it.isNotBlank() } < 2) title else rest
    }

    /** Where [word] stands in [text] (to colour it); null: not there. */
    fun wordRange(text: String, word: String?): IntRange? {
        val at = word?.takeIf { it.isNotBlank() }?.let { text.indexOf(it, ignoreCase = true) } ?: return null
        return if (at < 0) null else at until at + word.length
    }

    /**
     * The fences this phone registers: the server's, in its order (= importance), as many as fit into [budget] — the
     * places the Familie plugin watches come first and are never pushed out ([com.gatecontrol.android.familie.places.GeofenceBudget]).
     */
    fun select(fences: List<NFence>, budget: Int): List<NFence> = fences.distinctBy { it.id }.take(budget.coerceAtLeast(0))

    /** The phone moved farther than `refreshM` from the cell the places were chosen for: ask again. */
    fun moved(watch: NWatch, lat: Double, lon: Double): Boolean {
        val cLat = watch.cellLat ?: return true
        val cLon = watch.cellLon ?: return true
        return distanceM(cLat, cLon, lat, lon) > watch.refreshM
    }

    /** „Gerade in der Nähe“: the tasks of the fences the phone is inside ([inside]) that would speak up on arriving. */
    fun nearNow(watch: NWatch, state: PlaceState, lat: Double?, lon: Double?, nowMs: Long): List<NNearNow> =
        watch.fences.filter { it.id in state.inside }.flatMap { f ->
            f.tasks.filter { t -> t.trigger == NPlace.ENTER && PlaceGuard.muted(state, t.taskId, f.id, nowMs) == null }
                .map { t -> NNearNow(f, t, if (lat != null && lon != null) distanceM(lat, lon, f.lat, f.lon) else null) }
        }.distinctBy { it.task.taskId }
}

/**
 * What the phone remembers about its places besides the server's list: when a place fired for a task, „Später“ and
 * „Nicht hier“ pressed here (they work at once, also before the server knows), and the fences it is inside.
 */
data class PlaceState(
    /** `<taskId>|<fence>` → when it reminded. */
    val fired: Map<String, Long> = emptyMap(),
    /** Task → when a hint „in der Nähe“ of it came last. */
    val hinted: Map<Long, Long> = emptyMap(),
    /** Task → until when „Später“ holds. */
    val later: Map<Long, Long> = emptyMap(),
    /** `<taskId>|<fence>`: „Nicht hier“. */
    val skipped: Set<String> = emptySet(),
    val inside: Set<String> = emptySet(),
    /** The fence ids registered with Android. */
    val registered: List<String> = emptyList(),
    /** The side of each registered fence (and of `cell`), see [com.gatecontrol.android.familie.places.FenceSides]. */
    val sides: Map<String, com.gatecontrol.android.familie.places.FenceSide> = emptyMap(),
) {
    fun toJson() = JsonObject().apply {
        add("fired", JsonObject().apply { fired.forEach { (k, v) -> addProperty(k, v) } })
        add("hinted", JsonObject().apply { hinted.forEach { (k, v) -> addProperty(k.toString(), v) } })
        add("later", JsonObject().apply { later.forEach { (k, v) -> addProperty(k.toString(), v) } })
        add("skipped", JsonArray().apply { skipped.forEach { add(it) } })
        add("inside", JsonArray().apply { inside.forEach { add(it) } })
        add("registered", JsonArray().apply { registered.forEach { add(it) } })
        add("sides", com.gatecontrol.android.familie.places.FenceSides.write(sides))
    }

    companion object {
        private val PAIR = Regex("^[1-9]\\d{0,14}\\|(?:p:\\d{1,15}|osm:[NWR]\\d{1,18})$")

        private fun times(o: JsonObject?): Map<String, Long> = runCatching {
            o?.entrySet()?.mapNotNull { (k, v) -> runCatching { v.asLong }.getOrNull()?.let { k to it } }?.toMap()
        }.getOrNull().orEmpty()

        private fun strings(a: JsonArray?): List<String> = a?.mapNotNull { runCatching { it.asString }.getOrNull() }.orEmpty()

        /** Tolerant: what cannot be read is left out. */
        fun fromJson(o: JsonObject?): PlaceState {
            if (o == null) return PlaceState()
            val byTask = { k: String -> times(o.obj(k)).mapNotNull { (t, v) -> t.toLongOrNull()?.takeIf { it > 0 }?.let { it to v } }.toMap() }
            return PlaceState(
                fired = times(o.obj("fired")).filterKeys { PAIR.matches(it) },
                hinted = byTask("hinted"),
                later = byTask("later"),
                skipped = strings(o.arr("skipped")).filter { PAIR.matches(it) }.toSet(),
                inside = strings(o.arr("inside")).mapNotNull { NotizenPlacesJson.fenceId(it) }.toSet(),
                registered = strings(o.arr("registered")).mapNotNull { NotizenPlacesJson.fenceId(it) },
                sides = com.gatecontrol.android.familie.places.FenceSides.read(o.obj("sides")).filterKeys { it == "cell" || NotizenPlacesJson.fenceId(it) != null },
            )
        }
    }
}

/**
 * May a place remind — decided on the phone (pure, unit-tested). „Später“ and „Nicht hier“ pressed here hold at once
 * ([muted]); without a connection the phone applies the server's rules itself ([offline]): at most once per place and
 * task in three hours; a hint „in der Nähe“ at most once per task in three hours, not in the quiet hours, not when it
 * is switched off.
 */
object PlaceGuard {
    /** As the server: the same place does not remind again within this; „in der Nähe“ of one task neither. */
    const val REPEAT_MS = 3 * 3_600_000L
    /** „Später“ at a place. */
    const val LATER_MS = 3 * 3_600_000L
    private const val KEEP_MS = 8 * 86_400_000L
    private const val MAX = 400

    fun pair(taskId: Long, fence: String) = "$taskId|$fence"

    /** Why [taskId] stays quiet at [fence] by what was pressed on this phone (`later`, `skipped`); null: it does not. */
    fun muted(state: PlaceState, taskId: Long, fence: String, nowMs: Long): String? = when {
        pair(taskId, fence) in state.skipped -> "skipped"
        (state.later[taskId] ?: 0) > nowMs -> "later"
        else -> null
    }

    /** Without a connection: null → show the reminder; else the reason it stays quiet (the server's words). */
    fun offline(task: NFenceTask, fence: String, transition: String, watch: NWatch, state: PlaceState, zone: String?, nowMs: Long): String? {
        if (task.trigger != transition) return "transition"
        muted(state, task.taskId, fence, nowMs)?.let { return it }
        if (task.nearby) {
            if (!watch.nearby) return "off"
            val q = watch.quiet
            if (q != null && ReminderGuard.inQuietHours(q.from, q.to, zone, nowMs)) return "quiet"
            if (recent(state.hinted[task.taskId], nowMs)) return "recent"
        }
        if (recent(state.fired[pair(task.taskId, fence)], nowMs)) return "recent"
        return null
    }

    private fun recent(at: Long?, nowMs: Long): Boolean = at != null && nowMs >= at && nowMs - at < REPEAT_MS

    /** The reminder of [task] at [fence] is shown now. */
    fun shown(state: PlaceState, task: NFenceTask, fence: String, nowMs: Long): PlaceState = pruned(
        state.copy(
            fired = state.fired + (pair(task.taskId, fence) to nowMs),
            hinted = if (task.nearby) state.hinted + (task.taskId to nowMs) else state.hinted,
        ),
        nowMs,
    )

    fun later(state: PlaceState, taskId: Long, nowMs: Long): PlaceState = pruned(state.copy(later = state.later + (taskId to nowMs + LATER_MS)), nowMs)

    fun skipped(state: PlaceState, taskId: Long, fence: String): PlaceState = state.copy(skipped = (state.skipped + pair(taskId, fence)).toList().takeLast(MAX).toSet())

    /** The phone entered or left [fences]. */
    fun moved(state: PlaceState, fences: Collection<String>, entered: Boolean): PlaceState =
        state.copy(inside = if (entered) state.inside + fences else state.inside - fences.toSet())

    /** What is long over goes; what belongs to no task of [tasks] any more (null: unknown) as well. */
    fun pruned(state: PlaceState, nowMs: Long, tasks: Set<Long>? = null): PlaceState {
        val live = { t: Long -> tasks == null || t in tasks }
        val of = { k: String -> k.substringBefore('|').toLongOrNull() ?: 0L }
        return state.copy(
            fired = state.fired.filter { (k, v) -> nowMs - v < KEEP_MS && live(of(k)) }.entries.sortedBy { it.value }.takeLast(MAX).associate { it.key to it.value },
            hinted = state.hinted.filter { (k, v) -> nowMs - v < KEEP_MS && live(k) },
            later = state.later.filter { (k, v) -> v > nowMs && live(k) },
            skipped = state.skipped.filter { live(of(it)) }.toSet(),
        )
    }

    /**
     * The buttons the phone shows for [task] — the server's, in its order, as far as the phone can do them: „Erledigt“,
     * „Später“, „Nicht hier“, and „Liste öffnen“ only while the task carries a list.
     */
    fun buttons(task: NFenceTask): List<NReminderAction> = task.actions.filter { a ->
        a.id == DONE || a.id == LATER || a.id == NOT_HERE || (a.id == NotizenPush.ACTION_OPEN_LIST && task.attachedList != null)
    }.take(3)

    const val DONE = "done"
    const val LATER = "later"
    const val NOT_HERE = "not_here"
}
