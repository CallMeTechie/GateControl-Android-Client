package com.gatecontrol.android.ui.update

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.gatecontrol.android.R
import com.gatecontrol.android.update.ReleaseNote
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily

// Colours of the mockup that have no theme slot: the dark progress bar and
// toast stay dark in both themes (a lifted panel in the dark theme).
internal val UpdInk = Color(0xFF0F1D27)
internal val UpdOnInkMuted = Color(0xFFB8C6D1)
internal val UpdInkAccent = Color(0xFF3DDC97)
internal val UpdGradientStart = Color(0xFF0B8A5C)
internal val UpdGradientEnd = Color(0xFF0E6F86)
internal val UpdGradientLabel = Color(0xFFCFF5E6)

@Composable
internal fun updInkSurface(): Color {
    val extra = GateControlTheme.extraColors
    return if (extra.isDark) extra.panel2 else UpdInk
}

@Composable
internal fun updInkBorder(): BorderStroke? {
    val extra = GateControlTheme.extraColors
    return if (extra.isDark) BorderStroke(1.dp, extra.border2) else null
}

/** Mono caps label ("WAS IST NEU"). */
internal val UpdMonoLabel = TextStyle(
    fontFamily = MonoFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 11.5.sp,
    letterSpacing = 0.12.em,
)

/** Sheet / card headline (21 px, extra bold). */
@Composable
internal fun updTitleStyle(size: Float = 21f) =
    MaterialTheme.typography.headlineSmall.copy(fontSize = size.sp, lineHeight = (size + 6).sp)

/** Accent, blue and amber tiles of the "Was ist neu" items, in this order. */
@Composable
internal fun noteTint(index: Int): Pair<Color, Color> {
    val extra = GateControlTheme.extraColors
    return when (index % 3) {
        0 -> extra.accentBg to extra.accentText
        1 -> extra.blueBg to extra.blue
        else -> extra.warnBg to extra.warn
    }
}

internal fun noteIcon(note: ReleaseNote): ImageVector = when (note.kind) {
    ReleaseNote.Kind.Feature -> Icons.Rounded.AutoAwesome
    ReleaseNote.Kind.Fix -> Icons.Rounded.Build
    ReleaseNote.Kind.Other -> Icons.Rounded.Bolt
}

/** Rounded square with a tinted icon. */
@Composable
internal fun UpdIconTile(
    icon: ImageVector,
    background: Color,
    tint: Color,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    corner: Dp = 14.dp,
) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Ring with [progress] 0..1 drawn over a track. */
@Composable
internal fun UpdRing(
    progress: Float,
    color: Color,
    track: Color,
    stroke: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val w = stroke.toPx()
            val inset = w / 2
            val arcSize = Size(size.width - w, size.height - w)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
            if (progress > 0f) {
                drawArc(color, -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Butt))
            }
        }
        content()
    }
}

enum class StepState { Done, Active, Pending }

/** The three steps: found ✓, download, restart. */
@Composable
internal fun UpdSteps(steps: List<Pair<String, StepState>>) {
    val extra = GateControlTheme.extraColors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(extra.panel2).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for ((label, state) in steps) UpdStep(label, state)
    }
}

@Composable
private fun UpdStep(label: String, state: StepState) {
    val extra = GateControlTheme.extraColors
    val primary = MaterialTheme.colorScheme.primary
    val stateText = stringResource(
        when (state) {
            StepState.Done -> R.string.upd_step_done
            StepState.Active -> R.string.upd_step_active
            StepState.Pending -> R.string.upd_step_pending
        },
    )
    Row(
        Modifier.clearAndSetSemantics { contentDescription = "$label, $stateText" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val dot = Modifier.size(22.dp).clip(CircleShape)
        when (state) {
            StepState.Done -> Box(dot.background(primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
            }
            StepState.Active -> Box(dot.border(2.5.dp, primary, CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(primary))
            }
            StepState.Pending -> Box(dot.border(2.dp, extra.border2, CircleShape))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (state == StepState.Active) FontWeight.Bold else FontWeight.Normal),
            color = if (state == StepState.Pending) extra.faint else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Big pill button of the sheets (54 dp). */
@Composable
internal fun UpdBigButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(27.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Box(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.ExtraBold))
    }
}

/** Card button (48 dp): filled or outlined, side by side with weight. */
@Composable
internal fun RowScope.UpdCardButton(
    text: String,
    onClick: () -> Unit,
    filled: Boolean,
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
) {
    val shape = RoundedCornerShape(24.dp)
    val style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
    val mod = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp)
    if (filled) {
        Button(
            onClick = onClick,
            modifier = mod,
            shape = shape,
            colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) { Text(text, style = style, maxLines = 1) }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = mod,
            shape = shape,
            border = BorderStroke(1.dp, GateControlTheme.extraColors.border2),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) { Text(text, style = style, maxLines = 1) }
    }
}

/** 40×4 handle on top of the sheets. */
@Composable
internal fun UpdSheetHandle() {
    Box(
        Modifier.padding(top = 10.dp, bottom = 8.dp).size(width = 40.dp, height = 4.dp)
            .clip(RoundedCornerShape(2.dp)).background(GateControlTheme.extraColors.border2),
    )
}
