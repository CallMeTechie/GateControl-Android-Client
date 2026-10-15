package com.gatecontrol.android.ui.door

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.door.DoorAction
import com.gatecontrol.android.door.DoorBlocked
import com.gatecontrol.android.door.DoorLock
import com.gatecontrol.android.door.DoorLogic
import com.gatecontrol.android.ui.components.DeviceAuthResult
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.rememberGcDeviceAuth
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.flow.Flow

/** Label of an action button ("Zusperren", "Aufsperren", "Öffnen", opener: "Summer"). */
@StringRes
fun actionLabelRes(lock: DoorLock, action: String): Int = when (action) {
    DoorAction.LOCK -> R.string.door_action_lock
    DoorAction.UNLOCK -> R.string.door_action_unlock
    else -> if (DoorLogic.isBuzzer(lock, action)) R.string.door_action_buzzer else R.string.door_action_open
}

fun actionIcon(lock: DoorLock, action: String): ImageVector = when (action) {
    DoorAction.LOCK -> GcIcons.Lock
    DoorAction.UNLOCK -> GcIcons.LockOpen
    else -> if (lock.isOpener) GcIcons.Bell else GcIcons.Door
}

@StringRes
fun lockStateRes(lock: DoorLock): Int {
    if (!lock.state.reachable) return R.string.door_state_unreachable
    return when (lock.state.lock) {
        "locked" -> R.string.door_state_locked
        "unlocked" -> R.string.door_state_unlocked
        "unlatched" -> R.string.door_state_unlatched
        "locking" -> R.string.door_state_locking
        "unlocking" -> R.string.door_state_unlocking
        "unlatching" -> R.string.door_state_unlatching
        "jammed" -> R.string.door_state_jammed
        "uncalibrated" -> R.string.door_state_uncalibrated
        "online" -> R.string.door_state_online
        "rto_active" -> R.string.door_state_rto_active
        "open" -> R.string.door_state_open
        "opening" -> R.string.door_state_opening
        "untrained" -> R.string.door_state_untrained
        "boot_run" -> R.string.door_state_boot_run
        else -> R.string.door_state_unknown
    }
}

fun lockStateIcon(kind: DoorLogic.StateKind): ImageVector = when (kind) {
    DoorLogic.StateKind.LOCKED -> GcIcons.Lock
    DoorLogic.StateKind.UNLOCKED, DoorLogic.StateKind.OPEN -> GcIcons.LockOpen
    DoorLogic.StateKind.MOVING -> GcIcons.Refresh
    DoorLogic.StateKind.READY -> GcIcons.Door
    DoorLogic.StateKind.PROBLEM, DoorLogic.StateKind.UNREACHABLE -> GcIcons.Alert
    DoorLogic.StateKind.UNKNOWN -> GcIcons.Door
}

/** Foreground and background of a state (locked green, open orange, problem red). */
@Composable
fun lockStateColors(kind: DoorLogic.StateKind): Pair<Color, Color> {
    val extra = GateControlTheme.extraColors
    return when (kind) {
        DoorLogic.StateKind.LOCKED, DoorLogic.StateKind.READY -> extra.accentText to extra.accentBg
        DoorLogic.StateKind.UNLOCKED, DoorLogic.StateKind.OPEN -> extra.warn to extra.warnBg
        DoorLogic.StateKind.MOVING -> extra.blue to extra.blueBg
        DoorLogic.StateKind.PROBLEM, DoorLogic.StateKind.UNREACHABLE -> MaterialTheme.colorScheme.error to extra.errorBg
        DoorLogic.StateKind.UNKNOWN -> extra.muted to extra.panel2
    }
}

@StringRes
fun doorSensorRes(door: String): Int = when (door) {
    "open" -> R.string.door_sensor_open
    "closed" -> R.string.door_sensor_closed
    "unknown" -> R.string.door_sensor_unknown
    else -> R.string.door_sensor_unavailable
}

@StringRes
fun blockedRes(reason: String?): Int = when (reason) {
    DoorBlocked.READ_ONLY -> R.string.door_blocked_read_only
    DoorBlocked.OUTSIDE_WINDOW -> R.string.door_blocked_outside_window
    else -> R.string.door_blocked_other
}

/** Text of an error code of the door API. */
@Composable
fun doorErrorText(code: String, retryAfter: Int? = null): String = when (code) {
    "offline", "http_0" -> stringResource(R.string.door_error_offline)
    "no_right", "forbidden", "auto_no_right" -> stringResource(R.string.door_error_no_right)
    "read_only" -> stringResource(R.string.door_error_read_only)
    "outside_window" -> stringResource(R.string.door_blocked_outside_window)
    "rate_limited", "too_many" -> stringResource(R.string.door_error_rate_limited)
    "too_soon", "ring_rate_limited" -> stringResource(R.string.door_error_too_soon, retryAfter ?: 15)
    "no_image" -> stringResource(R.string.door_camera_no_image)
    "ring_not_connected" -> stringResource(R.string.door_error_ring_not_connected)
    "lock_unreachable", "unreachable" -> stringResource(R.string.door_error_lock_unreachable)
    "lock_failed", "nuki_error", "failed" -> stringResource(R.string.door_error_lock_failed)
    "not_found", "http_404" -> stringResource(R.string.door_error_not_found)
    "unsupported", "not_configured", "no_target" -> stringResource(R.string.door_error_unsupported)
    else -> stringResource(R.string.door_error_generic, code)
}

/** Text of a one-shot notice. */
@Composable
fun doorNoticeText(notice: DoorNotice): String = when (notice) {
    is DoorNotice.Text -> notice.text
    // Codes the app knows get its own wording; else the plugin's text.
    is DoorNotice.Error -> if (notice.text != null && notice.code !in KNOWN_CODES) notice.text else doorErrorText(notice.code, notice.retryAfter)
    DoorNotice.Done -> stringResource(R.string.door_done)
    DoorNotice.AuthUnavailable -> stringResource(R.string.door_auth_unavailable)
    DoorNotice.AuthFailed -> stringResource(R.string.door_auth_failed)
}

private val KNOWN_CODES = setOf(
    "offline", "no_right", "forbidden", "read_only", "outside_window", "rate_limited", "too_many", "too_soon",
    "ring_rate_limited", "no_image", "ring_not_connected", "lock_unreachable", "lock_failed", "not_found", "unsupported",
)

/** Shows [notices] in [host] (the result snackbar of the door screens). */
@Composable
fun DoorNoticeSnackbar(notices: Flow<DoorNotice>, host: SnackbarHostState) {
    var current by remember { mutableStateOf<DoorNotice?>(null) }
    LaunchedEffect(notices) { notices.collect { current = it } }
    val text = current?.let { doorNoticeText(it) }
    LaunchedEffect(current) {
        if (text != null) {
            host.currentSnackbarData?.dismiss()
            host.showSnackbar(text)
            current = null
        }
    }
}

/**
 * Starts a lock action the safe way: the confirm dialog when the plugin
 * asks for one, then the device unlock (biometrics or screen lock) for
 * unlock/open (DoorLogic.steps), then [onRun]. Without a screen lock the
 * action is refused ([onNotice] AuthUnavailable).
 */
@Composable
fun rememberDoorActionStarter(onRun: (DoorLock, String) -> Unit, onNotice: (DoorNotice) -> Unit): (DoorLock, String) -> Unit {
    val auth = rememberGcDeviceAuth()
    val resources = LocalResources.current
    val run by rememberUpdatedState(onRun)
    val notice by rememberUpdatedState(onNotice)
    var confirm by remember { mutableStateOf<Pair<DoorLock, String>?>(null) }

    fun afterConfirm(lock: DoorLock, action: String) {
        if (DoorLogic.Step.DEVICE_AUTH !in DoorLogic.steps(lock, action)) {
            run(lock, action)
            return
        }
        auth.authenticate(
            title = resources.getString(R.string.door_auth_title),
            subtitle = resources.getString(R.string.door_auth_subtitle, lock.name, resources.getString(actionLabelRes(lock, action))),
        ) { result ->
            when (result) {
                DeviceAuthResult.SUCCESS -> run(lock, action)
                DeviceAuthResult.CANCELLED -> Unit
                DeviceAuthResult.FAILED -> notice(DoorNotice.AuthFailed)
                DeviceAuthResult.UNAVAILABLE -> notice(DoorNotice.AuthUnavailable)
            }
        }
    }

    confirm?.let { (lock, action) ->
        val (title, text) = when {
            action == DoorAction.LOCK -> R.string.door_confirm_lock_title to R.string.door_confirm_lock_text
            action == DoorAction.UNLOCK -> R.string.door_confirm_unlock_title to R.string.door_confirm_unlock_text
            DoorLogic.isBuzzer(lock, action) -> R.string.door_confirm_buzzer_title to R.string.door_confirm_buzzer_text
            else -> R.string.door_confirm_open_title to R.string.door_confirm_open_text
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.extraLarge,
            icon = { Icon(actionIcon(lock, action), contentDescription = null) },
            title = { Text(stringResource(title, lock.name), style = MaterialTheme.typography.titleLarge) },
            text = { Text(stringResource(text), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    afterConfirm(lock, action)
                }) { Text(stringResource(actionLabelRes(lock, action))) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    return { lock, action ->
        val steps = DoorLogic.steps(lock, action)
        when {
            steps.isEmpty() -> Unit
            DoorLogic.Step.DEVICE_AUTH in steps && !auth.available() -> notice(DoorNotice.AuthUnavailable)
            DoorLogic.Step.CONFIRM in steps -> confirm = lock to action
            else -> afterConfirm(lock, action)
        }
    }
}

/** Detail pane of the door tab before something is picked. */
@Composable
fun DoorPanePlaceholder() {
    val extra = GateControlTheme.extraColors
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(GcIcons.Door, contentDescription = null, tint = extra.faint, modifier = Modifier.size(40.dp))
        Text(
            stringResource(R.string.door_pane_pick),
            style = MaterialTheme.typography.bodyLarge,
            color = extra.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
