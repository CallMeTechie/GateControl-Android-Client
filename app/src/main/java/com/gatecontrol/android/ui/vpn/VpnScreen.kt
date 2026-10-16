package com.gatecontrol.android.ui.vpn

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import com.gatecontrol.android.ui.adaptive.GcTwoPane
import com.gatecontrol.android.ui.adaptive.HingeSplit
import com.gatecontrol.android.ui.adaptive.LocalGcWindowLayout
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gatecontrol.android.R
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.messageRes
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcAvatar
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcSecondaryButton
import com.gatecontrol.android.ui.notifications.BellState
import com.gatecontrol.android.location.DeviceLocator
import com.gatecontrol.android.location.LocationServerStatus
import com.gatecontrol.android.location.LocationShareViewModel
import com.gatecontrol.android.ui.location.LocationShareBanner
import com.gatecontrol.android.ui.location.SatelliteButton
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.profile.MyAvatarViewModel
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay

private const val EXPIRY_WARN_DAYS = 7

/**
 * Start tab, built to fit a phone screen without scrolling: a header with
 * the own avatar (→ profile, from there the settings), the connection as a
 * pill next to it ("Verbinden" / "Geschützt"; tap toggles, long press →
 * connection details) and the portal, satellite and bell icons; then the
 * pinned quick actions as icon tiles, the Info card (contents the person
 * chose, sliding; long press → menu) and one card for throughput and data
 * usage (sliding). Kill switch and split tunneling live in the settings,
 * the DNS leak test and the connection details in Settings → Verbindung →
 * Verbindungsdetails, Pi-hole in the Netzwerk tab.
 */
@Composable
fun VpnScreen(
    viewModel: VpnViewModel = hiltViewModel(),
    onTokenInvalid: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    /** Unread messages of the notification center (bell badge). */
    unreadCount: Int = 0,
    /** Whether notifications arrive at all; when not, the bell leads to the settings. */
    bell: BellState = BellState(),
    onOpenInbox: () -> Unit = {},
    onEnableNotifications: () -> Unit = {},
    onDismissPushHint: () -> Unit = {},
    /** Quick actions of the plugins (all, pinning). */
    onOpenQuickActions: () -> Unit = {},
    /** Settings › Standortfreigabe (permission missing, not allowed). */
    onOpenLocationSettings: () -> Unit = {},
    /** Diagnosis screen, offered when the connection has a problem. */
    onOpenDiagnosis: () -> Unit = {},
    /** Own profile (tap on the own avatar in the header); the settings open from there. */
    onOpenProfile: () -> Unit = {},
    /** Picker "Inhalte der Info-Karte" (long-press menu of the Info card). */
    onOpenInfoCardPicker: () -> Unit = {},
    /** Settings → Verbindungsdetails (long press on the connection pill). */
    onOpenConnectionDetails: () -> Unit = {},
    /** Door screen "Tür & Kamera" (long press on a door quick action); null without the door plugin. */
    onOpenDoor: (() -> Unit)? = null,
    locationViewModel: LocationShareViewModel = hiltViewModel(),
    avatarViewModel: MyAvatarViewModel = hiltViewModel(),
) {
    val myAvatar by avatarViewModel.ui.collectAsState()
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
    val portalUrl by viewModel.portalUrl.collectAsState()
    val autoOpen by viewModel.autoOpenPortal.collectAsState()
    val expiresAt by viewModel.peerExpiresAt.collectAsState()
    val clientPolicy by viewModel.clientPolicy.collectAsState()
    val locationEnabled by locationViewModel.enabled.collectAsState()
    val locationServer by locationViewModel.server.collectAsState()
    val locationState by locationViewModel.state.collectAsState()
    LaunchedEffect(locationEnabled) { if (locationEnabled) locationViewModel.refresh() }
    // Shown only when switched on and the server neither lacks the feature nor forbids it.
    val showSatellite = locationEnabled &&
        locationServer !is LocationServerStatus.Unsupported &&
        locationServer !is LocationServerStatus.NotAllowed

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

    val isConnected = tunnelState is TunnelState.Connected
    val isBusy = tunnelState is TunnelState.Connecting
        || tunnelState is TunnelState.Disconnecting
        || tunnelState is TunnelState.Reconnecting
    val host = viewModel.serverUrlHost ?: viewModel.serverHost ?: "—"

    val startConnect: () -> Unit = {
        val prepareIntent = android.net.VpnService.prepare(context)
        if (prepareIntent != null) vpnPermissionLauncher.launch(prepareIntent) else viewModel.connect()
    }

    // Client policy "always on": no manual disconnect in the app.
    val disconnectLocked = isConnected && !clientPolicy.canDisconnect
    val lockedHint = stringResource(R.string.policy_disconnect_locked)

    val headerSection: @Composable () -> Unit = {
        // --- Header: avatar · connection pill · portal, satellite, bell ----
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val me = myAvatar
            if (me.initials != null) {
                // Own avatar; ring = own presence (Familie, when shared).
                val description = stringResource(R.string.profile_avatar_desc, me.name ?: stringResource(R.string.profile_title))
                GcAvatar(
                    url = me.avatarUrl,
                    initials = me.initials,
                    colorHex = me.colorHex,
                    ring = me.ring,
                    size = 40.dp,
                    contentDescription = description,
                    // Always the own profile (picture, colour, "Anwesenheit teilen"), also on older servers.
                    modifier = Modifier
                        .clip(CircleShape)
                        .combinedClickable(
                            role = Role.Button,
                            onClick = onOpenProfile,
                            onLongClick = {
                                android.widget.Toast.makeText(context, description, android.widget.Toast.LENGTH_SHORT).show()
                            },
                        ),
                )
            } else {
                // No avatar to tap (older server): the profile, and from there the settings, behind this button.
                GcIconButton(
                    icon = GcIcons.User,
                    contentDescription = stringResource(R.string.profile_open_desc),
                    onClick = onOpenProfile,
                    size = 40.dp,
                )
            }
            ConnectionChip(
                state = tunnelState,
                disconnectLocked = disconnectLocked,
                onClick = {
                    when {
                        disconnectLocked -> android.widget.Toast.makeText(context, lockedHint, android.widget.Toast.LENGTH_LONG).show()
                        isConnected || isBusy -> viewModel.disconnect()
                        else -> startConnect()
                    }
                },
                onLongClick = onOpenConnectionDetails,
            )
            Spacer(Modifier.weight(1f))
            if (isConnected && !portalUrl.isNullOrBlank()) {
                GcIconButton(
                    icon = GcIcons.External,
                    contentDescription = stringResource(R.string.vpn_portal_open),
                    onClick = { viewModel.openPortal(context) },
                )
            }
            if (showSatellite) {
                SatelliteButton(
                    state = locationState,
                    onClick = {
                        if (DeviceLocator.hasPermission(context)) locationViewModel.shareNow() else onOpenLocationSettings()
                    },
                )
            }
            Box {
                GcIconButton(
                    icon = if (bell.pushOff) GcIcons.BellOff else GcIcons.Bell,
                    contentDescription = when {
                        bell.pushOff -> stringResource(R.string.push_bell_off)
                        unreadCount > 0 -> stringResource(R.string.push_inbox_open, unreadCount)
                        else -> stringResource(R.string.push_inbox_open_none)
                    },
                    onClick = onOpenInbox,
                    tint = if (bell.pushOff) extra.warn else extra.muted,
                )
                if (bell.pushOff) {
                    // Small warning dot: nothing arrives until notifications are switched on.
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 10.dp, end = 10.dp)
                            .size(9.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(extra.warn),
                    )
                } else if (unreadCount > 0) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 6.dp, end = 4.dp)
                            .height(18.dp)
                            .widthIn(min = 18.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (unreadCount > 99) "99+" else unreadCount.toString(),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                        )
                    }
                }
            }
        }
    }
    val bannerSection: @Composable ColumnScope.() -> Unit = {
        // --- Connection failed: the message and what to do next ------------
        val st = tunnelState
        if (st is TunnelState.Error) {
            GcBanner(tone = GcTone.Error, icon = GcIcons.ShieldX) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GcSecondaryButton(
                            text = stringResource(R.string.vpn_retry),
                            onClick = startConnect,
                            fillWidth = false,
                        )
                        GcOutlineButton(
                            text = stringResource(R.string.vpn_logs),
                            onClick = onOpenLogs,
                            fillWidth = false,
                            minHeight = 40.dp,
                        )
                    }
                    GcOutlineButton(
                        text = stringResource(R.string.diag_home_check),
                        onClick = onOpenDiagnosis,
                        icon = GcIcons.Activity,
                        fillWidth = false,
                        minHeight = 40.dp,
                    )
                }
            }
        }

        // --- Location sharing: progress / result of the last tap ----------
        if (locationEnabled) {
            LocationShareBanner(
                state = locationState,
                host = host,
                onDismiss = locationViewModel::dismiss,
                onOpenSettings = onOpenLocationSettings,
            )
        }

        // --- Notifications are off ---------------------------------------
        if (bell.showHint) {
            GcBanner(tone = GcTone.Warn, icon = GcIcons.BellOff) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.push_start_hint_title),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(if (bell.blocked) R.string.push_start_hint_blocked else R.string.push_start_hint_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    GcSecondaryButton(
                        text = stringResource(R.string.push_inbox_enable),
                        onClick = onEnableNotifications,
                        fillWidth = false,
                    )
                }
                GcIconButton(
                    icon = GcIcons.Close,
                    contentDescription = stringResource(R.string.common_dismiss),
                    onClick = onDismissPushHint,
                    size = 40.dp,
                    iconSize = 18.dp,
                )
            }
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

        // --- Google Play update (Play build) --------------------------------
        if (com.gatecontrol.android.update.PlayUpdater.enabled) {
            com.gatecontrol.android.ui.update.PlayUpdateBanner()
        }
    }
    val quickActionsSection: @Composable ColumnScope.() -> Unit = {
        // --- Quick actions and widgets of the plugins ----------------------
        com.gatecontrol.android.ui.quickactions.QuickActionsCard(onOpenDoor = onOpenDoor)
    }

    // Long press on a free spot of the tab: the menu to set it up.
    var editOpen by rememberSaveable { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val openEdit: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        editOpen = true
    }
    if (editOpen) {
        StartEditSheet(
            onActions = { editOpen = false; onOpenQuickActions() },
            onInfoCard = { editOpen = false; onOpenInfoCardPicker() },
            onDismiss = { editOpen = false },
        )
    }
    val infoSection: @Composable ColumnScope.() -> Unit = {
        // --- Info card: contents the person chose (plugin start tiles) ---
        com.gatecontrol.android.ui.infocard.InfoCard(onEdit = onOpenInfoCardPicker)
    }
    val usageSection: @Composable ColumnScope.() -> Unit = {
        // --- Throughput + data usage: one card, the content slides -------------
        UsageCard(
            rxHistory = rxHistory.toList(),
            txHistory = txHistory.toList(),
            connected = isConnected,
            rxSpeed = stats.rxSpeed,
            txSpeed = stats.txSpeed,
            traffic = trafficUsage.takeIf { permissions.traffic },
        )
    }

    // Phones: one column. Wide windows: quick actions and Info card on the
    // left, usage on the right, split at the hinge of a half-opened foldable.
    // Tabletop posture: header and quick actions above the fold, the cards
    // below it.
    val layout = LocalGcWindowLayout.current
    when {
        layout.horizontalHinge != null -> TabletopHome(
            hinge = layout.horizontalHinge,
            onLongPress = openEdit,
            top = { headerSection(); quickActionsSection() },
            bottom = { bannerSection(); infoSection(); usageSection() },
        )
        layout.twoPane -> Column(Modifier.fillMaxSize().padding(top = 20.dp)) {
            Box(Modifier.padding(horizontal = 24.dp)) { headerSection() }
            GcTwoPane(
                listWidth = 420.dp,
                divider = false,
                list = {
                    HomeColumn(onLongPress = openEdit) { bannerSection(); quickActionsSection(); infoSection() }
                },
                detail = {
                    HomeColumn(onLongPress = openEdit) { usageSection() }
                },
            )
        }
        else -> HomeColumn(Modifier.padding(top = 20.dp), onLongPress = openEdit) {
            headerSection()
            bannerSection()
            quickActionsSection()
            infoSection()
            usageSection()
        }
    }

}

/** A scrolling column of home sections; a long press on its free space calls [onLongPress]. */
@Composable
private fun HomeColumn(
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val label = stringResource(R.string.start_edit_title)
    Column(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (onLongPress != null) {
                    Modifier
                        .pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) }
                        .semantics { onLongClick(label = label) { onLongPress(); true } }
                } else {
                    Modifier
                },
            )
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

/**
 * Half-opened foldable lying flat (tabletop): header and quick actions on
 * the upper half, the cards scroll on the lower half.
 */
@Composable
private fun TabletopHome(
    hinge: HingeSplit,
    onLongPress: () -> Unit,
    top: @Composable ColumnScope.() -> Unit,
    bottom: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    var startY by remember { mutableFloatStateOf(0f) }
    Column(Modifier.fillMaxSize().onGloballyPositioned { startY = it.positionInWindow().y }) {
        val topHeight = with(density) { (hinge.startPx - startY).coerceAtLeast(0f).toDp() }
        val gap = with(density) { (hinge.endPx - hinge.startPx).coerceAtLeast(0f).toDp() }
        HomeColumn(Modifier.height(topHeight).padding(top = 12.dp), onLongPress = onLongPress, content = top)
        Spacer(Modifier.height(gap))
        HomeColumn(Modifier.weight(1f).padding(top = 12.dp), onLongPress = onLongPress, content = bottom)
    }
}
