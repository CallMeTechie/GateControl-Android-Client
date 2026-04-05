package com.gatecontrol.android.ui.vpn

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.theme.DarkAccent
import com.gatecontrol.android.ui.theme.DarkBlue

private val ColorRx = DarkAccent
private val ColorTx = DarkBlue
private const val MIN_Y_VALUE = 1024L // 1 KB/s minimum scale

@Composable
fun BandwidthGraph(
    rxHistory: List<Long>,
    txHistory: List<Long>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.vpn_bandwidth),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
        ) {
            if (rxHistory.isEmpty() && txHistory.isEmpty()) return@Canvas

            val maxPoints = 60
            val rx = rxHistory.takeLast(maxPoints)
            val tx = txHistory.takeLast(maxPoints)
            val allValues = (rx + tx)
            val maxVal = maxOf(allValues.maxOrNull() ?: MIN_Y_VALUE, MIN_Y_VALUE).toFloat()

            val width = size.width
            val height = size.height
            val pointCount = maxOf(rx.size, tx.size).coerceAtLeast(2)
            val stepX = width / (pointCount - 1).coerceAtLeast(1).toFloat()

            fun buildPath(history: List<Long>): Pair<Path, Path> {
                val linePath = Path()
                val fillPath = Path()
                history.forEachIndexed { index, value ->
                    val x = index * stepX
                    val y = height - (value.toFloat() / maxVal) * height
                    if (index == 0) {
                        linePath.moveTo(x, y)
                        fillPath.moveTo(x, height)
                        fillPath.lineTo(x, y)
                    } else {
                        linePath.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                }
                // Close fill path along the bottom
                if (history.isNotEmpty()) {
                    val lastX = (history.size - 1) * stepX
                    fillPath.lineTo(lastX, height)
                    fillPath.close()
                }
                return linePath to fillPath
            }

            // Draw RX
            if (rx.isNotEmpty()) {
                val (rxLine, rxFill) = buildPath(rx)
                drawPath(rxFill, color = ColorRx.copy(alpha = 0.15f))
                drawPath(
                    rxLine,
                    color = ColorRx,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }

            // Draw TX
            if (tx.isNotEmpty()) {
                val (txLine, txFill) = buildPath(tx)
                drawPath(txFill, color = ColorTx.copy(alpha = 0.15f))
                drawPath(
                    txLine,
                    color = ColorTx,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // Legend
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LegendItem(color = ColorRx, label = stringResource(R.string.vpn_download))
            LegendItem(color = ColorTx, label = stringResource(R.string.vpn_upload))
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(8.dp),
            shape = CircleShape,
            color = color,
        ) {}
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
