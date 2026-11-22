package com.gatecontrol.android.tv.update

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.ClientPlatform
import com.gatecontrol.android.network.UpdateCheckResponse
import com.gatecontrol.android.network.UpdateChecker
import com.gatecontrol.android.tv.BuildConfig
import com.gatecontrol.android.update.ApkUpdateState
import com.gatecontrol.android.update.ApkUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** Result of the update check (Einstellungen › Updates). */
sealed interface TvUpdateCheck {
    data object Idle : TvUpdateCheck
    data object Checking : TvUpdateCheck
    data object UpToDate : TvUpdateCheck
    data class Available(val offer: UpdateCheckResponse) : TvUpdateCheck
    data object Failed : TvUpdateCheck
}

/**
 * Updates of GateControl TV: the check asks for the TV's APK (`client=android-tv&platform=android-tv`,
 * [ClientPlatform.updateQuery]); the download and the checks are core/network's [ApkUpdater] — at home from the
 * home gateway's cache (`lan` hint), else from the server or GitHub; SHA-256, package and signature checked — and
 * Android's installer does the rest ("Unbekannte Apps installieren" for GateControl TV, once).
 */
@HiltViewModel
class TvUpdateViewModel @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
    private val platform: ClientPlatform,
    private val updater: ApkUpdater,
) : ViewModel() {

    val version: String = BuildConfig.VERSION_NAME

    private val _check = MutableStateFlow<TvUpdateCheck>(TvUpdateCheck.Idle)
    val check: StateFlow<TvUpdateCheck> = _check.asStateFlow()

    val download: StateFlow<ApkUpdateState> = updater.state

    fun checkNow() {
        if (_check.value == TvUpdateCheck.Checking) return
        val server = setupRepository.getServerUrl()
        if (server.isBlank()) return
        _check.value = TvUpdateCheck.Checking
        viewModelScope.launch {
            _check.value = try {
                val q = platform.updateQuery
                val r = apiClientProvider.getClient(server).checkUpdate(version = version, platform = q.platform, client = q.client)
                val newer = r.ok && r.available && r.version != null && UpdateChecker.isNewer(r.version!!, version)
                if (newer) TvUpdateCheck.Available(r) else TvUpdateCheck.UpToDate
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("TV update: check failed (%s)", e.javaClass.simpleName)
                TvUpdateCheck.Failed
            }
        }
    }

    /** Loads and checks the offered APK, then opens Android's installer (from [from], the activity). */
    fun install(from: Context) {
        val offer = (_check.value as? TvUpdateCheck.Available)?.offer ?: return
        val busy = download.value
        if (busy is ApkUpdateState.Downloading || busy == ApkUpdateState.Verifying) return
        if (busy is ApkUpdateState.Ready) {
            updater.install(from)
            return
        }
        viewModelScope.launch {
            if (updater.download(offer, version) is ApkUpdateState.Ready) updater.install(from)
        }
    }

    /** Back from the system page for "Unbekannte Apps installieren". */
    fun onResume(from: Context) = updater.continueAfterPermission(from)
}
