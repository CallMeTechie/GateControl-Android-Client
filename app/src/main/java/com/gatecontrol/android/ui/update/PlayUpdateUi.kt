package com.gatecontrol.android.ui.update

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.update.PlayUpdateState
import com.gatecontrol.android.update.PlayUpdater
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcTone
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayUpdateViewModel @Inject constructor(private val updater: PlayUpdater) : ViewModel() {
    val state: StateFlow<PlayUpdateState> = updater.state

    fun check() {
        viewModelScope.launch { updater.check() }
    }

    fun start(launcher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest>) {
        updater.start(launcher)
    }

    fun complete() = updater.complete()

    fun declined() = updater.declined()
}

/** Launcher for Play's update dialog; a dismissed dialog keeps the update offered. */
@Composable
private fun rememberUpdateLauncher(viewModel: PlayUpdateViewModel) =
    rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) viewModel.declined()
    }

/** Settings › Updates of the Google Play build. */
@Composable
fun PlayUpdateRow(versionName: String, viewModel: PlayUpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val launcher = rememberUpdateLauncher(viewModel)
    val version = stringResource(R.string.settings_version, versionName)
    GcListRow(
        title = stringResource(R.string.settings_updates),
        description = version + " · " + statusText(state),
        onClick = if (state is PlayUpdateState.Checking || state is PlayUpdateState.Downloading) null else ({ viewModel.check() }),
    )
    UpdateActions(state, onStart = { viewModel.start(launcher) }, onRestart = viewModel::complete)
}

/** Start screen: shown only while an update is available, loading or ready. Checks once per visit. */
@Composable
fun PlayUpdateBanner(modifier: Modifier = Modifier, viewModel: PlayUpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val launcher = rememberUpdateLauncher(viewModel)
    LaunchedEffect(Unit) { if (PlayUpdater.enabled) viewModel.check() }
    if (state !is PlayUpdateState.Available && state !is PlayUpdateState.Downloading && state !is PlayUpdateState.Downloaded) return
    GcBanner(tone = GcTone.Info, modifier = modifier) {
        Text(
            statusText(state),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        when (state) {
            is PlayUpdateState.Available -> GcPrimaryButton(
                text = stringResource(R.string.play_update_install),
                onClick = { viewModel.start(launcher) },
                fillWidth = false,
                minHeight = 40.dp,
            )
            PlayUpdateState.Downloaded -> GcPrimaryButton(
                text = stringResource(R.string.play_update_restart),
                onClick = viewModel::complete,
                fillWidth = false,
                minHeight = 40.dp,
            )
            else -> Unit
        }
    }
}

@Composable
private fun UpdateActions(state: PlayUpdateState, onStart: () -> Unit, onRestart: () -> Unit) {
    val action = when (state) {
        is PlayUpdateState.Available -> R.string.play_update_install to onStart
        PlayUpdateState.Downloaded -> R.string.play_update_restart to onRestart
        else -> return
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GcOutlineButton(
            text = stringResource(action.first),
            onClick = action.second,
            modifier = Modifier.weight(1f),
            minHeight = 40.dp,
        )
    }
}

@Composable
private fun statusText(state: PlayUpdateState): String = when (state) {
    PlayUpdateState.Idle -> stringResource(R.string.play_update_check)
    PlayUpdateState.Checking -> stringResource(R.string.play_update_checking)
    PlayUpdateState.UpToDate -> stringResource(R.string.play_update_uptodate)
    is PlayUpdateState.Available -> stringResource(R.string.play_update_available)
    is PlayUpdateState.Downloading -> stringResource(R.string.play_update_downloading, state.percent?.let { "$it %" } ?: "")
    PlayUpdateState.Downloaded -> stringResource(R.string.play_update_downloaded)
    PlayUpdateState.NotFromPlay -> stringResource(R.string.play_update_not_from_play)
    PlayUpdateState.Failed -> stringResource(R.string.play_update_failed)
}
