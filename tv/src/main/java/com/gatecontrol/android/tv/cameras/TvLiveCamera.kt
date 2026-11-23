package com.gatecontrol.android.tv.cameras

import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.door.CameraMediaUrls
import com.gatecontrol.android.door.DoorApi
import com.gatecontrol.android.door.DoorResult
import com.gatecontrol.android.door.HomeCameraLogic
import com.gatecontrol.android.door.HomeLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/** What the live picture shows: the HLS list (in the VPN / the server's LAN) or a still every few seconds. */
data class TvLiveUi(
    val cameraId: String? = null,
    /** Absolute address of the newest HLS list, null: stills only. */
    val hlsUrl: String? = null,
    val still: Bitmap? = null,
    val every: Int = 10,
    val via: String? = null,
    val failed: Boolean = false,
)

/**
 * One camera live, like the phone app's door screen: `GET cameras/home/<id>/live`, the tokens renewed shortly before
 * they run out ([HomeCameraLogic.renewInMs]), the player loads only live files of the own server under the newest
 * token ([CameraMediaUrls.retoken]). Without VPN the server answers with stills: a new one every `every` seconds.
 * Runs in the scope of its screen ([start] / [stop]).
 */
class TvLiveCamera @Inject constructor(
    private val api: DoorApi,
    private val media: TvCameraMedia,
    private val setupRepository: SetupRepository,
) {
    private val _ui = MutableStateFlow(TvLiveUi())
    val ui: StateFlow<TvLiveUi> = _ui.asStateFlow()

    private var job: Job? = null
    private val counter = AtomicLong()

    fun start(scope: CoroutineScope, cameraId: String) {
        if (_ui.value.cameraId == cameraId && job?.isActive == true) return
        job?.cancel()
        _ui.value = TvLiveUi(cameraId = cameraId, still = media.peekPreview(cameraId))
        job = scope.launch {
            var failures = 0
            while (isActive) {
                when (val r = api.homeLive(cameraId)) {
                    is DoorResult.Ok -> {
                        failures = 0
                        val live = r.value
                        if (live.isLive) {
                            val url = CameraMediaUrls.resolve(setupRepository.getServerUrl(), live.hlsPath)
                            _ui.value = _ui.value.copy(hlsUrl = url, via = live.via, failed = url == null)
                            delay(HomeCameraLogic.renewInMs(live.expiresAt, System.currentTimeMillis()))
                        } else {
                            stills(cameraId, live)
                        }
                    }
                    else -> {
                        failures++
                        _ui.value = _ui.value.copy(failed = true)
                        delay(HomeCameraLogic.retryInMs(failures))
                    }
                }
            }
        }
    }

    /** Snapshot mode until the token's renewal is due: a still every `every` seconds. */
    private suspend fun stills(cameraId: String, live: HomeLive) {
        val every = live.every.coerceIn(2, 60)
        _ui.value = _ui.value.copy(hlsUrl = null, every = every, via = live.via, failed = false)
        val until = System.currentTimeMillis() + HomeCameraLogic.renewInMs(live.expiresAt, System.currentTimeMillis())
        while (System.currentTimeMillis() < until) {
            media.still(cameraId, live.snapshotPath, counter.incrementAndGet(), TvCameraMedia.OVERLAY_SIDE_PX)?.let {
                _ui.value = _ui.value.copy(still = it)
            }
            delay(every * 1_000L)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _ui.value = TvLiveUi()
    }

    /** Data sources of the player: live files of the own server only, always under the newest token. */
    @OptIn(UnstableApi::class)
    fun sourceFactory(): DataSource.Factory? = media.dataSourceFactory { requested ->
        _ui.value.hlsUrl?.let { CameraMediaUrls.retoken(requested, it) }
    }
}
