package com.gatecontrol.android.ui.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gatecontrol.android.R
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.ui.components.GcScreenTitle
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.pihole.PiholeContent
import com.gatecontrol.android.ui.rdp.RdpContent
import com.gatecontrol.android.ui.services.ServicesContent
import com.gatecontrol.android.ui.vpn.VpnViewModel

const val NETWORK_TAB_SERVICES = NetworkTabs.SERVICES
const val NETWORK_TAB_PIHOLE = NetworkTabs.PIHOLE
const val NETWORK_TAB_REMOTE = NetworkTabs.REMOTE

/** Segments of the Netzwerk tab (pure, unit-tested). */
object NetworkTabs {
    const val SERVICES = "services"
    const val PIHOLE = "pihole"
    /** RDP hosts; until 1.35 the own bottom tab "Remote". */
    const val REMOTE = "remote"

    /** Old name of the Remote segment (the former tab's route `rdp`). */
    private const val LEGACY_REMOTE = "rdp"

    /** The segments the token may use, in order: Dienste, Pi-hole, Remote. */
    fun available(services: Boolean, pihole: Boolean, remote: Boolean): List<String> = buildList {
        if (services) add(SERVICES)
        if (pihole) add(PIHOLE)
        if (remote) add(REMOTE)
    }

    /** A requested segment under its current name (the old `rdp` is Remote). */
    fun normalize(tab: String?): String? = if (tab == LEGACY_REMOTE) REMOTE else tab

    /** The segment to show: the requested one when available, else the first. */
    fun initial(requested: String?, available: List<String>): String? =
        normalize(requested)?.takeIf { it in available } ?: available.firstOrNull()
}

/**
 * "Netzwerk" tab: services, Pi-hole and the RDP hosts ("Remote", until
 * 1.35 an own tab) behind a segmented switch. Shows only the parts the
 * token is allowed to use; the switch disappears when only one is.
 */
@Composable
fun NetworkScreen(
    hasServices: Boolean,
    hasPihole: Boolean,
    hasRemote: Boolean,
    initialTab: String?,
    onGoToStart: () -> Unit,
    vpnViewModel: VpnViewModel = hiltViewModel(),
) {
    val tunnelState by vpnViewModel.tunnelState.collectAsState()
    val keys = NetworkTabs.available(services = hasServices, pihole = hasPihole, remote = hasRemote)
    val labels = mapOf(
        NetworkTabs.SERVICES to stringResource(R.string.nav_services),
        NetworkTabs.PIHOLE to stringResource(R.string.nav_pihole),
        NetworkTabs.REMOTE to stringResource(R.string.nav_remote),
    )
    val available = keys.map { it to labels.getValue(it) }
    var tab by rememberSaveable(initialTab) { mutableStateOf(NetworkTabs.initial(initialTab, keys)) }
    val current = tab?.takeIf { it in keys } ?: keys.firstOrNull()

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GcScreenTitle(stringResource(R.string.nav_network))
            if (available.size > 1 && current != null) {
                GcSegmented(options = available, selected = current, onSelect = { tab = it })
            }
        }
        when (current) {
            NetworkTabs.SERVICES -> ServicesContent(
                vpnConnected = tunnelState is TunnelState.Connected,
                onConnect = onGoToStart,
                modifier = Modifier.weight(1f),
            )
            NetworkTabs.PIHOLE -> PiholeContent(modifier = Modifier.weight(1f))
            NetworkTabs.REMOTE -> RdpContent(modifier = Modifier.weight(1f))
            else -> Unit
        }
    }
}
