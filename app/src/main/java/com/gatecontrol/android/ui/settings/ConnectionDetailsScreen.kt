package com.gatecontrol.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.common.Formatters
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.diagnosis.HANDSHAKE_OK_SEC
import com.gatecontrol.android.tunnel.TunnelConfig
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.tunnel.TunnelStats
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcLabeledValue
import com.gatecontrol.android.ui.components.GcSecondaryButton
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Tunnel details for Settings → Verbindung → "Verbindungsdetails" (until
 * 1.31 a card on the start tab): tunnel address, connected for (live),
 * last handshake, server.
 */
@HiltViewModel
class ConnectionDetailsViewModel @Inject constructor(
    private val tunnelManager: TunnelManager,
    private val setupRepository: SetupRepository,
) : ViewModel() {

    val tunnelState: StateFlow<TunnelState> = tunnelManager.state

    private val _stats = MutableStateFlow(TunnelStats())
    val stats: StateFlow<TunnelStats> = _stats.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                if (tunnelState.value is TunnelState.Connected) {
                    runCatching { tunnelManager.getStatistics() }.getOrNull()?.let { _stats.value = it }
                }
                delay(1_000)
            }
        }
    }

    /** Tunnel address from the WireGuard config, without prefix length. */
    val tunnelAddress: String?
        get() = config()?.let { c ->
            runCatching { c.address.split(",").first().trim().substringBefore("/") }.getOrNull()?.takeIf { it.isNotBlank() }
        }

    /** Server endpoint host from the WireGuard config, else the configured server. */
    val serverHost: String?
        get() = config()?.let { runCatching { it.getServerHost() }.getOrNull() }
            ?: setupRepository.getServerUrl().takeIf { it.isNotBlank() }?.let { runCatching { java.net.URI(it).host }.getOrNull() }

    private fun config(): TunnelConfig? =
        setupRepository.getWireGuardConfig().takeIf { it.isNotEmpty() }?.let { runCatching { TunnelConfig.parse(it) }.getOrNull() }
}

/** One-line summary for the settings row ("Verbunden seit 0:12:03 · 10.8.0.4" / "VPN ist aus"). */
@Composable
fun connectionDetailsSummary(viewModel: ConnectionDetailsViewModel): String {
    val state by viewModel.tunnelState.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val connected = state as? TunnelState.Connected
    LaunchedEffect(connected) {
        while (connected != null) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    return if (connected != null) {
        val since = connected.connectedSince
        val uptime = if (since > 0) Formatters.formatDuration((now - since).coerceAtLeast(0) / 1000) else "—"
        stringResource(R.string.connection_details_row_on, uptime, viewModel.tunnelAddress ?: "—")
    } else {
        stringResource(R.string.connection_details_off)
    }
}

@Composable
fun ConnectionDetailsScreen(
    onBack: () -> Unit,
    onOpenDiagnosis: () -> Unit,
    viewModel: ConnectionDetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.tunnelState.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val extra = GateControlTheme.extraColors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val connected = state as? TunnelState.Connected
    LaunchedEffect(connected) {
        while (connected != null) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GcSubpageBar(
            title = stringResource(R.string.connection_details_title),
            onBack = onBack,
            backDescription = stringResource(R.string.common_back),
        )
        GcCard(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (connected == null) {
                Text(stringResource(R.string.connection_details_off), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                GcLabeledValue(stringResource(R.string.vpn_server), viewModel.serverHost ?: "—")
            } else {
                val since = connected.connectedSince
                val uptime = if (since > 0) Formatters.formatDuration((now - since).coerceAtLeast(0) / 1000) else "—"
                val hsAge = stats.handshakeAgeSeconds
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    GcLabeledValue(stringResource(R.string.vpn_tunnel_ip), viewModel.tunnelAddress ?: "—", Modifier.weight(1f))
                    GcLabeledValue(stringResource(R.string.vpn_connected_since), uptime, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    GcLabeledValue(
                        stringResource(R.string.vpn_last_handshake),
                        if (hsAge == Long.MAX_VALUE) "—" else stringResource(R.string.vpn_handshake_ago, hsAge),
                        Modifier.weight(1f),
                    )
                    GcLabeledValue(stringResource(R.string.vpn_server), viewModel.serverHost ?: "—", Modifier.weight(1f))
                }
                // Stale handshake: offer the diagnosis.
                if (hsAge != Long.MAX_VALUE && hsAge > HANDSHAKE_OK_SEC) {
                    GcSecondaryButton(
                        text = stringResource(R.string.diag_home_check),
                        onClick = onOpenDiagnosis,
                        icon = GcIcons.Activity,
                        fillWidth = false,
                    )
                }
            }
        }
        Text(
            stringResource(R.string.connection_details_hint),
            style = MaterialTheme.typography.bodySmall,
            color = extra.muted,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}
