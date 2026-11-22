package com.gatecontrol.android.quickactions

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlin.math.roundToInt

/**
 * A widget's bar of segments (GateControl 1.318+), e.g. a heating plan over
 * the day: [from]..[to] (default 0..24, hours), the current position [now]
 * (null: none) and up to 12 [segments] in order, not overlapping (gaps are
 * allowed). Display only. Stored with the pins like the rest of the widget.
 */
data class QuickActionTimelineItem(
    val from: Double = 0.0,
    val to: Double = 24.0,
    val now: Double? = null,
    val segments: List<QuickActionTimelineSegment> = emptyList(),
) {
    /** Whether the range reads as hours of a day (then positions are spoken as „6:52“). */
    val isDay: Boolean get() = from >= 0.0 && to <= 24.0

    /** Where [x] lies in the range, 0..1. */
    fun fraction(x: Double): Float = ((x - from) / (to - from)).coerceIn(0.0, 1.0).toFloat()

    /** The axis below the bar: five evenly spaced marks, for 0..24 „0 6 12 18 24“. */
    fun ticks(): List<String> = (0..4).map { QuickActionWidgetItem.formatNumber(from + (to - from) * it / 4.0) }

    /** A position as text: „6:52“ / „24:00“ for a day, else the number („3,5“). */
    fun positionText(x: Double): String {
        if (!isDay) return QuickActionWidgetItem.formatNumber(x)
        val minutes = (x * 60).roundToInt()
        return "%d:%02d".format(minutes / 60, minutes % 60)
    }

    /** For screen readers, one entry per segment: „0:00–6:00 Nacht“ (the label left out when there is none). */
    fun spokenSegments(): List<String> = segments.map { s ->
        listOfNotNull("${positionText(s.from)}–${positionText(s.to)}", s.label).joinToString(" ")
    }

    /** Gson bypasses Kotlin defaults: an older stored shape may leave the list null. */
    @Suppress("SENSELESS_COMPARISON", "UNNECESSARY_SAFE_CALL")
    fun normalized(): QuickActionTimelineItem? {
        val list = (segments ?: emptyList()).filter { it != null }
        return if (list.isEmpty() || !(from < to)) null else copy(segments = list)
    }

    companion object {
        const val MAX_SEGMENTS = 12
        val TONES = setOf("good", "warn", "crit", "neutral")
        private val COLOR = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")

        /**
         * The server's timeline, checked as strictly as the host does: any
         * present field that is invalid, more than 12 segments, segments out
         * of order or overlapping, a [now] outside the range → null (the
         * widget stays).
         */
        fun from(raw: JsonElement?): QuickActionTimelineItem? {
            if (raw == null || !raw.isJsonObject) return null
            val o = raw.asJsonObject
            val from = number(o, "from", 0.0) ?: return null
            val to = number(o, "to", 24.0) ?: return null
            if (!(from < to)) return null
            val now = if (absent(o, "now")) null else (number(o, "now", null) ?: return null).takeIf { it in from..to } ?: return null
            val rawSegments = o.get("segments")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
            if (rawSegments.size() == 0 || rawSegments.size() > MAX_SEGMENTS) return null
            val segments = ArrayList<QuickActionTimelineSegment>(rawSegments.size())
            var end = from
            for (el in rawSegments) {
                if (!el.isJsonObject) return null
                val s = el.asJsonObject
                val sFrom = number(s, "from", null) ?: return null
                val sTo = number(s, "to", null) ?: return null
                if (!(sFrom < sTo) || sFrom < end || sTo > to) return null
                end = sTo
                val tone = if (absent(s, "tone")) null else (string(s, "tone")?.takeIf { it in TONES } ?: return null)
                val color = if (absent(s, "color")) null else (string(s, "color")?.takeIf { COLOR.matches(it) }?.lowercase() ?: return null)
                val label = if (absent(s, "label")) {
                    null
                } else {
                    string(s, "label")?.trim()?.takeIf { it.length in 1..20 && it.none(Char::isISOControl) } ?: return null
                }
                segments += QuickActionTimelineSegment(sFrom, sTo, tone, color, label)
            }
            return QuickActionTimelineItem(from, to, now, segments)
        }

        private fun absent(o: JsonObject, key: String) = !o.has(key) || o.get(key).isJsonNull

        /** A finite number at [key], [default] when absent, null when present but not a finite number. */
        private fun number(o: JsonObject, key: String, default: Double?): Double? {
            if (absent(o, key)) return default
            val p = o.get(key).takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.takeIf { it.isNumber } ?: return null
            return p.asDouble.takeIf { it.isFinite() }
        }

        private fun string(o: JsonObject, key: String): String? =
            o.get(key).takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.takeIf { it.isString }?.asString
    }
}

/** One segment of a timeline: [color] (`#rgb`/`#rrggbb`, lower case) wins over [tone]; [label] names it. */
data class QuickActionTimelineSegment(
    val from: Double,
    val to: Double,
    val tone: String? = null,
    val color: String? = null,
    val label: String? = null,
) {
    /** [color] as an opaque ARGB int, null when there is none. */
    val argb: Int? get() {
        val hex = color?.removePrefix("#") ?: return null
        val full = when (hex.length) {
            3 -> hex.map { "$it$it" }.joinToString("")
            6 -> hex
            else -> return null
        }
        return full.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
    }
}
