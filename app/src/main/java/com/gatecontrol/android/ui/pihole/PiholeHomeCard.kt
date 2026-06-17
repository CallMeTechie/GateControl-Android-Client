package com.gatecontrol.android.ui.pihole

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.util.findComponentActivity
import kotlinx.coroutines.delay

@Composable
fun PiholeHomeCard(
    onOpen: () -> Unit,
    viewModel: PiholeViewModel =
        hiltViewModel(androidx.compose.ui.platform.LocalContext.current.findComponentActivity())
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val s = ui.summary

    // Foreground polling ~30s while this card is in composition (mirrors PiholeScreen).
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            viewModel.refresh()
        }
    }

    val end = ui.pauseEndAtMillis
    val remaining by androidx.compose.runtime.produceState(
        initialValue = end?.let { ((it - System.currentTimeMillis()) / 1000).coerceAtLeast(0L).toInt() } ?: 0,
        end,
    ) {
        while (end != null) {
            val rem = ((end - System.currentTimeMillis()) / 1000).coerceAtLeast(0L)
            value = rem.toInt()
            if (rem <= 0L) { viewModel.onPauseExpired(); break }
            delay(1000)
        }
    }
    val isFinitePaused = end != null
    val isPaused = isFinitePaused || ui.pausePermanent

    Card(Modifier.fillMaxWidth().clickable { onOpen() }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.pihole_title), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                val statusText = when {
                    isFinitePaused -> stringResource(R.string.pihole_paused_mmss, formatMmSs(remaining))
                    ui.pausePermanent -> stringResource(R.string.pihole_paused)
                    else -> piholeStatusLabel(s?.blocking?.state ?: "unknown")
                }
                Text(statusText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (ui.canControl) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.gatecontrol.android.ui.components.GcOutlineButton(
                        text = if (isFinitePaused) formatMmSs(remaining)
                               else if (ui.pausePermanent) stringResource(R.string.pihole_paused)
                               else stringResource(R.string.pihole_pause_5m),
                        onClick = { viewModel.pauseBlocking(300) },
                        enabled = !ui.actionPending && !isPaused,
                        modifier = Modifier.weight(1f),
                    )
                    if (isPaused) {
                        com.gatecontrol.android.ui.components.GcOutlineButton(
                            text = stringResource(R.string.pihole_resume),
                            onClick = { viewModel.resumeBlocking() },
                            enabled = !ui.actionPending,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
