package com.gatecontrol.android.ui.quickactions

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * How the start tab's buttons share a row (pure, unit-tested): never fewer
 * than five in a row — a narrow phone makes them smaller instead of
 * wrapping the fifth — and on wide screens as many more as keep each one
 * from growing past [maxTilePx]. All in whole pixels, rounded down, so the
 * row never comes out wider than the space it has.
 */
internal object StartTileGrid {
    const val MIN_PER_ROW = 5

    /** Buttons in a row of [widthPx] with [gapPx] between them. */
    fun columns(widthPx: Int, gapPx: Int, maxTilePx: Int): Int {
        if (widthPx <= 0 || maxTilePx <= 0) return MIN_PER_ROW
        val fit = (widthPx + gapPx + maxTilePx + gapPx - 1) / (maxTilePx + gapPx)
        return maxOf(MIN_PER_ROW, fit)
    }

    /** Width of each of [columns] buttons in a row of [widthPx]. */
    fun tileWidth(widthPx: Int, gapPx: Int, columns: Int): Int =
        maxOf(0, (widthPx - gapPx * (columns - 1)) / columns)
}

/**
 * Where a button's title breaks (pure, unit-tested): only between words —
 * at a space, or after a hyphen or slash the title itself has — never
 * inside one.
 */
internal object TileLabelFit {
    private const val BREAK_AFTER = "-/–"

    /** Whether a line ending at [end] (exclusive) in [text] ends inside a word. */
    fun breaksInsideWord(text: String, end: Int): Boolean {
        if (end <= 0 || end >= text.length) return false
        val before = text[end - 1]
        return !(before.isWhitespace() || before in BREAK_AFTER || text[end].isWhitespace())
    }

    /** Whether any of the lines ending at [lineEnds] (all but the last) splits a word of [text]. */
    fun splitsWord(text: String, lineEnds: List<Int>): Boolean = lineEnds.any { breaksInsideWord(text, it) }

    /** The font sizes to try, largest first, in half steps down to [min]. */
    fun sizes(max: Float, min: Float, step: Float = 0.5f): List<Float> {
        if (max <= min) return listOf(min)
        val out = mutableListOf<Float>()
        var s = max
        while (s > min + 0.001f) {
            out += s
            s -= step
        }
        out += min
        return out
    }
}

/** How a title is set: its size and tracking, and whether it had to go on one line cut with „…“. */
internal data class TileLabelLayout(val fontSize: TextUnit, val letterSpacing: TextUnit, val singleLine: Boolean)

/** Down to here the title keeps the theme's tracking … */
internal val TILE_LABEL_SMALL_SIZE = 9.sp

/** … below, without it, down to this (a long word on a narrow phone's tile). */
internal val TILE_LABEL_MIN_SIZE = 8.sp

/**
 * A start tab button's title below its tile: at most two lines, broken only
 * between words (no hyphenation). The size steps down until every word fits
 * whole (below 9 sp without the extra tracking, down to 8 sp); a single
 * word too long even at the smallest size goes on one line,
 * cut with „…“ — never split in the middle. Always two lines high, so the
 * tiles in a row line up.
 */
@Composable
internal fun TileLabel(text: String, color: Color, modifier: Modifier = Modifier) {
    val base = MaterialTheme.typography.labelSmall
    val style = base.copy(
        color = color,
        textAlign = TextAlign.Center,
        hyphens = Hyphens.None,
        lineBreak = LineBreak.Paragraph,
        lineHeight = base.fontSize * 1.15f,
    )
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxPx = constraints.maxWidth
        val fit = remember(text, style, maxPx, density) { fitLabel(text, style, maxPx, measurer) }
        // Two lines high either way, so the tiles in a row line up.
        val twoLines = with(density) { (style.lineHeight * 2).toDp() }
        BasicText(
            text,
            style = style.copy(fontSize = fit.fontSize, letterSpacing = fit.letterSpacing),
            maxLines = if (fit.singleLine) 1 else 2,
            minLines = if (fit.singleLine) 1 else 2,
            softWrap = !fit.singleLine,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().then(if (fit.singleLine) Modifier.height(twoLines) else Modifier),
        )
    }
}

private fun fitLabel(
    text: String,
    style: TextStyle,
    maxPx: Int,
    measurer: TextMeasurer,
): TileLabelLayout {
    val tracking = style.letterSpacing
    if (maxPx == Constraints.Infinity || maxPx <= 0 || text.isBlank()) return TileLabelLayout(style.fontSize, tracking, singleLine = false)
    fun measure(size: Float, spacing: TextUnit) = measurer.measure(
        text,
        style = style.copy(fontSize = size.sp, letterSpacing = spacing),
        overflow = TextOverflow.Clip,
        maxLines = 2,
        constraints = Constraints(maxWidth = maxPx),
    )
    val small = TILE_LABEL_SMALL_SIZE.value
    val min = TILE_LABEL_MIN_SIZE.value
    val tries = TileLabelFit.sizes(style.fontSize.value, small).map { it to tracking } +
        TileLabelFit.sizes(small, min).map { it to 0.sp }
    for ((size, spacing) in tries) {
        val r = measure(size, spacing)
        val ends = (0 until r.lineCount - 1).map { r.getLineEnd(it) }
        if (!r.didOverflowHeight && !TileLabelFit.splitsWord(text, ends)) return TileLabelLayout(size.sp, spacing, singleLine = false)
    }
    // Too long even at the smallest size: two lines cut with „…“ while every
    // word fits a line by itself, else one line — never a word split in two.
    val r = measure(min, 0.sp)
    val firstSplits = r.lineCount > 1 && TileLabelFit.breaksInsideWord(text, r.getLineEnd(0))
    return TileLabelLayout(min.sp, 0.sp, singleLine = firstSplits)
}
