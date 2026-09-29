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
import com.gatecontrol.android.ui.services.ServicesContent
import com.gatecontrol.android.ui.vpn.VpnViewModel

const val NETWORK_TAB_SERVICES = "services"
const val NETWORK_TAB_PIHOLE = "pihole"

/**
 * "Netzwerk" tab: services and Pi-hole behind a segmented switch. Shows only
 * the parts the token is allowed to use; the switch disappears when only one is.
 */
@Composable
fun NetworkScreen(
    hasServices: Boolean,
    hasPihole: Boolean,
    initialTab: String?,
    onGoToStart: () -> Unit,
    vpnViewModel: VpnViewModel = hiltViewModel(),
) {
    val tunnelState by vpnViewModel.tunnelState.collectAsState()
    val available = buildList {
        if (hasServices) add(NETWORK_TAB_SERVICES to stringResource(R.string.nav_services))
        if (hasPihole) add(NETWORK_TAB_PIHOLE to stringResource(R.string.nav_pihole))
    }
    var tab by rememberSaveable(initialTab) {
        mutableStateOf(initialTab?.takeIf { t -> available.any { it.first == t } } ?: available.firstOrNull()?.first)
    }
    val current = tab?.takeIf { t -> available.any { it.first == t } } ?: available.firstOrNull()?.first

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
            NETWORK_TAB_SERVICES -> ServicesContent(
                vpnConnected = tunnelState is TunnelState.Connected,
                onConnect = onGoToStart,
                modifier = Modifier.weight(1f),
            )
            NETWORK_TAB_PIHOLE -> PiholeContent(modifier = Modifier.weight(1f))
            else -> Unit
        }
    }
}
