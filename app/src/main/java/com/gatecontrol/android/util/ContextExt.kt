package com.gatecontrol.android.util

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity

/**
 * Walks the Context wrapper chain to the hosting ComponentActivity. Used to scope a ViewModel to
 * the Activity (shared across nav destinations) instead of the per-destination NavBackStackEntry.
 * (activity-compose 1.9.3 has no LocalActivity yet.)
 */
fun Context.findComponentActivity(): ComponentActivity {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is ComponentActivity) return ctx
        ctx = ctx.baseContext
    }
    error("No ComponentActivity found in the Context chain")
}
