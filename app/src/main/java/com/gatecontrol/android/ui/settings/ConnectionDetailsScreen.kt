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
import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.ui.components.GcListRow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Result of the DNS leak test (`GET /api/v1/client/dns-check`). */
sealed interface DnsLeakState {
    data object Idle : DnsLeakState
    data object Running : DnsLeakState
    /** [resolver]: the resolver the tunnel uses, as the server reports it. */
    data class Ok(val resolver: String?) : DnsLeakState
    data object Failed : DnsLeakState
}

/**
 * Tunnel details for Settings → Verbindung → "Verbindungsdetails" (until
 * 1.31 a card on the start tab): tunnel address, connected for (live),
 * last handshake, server, and the DNS leak test (until 1.35 a tile on the
 * start tab; only with the token's DNS right).
 */
@HiltViewModel
class ConnectionDetailsViewModel @Inject constructor(
    private val tunnelManager: TunnelManager,
    private val setupRepository: SetupRepository,
    licenseRepository: LicenseRepository,
    private val apiClientProvider: ApiClientProvider,
) : ViewModel() {

    val tunnelState: StateFlow<TunnelState> = tunnelManager.state

    /** The token may run the DNS leak test. */
    val dnsAllowed: StateFlow<Boolean> = licenseRepository.permissions
        .map { it.dns }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), licenseRepository.permissions.value.dns)

    private val _dnsLeak = MutableStateFlow<DnsLeakState>(DnsLeakState.Idle)
    val dnsLeak: StateFlow<DnsLeakState> = _dnsLeak.asStateFlow()

    /** Asks the server which resolver the tunnel uses (same check as the old start tile). */
    fun runDnsLeakTest() {
        if (_dnsLeak.value == DnsLeakState.Running) return
        _dnsLeak.value = DnsLeakState.Running
        viewModelScope.launch {
            _dnsLeak.value = try {
                val serverUrl = setupRepository.getServerUrl()
                if (serverUrl.isEmpty()) {
                    DnsLeakState.Failed
                } else {
                    val response = apiClientProvider.getClient(serverUrl).dnsCheck()
                    if (response.ok) DnsLeakState.Ok(response.vpnDns) else DnsLeakState.Failed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "ConnectionDetails: DNS leak test failed")
                DnsLeakState.Failed
            }
        }
    }

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
        val dnsAllowed by viewModel.dnsAllowed.collectAsStateWithLifecycle()
        if (dnsAllowed) {
            val dnsLeak by viewModel.dnsLeak.collectAsStateWithLifecycle()
            DnsLeakCard(dnsLeak, onRun = viewModel::runDnsLeakTest, modifier = Modifier.padding(horizontal = 16.dp))
        }
        Text(
            stringResource(R.string.connection_details_hint),
            style = MaterialTheme.typography.bodySmall,
            color = extra.muted,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

/** "DNS-Leak-Test": a tap asks the server which resolver the tunnel uses. */
@Composable
private fun DnsLeakCard(state: DnsLeakState, onRun: () -> Unit, modifier: Modifier = Modifier) {
    val extra = GateControlTheme.extraColors
    val running = state == DnsLeakState.Running
    GcCard(modifier = modifier, contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
        GcListRow(
            title = stringResource(R.string.dns_leak_test),
            description = when (state) {
                DnsLeakState.Running -> stringResource(R.string.dns_testing)
                is DnsLeakState.Ok -> stringResource(R.string.dns_tile_ok, state.resolver ?: "")
                DnsLeakState.Failed -> stringResource(R.string.dns_tile_fail)
                DnsLeakState.Idle -> stringResource(R.string.dns_tile_idle)
            },
            onClick = if (running) null else onRun,
            leading = {
                Icon(
                    GcIcons.Globe,
                    contentDescription = null,
                    tint = when (state) {
                        is DnsLeakState.Ok -> extra.accentText
                        DnsLeakState.Failed -> MaterialTheme.colorScheme.error
                        else -> extra.blue
                    },
                    modifier = Modifier.size(22.dp),
                )
            },
            trailing = {
                if (running) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp), color = extra.faint)
                } else {
                    Icon(GcIcons.Refresh, contentDescription = null, tint = extra.faint, modifier = Modifier.size(20.dp))
                }
            },
        )
    }
}
