package com.gatecontrol.android.ui.vpn

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.common.Formatters
import com.gatecontrol.android.network.TrafficStats
import com.gatecontrol.android.ui.theme.DarkAccent
import com.gatecontrol.android.ui.theme.DarkBlue
import com.gatecontrol.android.ui.theme.GateControlTheme

private val ColorRx = DarkAccent
private val ColorTx = DarkBlue

@Composable
fun TrafficUsage(
    traffic: TrafficStats?,
    modifier: Modifier = Modifier,
) {
    val extra = GateControlTheme.extraColors

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.BarChart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.traffic_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TrafficCard(
                label = stringResource(R.string.traffic_24h),
                rx = traffic?.last24h?.rx ?: 0L,
                tx = traffic?.last24h?.tx ?: 0L,
                rxColor = ColorRx,
                txColor = ColorTx,
                borderColor = extra.border,
                modifier = Modifier.weight(1f),
            )
            TrafficCard(
                label = stringResource(R.string.traffic_7d),
                rx = traffic?.last7d?.rx ?: 0L,
                tx = traffic?.last7d?.tx ?: 0L,
                rxColor = ColorRx,
                txColor = ColorTx,
                borderColor = extra.border,
                modifier = Modifier.weight(1f),
            )
            TrafficCard(
                label = stringResource(R.string.traffic_30d),
                rx = traffic?.last30d?.rx ?: 0L,
                tx = traffic?.last30d?.tx ?: 0L,
                rxColor = ColorRx,
                txColor = ColorTx,
                borderColor = extra.border,
                modifier = Modifier.weight(1f),
            )
            TrafficCard(
                label = stringResource(R.string.traffic_total),
                rx = traffic?.total?.rx ?: 0L,
                tx = traffic?.total?.tx ?: 0L,
                rxColor = ColorRx,
                txColor = ColorTx,
                borderColor = extra.border,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TrafficCard(
    label: String,
    rx: Long,
    tx: Long,
    rxColor: Color,
    txColor: Color,
    borderColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = Formatters.formatBytes(rx),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = rxColor,
            )
            Text(
                text = Formatters.formatBytes(tx),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = txColor,
            )
        }
    }
}
