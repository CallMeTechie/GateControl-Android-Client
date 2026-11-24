package com.gatecontrol.android.tv.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.gatecontrol.android.common.SplitTunnelMode
import com.gatecontrol.android.door.HomeCamera
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.TvInstalledApp
import com.gatecontrol.android.tv.doorbell.DoorbellMode
import com.gatecontrol.android.tv.doorbell.DoorbellSettings
import com.gatecontrol.android.tv.security.TvPinCheck
import com.gatecontrol.android.tv.security.TvPinFunction
import com.gatecontrol.android.tv.security.TvPinState
import com.gatecontrol.android.tv.tunnel.TvSplitTunnelSettings
import com.gatecontrol.android.tv.ui.components.TvNewPinDialog
import com.gatecontrol.android.tv.ui.components.TvOptionRow
import com.gatecontrol.android.tv.ui.components.TvPill
import com.gatecontrol.android.tv.ui.components.TvPillTone
import com.gatecontrol.android.tv.ui.components.TvPinDialog
import com.gatecontrol.android.tv.ui.components.TvRowEnd
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme

/** The one-time ADB command for "Über anderen Apps einblenden" (Fire OS has no setting for it). */
internal const val OVERLAY_ADB = "adb shell appops set com.gatecontrol.client.tv SYSTEM_ALERT_WINDOW allow"

/** Quiet hours are set in steps of half an hour (◀ ▶ would be too many presses otherwise). */
internal const val QUIET_STEP_MIN = 30

internal fun hhmm(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

/** Einstellungen › Einblendungen: messages on/off, how the doorbell shows, quiet hours, motion, camera. */
@Composable
fun TvNoticeOptions(
    push: Boolean,
    onPush: (Boolean) -> Unit,
    bell: DoorbellSettings,
    onBell: (DoorbellSettings) -> Unit,
    canDrawOverlays: Boolean,
    cameras: List<HomeCamera>,
    onLoadCameras: () -> Unit,
) {
    LaunchedEffect(Unit) { onLoadCameras() }
    TvOptionRow(
        title = stringResource(R.string.tv_notices_push),
        description = stringResource(R.string.tv_notices_push_desc),
        end = TvRowEnd.Switch(push),
        onClick = { onPush(!push) },
    )
    val modes = DoorbellMode.entries
    TvOptionRow(
        title = stringResource(R.string.tv_bell_mode),
        description = stringResource(
            when (bell.mode) {
                DoorbellMode.WINDOW -> R.string.tv_bell_mode_window
                DoorbellMode.FULLSCREEN -> R.string.tv_bell_mode_fullscreen
                DoorbellMode.NOTICE -> R.string.tv_bell_mode_notice
            },
        ),
        end = TvRowEnd.Arrow,
        onClick = { onBell(bell.copy(mode = modes[(modes.indexOf(bell.mode) + 1) % modes.size])) },
    )
    var showAdb by rememberSaveable { mutableStateOf(false) }
    TvOptionRow(
        title = stringResource(R.string.tv_overlay_right),
        description = if (showAdb) stringResource(R.string.tv_overlay_howto, OVERLAY_ADB)
        else stringResource(if (canDrawOverlays) R.string.tv_overlay_granted else R.string.tv_overlay_missing),
        end = TvRowEnd.Arrow,
        onClick = { showAdb = !showAdb },
        badge = {
            if (canDrawOverlays) TvPill(stringResource(R.string.tv_overlay_on), TvPillTone.Accent, dot = false)
            else TvPill(stringResource(R.string.tv_adb_once), TvPillTone.Warn, dot = false)
        },
    )
    TvOptionRow(
        title = stringResource(R.string.tv_bell_quiet),
        description = stringResource(R.string.tv_bell_quiet_desc, hhmm(bell.quietFrom), hhmm(bell.quietTo)),
        end = TvRowEnd.Switch(bell.quiet),
        onClick = { onBell(bell.copy(quiet = !bell.quiet)) },
    )
    if (bell.quiet) {
        TvOptionRow(
            title = stringResource(R.string.tv_bell_quiet_from, hhmm(bell.quietFrom)),
            description = stringResource(R.string.tv_bell_quiet_step),
            end = TvRowEnd.Arrow,
            onClick = { onBell(bell.copy(quietFrom = (bell.quietFrom + QUIET_STEP_MIN) % (24 * 60))) },
        )
        TvOptionRow(
            title = stringResource(R.string.tv_bell_quiet_to, hhmm(bell.quietTo)),
            description = stringResource(R.string.tv_bell_quiet_step),
            end = TvRowEnd.Arrow,
            onClick = { onBell(bell.copy(quietTo = (bell.quietTo + QUIET_STEP_MIN) % (24 * 60))) },
        )
    }
    TvOptionRow(
        title = stringResource(R.string.tv_bell_motion),
        description = stringResource(R.string.tv_bell_motion_desc),
        end = TvRowEnd.Switch(bell.motion),
        onClick = { onBell(bell.copy(motion = !bell.motion)) },
    )
    // The camera of the live picture: none (the doorbell's own still) → each home camera → none …
    val chosen = cameras.firstOrNull { it.id == bell.cameraId }
    TvOptionRow(
        title = stringResource(R.string.tv_bell_camera),
        description = stringResource(R.string.tv_bell_camera_fallback, chosen?.name ?: stringResource(R.string.tv_bell_camera_none)),
        end = TvRowEnd.Arrow,
        onClick = {
            val ids = listOf<String?>(null) + cameras.map { it.id }
            val at = ids.indexOf(chosen?.id).coerceAtLeast(0)
            onBell(bell.copy(cameraId = ids[(at + 1) % ids.size]))
        },
    )
}

/** Einstellungen › Sicherheit: set / change / remove the TV PIN, per function "mit PIN" or not. */
@Composable
fun TvSecurityOptions(
    pin: TvPinState,
    checkPin: suspend (String) -> TvPinCheck,
    onSetPin: (String) -> Unit,
    onRemovePin: () -> Unit,
    onRequired: (TvPinFunction, Boolean) -> Unit,
) {
    val colors = GateControlTvTheme.colors
    // What waits for the old PIN: change, remove or switching a function off.
    var verifyFor by rememberSaveable { mutableStateOf<String?>(null) }
    var newPin by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.tv_pin_intro), style = MaterialTheme.typography.bodySmall, color = colors.muted, modifier = Modifier.padding(bottom = 4.dp))
    if (!pin.hasPin) {
        TvOptionRow(stringResource(R.string.tv_pin_set), stringResource(R.string.tv_pin_set_desc), TvRowEnd.Arrow, onClick = { newPin = true })
    } else {
        TvOptionRow(stringResource(R.string.tv_pin_change), null, TvRowEnd.Arrow, onClick = { verifyFor = "change" })
        TvOptionRow(stringResource(R.string.tv_pin_remove), stringResource(R.string.tv_pin_remove_desc), TvRowEnd.Arrow, onClick = { verifyFor = "remove" })
    }
    for (f in TvPinFunction.AVAILABLE) {
        val on = pin.needsPin(f)
        TvOptionRow(
            title = stringResource(
                when (f) {
                    TvPinFunction.DOOR -> R.string.tv_pin_fn_door
                    TvPinFunction.SETTINGS -> R.string.tv_pin_fn_settings
                    TvPinFunction.DRIVE -> R.string.tv_pin_fn_drive
                    TvPinFunction.VAULT -> R.string.tv_pin_fn_vault
                    TvPinFunction.NOTES -> R.string.tv_pin_fn_notes
                },
            ),
            description = stringResource(if (!pin.hasPin) R.string.tv_pin_fn_needs_pin else if (on) R.string.tv_pin_fn_on else R.string.tv_pin_fn_off),
            end = TvRowEnd.Switch(on),
            onClick = {
                when {
                    !pin.hasPin -> newPin = true
                    on -> verifyFor = "off:" + f.wire
                    else -> onRequired(f, true)
                }
            },
        )
    }
    verifyFor?.let { what ->
        TvPinDialog(
            title = stringResource(R.string.tv_pin_current),
            check = checkPin,
            onPassed = {
                verifyFor = null
                when {
                    what == "change" -> newPin = true
                    what == "remove" -> onRemovePin()
                    what.startsWith("off:") -> TvPinFunction.fromWire(what.removePrefix("off:"))?.let { onRequired(it, false) }
                }
            },
            onDismiss = { verifyFor = null },
        )
    }
    if (newPin) {
        TvNewPinDialog(onSet = {
            newPin = false
            onSetPin(it)
        }, onDismiss = { newPin = false })
    }
}

/** "Geteilter Tunnel" under Verbindung: the mode, then the apps (OK switches one). Applies from the next connect. */
@Composable
fun TvSplitTunnelOptions(
    split: TvSplitTunnelSettings,
    apps: List<TvInstalledApp>,
    onLoadApps: () -> Unit,
    onChange: (TvSplitTunnelSettings) -> Unit,
) {
    val colors = GateControlTvTheme.colors
    var open by rememberSaveable { mutableStateOf(false) }
    TvOptionRow(
        title = stringResource(R.string.tv_split),
        description = stringResource(
            when (split.mode) {
                SplitTunnelMode.OFF -> R.string.tv_split_off
                SplitTunnelMode.INCLUDE -> R.string.tv_split_include
                SplitTunnelMode.EXCLUDE -> R.string.tv_split_exclude
            },
        ),
        end = TvRowEnd.Arrow,
        onClick = {
            open = !open
            if (open) onLoadApps()
        },
    )
    if (!open) return
    val modes = listOf(SplitTunnelMode.OFF, SplitTunnelMode.EXCLUDE, SplitTunnelMode.INCLUDE)
    TvOptionRow(
        title = stringResource(R.string.tv_split_mode),
        description = stringResource(R.string.tv_split_next_connect),
        end = TvRowEnd.Arrow,
        onClick = { onChange(split.copy(mode = modes[(modes.indexOf(split.mode) + 1) % modes.size])) },
    )
    if (split.mode == SplitTunnelMode.OFF) return
    if (apps.isEmpty()) {
        Text(stringResource(R.string.tv_split_no_apps), style = MaterialTheme.typography.bodySmall, color = colors.muted)
    }
    for (app in apps) {
        val chosen = app.packageName in split.apps
        TvOptionRow(
            title = app.label,
            description = app.packageName,
            end = TvRowEnd.Switch(chosen),
            onClick = { onChange(split.copy(apps = if (chosen) split.apps - app.packageName else split.apps + app.packageName)) },
        )
    }
}
