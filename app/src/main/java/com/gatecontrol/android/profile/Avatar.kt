package com.gatecontrol.android.profile

import com.gatecontrol.android.familie.presence.PresenceState
import java.text.Normalizer

/** A favourite colour: palette key (stored on the server) and `#RRGGBB`. */
data class PaletteColor(val key: String, val hex: String)

/**
 * The favourite colour palette of GateControl (core 1.168+, `services/avatars.js`
 * PALETTE). The server sends its own palette with the profile; this one is
 * the fallback for an older answer or the offline start. Red is never part
 * of it: a red ring means "away".
 */
object AvatarPalette {

    /** Same order as the server: the default colour is `palette[id % 10]`. */
    val DEFAULT: List<PaletteColor> = listOf(
        "blue" to "#3B82F6", "teal" to "#14B8A6", "green" to "#22C55E", "lime" to "#84CC16", "yellow" to "#EAB308",
        "orange" to "#F97316", "pink" to "#EC4899", "purple" to "#A855F7", "indigo" to "#6366F1", "slate" to "#64748B",
    ).map { (key, hex) -> PaletteColor(key, hex) }

    /** Ring colour of someone who is away. */
    const val AWAY_HEX = "#E5484D"

    private val KEY_RE = Regex("^[a-z][a-z0-9_-]{0,23}$")
    private val HEX_RE = Regex("^#[0-9A-Fa-f]{6}$")

    /** Red means "away" and is never offered, whatever a server sends. */
    private fun isRed(c: PaletteColor): Boolean = c.key == "red" || c.hex.equals(AWAY_HEX, ignoreCase = true)

    /** The colours a person may pick (from the server's palette, else [DEFAULT]). */
    fun selectable(palette: List<PaletteColor>): List<PaletteColor> =
        palette.filterNot(::isRed).ifEmpty { DEFAULT }

    /** `#RRGGBB` of [key] in [palette] (then [DEFAULT]), null when unknown. */
    fun hexOf(key: String?, palette: List<PaletteColor> = DEFAULT): String? {
        if (key == null) return null
        return (palette.firstOrNull { it.key == key } ?: DEFAULT.firstOrNull { it.key == key })?.hex
    }

    /** The server's default for a user that never chose: `palette[id % 10]`. */
    fun defaultFor(userId: Int): PaletteColor = DEFAULT[Math.floorMod(userId, DEFAULT.size)]

    fun validKey(key: String?): String? = key?.trim()?.takeIf { KEY_RE.matches(it) }

    /** `#RRGGBB` normalised to upper case, else null. */
    fun validHex(hex: String?): String? = hex?.trim()?.takeIf { HEX_RE.matches(it) }?.uppercase()

    /** Opaque ARGB of `#RRGGBB` (no android.graphics: unit-testable), null when invalid. */
    fun argb(hex: String?): Long? {
        val h = validHex(hex) ?: return null
        return 0xFF000000L or h.substring(1).toLong(16)
    }
}

/** Ring around an avatar: presence at a glance. */
sealed interface AvatarRing {
    /** At home: the person's favourite colour (`#RRGGBB`; null = the app's accent). */
    data class Home(val colorHex: String?) : AvatarRing

    /** Away: red. */
    data object Away : AvatarRing

    /** Unknown, not shared, module off: a thin neutral border. */
    data object None : AvatarRing

    companion object {
        fun of(presence: PresenceState?, colorHex: String?): AvatarRing = when (presence) {
            PresenceState.HOME -> Home(AvatarPalette.validHex(colorHex))
            PresenceState.AWAY -> Away
            PresenceState.UNKNOWN, PresenceState.NOT_SHARED, null -> None
        }
    }
}

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

/** Initials as GateControl and the Familie plugin make them. */
object AvatarInitials {
    private val SPLIT = Regex("[\\s._-]+")

    private val WORDS = Regex("\\s+")

    /**
     * Two words → their first letters, one word → its first two letters, no letter at all (nothing, only emoji) → "?"
     * ([InitialLetters]: whole characters, letters and digits only — „Schatzimausi 💕“ → „S“).
     */
    fun of(name: String?): String {
        val words = name.orEmpty().trim().split(SPLIT).filter { it.isNotEmpty() }
        val chars = if (words.size > 1) {
            listOf(InitialLetters.first(words[0]), InitialLetters.first(words[1]))
        } else {
            InitialLetters.of(words.firstOrNull().orEmpty()).take(2)
        }
        return chars.joinToString("").uppercase().ifEmpty { "?" }
    }

    /**
     * Initials of a chat's name (the Messenger's rule, as its portal and the Windows client make them): the first letters
     * of the first and the last word („van der Berg“ → „VB“), one word → its first two letters, no letter → "?".
     */
    fun ofChat(name: String?): String {
        val words = name.orEmpty().trim().split(WORDS).filter { it.isNotEmpty() }
        val chars = if (words.size > 1) {
            listOf(InitialLetters.first(words.first()), InitialLetters.first(words.last()))
        } else {
            InitialLetters.of(words.firstOrNull().orEmpty()).take(2)
        }
        return chars.joinToString("").uppercase().ifEmpty { "?" }
    }

    /** Initials a server sent, letters and digits only and at most 3 of them (whole characters); null when none is left. */
    fun clean(server: String?): String? =
        server?.let { InitialLetters.of(it).take(3).joinToString("") }?.takeIf { it.isNotEmpty() }

    /** Initials from the server when usable (1–3 letters or digits), else made from [name]. */
    fun pick(server: String?, name: String?): String {
        val letters = InitialLetters.of(server.orEmpty())
        return if (letters.isNotEmpty() && letters.size <= 3) letters.joinToString("") else of(name)
    }
}
