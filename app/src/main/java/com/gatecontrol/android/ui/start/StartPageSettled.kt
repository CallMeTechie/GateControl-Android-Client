package com.gatecontrol.android.ui.start

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs

/**
 * True while the screen is the page Start's pager rests on, and for every
 * screen outside the pager (a tab, a route of its own). The pager composes
 * a page before it is shown (the next one while swiping towards it, the one
 * swiped in while the finger is still down): false there until the pager has
 * come to rest on it. What must not happen on a page nobody looks at (an
 * unlock prompt, "read") waits for it, see [PageArrivalEffect].
 */
val LocalStartPageSettled = compositionLocalOf { true }

/** Whether the page [index] is the one a pager rests on ([settledPage]: its page once no scroll is running). */
internal fun startPageSettled(index: Int, settledPage: Int): Boolean = index == settledPage

/**
 * True while a part of the screen shows: always outside Start's pager, in it
 * from the first pixels swiped in until the page is gone — not for a page the
 * pager only keeps composed beside the shown one. What must hold for as long
 * as the screen can be seen (no screenshots of a vault) follows this, not
 * [LocalStartPageSettled]: a page dragged in halfway shows what is on it
 * before the pager rests on it.
 */
val LocalStartPageVisible = compositionLocalOf { true }

/**
 * Whether a part of the page [index] shows: the pager is at [currentPage] moved by [offsetFraction] of a page
 * (-0.5..0.5) towards the next one, and a page shows while it is less than one page away from there.
 */
internal fun startPageVisible(index: Int, currentPage: Int, offsetFraction: Float): Boolean =
    abs(currentPage + offsetFraction - index) < 1f

/** What [PageArrivalEffect] does next: [run] the action now, [done] kept for the next change. */
internal data class PageArrival(val done: Boolean, val run: Boolean) {
    companion object {
        /**
         * The action runs once per arrival: the page is [settled], the app [resumed] in front and the action
         * [enabled]. Leaving the page (not [settled]) arms it again; going to the background and back does not
         * (a cancelled prompt would come back by itself).
         */
        fun next(done: Boolean, settled: Boolean, resumed: Boolean, enabled: Boolean): PageArrival = when {
            !settled -> PageArrival(done = false, run = false)
            !done && resumed && enabled -> PageArrival(done = true, run = true)
            else -> PageArrival(done = done, run = false)
        }
    }
}

/**
 * Runs [onArrive] once when the screen is really in front of the user: its
 * page is the one the pager rests on ([LocalStartPageSettled]) and the app
 * is resumed. Not for a page composed ahead beside the shown one, not while
 * swiping, not in the background. Again only after the page was left and
 * come back to, or the screen was composed anew.
 */
@Composable
fun PageArrivalEffect(enabled: Boolean = true, onArrive: () -> Unit) {
    val settled = LocalStartPageSettled.current
    val state by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val resumed = state.isAtLeast(Lifecycle.State.RESUMED)
    val arrive by rememberUpdatedState(onArrive)
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(settled, resumed, enabled) {
        val next = PageArrival.next(done, settled, resumed, enabled)
        done = next.done
        if (next.run) arrive()
    }
}
