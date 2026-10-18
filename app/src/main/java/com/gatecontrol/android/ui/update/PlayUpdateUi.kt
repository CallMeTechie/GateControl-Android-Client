package com.gatecontrol.android.ui.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.update.PlayUpdateState
import com.gatecontrol.android.update.PlayUpdateTarget
import com.gatecontrol.android.update.PostUpdateMarker
import com.gatecontrol.android.update.ReleaseNotesRepository
import com.gatecontrol.android.update.ReleaseNotesState
import com.gatecontrol.android.update.UpdateCoordinator
import com.gatecontrol.android.update.UpdateFormat
import com.gatecontrol.android.update.UpdateNetwork
import com.gatecontrol.android.update.UpdateSheetPage
import com.gatecontrol.android.update.UpdateToast
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class PlayUpdateViewModel @Inject constructor(private val coordinator: UpdateCoordinator) : ViewModel() {
    val state: StateFlow<PlayUpdateState> = coordinator.state
    val target: StateFlow<PlayUpdateTarget?> = coordinator.target
    val sheet: StateFlow<UpdateSheetPage?> = coordinator.sheet
    val network: StateFlow<UpdateNetwork> = coordinator.network
    val eta: StateFlow<Int?> = coordinator.eta
    val vpnConnected: StateFlow<Boolean> = coordinator.vpnConnected
    val autoStart: StateFlow<Boolean> = coordinator.autoStart
    val postUpdate: StateFlow<PostUpdateMarker?> = coordinator.postUpdate
    val toast: StateFlow<UpdateToast?> = coordinator.toast
    val errorDismissed: StateFlow<Boolean> = coordinator.errorDismissed
    val wifiOnly: StateFlow<Boolean> = coordinator.prefs.wifiOnly
    val waitingCode: StateFlow<Int> = coordinator.prefs.waitingCode
    val snooze: StateFlow<Pair<Int, Long>> = coordinator.prefs.snooze
    val lastChecked: StateFlow<Long> = coordinator.prefs.lastChecked
    val notes = coordinator.notes.state

    fun check() {
        viewModelScope.launch { coordinator.check() }
    }

    /** Notes of the update on offer (card or sheet on screen). */
    fun loadNotes() {
        viewModelScope.launch { coordinator.loadNotes() }
    }

    /** Notes of the update that was just installed ("Das ist neu"). */
    fun loadNotes(from: Int, to: Int) {
        viewModelScope.launch { coordinator.notes.load(from, to) }
    }

    fun openSheet(page: UpdateSheetPage) = coordinator.openSheet(page)
    fun closeSheet() = coordinator.closeSheet()
    fun setWifiOnly(on: Boolean) = coordinator.prefs.setWifiOnly(on)
    fun requestUpdate(launcher: ActivityResultLauncher<IntentSenderRequest>) = coordinator.requestUpdate(launcher)
    fun startNow(launcher: ActivityResultLauncher<IntentSenderRequest>) = coordinator.startNow(launcher)
    fun declined() = coordinator.declined()
    fun remindLater() = coordinator.remindLater()
    fun dismissError() = coordinator.dismissError()
    fun restartNow() = coordinator.restartNow()
    fun restartLater() = coordinator.restartLater()
    fun onStartShown(reconnect: () -> Boolean) = coordinator.onStartShown(reconnect)
    fun dismissPostUpdate() = coordinator.dismissPostUpdate()
    fun toastShown() = coordinator.toastShown()

    /** "In Google Play öffnen" tapped: the card rests for a day. */
    fun snoozeNotFromPlay(key: Int) = coordinator.prefs.snooze(key, System.currentTimeMillis() + UpdateFormat.SNOOZE_MS)

    /** "Erneut versuchen": asks Play again and starts the download when it is offered. */
    fun retry(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        viewModelScope.launch {
            when (coordinator.check()) {
                is PlayUpdateState.Available -> coordinator.startNow(launcher)
                is PlayUpdateState.Downloading, PlayUpdateState.Downloaded -> coordinator.openSheet(UpdateSheetPage.Status)
                else -> Unit
            }
        }
    }
}

/** Launcher for Play's update dialog; a dismissed dialog keeps the update offered. */
@Composable
internal fun rememberUpdateLauncher(viewModel: PlayUpdateViewModel) =
    rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) viewModel.declined()
    }

@Composable
internal fun currentLocale(): Locale =
    LocalConfiguration.current.locales.takeUnless { it.isEmpty }?.get(0) ?: Locale.ROOT

/** "noch etwa 20 Sekunden" / "noch etwa 2 Minuten"; null while not known. */
@Composable
internal fun etaLong(seconds: Int?): String? = when {
    seconds == null -> null
    seconds < 60 -> seconds.coerceAtLeast(1).let { pluralStringResource(R.plurals.upd_eta_seconds, it, it) }
    else -> ((seconds + 59) / 60).let { pluralStringResource(R.plurals.upd_eta_minutes, it, it) }
}

/** "noch etwa 20 s" / "noch etwa 2 min". */
@Composable
internal fun etaShort(seconds: Int?): String? = when {
    seconds == null -> null
    seconds < 60 -> stringResource(R.string.upd_eta_short_s, seconds.coerceAtLeast(1))
    else -> stringResource(R.string.upd_eta_short_min, (seconds + 59) / 60)
}

/** "über WLAN" / "über mobile Daten". */
@Composable
internal fun viaNetwork(net: UpdateNetwork): String? = when {
    !net.online -> null
    net.wifi -> stringResource(R.string.upd_via_wifi)
    net.cellular -> stringResource(R.string.upd_via_mobile)
    else -> stringResource(R.string.upd_via_other)
}

internal fun openReleases(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, ReleaseNotesRepository.RELEASES_URL.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Settings › Updates of the Google Play build ("Nach Updates suchen"). */
@Composable
fun PlayUpdateRow(versionName: String, viewModel: PlayUpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lastChecked by viewModel.lastChecked.collectAsStateWithLifecycle()
    val launcher = rememberUpdateLauncher(viewModel)
    val extra = GateControlTheme.extraColors
    if (state == PlayUpdateState.UpToDate) {
        // "GateControl ist aktuell · Version X · zuletzt geprüft vor …"; a tap checks again.
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(lastChecked) {
            while (true) {
                now = System.currentTimeMillis()
                delay(30_000)
            }
        }
        GcListRow(
            title = stringResource(R.string.upd_uptodate_title),
            description = stringResource(R.string.upd_uptodate_text, versionName, checkedAgo(now, lastChecked)),
            onClick = { viewModel.check() },
            leading = { UpdIconTile(Icons.Rounded.Check, extra.accentBg, extra.accentText) },
        )
        return
    }
    val busy = state is PlayUpdateState.Checking || state is PlayUpdateState.Downloading
    val version = stringResource(R.string.settings_version, versionName)
    GcListRow(
        title = stringResource(R.string.settings_updates),
        description = version + " · " + statusText(state),
        onClick = if (busy) null else ({ viewModel.check() }),
    )
    val action: Pair<Int, () -> Unit> = when (state) {
        is PlayUpdateState.Available, is PlayUpdateState.DownloadFailed ->
            R.string.play_update_install to { viewModel.startNow(launcher) }
        PlayUpdateState.Downloaded -> R.string.play_update_restart to { viewModel.restartNow() }
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
            minHeight = 48.dp,
        )
    }
}

@Composable
private fun checkedAgo(now: Long, then: Long): String {
    val ago = UpdateFormat.ago(now, then) ?: return stringResource(R.string.upd_ago_now)
    val res = when (ago.first) {
        UpdateFormat.AgoUnit.Minutes -> R.plurals.upd_ago_minutes
        UpdateFormat.AgoUnit.Hours -> R.plurals.upd_ago_hours
        UpdateFormat.AgoUnit.Days -> R.plurals.upd_ago_days
    }
    return pluralStringResource(res, ago.second, ago.second)
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
    is PlayUpdateState.DownloadFailed -> state.percent?.let { stringResource(R.string.upd_paused_title, it) }
        ?: stringResource(R.string.upd_paused_title_plain)
}

/** A [ReleaseNotesState] for the step [from] → [to] out of the repository's last result. */
internal fun Pair<Pair<Int, Int>, ReleaseNotesState>?.forStep(from: Int, to: Int): ReleaseNotesState =
    this?.takeIf { it.first == (from to to) }?.second ?: ReleaseNotesState.Loading
