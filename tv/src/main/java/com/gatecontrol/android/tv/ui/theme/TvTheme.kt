package com.gatecontrol.android.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import com.gatecontrol.android.ui.theme.DarkAccent
import com.gatecontrol.android.ui.theme.DarkAccentBg
import com.gatecontrol.android.ui.theme.DarkAccentText
import com.gatecontrol.android.ui.theme.DarkBg
import com.gatecontrol.android.ui.theme.DarkBlue
import com.gatecontrol.android.ui.theme.DarkBlueBg
import com.gatecontrol.android.ui.theme.DarkError
import com.gatecontrol.android.ui.theme.DarkErrorBg
import com.gatecontrol.android.ui.theme.DarkFaint
import com.gatecontrol.android.ui.theme.DarkLine
import com.gatecontrol.android.ui.theme.DarkLine2
import com.gatecontrol.android.ui.theme.DarkMuted
import com.gatecontrol.android.ui.theme.DarkOnAccent
import com.gatecontrol.android.ui.theme.DarkPanel
import com.gatecontrol.android.ui.theme.DarkPanel2
import com.gatecontrol.android.ui.theme.DarkPro
import com.gatecontrol.android.ui.theme.DarkScrim
import com.gatecontrol.android.ui.theme.DarkText
import com.gatecontrol.android.ui.theme.DarkWarn
import com.gatecontrol.android.ui.theme.DarkWarnBg
import com.gatecontrol.android.ui.theme.DisplayFontFamily
import com.gatecontrol.android.ui.theme.UiFontFamily

// GateControl TV uses the phone app's dark tokens (:core:designsystem) and adds the focus of the mockup
// "GateControl TV": a light ring (--focus) with a green glow (--focus-glow) and a slight zoom.

/** Colors without a Material slot, as in the phone app's GateControlExtraColors, plus the TV focus. */
@Immutable
data class TvColors(
    val bg: Color = DarkBg,
    val panel: Color = DarkPanel,
    val panel2: Color = DarkPanel2,
    val line: Color = DarkLine,
    val line2: Color = DarkLine2,
    val text: Color = DarkText,
    val muted: Color = DarkMuted,
    val faint: Color = DarkFaint,
    val accent: Color = DarkAccent,
    val onAccent: Color = DarkOnAccent,
    val accentText: Color = DarkAccentText,
    val accentBg: Color = DarkAccentBg,
    val warn: Color = DarkWarn,
    val warnBg: Color = DarkWarnBg,
    val error: Color = DarkError,
    val errorBg: Color = DarkErrorBg,
    val info: Color = DarkBlue,
    val infoBg: Color = DarkBlueBg,
    /** Ring around the focused element. */
    val focus: Color = DarkText,
    /** Glow around the focused element: the accent at 45 %. */
    val focusGlow: Color = DarkAccent.copy(alpha = 0.45f),
    /** The rail's background, nearly opaque page color. */
    val rail: Color = DarkBg.copy(alpha = 0.95f),
)

val LocalTvColors = staticCompositionLocalOf { TvColors() }

/** Zoom of a focused card or tile (mockup: scale(1.06)). */
const val TV_FOCUS_SCALE = 1.06f

private val TvColorScheme = darkColorScheme(
    primary = DarkAccent,
    onPrimary = DarkOnAccent,
    primaryContainer = DarkAccentBg,
    onPrimaryContainer = DarkAccentText,
    secondary = DarkBlue,
    onSecondary = DarkBg,
    tertiary = DarkPro,
    onTertiary = DarkBg,
    background = DarkBg,
    onBackground = DarkText,
    surface = DarkPanel,
    onSurface = DarkText,
    surfaceVariant = DarkPanel2,
    onSurfaceVariant = DarkMuted,
    inverseSurface = DarkText,
    inverseOnSurface = DarkBg,
    border = DarkLine2,
    borderVariant = DarkLine,
    error = DarkError,
    onError = DarkBg,
    errorContainer = DarkErrorBg,
    onErrorContainer = DarkError,
    scrim = DarkScrim,
)

private val displayTracking = (-0.01).em

/** The phone app's faces at TV size (seen from the sofa: 10-foot UI). */
private val TvTypography = Typography(
    displaySmall = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 42.sp, letterSpacing = displayTracking),
    headlineMedium = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = displayTracking),
    headlineSmall = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = displayTracking),
    titleMedium = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 22.sp, letterSpacing = displayTracking),
    titleSmall = TextStyle(fontFamily = UiFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = UiFontFamily, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = UiFontFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = UiFontFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = UiFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = UiFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp),
)

/** Root theme of GateControl TV: dark only (TV rooms), like the mockup. */
@Composable
fun GateControlTvTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTvColors provides TvColors()) {
        MaterialTheme(colorScheme = TvColorScheme, typography = TvTypography, content = content)
    }
}

object GateControlTvTheme {
    val colors: TvColors
        @Composable
        @ReadOnlyComposable
        get() = LocalTvColors.current
}
