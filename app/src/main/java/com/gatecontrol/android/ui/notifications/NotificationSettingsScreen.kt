package com.gatecontrol.android.ui.notifications

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.notify.BlockReason
import com.gatecontrol.android.notify.PushMode
import com.gatecontrol.android.notify.PushStatus
import com.gatecontrol.android.notify.WaitReason
import com.gatecontrol.android.push.PushText
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcDot
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Android lets GateControl post notifications (runtime permission and app switch). */
internal fun notificationsAllowed(context: Context): Boolean =
    (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun Context.openAppNotificationSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Settings → Benachrichtigungen: status (with test message), battery
 * optimisation, delivery (switch + mode), topics, quiet hours (portal).
 */
@Composable
fun NotificationSettingsScreen(
    onBack: () -> Unit,
    /** "Wo Mitteilungen erscheinen" (core 1.191+; the row stays hidden on older servers). */
    onOpenRoutes: (() -> Unit)? = null,
    viewModel: NotificationSettingsViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val extra = GateControlTheme.extraColors
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val activity = context as? Activity
        if (!granted && activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            permanentlyDenied = true
        }
        viewModel.onNotificationPermissionResult(notificationsAllowed(context))
    }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshSystemState(notificationsAllowed(context))
    }
    val requestBatteryExemption = {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
        val launched = runCatching { batteryLauncher.launch(direct) }.isSuccess
        if (!launched) runCatching { batteryLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
    val requestPermission = {
        // Before Android 13 there is no runtime permission: only the app switch in the system settings.
        if (permanentlyDenied || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) context.openAppNotificationSettings()
        else permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshSystemState(notificationsAllowed(context))
        onPauseOrDispose { }
    }

    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                SettingsEffect.RequestNotificationPermission ->
                    if (!notificationsAllowed(context)) requestPermission() else viewModel.onNotificationPermissionResult(true)
                SettingsEffect.RequestBatteryExemption -> requestBatteryExemption()
                is SettingsEffect.OpenUrl -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(effect.url))) }
                SettingsEffect.PortalUnavailable ->
                    Toast.makeText(context, resources.getString(R.string.push_portal_unavailable), Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        GcSubpageBar(
            title = stringResource(R.string.push_title_settings),
            onBack = onBack,
            backDescription = stringResource(R.string.common_back),
        )
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StatusCard(ui, onTest = viewModel::requestTest, onRetry = viewModel::retry)

            if (ui.enabled && !ui.notificationsAllowed) {
                GcBanner(tone = GcTone.Warn, icon = GcIcons.Bell) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.push_permission_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.push_permission_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                        GcOutlineButton(text = stringResource(R.string.push_permission_allow), onClick = requestPermission, minHeight = 40.dp)
                    }
                }
            }

            if (ui.enabled && ui.mode == PushMode.ALWAYS && ui.batteryRestricted) {
                GcBanner(tone = GcTone.Warn, icon = GcIcons.Battery) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.push_battery_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.push_battery_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                        GcOutlineButton(text = stringResource(R.string.push_battery_exempt), onClick = requestBatteryExemption, minHeight = 40.dp)
                    }
                }
            }

            // --- Zustellung ---------------------------------------------
            Group(stringResource(R.string.push_section_delivery)) {
                GcSwitchRow(
                    label = stringResource(R.string.push_enabled),
                    description = null,
                    checked = ui.enabled,
                    onCheckedChange = viewModel::setEnabled,
                )
                GcRowDivider()
                ModeRow(
                    title = stringResource(R.string.push_mode_always),
                    desc = stringResource(R.string.push_mode_always_desc),
                    recommended = true,
                    selected = ui.mode == PushMode.ALWAYS,
                    onSelect = { viewModel.setMode(PushMode.ALWAYS) },
                )
                ModeRow(
                    title = stringResource(R.string.push_mode_vpn_only),
                    desc = stringResource(R.string.push_mode_vpn_only_desc),
                    recommended = false,
                    selected = ui.mode == PushMode.VPN_ONLY,
                    onSelect = { viewModel.setMode(PushMode.VPN_ONLY) },
                )
            }

            // --- Themen -------------------------------------------------
            Group(stringResource(R.string.push_section_topics)) {
                if (ui.topics.isEmpty()) {
                    Text(
                        stringResource(R.string.push_topics_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.muted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                ui.topics.forEach { topic ->
                    GcSwitchRow(
                        label = topicLabel(topic.id, topic.label),
                        description = stringResource(PushText.topicDescription(topic.id)),
                        checked = !topic.muted,
                        onCheckedChange = { viewModel.setTopicEnabled(topic.id, it) },
                        enabled = !topic.locked,
                    )
                }
                if (onOpenRoutes != null) NotifyRoutesEntryRow(onOpenRoutes)
                GcListRow(
                    title = stringResource(R.string.push_topics_android),
                    description = stringResource(R.string.push_topics_android_desc),
                    onClick = { context.openAppNotificationSettings() },
                    trailing = { GcChevron() },
                )
            }

            // --- Ruhezeiten (set per person in the portal) ---------------
            Group(stringResource(R.string.push_section_quiet)) {
                when (val quiet = ui.quiet) {
                    is QuietDisplay.Window -> {
                        GcListRow(
                            title = stringResource(R.string.push_quiet_window),
                            description = quiet.zone?.let { stringResource(R.string.push_quiet_zone, it) },
                            trailing = {
                                Text(
                                    stringResource(R.string.push_quiet_range, quiet.from, quiet.to),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                        )
                        // Read only: set per person in the portal.
                        GcSwitchRow(
                            label = stringResource(R.string.push_quiet_critical),
                            description = null,
                            checked = quiet.criticalBypass,
                            onCheckedChange = {},
                            enabled = false,
                        )
                    }
                    QuietDisplay.None -> GcListRow(
                        title = stringResource(R.string.push_quiet_window),
                        trailing = {
                            Text(
                                stringResource(R.string.push_quiet_none),
                                style = MaterialTheme.typography.bodyMedium,
                                color = extra.muted,
                            )
                        },
                    )
                    QuietDisplay.Unknown -> Unit
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.push_quiet_desc), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                    if (ui.portalAvailable) {
                        GcOutlineButton(
                            text = stringResource(R.string.push_quiet_open_portal),
                            onClick = viewModel::openQuietHoursInPortal,
                            icon = GcIcons.External,
                            minHeight = 40.dp,
                        )
                    }
                }
            }
        }
    }

    if (ui.modeDialogVisible) {
        AlertDialog(
            onDismissRequest = viewModel::dismissModeDialog,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(stringResource(R.string.push_mode_dialog_title), style = MaterialTheme.typography.titleLarge) },
            text = { Text(stringResource(R.string.push_mode_dialog_text), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmMode(PushMode.ALWAYS) }) { Text(stringResource(R.string.push_mode_dialog_always)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.confirmMode(PushMode.VPN_ONLY) }) { Text(stringResource(R.string.push_mode_dialog_vpn)) }
            },
        )
    }
}

@Composable
internal fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        GcSectionLabel(title, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) { content() }
    }
}

@Composable
private fun StatusCard(ui: NotificationSettingsUiState, onTest: () -> Unit, onRetry: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val status = ui.status
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status) {
        while (status is PushStatus.Connected) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }

    val (dot: Color, title: String) = when {
        !ui.enabled -> extra.faint to stringResource(R.string.push_status_off)
        status is PushStatus.Connected -> scheme.primary to stringResource(R.string.push_status_ready)
        status is PushStatus.Blocked -> scheme.error to stringResource(R.string.push_status_blocked)
        status is PushStatus.Waiting -> extra.warn to stringResource(R.string.push_status_waiting)
        status is PushStatus.WaitingForVpn -> extra.faint to stringResource(R.string.push_status_waiting_vpn)
        else -> extra.warn to stringResource(R.string.push_status_connecting)
    }
    val detail = when {
        !ui.enabled -> stringResource(R.string.push_status_off_detail)
        status is PushStatus.Connected -> stringResource(
            R.string.push_status_connected_detail,
            ui.host,
            stringResource(if (status.via == "tunnel") R.string.push_status_via_tunnel else R.string.push_status_via_direct),
            shortDuration(now - status.since),
        )
        status is PushStatus.WaitingForVpn -> stringResource(R.string.push_status_waiting_vpn_detail)
        status is PushStatus.Blocked -> stringResource(
            if (status.reason == BlockReason.MACHINE_BINDING) R.string.push_blocked_binding else R.string.push_blocked_auth,
        )
        status is PushStatus.Waiting -> waitReason(status.reason) + " " + stringResource(
            R.string.push_status_retry_at,
            Instant.ofEpochMilli(status.retryAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")),
        )
        else -> ui.host
    }

    GcCard(contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GcDot(dot)
            Text(title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = extra.muted)
        when (ui.testState) {
            TestState.Sent -> Text(stringResource(R.string.push_test_sent), style = MaterialTheme.typography.bodySmall, color = extra.accentText)
            TestState.Failed -> Text(stringResource(R.string.push_test_failed), style = MaterialTheme.typography.bodySmall, color = scheme.error)
            else -> Unit
        }
        if (ui.enabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GcOutlineButton(
                    text = stringResource(R.string.push_test_request),
                    onClick = onTest,
                    loading = ui.testState == TestState.Sending,
                    modifier = Modifier.weight(1f),
                    minHeight = 40.dp,
                )
                if (status is PushStatus.Waiting || status is PushStatus.Blocked) {
                    GcOutlineButton(
                        text = stringResource(R.string.push_retry),
                        onClick = onRetry,
                        icon = GcIcons.Refresh,
                        modifier = Modifier.weight(1f),
                        minHeight = 40.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun waitReason(reason: WaitReason): String = stringResource(
    when (reason) {
        WaitReason.BACKOFF -> R.string.push_wait_backoff
        WaitReason.SERVER_UNSUPPORTED -> R.string.push_wait_unsupported
        WaitReason.PUSH_DISABLED -> R.string.push_wait_disabled
        WaitReason.SERVER_BUSY -> R.string.push_wait_busy
        WaitReason.RATE_LIMITED -> R.string.push_wait_rate_limited
        WaitReason.DIRECT_NOT_ALLOWED -> R.string.push_wait_direct
    },
)

/** A radio row with title and description (also the choices of "Wo Mitteilungen erscheinen"). */
@Composable
internal fun ModeRow(title: String, desc: String, recommended: Boolean, selected: Boolean, onSelect: () -> Unit, enabled: Boolean = true) {
    val extra = GateControlTheme.extraColors
    val accent = if (enabled) MaterialTheme.colorScheme.primary else extra.faint
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 2.dp)
                .size(22.dp)
                .clip(CircleShape)
                .border(2.dp, if (selected) accent else extra.border2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (selected) accent else Color.Transparent))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) MaterialTheme.colorScheme.onSurface else extra.muted, modifier = Modifier.weight(1f, fill = false))
                if (recommended) GcChip(stringResource(R.string.push_recommended), tone = GcTone.Ok, small = true)
            }
            Text(desc, style = MaterialTheme.typography.bodySmall, color = extra.muted)
        }
    }
}
