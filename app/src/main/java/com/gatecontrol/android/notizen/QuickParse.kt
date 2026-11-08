package com.gatecontrol.android.notizen

import java.text.Normalizer
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Schnell-Eingabe: a line in plain language → a task (title, date, time, repeat, list, person, important) — a 1:1 port
 * of the plugin's `ui/quickparse.js` (gatecontrol-notizen 1.0.0). Deterministic, no AI, no clock of its own: the
 * plugin's `test/vectors/quickparse.json` (copied into the unit tests) is what this port must give, case by case.
 *
 * The JavaScript regular expressions are kept as they are, with three spellings made explicit so the JVM (unit tests)
 * and Android's ICU engine read them the same as V8: `\s` is JavaScript's whitespace set, `\d` is `[0-9]` and `\b`
 * (only in the weekday list) is an ASCII word boundary. Offsets of [Marks][Mark] are UTF-16 code units, as in JS.
 */
object QuickParse {

    data class Ref(val id: Long, val name: String)

    data class Mark(val start: Int, val end: Int, val kind: String)

    data class Remind(val date: String, val time: String)

    data class Ctx(
        /** The wall-clock date in the hub's time zone (`me.today`). */
        val today: String,
        /** `HH:MM` now (`me.time`): a time alone that is over means tomorrow. */
        val now: String? = null,
        val lists: List<Ref> = emptyList(),
        val people: List<Ref> = emptyList(),
        /** The person's words „früh“, „abends“ … (`prefs.times`). */
        val times: Map<String, String> = emptyMap(),
    )

    data class Parsed(
        val title: String,
        val date: String?,
        val time: String?,
        val remind: Remind?,
        val important: Boolean,
        val list: Ref?,
        val listName: String?,
        val assignee: Ref?,
        val assigneeName: String?,
        val repeat: NRepeat.Rule?,
        val marks: List<Mark>,
    )

    val DEFAULT_TIMES: Map<String, String> = linkedMapOf("morning" to "09:00", "noon" to "12:00", "afternoon" to "15:00", "evening" to "19:00", "night" to "21:00")

    // JavaScript's \s (WhiteSpace and LineTerminator of ECMAScript)
    private const val WS = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"

    /** A JS pattern → the same for java.util.regex and ICU: `\s` and `\d` spelled out. */
    private fun js(re: String): String {
        val out = StringBuilder()
        var i = 0
        var inClass = false
        while (i < re.length) {
            val c = re[i]
            if (c == '\\' && i + 1 < re.length) {
                val n = re[i + 1]
                when (n) {
                    's' -> out.append(if (inClass) WS else "[$WS]")
                    'd' -> out.append(if (inClass) "0-9" else "[0-9]")
                    else -> out.append(c).append(n)
                }
                i += 2
                continue
            }
            if (c == '[' && !inClass) inClass = true else if (c == ']' && inClass) inClass = false
            out.append(c)
            i++
        }
        return out.toString()
    }

    private const val L = "[\\p{L}\\p{N}]"
    private const val B = "(?<!$L)" // start of a word
    private const val E = "(?!$L)" // end of a word

    private val WEEKDAYS = listOf(
        listOf("montag", "monday", "mo", "mon"),
        listOf("dienstag", "tuesday", "di", "tue", "tues"),
        listOf("mittwoch", "wednesday", "mi", "wed"),
        listOf("donnerstag", "thursday", "do", "thu", "thur", "thurs"),
        listOf("freitag", "friday", "fr", "fri"),
        listOf("samstag", "sonnabend", "saturday", "sa", "sat"),
        listOf("sonntag", "sunday", "so", "sun"),
    )
    private val MONTHS = listOf(
        listOf("januar", "jänner", "january", "jan"), listOf("februar", "february", "feb"), listOf("märz", "maerz", "march", "mär", "mar"),
        listOf("april", "apr"), listOf("mai", "may"), listOf("juni", "june", "jun"), listOf("juli", "july", "jul"), listOf("august", "aug"),
        listOf("september", "sept", "sep"), listOf("oktober", "october", "okt", "oct"), listOf("november", "nov"), listOf("dezember", "december", "dez", "dec"),
    )
    private val NUMBERS: Map<String, Int> = linkedMapOf(
        "ein" to 1, "eins" to 1, "eine" to 1, "einem" to 1, "einen" to 1, "einer" to 1, "a" to 1, "an" to 1, "one" to 1, "zwei" to 2, "two" to 2,
        "drei" to 3, "three" to 3, "vier" to 4, "four" to 4, "fünf" to 5, "fuenf" to 5, "five" to 5, "sechs" to 6, "six" to 6, "sieben" to 7,
        "seven" to 7, "acht" to 8, "eight" to 8, "neun" to 9, "nine" to 9, "zehn" to 10, "ten" to 10,
    )

    private fun byLengthDesc(words: List<String>) = words.sortedByDescending { it.length }

    private fun alt(groups: List<List<String>>, short: Boolean = false): String =
        byLengthDesc(groups.flatten().filter { !short || it.length <= 4 }).joinToString("|")

    private fun full(groups: List<List<String>>): String = byLengthDesc(groups.flatten().filter { it.length > 4 }).joinToString("|")

    private val WD_FULL = full(WEEKDAYS)
    private val WD_SHORT = alt(WEEKDAYS, true)
    private val MON = alt(MONTHS)
    private val NUM = "\\d{1,3}|" + byLengthDesc(NUMBERS.keys.toList()).joinToString("|")
    private val TIME_AHEAD = "(?=\\s*(?:um\\s+|at\\s+)?\\d{1,2}(?:[:.]\\d{2})?\\s*(?:uhr|h|am|pm)?$E)"
    private val WD_WORD = "(?:$WD_FULL|(?:$WD_SHORT)(?:\\.|$TIME_AHEAD))"
    private const val PRE = "(?:(?:am|bis|ab|on|by|due|until|für|for)\\s+)?"

    private fun lower(s: String) = s.lowercase(Locale.ROOT)

    private fun weekdayOf(word: String): Int? {
        val w = lower(word).removeSuffix(".")
        WEEKDAYS.forEachIndexed { i, g -> if (w in g) return i + 1 }
        return null
    }

    private fun monthOf(word: String): Int? {
        val w = lower(word).removeSuffix(".")
        MONTHS.forEachIndexed { i, g -> if (w in g) return i + 1 }
        return null
    }

    private fun numberOf(word: String): Int? {
        val w = lower(word)
        return if (Regex("^[0-9]+$").matches(w)) w.toIntOrNull() else NUMBERS[w]
    }

    private fun pad(n: Int) = (if (n < 10) "0" else "") + n
    private fun hhmm(h: Int, m: Int) = pad(h) + ":" + pad(m)

    private fun validDate(y: Int, m: Int, d: Int): Boolean {
        if (m < 1 || m > 12 || d < 1) return false
        return d <= java.time.YearMonth.of(y, m).lengthOfMonth()
    }

    /** The next date with ISO weekday [wd] strictly after [today]. */
    private fun nextWeekday(today: String, wd: Int): String {
        val cur = NRepeat.weekday(today)!!
        val diff = Math.floorMod(wd - cur + 7, 7)
        return NRepeat.addDays(today, if (diff == 0) 7L else diff.toLong())!!
    }

    /** A day and month without a year → the next such date from today on. */
    private fun upcoming(today: String, m: Int, d: Int): String? {
        val ty = today.substring(0, 4).toInt()
        for (y in ty..ty + 8) {
            if (!validDate(y, m, d)) continue
            val s = "$y-${pad(m)}-${pad(d)}"
            if (s >= today) return s
        }
        return null
    }

    private fun addMonths(date: String, n: Int): String {
        val y0 = date.substring(0, 4).toInt()
        val m0 = date.substring(5, 7).toInt()
        val d0 = date.substring(8, 10).toInt()
        val mm = m0 - 1 + n
        val y = y0 + Math.floorDiv(mm, 12)
        val m = Math.floorMod(mm, 12) + 1
        val last = java.time.YearMonth.of(y, m).lengthOfMonth()
        return "$y-${pad(m)}-${pad(minOf(d0, last))}"
    }

    private val NORM_RE = Regex(js("[\\s_\\-–—.]+"))

    private fun norm(s: String?): String = NORM_RE.replace(Normalizer.normalize(lower(s.orEmpty()), Normalizer.Form.NFC), "")

    /** What a rule took (null: the match stays in the title). */
    private class Got(
        val repeat: NRepeat.Rule? = null,
        val date: String? = null,
        val time: String? = null,
        val soft: Boolean = false,
        val important: Boolean = false,
    )

    private class Rule(val kind: String, re: String, val bare: Boolean = false, val take: (Matcher) -> Got?) {
        val pattern: Pattern = Pattern.compile(
            js((if (bare) "(?<![\\p{L}\\p{N}!])" else B) + "(?:" + re + ")" + (if (bare) "(?![\\p{L}\\p{N}!])" else E)),
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
        )
    }

    private fun Matcher.g(i: Int): String? = if (i <= groupCount()) group(i) else null

    private val SPLIT_RE = Regex(js("\\s*(?:,|(?<![A-Za-z0-9_])und(?![A-Za-z0-9_])|(?<![A-Za-z0-9_])and(?![A-Za-z0-9_])|&|/)\\s*"), RegexOption.IGNORE_CASE)

    private fun weekdayList(s: String): List<Int> {
        val out = mutableListOf<Int>()
        s.split(SPLIT_RE).forEach { w -> weekdayOf(w.trim())?.let { if (it !in out) out.add(it) } }
        return out.sorted()
    }

    private fun unitOf(u: String): String {
        val x = lower(u)
        return when {
            x.startsWith("tag") || x.startsWith("day") -> "daily"
            x.startsWith("woche") || x.startsWith("week") -> "weekly"
            x.startsWith("monat") || x.startsWith("month") -> "monthly"
            else -> "yearly"
        }
    }

    private fun rules(today: String, times: Map<String, String>): List<Rule> {
        val morning = times.getValue("morning")
        val noon = times.getValue("noon")
        val afternoon = times.getValue("afternoon")
        val evening = times.getValue("evening")
        val night = times.getValue("night")
        val dayPart = linkedMapOf(
            "früh" to morning, "frueh" to morning, "morgens" to morning, "vormittags" to morning, "morning" to morning, "in the morning" to morning,
            "mittags" to noon, "noon" to noon, "at noon" to noon, "nachmittags" to afternoon, "afternoon" to afternoon, "in the afternoon" to afternoon,
            "abends" to evening, "abend" to evening, "evening" to evening, "in the evening" to evening, "tonight" to evening, "nachts" to night, "at night" to night,
        )
        val dp = byLengthDesc(dayPart.keys.toList()).joinToString("|") { it.replace(" ", "\\s+") }
        val unit = "(tag|tage|tagen|woche|wochen|monat|monate|monaten|jahr|jahre|jahren|day|days|week|weeks|month|months|year|years)"
        val wdAny = "(?:$WD_FULL|$WD_SHORT)\\.?"
        val wdSeq = "$wdAny(?:\\s*(?:,|und|and|&|/)\\s*$wdAny)*"
        val pre = "(?:um\\s+|at\\s+|gegen\\s+|ab\\s+)?"
        return listOf(
            // ── repeat ──
            Rule("repeat", "(\\d{1,3})\\s+(tage?|tagen|days?)\\s+nach\\s+(?:der\\s+)?erledigung|(\\d{1,3})\\s+days?\\s+after\\s+(?:completion|done|completing)") { m ->
                Got(repeat = NRepeat.Rule("after", (m.g(1) ?: m.g(3))!!.toInt()))
            },
            Rule("repeat", "(?:jeden|jede|every)\\s+($wdSeq)") { m ->
                val d = weekdayList(m.g(1)!!)
                if (d.isNotEmpty()) Got(repeat = NRepeat.Rule("weekly", 1, d)) else null
            },
            Rule("repeat", "(?:alle|every)\\s+($NUM)\\s+$unit(?:\\s+(?:am|on)\\s+($wdSeq))?") { m ->
                val n = numberOf(m.g(1)!!)
                if (n == null || n == 0) return@Rule null
                val type = unitOf(m.g(2)!!)
                val days = m.g(3)?.let { weekdayList(it) }
                Got(repeat = NRepeat.Rule(type, n, if (type == "weekly") days?.takeIf { it.isNotEmpty() } else null))
            },
            Rule("repeat", "werktags|(?:jeden|every)\\s+(?:werktag|weekday)|weekdays|(?:montags?|monday)\\s*(?:bis|-|–|to)\\s*(?:freitags?|friday)") {
                Got(repeat = NRepeat.Rule("weekly", 1, listOf(1, 2, 3, 4, 5)))
            },
            Rule("repeat", "täglich|taeglich|jeden\\s+tag|daily|every\\s+day") { Got(repeat = NRepeat.Rule("daily", 1)) },
            Rule("repeat", "wöchentlich|woechentlich|jede\\s+woche|weekly|every\\s+week") { Got(repeat = NRepeat.Rule("weekly", 1, null)) },
            Rule("repeat", "monatlich|jeden\\s+monat|monthly|every\\s+month") { Got(repeat = NRepeat.Rule("monthly", 1)) },
            Rule("repeat", "jährlich|jaehrlich|jedes\\s+jahr|yearly|annually|every\\s+year") { Got(repeat = NRepeat.Rule("yearly", 1)) },
            // ── a date ──
            Rule("date", "$PRE(\\d{4})-(\\d{2})-(\\d{2})") { m ->
                val y = m.g(1)!!.toInt()
                val mo = m.g(2)!!.toInt()
                val d = m.g(3)!!.toInt()
                if (validDate(y, mo, d)) Got(date = m.g(1) + "-" + m.g(2) + "-" + m.g(3)) else null
            },
            Rule("date", "$PRE(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4}|\\d{2})?") { m ->
                val d = m.g(1)!!.toInt()
                val mo = m.g(2)!!.toInt()
                val ys = m.g(3)
                if (!ys.isNullOrEmpty()) {
                    val y = if (ys.toInt() < 100) 2000 + ys.toInt() else ys.toInt()
                    if (validDate(y, mo, d)) Got(date = "$y-${pad(mo)}-${pad(d)}") else null
                } else {
                    upcoming(today, mo, d)?.let { Got(date = it) }
                }
            },
            Rule("date", "$PRE(\\d{1,2})(?:\\.|st|nd|rd|th)?\\s+(?:of\\s+)?($MON)\\.?(?:\\s+(\\d{4}))?") { m ->
                val d = m.g(1)!!.toInt()
                val mo = monthOf(m.g(2)!!) ?: return@Rule null
                val ys = m.g(3)
                if (ys != null) {
                    if (validDate(ys.toInt(), mo, d)) Got(date = "$ys-${pad(mo)}-${pad(d)}") else null
                } else {
                    upcoming(today, mo, d)?.let { Got(date = it) }
                }
            },
            Rule("date", "$PRE($MON)\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?") { m ->
                val mo = monthOf(m.g(1)!!) ?: return@Rule null
                val d = m.g(2)!!.toInt()
                val ys = m.g(3)
                if (ys != null) {
                    if (validDate(ys.toInt(), mo, d)) Got(date = "$ys-${pad(mo)}-${pad(d)}") else null
                } else {
                    upcoming(today, mo, d)?.let { Got(date = it) }
                }
            },
            Rule("date", "${PRE}in\\s+($NUM)\\s+$unit") { m ->
                val n = numberOf(m.g(1)!!)
                if (n == null || n == 0) return@Rule null
                when (unitOf(m.g(2)!!)) {
                    "daily" -> Got(date = NRepeat.addDays(today, n.toLong()))
                    "weekly" -> Got(date = NRepeat.addDays(today, 7L * n))
                    "monthly" -> Got(date = addMonths(today, n))
                    else -> Got(date = addMonths(today, 12 * n))
                }
            },
            Rule("date", "(?:nächste|naechste|nächsten|naechsten)\\s+woche|next\\s+week") { Got(date = nextWeekday(today, 1)) },
            Rule("date", "(?:am\\s+|this\\s+|on\\s+the\\s+)?(?:wochenende|weekend)") {
                val w = NRepeat.weekday(today)!!
                Got(date = if (w >= 6) today else NRepeat.addDays(today, (6 - w).toLong()))
            },
            Rule("date", "$PRE(?:(?:heute|today)\\s+(?:abend|nacht)|tonight)") { m ->
                Got(date = today, time = if (Regex("nacht", RegexOption.IGNORE_CASE).containsMatchIn(m.group())) night else evening)
            },
            Rule("date", "$PRE(?:übermorgen|uebermorgen|(?:the\\s+)?day\\s+after\\s+tomorrow)") { Got(date = NRepeat.addDays(today, 2)) },
            Rule("date", "$PRE(?:morgen|tomorrow)(?:\\s+(früh|frueh|vormittag|mittag|nachmittag|abend|morning|afternoon|evening|night))?") { m ->
                val p = lower(m.g(1).orEmpty())
                val time = when {
                    p.isEmpty() -> null
                    Regex("früh|frueh|vormittag|morning").containsMatchIn(p) -> morning
                    p.startsWith("mittag") -> noon
                    Regex("nachmittag|afternoon").containsMatchIn(p) -> afternoon
                    p.contains("night") -> night
                    else -> evening
                }
                Got(date = NRepeat.addDays(today, 1), time = time)
            },
            Rule("date", "$PRE(?:heute|today)") { Got(date = today) },
            Rule("date", "$PRE(?:(?:nächsten|naechsten|nächster|kommenden|diesen|next|this)\\s+)?($WD_WORD)") { m ->
                weekdayOf(m.g(1)!!)?.let { Got(date = nextWeekday(today, it)) }
            },
            // ── a time ──
            Rule("time", "(?:um\\s+|gegen\\s+|ab\\s+)?halb\\s+(\\d{1,2})(?:\\s*uhr)?") { m ->
                val h = m.g(1)!!.toInt() - 1
                if (h in 0..23) Got(time = hhmm(h, 30)) else null
            },
            Rule("time", "$pre(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)") { m ->
                var h = m.g(1)!!.toInt()
                val mi = m.g(2)?.toInt() ?: 0
                val pm = lower(m.g(3)!!).startsWith("p")
                if (h < 1 || h > 12 || mi > 59) return@Rule null
                if (h == 12) h = 0
                Got(time = hhmm(if (pm) h + 12 else h, mi))
            },
            Rule("time", "$pre(\\d{1,2})(?:[:.](\\d{2}))?\\s*(?:uhr|h|o'clock)") { m ->
                val h = m.g(1)!!.toInt()
                val mi = m.g(2)?.toInt() ?: 0
                if (h <= 23 && mi <= 59) Got(time = hhmm(h, mi)) else null
            },
            Rule("time", "$pre(\\d{1,2}):(\\d{2})") { m ->
                val h = m.g(1)!!.toInt()
                val mi = m.g(2)!!.toInt()
                if (h <= 23 && mi <= 59) Got(time = hhmm(h, mi)) else null
            },
            Rule("time", "(?:um|at|gegen)\\s+(\\d{1,2})") { m ->
                val h = m.g(1)!!.toInt()
                if (h <= 23) Got(time = hhmm(h, 0)) else null
            },
            Rule("time", "($dp)") { m -> Got(time = dayPart[lower(m.g(1)!!).replace(Regex(js("\\s+")), " ")], soft = true) },
            // ── important ──
            Rule("important", "wichtig|dringend|important|urgent") { Got(important = true) },
            Rule("important", "!{1,3}", bare = true) { Got(important = true) },
        )
    }

    private fun blank(s: String, start: Int, end: Int): String = s.substring(0, start) + " ".repeat(end - start) + s.substring(end)

    private fun matchList(name: String, lists: List<Ref>): Ref? {
        val n = norm(name)
        lists.firstOrNull { norm(it.name) == n }?.let { return it }
        val pre = lists.filter { norm(it.name).startsWith(n) }
        return if (pre.size == 1) pre[0] else null
    }

    private val SPACES = Regex(js("\\s+"))

    private fun matchPerson(name: String, people: List<Ref>): Ref? {
        val n = norm(name)
        for (p in people) {
            if (norm(p.name) == n) return p
            if (p.name.split(SPACES).any { norm(it) == n }) return p
        }
        val pre = people.filter { p -> norm(p.name).startsWith(n) || p.name.split(SPACES).any { norm(it).startsWith(n) } }
        return if (pre.size == 1) pre[0] else null
    }

    private val CONTROL = Regex("[\\u0000-\\u001f\\u007f]")
    private val TIME_RE = Regex("^[0-9]{2}:[0-9]{2}$")
    private val TAG = Pattern.compile(js("(?:^|(?<=[\\s,;(]))([#@])($L[\\p{L}\\p{N}_\\-&+.]*$L|$L)"))
    private val T1 = Regex(js("\\s+"))
    private val T2 = Regex(js("\\s+([,;:.!?])"))
    private val T3 = Regex("([,;:])(?=[,;:])")
    private val T4 = Regex(js("^[\\s,;:\\-–—]+|[\\s,;:\\-–—]+\\z"))
    private val T5 = Regex(js("\\s*,\\s*,+"))

    /** The line → what it says. Throws [IllegalArgumentException] without a valid [Ctx.today] (as the JS does). */
    fun parse(text: String?, ctx: Ctx): Parsed {
        require(NRepeat.toDays(ctx.today) != null) { "quickparse: ctx.today (YYYY-MM-DD) is required" }
        val src = CONTROL.replace(Normalizer.normalize(text.orEmpty(), Normalizer.Form.NFC), " ")
        val times = DEFAULT_TIMES.mapValues { (k, v) -> ctx.times[k]?.takeIf { TIME_RE.matches(it) } ?: v }
        var work = src
        var date: String? = null
        var time: String? = null
        var important = false
        var list: Ref? = null
        var listName: String? = null
        var assignee: Ref? = null
        var assigneeName: String? = null
        var repeat: NRepeat.Rule? = null
        val marks = mutableListOf<Mark>()
        var softTime = false

        // #list and @person first (their words are never dates)
        data class Found(val start: Int, val end: Int, val sign: String, val name: String)
        val found = mutableListOf<Found>()
        val tm = TAG.matcher(work)
        while (tm.find()) found.add(Found(tm.start(), tm.end(), tm.group(1)!!, tm.group(2)!!))
        for (f in found) {
            if (f.sign == "#" && listName == null) {
                listName = f.name
                list = matchList(f.name, ctx.lists)
                if (list != null) listName = null
                marks.add(Mark(f.start, f.end, "list"))
                work = blank(work, f.start, f.end)
            } else if (f.sign == "@" && assigneeName == null && assignee == null) {
                assignee = matchPerson(f.name, ctx.people)
                assigneeName = if (assignee != null) null else f.name
                marks.add(Mark(f.start, f.end, "person"))
                work = blank(work, f.start, f.end)
            }
        }

        for (r in rules(ctx.today, times)) {
            var last = 0
            while (last <= work.length) {
                val mm = r.pattern.matcher(work)
                if (!mm.find(last)) break
                val s = mm.start()
                val e = mm.end()
                last = e
                if (mm.group().trim().isEmpty()) {
                    last = e + 1
                    continue
                }
                if (r.kind == "date" && date != null) break
                if (r.kind == "time" && time != null && !softTime) break
                if (r.kind == "repeat" && repeat != null) break
                if (r.kind == "important" && important) {
                    work = blank(work, s, e)
                    marks.add(Mark(s, e, "important"))
                    continue
                }
                val got = r.take(mm) ?: continue
                if (got.time != null && r.kind == "time" && time != null && softTime && got.soft) continue
                if (got.repeat != null) repeat = got.repeat
                if (got.date != null) date = got.date
                // a time of a word („morgen früh“, „abends“) gives way to a written one („morgen früh 7 Uhr“)
                if (got.time != null && (time == null || softTime)) {
                    time = got.time
                    softTime = got.soft || r.kind == "date"
                }
                if (got.important) important = true
                marks.add(Mark(s, e, r.kind))
                work = blank(work, s, e)
                if (r.kind != "important") break
            }
        }

        if (repeat != null) {
            if (repeat.type == "weekly" && repeat.days == null) repeat = repeat.copy(days = listOf(NRepeat.weekday(date ?: ctx.today)!!))
            repeat = NRepeat.normalize(repeat)
            if (repeat != null && date == null) date = if (repeat.type == "after") ctx.today else NRepeat.first(repeat, ctx.today)
        }
        if (time != null && date == null) {
            // a time alone: today, or tomorrow when that time is over (ctx.now 'HH:MM', optional)
            date = if (ctx.now != null && TIME_RE.matches(ctx.now) && time <= ctx.now) NRepeat.addDays(ctx.today, 1) else ctx.today
        }
        val remind = if (date != null && time != null) Remind(date, time) else null

        var title = T1.replace(work, " ")
        title = T2.replace(title, "$1")
        title = T3.replace(title, "")
        title = T4.replace(title, "")
        title = T5.replace(title, ",")
        title = title.trim()
        return Parsed(title, date, time, remind, important, list, listName, assignee, assigneeName, repeat, marks.sortedBy { it.start })
    }
}
