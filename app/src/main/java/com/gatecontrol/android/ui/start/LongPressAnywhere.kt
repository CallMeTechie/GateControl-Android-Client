package com.gatecontrol.android.ui.start

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics

/**
 * A long press anywhere on the element — also on its buttons, switches and
 * rows — calls [onLongPress] (a widget's or a Familie card's settings). It
 * watches the touch before the children do and, once it fired, swallows
 * the rest of that touch, so the button under the finger does not run as
 * well. Moving the finger (scrolling, a slider) or a second finger cancels
 * it; a plain tap goes to the children as usual, and a long press handler
 * of an enclosing element does not fire as well. Screen readers get it as
 * the element's long-click action named [label].
 */
fun Modifier.longPressAnywhere(label: String?, onLongPress: (() -> Unit)?): Modifier {
    if (onLongPress == null) return this
    return this.composed {
        // The latest callback without restarting the gesture detection on every recomposition.
        val current by rememberUpdatedState(onLongPress)
        Modifier.pointerInput(Unit) {
            val timeout = viewConfiguration.longPressTimeoutMillis
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // The same touch after the children saw it: it is this element's, so a long press
                // handler around it (the start tab's free space) does not start one of its own.
                awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull { it.id == down.id }?.let { if (!it.isConsumed) it.consume() }
                val ended = withTimeoutOrNull(timeout) {
                    var stop = false
                    while (!stop) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        stop = event.changes.size > 1 || change == null || !change.pressed || change.isConsumed ||
                            (change.position - down.position).getDistance() > slop
                    }
                    true
                }
                if (ended == null) {
                    current()
                    // The rest of this touch is the long press's: the child under the finger sees it cancelled.
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
        }.semantics { onLongClick(label = label) { current(); true } }
    }
}
