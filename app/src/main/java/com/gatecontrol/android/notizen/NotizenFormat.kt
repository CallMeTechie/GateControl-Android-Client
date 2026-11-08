package com.gatecontrol.android.notizen

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The dates and times of the screens, as the mockups write them (pure, unit-tested). */
object NotizenFormat {

    private fun locale(lang: String) = if (lang == "en") Locale.ENGLISH else Locale.GERMAN

    private fun date(s: String?): LocalDate? = s?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** `09:00` → `9:00`. */
    fun time(hhmm: String?): String? = hhmm?.takeIf { Regex("^\\d{2}:\\d{2}$").matches(it) }?.removePrefix("0")

    /** „Sonntag, 8. November“ / "Sunday, November 8" — the subtitle of „Mein Tag“. */
    fun dayTitle(today: String, lang: String): String {
        val d = date(today) ?: return today
        return d.format(DateTimeFormatter.ofPattern(if (lang == "en") "EEEE, MMMM d" else "EEEE, d. MMMM", locale(lang)))
    }

    /** „Mo. 9. Nov.“ / "Mon, Nov 9". */
    fun shortDate(day: String, lang: String): String {
        val d = date(day) ?: return day
        return if (lang == "en") d.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH))
        else d.format(DateTimeFormatter.ofPattern("EE d. MMM", Locale.GERMAN))
    }

    /**
     * A due day as the rows write it: „heute“, „morgen“, „gestern“, else „Fr. 13. Nov.“; with a time „heute 9:00“.
     * [words] gives the words of the language (today, tomorrow, yesterday).
     */
    fun due(due: NDue?, today: String, lang: String, words: Triple<String, String, String>): String? {
        if (due == null) return null
        val d = date(due.date) ?: return null
        val t = date(today)
        val day = when (t?.let { d.toEpochDay() - it.toEpochDay() }) {
            0L -> words.first
            1L -> words.second
            -1L -> words.third
            else -> shortDate(due.date, lang)
        }
        return listOfNotNull(day, time(due.time)).joinToString(" ")
    }

    /** The day of a reminder (`remindAt` ISO with offset, in the hub's wall clock) → its date and time. */
    fun remindParts(iso: String?): NDue? {
        val m = Regex("^(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2})").find(iso ?: return null) ?: return null
        return NDue(m.groupValues[1], m.groupValues[2])
    }

    /** „1,4 GB“ / "1.4 GB". */
    fun bytes(n: Long, lang: String): String {
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var v = n.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        val s = if (i == 0 || v >= 10) Math.round(v).toString() else String.format(Locale.ROOT, "%.1f", v)
        return (if (lang == "en") s else s.replace('.', ',')) + " " + units[i]
    }
}
