package com.gatecontrol.android.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * GateControl colors that have no direct Material3 slot: the second panel
 * tone, muted/faint text, the tinted status backgrounds and the Pro accent.
 *
 * The older names (bg0, bgHover, text3, accentDim, blue, border, border2) are
 * kept as aliases so existing screens pick up the new palette unchanged.
 *
 * Retrieve the current instance inside a Composable via [LocalGateControlColors].
 */
data class GateControlExtraColors(
    val panel2: Color,
    val muted: Color,
    val faint: Color,
    val accentText: Color,
    val accentBg: Color,
    val warn: Color,
    val warnBg: Color,
    val errorBg: Color,
    val blue: Color,
    val blueBg: Color,
    val pro: Color,
    val proBg: Color,
    val border: Color,
    val border2: Color,
    val scrim: Color,
    val isDark: Boolean,
) {
    val bg0: Color get() = panel2
    val bgHover: Color get() = panel2
    val text3: Color get() = faint
    val accentDim: Color get() = accentText
}

internal val DarkExtraColors = GateControlExtraColors(
    panel2     = DarkPanel2,
    muted      = DarkMuted,
    faint      = DarkFaint,
    accentText = DarkAccentText,
    accentBg   = DarkAccentBg,
    warn       = DarkWarn,
    warnBg     = DarkWarnBg,
    errorBg    = DarkErrorBg,
    blue       = DarkBlue,
    blueBg     = DarkBlueBg,
    pro        = DarkPro,
    proBg      = DarkProBg,
    border     = DarkLine,
    border2    = DarkLine2,
    scrim      = DarkScrim,
    isDark     = true,
)

internal val LightExtraColors = GateControlExtraColors(
    panel2     = LightPanel2,
    muted      = LightMuted,
    faint      = LightFaint,
    accentText = LightAccentText,
    accentBg   = LightAccentBg,
    warn       = LightWarn,
    warnBg     = LightWarnBg,
    errorBg    = LightErrorBg,
    blue       = LightBlue,
    blueBg     = LightBlueBg,
    pro        = LightPro,
    proBg      = LightProBg,
    border     = LightLine,
    border2    = LightLine2,
    scrim      = LightScrim,
    isDark     = false,
)

/**
 * CompositionLocal that provides [GateControlExtraColors] to the composition
 * tree. Always provided inside [GateControlTheme]; do not read this outside a
 * GateControl-themed context.
 */
val LocalGateControlColors = staticCompositionLocalOf { DarkExtraColors }
