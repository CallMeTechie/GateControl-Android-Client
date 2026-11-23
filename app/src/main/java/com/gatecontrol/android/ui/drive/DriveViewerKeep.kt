package com.gatecontrol.android.ui.drive

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.currentCompositeKeyHash
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

/*
 * What a viewer of Drive keeps when the activity is made anew (a fold, an unfold, a turn of the phone): which file is
 * open ([rememberKept]: a value that cannot go into a Bundle, e.g. a DNode) and the player of a video
 * ([DriveKeptPlayer], [ReleaseWhenGone]) — the film plays on where it was, its player is released once.
 */

/** One value of a screen, held by the screen's ViewModelStore (its page of the navigation): over a new activity. */
internal class KeptValue<T>(initial: T) : ViewModel() {
    val state: MutableState<T> = mutableStateOf(initial)
}

/**
 * As `remember { mutableStateOf(init()) }`, but kept while the activity is made anew — for values that do not fit
 * into `rememberSaveable` (a DNode, a list of them). Keyed by the place in the composition, as `rememberSaveable`;
 * gone with the page (not after the app was ended by the system).
 */
@Composable
internal fun <T> rememberKept(init: () -> T): MutableState<T> {
    val key = "gc_kept:" + currentCompositeKeyHash.toString(36)
    return viewModel(key = key) { KeptValue(init()) }.state
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The one player of a viewer, kept by a view model: [get] gives the one made for [key] (the video shown, its try) or
 * makes it — another key releases the last one first. [release] gives up exactly [value] (nothing if it went already,
 * so twice is once), [clear] whatever is kept (the view model goes). Main thread only.
 */
internal class DriveKeptPlayer<T : Any>(private val free: (T) -> Unit) {
    private var key: Any? = null
    private var value: T? = null

    /** The player kept for [key], null when there is none (nothing is made). */
    fun peek(key: Any): T? = value?.takeIf { this.key == key }

    fun get(key: Any, make: () -> T): T {
        peek(key)?.let { return it }
        clear()
        val made = make()
        this.key = key
        value = made
        return made
    }

    /** Whether [value] is the player kept now. */
    fun holds(value: T): Boolean = this.value === value

    fun release(value: T) {
        if (this.value === value) clear()
    }

    fun clear() {
        val v = value ?: return
        value = null
        key = null
        free(v)
    }
}

/**
 * [player] is shown: when it leaves the screen (another page, the viewer closed, the page of the navigation left)
 * [onGone] and then it is released in [kept] — not while the activity is made anew (a fold, an unfold, a turn): the
 * viewer comes back with the same player, it plays on.
 */
@Composable
internal fun <T : Any> ReleaseWhenGone(kept: DriveKeptPlayer<T>, player: T?, onGone: (T) -> Unit = {}) {
    val activity = LocalContext.current.findActivity()
    val gone by rememberUpdatedState(onGone)
    DisposableEffect(player, kept) {
        onDispose {
            val p = player ?: return@onDispose
            if (activity?.isChangingConfigurations != true && kept.holds(p)) {
                gone(p)
                kept.release(p)
            }
        }
    }
}
