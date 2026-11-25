package com.gatecontrol.android.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * The icons of the status bar (clock, battery, Wi-Fi) and of the navigation bar follow the app's design, not the
 * system's: dark icons on the light design, light ones on the dark design. The window is drawn edge to edge (target
 * SDK 35+), so the bars are see-through over the page; with the window theme's light icons they were invisible on the
 * light page. Updated when the design is switched in the app. Not for the player and the terminal (their own bars).
 */
@Composable
fun GcSystemBars(darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(view, darkTheme) {
        view.context.findWindow()?.let { applySystemBars(it, view, darkTheme) }
        onDispose { }
    }
}

/** Dark icons on the light design ([darkTheme] false), light icons on the dark design. */
fun applySystemBars(window: Window, view: View, darkTheme: Boolean) {
    WindowCompat.getInsetsController(window, view).apply {
        isAppearanceLightStatusBars = !darkTheme
        isAppearanceLightNavigationBars = !darkTheme
    }
}

private tailrec fun Context.findWindow(): Window? = when (this) {
    is Activity -> window
    is ContextWrapper -> baseContext.findWindow()
    else -> null
}
