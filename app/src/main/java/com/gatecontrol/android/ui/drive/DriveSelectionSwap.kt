package com.gatecontrol.android.ui.drive

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gatecontrol.android.ui.components.GcSelectionSwap
import com.gatecontrol.android.ui.components.gcInert

/**
 * The title row of a page that ticks items: [bar] normally, [selection] (✕, "N ausgewählt", …) while [selecting] — in
 * one row exactly as high as the higher of both, so starting or ending a selection moves nothing below
 * ([GcSelectionSwap], shared with Mail).
 */
@Composable
internal fun SelectionSwap(selecting: Boolean, bar: @Composable () -> Unit, selection: @Composable () -> Unit, modifier: Modifier = Modifier) =
    GcSelectionSwap(selecting, bar, selection, modifier)

/** While items are ticked: dimmed, and taps and TalkBack pass it by (it stays in place, nothing below moves). */
internal fun Modifier.inert(on: Boolean): Modifier = gcInert(on)
