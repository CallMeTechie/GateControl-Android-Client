package com.gatecontrol.android.ui.network

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gatecontrol.android.R
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.ui.components.GcScreenTitle
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.dnsprotect.DnsProtectContent
import com.gatecontrol.android.ui.rdp.RdpContent
import com.gatecontrol.android.ui.services.ServicesContent
import com.gatecontrol.android.ui.vpn.VpnViewModel

const val NETWORK_TAB_SERVICES = NetworkTabs.SERVICES
const val NETWORK_TAB_DNS = NetworkTabs.DNS
const val NETWORK_TAB_REMOTE = NetworkTabs.REMOTE

/** Segments of the Netzwerk tab (pure, unit-tested). */
object NetworkTabs {
    const val SERVICES = "services"
    /** DNS protection of the server (plugin gatecontrol-dns), where the server reports it. */
    const val DNS = "dns"
    /** RDP hosts; until 1.35 the own bottom tab "Remote". */
    const val REMOTE = "remote"

    /** Old name of the Remote segment (the former tab's route `rdp`). */
    private const val LEGACY_REMOTE = "rdp"

    /** Segment of the removed Pi-hole link (a stored default, an old link); DNS-Schutz stands in its place. */
    private const val LEGACY_PIHOLE = "pihole"

    /**
     * The segments the token may use, in order: Dienste, DNS-Schutz,
     * Remote. [dns]: the server reports a DNS protection.
     */
    fun available(services: Boolean, remote: Boolean, dns: Boolean = false): List<String> = buildList {
        if (services) add(SERVICES)
        if (dns) add(DNS)
        if (remote) add(REMOTE)
    }

    /** A requested segment under its current name (the old `rdp` is Remote, the old `pihole` is DNS-Schutz). */
    fun normalize(tab: String?): String? = when (tab) {
        LEGACY_REMOTE -> REMOTE
        LEGACY_PIHOLE -> DNS
        else -> tab
    }

    /** [tab] when [available] has it. */
    private fun present(tab: String?, available: List<String>): String? = tab?.takeIf { it in available }

    /**
     * The segment to show: the requested one when available, else the
     * stored [default] (long press on a segment) when available, else the first.
     */
    fun initial(requested: String?, available: List<String>, default: String? = null): String? =
        present(normalize(requested), available)
            ?: present(normalize(default), available)
            ?: available.firstOrNull()
}

/**
 * "Netzwerk" tab: services, the server's DNS protection and the RDP hosts ("Remote", until
 * 1.35 an own tab) behind a segmented switch. Shows only the parts the
 * token is allowed to use; the switch disappears when only one is. A long
 * press on a segment makes it the one the tab opens with.
 */
@Composable
fun NetworkScreen(
    hasServices: Boolean,
    hasRemote: Boolean,
    initialTab: String?,
    /** The server reports a DNS protection: the segment "DNS-Schutz". */
    hasDnsProtect: Boolean = false,
    onGoToStart: () -> Unit,
    vpnViewModel: VpnViewModel = hiltViewModel(),
    tabsViewModel: NetworkTabsViewModel = hiltViewModel(),
) {
    val tunnelState by vpnViewModel.tunnelState.collectAsState()
    val default by tabsViewModel.default.collectAsState()
    val context = LocalContext.current
    val keys = NetworkTabs.available(services = hasServices, remote = hasRemote, dns = hasDnsProtect)
    val labels = mapOf(
        NetworkTabs.SERVICES to stringResource(R.string.nav_services),
        NetworkTabs.DNS to stringResource(R.string.nav_dns_protect),
        NetworkTabs.REMOTE to stringResource(R.string.nav_remote),
    )
    val available = keys.map { it to labels.getValue(it) }
    // The person's choice on this screen; before one, the requested or stored segment.
    var tab by rememberSaveable(initialTab) { mutableStateOf<String?>(null) }
    val current = tab?.takeIf { it in keys } ?: when (val d = default) {
        NetworkDefault.Loading -> null
        is NetworkDefault.Known -> NetworkTabs.initial(initialTab, keys, d.tab)
    }
    val defaultSet = stringResource(R.string.pager_favorite_set)

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GcScreenTitle(stringResource(R.string.nav_network), Modifier.weight(1f))
                // "⋯" of the page when Netzwerk is pinned to Start.
                com.gatecontrol.android.ui.start.StartPageMenuButton()
            }
            if (available.size > 1 && current != null) {
                GcSegmented(
                    options = available,
                    selected = current,
                    onSelect = { tab = it },
                    onLongPress = {
                        tabsViewModel.setDefault(it)
                        Toast.makeText(context, defaultSet, Toast.LENGTH_SHORT).show()
                    },
                    onLongPressLabel = stringResource(R.string.pager_favorite_action),
                )
            }
        }
        when (current) {
            NetworkTabs.SERVICES -> ServicesContent(
                vpnConnected = tunnelState is TunnelState.Connected,
                onConnect = onGoToStart,
                modifier = Modifier.weight(1f),
            )
            NetworkTabs.DNS -> DnsProtectContent(modifier = Modifier.weight(1f))
            NetworkTabs.REMOTE -> RdpContent(modifier = Modifier.weight(1f))
            else -> Unit
        }
    }
}
