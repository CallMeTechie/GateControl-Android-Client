package com.gatecontrol.android.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.net.toUri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.data.TrustedNetworkSettings
import com.gatecontrol.android.trustednet.HomeCheck
import com.gatecontrol.android.trustednet.TrustedNetSnapshot
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcDot
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme

/** What Android lets the app know about the WLAN name right now. */
private data class LocationAccess(
    val fine: Boolean = false,
    val background: Boolean = false,
    val locationOn: Boolean = true,
)

private fun locationAccess(context: Context): LocationAccess {
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    val lm = context.getSystemService(LocationManager::class.java)
    return LocationAccess(
        fine = granted(Manifest.permission.ACCESS_FINE_LOCATION),
        background = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        locationOn = lm?.isLocationEnabled ?: true,
    )
}

private fun Context.openAppDetailsSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * "Vertrauenswürdige WLANs": master switch, current network, detection by
 * the server's home network and by WLAN name (with the location
 * permission flow). Changes are saved immediately; the monitor acts on the
 * next network change.
 */
@Composable
fun TrustedNetworksScreen(
    onBack: () -> Unit,
    viewModel: TrustedNetworksViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val extra = GateControlTheme.extraColors
    var access by remember { mutableStateOf(locationAccess(context)) }
    var showRationale by rememberSaveable { mutableStateOf(false) }
    var addCurrentAfterGrant by rememberSaveable { mutableStateOf(false) }
    var manualSsid by rememberSaveable { mutableStateOf("") }
    var manualError by rememberSaveable { mutableStateOf(false) }
    val locked = ui.locked
    val settings = ui.settings

    val addCurrent: () -> Unit = {
        viewModel.addCurrentSsid { ssid ->
            val text = if (ssid != null) resources.getString(R.string.trusted_add_current_added, ssid)
            else resources.getString(R.string.trusted_add_current_none)
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }

    val foregroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        access = locationAccess(context)
        if (access.fine) {
            viewModel.refresh()
            if (addCurrentAfterGrant) addCurrent()
        } else {
            val activity = context as? Activity
            // Denied for good: Android shows no dialog any more, only the settings help.
            if (result.isNotEmpty() && activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
            ) {
                context.openAppDetailsSettings()
            }
        }
        addCurrentAfterGrant = false
    }
    // Android 11+: "Allow all the time" can only be chosen on the system
    // settings page; this request opens it directly.
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        access = locationAccess(context)
        val activity = context as? Activity
        if (!granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ) {
            context.openAppDetailsSettings()
        }
        viewModel.refresh()
    }

    LifecycleResumeEffect(Unit) {
        access = locationAccess(context)
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        GcSubpageBar(
            title = stringResource(R.string.settings_trusted_networks),
            onBack = onBack,
            backDescription = stringResource(R.string.common_back),
        )
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (locked) {
                GcBanner(tone = GcTone.Warn, icon = GcIcons.Lock) {
                    Text(stringResource(R.string.trusted_locked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }

            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                GcSwitchRow(
                    label = stringResource(R.string.trusted_master),
                    description = stringResource(R.string.trusted_master_desc),
                    checked = settings.enabled,
                    onCheckedChange = viewModel::setEnabled,
                    icon = GcIcons.Wifi,
                    enabled = !locked,
                )
            }

            // --- Aktuelles Netz ------------------------------------------
            GcSectionLabel(stringResource(R.string.trusted_section_current), Modifier.padding(horizontal = 4.dp))
            GcCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    currentNetworkLine(ui, access, active = settings.enabled && !locked),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (settings.enabled && !locked) {
                    Text(stringResource(R.string.trusted_next_change_hint), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
            }

            // --- Erkennung: Heimnetz -------------------------------------
            GcSectionLabel(stringResource(R.string.trusted_section_detection), Modifier.padding(horizontal = 4.dp))
            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                GcSwitchRow(
                    label = stringResource(R.string.trusted_home),
                    description = stringResource(R.string.trusted_home_desc),
                    checked = settings.homeEnabled,
                    onCheckedChange = viewModel::setHomeEnabled,
                    icon = GcIcons.Home,
                    enabled = !locked,
                )
                if (settings.enabled && settings.homeEnabled && !locked) {
                    HomeStatusLine(ui)
                }
            }

            // --- Erkennung: WLAN-Namen -----------------------------------
            GcSectionLabel(stringResource(R.string.trusted_section_ssids), Modifier.padding(horizontal = 4.dp))
            if (settings.ssids.isNotEmpty()) {
                PermissionWarning(
                    access = access,
                    onGrant = { showRationale = true },
                    onBackground = { backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
                    onLocationSettings = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                )
            }
            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                if (settings.ssids.isEmpty()) {
                    Text(
                        stringResource(R.string.trusted_ssids_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.muted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
                settings.ssids.sorted().forEach { ssid ->
                    GcListRow(
                        title = ssid,
                        leading = { Icon(GcIcons.Wifi, contentDescription = null, tint = extra.muted, modifier = Modifier.size(20.dp)) },
                        trailing = {
                            GcIconButton(
                                icon = GcIcons.Close,
                                contentDescription = stringResource(R.string.common_remove_named, ssid),
                                onClick = { viewModel.removeSsid(ssid) },
                                iconSize = 18.dp,
                                enabled = !locked,
                            )
                        },
                    )
                    GcRowDivider()
                }
                GcListRow(
                    title = stringResource(R.string.trusted_add_current),
                    titleColor = if (locked) extra.muted else extra.accentText,
                    onClick = if (locked) null else {
                        {
                            if (access.fine) {
                                addCurrent()
                            } else {
                                addCurrentAfterGrant = true
                                showRationale = true
                            }
                        }
                    },
                    leading = { Icon(GcIcons.Plus, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(20.dp)) },
                )
            }

            val submitManual: () -> Unit = {
                if (viewModel.addSsid(manualSsid)) {
                    manualSsid = ""
                    manualError = false
                    if (!access.fine) showRationale = true
                } else {
                    manualError = true
                }
            }
            GcTextField(
                value = manualSsid,
                onValueChange = {
                    manualSsid = it
                    manualError = false
                },
                label = stringResource(R.string.trusted_add_manual_label),
                placeholder = stringResource(R.string.trusted_add_manual_placeholder),
                error = if (manualError) stringResource(R.string.trusted_add_manual_invalid) else null,
                enabled = !locked,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submitManual() }),
            )
            GcOutlineButton(
                text = stringResource(R.string.trusted_add),
                onClick = submitManual,
                enabled = !locked && manualSsid.isNotBlank(),
                icon = GcIcons.Plus,
            )
        }
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = {
                showRationale = false
                addCurrentAfterGrant = false
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(stringResource(R.string.trusted_perm_title), style = MaterialTheme.typography.titleLarge) },
            text = { Text(stringResource(R.string.trusted_perm_explain), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    foregroundLauncher.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    )
                }) { Text(stringResource(R.string.trusted_perm_continue)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRationale = false
                    addCurrentAfterGrant = false
                }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun currentNetworkLine(ui: TrustedNetworksUiState, access: LocationAccess, active: Boolean): String {
    val network = ui.network
    val base = when (network.kind) {
        TrustedNetSnapshot.Kind.WIFI -> {
            val ssid = network.ssid?.takeIf { access.fine }
            if (ssid != null) stringResource(R.string.trusted_current_wifi_named, ssid)
            else stringResource(R.string.trusted_current_wifi)
        }
        TrustedNetSnapshot.Kind.OTHER -> stringResource(R.string.trusted_current_mobile)
        TrustedNetSnapshot.Kind.NONE -> return stringResource(R.string.trusted_current_none)
    }
    if (!active) return base
    val status = ui.status
    val state = when {
        status.key != network.key || status.home == HomeCheck.CHECKING -> stringResource(R.string.trusted_state_checking)
        status.trusted == true -> stringResource(R.string.trusted_state_trusted)
        // Unknown counts as not trusted.
        else -> stringResource(R.string.trusted_state_untrusted)
    }
    return stringResource(R.string.trusted_current_with_state, base, state)
}

@Composable
private fun HomeStatusLine(ui: TrustedNetworksUiState) {
    val extra = GateControlTheme.extraColors
    val home = ui.status.home
    val (text, color) = when {
        ui.network.kind != TrustedNetSnapshot.Kind.WIFI -> stringResource(R.string.trusted_home_no) to extra.muted
        ui.status.key != ui.network.key || home == HomeCheck.CHECKING || home == HomeCheck.OFF ->
            stringResource(R.string.trusted_home_checking) to extra.muted
        home == HomeCheck.HOME -> stringResource(R.string.trusted_home_yes) to extra.accentText
        home == HomeCheck.NOT_HOME -> stringResource(R.string.trusted_home_no) to extra.muted
        home == HomeCheck.UNSUPPORTED -> stringResource(R.string.trusted_home_unsupported) to extra.warn
        home == HomeCheck.NEXT_CONNECT -> stringResource(R.string.trusted_home_next_connect) to extra.muted
        else -> stringResource(R.string.trusted_home_unknown) to extra.warn
    }
    Row(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GcDot(color, size = 8.dp)
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun PermissionWarning(
    access: LocationAccess,
    onGrant: () -> Unit,
    onBackground: () -> Unit,
    onLocationSettings: () -> Unit,
) {
    val (text, button, action) = when {
        !access.fine -> Triple(R.string.trusted_perm_missing, R.string.trusted_perm_grant, onGrant)
        !access.background -> Triple(R.string.trusted_perm_foreground_only, R.string.trusted_perm_open_settings, onBackground)
        !access.locationOn -> Triple(R.string.trusted_perm_location_off, R.string.trusted_perm_location_settings, onLocationSettings)
        else -> return
    }
    WarningBanner(GcIcons.Alert, stringResource(text), stringResource(button), action)
}

@Composable
private fun WarningBanner(icon: ImageVector, text: String, button: String, onClick: () -> Unit) {
    GcBanner(tone = GcTone.Warn, icon = icon) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            GcOutlineButton(text = button, onClick = onClick, minHeight = 40.dp)
        }
    }
}

/** Settings row summary: "Aus" / "An · Heimnetz + 2 WLANs". */
@Composable
internal fun trustedNetworksSummary(settings: TrustedNetworkSettings, locked: Boolean): String {
    if (!settings.enabled) return stringResource(R.string.trusted_summary_off)
    if (locked) return stringResource(R.string.trusted_summary_locked)
    val parts = buildList {
        if (settings.homeEnabled) add(stringResource(R.string.trusted_summary_home))
        if (settings.ssids.isNotEmpty()) {
            add(pluralStringResource(R.plurals.trusted_summary_wlans, settings.ssids.size, settings.ssids.size))
        }
    }
    return if (parts.isEmpty()) stringResource(R.string.trusted_summary_on)
    else stringResource(R.string.trusted_summary_on_with, parts.joinToString(" + "))
}
