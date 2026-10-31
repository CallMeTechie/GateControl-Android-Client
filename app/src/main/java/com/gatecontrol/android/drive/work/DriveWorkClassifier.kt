package com.gatecontrol.android.drive.work

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.exp

/**
 * What the AI learns from (pure, unit-tested): a photo's features — the labels the phone's image labeling found
 * (their index → confidence) and when it was taken (a working day's working hours, the weekend) — and whether the
 * person said work or private, from where ([Source]).
 */
data class WorkExample(
    /** One per photo (a later answer replaces an earlier one): `n:<nodeId>` or `m:<sha256>`. */
    val key: String,
    val work: Boolean,
    val source: WorkExample.Source,
    val features: Map<Int, Float>,
    val at: Long,
) {
    enum class Source(val code: String) {
        /** Taken in the WLAN "Firma" or "Zuhause". */
        WIFI("wifi"),
        /** "Arbeit" / "Privat" of the bar, "Mehr › Zuordnung". */
        ASSIGN("assign"),
        /** Moved into or out of the work folder. */
        MOVE("move"),
        /** "Aufräumen": ticked = work, unticked = private. */
        CLEANUP("cleanup"),
        /** Taken at the place "Firma" or "Zuhause" ([DriveWorkPlaces]). */
        PLACE("place"),
        /** In the work folder in Drive (assigned in the portal or on another device). */
        FOLDER("folder"),
        ;

        companion object {
            fun of(code: String?): Source? = entries.firstOrNull { it.code == code }
        }
    }
}

/**
 * A small model on the phone: logistic regression over the label features, trained on the examples ([train]), its
 * answer the probability "work" ([predict]). Nothing of it leaves the phone.
 */
class DriveWorkModel internal constructor(private val bias: Double, private val weights: Map<Int, Double>) {

    fun predict(features: Map<Int, Float>): Double {
        var z = bias
        for ((i, v) in features) z += (weights[i] ?: 0.0) * v
        return sigmoid(z)
    }

    companion object {
        private const val EPOCHS = 300
        private const val RATE = 0.5
        private const val L2 = 0.02

        /** Trained on [examples] (both kinds weighed equally); null without examples of both kinds. */
        fun train(examples: List<WorkExample>): DriveWorkModel? {
            val pos = examples.count { it.work }
            val neg = examples.size - pos
            if (pos == 0 || neg == 0) return null
            // equal weight for both kinds, whatever their numbers
            val wPos = examples.size / (2.0 * pos)
            val wNeg = examples.size / (2.0 * neg)
            val keys = examples.flatMap { it.features.keys }.toSortedSet().toList()
            val index = keys.withIndex().associate { it.value to it.index }
            val w = DoubleArray(keys.size)
            var b = 0.0
            val n = examples.size.toDouble()
            val rows = examples.map { e -> e.features.map { (k, v) -> index.getValue(k) to v.toDouble() } }
            repeat(EPOCHS) {
                val gw = DoubleArray(w.size)
                var gb = 0.0
                for ((r, e) in examples.withIndex()) {
                    var z = b
                    for ((i, v) in rows[r]) z += w[i] * v
                    val y = if (e.work) 1.0 else 0.0
                    val err = (sigmoid(z) - y) * (if (e.work) wPos else wNeg)
                    for ((i, v) in rows[r]) gw[i] += err * v
                    gb += err
                }
                for (i in w.indices) w[i] -= RATE * (gw[i] / n + L2 * w[i])
                b -= RATE * gb / n
            }
            return DriveWorkModel(b, keys.withIndex().associate { it.value to w[it.index] }.filterValues { it != 0.0 })
        }

        private fun sigmoid(z: Double): Double = 1.0 / (1.0 + exp(-z.coerceIn(-30.0, 30.0)))
    }
}

/** Features and examples on the phone. */
object DriveWorkFeatures {

    /** Index of "taken on a working day between 7 and 19 o'clock" (the labels' indices are far below). */
    const val WORK_HOURS = 100_000

    /** Index of "taken on a Saturday or Sunday". */
    const val WEEKEND = 100_001

    /** Labels below this confidence are left out (noise). */
    const val MIN_CONFIDENCE = 0.5f

    /** At most this many examples are kept (the oldest go). */
    const val MAX_EXAMPLES = 2000

    /** The features of a photo: its labels ([labels], index → confidence) and when it was taken ([takenMs]). */
    fun of(labels: Map<Int, Float>, takenMs: Long?, zone: ZoneId = ZoneId.systemDefault()): Map<Int, Float> {
        val out = HashMap<Int, Float>()
        labels.forEach { (i, c) -> if (i in 0 until WORK_HOURS && c >= MIN_CONFIDENCE) out[i] = c.coerceIn(0f, 1f) }
        if (takenMs != null && takenMs > 0) {
            val t = Instant.ofEpochMilli(takenMs).atZone(zone)
            val weekend = t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY
            if (weekend) out[WEEKEND] = 1f else if (t.hour in 7 until 19) out[WORK_HOURS] = 1f
        }
        return out
    }

    /** [e] added: an earlier example of the same photo is replaced; the oldest go beyond [MAX_EXAMPLES]. */
    fun add(list: List<WorkExample>, e: WorkExample): List<WorkExample> {
        val out = list.filter { it.key != e.key } + e
        return if (out.size > MAX_EXAMPLES) out.sortedBy { it.at }.takeLast(MAX_EXAMPLES) else out
    }

    /** What was learned so far, for the settings ("Gelernt aus …"). */
    fun learned(list: List<WorkExample>): DriveWorkLogic.Learned = DriveWorkLogic.Learned(
        total = list.size,
        work = list.count { it.work },
        private = list.count { !it.work },
        workFromWifi = list.count { it.work && it.source == WorkExample.Source.WIFI },
        privateFromWifi = list.count { !it.work && it.source == WorkExample.Source.WIFI },
    )

    /** `{ v:1, e:[{ k, w, s, f:{ "12": 0.83, … }, at }, …] }`. */
    fun encode(list: List<WorkExample>): String = JsonObject().apply {
        addProperty("v", 1)
        add("e", JsonArray().apply {
            list.forEach { e ->
                add(JsonObject().apply {
                    addProperty("k", e.key)
                    addProperty("w", e.work)
                    addProperty("s", e.source.code)
                    add("f", JsonObject().apply { e.features.forEach { (i, v) -> addProperty(i.toString(), v) } })
                    addProperty("at", e.at)
                })
            }
        })
    }.toString()

    /** Anything broken reads as no examples (the AI starts again). */
    fun decode(text: String?): List<WorkExample> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            JsonParser.parseString(text).asJsonObject.getAsJsonArray("e").mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val key = o.get("k")?.takeIf { it.isJsonPrimitive }?.asString?.take(80) ?: return@mapNotNull null
                val work = o.get("w")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: return@mapNotNull null
                val source = WorkExample.Source.of(o.get("s")?.takeIf { it.isJsonPrimitive }?.asString) ?: return@mapNotNull null
                val f = o.getAsJsonObject("f")?.entrySet()?.mapNotNull { (k, v) ->
                    val i = k.toIntOrNull() ?: return@mapNotNull null
                    val x = runCatching { v.asFloat }.getOrNull()?.takeIf { it.isFinite() } ?: return@mapNotNull null
                    i to x
                }?.toMap().orEmpty()
                WorkExample(key, work, source, f, o.get("at")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L)
            }
        }.getOrDefault(emptyList())
    }
}
