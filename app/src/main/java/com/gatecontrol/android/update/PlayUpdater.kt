package com.gatecontrol.android.update

import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.gatecontrol.android.BuildConfig
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Update state of the Google Play build. */
sealed interface PlayUpdateState {
    data object Idle : PlayUpdateState
    data object Checking : PlayUpdateState
    data object UpToDate : PlayUpdateState
    /** A newer version is on Play for this account (also internal testing). */
    data class Available(val versionCode: Int) : PlayUpdateState
    /** [percent] 0–100, or null while Play has not reported sizes yet. */
    data class Downloading(val percent: Int?) : PlayUpdateState
    /** Downloaded: a restart installs it. */
    data object Downloaded : PlayUpdateState
    /** The app was not installed from Google Play (sideloaded, debug). */
    data object NotFromPlay : PlayUpdateState
    data object Failed : PlayUpdateState
}

/**
 * Google Play in-app updates (flexible flow) for the Play build
 * ([BuildConfig.DISTRIBUTION] == "play"): looks for a newer version on the
 * track this account gets (internal testing included), downloads it in the
 * background and installs it on restart. The GitHub build keeps its own
 * update check.
 */
@Singleton
class PlayUpdater internal constructor(private val manager: AppUpdateManager) {

    @Inject constructor(@ApplicationContext context: Context) : this(AppUpdateManagerFactory.create(context))

    private val _state = MutableStateFlow<PlayUpdateState>(PlayUpdateState.Idle)
    val state: StateFlow<PlayUpdateState> = _state.asStateFlow()

    private var info: AppUpdateInfo? = null

    private val listener = InstallStateUpdatedListener { s ->
        _state.value = when (s.installStatus()) {
            InstallStatus.PENDING -> PlayUpdateState.Downloading(null)
            InstallStatus.DOWNLOADING -> PlayUpdateState.Downloading(
                s.totalBytesToDownload().takeIf { it > 0 }?.let { (s.bytesDownloaded() * 100 / it).toInt().coerceIn(0, 100) },
            )
            InstallStatus.DOWNLOADED -> PlayUpdateState.Downloaded
            InstallStatus.FAILED, InstallStatus.CANCELED -> PlayUpdateState.Failed
            else -> _state.value
        }
    }

    init {
        manager.registerListener(listener)
    }

    suspend fun check(): PlayUpdateState {
        if (_state.value is PlayUpdateState.Downloading) return _state.value
        _state.value = PlayUpdateState.Checking
        val next = try {
            val i = manager.appUpdateInfo.await()
            info = i
            when {
                i.installStatus() == InstallStatus.DOWNLOADED -> PlayUpdateState.Downloaded
                i.installStatus() == InstallStatus.DOWNLOADING || i.installStatus() == InstallStatus.PENDING ->
                    PlayUpdateState.Downloading(null)
                i.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    i.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> PlayUpdateState.Available(i.availableVersionCode())
                else -> PlayUpdateState.UpToDate
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ERROR_APP_NOT_OWNED and friends: not installed through Play.
            Timber.d("Play update check failed (%s)", e.javaClass.simpleName)
            if (e is com.google.android.play.core.install.InstallException) PlayUpdateState.NotFromPlay else PlayUpdateState.Failed
        }
        _state.value = next
        return next
    }

    /** Starts the download for an [PlayUpdateState.Available] update (Play shows its own dialog). */
    fun start(launcher: ActivityResultLauncher<IntentSenderRequest>): Boolean {
        val i = info ?: return false
        return try {
            manager.startUpdateFlowForResult(i, launcher, AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build())
        } catch (e: Exception) {
            Timber.w("Play update could not start (%s)", e.javaClass.simpleName)
            _state.value = PlayUpdateState.Failed
            false
        }
    }

    /** Installs a downloaded update; Play restarts the app. */
    fun complete() {
        manager.completeUpdate()
    }

    /** The user closed Play's dialog without updating. */
    fun declined() {
        if (_state.value !is PlayUpdateState.Downloading) _state.value = info?.let {
            PlayUpdateState.Available(it.availableVersionCode())
        } ?: PlayUpdateState.Idle
    }

    companion object {
        val enabled: Boolean get() = BuildConfig.DISTRIBUTION == "play"
    }
}

/** Awaits a Play services [Task] (no extra coroutines-play-services dependency). */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    // Direct executor: resume on whatever thread completes the task (the
    // default would post to the main looper, which a test JVM doesn't run).
    val direct = java.util.concurrent.Executor { it.run() }
    addOnSuccessListener(direct) { cont.resume(it) }
    addOnFailureListener(direct) { cont.resumeWithException(it) }
}
