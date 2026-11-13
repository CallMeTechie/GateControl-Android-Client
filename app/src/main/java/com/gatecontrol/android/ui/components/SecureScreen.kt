package com.gatecontrol.android.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.gatecontrol.android.ui.start.LocalStartPageVisible
import java.util.WeakHashMap

/**
 * Who wants a window [W] kept out of screenshots: [set] is called with true
 * for the first one of a window and with false after its last one has gone,
 * so two screens (the one coming in and the one going out of a navigation, a
 * page and a viewer over it) never take it from each other. A window that
 * was secure before by itself ([wasSecure]) stays so. Main thread only.
 */
internal class SecureHolds<W : Any>(
    private val wasSecure: (W) -> Boolean,
    private val set: (W, Boolean) -> Unit,
) {
    private class Hold(val foreign: Boolean, var count: Int = 0)

    private val holds = WeakHashMap<W, Hold>()

    fun count(window: W): Int = holds[window]?.count ?: 0

    fun acquire(window: W) {
        val hold = holds.getOrPut(window) { Hold(foreign = wasSecure(window)) }
        hold.count++
        if (hold.count == 1 && !hold.foreign) set(window, true)
    }

    fun release(window: W) {
        val hold = holds[window] ?: return
        hold.count--
        if (hold.count > 0) return
        holds.remove(window)
        if (!hold.foreign) set(window, false)
    }
}

/** FLAG_SECURE per window of the app (the activity's, a dialog's own). */
internal val SecureWindows = SecureHolds<Window>(
    wasSecure = { it.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 },
    set = { window, secure ->
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        // The activity's own window: no picture of it in the recent apps either.
        val activity = window.context as? Activity
        if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) activity.setRecentsScreenshotEnabled(!secure)
    },
)

/**
 * Whether a screen that asks for it ([wanted]) holds the flag now: as a page
 * beside Start only while a part of it shows ([pageVisible]), not while the
 * pager merely keeps it composed beside the shown one.
 */
internal fun secureScreenActive(wanted: Boolean, pageVisible: Boolean): Boolean = wanted && pageVisible

private fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** The window a composable is shown in: a dialog's own inside a dialog, the activity's otherwise. */
private fun windowOf(view: View, context: Context): Window? =
    (view.parent as? DialogWindowProvider)?.window ?: context.activity()?.window

/**
 * While composed and [active]: no screenshot, no screen recording, no
 * picture in the recent apps of the window this is in (FLAG_SECURE). Counted
 * per window ([SecureHolds]): the flag stays as long as one screen asks for
 * it. On a page beside Start it holds from the first part of the page that
 * shows until the page is gone ([LocalStartPageVisible]). Dialogs, sheets
 * and menus opened from a secure window are secure by themselves (they
 * inherit it); a dialog over a screen that is not secure calls this inside
 * its content.
 */
@Composable
fun SecureScreenEffect(active: Boolean = true) {
    if (LocalInspectionMode.current) return
    val view = LocalView.current
    val context = LocalContext.current
    val on = secureScreenActive(active, LocalStartPageVisible.current)
    DisposableEffect(on, view, context) {
        val window = if (on) windowOf(view, context) else null
        window?.let(SecureWindows::acquire)
        onDispose { window?.let(SecureWindows::release) }
    }
}
