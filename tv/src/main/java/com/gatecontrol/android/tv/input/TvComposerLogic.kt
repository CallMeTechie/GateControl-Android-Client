package com.gatecontrol.android.tv.input

/** Where a text building block comes from (its small label in the column „Textbausteine“). */
enum class TvSnippetKind {
    /** The person's own, from Einstellungen › Eingabe. */
    OWN,

    /** An answer the mail plugin's AI suggests for this mail (stage 3f). */
    AI,

    /** A quick answer the messenger suggests for the last message (stage 3e). */
    SUGGESTION,
}

data class TvSnippet(val text: String, val kind: TvSnippetKind)

/**
 * A source of text building blocks for [com.gatecontrol.android.tv.ui.input.TvComposer]: the own ones (settings), later
 * the AI answers of a mail or the messenger's suggestions. Asked when the composer opens; a failing source gives none.
 */
fun interface TvSnippetSource {
    suspend fun snippets(): List<TvSnippet>
}

/**
 * The rules of the input building block of GateControl TV (stage 3d): the preview „So senden?“ smooths what the
 * dictation of Fire OS wrote (first word capital, a full stop at the end) and marks numbers and times, where it
 * mishears most. Pure (no Android), unit-tested.
 */
object TvComposerLogic {
    /** Longest text the composer takes (an answer, a note; GateDrop takes 20 000). */
    const val MAX_TEXT = 4_000

    /** Own building blocks: how many and how long each. */
    const val MAX_SNIPPETS = 30
    const val MAX_SNIPPET = 300

    private val END = setOf('.', '!', '?', '…', ')', '"', '“', '”', '»', '«')
    private val SPACES = Regex("[ \\t\\u00A0]+")
    private val BEFORE_MARK = Regex("[ \\t]+([,.!?;:])")
    private val TOKEN = Regex("[\\p{L}\\p{N}'’]+(?:[.:,][0-9]+)*")

    /** Number and time words the dictation writes out (de, en); with „Uhr“, „halb“, „viertel“ they make a time. */
    private val NUMBER_WORDS = setOf(
        "null", "eins", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun", "zehn", "elf", "zwölf",
        "dreizehn", "vierzehn", "fünfzehn", "sechzehn", "siebzehn", "achtzehn", "neunzehn", "zwanzig", "dreißig",
        "vierzig", "fünfzig", "sechzig", "hundert", "tausend", "halb", "viertel", "dreiviertel", "uhr",
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "twenty", "thirty", "forty", "fifty", "hundred", "thousand", "half", "quarter", "o'clock",
    )

    /**
     * „Satzzeichen glätten“: spaces tidied (none before , . ! ? ; :), the first letter capital and, unless the text
     * ends with a sign of its own, a full stop at the end. Line breaks stay.
     */
    fun smooth(raw: String): String {
        val lines = raw.replace("\r\n", "\n").lines().map { it.replace(SPACES, " ").trim().replace(BEFORE_MARK, "$1") }
        var t = lines.joinToString("\n").trim()
        if (t.isEmpty()) return t
        val first = t.indexOfFirst { it.isLetter() }
        if (first >= 0 && t[first].isLowerCase()) t = t.substring(0, first) + t[first].uppercase() + t.substring(first + 1)
        t = t.trimEnd(',', ';', ' ')
        if (t.isNotEmpty() && t.last() !in END && t.last() != ':' && !isSymbol(t.last())) t += "."
        return t
    }

    /** An emoji or other symbol at the end: no full stop after it. */
    private fun isSymbol(c: Char): Boolean = Character.isSurrogate(c) || Character.getType(c) == Character.OTHER_SYMBOL.toInt()

    /**
     * Where [text] has numbers or times („17:30“, „10.“, „halb sieben“, „17 Uhr“): yellow in the preview. Neighbouring
     * words of that kind make one range.
     */
    fun marks(text: String): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        for (m in TOKEN.findAll(text)) {
            val word = m.value
            val numeric = word.any { it.isDigit() } || word.lowercase() in NUMBER_WORDS
            if (!numeric) continue
            val last = ranges.lastOrNull()
            val between = last?.let { text.substring(it.last + 1, m.range.first) }
            if (last != null && between != null && between.isNotEmpty() && between.all { it == ' ' }) {
                ranges[ranges.size - 1] = last.first..m.range.last
            } else {
                ranges += m.range
            }
        }
        // „uhr“ alone („um wie viel Uhr“) is no time
        return ranges.filterNot { r -> text.substring(r.first, r.last + 1).equals("uhr", ignoreCase = true) }
    }

    /** A building block into the draft: on its own when the draft is empty, else after a space at the end. */
    fun insert(draft: String, snippet: String): String {
        val d = draft.trimEnd()
        return (if (d.isEmpty()) snippet.trim() else d + " " + snippet.trim()).take(MAX_TEXT)
    }

    /** „Weiter sprechen“: the next dictation goes after a space at the end. */
    fun continued(draft: String): String = if (draft.isEmpty() || draft.last().isWhitespace()) draft else "$draft "

    /** The own building blocks as kept: trimmed, not empty, no doubles, at most [MAX_SNIPPETS] of [MAX_SNIPPET]. */
    fun cleanSnippets(list: List<String>): List<String> =
        list.map { s -> s.replace("\r\n", "\n").trim().take(MAX_SNIPPET).trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_SNIPPETS)

    /** [list] with the entry [index] moved by [by] places (−1 up, +1 down); the same list at an end. */
    fun move(list: List<String>, index: Int, by: Int): List<String> {
        val to = index + by
        if (index !in list.indices || to !in list.indices) return list
        return list.toMutableList().apply { add(to, removeAt(index)) }
    }
}
