package com.gatecontrol.android.drive.work

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * One look at the WLAN: from [at] (ms) on the phone was in [ssid] (null: no WLAN, or Android did not tell its name).
 * [session] is the app process that saw it: within one process the network callback runs all the time, so two looks
 * of the same session have nothing unseen between them; between sessions (the app was not running) only the looks
 * themselves are known.
 */
data class WifiSeen(val at: Long, val ssid: String?, val session: String)

/**
 * The WLAN timeline (pure, unit-tested): when the phone was in which WLAN, so a photo's time ([wifiAt]) tells where it
 * was taken. Kept on the phone only ([encode]), for [KEEP_MS].
 */
object DriveWifiTimeline {

    /** Kept this long (photos older than that are backed up long ago or not at all). */
    const val KEEP_MS = 30L * 24 * 60 * 60 * 1000

    /** At most this many looks. */
    const val MAX = 4000

    /** A look this close to a photo counts for it, whatever came before or after. */
    const val GRACE_MS = 2L * 60 * 1000

    /**
     * [seen] added to [list] (sorted by time). A look of the same session and WLAN as the last one adds nothing (the
     * callback saw no change); one from another session does (it confirms the WLAN after a gap). Old looks go, the
     * last one before the cut stays (it says what was then).
     */
    fun add(list: List<WifiSeen>, seen: WifiSeen, now: Long = seen.at): List<WifiSeen> {
        val last = list.lastOrNull()
        val out = when {
            last != null && last.ssid == seen.ssid && last.session == seen.session && seen.at >= last.at -> list
            last == null || seen.at >= last.at -> list + seen
            else -> (list + seen).sortedBy { it.at }
        }
        return prune(out, now)
    }

    fun prune(list: List<WifiSeen>, now: Long): List<WifiSeen> {
        val cut = now - KEEP_MS
        val firstKept = list.indexOfFirst { it.at >= cut }
        val trimmed = when {
            firstKept < 0 -> list.takeLast(1)
            firstKept > 0 -> list.subList(firstKept - 1, list.size)
            else -> list
        }
        return if (trimmed.size > MAX) trimmed.takeLast(MAX) else trimmed
    }

    /**
     * The WLAN the phone was in at [t]; null when it was in none or it cannot tell. [current] is this process'
     * session: its last look holds until now (the callback would have seen a change). Between two looks the earlier
     * holds when both are of one session (seen without a gap) or name the same WLAN; else only a look within
     * [GRACE_MS] of [t] counts.
     */
    fun wifiAt(list: List<WifiSeen>, t: Long, current: String?): String? {
        var prev: WifiSeen? = null
        var next: WifiSeen? = null
        for (s in list) {
            if (s.at <= t) prev = s else { next = s; break }
        }
        if (prev == null) return next?.takeIf { it.at - t <= GRACE_MS }?.ssid
        if (next == null) {
            return if (prev.session == current || t - prev.at <= GRACE_MS) prev.ssid else null
        }
        if (prev.ssid == next.ssid || prev.session == next.session) return prev.ssid
        if (t - prev.at <= GRACE_MS) return prev.ssid
        if (next.at - t <= GRACE_MS) return next.ssid
        return null
    }

    // ─── on the phone ───────────────────────────────────────────────

    /** `{ v:1, s:[[at, ssid|null, session], …] }`. */
    fun encode(list: List<WifiSeen>): String = JsonObject().apply {
        addProperty("v", 1)
        add("s", JsonArray().apply {
            list.forEach { w ->
                add(JsonArray().apply {
                    add(w.at)
                    if (w.ssid != null) add(w.ssid) else add(JsonNull.INSTANCE)
                    add(w.session)
                })
            }
        })
    }.toString()

    /** Anything broken reads as an empty timeline. */
    fun decode(text: String?): List<WifiSeen> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            val o = JsonParser.parseString(text).asJsonObject
            o.getAsJsonArray("s").mapNotNull { e ->
                val a = e as? JsonArray ?: return@mapNotNull null
                if (a.size() < 3) return@mapNotNull null
                val at = runCatching { a[0].asLong }.getOrNull() ?: return@mapNotNull null
                val ssid = a[1].takeIf { it.isJsonPrimitive }?.asString?.take(64)
                val session = a[2].takeIf { it.isJsonPrimitive }?.asString?.take(40) ?: return@mapNotNull null
                WifiSeen(at, ssid, session)
            }.sortedBy { it.at }
        }.getOrDefault(emptyList())
    }
}
