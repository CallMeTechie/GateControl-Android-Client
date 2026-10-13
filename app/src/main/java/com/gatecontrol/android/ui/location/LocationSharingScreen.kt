package com.gatecontrol.android.ui.location

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.location.DeviceLocator
import com.gatecontrol.android.location.LocationServerStatus
import com.gatecontrol.android.location.LocationShareViewModel
import com.gatecontrol.android.location.SentLocation
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

private enum class PermissionLevel { PRECISE, APPROXIMATE, MISSING }

private fun permissionLevel(context: Context): PermissionLevel = when {
    DeviceLocator.hasPrecisePermission(context) -> PermissionLevel.PRECISE
    DeviceLocator.hasPermission(context) -> PermissionLevel.APPROXIMATE
    else -> PermissionLevel.MISSING
}

private fun Context.openAppDetails() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * "Standortfreigabe": switch with the prominent disclosure before the
 * (foreground-only) location permission, permission state, last position
 * sent, recipient, and deleting what the server keeps.
 */
@Composable
fun LocationSharingScreen(
    onBack: () -> Unit,
    viewModel: LocationShareViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val extra = GateControlTheme.extraColors
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val server by viewModel.server.collectAsStateWithLifecycle()
    val lastSent by viewModel.lastSent.collectAsStateWithLifecycle()
    val host by viewModel.serverHost.collectAsStateWithLifecycle()
    val deleting by viewModel.deleting.collectAsStateWithLifecycle()
    val deleteResult by viewModel.deleteResult.collectAsStateWithLifecycle()

    var permission by remember { mutableStateOf(permissionLevel(context)) }
    var showDisclosure by rememberSaveable { mutableStateOf(false) }
    var showDisableAsk by rememberSaveable { mutableStateOf(false) }
    var showDeleteAsk by rememberSaveable { mutableStateOf(false) }
    var permissionAsks by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permission = permissionLevel(context)
        if (permission != PermissionLevel.MISSING) {
            viewModel.setEnabled(true)
        } else {
            val activity = context as? Activity
            // Denied for good: Android shows no dialog any more, only the app settings help.
            if (result.isNotEmpty() && activity != null && permissionAsks > 0 &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
            ) {
                context.openAppDetails()
            }
            permissionAsks++
        }
    }
    val requestPermission = {
        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    LifecycleResumeEffect(Unit) {
        permission = permissionLevel(context)
        viewModel.refresh()
        onPauseOrDispose { }
    }

    LaunchedEffect(deleteResult) {
        val r = deleteResult ?: return@LaunchedEffect
        Toast.makeText(
            context,
            resources.getString(if (r) R.string.location_deleted else R.string.location_delete_failed),
            Toast.LENGTH_LONG,
        ).show()
        viewModel.clearDeleteResult()
    }

    val blocked = server is LocationServerStatus.NotAllowed || server is LocationServerStatus.Unsupported
    val allowed = server as? LocationServerStatus.Allowed

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        GcSubpageBar(
            title = stringResource(R.string.location_title),
            onBack = onBack,
            backDescription = stringResource(R.string.common_back),
        )
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (server) {
                LocationServerStatus.NotAllowed -> GcBanner(tone = GcTone.Warn, icon = GcIcons.Lock) {
                    Text(stringResource(R.string.location_not_allowed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
                LocationServerStatus.Unsupported -> GcBanner(tone = GcTone.Warn, icon = GcIcons.Satellite) {
                    Text(stringResource(R.string.location_unsupported), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
                else -> Unit
            }

            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                GcSwitchRow(
                    label = stringResource(R.string.location_switch),
                    description = stringResource(R.string.location_switch_desc),
                    checked = enabled,
                    onCheckedChange = { on ->
                        if (on) showDisclosure = true
                        else if (allowed?.last != null || lastSent != null) showDisableAsk = true
                        else viewModel.setEnabled(false)
                    },
                    icon = GcIcons.Satellite,
                    // Switching off always works; switching on needs the server's consent.
                    enabled = enabled || !blocked,
                )
                Text(
                    stringResource(R.string.location_explain),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                )
            }

            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                GcListRow(
                    title = stringResource(R.string.location_permission),
                    description = stringResource(
                        when (permission) {
                            PermissionLevel.PRECISE -> R.string.location_permission_precise
                            PermissionLevel.APPROXIMATE -> R.string.location_permission_approx
                            PermissionLevel.MISSING -> R.string.location_permission_missing
                        },
                    ),
                    onClick = when (permission) {
                        PermissionLevel.MISSING -> if (enabled) requestPermission else null
                        else -> { { context.openAppDetails() } }
                    },
                    trailing = {
                        GcChip(
                            text = stringResource(if (permission == PermissionLevel.MISSING) R.string.location_chip_missing else R.string.location_chip_granted),
                            tone = if (permission == PermissionLevel.MISSING) GcTone.Warn else GcTone.Ok,
                            small = true,
                        )
                    },
                )
                GcRowDivider(Modifier.padding(horizontal = 16.dp))
                GcListRow(
                    title = stringResource(R.string.location_last_sent),
                    description = lastSentText(allowed?.last ?: lastSent),
                )
                GcRowDivider(Modifier.padding(horizontal = 16.dp))
                GcListRow(
                    title = stringResource(R.string.location_recipient),
                    description = stringResource(R.string.location_recipient_desc, host.ifBlank { "—" }),
                )
            }

            GcSectionLabel(stringResource(R.string.location_stored_title), Modifier.padding(horizontal = 4.dp))
            GcCard {
                Text(
                    stringResource(
                        R.string.location_stored_desc,
                        allowed?.keep ?: com.gatecontrol.android.location.LocationShareRepository.DEFAULT_KEEP,
                        allowed?.retentionDays ?: com.gatecontrol.android.location.LocationShareRepository.DEFAULT_RETENTION_DAYS,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                )
                GcOutlineButton(
                    text = stringResource(R.string.location_delete),
                    onClick = { showDeleteAsk = true },
                    enabled = server !is LocationServerStatus.Unsupported,
                    loading = deleting,
                    icon = GcIcons.Trash,
                )
            }
        }
    }

    if (showDisclosure) {
        AlertDialog(
            onDismissRequest = { showDisclosure = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(stringResource(R.string.location_disclosure_title), style = MaterialTheme.typography.titleLarge) },
            text = { Text(stringResource(R.string.location_disclosure_text), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    showDisclosure = false
                    if (permissionLevel(context) == PermissionLevel.MISSING) requestPermission()
                    else viewModel.setEnabled(true)
                }) { Text(stringResource(R.string.location_disclosure_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showDisclosure = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showDisableAsk) {
        AlertDialog(
            onDismissRequest = { showDisableAsk = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(stringResource(R.string.location_disable_title), style = MaterialTheme.typography.titleLarge) },
            text = { Text(stringResource(R.string.location_disable_text), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    showDisableAsk = false
                    viewModel.setEnabled(false)
                    viewModel.deleteOnServer()
                }) { Text(stringResource(R.string.location_disable_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDisableAsk = false
                    viewModel.setEnabled(false)
                }) { Text(stringResource(R.string.location_disable_keep)) }
            },
        )
    }

    if (showDeleteAsk) {
        AlertDialog(
            onDismissRequest = { showDeleteAsk = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(stringResource(R.string.location_delete_title), style = MaterialTheme.typography.titleLarge) },
            text = { Text(stringResource(R.string.location_delete_text), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteAsk = false
                    viewModel.deleteOnServer()
                }) { Text(stringResource(R.string.location_delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAsk = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun lastSentText(sent: SentLocation?): String {
    if (sent == null) return stringResource(R.string.location_last_sent_never)
    val when_ = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(sent.at))
    val acc = sent.accuracy
    return if (acc != null) stringResource(R.string.location_last_sent_value, when_, acc.roundToInt())
    else when_
}

/** One line for the settings overview row. */
@Composable
fun locationSharingSummary(enabled: Boolean, server: LocationServerStatus): String = stringResource(
    when {
        server is LocationServerStatus.NotAllowed -> R.string.location_summary_not_allowed
        server is LocationServerStatus.Unsupported -> R.string.location_summary_unsupported
        enabled -> R.string.location_summary_on
        else -> R.string.location_summary_off
    },
)
