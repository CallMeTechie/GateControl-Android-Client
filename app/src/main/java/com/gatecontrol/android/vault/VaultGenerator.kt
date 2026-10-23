package com.gatecontrol.android.vault

import java.security.SecureRandom
import kotlin.math.ln

/**
 * Password generator (mockup A6) and a strength estimate (setup, A1). Random: lower case always, upper case,
 * digits, symbols each on request and each at least once; "avoid look-alikes" drops l, 1, I, O, 0. Words: pronounceable
 * made-up words (consonant-vowel syllables, no dictionary to ship) joined by "-", one with a digit. Pure, unit-tested.
 */
object VaultGenerator {
    data class Options(
        val words: Boolean = false,
        val length: Int = 20,
        val upper: Boolean = true,
        val digits: Boolean = true,
        val symbols: Boolean = true,
        val avoidAmbiguous: Boolean = false,
        val wordCount: Int = 4,
    )

    private const val LOWER = "abcdefghijkmnopqrstuvwxyz" + "l"
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ" + "IO"
    private const val DIGITS = "23456789" + "01"
    private const val SYMBOLS = "!#$%&*+-=?@^_~"
    private const val AMBIGUOUS = "l1IO0"
    private const val CONSONANTS = "bdfgkmnprstvz"
    private const val VOWELS = "aeiou"

    private val random = SecureRandom()

    private fun pick(s: String) = s[random.nextInt(s.length)]

    fun generate(o: Options): String = if (o.words) words(o) else chars(o)

    private fun chars(o: Options): String {
        val len = o.length.coerceIn(8, 64)
        fun clean(s: String) = if (o.avoidAmbiguous) s.filter { it !in AMBIGUOUS } else s
        val classes = buildList {
            add(clean(LOWER))
            if (o.upper) add(clean(UPPER))
            if (o.digits) add(clean(DIGITS))
            if (o.symbols) add(SYMBOLS)
        }
        val all = classes.joinToString("")
        val out = CharArray(len) { pick(all) }
        // each chosen class at least once, at distinct random places
        val places = (0 until len).shuffled(random).take(classes.size)
        classes.forEachIndexed { i, c -> out[places[i]] = pick(c) }
        return String(out)
    }

    private fun word(): String {
        val syllables = 2 + random.nextInt(2)
        val sb = StringBuilder()
        repeat(syllables) { sb.append(pick(CONSONANTS)).append(pick(VOWELS)) }
        return sb.toString().replaceFirstChar { it.uppercase() }
    }

    private fun words(o: Options): String {
        val n = o.wordCount.coerceIn(3, 8)
        val list = MutableList(n) { word() }
        val withDigit = random.nextInt(n)
        list[withDigit] = list[withDigit] + (2 + random.nextInt(98)).toString()
        return list.joinToString("-")
    }

    enum class Strength { WEAK, FAIR, STRONG, VERY_STRONG }

    /** Rough entropy in bits: the character pool per class used, length; repeated characters count less. */
    fun bits(password: String): Double {
        if (password.isEmpty()) return 0.0
        var pool = 0
        if (password.any { it.isLowerCase() }) pool += 26
        if (password.any { it.isUpperCase() }) pool += 26
        if (password.any { it.isDigit() }) pool += 10
        if (password.any { !it.isLetterOrDigit() }) pool += 33
        val distinct = password.toSet().size.toDouble()
        val effective = minOf(password.length.toDouble(), distinct * 1.5)
        return effective * ln(pool.coerceAtLeast(10).toDouble()) / ln(2.0)
    }

    fun strength(password: String): Strength {
        val b = bits(password)
        return when {
            password.length < 10 || b < 50 -> Strength.WEAK
            b < 70 -> Strength.FAIR
            b < 100 -> Strength.STRONG
            else -> Strength.VERY_STRONG
        }
    }

    /** Words in a passphrase ("Sonnenblume-Kanu-Turm-47" → 4). */
    fun wordCount(password: String): Int = password.split(Regex("[\\s\\-_.]+")).count { it.length >= 2 }

    /** Master password rule: at least 12 characters and not weak. */
    fun goodMasterPassword(password: String) = password.length >= 12 && strength(password) != Strength.WEAK
}
