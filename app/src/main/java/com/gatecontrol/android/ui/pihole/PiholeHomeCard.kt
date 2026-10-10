package com.gatecontrol.android.ui.pihole

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcTile
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.util.findComponentActivity
import kotlinx.coroutines.delay

/**
 * Pi-hole quick tile on the start screen: tinted while blocking is active,
 * warn-colored while paused (with the remaining time). Opens the Pi-hole view.
 */
@Composable
fun PiholeTile(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PiholeViewModel =
        hiltViewModel(androidx.compose.ui.platform.LocalContext.current.findComponentActivity()),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val extra = GateControlTheme.extraColors

    // Foreground polling ~30s while the tile is in composition (mirrors PiholeScreen).
    LaunchedEffect(Unit) {
        viewModel.refresh()
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
    val isPaused = end != null || ui.pausePermanent
    val state = ui.summary?.blocking?.state ?: "unknown"

    GcTile(
        icon = GcIcons.ShieldX,
        iconTint = if (isPaused || state == "disabled") extra.warn else extra.accentText,
        title = stringResource(R.string.pihole_title),
        subtitle = when {
            end != null -> stringResource(R.string.pihole_paused_mmss, formatMmSs(remaining))
            ui.pausePermanent -> stringResource(R.string.pihole_paused)
            state == "enabled" -> stringResource(R.string.pihole_tile_blocking)
            else -> piholeStatusLabel(state)
        },
        active = !isPaused && state == "enabled",
        onClick = onOpen,
        modifier = modifier,
    )
}
