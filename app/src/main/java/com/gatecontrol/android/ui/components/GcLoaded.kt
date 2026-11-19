package com.gatecontrol.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState

/**
 * A value loaded for [key1], [key2] and [key3] (a picture for its media id and size): [peek] at once (a cache), else [load]
 * in the background; null meanwhile.
 *
 * Keyed as a whole: `produceState(initial, keys) { if (value == null) … }` keeps its state across new keys — only the
 * producer restarts, and it saw the old value and loaded nothing. The status viewer then showed the first picture for
 * every status of a contact. Here a new key starts with a new state.
 */
@Composable
fun <T : Any> rememberLoaded(
    key1: Any?,
    key2: Any? = null,
    key3: Any? = null,
    peek: () -> T?,
    load: suspend () -> T?,
): T? = key(key1, key2, key3) {
    val value by produceState(peek(), key1, key2, key3) {
        if (value == null) value = load()
    }
    value
}
