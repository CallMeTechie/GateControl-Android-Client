package com.gatecontrol.android.ui.vpn

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.common.Formatters
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily

private const val MIN_Y_VALUE = 1024L // 1 KB/s minimum scale

/**
 * "Durchsatz" card: current download/upload rate and a 60-second graph
 * (download as filled line, upload dashed). Shows a placeholder while the
 * tunnel is down.
 */
@Composable
fun BandwidthGraph(
    rxHistory: List<Long>,
    txHistory: List<Long>,
    modifier: Modifier = Modifier,
    connected: Boolean = true,
    rxSpeed: Long = rxHistory.lastOrNull() ?: 0L,
    txSpeed: Long = txHistory.lastOrNull() ?: 0L,
) {
    val extra = GateControlTheme.extraColors
    val rxColor = extra.accentText
    val txColor = extra.blue

    GcCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = stringResource(R.string.vpn_bandwidth),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text("60 s", style = MaterialTheme.typography.bodySmall.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize), color = extra.faint)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            RateValue("↓ " + stringResource(R.string.vpn_download), if (connected) Formatters.formatSpeed(rxSpeed) else "—", rxColor)
            RateValue("↑ " + stringResource(R.string.vpn_upload), if (connected) Formatters.formatSpeed(txSpeed) else "—", txColor)
        }

        if (!connected) {
            val dash = extra.border2
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .drawBehind {
                        drawRoundRect(
                            color = dash,
                            cornerRadius = CornerRadius(14.dp.toPx()),
                            style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.vpn_live_placeholder),
                    style = MaterialTheme.typography.bodyMedium,
                    color = extra.muted,
                )
            }
            return@GcCard
        }

        val graphDesc = stringResource(R.string.vpn_bandwidth_graph_desc)
        val midline = extra.border
        val fill = extra.accentBg
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp)
                .semantics { contentDescription = graphDesc },
        ) {
            val h = size.height
            val w = size.width
            drawLine(midline, Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 1.dp.toPx())
            if (rxHistory.isEmpty() && txHistory.isEmpty()) return@Canvas

            val maxPoints = 60
            val rx = rxHistory.takeLast(maxPoints)
            val tx = txHistory.takeLast(maxPoints)
            val maxVal = maxOf((rx + tx).maxOrNull() ?: MIN_Y_VALUE, MIN_Y_VALUE).toFloat() * 1.15f
            val stepX = w / (maxPoints - 1).toFloat()
            // Right-align so the newest sample is always at the right edge.
            fun xAt(index: Int, count: Int) = w - (count - 1 - index) * stepX
            fun yAt(v: Long) = h - (v.toFloat() / maxVal) * h

            fun line(history: List<Long>): Path = Path().apply {
                history.forEachIndexed { i, v ->
                    if (i == 0) moveTo(xAt(i, history.size), yAt(v)) else lineTo(xAt(i, history.size), yAt(v))
                }
            }

            if (rx.isNotEmpty()) {
                val area = line(rx).apply {
                    lineTo(xAt(rx.size - 1, rx.size), h)
                    lineTo(xAt(0, rx.size), h)
                    close()
                }
                drawPath(area, color = fill)
                drawPath(line(rx), color = rxColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            if (tx.isNotEmpty()) {
                drawPath(
                    line(tx),
                    color = txColor,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        join = StrokeJoin.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                    ),
                )
            }
        }
    }
}

@Composable
private fun RateValue(label: String, value: String, color: Color) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = null), color = GateControlTheme.extraColors.muted)
        Text(
            value,
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = MonoFontFamily, fontSize = MaterialTheme.typography.headlineSmall.fontSize),
            color = color,
        )
    }
}
