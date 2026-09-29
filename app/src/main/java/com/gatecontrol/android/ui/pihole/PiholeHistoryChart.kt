package com.gatecontrol.android.ui.pihole

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.theme.GateControlTheme

private const val MAX_BARS = 24

/**
 * Stacked bar chart of Pi-hole queries (allowed in the neutral line tone,
 * blocked in warn). Longer histories are summed into at most 24 buckets.
 */
@Composable
fun PiholeHistoryChart(
    allowed: List<Long>,
    blocked: List<Long>,
    modifier: Modifier = Modifier,
) {
    val extra = GateControlTheme.extraColors
    val allowedColor = extra.border2
    val blockedColor = extra.warn
    val n = maxOf(allowed.size, blocked.size)
    val bucket = if (n > MAX_BARS) (n + MAX_BARS - 1) / MAX_BARS else 1
    fun bucketed(series: List<Long>): List<Long> =
        (0 until n).chunked(bucket).map { idx -> idx.sumOf { series.getOrElse(it) { 0L } } }
    val a = bucketed(allowed)
    val b = bucketed(blocked)
    val chartDesc = stringResource(R.string.pihole_history_desc)

    GcCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.pihole_history_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Legend(allowedColor, stringResource(R.string.pihole_allowed))
            Legend(blockedColor, stringResource(R.string.pihole_blocked))
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .semantics { contentDescription = chartDesc },
        ) {
            if (a.isEmpty()) return@Canvas
            val maxVal = (a.indices.maxOfOrNull { a[it] + b.getOrElse(it) { 0L } } ?: 1L).coerceAtLeast(1L).toFloat()
            val gap = 3.dp.toPx()
            val barW = (size.width - gap * (a.size - 1)) / a.size
            val r = CornerRadius(3.dp.toPx())
            a.indices.forEach { i ->
                val x = i * (barW + gap)
                val hb = size.height * b.getOrElse(i) { 0L } / maxVal
                val ha = size.height * a[i] / maxVal
                if (ha > 0f) drawRoundRect(allowedColor, Offset(x, size.height - hb - ha), Size(barW, ha), r)
                if (hb > 0f) drawRect(blockedColor, Offset(x, size.height - hb), Size(barW, hb))
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(label, style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
    }
}
