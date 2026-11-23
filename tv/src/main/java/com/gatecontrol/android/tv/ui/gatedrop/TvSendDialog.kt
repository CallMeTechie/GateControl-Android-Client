package com.gatecontrol.android.tv.ui.gatedrop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.gatedrop.TvDropSend
import com.gatecontrol.android.tv.gatedrop.TvDropTarget
import com.gatecontrol.android.tv.ui.components.TvButton
import com.gatecontrol.android.tv.ui.components.TvDialog
import com.gatecontrol.android.tv.ui.components.TvOptionRow
import com.gatecontrol.android.tv.ui.components.TvRowEnd
import com.gatecontrol.android.tv.ui.drive.requestSoon
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import kotlinx.coroutines.launch

/**
 * "Senden an …" (GateDrop): the person's other devices first, then people. OK sends [name] there; the dialog says
 * when it went or why not.
 */
@Composable
fun TvSendDialog(
    name: String,
    targets: suspend () -> List<TvDropTarget>?,
    send: suspend (TvDropTarget) -> TvDropSend,
    onDismiss: () -> Unit,
) {
    val colors = GateControlTvTheme.colors
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<TvDropTarget>?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<TvDropSend?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        list = targets()
        loaded = true
    }
    LaunchedEffect(list, state) { first.requestSoon() }
    TvDialog(onDismiss = { if (state != TvDropSend.Busy) onDismiss() }) {
        Text(stringResource(R.string.tv_send_title, name), style = MaterialTheme.typography.headlineSmall, color = colors.text)
        when (val s = state) {
            TvDropSend.Busy -> Text(stringResource(R.string.tv_send_busy), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
            is TvDropSend.Done -> {
                Text(stringResource(R.string.tv_send_done, s.to), style = MaterialTheme.typography.bodyMedium, color = colors.accentText)
                TvButton(stringResource(R.string.tv_player_close_panel), onClick = onDismiss, modifier = Modifier.focusRequester(first))
            }
            is TvDropSend.Failed -> {
                Text(stringResource(R.string.tv_send_failed), style = MaterialTheme.typography.bodyMedium, color = colors.warn)
                TvButton(stringResource(R.string.tv_player_close_panel), onClick = onDismiss, modifier = Modifier.focusRequester(first))
            }
            null -> {
                val l = list
                when {
                    !loaded -> Text(stringResource(R.string.tv_drive_loading), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                    l == null -> Text(stringResource(R.string.tv_drop_not_on), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                    l.isEmpty() -> Text(stringResource(R.string.tv_send_none), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                    else -> Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        l.forEachIndexed { i, t ->
                            TvOptionRow(
                                title = t.name,
                                description = stringResource(
                                    when {
                                        !t.device -> R.string.tv_send_person
                                        t.online -> R.string.tv_send_device_online
                                        else -> R.string.tv_send_device
                                    },
                                ),
                                end = TvRowEnd.Arrow,
                                onClick = {
                                    state = TvDropSend.Busy
                                    scope.launch { state = send(t) }
                                },
                                modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                            )
                        }
                    }
                }
                if (loaded && l.isNullOrEmpty()) TvButton(stringResource(R.string.tv_player_close_panel), onClick = onDismiss, modifier = Modifier.focusRequester(first))
            }
        }
    }
}
