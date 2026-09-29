package com.gatecontrol.android.ui.pihole

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcStatCard
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily
import com.gatecontrol.android.util.findComponentActivity
import kotlinx.coroutines.delay
import java.text.NumberFormat

/** Pi-hole view inside the "Netzwerk" tab (no own title; the tab provides it). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiholeContent(
    modifier: Modifier = Modifier,
    viewModel: PiholeViewModel =
        hiltViewModel(androidx.compose.ui.platform.LocalContext.current.findComponentActivity()),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val extra = GateControlTheme.extraColors

    // Foreground polling ~30s (lifecycle-bound: stops when screen leaves composition).
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            viewModel.refresh()
        }
    }

    when {
        ui.isLoading -> Box(modifier.fillMaxSize()) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.primary)
        }
        !ui.everLoaded && ui.summary == null -> Text(
            stringResource(R.string.pihole_empty),
            color = extra.muted,
            modifier = modifier.padding(16.dp),
        )
        else -> PullToRefreshBox(
            isRefreshing = ui.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = modifier,
        ) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item { StatusCard(ui, viewModel) }
                item { SummaryGrid(ui) }
                item {
                    PiholeHistoryChart(
                        allowed = ui.history.map { it.allowed },
                        blocked = ui.history.map { it.blocked },
                    )
                }
                if (ui.canControl) item { ControlCard(ui, viewModel) }
                if (ui.topDomains.isNotEmpty()) {
                    item {
                        ListCard(
                            title = stringResource(R.string.pihole_top_domains),
                            rows = ui.topDomains.map { it.domain to it.count.toString() },
                        )
                    }
                }
                if (ui.topClients.isNotEmpty()) {
                    item {
                        ListCard(
                            title = stringResource(R.string.pihole_top_clients),
                            rows = ui.topClients.map { (it.peerName ?: it.ip) to it.count.toString() },
                        )
                    }
                }
                if (ui.queryTypes.isNotEmpty()) {
                    item { QueryTypesCard(ui.queryTypes) }
                }
            }
        }
    }
}

@Composable
private fun rememberPauseRemaining(ui: PiholeUiState, viewModel: PiholeViewModel): Int {
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
    return remaining
}

@Composable
private fun StatusCard(ui: PiholeUiState, viewModel: PiholeViewModel) {
    val extra = GateControlTheme.extraColors
    val s = ui.summary
    val remaining = rememberPauseRemaining(ui, viewModel)
    val paused = ui.pauseEndAtMillis != null || ui.pausePermanent
    val state = s?.blocking?.state ?: "unknown"
    val active = !paused && state == "enabled"
    val title = when {
        ui.pauseEndAtMillis != null -> stringResource(R.string.pihole_paused_mmss, formatMmSs(remaining))
        ui.pausePermanent -> stringResource(R.string.pihole_paused)
        state == "enabled" -> stringResource(R.string.pihole_tile_blocking)
        else -> piholeStatusLabel(state)
    }
    val syncAge = s?.lastSyncAt?.let { ((System.currentTimeMillis() - it) / 1000).coerceAtLeast(0) }

    GcCard(color = if (active) extra.accentBg else if (paused) extra.warnBg else MaterialTheme.colorScheme.surface) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GcIconSquare(size = 48.dp, background = MaterialTheme.colorScheme.surface) {
                Icon(
                    GcIcons.ShieldX,
                    contentDescription = null,
                    tint = if (active) extra.accentText else extra.warn,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                if (syncAge != null) {
                    Text(
                        stringResource(R.string.pihole_synced_ago, syncAge),
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryGrid(ui: PiholeUiState) {
    val s = ui.summary ?: return
    val nf = NumberFormat.getIntegerInstance()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GcStatCard(stringResource(R.string.pihole_queries), nf.format(s.queries?.total ?: 0), modifier = Modifier.weight(1f))
            GcStatCard(
                stringResource(R.string.pihole_blocked),
                nf.format(s.queries?.blocked ?: 0),
                subtitle = "%.1f %%".format(s.queries?.percent ?: 0.0),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GcStatCard(stringResource(R.string.pihole_blocklist), nf.format(s.gravity ?: 0), modifier = Modifier.weight(1f))
            GcStatCard(stringResource(R.string.pihole_active_clients), nf.format(s.clients?.active ?: 0), modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ControlCard(ui: PiholeUiState, viewModel: PiholeViewModel) {
    val extra = GateControlTheme.extraColors
    val remaining = rememberPauseRemaining(ui, viewModel)
    val isFinitePaused = ui.pauseEndAtMillis != null
    val isPermanentPaused = ui.pausePermanent
    val isPaused = isFinitePaused || isPermanentPaused

    GcCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.pihole_pause_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        if (ui.actionPending) {
            Text(stringResource(R.string.pihole_applying), style = MaterialTheme.typography.bodySmall, color = extra.muted)
        }
        if (ui.error != null) {
            Text(stringResource(R.string.pihole_action_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (isFinitePaused && ui.pausedPresetSec == null) {
            // Generic fallback: server timer with no matching preset.
            Text(stringResource(R.string.pihole_paused_mmss, formatMmSs(remaining)), color = extra.muted)
        } else {
            val presets = listOf(
                30 to stringResource(R.string.pihole_chip_30s),
                300 to stringResource(R.string.pihole_chip_5m),
                1800 to stringResource(R.string.pihole_chip_30m),
                null to stringResource(R.string.pihole_pause_forever),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { (sec, label) ->
                    val isThis = (sec == null && isPermanentPaused) || (sec != null && isFinitePaused && ui.pausedPresetSec == sec)
                    GcFilterChip(
                        text = if (isThis && sec != null) formatMmSs(remaining) else label,
                        selected = isThis,
                        onClick = { if (!ui.actionPending && !isPaused) viewModel.pauseBlocking(sec) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (isPaused) {
            GcPrimaryButton(
                text = stringResource(R.string.pihole_resume),
                onClick = { viewModel.resumeBlocking() },
                enabled = !ui.actionPending,
            )
        }
    }
}

@Composable
private fun ListCard(title: String, rows: List<Pair<String, String>>) {
    val extra = GateControlTheme.extraColors
    GcCard(contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.Top) {
        GcSectionLabel(title, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        rows.forEach { (k, v) ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    k,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(v, style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily), color = extra.muted)
            }
        }
    }
}

@Composable
private fun QueryTypesCard(queryTypes: Map<String, Long>) {
    val extra = GateControlTheme.extraColors
    val max = (queryTypes.values.maxOrNull() ?: 1L).coerceAtLeast(1L)
    GcCard(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GcSectionLabel(stringResource(R.string.pihole_query_types))
        queryTypes.entries.sortedByDescending { it.value }.forEach { (type, count) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(type, style = MaterialTheme.typography.bodySmall, color = extra.muted)
                    Text(count.toString(), style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily), color = MaterialTheme.colorScheme.onSurface)
                }
                Box(
                    Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(extra.panel2),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((count.toFloat() / max).coerceIn(0f, 1f))
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(extra.blue),
                    )
                }
            }
        }
    }
}
