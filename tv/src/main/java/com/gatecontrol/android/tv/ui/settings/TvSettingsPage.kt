package com.gatecontrol.android.tv.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.SelectableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.ui.components.TvButton
import com.gatecontrol.android.tv.ui.components.TvButtonStyle
import com.gatecontrol.android.tv.ui.components.TvOptionRow
import com.gatecontrol.android.tv.ui.components.TvPill
import com.gatecontrol.android.tv.ui.components.TvPillTone
import com.gatecontrol.android.tv.ui.components.TvRowEnd
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import com.gatecontrol.android.tv.update.TvUpdateCheck
import com.gatecontrol.android.ui.theme.MonoFontFamily
import com.gatecontrol.android.update.ApkUpdateState

/** What the connection part shows. */
data class TvConnectionUi(
    val tunnel: TunnelState,
    val host: String,
    val address: String,
    val autostartTunnel: Boolean,
    val openOnBoot: Boolean,
    val consentDenied: Boolean,
)

data class TvUpdateUi(val version: String, val check: TvUpdateCheck, val download: ApkUpdateState)

/** The categories of Einstellungen (mockup "6 · Einstellungen"); GateDrop and Startseite follow with their functions. */
internal enum class TvSettingsCategory(@param:StringRes val label: Int) {
    Connection(R.string.tv_set_connection),
    Notices(R.string.tv_set_notices),
    Security(R.string.tv_set_security),
    Frame(R.string.tv_set_frame),
    Updates(R.string.tv_set_updates),
    Device(R.string.tv_set_device),
}

/** Einstellungen: categories left, options right; VPN and autostart first. */
@Composable
fun TvSettingsPage(
    connection: TvConnectionUi,
    update: TvUpdateUi,
    deviceName: String,
    onVpn: (Boolean) -> Unit,
    onAutostartTunnel: (Boolean) -> Unit,
    onOpenOnBoot: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onUnpair: () -> Unit,
    modifier: Modifier = Modifier,
    /** "Geteilter Tunnel" under Verbindung. */
    splitTunnel: @Composable () -> Unit = {},
    /** Einblendungen: messages, the doorbell. */
    notices: @Composable () -> Unit = {},
    /** Sicherheit: the TV PIN. */
    security: @Composable () -> Unit = {},
    /** Fotorahmen: folder, interval, order, idle start. */
    frame: @Composable () -> Unit = {},
) {
    var category by rememberSaveable { mutableStateOf(TvSettingsCategory.Connection) }
    Row(modifier.fillMaxSize().padding(end = 30.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.width(170.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            for (c in TvSettingsCategory.entries) CategoryItem(c, selected = c == category, onClick = { category = c })
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            when (category) {
                TvSettingsCategory.Connection -> {
                    ConnectionOptions(connection, onVpn, onAutostartTunnel, onOpenOnBoot)
                    splitTunnel()
                }
                TvSettingsCategory.Notices -> notices()
                TvSettingsCategory.Security -> security()
                TvSettingsCategory.Frame -> frame()
                TvSettingsCategory.Updates -> UpdateOptions(update, onCheckUpdate, onInstallUpdate)
                TvSettingsCategory.Device -> DeviceOptions(connection.host, deviceName, onUnpair)
            }
        }
    }
    LaunchedEffect(category) { if (category == TvSettingsCategory.Updates && update.check == TvUpdateCheck.Idle) onCheckUpdate() }
}

@Composable
private fun CategoryItem(category: TvSettingsCategory, selected: Boolean, onClick: () -> Unit) {
    val colors = GateControlTvTheme.colors
    val shape = RoundedCornerShape(7.dp)
    val ring = Border(BorderStroke(2.dp, colors.focus), shape = shape)
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(27.dp),
        shape = SelectableSurfaceDefaults.shape(shape),
        colors = SelectableSurfaceDefaults.colors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = colors.muted,
            focusedContainerColor = colors.panel2,
            focusedContentColor = colors.text,
            selectedContainerColor = colors.panel2,
            selectedContentColor = colors.text,
            focusedSelectedContainerColor = colors.panel2,
            focusedSelectedContentColor = colors.text,
        ),
        border = SelectableSurfaceDefaults.border(focusedBorder = ring, focusedSelectedBorder = ring),
        scale = SelectableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Text(
            stringResource(category.label),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 10.dp),
        )
    }
}

/** "seit 3 Std." / "seit 12 Min." */
@Composable
private fun sinceText(since: Long): String {
    val minutes = ((System.currentTimeMillis() - since) / 60_000L).coerceAtLeast(0)
    return if (minutes < 60) stringResource(R.string.tv_since_minutes, minutes.toInt())
    else stringResource(R.string.tv_since_hours, (minutes / 60).toInt())
}

@Composable
private fun ConnectionOptions(
    ui: TvConnectionUi,
    onVpn: (Boolean) -> Unit,
    onAutostartTunnel: (Boolean) -> Unit,
    onOpenOnBoot: (Boolean) -> Unit,
) {
    val colors = GateControlTvTheme.colors
    val connected = ui.tunnel is TunnelState.Connected
    val busy = ui.tunnel is TunnelState.Connecting || ui.tunnel is TunnelState.Reconnecting || ui.tunnel is TunnelState.Disconnecting
    // .vpnhead
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (connected) colors.accentBg else colors.panel2, RoundedCornerShape(9.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            stringResource(
                when {
                    connected -> R.string.tv_vpn_connected
                    busy -> R.string.tv_vpn_connecting
                    ui.tunnel is TunnelState.Error -> R.string.tv_vpn_error
                    else -> R.string.tv_vpn_disconnected
                },
            ),
            style = MaterialTheme.typography.headlineSmall,
            color = if (connected) colors.accentText else colors.text,
        )
        val detail = buildList {
            add(stringResource(R.string.tv_vpn_tunnel_to, ui.host))
            if (ui.address.isNotEmpty()) add(ui.address)
            (ui.tunnel as? TunnelState.Connected)?.let { add(sinceText(it.connectedSince)) }
        }.joinToString(" · ")
        Text(detail, style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily), color = colors.muted, modifier = Modifier.padding(top = 2.dp))
    }
    TvOptionRow(
        title = stringResource(R.string.tv_vpn),
        description = stringResource(if (ui.consentDenied) R.string.tv_vpn_consent_denied else R.string.tv_vpn_desc),
        end = TvRowEnd.Switch(connected || busy),
        onClick = { onVpn(!(connected || busy)) },
    )
    TvOptionRow(
        title = stringResource(R.string.tv_autostart),
        description = stringResource(R.string.tv_autostart_desc),
        end = TvRowEnd.Switch(ui.autostartTunnel),
        onClick = { onAutostartTunnel(!ui.autostartTunnel) },
    )
    TvOptionRow(
        title = stringResource(R.string.tv_open_on_boot),
        description = stringResource(R.string.tv_open_on_boot_desc),
        end = TvRowEnd.Switch(ui.openOnBoot),
        onClick = { onOpenOnBoot(!ui.openOnBoot) },
    )
    var showAdb by rememberSaveable { mutableStateOf(false) }
    TvOptionRow(
        title = stringResource(R.string.tv_always_on),
        description = if (showAdb) stringResource(R.string.tv_always_on_howto, ALWAYS_ON_ADB) else stringResource(R.string.tv_always_on_desc),
        end = TvRowEnd.Arrow,
        onClick = { showAdb = !showAdb },
        badge = { TvPill(stringResource(R.string.tv_adb_once), TvPillTone.Warn, dot = false) },
    )
}

/** The one-time ADB command for always-on VPN (Fire OS has no setting for it). */
internal const val ALWAYS_ON_ADB = "adb shell settings put secure always_on_vpn_app com.gatecontrol.client.tv"

@Composable
private fun UpdateOptions(ui: TvUpdateUi, onCheck: () -> Unit, onInstall: () -> Unit) {
    val colors = GateControlTvTheme.colors
    TvOptionRow(
        title = stringResource(R.string.tv_update_installed, ui.version),
        description = when (val c = ui.check) {
            TvUpdateCheck.Idle, TvUpdateCheck.Checking -> stringResource(R.string.tv_update_checking)
            TvUpdateCheck.UpToDate -> stringResource(R.string.tv_update_up_to_date)
            TvUpdateCheck.Failed -> stringResource(R.string.tv_update_check_failed)
            is TvUpdateCheck.Available -> stringResource(R.string.tv_update_available, c.offer.version.orEmpty())
        },
        end = TvRowEnd.None,
        onClick = onCheck,
    )
    val download = ui.download
    val status = when (download) {
        is ApkUpdateState.Downloading -> {
            val pct = if (download.total > 0) (download.done * 100 / download.total).toInt() else null
            val where = stringResource(if (download.home) R.string.tv_update_from_home else R.string.tv_update_from_server)
            if (pct != null) stringResource(R.string.tv_update_loading_pct, pct, where) else stringResource(R.string.tv_update_loading, where)
        }
        ApkUpdateState.Verifying -> stringResource(R.string.tv_update_verifying)
        is ApkUpdateState.Ready -> stringResource(if (download.needsPermission) R.string.tv_update_permission else R.string.tv_update_ready)
        is ApkUpdateState.Failed -> stringResource(R.string.tv_update_failed, download.code)
        ApkUpdateState.Idle -> null
    }
    if (status != null) Text(status, style = MaterialTheme.typography.bodySmall, color = colors.muted)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        if (ui.check is TvUpdateCheck.Available) {
            TvButton(stringResource(R.string.tv_update_install), onClick = onInstall, style = TvButtonStyle.Primary)
        }
        TvButton(stringResource(R.string.tv_update_check), onClick = onCheck, enabled = ui.check != TvUpdateCheck.Checking)
    }
}

@Composable
private fun DeviceOptions(host: String, deviceName: String, onUnpair: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    TvOptionRow(title = stringResource(R.string.tv_device_server), description = host, end = TvRowEnd.None, onClick = {})
    TvOptionRow(title = stringResource(R.string.tv_device_name), description = deviceName, end = TvRowEnd.None, onClick = {})
    TvOptionRow(
        title = stringResource(R.string.tv_unpair),
        description = stringResource(if (confirm) R.string.tv_unpair_confirm else R.string.tv_unpair_desc),
        end = TvRowEnd.Arrow,
        onClick = { if (confirm) onUnpair() else confirm = true },
    )
}
