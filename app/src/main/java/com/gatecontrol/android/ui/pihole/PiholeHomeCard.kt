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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R

@Composable
fun PiholeHomeCard(
    onOpen: () -> Unit,
    viewModel: PiholeViewModel = hiltViewModel()
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val s = ui.summary

    Card(Modifier.fillMaxWidth().clickable { onOpen() }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.pihole_title), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                val state = s?.blocking?.state ?: "unknown"
                val timer = s?.blocking?.timer
                val statusText = if (state == "disabled" && timer != null && timer > 0)
                    stringResource(R.string.pihole_paused_remaining, timer)
                else piholeStatusLabel(state)
                Text(statusText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (s != null) {
                Text(
                    "${stringResource(R.string.pihole_blocked)}: ${s.queries?.blocked ?: 0}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (ui.canControl) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.gatecontrol.android.ui.components.GcOutlineButton(
                        text = stringResource(R.string.pihole_pause_5m),
                        onClick = { viewModel.pauseBlocking(300) },
                        enabled = !ui.actionPending,
                        modifier = Modifier.weight(1f),
                    )
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

@Composable
private fun piholeStatusLabel(state: String): String = when (state) {
    "enabled" -> stringResource(R.string.pihole_status_enabled)
    "disabled" -> stringResource(R.string.pihole_status_disabled)
    "partial" -> stringResource(R.string.pihole_status_partial)
    else -> stringResource(R.string.pihole_status_unknown)
}
