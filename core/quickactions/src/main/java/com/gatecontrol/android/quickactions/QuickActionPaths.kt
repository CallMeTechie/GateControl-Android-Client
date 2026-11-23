package com.gatecontrol.android.quickactions

/**
 * Icon paths of the quick actions: the server sends per action an SVG path `d` for a 24×24 viewBox (stroke icon,
 * like a plugin's tab icon). Only the check lives here; drawing it is the app's (phone: `QuickActionIcons`, TV: its
 * own tiles).
 */
object QuickActionPaths {
    const val MAX_LENGTH = 4_000

    /** Path commands, numbers and separators only. */
    private val PATH_RE = Regex("^[MmLlHhVvCcSsQqTtAaZz0-9eE.,+\\-\\s]+$")

    /** A path the app may try to draw, trimmed; null when empty, too long or with foreign characters. */
    fun sanitize(d: String?): String? {
        val s = d?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH } ?: return null
        if (!PATH_RE.matches(s) || (s[0] != 'M' && s[0] != 'm')) return null
        return s
    }
}
