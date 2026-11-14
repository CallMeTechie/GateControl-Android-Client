package com.gatecontrol.android.notizen

import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern

/**
 * „Wer ist dran“: what a line of the quick entry says about several people and stars — a 1:1 port of the plugin's
 * `ui/turnwords.js` (gatecontrol-notizen 1.6.0). Applied AFTER [QuickParse] (which stays as it is and takes the first
 * „@Name“). Deterministic, no AI: the plugin's `test/vectors/turnwords.json` (copied into the unit tests) is what this
 * port must give, case by case.
 *
 * The JavaScript regular expressions are kept as they are; as in [QuickParse] `\s` is JavaScript's whitespace set and
 * `\d` is `[0-9]`, so the JVM (unit tests) and Android's ICU engine read them the same as V8.
 */
object TurnWords {
    const val ROTATE = "rotate"
    const val FIRST = "first"

    /** [people]: the person [QuickParse] took and every further „@Name“ that names someone, in the order written. */
    data class Result(val title: String, val people: List<QuickParse.Ref>, val turn: String?, val stars: Int?)

    // JavaScript's \s (WhiteSpace and LineTerminator of ECMAScript)
    private const val WS = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    private const val S = "[$WS]"

    private const val L = "[\\p{L}\\p{N}]"
    private const val B = "(?<!$L)" // start of a word
    private const val E = "(?!$L)" // end of a word
    private const val ROTATE_WORDS = "reihum|abwechselnd|in turns|taking turns"
    private const val FIRST_WORDS = "wer zuerst|whoever is first|whoever first"

    private const val IU = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE

    private val NORM_RE = Regex("[${WS}_\\-–—.]+")
    private val SPACES = Regex("$S+")
    private val TAG = Pattern.compile("(?:^|(?<=[$WS,;(]))@($L[\\p{L}\\p{N}_\\-&+.]*$L|$L)")
    private val STARS = Pattern.compile("$B([0-9]{1,2})$S*(?:sterne|stern|stars|star)$E", IU)
    private val FIRST_RE = Pattern.compile("$B(?:$FIRST_WORDS)$E", IU)
    private val ROTATE_RE = Pattern.compile("$B(?:$ROTATE_WORDS)$E", IU)
    private val T1 = Regex("$S+")
    private val T2 = Regex("$S+([,;:.!?])")
    private val T3 = Regex("([,;:])(?=[,;:])")
    private val T4 = Regex("^[$WS,;:\\-–—]+|[$WS,;:\\-–—]+\\z")
    private val T5 = Regex("$S*,$S*,+")

    private fun norm(s: String?): String = NORM_RE.replace(Normalizer.normalize(s.orEmpty().lowercase(Locale.ROOT), Normalizer.Form.NFC), "")

    private fun blank(s: String, start: Int, end: Int): String = s.substring(0, start) + " ".repeat(end - start) + s.substring(end)

    // the same person quickparse would find
    private fun matchPerson(name: String, people: List<QuickParse.Ref>): QuickParse.Ref? {
        val n = norm(name)
        for (p in people) {
            if (norm(p.name) == n) return p
            if (p.name.split(SPACES).any { norm(it) == n }) return p
        }
        val pre = people.filter { p -> norm(p.name).startsWith(n) || p.name.split(SPACES).any { norm(it).startsWith(n) } }
        return if (pre.size == 1) pre[0] else null
    }

    /**
     * [title] and [assignee] of [QuickParse.parse]; [people]: whom a task may go to; [stars]: the person may give stars
     * here (a parent of the family, the task goes into the family's list) — else „Hotel 4 Sterne buchen“ stays whole.
     */
    fun apply(title: String?, assignee: QuickParse.Ref?, people: List<QuickParse.Ref>, stars: Boolean): Result {
        val src = title.orEmpty()
        var work = src
        val out = mutableListOf<QuickParse.Ref>()
        assignee?.let { out.add(QuickParse.Ref(it.id, it.name)) }
        var took = false

        data class Found(val start: Int, val end: Int, val name: String)
        val found = mutableListOf<Found>()
        val tm = TAG.matcher(work)
        while (tm.find()) found.add(Found(tm.start(), tm.end(), tm.group(1)!!))
        for (f in found) {
            val p = matchPerson(f.name, people) ?: continue
            if (out.none { it.id == p.id }) out.add(QuickParse.Ref(p.id, p.name))
            work = blank(work, f.start, f.end)
            took = true
        }

        var n: Int? = null
        if (stars) {
            val sm = STARS.matcher(work)
            if (sm.find()) {
                val v = sm.group(1)!!.toInt()
                if (v in 1..10) {
                    n = v
                    work = blank(work, sm.start(), sm.end())
                    took = true
                }
            }
        }

        var turn: String? = null
        if (out.size > 1) {
            val fm = FIRST_RE.matcher(work)
            val rm = ROTATE_RE.matcher(work)
            val first = fm.find()
            val rotate = rm.find()
            if (first && (!rotate || fm.start() < rm.start())) {
                turn = FIRST
                work = blank(work, fm.start(), fm.end())
                took = true
            } else if (rotate) {
                turn = ROTATE
                work = blank(work, rm.start(), rm.end())
                took = true
            }
        }

        // nothing taken: the title is quickparse's, untouched
        if (!took) return Result(src, out, null, null)
        var t = T1.replace(work, " ")
        t = T2.replace(t, "$1")
        t = T3.replace(t, "")
        t = T4.replace(t, "")
        t = T5.replace(t, ",")
        return Result(t.trim(), out, turn, n)
    }

    /** The line as [QuickParse] read it → what it says about several people and stars. */
    fun apply(parsed: QuickParse.Parsed, people: List<QuickParse.Ref>, stars: Boolean): Result = apply(parsed.title, parsed.assignee, people, stars)
}
