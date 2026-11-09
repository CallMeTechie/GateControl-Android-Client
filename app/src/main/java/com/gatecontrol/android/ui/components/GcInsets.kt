package com.gatecontrol.android.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/**
 * Room for the keyboard on a page of the app's NavHost. The app's Scaffold (AppNavigation) already pads the page
 * above the navigation bar without consuming that inset, and the keyboard's inset counts from the screen's bottom
 * edge (the navigation bar included): `imePadding()` there leaves a navigation bar's height of empty space above
 * the keyboard. This pads only by the part of the keyboard above the navigation bar (as the chat does).
 */
fun Modifier.keyboardPadding(): Modifier = composed { windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars)) }
