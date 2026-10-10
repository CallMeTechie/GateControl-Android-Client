package com.gatecontrol.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// Material3 color schemes
// ---------------------------------------------------------------------------

private val DarkColorScheme = darkColorScheme(
    primary              = DarkAccent,
    onPrimary            = DarkOnAccent,
    primaryContainer     = DarkAccentBg,
    onPrimaryContainer   = DarkAccentText,
    secondary            = DarkBlue,
    onSecondary          = DarkBg,
    secondaryContainer   = DarkAccentBg,
    onSecondaryContainer = DarkAccentText,
    tertiary             = DarkPro,
    onTertiary           = DarkBg,
    background           = DarkBg,
    onBackground         = DarkText,
    surface              = DarkPanel,
    onSurface            = DarkText,
    surfaceVariant       = DarkPanel2,
    onSurfaceVariant     = DarkMuted,
    surfaceContainerLowest = DarkBg,
    surfaceContainerLow  = DarkPanel,
    surfaceContainer     = DarkPanel,
    surfaceContainerHigh = DarkPanel,
    surfaceContainerHighest = DarkPanel2,
    inverseSurface       = DarkText,
    inverseOnSurface     = DarkBg,
    inversePrimary       = LightAccent,
    outline              = DarkLine2,
    outlineVariant       = DarkLine,
    error                = DarkError,
    onError              = DarkBg,
    errorContainer       = DarkErrorBg,
    onErrorContainer     = DarkError,
    scrim                = DarkScrim,
)

private val LightColorScheme = lightColorScheme(
    primary              = LightAccent,
    onPrimary            = LightOnAccent,
    primaryContainer     = LightAccentBg,
    onPrimaryContainer   = LightAccentText,
    secondary            = LightBlue,
    onSecondary          = LightPanel,
    secondaryContainer   = LightAccentBg,
    onSecondaryContainer = LightAccentText,
    tertiary             = LightPro,
    onTertiary           = LightPanel,
    background           = LightBg,
    onBackground         = LightText,
    surface              = LightPanel,
    onSurface            = LightText,
    surfaceVariant       = LightPanel2,
    onSurfaceVariant     = LightMuted,
    surfaceContainerLowest = LightPanel,
    surfaceContainerLow  = LightPanel,
    surfaceContainer     = LightPanel,
    surfaceContainerHigh = LightPanel,
    surfaceContainerHighest = LightPanel2,
    inverseSurface       = LightText,
    inverseOnSurface     = LightBg,
    inversePrimary       = DarkAccent,
    outline              = LightLine2,
    outlineVariant       = LightLine,
    error                = LightError,
    onError              = LightPanel,
    errorContainer       = LightErrorBg,
    onErrorContainer     = LightError,
    scrim                = LightScrim,
)

/** Radii from the mockup: chips 8, inputs 14, cards 20, sheets 28. */
val GateControlShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small      = RoundedCornerShape(14.dp),
    medium     = RoundedCornerShape(20.dp),
    large      = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// ---------------------------------------------------------------------------
// Theme composable
// ---------------------------------------------------------------------------

/**
 * Root theme composable for the GateControl Android app.
 *
 * Applies the GateControl Material3 color scheme, typography and shapes, and
 * provides [GateControlExtraColors] through [LocalGateControlColors] for the
 * colors without a Material3 slot.
 *
 * To access extra colors inside a composable:
 * ```kotlin
 * val extra = GateControlTheme.extraColors
 * Box(modifier = Modifier.background(extra.panel2))
 * ```
 */
@Composable
fun GateControlTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extraColors = if (darkTheme) DarkExtraColors else LightExtraColors

    CompositionLocalProvider(LocalGateControlColors provides extraColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = GateControlTypography,
            shapes      = GateControlShapes,
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
 */
object GateControlTheme {
    val extraColors: GateControlExtraColors
        @Composable
        @ReadOnlyComposable
        get() = LocalGateControlColors.current
}
