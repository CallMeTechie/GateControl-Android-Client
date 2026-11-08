package com.gatecontrol.android.ui.update

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.network.UpdateCheckResponse
import com.gatecontrol.android.update.ApkUpdateState
import com.gatecontrol.android.update.ApkUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** "Installieren" of the GitHub build in Settings › Updates: the own download ([ApkUpdater]), then Android's installer. */
@HiltViewModel
class ApkUpdateViewModel @Inject constructor(private val updater: ApkUpdater) : ViewModel() {
    val state: StateFlow<ApkUpdateState> = updater.state

    /** Loads and checks [offer]; once it is ready, the installer opens at once ([context]). */
    fun start(context: Context, offer: UpdateCheckResponse, currentVersion: String) {
        if (!updater.enabled) return
        if (state.value is ApkUpdateState.Downloading || state.value == ApkUpdateState.Verifying) return
        // the application context: the download may outlast this screen (rotation), the installer opens in a new task
        val app = context.applicationContext
        viewModelScope.launch {
            if (updater.download(offer, currentVersion) is ApkUpdateState.Ready) updater.install(app)
        }
    }

    /** "Jetzt installieren" again (after allowing "Unbekannte Apps installieren", or the installer was closed). */
    fun install(context: Context) = updater.install(context)

    /** Back in the app (e.g. from "Unbekannte Apps installieren"): the installer follows when it is allowed now. */
    fun onResume(context: Context) = updater.continueAfterPermission(context)

    fun reset() = updater.reset()
}
