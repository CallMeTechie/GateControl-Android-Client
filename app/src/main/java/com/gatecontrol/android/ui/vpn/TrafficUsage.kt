package com.gatecontrol.android.ui.vpn

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.gatecontrol.android.R
import com.gatecontrol.android.common.Formatters
import com.gatecontrol.android.network.TrafficStats
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily
import androidx.compose.ui.unit.dp

/** "Datennutzung" card: period switch, total, download/upload split bar. */
@Composable
fun TrafficUsage(
    traffic: TrafficStats?,
    modifier: Modifier = Modifier,
) {
    val extra = GateControlTheme.extraColors
    var period by rememberSaveable { mutableStateOf("24h") }

    val (rx, tx) = when (period) {
        "24h" -> (traffic?.last24h?.rx ?: 0L) to (traffic?.last24h?.tx ?: 0L)
        "7d" -> (traffic?.last7d?.rx ?: 0L) to (traffic?.last7d?.tx ?: 0L)
        "30d" -> (traffic?.last30d?.rx ?: 0L) to (traffic?.last30d?.tx ?: 0L)
        else -> (traffic?.total?.rx ?: 0L) to (traffic?.total?.tx ?: 0L)
    }
    val total = rx + tx
    val rxShare = if (total > 0) rx.toFloat() / total else 0f

    GcCard(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.traffic_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        GcSegmented(
            options = listOf(
                "24h" to stringResource(R.string.traffic_24h),
                "7d" to stringResource(R.string.traffic_7d),
                "30d" to stringResource(R.string.traffic_30d),
                "total" to stringResource(R.string.traffic_total),
            ),
            selected = period,
            onSelect = { period = it },
        )
        Text(
            Formatters.formatBytes(total),
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(extra.panel2),
        ) {
            if (total > 0) {
                if (rxShare > 0f) Box(Modifier.weight(rxShare).fillMaxHeight().background(extra.accentText))
                if (rxShare < 1f) Box(Modifier.weight(1f - rxShare).fillMaxHeight().background(extra.blue))
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            UsageValue(stringResource(R.string.vpn_received), Formatters.formatBytes(rx))
            UsageValue(stringResource(R.string.vpn_sent), Formatters.formatBytes(tx))
        }
    }
}

@Composable
private fun UsageValue(label: String, value: String) {
    Text(
        buildAnnotatedString {
            append(label)
            append(" ")
            withStyle(SpanStyle(fontFamily = MonoFontFamily, fontWeight = FontWeight.Medium)) { append(value) }
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
