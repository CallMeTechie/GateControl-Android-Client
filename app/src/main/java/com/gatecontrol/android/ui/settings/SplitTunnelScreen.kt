package com.gatecontrol.android.ui.settings

import com.gatecontrol.android.common.SplitTunnelMode
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.util.WifiSubnetDetector

/**
 * Split-Tunneling page: mode as radio cards (off / exceptions / only
 * selected), then networks and apps. Changes are saved immediately and take
 * effect on the next connect.
 */
@Composable
fun SplitTunnelScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val extra = GateControlTheme.extraColors
    var showAppPicker by remember { mutableStateOf(false) }
    val wifiSubnet = remember { WifiSubnetDetector.detect(context) }
    val policy = uiState.policy
    // Admin preset lock (server split-tunnel preset) or client policy lock.
    val locked = uiState.splitTunnelAdminLocked || policy.splitTunnelFrozen
    val appsLocked = policy.lockSettings
    @Composable
    fun modeDesc(mode: SplitTunnelMode, default: String): String =
        if (policy.isModeAllowed(mode)) default else stringResource(R.string.policy_split_mode_not_allowed)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        GcSubpageBar(
            title = stringResource(R.string.settings_split_tunnel),
            onBack = onBack,
            backDescription = stringResource(R.string.common_back),
        )
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (policy.splitTunnelFrozen || policy.splitTunnelModes.size < SplitTunnelMode.entries.size) {
                com.gatecontrol.android.ui.components.GcPolicyLockedHint(Modifier.padding(horizontal = 4.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeCard(
                    title = stringResource(R.string.split_tunnel_mode_off),
                    desc = modeDesc(SplitTunnelMode.OFF, stringResource(R.string.split_tunnel_off_desc)),
                    selected = uiState.splitTunnelMode == SplitTunnelMode.OFF,
                    enabled = !locked && policy.isModeAllowed(SplitTunnelMode.OFF),
                    onSelect = { viewModel.setSplitTunnelMode(SplitTunnelMode.OFF) },
                )
                ModeCard(
                    title = stringResource(R.string.split_tunnel_mode_exclude),
                    desc = modeDesc(SplitTunnelMode.EXCLUDE, stringResource(R.string.split_tunnel_exclude_label)),
                    selected = uiState.splitTunnelMode == SplitTunnelMode.EXCLUDE,
                    enabled = !locked && policy.isModeAllowed(SplitTunnelMode.EXCLUDE),
                    onSelect = { viewModel.setSplitTunnelMode(SplitTunnelMode.EXCLUDE) },
                )
                ModeCard(
                    title = stringResource(R.string.split_tunnel_mode_include),
                    desc = modeDesc(SplitTunnelMode.INCLUDE, stringResource(R.string.split_tunnel_include_label)),
                    selected = uiState.splitTunnelMode == SplitTunnelMode.INCLUDE,
                    enabled = !locked && policy.isModeAllowed(SplitTunnelMode.INCLUDE),
                    onSelect = { viewModel.setSplitTunnelMode(SplitTunnelMode.INCLUDE) },
                )
            }

            if (uiState.splitTunnelMode != SplitTunnelMode.OFF) {
                val exclude = uiState.splitTunnelMode == SplitTunnelMode.EXCLUDE
                GcSectionLabel(
                    stringResource(
                        if (exclude) R.string.split_tunnel_networks_exclude_header
                        else R.string.split_tunnel_networks_include_header,
                    ),
                    Modifier.padding(horizontal = 4.dp),
                )
                NetworkPresetsSection(
                    networks = uiState.splitTunnelNetworks,
                    wifiSubnet = wifiSubnet,
                    adminLocked = locked,
                    onNetworksChanged = { viewModel.setSplitTunnelNetworks(it) },
                )

                GcSectionLabel(
                    stringResource(
                        if (exclude) R.string.split_tunnel_apps_exclude_header
                        else R.string.split_tunnel_apps_include_header,
                    ),
                    Modifier.padding(horizontal = 4.dp),
                )
                GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                    val pm = context.packageManager
                    uiState.splitTunnelAppsV2.forEach { pkg ->
                        val appLabel = remember(pkg) {
                            try { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() } catch (_: Exception) { pkg }
                        }
                        val appIcon = remember(pkg) {
                            try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }
                        }
                        GcListRow(
                            title = appLabel,
                            leading = {
                                if (appIcon != null) {
                                    Image(
                                        bitmap = appIcon.toBitmap(80, 80).asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.size(40.dp).clip(MaterialTheme.shapes.small),
                                    )
                                } else {
                                    GcIconSquare(size = 40.dp) {
                                        Text(appLabel.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = extra.muted)
                                    }
                                }
                            },
                            trailing = {
                                if (!appsLocked) {
                                    GcIconButton(
                                        icon = GcIcons.Close,
                                        contentDescription = stringResource(R.string.common_remove_named, appLabel),
                                        onClick = { viewModel.setSplitTunnelAppsV2(uiState.splitTunnelAppsV2 - pkg) },
                                        iconSize = 18.dp,
                                    )
                                }
                            },
                        )
                    }
                    if (!appsLocked) {
                        GcListRow(
                            title = stringResource(R.string.split_tunnel_pick_apps),
                            titleColor = extra.accentText,
                            onClick = { showAppPicker = true },
                            leading = { Icon(GcIcons.Plus, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(20.dp)) },
                        )
                    }
                }
            }

            Text(
                stringResource(R.string.split_tunnel_reconnect_hint),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    if (showAppPicker) {
        AppPickerSheet(
            selectedPackages = uiState.splitTunnelAppsV2.toSet(),
            onDismiss = { selected ->
                viewModel.setSplitTunnelAppsV2(selected.toList())
                showAppPicker = false
            },
        )
    }
}

@Composable
private fun ModeCard(
    title: String,
    desc: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val extra = GateControlTheme.extraColors
    val accent = MaterialTheme.colorScheme.primary
    val border = if (selected) accent else extra.border
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, border, MaterialTheme.shapes.medium)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 1.dp)
                .size(22.dp)
                .clip(CircleShape)
                .border(2.dp, if (selected) accent else extra.border2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (selected) accent else Color.Transparent))
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = extra.muted)
        }
    }
}
