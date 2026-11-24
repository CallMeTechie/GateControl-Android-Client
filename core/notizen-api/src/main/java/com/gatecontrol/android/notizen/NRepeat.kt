package com.gatecontrol.android.notizen

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.time.LocalDate

/**
 * Repeat rules of tasks — a 1:1 port of the plugin's `ui/repeat.js` (gatecontrol-notizen 1.0.0). Pure, no clock:
 * every function gets the dates it works on. Dates are `YYYY-MM-DD` (the wall-clock date in the hub's time zone),
 * weekdays ISO (1 = Monday … 7 = Sunday). The plugin's `test/vectors/repeat.json` (copied into the unit tests) pins it.
 *
 *   daily   { interval }                every n days
 *   weekly  { interval, days }          every n weeks on these weekdays (weeks Monday-based from the rule's start)
 *   monthly { interval, day? }          every n months on that day (clamped to the month's end)
 *   yearly  { interval, month?, day? }  every n years
 *   after   { interval }                n days after the task was completed
 */
object NRepeat {
    val TYPES = listOf("daily", "weekly", "monthly", "yearly", "after")
    private val DATE_RE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")

    /** A rule (only canonical ones come out of [normalize]). */
    data class Rule(
        val type: String,
        val interval: Int = 1,
        val days: List<Int>? = null,
        val day: Int? = null,
        val month: Int? = null,
    )

    fun toDays(date: String?): Long? {
        val m = DATE_RE.matchEntire(date ?: return null) ?: return null
        return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()).toEpochDay() }.getOrNull()
    }

    fun fromDays(n: Long): String = LocalDate.ofEpochDay(n).toString()

    fun addDays(date: String?, n: Long): String? = toDays(date)?.let { fromDays(it + n) }

    /** ISO weekday of a date: 1 = Monday … 7 = Sunday. */
    fun weekday(date: String?): Int? = toDays(date)?.let { wdOf(it) }

    private fun wdOf(d: Long): Int = (((d + 3) % 7 + 7) % 7 + 1).toInt()

    private fun daysIn(y: Int, m: Int): Int = LocalDate.of(y, m, 1).lengthOfMonth()

    private fun make(y: Int, m: Int, d: Int): String = LocalDate.of(y, m, minOf(d, daysIn(y, m))).toString()

    private fun ymd(date: String): Triple<Int, Int, Int> {
        val m = DATE_RE.matchEntire(date)!!
        return Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }

    /** A rule as given → the canonical rule, or null when it is not one. */
    fun normalize(r: Rule?): Rule? {
        if (r == null || r.type !in TYPES) return null
        if (r.interval < 1 || r.interval > 999) return null
        var days: List<Int>? = null
        if (r.type == "weekly") {
            val d = r.days ?: return null
            if (d.isEmpty() || d.any { it < 1 || it > 7 }) return null
            days = d.distinct().sorted()
        }
        var day: Int? = null
        if ((r.type == "monthly" || r.type == "yearly") && r.day != null) {
            if (r.day < 1 || r.day > 31) return null
            day = r.day
        }
        var month: Int? = null
        if (r.type == "yearly" && r.month != null) {
            if (r.month < 1 || r.month > 12) return null
            month = r.month
        }
        return Rule(r.type, r.interval, days, day, month)
    }

    /** A rule of the server (or a vector) → the canonical rule; anything the JS would refuse gives null. */
    fun fromJson(e: JsonElement?): Rule? {
        val o = e?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val type = o.get("type")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
        fun int(key: String): Pair<Boolean, Int?> {
            val v = o.get(key)
            if (v == null || v.isJsonNull) return true to null
            if (!v.isJsonPrimitive || !v.asJsonPrimitive.isNumber) return false to null
            val d = v.asDouble
            if (d % 1.0 != 0.0 || d < Int.MIN_VALUE || d > Int.MAX_VALUE) return false to null
            return true to d.toInt()
        }
        val (iOk, interval) = int("interval")
        if (!iOk) return null
        var days: List<Int>? = null
        if (type == "weekly") {
            val a = o.get("days")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
            days = a.map { x ->
                if (!x.isJsonPrimitive || !x.asJsonPrimitive.isNumber) return null
                val d = x.asDouble
                if (d % 1.0 != 0.0) return null
                d.toInt()
            }
        }
        val (dOk, day) = int("day")
        val (mOk, month) = int("month")
        if ((type == "monthly" || type == "yearly") && !dOk) return null
        if (type == "yearly" && !mOk) return null
        return normalize(Rule(type, interval ?: 1, days, day, month))
    }

    /** The rule as the API takes it (only its fields). */
    fun toJson(r: Rule): JsonObject = JsonObject().apply {
        addProperty("type", r.type)
        addProperty("interval", r.interval)
        r.days?.let { d -> add("days", JsonArray().apply { d.forEach { add(it) } }) }
        r.day?.let { addProperty("day", it) }
        r.month?.let { addProperty("month", it) }
    }

    /** Monday-based week number of a day (for "every n weeks"). */
    private fun weekIndex(n: Long): Long = Math.floorDiv(n + 3, 7L)

    private fun step(rule: Rule, date: String, anchor: String?): String {
        val n = toDays(date)!!
        if (rule.type == "daily" || rule.type == "after") return fromDays(n + rule.interval)
        if (rule.type == "weekly") {
            val a = weekIndex(toDays(anchor ?: date)!!)
            val days = rule.days.orEmpty()
            for (i in 1..(7 * rule.interval + 7)) {
                val c = n + i
                if (wdOf(c) in days && Math.floorMod(weekIndex(c) - a, rule.interval.toLong()) == 0L) return fromDays(c)
            }
            return fromDays(n + 7L * rule.interval)
        }
        val (py, pm, _) = ymd(date)
        val (_, bm, bd) = ymd(anchor ?: date)
        if (rule.type == "monthly") {
            val mm = pm - 1 + rule.interval
            return make(py + Math.floorDiv(mm, 12), mm % 12 + 1, rule.day ?: bd)
        }
        return make(py + rule.interval, rule.month ?: bm, rule.day ?: bd)
    }

    /** The next due date after a completion on [done] (and the task's [due]); null for no rule / bad dates. */
    fun next(rule: Rule?, due: String?, done: String?): String? {
        val r = normalize(rule) ?: return null
        val doneDays = toDays(done) ?: return null
        if (r.type == "after") return addDays(done, r.interval.toLong())
        val from = if (due != null && toDays(due) != null) due else done!!
        var d = step(r, from, from)
        var guard = 0
        while (toDays(d)!! <= doneDays && guard++ < 5000) d = step(r, d, from)
        return d
    }

    /** The first due date of a new rule from [today] (today counts): weekly → the first of its days. */
    fun first(rule: Rule?, today: String?): String? {
        val r = normalize(rule) ?: return null
        if (toDays(today) == null) return null
        if (r.type == "weekly") {
            for (i in 0 until 7) {
                val d = addDays(today, i.toLong())!!
                if (weekday(d) in r.days.orEmpty()) return d
            }
        }
        if (r.type == "monthly" && r.day != null) {
            val (y, m, _) = ymd(today!!)
            val mm = make(y, m, r.day)
            return if (toDays(mm)!! >= toDays(today)!!) mm else make(if (m == 12) y + 1 else y, if (m == 12) 1 else m + 1, r.day)
        }
        return today
    }

    private val WD_DE = listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")
    private val WD_EN = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    private fun list(words: List<String>, en: Boolean): String {
        if (words.size < 2) return words.joinToString("")
        return words.dropLast(1).joinToString(", ") + (if (en) " and " else " und ") + words.last()
    }

    /** A short text of the rule: „Jeden Montag“, „Alle 2 Wochen am Montag und Donnerstag“, „3 Tage nach Erledigung“. */
    fun describe(rule: Rule?, lang: String): String {
        val r = normalize(rule)
        val en = lang == "en"
        if (r == null) return if (en) "Never" else "Nie"
        val n = r.interval
        return when (r.type) {
            "after" -> if (en) "$n ${if (n == 1) "day" else "days"} after completion" else "$n ${if (n == 1) "Tag" else "Tage"} nach Erledigung"
            "daily" -> if (n == 1) (if (en) "Daily" else "Täglich") else (if (en) "Every $n days" else "Alle $n Tage")
            "weekly" -> {
                val days = r.days.orEmpty()
                val names = days.map { (if (en) WD_EN else WD_DE)[it - 1] }
                val work = days == listOf(1, 2, 3, 4, 5)
                if (n == 1) {
                    if (work) (if (en) "Every weekday" else "Werktags") else if (en) "Every " + list(names, true) else "Jeden " + list(names, false)
                } else if (en) {
                    "Every $n weeks on " + list(names, true)
                } else {
                    "Alle $n Wochen am " + list(names, false)
                }
            }
            "monthly" -> if (n == 1) (if (en) "Monthly" else "Monatlich") else (if (en) "Every $n months" else "Alle $n Monate")
            else -> if (n == 1) (if (en) "Yearly" else "Jährlich") else (if (en) "Every $n years" else "Alle $n Jahre")
        }
    }
}
