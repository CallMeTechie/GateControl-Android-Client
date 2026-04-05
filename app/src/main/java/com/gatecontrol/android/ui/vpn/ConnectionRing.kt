package com.gatecontrol.android.ui.vpn

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.ui.theme.DarkAccent
import com.gatecontrol.android.ui.theme.DarkError
import com.gatecontrol.android.ui.theme.DarkWarn

private val ColorConnected = DarkAccent
private val ColorConnecting = DarkWarn
private val ColorDisconnected = Color(0xFF4B5563)
private val ColorError = DarkError

@Composable
fun ConnectionRing(
    state: TunnelState,
    modifier: Modifier = Modifier,
    ringSize: Dp = 160.dp,
) {
    val isAnimating = state is TunnelState.Connecting
        || state is TunnelState.Disconnecting
        || state is TunnelState.Reconnecting

    val ringColor = when (state) {
        is TunnelState.Connected -> ColorConnected
        is TunnelState.Connecting, is TunnelState.Reconnecting -> ColorConnecting
        is TunnelState.Disconnecting -> ColorDisconnected
        is TunnelState.Error -> ColorError
        TunnelState.Disconnected -> ColorDisconnected
    }

    val fillAlpha by animateFloatAsState(
        targetValue = if (state is TunnelState.Connected) 0.15f else 0.05f,
        animationSpec = tween(600),
        label = "fill_alpha",
    )

    val infiniteTransition = rememberInfiniteTransition(label = "ring_spin")
    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spin_angle",
    )

    Box(
        modifier = modifier.size(ringSize),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(ringSize)) {
            val stroke = 6.dp.toPx()
            val inset = stroke / 2f

            // Filled circle (semi-transparent background)
            drawCircle(
                color = ringColor.copy(alpha = fillAlpha),
                radius = size.minDimension / 2f - inset,
            )

            if (isAnimating) {
                // Spinning arc segment
                rotate(spinAngle) {
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = 240f,
                        useCenter = false,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            } else {
                // Full ring
                drawCircle(
                    color = ringColor,
                    radius = size.minDimension / 2f - inset,
                    style = Stroke(width = stroke),
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when (state) {
                is TunnelState.Connected -> {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = ColorConnected,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.vpn_connected),
                        style = MaterialTheme.typography.labelLarge,
                        color = ColorConnected,
                    )
                }
                is TunnelState.Connecting -> {
                    Text(
                        text = stringResource(R.string.vpn_connecting),
                        style = MaterialTheme.typography.labelLarge,
                        color = ColorConnecting,
                    )
                }
                is TunnelState.Reconnecting -> {
                    Text(
                        text = stringResource(
                            R.string.vpn_reconnecting,
                            state.attempt,
                            state.maxAttempts,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = ColorConnecting,
                    )
                }
                is TunnelState.Disconnecting -> {
                    Text(
                        text = stringResource(R.string.vpn_disconnecting),
                        style = MaterialTheme.typography.labelLarge,
                        color = ColorDisconnected,
                    )
                }
                is TunnelState.Error -> {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = ColorError,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.vpn_error),
                        style = MaterialTheme.typography.labelLarge,
                        color = ColorError,
                    )
                }
                TunnelState.Disconnected -> {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = ColorDisconnected,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.vpn_disconnected),
                        style = MaterialTheme.typography.labelLarge,
                        color = ColorDisconnected,
                    )
                }
            }
        }
    }
}
