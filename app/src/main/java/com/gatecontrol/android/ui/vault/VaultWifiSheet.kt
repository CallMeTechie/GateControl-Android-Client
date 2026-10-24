package com.gatecontrol.android.ui.vault

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.vault.NearbyWifi
import com.gatecontrol.android.vault.VaultWifiScanner
import com.gatecontrol.android.vault.VaultWifiScanner.State

private fun Context.openSettings(action: String, withPackage: Boolean = false) {
    runCatching {
        startActivity(
            Intent(action).apply { if (withPackage) data = "package:$packageName".toUri() }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * "WLAN in der Nähe" in the WLAN editor: scans, lists the networks in range (connected
 * one first, then by signal) and hands the picked one to [onPick]. Asks for the location
 * permission first when needed (Android only shows scan results with it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultWifiSheet(scanner: VaultWifiScanner, onDismiss: () -> Unit, onPick: (NearbyWifi) -> Unit) {
    val x = GateControlTheme.extraColors
    val context = LocalContext.current
    val open = stringResource(R.string.vault_wifi_open)
    // a new value scans again (button, granted permission, back from the settings)
    var attempt by remember { mutableIntStateOf(0) }
    var denied by remember { mutableStateOf(false) }
    val state by remember(attempt) { scanner.scan(open) }.collectAsState(initial = State.Results(emptyList(), scanning = true))
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val activity = context as? Activity
        denied = scanner.blocker() == State.NoPermission && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
        attempt++
    }
    LifecycleResumeEffect(Unit) {
        if (state !is State.Results) attempt++
        onPauseOrDispose { }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.vault_wifi_nearby_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                if (state is State.Results) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(x.panel2).border(1.dp, x.border2, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        GcIconButton(GcIcons.Refresh, stringResource(R.string.vault_wifi_scan_again), onClick = { attempt++ }, size = 44.dp, tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            when (val s = state) {
                State.NoPermission -> if (denied) {
                    Text(stringResource(R.string.vault_wifi_perm_denied), style = MaterialTheme.typography.bodyMedium, color = x.muted)
                    GcPrimaryButton(stringResource(R.string.vault_wifi_open_settings), onClick = { context.openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPackage = true) }, minHeight = 52.dp)
                } else {
                    Text(stringResource(R.string.vault_wifi_perm_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.vault_wifi_perm_explain), style = MaterialTheme.typography.bodyMedium, color = x.muted)
                    GcPrimaryButton(stringResource(R.string.trusted_perm_continue), onClick = {
                        permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }, minHeight = 52.dp)
                }
                State.LocationOff -> {
                    Text(stringResource(R.string.vault_wifi_location_off), style = MaterialTheme.typography.bodyMedium, color = x.muted)
                    GcPrimaryButton(stringResource(R.string.vault_wifi_open_settings), onClick = { context.openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS) }, minHeight = 52.dp)
                }
                State.WifiOff -> {
                    Text(stringResource(R.string.vault_wifi_off), style = MaterialTheme.typography.bodyMedium, color = x.muted)
                    GcPrimaryButton(stringResource(R.string.vault_wifi_open_settings), onClick = { context.openSettings(Settings.Panel.ACTION_WIFI) }, minHeight = 52.dp)
                }
                is State.Results -> {
                    if (s.networks.isEmpty()) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (s.scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                            Text(stringResource(if (s.scanning) R.string.vault_wifi_scanning else R.string.vault_wifi_none), style = MaterialTheme.typography.bodyMedium, color = x.muted)
                        }
                    } else {
                        VaultRowsCard {
                            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                                itemsIndexed(s.networks, key = { _, n -> n.ssid }) { i, n ->
                                    if (i > 0) GcRowDivider()
                                    WifiRow(n, onClick = { onPick(n) })
                                }
                            }
                        }
                        if (s.scanning) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                                Text(stringResource(R.string.vault_wifi_scanning), style = MaterialTheme.typography.bodySmall, color = x.muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WifiRow(n: NearbyWifi, onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    val signal = stringResource(R.string.vault_wifi_signal, n.level)
    GcListRow(
        title = n.ssid,
        description = "${n.security} · $signal",
        onClick = onClick,
        leading = {
            Icon(
                GcIcons.Wifi, contentDescription = null,
                tint = if (n.connected) x.accentText else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp).alpha(0.35f + 0.65f * n.level / 4f),
            )
        },
        trailing = {
            if (n.connected) {
                Text(
                    stringResource(R.string.vault_wifi_connected), style = MaterialTheme.typography.labelMedium, color = x.accentText,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(x.accentBg).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        },
    )
}
