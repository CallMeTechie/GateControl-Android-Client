package com.gatecontrol.android.ui.door

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.door.DoorAction
import com.gatecontrol.android.door.DoorDest
import com.gatecontrol.android.door.DoorHistoryItem
import com.gatecontrol.android.door.DoorLogic
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.familie.FamilieLoading
import com.gatecontrol.android.ui.familie.relativeStamp
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * "Verlauf" of the door screens: newest first, paged, filter alle / Aktionen /
 * Klingeln. Rings and motion open their detail with the picture.
 * Pictures of the history are never stored on the device.
 */
@Composable
fun DoorHistoryScreen(
    onBack: () -> Unit,
    onOpen: (DoorDest) -> Unit,
    selected: DoorDest? = null,
    viewModel: DoorHistoryViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val extra = GateControlTheme.extraColors
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        GcSubpageBar(title = stringResource(R.string.door_history), onBack = onBack, backDescription = stringResource(R.string.common_back))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DoorLogic.HistoryFilter.entries.forEach { f ->
                GcFilterChip(
                    text = stringResource(
                        when (f) {
                            DoorLogic.HistoryFilter.ALL -> R.string.door_filter_all
                            DoorLogic.HistoryFilter.ACTIONS -> R.string.door_filter_actions
                            DoorLogic.HistoryFilter.RINGS -> R.string.door_filter_rings
                        },
                    ),
                    selected = ui.filter == f,
                    onClick = { viewModel.setFilter(f) },
                )
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ui.error?.let { code ->
                item(key = "error") { Text(doorErrorText(code), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            }
            if (ui.items.isEmpty()) {
                item(key = "empty") {
                    if (ui.loading || !ui.loaded) FamilieLoading()
                    else Text(stringResource(R.string.door_history_empty), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
                }
            }
            items(ui.items, key = { it.id }) { item ->
                GcCard(contentPadding = PaddingValues(vertical = 2.dp)) {
                    HistoryRow(item, selected = (selected as? DoorDest.Event)?.eventId == item.id, onOpen = onOpen)
                }
            }
            if (ui.nextCursor != null) {
                item(key = "more") {
                    GcOutlineButton(text = stringResource(R.string.door_history_more), onClick = viewModel::loadMore, loading = ui.loading)
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(item: DoorHistoryItem, selected: Boolean, onOpen: (DoorDest) -> Unit) {
    val extra = GateControlTheme.extraColors
    val (icon, tint) = when (item.kind) {
        DoorHistoryItem.KIND_DING -> GcIcons.Bell to extra.blue
        DoorHistoryItem.KIND_MOTION -> GcIcons.Camera to extra.blue
        DoorHistoryItem.KIND_ALERT -> GcIcons.Alert to extra.warn
        else -> (if (item.action == DoorAction.LOCK) GcIcons.Lock else GcIcons.LockOpen) to extra.accentText
    }
    val title = historyTitle(item)
    val description = buildList {
        (item.lockName ?: item.cameraName)?.let { add(it) }
        item.userName?.let { add(it) }
        item.via?.let { v -> viaRes(v)?.let { add(stringResource(it)) } }
        add(relativeStamp(item.at))
    }.joinToString(" · ")
    GcListRow(
        title = title,
        description = description,
        selected = selected,
        onClick = if (item.hasDetail) ({ onOpen(DoorDest.Event(item.id)) }) else null,
        leading = { GcIconSquare { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp)) } },
        trailing = {
            when {
                item.result == "refused" -> GcChip(stringResource(R.string.door_result_refused), tone = GcTone.Warn, small = true)
                item.result == "failed" -> GcChip(stringResource(R.string.door_result_failed), tone = GcTone.Error, small = true)
                item.hasDetail -> GcChevron()
            }
        },
    )
    item.reasonText?.takeIf { item.result != "ok" }?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = extra.muted, modifier = Modifier.padding(start = 74.dp, end = 16.dp, bottom = 8.dp))
    }
}

@Composable
private fun historyTitle(item: DoorHistoryItem): String = when (item.kind) {
    DoorHistoryItem.KIND_DING -> stringResource(R.string.door_kind_ding)
    DoorHistoryItem.KIND_MOTION -> stringResource(R.string.door_kind_motion)
    DoorHistoryItem.KIND_ALERT -> stringResource(
        when (item.action) {
            "open_long" -> R.string.door_alert_open_long
            "jammed" -> R.string.door_alert_jammed
            "unreachable" -> R.string.door_alert_unreachable
            "night" -> R.string.door_alert_night
            else -> R.string.door_kind_alert
        },
    )
    else -> when (item.action) {
        DoorAction.LOCK -> stringResource(R.string.door_did_lock)
        DoorAction.UNLOCK -> stringResource(R.string.door_did_unlock)
        DoorAction.OPEN -> stringResource(R.string.door_did_open)
        else -> stringResource(R.string.door_kind_nuki)
    }
}

@StringRes
private fun viaRes(via: String): Int? = when (via) {
    "app" -> R.string.door_via_app
    "portal" -> R.string.door_via_portal
    "notification" -> R.string.door_via_notification
    "automation" -> R.string.door_via_automation
    "system" -> R.string.door_via_system
    "nuki" -> R.string.door_via_nuki
    "keypad" -> R.string.door_via_keypad
    "ring" -> R.string.door_via_ring
    else -> null
}
