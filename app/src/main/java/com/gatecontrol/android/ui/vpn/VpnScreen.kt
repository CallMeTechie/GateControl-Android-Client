package com.gatecontrol.android.ui.vpn

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gatecontrol.android.R
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcToggleRow
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.delay

@Composable
fun VpnScreen(
    viewModel: VpnViewModel = hiltViewModel(),
) {
    val tunnelState by viewModel.tunnelState.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val trafficUsage by viewModel.trafficUsage.collectAsState()
    val permissions by viewModel.permissions.collectAsState()
    val services by viewModel.services.collectAsState()
    val killSwitchEnabled by viewModel.killSwitchEnabled.collectAsState()

    // Bandwidth history ring buffers (60 points each)
    val rxHistory = remember { mutableStateListOf<Long>() }
    val txHistory = remember { mutableStateListOf<Long>() }

    // Push speed samples every second when connected
    LaunchedEffect(tunnelState) {
        if (tunnelState is TunnelState.Connected) {
            while (true) {
                delay(1_000)
                if (rxHistory.size >= 60) rxHistory.removeAt(0)
                if (txHistory.size >= 60) txHistory.removeAt(0)
                rxHistory.add(stats.rxSpeed)
                txHistory.add(stats.txSpeed)
            }
        }
    }

    // Load data and start monitoring on first composition
    LaunchedEffect(Unit) {
        viewModel.startMonitoring()
        viewModel.loadPermissions()
        viewModel.loadTrafficStats()
        viewModel.loadServices()
    }

    val isConnected = tunnelState is TunnelState.Connected
    val isBusy = tunnelState is TunnelState.Connecting
        || tunnelState is TunnelState.Disconnecting
        || tunnelState is TunnelState.Reconnecting

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {

        // Connection ring
        ConnectionRing(
            state = tunnelState,
            ringSize = 180.dp,
        )

        // Connect / Disconnect button
        if (isConnected) {
            GcOutlineButton(
                text = stringResource(R.string.vpn_disconnect),
                onClick = { viewModel.disconnect() },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            GcPrimaryButton(
                text = stringResource(R.string.vpn_connect),
                onClick = { viewModel.connect() },
                enabled = !isBusy,
                loading = isBusy,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Stats grid (only show when connected or previously connected)
        if (isConnected || stats.rxBytes > 0 || stats.txBytes > 0) {
            StatsGrid(
                stats = stats,
                serverHost = viewModel.serverHost,
                locale = "en",
            )
        }

        // Bandwidth graph (only when connected)
        if (isConnected) {
            BandwidthGraph(
                rxHistory = rxHistory.toList(),
                txHistory = txHistory.toList(),
            )
        }

        // Traffic usage (requires traffic permission)
        if (permissions.traffic && trafficUsage != null) {
            TrafficUsage(traffic = trafficUsage)
        }

        // Kill-switch toggle
        GcToggleRow(
            icon = Icons.Default.Lock,
            label = stringResource(R.string.vpn_kill_switch),
            description = stringResource(R.string.vpn_kill_switch_desc),
            checked = killSwitchEnabled,
            onCheckedChange = { viewModel.toggleKillSwitch(it) },
        )

        // Services list (requires services permission)
        if (permissions.services && services.isNotEmpty()) {
            ServicesSection(services = services)
        }

        // DNS leak test (requires dns permission)
        if (permissions.dns) {
            var dnsTestResult by remember { mutableStateOf<String?>(null) }
            var dnsTestLoading by remember { mutableStateOf(false) }

            GcOutlineButton(
                text = if (dnsTestLoading) stringResource(R.string.dns_testing)
                       else dnsTestResult ?: stringResource(R.string.dns_leak_test),
                onClick = {
                    dnsTestLoading = true
                    viewModel.runDnsLeakTest { result ->
                        dnsTestResult = result
                        dnsTestLoading = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ServicesSection(
    services: List<com.gatecontrol.android.network.VpnService>,
) {
    val extra = GateControlTheme.extraColors

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.services_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        services.forEach { service ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, extra.border),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                    ) {
                        Text(
                            text = service.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = service.domain,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (service.hasAuth) {
                        Text(
                            text = stringResource(R.string.services_auth_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = extra.warn,
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}
