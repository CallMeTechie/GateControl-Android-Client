package com.gatecontrol.android.chat

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * What the form „Termin anlegen“ starts with: the day and time found in a message and a title without them.
 *
 * The messenger plugin finds the dates (`GET messages/:id/suggest`, server side). Its numeric pattern needs a dot after
 * the month („30.11.“), so „30.11 um 08:00“ became only the time („heute 08:00“) and the title kept „30 11“. The app
 * reads the numeric day itself (also „30.11“, „30.11.“, „30.11.2026“, „30.11.26“), keeps the server's answer for all
 * the rest (morgen, Samstag, „5. Dezember“ …) and takes the date words („am“, „um“, „Uhr“) out of the title.
 */
data class ChatDatePrefill(val startMs: Long, val allDay: Boolean, val title: String)

object ChatDateHint {
    /** A day like 30.11 / 30.11. / 30.11.2026 / 30.11.26, not part of a longer number, not a time („10.15 Uhr“). */
    private val DAY = Regex(
        """(?i)(?:\b(?:am|den|ab|vom|bis)\s+)?(?<![\d.:])(\d{1,2})\.(\d{1,2})(?:\.(\d{4}|\d{2})(?!\d)|\.)?(?![\d:])(?!\s*(?:uhr|h)\b)""",
    )

    /** „um 8“, „um 8:30“, „8:00“, „8.30 Uhr“, „8 Uhr“ (with the filler word before and „Uhr“ after). */
    private val TIMES = listOf(
        Regex("""(?i)(?:\b(?:um|ab|gegen|at)\s+)?\b([01]?\d|2[0-3])[:.]([0-5]\d)(?:\s*(?:uhr|h)\b)?"""),
        Regex("""(?i)(?:\b(?:um|ab|gegen|at)\s+)?\b([01]?\d|2[0-3])\s*(?:uhr|h)\b"""),
        Regex("""(?i)\b(?:um|gegen|at)\s+([01]?\d|2[0-3])\b(?![.:]\d)"""),
    )

    private val FILLER_EDGE = Regex("""(?i)^(?:und|aber|also|dann|ok|okay|and|am|um|ab|gegen|den|uhr)\s+|\s+(?:am|um|ab|gegen|den|uhr)$""")

    /** The plugin's leftovers of a numeric date it did not read („30 11 kommt …“). */
    private val NUMERIC_LEFTOVER = Regex("""^\s*\d{1,2}\s+\d{1,2}(?:\s+\d{2,4})?\b""")

    private val EDGE_PUNCT = charArrayOf(',', ';', ':', '!', '?', '.', '-', '–', ' ')

    private val TIME_BEFORE = Regex("""(?i)\b(?:um|gegen)\s+$""")

    /** Familie keeps 80 characters of a title (the plugin's `to-calendar`), Mail 255. */
    const val TITLE_MAX = 80

    /**
     * The form's start for a message [text] sent at [sentAtMs]: the numeric day of the text (a day without a year
     * that already passed is next year's), the time of that line, and the line without them as the title. Without a
     * numeric day: the server's [server] date (its title cleaned). Null when neither has a date.
     */
    fun prefill(text: String?, sentAtMs: Long, zone: ZoneId, server: ChatSuggestedDate?): ChatDatePrefill? {
        val base = Instant.ofEpochMilli(sentAtMs).atZone(zone).toLocalDate()
        val lines = text.orEmpty().lines()
        for (line in lines) {
            var day: LocalDate? = null
            val m = DAY.findAll(line).firstOrNull { c ->
                // „um 10.15“: a time, not the 10th of a month
                !TIME_BEFORE.containsMatchIn(line.substring(0, c.range.first)) && dayOf(c, base)?.also { day = it } != null
            } ?: continue
            val rest = line.replaceRange(m.range, " ")
            val t = timeIn(rest)
            val d = day ?: continue
            val start = if (t != null) d.atTime(t.first).atZone(zone) else d.atStartOfDay(zone)
            val title = tidy(t?.let { rest.replaceRange(it.second, " ") } ?: rest)
            return ChatDatePrefill(start.toInstant().toEpochMilli(), t == null, title.ifEmpty { server?.title?.let(::cleanTitle).orEmpty() })
        }
        server ?: return null
        val at = instant(server.startsAt) ?: return null
        val local = at.atZone(zone)
        // an all-day date of the plugin starts at midnight; its label has no time
        val allDay = local.toLocalTime() == LocalTime.MIDNIGHT && server.label?.contains(':') != true
        val title = server.title?.let(::cleanTitle)?.takeIf { it.isNotEmpty() } ?: firstLine(text)
        return ChatDatePrefill(at.toEpochMilli(), allDay, title)
    }

    /** The menu's label „So., 30.11. 08:00“ of [p]. */
    fun label(p: ChatDatePrefill, zone: ZoneId, locale: Locale): String {
        val z = Instant.ofEpochMilli(p.startMs).atZone(zone)
        val day = java.time.format.DateTimeFormatter.ofPattern("EEE, dd.MM.", locale).format(z)
        return if (p.allDay) day else day + " " + java.time.format.DateTimeFormatter.ofPattern("HH:mm", locale).format(z)
    }

    /** A server title without what is left of a date. */
    fun cleanTitle(t: String): String = tidy(t.replace(NUMERIC_LEFTOVER, " "))

    private fun dayOf(m: MatchResult, base: LocalDate): LocalDate? {
        val d = m.groupValues[1].toInt()
        val mo = m.groupValues[2].toInt()
        if (d !in 1..31 || mo !in 1..12) return null
        val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) it + 2000 else it }
        val date = runCatching { LocalDate.of(y ?: base.year, mo, d) }.getOrNull() ?: return null
        if (y == null && date.isBefore(base)) return runCatching { LocalDate.of(base.year + 1, mo, d) }.getOrNull()
        return date
    }

    private fun timeIn(s: String): Pair<LocalTime, IntRange>? {
        for (re in TIMES) {
            val m = re.find(s) ?: continue
            val h = m.groupValues[1].toInt()
            val min = m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toInt() ?: 0
            return LocalTime.of(h, min) to m.range
        }
        return null
    }

    private fun instant(iso: String): Instant? =
        runCatching { Instant.parse(iso) }.getOrNull() ?: runCatching { java.time.OffsetDateTime.parse(iso).toInstant() }.getOrNull()

    private fun tidy(s: String): String {
        var t = s.replace(Regex("""\s+[,;:]+(?=\s)"""), " ").replace(Regex("""\s+"""), " ").trim().trim(*EDGE_PUNCT).trim()
        var before: String
        do {
            before = t
            t = t.replace(FILLER_EDGE, "").trim().trim(*EDGE_PUNCT).trim()
        } while (t != before)
        if (t.length > TITLE_MAX) t = t.take(TITLE_MAX - 1).replace(Regex("""\s+\S*$"""), "") + "…"
        return t
    }

    private fun firstLine(text: String?): String = text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(60).orEmpty()
}
