package com.gatecontrol.android.ui.pihole

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.util.findComponentActivity
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiholeScreen(
    viewModel: PiholeViewModel =
        hiltViewModel(androidx.compose.ui.platform.LocalContext.current.findComponentActivity())
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()

    // Foreground polling ~30s (lifecycle-bound: stops when screen leaves composition).
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            viewModel.refresh()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.pihole_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))

        when {
            ui.isLoading -> androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            !ui.everLoaded && ui.summary == null -> Text(stringResource(R.string.pihole_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> PullToRefreshBox(isRefreshing = ui.isRefreshing, onRefresh = { viewModel.refresh() }) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { SummaryCards(ui) }
                    item {
                        PiholeHistoryChart(
                            allowed = ui.history.map { it.allowed },
                            blocked = ui.history.map { it.blocked },
                        )
                    }
                    if (ui.canControl) item { ControlCard(ui, viewModel) }
                    item { SectionTitle(stringResource(R.string.pihole_top_domains)) }
                    items(ui.topDomains) { d ->
                        KeyValueRow(d.domain, d.count.toString())
                    }
                    item { SectionTitle(stringResource(R.string.pihole_top_clients)) }
                    items(ui.topClients) { c ->
                        KeyValueRow(c.peerName ?: c.ip, c.count.toString())
                    }
                    item { SectionTitle(stringResource(R.string.pihole_query_types)) }
                    val qtMax = (ui.queryTypes.values.maxOrNull() ?: 1L).coerceAtLeast(1L)
                    val sortedQueryTypes: List<Pair<String, Long>> =
                        ui.queryTypes.entries.sortedByDescending { it.value }.map { it.key to it.value }
                    items(sortedQueryTypes) { (type, count) ->
                        QueryTypeMeter(type, count, qtMax)
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCards(ui: PiholeUiState) {
    val s = ui.summary ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyValueRow(stringResource(R.string.pihole_queries), (s.queries?.total ?: 0).toString())
            KeyValueRow(
                stringResource(R.string.pihole_blocked),
                "${s.queries?.blocked ?: 0} (${s.queries?.percent ?: 0.0}%)"
            )
            KeyValueRow(stringResource(R.string.pihole_blocklist), (s.gravity ?: 0).toString())
            KeyValueRow(stringResource(R.string.pihole_active_clients), (s.clients?.active ?: 0).toString())
            // Status + sync age (lastSyncAt is epoch ms from the server).
            val syncAge = s.lastSyncAt?.let { ((System.currentTimeMillis() - it) / 1000).coerceAtLeast(0) }
            val statusText = if (syncAge != null)
                "${piholeStatusLabel(s.blocking?.state ?: "unknown")} · ${stringResource(R.string.pihole_synced_ago, syncAge)}"
            else piholeStatusLabel(s.blocking?.state ?: "unknown")
            KeyValueRow(stringResource(R.string.pihole_status), statusText)
        }
    }
}

@Composable
private fun ControlCard(ui: PiholeUiState, viewModel: PiholeViewModel) {
    val end = ui.pauseEndAtMillis
    // UI-driven countdown: derive remaining seconds from the absolute end time once per second.
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
    val isPermanentPaused = ui.pausePermanent
    val isPaused = isFinitePaused || isPermanentPaused

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.pihole_control))
            if (ui.actionPending) {
                Text(stringResource(R.string.pihole_applying), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (ui.error != null) {
                Text(stringResource(R.string.pihole_action_failed), color = MaterialTheme.colorScheme.error)
            }

            if (isFinitePaused && ui.pausedPresetSec == null) {
                // Generic fallback: server timer with no matching preset.
                Text(
                    stringResource(R.string.pihole_paused_mmss, formatMmSs(remaining)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                @Composable
                fun presetButton(label: String, sec: Int?, modifier: Modifier) {
                    val isThisPreset =
                        (sec == null && isPermanentPaused) || (sec != null && isFinitePaused && ui.pausedPresetSec == sec)
                    val text = when {
                        sec == null && isPermanentPaused -> stringResource(R.string.pihole_paused)
                        sec != null && isThisPreset -> formatMmSs(remaining)
                        else -> label
                    }
                    com.gatecontrol.android.ui.components.GcOutlineButton(
                        text = text,
                        onClick = { viewModel.pauseBlocking(sec) },
                        enabled = !ui.actionPending && !isPaused,
                        modifier = modifier,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presetButton(stringResource(R.string.pihole_pause_30s), 30, Modifier.weight(1f))
                    presetButton(stringResource(R.string.pihole_pause_5m), 300, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presetButton(stringResource(R.string.pihole_pause_30m), 1800, Modifier.weight(1f))
                    presetButton(stringResource(R.string.pihole_pause_forever), null, Modifier.weight(1f))
                }
            }

            if (isPaused) {
                com.gatecontrol.android.ui.components.GcPrimaryButton(
                    text = stringResource(R.string.pihole_resume),
                    onClick = { viewModel.resumeBlocking() },
                    enabled = !ui.actionPending,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
}

@Composable
private fun KeyValueRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun QueryTypeMeter(type: String, count: Long, max: Long) {
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(type, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(count.toString(), color = MaterialTheme.colorScheme.onBackground)
        }
        androidx.compose.material3.LinearProgressIndicator(
            progress = { (count.toFloat() / max.toFloat()).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
