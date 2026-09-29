package com.gatecontrol.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme

data class NetworkEntry(val cidr: String, val label: String)

// 10.0.0.0/8 intentionally excluded — the WireGuard VPN subnet (10.8.0.0/24)
// lives there. Users who need it can add it as a custom network.
private val PRIVATE_NETS = listOf(
    NetworkEntry("172.16.0.0/12", "Private 172.x"),
    NetworkEntry("192.168.0.0/16", "Private 192.x"),
)
private val LINK_LOCAL = NetworkEntry("169.254.0.0/16", "Link-Local")

/** Network presets (switch rows) plus custom networks, as one card. */
@Composable
fun NetworkPresetsSection(
    networks: List<NetworkEntry>,
    wifiSubnet: String?,      // null if not on WiFi
    adminLocked: Boolean,
    onNetworksChanged: (List<NetworkEntry>) -> Unit,
) {
    val extra = GateControlTheme.extraColors
    if (adminLocked) {
        GcBanner(tone = GcTone.Info, icon = GcIcons.Lock) {
            Text(stringResource(R.string.split_tunnel_admin_locked), style = MaterialTheme.typography.bodyMedium)
        }
    }

    val activeCidrs = remember(networks) { networks.map { it.cidr }.toSet() }
    val hasPrivate = PRIVATE_NETS.all { it.cidr in activeCidrs }
    val hasLinkLocal = LINK_LOCAL.cidr in activeCidrs
    val hasWifi = wifiSubnet != null && wifiSubnet in activeCidrs
    val customNets = remember(networks) {
        val presetCidrs = PRIVATE_NETS.map { it.cidr }.toSet() + LINK_LOCAL.cidr + (wifiSubnet ?: "")
        networks.filter { it.cidr !in presetCidrs }
    }
    var showDialog by remember { mutableStateOf(false) }

    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
        GcSwitchRow(
            label = stringResource(R.string.split_tunnel_private_nets),
            description = PRIVATE_NETS.joinToString(" · ") { it.cidr },
            checked = hasPrivate,
            enabled = !adminLocked,
            onCheckedChange = { checked ->
                val newNets = if (checked) networks + PRIVATE_NETS.filter { it.cidr !in activeCidrs }
                    else networks.filter { it.cidr !in PRIVATE_NETS.map { p -> p.cidr }.toSet() }
                onNetworksChanged(newNets)
            },
        )
        GcSwitchRow(
            label = "Link-Local",
            description = LINK_LOCAL.cidr,
            checked = hasLinkLocal,
            enabled = !adminLocked,
            onCheckedChange = { checked ->
                onNetworksChanged(if (checked) networks + LINK_LOCAL else networks.filter { it.cidr != LINK_LOCAL.cidr })
            },
        )
        if (wifiSubnet != null) {
            GcSwitchRow(
                label = stringResource(R.string.split_tunnel_wifi_current),
                description = wifiSubnet,
                checked = hasWifi,
                enabled = !adminLocked,
                onCheckedChange = { checked ->
                    val entry = NetworkEntry(wifiSubnet, "WiFi ($wifiSubnet)")
                    onNetworksChanged(if (checked) networks + entry else networks.filter { it.cidr != wifiSubnet })
                },
            )
        } else {
            GcSwitchRow(
                label = stringResource(R.string.split_tunnel_no_wifi),
                description = null,
                checked = false,
                enabled = false,
                onCheckedChange = {},
            )
        }
        customNets.forEach { net ->
            GcListRow(
                title = net.label,
                description = net.cidr,
                descriptionMono = true,
                trailing = if (adminLocked) null else ({
                    GcIconButton(
                        icon = GcIcons.Trash,
                        contentDescription = stringResource(R.string.common_remove_named, net.label),
                        onClick = { onNetworksChanged(networks.filter { it.cidr != net.cidr }) },
                        iconSize = 18.dp,
                    )
                }),
            )
        }
        if (!adminLocked) {
            GcListRow(
                title = stringResource(R.string.split_tunnel_add_network),
                titleColor = extra.accentText,
                onClick = { showDialog = true },
                leading = { Icon(GcIcons.Plus, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(20.dp)) },
            )
        }
    }

    if (showDialog) {
        AddNetworkDialog(
            onDismiss = { showDialog = false },
            onAdd = { label, cidr ->
                onNetworksChanged(networks + NetworkEntry(cidr, label))
                showDialog = false
            },
        )
    }
}

@Composable
private fun AddNetworkDialog(onDismiss: () -> Unit, onAdd: (label: String, cidr: String) -> Unit) {
    var label by remember { mutableStateOf("") }
    var cidr by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val cidrRegex = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}/\d{1,2}$""")
    val labelRequired = stringResource(R.string.split_tunnel_label_required)
    val invalidCidr = stringResource(R.string.split_tunnel_invalid_cidr)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(R.string.split_tunnel_add_network), style = MaterialTheme.typography.headlineMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                GcTextField(
                    value = label,
                    onValueChange = { label = it; error = null },
                    label = stringResource(R.string.split_tunnel_label),
                )
                GcTextField(
                    value = cidr,
                    onValueChange = { cidr = it; error = null },
                    label = "CIDR",
                    placeholder = "172.20.0.0/16",
                    mono = true,
                    error = error,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (label.isBlank()) { error = labelRequired; return@TextButton }
                if (!cidrRegex.matches(cidr.trim())) { error = invalidCidr; return@TextButton }
                val prefix = cidr.trim().split("/")[1].toIntOrNull() ?: -1
                if (prefix < 0 || prefix > 32) { error = invalidCidr; return@TextButton }
                onAdd(label.trim(), cidr.trim())
            }) { Text(stringResource(R.string.split_tunnel_add_network)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
