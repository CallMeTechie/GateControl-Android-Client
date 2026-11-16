package com.gatecontrol.android.terminal

import android.view.KeyEvent

/** Strg/Alt of the bar: tapped once for the next key, twice locked, a third tap off (design section 4). */
enum class Latch { OFF, ONCE, LOCKED;

    fun tapped(): Latch = when (this) {
        OFF -> ONCE
        ONCE -> LOCKED
        LOCKED -> OFF
    }

    /** Read for one key: a one-time press is used up. */
    fun used(): Latch = if (this == ONCE) OFF else this
}

/** A key of the bar: a key code for the emulator ([code]), a character ([char]) or a modifier. */
data class TermKey(val label: String, val code: Int = 0, val char: Int = 0, val modifier: String? = null) {
    companion object {
        const val CTRL = "ctrl"
        const val ALT = "alt"
        const val FN = "fn"
    }
}

/**
 * The fixed bar of stage 3 (decision 10): Esc, Tab, Strg, Alt, |, ~, /, -, arrows; in landscape one row with „F“, which
 * opens F1…F12 and page up/down (for htop, mc, nano). Portrait: two rows of six so each key stays big enough.
 */
object TermKeys {
    val ESC = TermKey("Esc", code = KeyEvent.KEYCODE_ESCAPE)
    val TAB = TermKey("Tab", code = KeyEvent.KEYCODE_TAB)
    val CTRL = TermKey("Ctrl", modifier = TermKey.CTRL)
    val ALT = TermKey("Alt", modifier = TermKey.ALT)
    val LEFT = TermKey("←", code = KeyEvent.KEYCODE_DPAD_LEFT)
    val DOWN = TermKey("↓", code = KeyEvent.KEYCODE_DPAD_DOWN)
    val UP = TermKey("↑", code = KeyEvent.KEYCODE_DPAD_UP)
    val RIGHT = TermKey("→", code = KeyEvent.KEYCODE_DPAD_RIGHT)
    val FN = TermKey("F", modifier = TermKey.FN)

    private fun ch(c: Char) = TermKey(c.toString(), char = c.code)

    val portrait: List<List<TermKey>> = listOf(
        listOf(ESC, TAB, CTRL, ALT, ch('|'), ch('~')),
        listOf(ch('/'), ch('-'), LEFT, DOWN, UP, RIGHT),
    )

    val landscape: List<List<TermKey>> = listOf(
        listOf(ESC, TAB, CTRL, ALT, ch('|'), ch('~'), ch('/'), ch('-'), LEFT, DOWN, UP, RIGHT, FN),
    )

    /** „F“: F1…F12 and page up/down. */
    val functions: List<TermKey> = (1..12).map { TermKey("F$it", code = KeyEvent.KEYCODE_F1 + it - 1) } + listOf(
        TermKey("PgUp", code = KeyEvent.KEYCODE_PAGE_UP),
        TermKey("PgDn", code = KeyEvent.KEYCODE_PAGE_DOWN),
    )
}
