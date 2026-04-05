package com.gatecontrol.android.ui.vpn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.common.Formatters
import com.gatecontrol.android.tunnel.TunnelStats
import com.gatecontrol.android.ui.components.GcStatCard

@Composable
fun StatsGrid(
    stats: TunnelStats,
    serverHost: String?,
    locale: String,
    modifier: Modifier = Modifier,
) {
    val rxValue = Formatters.formatBytes(stats.rxBytes)
    val rxSpeed = Formatters.formatSpeed(stats.rxSpeed)
    val txValue = Formatters.formatBytes(stats.txBytes)
    val txSpeed = Formatters.formatSpeed(stats.txSpeed)
    val handshake = if (stats.lastHandshakeEpoch > 0) {
        Formatters.formatHandshakeAge(stats.handshakeAgeSeconds, locale)
    } else {
        "—"
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GcStatCard(
                label = stringResource(R.string.vpn_server),
                value = serverHost ?: "—",
                modifier = Modifier.weight(1f),
            )
            GcStatCard(
                label = stringResource(R.string.vpn_handshake),
                value = handshake,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GcStatCard(
                label = stringResource(R.string.vpn_received),
                value = rxValue,
                subtitle = rxSpeed,
                modifier = Modifier.weight(1f),
            )
            GcStatCard(
                label = stringResource(R.string.vpn_sent),
                value = txValue,
                subtitle = txSpeed,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
