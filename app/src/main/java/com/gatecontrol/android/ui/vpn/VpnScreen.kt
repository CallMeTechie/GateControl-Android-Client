package com.gatecontrol.android.ui.vpn

import com.gatecontrol.android.common.SplitTunnelMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gatecontrol.android.R
import com.gatecontrol.android.util.openSystemVpnSettings
import com.gatecontrol.android.common.Formatters
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.messageRes
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcLabeledValue
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcTile
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.pihole.PiholeTile
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.delay

private const val EXPIRY_WARN_DAYS = 7

/**
 * Start tab: header, connect orb with state text, connection details,
 * quick tiles (kill switch, split tunneling, Pi-hole, DNS leak test),
 * throughput and data usage.
 */
@Composable
fun VpnScreen(
    viewModel: VpnViewModel = hiltViewModel(),
    onTokenInvalid: () -> Unit = {},
    onOpenPihole: () -> Unit = {},
    onOpenSplitTunnel: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val extra = GateControlTheme.extraColors

    // VPN permission launcher — Android requires user consent before creating a VPN tunnel
    val vpnPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.connect()
        }
    }

    val tunnelState by viewModel.tunnelState.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val trafficUsage by viewModel.trafficUsage.collectAsState()
    val permissions by viewModel.permissions.collectAsState()
    val splitMode by viewModel.splitTunnelMode.collectAsState()
    val theme by viewModel.theme.collectAsState()
    val portalUrl by viewModel.portalUrl.collectAsState()
    val autoOpen by viewModel.autoOpenPortal.collectAsState()
    val expiresAt by viewModel.peerExpiresAt.collectAsState()
    val clientPolicy by viewModel.clientPolicy.collectAsState()

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

    // Start monitoring and validate token on first composition
    LaunchedEffect(Unit) {
        viewModel.startMonitoring()
        viewModel.validateToken()
        viewModel.loadPeerInfo()
    }

    // Redirect to setup if token is invalid
    val tokenInvalid by viewModel.tokenInvalid.collectAsState()
    LaunchedEffect(tokenInvalid) {
        if (tokenInvalid) onTokenInvalid()
    }

    // Show notification when peer is disabled on server
    val peerDisabled by viewModel.peerDisabled.collectAsState()
    val peerDisabledMessage = stringResource(R.string.peer_disabled_by_server)
    LaunchedEffect(peerDisabled) {
        if (peerDisabled) {
            android.widget.Toast.makeText(
                context,
                peerDisabledMessage,
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    // Reload data when VPN state changes. Invalidate cached HTTP clients first
    // because OkHttp's connection pool holds stale connections on the old network
    // interface after VPN connect/disconnect, causing SocketTimeoutExceptions.
    // Brief delay after Connected: the excluded app's network path needs a moment
    // to stabilize after the VPN tunnel changes the routing table.
    LaunchedEffect(tunnelState) {
        if (tunnelState is TunnelState.Connected) {
            delay(2_000) // wait for network stack to stabilize
            viewModel.invalidateApiClients()
            viewModel.loadPermissions()
            viewModel.loadTrafficStats()
            viewModel.loadServices()
        } else if (tunnelState is TunnelState.Disconnected) {
            viewModel.invalidateApiClients()
            viewModel.loadPermissions()
            viewModel.loadTrafficStats()
            viewModel.loadServices()
        }
    }

    // Auto-open portal once per tunnel session; the ViewModel keys it on
    // connectedSince. autoOpen/portalUrl are keys so a delayed permissions
    // fetch that enables it re-evaluates for the current session.
    LaunchedEffect(tunnelState, portalUrl, autoOpen) {
        viewModel.autoOpenPortalIfNeeded(context, tunnelState)
    }

    // Tick every second to update connection duration
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(tunnelState) {
        if (tunnelState is TunnelState.Connected) {
            while (true) {
                delay(1_000)
                tick = System.currentTimeMillis()
            }
        }
    }

    val isConnected = tunnelState is TunnelState.Connected
    val isBusy = tunnelState is TunnelState.Connecting
        || tunnelState is TunnelState.Disconnecting
        || tunnelState is TunnelState.Reconnecting
    val isError = tunnelState is TunnelState.Error
    val host = viewModel.serverUrlHost ?: viewModel.serverHost ?: "—"

    val startConnect: () -> Unit = {
        val prepareIntent = android.net.VpnService.prepare(context)
        if (prepareIntent != null) vpnPermissionLauncher.launch(prepareIntent) else viewModel.connect()
    }

    val systemDark = isSystemInDarkTheme()
    val effectivelyDark = when (theme) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // --- Header -------------------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(GcIcons.ShieldCheck, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f)) {
                Text("GateControl", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                Text(host, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1)
            }
            GcIconButton(
                icon = if (effectivelyDark) GcIcons.Sun else GcIcons.Moon,
                contentDescription = stringResource(
                    if (effectivelyDark) R.string.vpn_theme_to_light else R.string.vpn_theme_to_dark,
                ),
                onClick = { viewModel.setTheme(if (effectivelyDark) "light" else "dark") },
            )
        }

        // --- Access expiry ------------------------------------------------
        var expiryDismissed by rememberSaveable { mutableStateOf(false) }
        val expiry = expiresAt
        if (expiry != null && !expiryDismissed) {
            val msLeft = expiry - System.currentTimeMillis()
            val daysLeft = (msLeft / 86_400_000L).toInt()
            if (msLeft > 0 && daysLeft < EXPIRY_WARN_DAYS) {
                GcBanner(tone = GcTone.Warn, icon = GcIcons.Clock) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                append(
                                    if (daysLeft == 0) stringResource(R.string.vpn_expiry_today)
                                    else pluralStringResource(R.plurals.vpn_expiry_days, daysLeft, daysLeft),
                                )
                            }
                            append(" ")
                            append(stringResource(R.string.vpn_expiry_hint))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    GcIconButton(
                        icon = GcIcons.Close,
                        contentDescription = stringResource(R.string.common_dismiss),
                        onClick = { expiryDismissed = true },
                        size = 40.dp,
                        iconSize = 18.dp,
                    )
                }
            }
        }

        // --- Machine binding: the server rejected this device ------------
        val bindingError by viewModel.machineBindingError.collectAsState()
        bindingError?.let { err ->
            GcBanner(tone = GcTone.Error) {
                Text(
                    text = stringResource(err.messageRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // --- Client policy: kill switch / always-on need the system settings
        com.gatecontrol.android.ui.components.GcPolicySystemVpnCard(clientPolicy)

        // --- Orb ----------------------------------------------------------
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Client policy "always on": no manual disconnect in the app.
            val disconnectLocked = isConnected && !clientPolicy.canDisconnect
            val orbHint = when {
                disconnectLocked -> stringResource(R.string.policy_disconnect_locked)
                isConnected -> stringResource(R.string.vpn_disconnect)
                isBusy -> stringResource(R.string.vpn_connecting)
                else -> stringResource(R.string.vpn_connect)
            }
            ConnectionOrb(
                state = tunnelState,
                hint = orbHint,
                contentDescription = orbHint,
                onClick = {
                    when {
                        disconnectLocked -> Unit
                        isConnected -> viewModel.disconnect()
                        isBusy -> viewModel.disconnect()
                        else -> startConnect()
                    }
                },
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(
                    text = heroTitle(tunnelState),
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = when {
                        isConnected -> stringResource(R.string.vpn_sub_on, host)
                        isBusy -> stringResource(R.string.vpn_sub_connecting, host)
                        isError -> stringResource(R.string.vpn_sub_error)
                        else -> stringResource(R.string.vpn_sub_off)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = extra.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            val st = tunnelState
            if (st is TunnelState.Error) {
                GcBanner(tone = GcTone.Error) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)) {
                                append(stringResource(R.string.vpn_error))
                                append(". ")
                            }
                            append(st.message)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GcPrimaryButton(
                        text = stringResource(R.string.vpn_retry),
                        onClick = startConnect,
                        modifier = Modifier.weight(1f),
                    )
                    GcOutlineButton(
                        text = stringResource(R.string.vpn_logs),
                        onClick = onOpenLogs,
                        fillWidth = false,
                    )
                }
            }
            if (isBusy) {
                GcOutlineButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { viewModel.disconnect() },
                    fillWidth = false,
                )
            }
            if (isConnected && !portalUrl.isNullOrBlank()) {
                GcOutlineButton(
                    text = stringResource(R.string.vpn_portal_open),
                    onClick = { viewModel.openPortal(context) },
                    icon = GcIcons.External,
                    fillWidth = false,
                    minHeight = 40.dp,
                )
            }
        }

        // --- Connection details ------------------------------------------
        if (isConnected) {
            val connectedSince = (tunnelState as? TunnelState.Connected)?.connectedSince ?: 0L
            @Suppress("UNUSED_EXPRESSION")
            tick // recompose every second
            val uptime = if (connectedSince > 0) {
                Formatters.formatDuration((System.currentTimeMillis() - connectedSince) / 1000)
            } else {
                "—"
            }
            val hsAge = stats.handshakeAgeSeconds
            GcCard(verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
            }
        }

        // --- Quick tiles --------------------------------------------------
        val tiles = buildList<@Composable (Modifier) -> Unit> {
            add { m ->
                GcTile(
                    icon = GcIcons.Lock,
                    iconTint = extra.faint,
                    title = stringResource(R.string.vpn_kill_switch),
                    // Real kill switch = Android "Always-on VPN" + "Block
                    // connections without VPN"; the app cannot enforce it.
                    subtitle = stringResource(R.string.vpn_kill_switch_system),
                    active = false,
                    onClick = { context.openSystemVpnSettings() },
                    modifier = m,
                )
            }
            add { m ->
                GcTile(
                    icon = GcIcons.Split,
                    iconTint = extra.blue,
                    title = stringResource(R.string.settings_split_tunnel),
                    subtitle = stringResource(
                        when (splitMode) {
                            SplitTunnelMode.EXCLUDE -> R.string.split_tile_exclude
                            SplitTunnelMode.INCLUDE -> R.string.split_tile_include
                            SplitTunnelMode.OFF -> R.string.tile_off
                        },
                    ),
                    onClick = onOpenSplitTunnel,
                    modifier = m,
                )
            }
            if (permissions.pihole) {
                add { m -> PiholeTile(onOpen = onOpenPihole, modifier = m) }
            }
            if (permissions.dns) {
                add { m -> DnsLeakTile(viewModel, m) }
            }
        }
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { tile -> tile(Modifier.weight(1f)) }
                if (row.size == 1) Column(Modifier.weight(1f)) {}
            }
        }

        // --- Throughput + usage ------------------------------------------
        BandwidthGraph(
            rxHistory = rxHistory.toList(),
            txHistory = txHistory.toList(),
            connected = isConnected,
            rxSpeed = stats.rxSpeed,
            txSpeed = stats.txSpeed,
        )

        if (permissions.traffic && trafficUsage != null) {
            TrafficUsage(traffic = trafficUsage)
        }
    }
}

@Composable
private fun heroTitle(state: TunnelState): String = when (state) {
    is TunnelState.Connected -> stringResource(R.string.vpn_hero_on)
    is TunnelState.Connecting -> stringResource(R.string.vpn_connecting)
    is TunnelState.Reconnecting -> stringResource(R.string.vpn_reconnecting, state.attempt, state.maxAttempts)
    TunnelState.Disconnecting -> stringResource(R.string.vpn_disconnecting)
    is TunnelState.Error -> stringResource(R.string.vpn_hero_error)
    TunnelState.Disconnected -> stringResource(R.string.vpn_hero_off)
}

@Composable
private fun DnsLeakTile(viewModel: VpnViewModel, modifier: Modifier) {
    val extra = GateControlTheme.extraColors
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String?>?>(null) }

    val spin = androidx.compose.animation.core.rememberInfiniteTransition(label = "dns")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1_100, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "dns_spin",
    )

    val (ok, detail) = result ?: (null to null)
    GcTile(
        icon = GcIcons.Globe,
        iconTint = when (ok) {
            true -> extra.accentText
            false -> MaterialTheme.colorScheme.error
            null -> extra.blue
        },
        title = stringResource(R.string.dns_leak_test),
        subtitle = when {
            busy -> stringResource(R.string.dns_testing)
            ok == true -> stringResource(R.string.dns_tile_ok, detail ?: "")
            ok == false -> stringResource(R.string.dns_tile_fail)
            else -> stringResource(R.string.dns_tile_idle)
        },
        enabled = !busy,
        onClick = {
            busy = true
            viewModel.runDnsLeakTest { success, info ->
                result = success to info
                busy = false
            }
        },
        iconModifier = if (busy) Modifier.graphicsLayer { rotationZ = angle } else Modifier,
        modifier = modifier,
    )
}
