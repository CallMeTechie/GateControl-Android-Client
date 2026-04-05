package com.gatecontrol.android.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Holds GateControl-specific colors that have no direct Material3 slot mapping,
 * such as the zeroth background layer, hover states, tertiary text, warning
 * color, accent dim, blue accent, and the two border shades.
 *
 * Retrieve the current instance inside a Composable via [LocalGateControlColors].
 */
data class GateControlExtraColors(
    val bg0: Color,
    val bgHover: Color,
    val text3: Color,
    val warn: Color,
    val accentDim: Color,
    val blue: Color,
    val border: Color,
    val border2: Color,
)

/** Dark-theme defaults used as the static fallback. */
private val darkDefaults = GateControlExtraColors(
    bg0       = DarkBg0,
    bgHover   = DarkBgHover,
    text3     = DarkText3,
    warn      = DarkWarn,
    accentDim = DarkAccentDim,
    blue      = DarkBlue,
    border    = DarkBorder,
    border2   = DarkBorder2,
)

/**
 * CompositionLocal that provides [GateControlExtraColors] to the composition
 * tree. Always provided inside [GateControlTheme]; do not read this outside a
 * GateControl-themed context.
 */
val LocalGateControlColors = staticCompositionLocalOf { darkDefaults }
