package com.gatecontrol.android.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R

data class NetworkEntry(val cidr: String, val label: String)

private val PRIVATE_NETS = listOf(
    NetworkEntry("10.0.0.0/8", "Private 10.x"),
    NetworkEntry("172.16.0.0/12", "Private 172.x"),
    NetworkEntry("192.168.0.0/16", "Private 192.x"),
)
private val LINK_LOCAL = NetworkEntry("169.254.0.0/16", "Link-Local")

@Composable
fun NetworkPresetsSection(
    networks: List<NetworkEntry>,
    wifiSubnet: String?,      // null if not on WiFi
    adminLocked: Boolean,
    onNetworksChanged: (List<NetworkEntry>) -> Unit,
) {
    if (adminLocked) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.split_tunnel_admin_locked), style = MaterialTheme.typography.bodySmall)
        }
    }

    // Derived state: which presets are active
    val activeCidrs = remember(networks) { networks.map { it.cidr }.toSet() }
    val hasPrivate = PRIVATE_NETS.all { it.cidr in activeCidrs }
    val hasLinkLocal = LINK_LOCAL.cidr in activeCidrs
    val hasWifi = wifiSubnet != null && wifiSubnet in activeCidrs
    val customNets = remember(networks) {
        val presetCidrs = PRIVATE_NETS.map { it.cidr }.toSet() + LINK_LOCAL.cidr + (wifiSubnet ?: "")
        networks.filter { it.cidr !in presetCidrs }
    }

    // Preset checkboxes
    Text(stringResource(R.string.split_tunnel_presets_label), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = hasPrivate, onCheckedChange = { checked ->
            if (adminLocked) return@Checkbox
            val newNets = if (checked) networks + PRIVATE_NETS.filter { it.cidr !in activeCidrs }
                else networks.filter { it.cidr !in PRIVATE_NETS.map { p -> p.cidr }.toSet() }
            onNetworksChanged(newNets)
        }, enabled = !adminLocked)
        Text(stringResource(R.string.split_tunnel_private_nets), style = MaterialTheme.typography.bodyMedium)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = hasLinkLocal, onCheckedChange = { checked ->
            if (adminLocked) return@Checkbox
            onNetworksChanged(if (checked) networks + LINK_LOCAL else networks.filter { it.cidr != LINK_LOCAL.cidr })
        }, enabled = !adminLocked)
        Text("Link-Local (169.254.0.0/16)", style = MaterialTheme.typography.bodyMedium)
    }

    if (wifiSubnet != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = hasWifi, onCheckedChange = { checked ->
                if (adminLocked) return@Checkbox
                val entry = NetworkEntry(wifiSubnet, "WiFi ($wifiSubnet)")
                onNetworksChanged(if (checked) networks + entry else networks.filter { it.cidr != wifiSubnet })
            }, enabled = !adminLocked)
            Text(stringResource(R.string.split_tunnel_wifi_subnet, wifiSubnet), style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = false, onCheckedChange = {}, enabled = false)
            Text(stringResource(R.string.split_tunnel_no_wifi), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    // Custom networks
    if (customNets.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.split_tunnel_custom_label), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp))
        customNets.forEach { net ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(net.label, style = MaterialTheme.typography.bodyMedium)
                    Text(net.cidr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!adminLocked) {
                    IconButton(onClick = { onNetworksChanged(networks.filter { it.cidr != net.cidr }) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove")
                    }
                }
            }
        }
    }

    // Add button
    if (!adminLocked) {
        var showDialog by remember { mutableStateOf(false) }
        TextButton(onClick = { showDialog = true }) {
            Text("+ ${stringResource(R.string.split_tunnel_add_network)}")
        }
        if (showDialog) {
            AddNetworkDialog(
                onDismiss = { showDialog = false },
                onAdd = { label, cidr ->
                    onNetworksChanged(networks + NetworkEntry(cidr, label))
                    showDialog = false
                }
            )
        }
    }
}

@Composable
private fun AddNetworkDialog(onDismiss: () -> Unit, onAdd: (label: String, cidr: String) -> Unit) {
    var label by remember { mutableStateOf("") }
    var cidr by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val cidrRegex = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}/\d{1,2}$""")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.split_tunnel_add_network)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = label, onValueChange = { label = it; error = null }, label = { Text(stringResource(R.string.split_tunnel_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = cidr, onValueChange = { cidr = it; error = null }, label = { Text("CIDR") }, placeholder = { Text("z.B. 172.20.0.0/16") }, singleLine = true, modifier = Modifier.fillMaxWidth(), isError = error != null, supportingText = error?.let { { Text(it) } })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (label.isBlank()) { error = "Label required"; return@TextButton }
                if (!cidrRegex.matches(cidr)) { error = "Invalid CIDR"; return@TextButton }
                val prefix = cidr.split("/")[1].toIntOrNull() ?: 0
                if (prefix < 0 || prefix > 32) { error = "Prefix 0-32"; return@TextButton }
                onAdd(label.trim(), cidr.trim())
            }) { Text(stringResource(R.string.split_tunnel_add_network)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}
