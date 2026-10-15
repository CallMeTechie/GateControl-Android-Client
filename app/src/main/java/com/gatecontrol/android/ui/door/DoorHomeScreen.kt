package com.gatecontrol.android.ui.door

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.door.DoorCamera
import com.gatecontrol.android.door.DoorDest
import com.gatecontrol.android.door.DoorLock
import com.gatecontrol.android.door.DoorLogic
import com.gatecontrol.android.door.DoorOverview
import com.gatecontrol.android.door.DoorRight
import com.gatecontrol.android.door.DoorWindow
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.familie.FamilieLoading
import com.gatecontrol.android.ui.familie.appLocale
import com.gatecontrol.android.ui.familie.relativeStamp
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.format.TextStyle

/**
 * Door screen "Tür & Kamera" (opened from the start tab's door card or a
 * door push; no tab): the locks of this person with state, door sensor,
 * battery and their buttons, the camera pictures with "Neues Bild" and the
 * push switches, the Ring status and the way to the history. [onBack]
 * returns to the start tab; [selected] marks the screen shown beside it on
 * wide windows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoorHomeScreen(
    onOpen: (DoorDest) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    selected: DoorDest? = null,
    viewModel: DoorHomeViewModel = hiltViewModel(),
) {
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val fromCache by viewModel.fromCache.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snapshots by viewModel.snapshots.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // `GET me` every 10 s while the door screen is in front.
    LifecycleResumeEffect(viewModel) {
        viewModel.startPolling()
        onPauseOrDispose { viewModel.stopPolling() }
    }
    DoorNoticeSnackbar(viewModel.notice, snackbar)
    val start = rememberDoorActionStarter(onRun = viewModel::runAction, onNotice = viewModel::notify)

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PullToRefreshBox(isRefreshing = refreshing && overview != null, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                GcSubpageBar(
                    title = stringResource(R.string.door_screen_title),
                    onBack = onBack,
                    backDescription = stringResource(R.string.common_back),
                )
                Column(
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    RingStatus(overview)
                    val o = overview
                    when {
                        o == null && !loaded -> FamilieLoading()
                        o == null -> ErrorBanner(error, onRetry = viewModel::refresh)
                        else -> Content(
                            o, fromCache, busy, snapshots, selected,
                            onAction = start,
                            onSnapshot = viewModel::loadSnapshot,
                            onPref = viewModel::setPref,
                            onOpen = onOpen,
                        )
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

/** "Ring: inoffizielle Verbindung" / "gestört" / "nicht verbunden" under the title (with Ring or cameras). */
@Composable
private fun RingStatus(overview: DoorOverview?) {
    val extra = GateControlTheme.extraColors
    val ring = overview?.ring ?: return
    if (!(ring.connected || ring.broken || overview.cameras.isNotEmpty())) return
    Text(
        stringResource(
            when {
                ring.broken -> R.string.door_ring_broken
                ring.connected -> R.string.door_ring_unofficial
                else -> R.string.door_ring_off
            },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = if (ring.broken) MaterialTheme.colorScheme.error else extra.muted,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun ErrorBanner(code: String?, onRetry: () -> Unit) {
    GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(doorErrorText(code ?: "offline"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            GcOutlineButton(text = stringResource(R.string.familie_retry), onClick = onRetry, minHeight = 40.dp)
        }
    }
}

@Composable
private fun Content(
    o: DoorOverview,
    fromCache: Boolean,
    busy: String?,
    snapshots: Map<Long, SnapshotUi>,
    selected: DoorDest?,
    onAction: (DoorLock, String) -> Unit,
    onSnapshot: (Long, Boolean) -> Unit,
    onPref: (DoorCamera, Boolean?, Boolean?) -> Unit,
    onOpen: (DoorDest) -> Unit,
) {
    val extra = GateControlTheme.extraColors
    if (fromCache) {
        GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
            Text(stringResource(R.string.door_offline_cached), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
    o.me.window?.let { w ->
        Text(windowText(w), style = MaterialTheme.typography.bodySmall, color = if (o.me.windowOpen) extra.muted else MaterialTheme.colorScheme.error)
    }
    if (o.me.autoUnlock) {
        Text(stringResource(R.string.door_auto_unlock_on), style = MaterialTheme.typography.bodySmall, color = extra.muted)
    }

    if (o.locks.isEmpty()) {
        GcCard {
            Text(stringResource(R.string.door_empty), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
        }
    } else {
        GcSectionLabel(stringResource(R.string.door_locks), Modifier.padding(start = 4.dp))
        o.locks.forEach { lock ->
            LockCard(lock, canAct = o.me.canAct, busy = busy, onAction = onAction)
        }
    }

    if (o.cameras.isNotEmpty()) {
        GcSectionLabel(stringResource(R.string.door_cameras), Modifier.padding(start = 4.dp, top = 4.dp))
        o.cameras.forEach { cam ->
            CameraCard(cam, snapshots[cam.id], canAct = o.me.canAct, onSnapshot = onSnapshot, onPref = onPref)
        }
        if (o.ring.connected || o.ring.broken) {
            Text(stringResource(R.string.door_unofficial_hint), style = MaterialTheme.typography.bodySmall, color = extra.faint)
        }
    }

    GcCard(contentPadding = PaddingValues(vertical = 4.dp)) {
        GcListRow(
            title = stringResource(R.string.door_history),
            description = stringResource(R.string.door_history_desc),
            onClick = { onOpen(DoorDest.History) },
            selected = selected == DoorDest.History,
            leading = { GcIconSquare { Icon(GcIcons.Logs, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(22.dp)) } },
            trailing = { GcChevron() },
        )
    }
}

@Composable
private fun windowText(w: DoorWindow): String {
    val locale = appLocale()
    if (w.days == (1..7).toList()) return stringResource(R.string.door_window_daily, w.from, w.to)
    val days = w.days.joinToString(", ") { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, locale) }
    return stringResource(R.string.door_window, days, w.from, w.to)
}

/** A lock: state, door sensor, battery and one button per offered action. */
@Composable
fun LockCard(lock: DoorLock, canAct: Boolean, busy: String?, onAction: (DoorLock, String) -> Unit) {
    val extra = GateControlTheme.extraColors
    val kind = DoorLogic.stateKind(lock)
    val (fg, bg) = lockStateColors(kind)
    GcCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GcIconSquare(background = bg) { Icon(lockStateIcon(kind), contentDescription = null, tint = fg, modifier = Modifier.size(22.dp)) }
            Column(Modifier.weight(1f)) {
                Text(lock.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(lockStateRes(lock)), style = MaterialTheme.typography.bodyMedium, color = fg)
            }
        }
        val chips = buildList {
            lock.state.door?.let { add(stringResource(doorSensorRes(it)) to if (it == "open") GcTone.Warn else GcTone.Neutral) }
            val s = lock.state
            if (s.batteryCritical) add(stringResource(R.string.door_battery_critical) to GcTone.Error)
            else if (s.batteryPercent != null) {
                val text = stringResource(R.string.door_battery_pct, s.batteryPercent) +
                    if (s.batteryCharging) " · " + stringResource(R.string.door_battery_charging) else ""
                add(text to if (DoorLogic.batteryLow(s)) GcTone.Warn else GcTone.Neutral)
            }
            if (lock.right == DoorRight.VIEW) add(stringResource(R.string.door_right_view) to GcTone.Info)
        }
        if (chips.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach { (text, tone) -> GcChip(text, tone = tone, small = true) }
            }
        }
        lock.state.updatedAt?.let {
            Text(stringResource(R.string.door_updated, relativeStamp(it)), style = MaterialTheme.typography.bodySmall, color = extra.faint)
        }
        val buttons = DoorLogic.buttons(lock, canAct)
        if (buttons.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                buttons.forEach { b ->
                    val label = stringResource(actionLabelRes(lock, b.action))
                    val loading = busy == "${lock.id}:${b.action}"
                    val enabled = b.enabled && busy == null
                    if (b.action == com.gatecontrol.android.door.DoorAction.LOCK) {
                        GcOutlineButton(label, { onAction(lock, b.action) }, enabled = enabled, loading = loading, modifier = Modifier.weight(1f), icon = actionIcon(lock, b.action), minHeight = 44.dp)
                    } else {
                        GcPrimaryButton(label, { onAction(lock, b.action) }, enabled = enabled, loading = loading, modifier = Modifier.weight(1f), icon = actionIcon(lock, b.action), minHeight = 44.dp)
                    }
                }
            }
            buttons.firstOrNull { !it.enabled }?.blockedReason?.let { reason ->
                Text(stringResource(blockedRes(reason)), style = MaterialTheme.typography.bodySmall, color = extra.muted)
            }
        }
    }
}

/** A camera: latest picture, "Neues Bild" (with the wait the server asks for) and the push switches. */
@Composable
private fun CameraCard(
    cam: DoorCamera,
    snapshot: SnapshotUi?,
    canAct: Boolean,
    onSnapshot: (Long, Boolean) -> Unit,
    onPref: (DoorCamera, Boolean?, Boolean?) -> Unit,
) {
    val extra = GateControlTheme.extraColors
    // Seconds left before "Neues Bild" works again, counted down.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val retryUntil = snapshot?.retryUntilMs ?: 0L
    LaunchedEffect(retryUntil) {
        now = System.currentTimeMillis()
        while (now < retryUntil) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val wait = DoorLogic.secondsLeft(retryUntil, now)

    GcCard(contentPadding = PaddingValues(top = 16.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GcIconSquare { Icon(if (cam.doorbell) GcIcons.Bell else GcIcons.Camera, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(22.dp)) }
            Column(Modifier.weight(1f)) {
                Text(cam.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                val sub = buildList {
                    add(stringResource(if (cam.doorbell) R.string.door_camera_doorbell else R.string.door_camera_camera))
                    if (cam.online == false) add(stringResource(R.string.door_camera_offline))
                    cam.battery?.let { add(stringResource(R.string.door_battery_pct, it)) }
                }.joinToString(" · ")
                Text(sub, style = MaterialTheme.typography.bodySmall, color = if (cam.online == false) MaterialTheme.colorScheme.error else extra.muted)
            }
        }
        Box(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp))
                .background(extra.panel2),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = snapshot?.bitmap
            if (bmp != null) {
                Image(
                    bmp.asImageBitmap(),
                    contentDescription = stringResource(R.string.door_snapshot_desc, cam.name),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (snapshot?.loading != true) {
                Text(
                    if (snapshot?.error == null || snapshot.error == "no_image") stringResource(R.string.door_camera_no_image) else doorErrorText(snapshot.error),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                )
            }
            if (snapshot?.loading == true) FamilieLoading()
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                (snapshot?.at ?: cam.lastImageAt)?.let { stringResource(R.string.door_camera_image_at, relativeStamp(it)) }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                modifier = Modifier.weight(1f),
            )
            GcOutlineButton(
                text = if (wait > 0) stringResource(R.string.door_camera_retry_in, wait) else stringResource(R.string.door_camera_new_image),
                onClick = { onSnapshot(cam.id, true) },
                enabled = wait == 0,
                loading = snapshot?.loading == true,
                icon = GcIcons.Refresh,
                fillWidth = false,
                minHeight = 40.dp,
            )
        }
        GcRowDivider()
        Column {
            if (cam.doorbell) {
                GcSwitchRow(
                    label = stringResource(R.string.door_pref_ding),
                    description = null,
                    checked = cam.prefs.ding,
                    onCheckedChange = { onPref(cam, it, null) },
                    enabled = canAct,
                )
            }
            GcSwitchRow(
                label = stringResource(R.string.door_pref_motion),
                description = null,
                checked = cam.prefs.motion,
                onCheckedChange = { onPref(cam, null, it) },
                enabled = canAct,
            )
        }
    }
}
