package com.gatecontrol.android.profile

import java.text.Normalizer

/**
 * The letters initials are made of (second real test over WhatsApp: a chat „Schatzimausi 💕“ showed „S“ and a
 * replacement mark — `take(1)` of „💕“ is half of its surrogate pair). Whole characters only — a code point with its
 * combining marks, after NFC — and only letters and digits: an emoji, a symbol or punctuation is never an initial.
 */
object InitialLetters {
    /** The letters and digits of [word], each one whole character, in order ("💕Mama" → M, a, m, a). */
    fun of(word: String): List<String> {
        val s = Normalizer.normalize(word, Normalizer.Form.NFC)
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            var end = i + Character.charCount(cp)
            if (Character.isLetterOrDigit(cp)) {
                while (end < s.length && isMark(s.codePointAt(end))) end += Character.charCount(s.codePointAt(end))
                out += s.substring(i, end)
            }
            i = end
        }
        return out
    }

    /** The first letter or digit of [word], "" when it has none. */
    fun first(word: String): String = of(word).firstOrNull().orEmpty()

    /** One initial of [text] (its first letter or digit, upper case), [fallback] when it has none. */
    fun initial(text: String?, fallback: String = "?"): String = first(text.orEmpty()).uppercase().ifEmpty { fallback }

    // a combining mark belongs to its letter ("A\u0308" → „Ä“); variation selectors and enclosing marks (keycaps) do not
    private fun isMark(cp: Int): Boolean {
        if (cp in 0xFE00..0xFE0F) return false
        val t = Character.getType(cp)
        return t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt()
    }
}
