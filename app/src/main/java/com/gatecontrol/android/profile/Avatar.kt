package com.gatecontrol.android.profile

import com.gatecontrol.android.familie.presence.PresenceState

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

/** Initials as GateControl and the Familie plugin make them. */
object AvatarInitials {
    private val SPLIT = Regex("[\\s._-]+")

    /** Two words → their first letters, one word → its first two letters, nothing → "?". */
    fun of(name: String?): String {
        val words = name.orEmpty().trim().split(SPLIT).filter { it.isNotEmpty() }
        if (words.isEmpty()) return "?"
        val chars = if (words.size > 1) {
            listOf(firstChar(words[0]), firstChar(words[1]))
        } else {
            val w = words[0]
            val first = firstChar(w)
            listOf(first, firstChar(w.substring(first.length)))
        }
        return chars.joinToString("").uppercase().ifEmpty { "?" }
    }

    /** Initials from the server when usable (1–3 characters), else made from [name]. */
    fun pick(server: String?, name: String?): String {
        val s = server?.trim()?.filter { !it.isISOControl() }.orEmpty()
        return if (s.isNotEmpty() && s.codePointCount(0, s.length) <= 3) s else of(name)
    }

    private fun firstChar(s: String): String = if (s.isEmpty()) "" else s.substring(0, s.offsetByCodePoints(0, 1))
}
