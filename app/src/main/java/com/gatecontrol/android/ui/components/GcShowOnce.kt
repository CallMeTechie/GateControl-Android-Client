package com.gatecontrol.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch

/**
 * Shows [value] once it is there ([show], e.g. a snackbar) and takes it at once ([consume]). [show] runs in the scope of
 * the composition, not in the effect keyed by [value]: taking the value changes the key, which restarts that effect — a
 * snackbar shown in it would be cancelled after a frame (PR #218 found it in Mail). It ends only when the caller leaves.
 */
@Composable
fun <T : Any> GcShowOnce(value: T?, consume: () -> Unit, show: suspend (T) -> Unit) {
    val scope = rememberCoroutineScope()
    val currentShow by rememberUpdatedState(show)
    val currentConsume by rememberUpdatedState(consume)
    LaunchedEffect(value) {
        val v = value ?: return@LaunchedEffect
        currentConsume()
        scope.launch { currentShow(v) }
    }
}
