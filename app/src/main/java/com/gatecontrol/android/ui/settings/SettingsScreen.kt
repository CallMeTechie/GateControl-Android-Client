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
import androidx.compose.ui.res.pluralStringResource
import com.gatecontrol.android.ui.drive.DriveBackupSettingsViewModel
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
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSecondaryButton
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily
import com.gatecontrol.android.notify.PushMode
import com.gatecontrol.android.ui.notifications.NotificationSettingsViewModel
import com.gatecontrol.android.location.LocationShareViewModel
import com.gatecontrol.android.ui.location.locationSharingSummary
import com.gatecontrol.android.ui.profile.MyAvatarViewModel
import com.gatecontrol.android.ui.profile.ProfilePresenceViewModel
import com.gatecontrol.android.ui.profile.ProfileRowAvatar

/**
 * Settings (no tab since 1.35: the gear on the profile opens them, back
 * leads there). Overview: the own profile (picture, favourite colour) first,
 * status card of the server connection, then grouped cards (Verbindung
 * with the connection details, Sicherheit, Benachrichtigungen,
 * Darstellung, Lizenz, Hilfe). Profile, connection details, server setup
 * and split tunneling open as their own pages.
 */
@Composable
fun SettingsScreen(
    /** Back to where the settings were opened (the profile, a hint): no tab since 1.35. */
    onBack: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onNavigateToServer: () -> Unit,
    onNavigateToSplitTunnel: () -> Unit,
    onNavigateToTrustedNetworks: () -> Unit,
    onNavigateToLocation: () -> Unit = {},
    onNavigateToDiagnosis: () -> Unit = {},
    /** Settings → Profil (only when the server has profiles). */
    onNavigateToProfile: () -> Unit = {},
    /** Verbindung → Verbindungsdetails. */
    onNavigateToConnection: () -> Unit = {},
    /** GateDrop "Geteilt"; null hides the row (no GateDrop on the server). */
    onNavigateToShared: (() -> Unit)? = null,
    /** "Drive-Einstellungen" (backup, vaults, data on this phone …); null hides the row (no Drive on the server). */
    onNavigateToDrive: (() -> Unit)? = null,
    /** "Leiste und Seiten" (functions as tabs or as pages beside Start). */
    onNavigateToPages: (() -> Unit)? = null,
    /** Page shown next to the list on wide screens; its row is highlighted. */
    selectedPane: SettingsPane? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
    locationViewModel: LocationShareViewModel = hiltViewModel(),
    pushViewModel: NotificationSettingsViewModel = hiltViewModel(),
    trustedViewModel: TrustedNetworksViewModel = hiltViewModel(),
    avatarViewModel: MyAvatarViewModel = hiltViewModel(),
    presenceViewModel: ProfilePresenceViewModel = hiltViewModel(),
    connectionViewModel: ConnectionDetailsViewModel = hiltViewModel(),
    driveBackupViewModel: DriveBackupSettingsViewModel = hiltViewModel(),
) {
    val myAvatar by avatarViewModel.ui.collectAsStateWithLifecycle()
    val presenceVisible by presenceViewModel.visible.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pushState by pushViewModel.uiState.collectAsStateWithLifecycle()
    val trustedState by trustedViewModel.uiState.collectAsStateWithLifecycle()
    val locationEnabled by locationViewModel.enabled.collectAsStateWithLifecycle()
    val locationServer by locationViewModel.server.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val logsExportTitle = stringResource(R.string.settings_logs_export)
    val extra = GateControlTheme.extraColors

    val versionName = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: uiState.appVersion
    val host = uiState.serverUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        GcSubpageBar(
            title = stringResource(R.string.settings_title),
            onBack = onBack,
            backDescription = stringResource(R.string.common_back),
        )
    Column(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {

        // --- Profil (own picture + ring colour, "Anwesenheit teilen" with the Familie presence module) ---
        if (myAvatar.profileAvailable || presenceVisible) {
            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                GcListRow(
                    title = myAvatar.name ?: stringResource(R.string.profile_title),
                    description = stringResource(
                        when {
                            !myAvatar.profileAvailable -> R.string.familie_presence_share
                            presenceVisible -> R.string.profile_settings_row_desc_presence
                            else -> R.string.profile_settings_row_desc
                        },
                    ),
                    onClick = onNavigateToProfile,
                    leading = { ProfileRowAvatar(myAvatar) },
                    trailing = { GcChevron() },
                    selected = selectedPane == SettingsPane.Profile,
                )
            }
        }

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
            // Tunnel-IP, verbunden seit, letzter Handshake, Server (until 1.31 on the start tab).
            GcListRow(
                title = stringResource(R.string.connection_details_title),
                description = connectionDetailsSummary(connectionViewModel),
                onClick = onNavigateToConnection,
                leading = { Icon(GcIcons.Activity, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(22.dp)) },
                trailing = { GcChevron() },
                selected = selectedPane == SettingsPane.Connection,
            )
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
                    selected = selectedPane == SettingsPane.Server,
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
                selected = selectedPane == SettingsPane.SplitTunnel,
            )
            GcListRow(
                title = stringResource(R.string.settings_trusted_networks),
                description = trustedNetworksSummary(trustedState.settings, trustedState.locked),
                onClick = onNavigateToTrustedNetworks,
                trailing = { GcChevron() },
                selected = selectedPane == SettingsPane.TrustedNetworks,
            )
        }

        // --- Standortfreigabe (location on tap) ------------------------------
        SettingsGroup(stringResource(R.string.location_title)) {
            GcListRow(
                title = stringResource(R.string.location_title),
                description = locationSharingSummary(locationEnabled, locationServer),
                onClick = onNavigateToLocation,
                leading = { Icon(GcIcons.Satellite, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(22.dp)) },
                trailing = { GcChevron() },
                selected = selectedPane == SettingsPane.Location,
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

        // --- Benachrichtigungen (notification center) ------------------------
        SettingsGroup(stringResource(R.string.push_title_settings)) {
            GcListRow(
                title = stringResource(R.string.push_title_settings),
                description = if (pushState.enabled) {
                    stringResource(
                        R.string.push_settings_row_on,
                        stringResource(if (pushState.mode == PushMode.ALWAYS) R.string.push_mode_short_always else R.string.push_mode_short_vpn),
                    )
                } else {
                    stringResource(R.string.push_settings_row_off)
                },
                onClick = onNavigateToNotifications,
                leading = { Icon(GcIcons.Bell, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(22.dp)) },
                trailing = { GcChevron() },
                selected = selectedPane == SettingsPane.Notifications,
            )
        }

        // --- Teilen (GateDrop) -------------------------------------------------
        if (onNavigateToShared != null) {
            SettingsGroup(stringResource(R.string.gatedrop_settings_group)) {
                GcListRow(
                    title = stringResource(R.string.gatedrop_shared_title),
                    description = stringResource(R.string.gatedrop_settings_row_desc),
                    onClick = onNavigateToShared,
                    leading = { Icon(GcIcons.Share, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(22.dp)) },
                    trailing = { GcChevron() },
                    selected = selectedPane == SettingsPane.Shared,
                )
            }
        }

        // --- Drive: everything of Drive is in its own settings (Drive 1.11) ---
        if (onNavigateToDrive != null) {
            val backup by driveBackupViewModel.config.collectAsStateWithLifecycle()
            SettingsGroup(stringResource(R.string.drive_title)) {
                GcListRow(
                    title = stringResource(R.string.drive_settings_title),
                    description = stringResource(R.string.drive_settings_row_desc),
                    onClick = onNavigateToDrive,
                    leading = { Icon(GcIcons.Cloud, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(22.dp)) },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (backup.enabled) {
                                Text(
                                    stringResource(R.string.drive_settings_row_backup_on),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = extra.accentText,
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                            }
                            GcChevron()
                        }
                    },
                )
            }
        }

        // --- Darstellung ---------------------------------------------------
        SettingsGroup(stringResource(R.string.settings_group_appearance)) {
            if (onNavigateToPages != null) {
                GcListRow(
                    title = stringResource(R.string.pages_settings_title),
                    description = stringResource(R.string.pages_settings_row_desc),
                    onClick = onNavigateToPages,
                    leading = { Icon(GcIcons.ViewGrid, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(22.dp)) },
                    trailing = { GcChevron() },
                    selected = selectedPane == SettingsPane.Pages,
                )
                GcRowDivider(Modifier.padding(horizontal = 16.dp))
            }
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
                selected = selectedPane == SettingsPane.Logs,
            )
            GcListRow(
                title = stringResource(R.string.settings_logs_export),
                onClick = { LogExport.share(context, logsExportTitle) },
                trailing = { Icon(GcIcons.Share, contentDescription = null, tint = extra.faint, modifier = Modifier.size(20.dp)) },
            )
            // "Diagnose & Problem melden": checks + report with description (ui/diagnosis)
            GcListRow(
                title = stringResource(R.string.diag_settings_row),
                description = stringResource(R.string.diag_settings_row_desc),
                onClick = onNavigateToDiagnosis,
                trailing = { GcChevron() },
                selected = selectedPane == SettingsPane.Diagnosis,
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
            if (com.gatecontrol.android.update.PlayUpdater.enabled) {
                // Google Play build: updates come from Play only (Play policy).
                com.gatecontrol.android.ui.update.PlayUpdateRow(versionName = versionName)
            } else UpdateRow(
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
