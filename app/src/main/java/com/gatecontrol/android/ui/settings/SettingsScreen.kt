package com.gatecontrol.android.ui.settings

import com.gatecontrol.android.common.SplitTunnelMode
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.util.openSystemVpnSettings
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPolicyLockedHint
import com.gatecontrol.android.ui.components.GcPolicyManagedBanner
import com.gatecontrol.android.ui.components.GcPolicySystemVpnCard
import com.gatecontrol.android.ui.components.GcScreenTitle
import com.gatecontrol.android.ui.components.GcSecondaryButton
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily

/**
 * Settings overview: status card of the server connection, then grouped
 * cards (Verbindung, Sicherheit, Darstellung, Lizenz, Hilfe). Server setup
 * and split tunneling open as their own pages.
 */
@Composable
fun SettingsScreen(
    onNavigateToLogs: () -> Unit,
    onNavigateToServer: () -> Unit,
    onNavigateToSplitTunnel: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val extra = GateControlTheme.extraColors

    val versionName = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: uiState.appVersion
    val host = uiState.serverUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GcScreenTitle(stringResource(R.string.settings_title))

        // --- Status card ---------------------------------------------------
        GcCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GcIconSquare(background = extra.accentBg) {
                    Icon(GcIcons.ShieldCheck, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        host.ifBlank { stringResource(R.string.settings_server_not_configured) },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        if (uiState.peerId > 0) stringResource(R.string.settings_registered_peer, uiState.peerId)
                        else stringResource(R.string.settings_registered),
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.muted,
                    )
                }
                GcChip(
                    text = if (uiState.isPro) stringResource(R.string.settings_license_pro)
                           else stringResource(R.string.settings_license_community),
                    tone = if (uiState.isPro) GcTone.Pro else GcTone.Neutral,
                )
            }
        }

        // --- Client policy ----------------------------------------------
        val policy = uiState.policy
        GcPolicyManagedBanner(policy)
        GcPolicySystemVpnCard(policy)

        // --- Verbindung ----------------------------------------------------
        SettingsGroup(stringResource(R.string.settings_group_connection)) {
            if (policy.lockServer) {
                GcListRow(
                    title = stringResource(R.string.settings_server_row),
                    description = stringResource(R.string.policy_server_locked),
                    trailing = { Icon(GcIcons.Lock, contentDescription = null, tint = extra.muted, modifier = Modifier.size(18.dp)) },
                )
            } else {
                GcListRow(
                    title = stringResource(R.string.settings_server_row),
                    description = stringResource(R.string.settings_server_row_desc, host.ifBlank { "—" }),
                    onClick = onNavigateToServer,
                    trailing = { GcChevron() },
                )
            }
            GcSwitchRow(
                label = stringResource(R.string.settings_auto_connect),
                description = stringResource(R.string.settings_auto_connect_desc),
                checked = policy.forcedAutoConnect ?: uiState.autoConnect,
                onCheckedChange = { viewModel.setAutoConnect(it) },
                enabled = !policy.autoConnectLocked,
            )
            if (policy.autoConnectLocked) {
                GcPolicyLockedHint(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
            }
            GcListRow(
                title = stringResource(R.string.settings_split_tunnel),
                description = splitSummary(uiState),
                onClick = onNavigateToSplitTunnel,
                trailing = { GcChevron() },
            )
        }

        // --- Sicherheit ----------------------------------------------------
        SettingsGroup(stringResource(R.string.settings_group_security)) {
            // Android has no app-side kill switch: blocking traffic without
            // VPN is done by the system ("Always-on VPN" + "Block connections
            // without VPN"). The row explains that and opens those settings.
            GcListRow(
                title = stringResource(R.string.vpn_kill_switch),
                description = stringResource(R.string.vpn_kill_switch_desc),
                onClick = { context.openSystemVpnSettings() },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.vpn_kill_switch_system),
                            style = MaterialTheme.typography.bodySmall,
                            color = extra.muted,
                        )
                        GcChevron()
                    }
                },
            )
        }

        // --- Darstellung ---------------------------------------------------
        SettingsGroup(stringResource(R.string.settings_group_appearance)) {
            SegmentRow(stringResource(R.string.settings_theme)) {
                GcSegmented(
                    options = listOf(
                        "system" to stringResource(R.string.settings_theme_system),
                        "light" to stringResource(R.string.settings_theme_light),
                        "dark" to stringResource(R.string.settings_theme_dark),
                    ),
                    selected = uiState.theme,
                    onSelect = { viewModel.setTheme(it) },
                )
            }
            SegmentRow(stringResource(R.string.settings_language)) {
                GcSegmented(
                    options = listOf("de" to "Deutsch", "en" to "English"),
                    selected = uiState.locale,
                    onSelect = { viewModel.setLocale(it) },
                )
            }
        }

        // --- Lizenz --------------------------------------------------------
        SettingsGroup(stringResource(R.string.settings_license)) {
            GcListRow(
                title = if (uiState.isPro) stringResource(R.string.settings_license_pro)
                        else stringResource(R.string.settings_license_community),
                description = stringResource(R.string.settings_license_managed),
                onClick = { viewModel.refreshLicense() },
                trailing = {
                    Text(
                        if (uiState.isPro) stringResource(R.string.settings_license_refresh)
                        else stringResource(R.string.settings_license_activate),
                        style = MaterialTheme.typography.titleSmall,
                        color = extra.accentText,
                    )
                },
            )
        }

        // --- Hilfe ---------------------------------------------------------
        SettingsGroup(stringResource(R.string.settings_group_help)) {
            GcListRow(
                title = stringResource(R.string.settings_logs_view),
                description = stringResource(R.string.settings_logs_desc),
                onClick = onNavigateToLogs,
                trailing = { GcChevron() },
            )
            GcListRow(
                title = stringResource(R.string.settings_logs_export),
                onClick = { viewModel.exportLogs(context.cacheDir) },
                trailing = { Icon(GcIcons.Share, contentDescription = null, tint = extra.faint, modifier = Modifier.size(20.dp)) },
            )
            // "Support-Paket senden": confirmation dialog → redacted bundle → server
            GcListRow(
                title = stringResource(R.string.support_send),
                description = when {
                    uiState.supportSending -> stringResource(R.string.support_sending)
                    uiState.supportRequested -> stringResource(R.string.support_requested)
                    else -> stringResource(R.string.support_send_desc)
                },
                titleColor = if (uiState.supportRequested) extra.accentText else MaterialTheme.colorScheme.onSurface,
                onClick = if (uiState.supportSending) null else { { viewModel.requestSupportBundle() } },
                trailing = { Icon(GcIcons.Logs, contentDescription = null, tint = extra.faint, modifier = Modifier.size(20.dp)) },
            )
            UpdateRow(
                versionName = versionName,
                uiState = uiState,
                onCheck = { viewModel.checkForUpdate(versionName) },
                onInstall = { url ->
                    // Updates are downloaded in the browser — only ever hand
                    // it an https URL from the server's update response.
                    if (url.trim().startsWith("https://", ignoreCase = true)) {
                        runCatching {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url.trim())))
                        }
                    }
                },
                onLater = { viewModel.dismissUpdate() },
            )
        }

        if (uiState.supportDialogVisible) {
            SupportBundleDialog(
                host = host,
                requestedByAdmin = uiState.supportRequested,
                onSend = { viewModel.sendSupportBundle(versionName) },
                onDismiss = { viewModel.dismissSupportDialog() },
            )
        }

        // Result of the upload as a toast (resolved in the app language).
        val supportMessage = uiState.supportMessage?.asString()
        LaunchedEffect(supportMessage) {
            if (supportMessage != null) {
                android.widget.Toast.makeText(context, supportMessage, android.widget.Toast.LENGTH_LONG).show()
                viewModel.consumeSupportMessage()
            }
        }

        uiState.error?.let { error ->
            Text(
                text = error.asString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Text(
            text = stringResource(R.string.settings_footer, versionName),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
            color = extra.faint,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        if (uiState.deviceIdShort.isNotEmpty()) {
            // Matches the server's "Gebunden an Gerät ab12cd34…" for machine binding.
            Text(
                text = stringResource(R.string.settings_device_id, uiState.deviceIdShort),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                color = extra.faint,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun splitSummary(ui: SettingsUiState): String = when (ui.splitTunnelMode) {
    SplitTunnelMode.EXCLUDE -> stringResource(R.string.settings_split_summary_exclude, ui.splitTunnelNetworks.size, ui.splitTunnelAppsV2.size)
    SplitTunnelMode.INCLUDE -> stringResource(R.string.settings_split_summary_include, ui.splitTunnelNetworks.size, ui.splitTunnelAppsV2.size)
    SplitTunnelMode.OFF -> stringResource(R.string.settings_split_summary_off)
}

@Composable
internal fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        GcSectionLabel(title, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
            content()
        }
    }
}

@Composable
private fun SegmentRow(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        content()
    }
}

@Composable
private fun UpdateRow(
    versionName: String,
    uiState: SettingsUiState,
    onCheck: () -> Unit,
    onInstall: (String) -> Unit,
    onLater: () -> Unit,
) {
    val info = uiState.updateInfo
    val desc = when {
        info?.available == true -> stringResource(R.string.settings_update_available, info.version ?: "")
        info != null -> stringResource(R.string.settings_version, versionName) + " · " + stringResource(R.string.settings_update_none)
        else -> stringResource(R.string.settings_version, versionName)
    }
    GcListRow(
        title = stringResource(R.string.settings_updates),
        description = desc,
        onClick = if (uiState.isLoading) null else onCheck,
        trailing = { Icon(GcIcons.Refresh, contentDescription = null, tint = GateControlTheme.extraColors.faint, modifier = Modifier.size(20.dp)) },
    )
    if (info?.available == true) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GcOutlineButton(
                text = stringResource(R.string.settings_update_install),
                onClick = { info.downloadUrl?.let(onInstall) },
                modifier = Modifier.weight(1f),
                minHeight = 40.dp,
            )
            GcSecondaryButton(
                text = stringResource(R.string.settings_update_later),
                onClick = onLater,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** "Diagnosedaten an <server> senden?" — lists what is (not) included. */
@Composable
private fun SupportBundleDialog(
    host: String,
    requestedByAdmin: Boolean,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(R.string.support_dialog_title, host), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (requestedByAdmin) {
                    Text(stringResource(R.string.support_dialog_admin), style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.accentText)
                }
                Text(stringResource(R.string.support_dialog_includes), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.support_dialog_excludes), style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.muted)
            }
        },
        confirmButton = { TextButton(onClick = onSend) { Text(stringResource(R.string.support_dialog_send)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.support_dialog_cancel)) } },
    )
}
