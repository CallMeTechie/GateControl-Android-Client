package com.gatecontrol.android.ui.vpn

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.theme.GateControlTheme

/** Accent color for a tunnel state: on = accent, busy = warn, error = err, off = faint. */
@Composable
fun tunnelStateColor(state: TunnelState): Color {
    val extra = GateControlTheme.extraColors
    return when (state) {
        is TunnelState.Connected -> MaterialTheme.colorScheme.primary
        is TunnelState.Connecting, is TunnelState.Reconnecting, TunnelState.Disconnecting -> extra.warn
        is TunnelState.Error -> MaterialTheme.colorScheme.error
        TunnelState.Disconnected -> extra.faint
    }
}

/**
 * The big round connect button of the start screen: a 196 dp ring (full when
 * connected or failed, a spinning arc while connecting, empty when off)
 * around a panel disc with the power glyph and a short hint.
 */
@Composable
fun ConnectionOrb(
    state: TunnelState,
    hint: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    orbSize: Dp = 196.dp,
) {
    val extra = GateControlTheme.extraColors
    val busy = state is TunnelState.Connecting || state is TunnelState.Reconnecting || state is TunnelState.Disconnecting
    val full = state is TunnelState.Connected || state is TunnelState.Error
    val color by animateColorAsState(tunnelStateColor(state), tween(400), label = "orb_color")
    val halo by animateColorAsState(
        when (state) {
            is TunnelState.Connected -> extra.accentBg
            is TunnelState.Error -> extra.errorBg
            else -> Color.Transparent
        },
        tween(400),
        label = "orb_halo",
    )
    val sweep by animateFloatAsState(
        targetValue = if (full) 360f else if (busy) 90f else 0f,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "orb_sweep",
    )

    val transition = rememberInfiniteTransition(label = "orb")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing), RepeatMode.Restart),
        label = "orb_spin",
    )
    val breathe by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "orb_breathe",
    )
    val track = extra.border

    Box(
        modifier = modifier
            .size(orbSize)
            .scale(if (busy) breathe else 1f)
            .clip(CircleShape)
            .background(halo)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 6.dp.toPx()
            val inset = size.width * 8f / 196f
            val arcSize = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            if (sweep > 0f) {
                rotate(if (busy) spin else 0f) {
                    drawArc(color, -90f, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
        }
        Column(
            modifier = Modifier
                .size(orbSize * (132f / 196f))
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, extra.border, CircleShape),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            Icon(GcIcons.Power, contentDescription = null, tint = color, modifier = Modifier.size(40.dp))
            Text(hint, style = MaterialTheme.typography.titleSmall.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize), color = extra.muted)
        }
    }
}
