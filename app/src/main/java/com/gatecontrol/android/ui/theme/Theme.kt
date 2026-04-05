package com.gatecontrol.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

// ---------------------------------------------------------------------------
// Material3 color schemes
// ---------------------------------------------------------------------------

private val DarkColorScheme = darkColorScheme(
    primary            = DarkAccent,
    onPrimary          = DarkBg0,
    primaryContainer   = DarkAccentDim,
    secondary          = DarkBlue,
    onSecondary        = DarkBg0,
    background         = DarkBg1,
    onBackground       = DarkText1,
    surface            = DarkBg2,
    onSurface          = DarkText1,
    surfaceVariant     = DarkBg3,
    onSurfaceVariant   = DarkText2,
    outline            = DarkBorder,
    outlineVariant     = DarkBorder2,
    error              = DarkError,
    onError            = DarkBg0,
)

private val LightColorScheme = lightColorScheme(
    primary            = LightAccent,
    onPrimary          = LightBg0,
    primaryContainer   = LightAccentDim,
    secondary          = LightBlue,
    onSecondary        = LightBg0,
    background         = LightBg1,
    onBackground       = LightText1,
    surface            = LightBg2,
    onSurface          = LightText1,
    surfaceVariant     = LightBg3,
    onSurfaceVariant   = LightText2,
    outline            = LightBorder,
    outlineVariant     = LightBorder2,
    error              = LightError,
    onError            = LightBg0,
)

// ---------------------------------------------------------------------------
// Extra-color instances per theme
// ---------------------------------------------------------------------------

private val DarkExtraColors = GateControlExtraColors(
    bg0       = DarkBg0,
    bgHover   = DarkBgHover,
    text3     = DarkText3,
    warn      = DarkWarn,
    accentDim = DarkAccentDim,
    blue      = DarkBlue,
    border    = DarkBorder,
    border2   = DarkBorder2,
)

private val LightExtraColors = GateControlExtraColors(
    bg0       = LightBg0,
    bgHover   = LightBgHover,
    text3     = LightText3,
    warn      = LightWarn,
    accentDim = LightAccentDim,
    blue      = LightBlue,
    border    = LightBorder,
    border2   = LightBorder2,
)

// ---------------------------------------------------------------------------
// Theme composable
// ---------------------------------------------------------------------------

/**
 * Root theme composable for the GateControl Android app.
 *
 * Applies the GateControl Material3 color scheme and typography, and provides
 * [GateControlExtraColors] through [LocalGateControlColors] so that non-slot
 * colors (bg0, bgHover, text3, warn, accentDim, blue, border, border2) are
 * accessible anywhere inside the composition tree.
 *
 * Usage:
 * ```kotlin
 * GateControlTheme {
 *     // your screen content
 * }
 * ```
 *
 * To access extra colors inside a composable:
 * ```kotlin
 * val extra = LocalGateControlColors.current
 * Box(modifier = Modifier.background(extra.bg0))
 * ```
 */
@Composable
fun GateControlTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extraColors  = if (darkTheme) DarkExtraColors  else LightExtraColors

    CompositionLocalProvider(LocalGateControlColors provides extraColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = GateControlTypography,
            content     = content,
        )
    }
}

// ---------------------------------------------------------------------------
// Convenience accessor
// ---------------------------------------------------------------------------

/**
 * Shorthand for [LocalGateControlColors.current], readable from any composable
 * inside [GateControlTheme].
 *
 * Example:
 * ```kotlin
 * Text(color = GateControlTheme.extraColors.warn, text = "Warning")
 * ```
 */
object GateControlTheme {
    val extraColors: GateControlExtraColors
        @Composable
        @ReadOnlyComposable
        get() = LocalGateControlColors.current
}
