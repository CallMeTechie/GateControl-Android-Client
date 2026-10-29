package com.gatecontrol.android.drive.work

import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DriveLogic
import java.time.ZoneId
import kotlin.math.roundToInt

/** Where a WLAN puts the photos taken in it ("Firma", "Zuhause", "Weder noch"); set by the person, kept on the phone. */
enum class WifiZone { WORK, HOME, NEITHER }

/** The WLANs the person assigned, by name (normalised SSID). */
data class WifiZones(val work: Set<String> = emptySet(), val home: Set<String> = emptySet(), val neither: Set<String> = emptySet()) {
    fun zoneOf(ssid: String?): WifiZone? = when (ssid) {
        null -> null
        in work -> WifiZone.WORK
        in home -> WifiZone.HOME
        in neither -> WifiZone.NEITHER
        else -> null
    }

    /** [ssid] into [zone] (out of the others); null: forgotten. */
    fun with(ssid: String, zone: WifiZone?): WifiZones = WifiZones(
        work = if (zone == WifiZone.WORK) work + ssid else work - ssid,
        home = if (zone == WifiZone.HOME) home + ssid else home - ssid,
        neither = if (zone == WifiZone.NEITHER) neither + ssid else neither - ssid,
    )

    /** A WLAN to ask about: not assigned yet. */
    fun unknown(ssid: String?): Boolean = ssid != null && zoneOf(ssid) == null
}

/** Pure part of "Arbeitsfotos getrennt sichern" (unit-tested). */
object DriveWorkLogic {

    /** Where a photo goes: the work folder or the private one (Kamera-Uploads). */
    enum class Dest { WORK, PRIVATE }

    /** Why it goes there. */
    enum class Why { WIFI_WORK, WIFI_HOME, AI_WORK, AI_PRIVATE, REVIEW }

    /**
     * Where a new photo goes: [dest]; [review] it gets the mark "zu prüfen" with [hint] (the AI's "work" in percent);
     * [learn] the AI learns it as work (true) or private (false), null: nothing to learn.
     */
    data class Placement(val dest: Dest, val review: Boolean, val hint: Int?, val learn: Boolean?, val why: Why)

    /** The AI decides alone only this sure (and only after it learned, with "Selbst einsortieren, wenn sicher"). */
    const val SURE_WORK = 0.85
    const val SURE_PRIVATE = 0.15

    /** Examples before the AI may decide alone ("Lernphase"). */
    const val MIN_EXAMPLES = 30

    /** Of each kind at least, so the model has seen both. */
    const val MIN_EACH = 3

    /** "Aufräumen": shown from, and ticked from. */
    const val CLEANUP_SHOW = 0.5
    const val CLEANUP_PRESELECT = 0.70

    /**
     * Where a new photo goes. [zone]: the WLAN it was taken in (null: none, not assigned, or unknown — no permission);
     * [score]: the AI's probability "work" (null: no answer); [ready]: past the learning phase; [autoSort]: "Selbst
     * einsortieren, wenn sicher". Firma → work, Zuhause → private, both are examples to learn from; else the AI when it
     * is sure and may, otherwise private with the review mark.
     */
    fun place(zone: WifiZone?, score: Double?, ready: Boolean, autoSort: Boolean): Placement {
        when (zone) {
            WifiZone.WORK -> return Placement(Dest.WORK, review = false, hint = null, learn = true, why = Why.WIFI_WORK)
            WifiZone.HOME -> return Placement(Dest.PRIVATE, review = false, hint = null, learn = false, why = Why.WIFI_HOME)
            WifiZone.NEITHER, null -> Unit
        }
        val hint = score?.let { percent(it) }
        if (ready && autoSort && score != null) {
            if (score >= SURE_WORK) return Placement(Dest.WORK, review = false, hint = null, learn = null, why = Why.AI_WORK)
            if (score <= SURE_PRIVATE) return Placement(Dest.PRIVATE, review = false, hint = null, learn = null, why = Why.AI_PRIVATE)
        }
        return Placement(Dest.PRIVATE, review = true, hint = hint, learn = null, why = Why.REVIEW)
    }

    fun percent(p: Double): Int = (p.coerceIn(0.0, 1.0) * 100).roundToInt()

    // ─── what the AI has learned ────────────────────────────────────

    /** The examples so far: all, work, private, and of those how many came from the WLANs. */
    data class Learned(val total: Int = 0, val work: Int = 0, val private: Int = 0, val workFromWifi: Int = 0, val privateFromWifi: Int = 0) {
        /** Past the learning phase: enough examples, of both kinds. */
        val ready: Boolean get() = total >= MIN_EXAMPLES && work >= MIN_EACH && private >= MIN_EACH

        /** "23 von 30 Beispielen" (never above 30). */
        val progress: Int get() = total.coerceAtMost(MIN_EXAMPLES)

        /** Enough to suggest anything (Aufräumen, hints): a few of each. */
        val usable: Boolean get() = work >= MIN_EACH && private >= MIN_EACH
    }

    // ─── the selection ──────────────────────────────────────────────

    /** Marked "zu prüfen": on the server ([DNode.review]) or, without Drive 1.13, on this phone ([local]). */
    fun isReview(node: DNode, local: Map<Long, Int?>): Boolean = node.review != null || node.id in local

    /** The hint of a marked photo (server first, else this phone's). */
    fun hintOf(node: DNode, local: Map<Long, Int?>): Int? = node.review?.hint ?: local[node.id]

    /** The big buttons "Arbeit" and "Privat" in front of the bar: only when every photo ticked is to be reviewed. */
    fun reviewBar(selected: List<DNode>, local: Map<Long, Int?>): Boolean = selected.isNotEmpty() && selected.all { isReview(it, local) }

    /** The AI's line above the buttons: "wahrscheinlich Arbeit · 72 % sicher" ([work] true) or "eher privat · 62 %". */
    data class Hint(val work: Boolean, val sure: Int)

    /** The mean of the hints of the ticked photos; null when none has one. */
    fun hint(hints: List<Int?>): Hint? {
        val known = hints.filterNotNull()
        if (known.isEmpty()) return null
        val mean = (known.sum().toDouble() / known.size).roundToInt()
        return if (mean >= 50) Hint(true, mean) else Hint(false, 100 - mean)
    }

    /** What "Mehr › Zuordnung" offers for [nodes]: work (not all work yet), private (some work or marked), review. */
    data class Offer(val work: Boolean, val private: Boolean, val review: Boolean)

    fun offer(nodes: List<DNode>, local: Map<Long, Int?>): Offer = Offer(
        work = nodes.any { !it.work || isReview(it, local) },
        private = nodes.any { it.work || isReview(it, local) },
        review = nodes.any { !isReview(it, local) },
    )

    // ─── moving photos to where they belong ─────────────────────────

    /** The folders below the work folder or Kamera-Uploads a photo goes to: its year and month (`["2026", "10"]`). */
    fun monthPath(node: DNode, zone: ZoneId = ZoneId.systemDefault()): List<String> {
        val m = DriveLogic.monthOf(node, zone) ?: return emptyList()
        return listOf(m.substring(0, 4), m.substring(5, 7))
    }

    /**
     * Which of [nodes] move where for [to]: work — those not under the work folder yet ([underWork]; a marked photo
     * there goes into its month's folder too), below it by month when [subfolders]; private — those under the work
     * folder, into Kamera-Uploads by month (the others stay where they are). Each path (empty: the folder itself) with
     * its photos, in the order given. [underWork] defaults to the server's `work` (false for a marked photo).
     */
    fun moves(
        nodes: List<DNode>,
        to: Dest,
        subfolders: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
        underWork: (DNode) -> Boolean = { it.work },
    ): Map<List<String>, List<DNode>> {
        val going = when (to) {
            Dest.WORK -> nodes.filter { !it.work }
            Dest.PRIVATE -> nodes.filter { underWork(it) }
        }
        val out = LinkedHashMap<List<String>, MutableList<DNode>>()
        for (n in going) {
            val path = if (to == Dest.WORK && !subfolders) emptyList() else monthPath(n, zone)
            out.getOrPut(path) { mutableListOf() } += n
        }
        return out
    }

    // ─── Aufräumen ──────────────────────────────────────────────────

    /** A suggestion of "Aufräumen": the photo, its month (`2026-10`), the AI's "work" in percent, ticked at first. */
    data class Suggestion(val node: DNode, val month: String?, val percent: Int, val preselected: Boolean)

    /** The photos the AI thinks are work (from 50 %), by month (newest first), the surest first; ticked from 70 %. */
    fun suggestions(scored: List<Pair<DNode, Double>>, zone: ZoneId = ZoneId.systemDefault()): List<Suggestion> =
        scored.asSequence()
            .filter { (n, p) -> p >= CLEANUP_SHOW && !n.work && n.review == null }
            .map { (n, p) -> Suggestion(n, DriveLogic.monthOf(n, zone), percent(p), p >= CLEANUP_PRESELECT) }
            .sortedWith(compareByDescending<Suggestion> { it.month ?: "" }.thenByDescending { it.percent }.thenByDescending { it.node.id })
            .toList()

    /** The suggestions by month, in their order. */
    fun byMonth(list: List<Suggestion>): List<Pair<String?, List<Suggestion>>> {
        val out = ArrayList<Pair<String?, MutableList<Suggestion>>>()
        for (s in list) if (out.isNotEmpty() && out.last().first == s.month) out.last().second += s else out += s.month to mutableListOf(s)
        return out
    }
}
