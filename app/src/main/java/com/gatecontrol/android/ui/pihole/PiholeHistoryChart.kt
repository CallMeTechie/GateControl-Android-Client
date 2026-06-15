package com.gatecontrol.android.ui.pihole

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R

private val ColorAllowed = Color(0xFF3BA776)
private val ColorBlocked = Color(0xFFE0524B)

@Composable
fun PiholeHistoryChart(
    allowed: List<Long>,
    blocked: List<Long>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.pihole_history_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        Canvas(modifier = Modifier.fillMaxWidth().height(96.dp)) {
            if (allowed.isEmpty() && blocked.isEmpty()) return@Canvas
            val maxVal = maxOf((allowed + blocked).maxOrNull() ?: 1L, 1L).toFloat()
            val width = size.width
            val height = size.height
            val pointCount = maxOf(allowed.size, blocked.size).coerceAtLeast(2)
            val stepX = width / (pointCount - 1).coerceAtLeast(1).toFloat()

            fun buildPath(series: List<Long>): Pair<Path, Path> {
                val line = Path(); val fill = Path()
                series.forEachIndexed { i, v ->
                    val x = i * stepX
                    val y = height - (v.toFloat() / maxVal) * height
                    if (i == 0) { line.moveTo(x, y); fill.moveTo(x, height); fill.lineTo(x, y) }
                    else { line.lineTo(x, y); fill.lineTo(x, y) }
                }
                if (series.isNotEmpty()) { fill.lineTo((series.size - 1) * stepX, height); fill.close() }
                return line to fill
            }

            if (allowed.isNotEmpty()) {
                val (l, f) = buildPath(allowed)
                drawPath(f, color = ColorAllowed.copy(alpha = 0.15f))
                drawPath(l, color = ColorAllowed, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            if (blocked.isNotEmpty()) {
                val (l, f) = buildPath(blocked)
                drawPath(f, color = ColorBlocked.copy(alpha = 0.15f))
                drawPath(l, color = ColorBlocked, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendDot(ColorAllowed, stringResource(R.string.pihole_allowed))
            LegendDot(ColorBlocked, stringResource(R.string.pihole_blocked))
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = color) {}
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
