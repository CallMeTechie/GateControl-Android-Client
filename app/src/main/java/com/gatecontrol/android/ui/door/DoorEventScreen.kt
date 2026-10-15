package com.gatecontrol.android.ui.door

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.door.DoorAction
import com.gatecontrol.android.door.DoorHistoryItem
import com.gatecontrol.android.door.DoorLogic
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.familie.FamilieLoading
import com.gatecontrol.android.ui.familie.relativeStamp
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * One ring or motion event (push "Ansehen", history): the picture, when
 * and where, and — for people with the `open` right — "Tür öffnen" /
 * "Summer" behind a confirmation and the device unlock.
 */
@Composable
fun DoorEventScreen(
    eventId: Long,
    onBack: () -> Unit,
    viewModel: DoorEventViewModel = hiltViewModel(key = "door-event-$eventId"),
) {
    LaunchedEffect(eventId) { viewModel.show(eventId) }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val extra = GateControlTheme.extraColors
    val snackbar = remember { SnackbarHostState() }
    DoorNoticeSnackbar(viewModel.notice, snackbar)
    val start = rememberDoorActionStarter(onRun = { lock, _ -> viewModel.open(lock) }, onNotice = viewModel::notify)
    val item = ui.detail?.item

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            GcSubpageBar(
                title = stringResource(if (item?.kind == DoorHistoryItem.KIND_MOTION) R.string.door_event_motion_title else R.string.door_event_ding_title),
                onBack = onBack,
                backDescription = stringResource(R.string.common_back),
            )
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when {
                    item == null && ui.loading -> FamilieLoading()
                    item == null -> {
                        Text(doorErrorText(ui.error ?: "not_found"), style = MaterialTheme.typography.bodyLarge, color = extra.muted)
                        GcOutlineButton(text = stringResource(R.string.familie_retry), onClick = viewModel::load)
                    }
                    else -> {
                        val bmp = ui.picture
                        if (bmp != null) {
                            Image(
                                bmp.asImageBitmap(),
                                contentDescription = stringResource(R.string.door_snapshot_desc, item.cameraName ?: item.lockName.orEmpty()),
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
                            )
                        } else {
                            Box(
                                Modifier.fillMaxWidth().heightIn(min = 120.dp).clip(RoundedCornerShape(16.dp)).background(extra.panel2),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(stringResource(R.string.door_event_no_image), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
                            }
                        }
                        val where = listOfNotNull(item.cameraName, item.lockName).distinct().joinToString(" · ")
                        if (where.isNotEmpty()) Text(where, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                        Text(relativeStamp(item.at), style = MaterialTheme.typography.bodyMedium, color = extra.muted)

                        val lock = ui.lock
                        if (lock != null) {
                            Text(
                                lock.name + " · " + stringResource(lockStateRes(lock)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = lockStateColors(DoorLogic.stateKind(lock)).first,
                            )
                        }
                        if (lock != null && DoorLogic.canOpenFromEvent(lock)) {
                            val buzzer = lock.isOpener
                            GcPrimaryButton(
                                text = stringResource(if (buzzer) R.string.door_event_buzzer else R.string.door_event_open),
                                onClick = { start(lock, DoorAction.OPEN) },
                                enabled = lock.blocked == null,
                                loading = ui.busy,
                                icon = if (buzzer) GcIcons.Bell else GcIcons.Door,
                            )
                            lock.blocked?.let { Text(stringResource(blockedRes(it)), style = MaterialTheme.typography.bodySmall, color = extra.muted) }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}
