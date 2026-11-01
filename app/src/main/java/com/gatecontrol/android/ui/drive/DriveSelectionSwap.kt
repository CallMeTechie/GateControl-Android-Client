package com.gatecontrol.android.ui.drive

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints

/**
 * The title row of a page that ticks items: [bar] normally, [selection] (✕, "N ausgewählt", …) while [selecting] — in
 * one row exactly as high as the higher of both, measured either way, so starting or ending a selection moves nothing
 * below (the finger of the long press stays on the photo it pressed).
 */
@Composable
internal fun SelectionSwap(selecting: Boolean, bar: @Composable () -> Unit, selection: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Layout(contents = listOf(bar, selection), modifier = modifier) { (bars, heads), constraints ->
        val width = constraints.maxWidth
        val height = maxOf(
            bars.maxOfOrNull { it.maxIntrinsicHeight(width) } ?: 0,
            heads.maxOfOrNull { it.maxIntrinsicHeight(width) } ?: 0,
        ).coerceIn(constraints.minHeight, constraints.maxHeight)
        // only the one shown is measured and placed (the other has no input and no semantics)
        val shown = (if (selecting) heads else bars).map { it.measure(Constraints(constraints.minWidth, width, height, height)) }
        layout(width, height) { shown.forEach { it.placeRelative(0, 0) } }
    }
}

/** While items are ticked: dimmed, and taps and TalkBack pass it by (it stays in place, nothing below moves). */
internal fun Modifier.inert(on: Boolean): Modifier = if (!on) this else this
    .alpha(INERT_ALPHA)
    .clearAndSetSemantics { }
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }

private const val INERT_ALPHA = 0.45f
