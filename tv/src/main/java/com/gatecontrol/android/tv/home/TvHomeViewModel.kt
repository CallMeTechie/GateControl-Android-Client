package com.gatecontrol.android.tv.home

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.door.DoorApi
import com.gatecontrol.android.door.DoorResult
import com.gatecontrol.android.door.HomeCamera
import com.gatecontrol.android.layout.TabLayoutRepository
import com.gatecontrol.android.quickactions.QuickActionItem
import com.gatecontrol.android.quickactions.QuickActionRepository
import com.gatecontrol.android.quickactions.QuickActionResult
import com.gatecontrol.android.quickactions.QuickActionsLoad
import com.gatecontrol.android.tv.cameras.TvCameraMedia
import com.gatecontrol.android.tv.cameras.TvLiveCamera
import com.gatecontrol.android.tv.cameras.TvLiveUi
import com.gatecontrol.android.tv.security.TvPinFunction
import com.gatecontrol.android.tv.security.TvPinStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A run waiting for the PIN and / or a "Wirklich?" first. */
data class TvPendingRun(val tile: TvTile, val item: QuickActionItem, val needsPin: Boolean, val needsConfirm: Boolean)

/** What a run answered, shown for a moment under the row. */
data class TvRunNotice(val title: String, val ok: Boolean, val message: String?, val reason: String?)

/**
 * The start page: the row "Schnellaktionen" from the server's start layout and actions ([TvTiles]), the row
 * "Kameras" with a still per home camera (refreshed while the page is in front, every [CameraStillEveryMs]).
 */
@HiltViewModel
class TvHomeViewModel @Inject constructor(
    private val quickActions: QuickActionRepository,
    private val tabLayout: TabLayoutRepository,
    private val doorApi: DoorApi,
    private val media: TvCameraMedia,
    private val pins: TvPinStore,
    /** The page "Kameras": one camera live. */
    val live: TvLiveCamera,
) : ViewModel() {

    val tiles: StateFlow<List<TvTile>> = combine(quickActions.actions, tabLayout.layout) { actions, layout ->
        TvTiles.build(layout.start, actions)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val load: StateFlow<QuickActionsLoad> = quickActions.load

    private val _cameras = MutableStateFlow<List<HomeCamera>>(emptyList())
    val cameras: StateFlow<List<HomeCamera>> = _cameras.asStateFlow()

    private val _previews = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val previews: StateFlow<Map<String, Bitmap>> = _previews.asStateFlow()

    private val _pending = MutableStateFlow<TvPendingRun?>(null)
    val pending: StateFlow<TvPendingRun?> = _pending.asStateFlow()

    private val _notice = MutableStateFlow<TvRunNotice?>(null)
    val notice: StateFlow<TvRunNotice?> = _notice.asStateFlow()

    private val _busy = MutableStateFlow<Set<String>>(emptySet())

    /** Tiles whose run is on its way (a small spinner, OK ignored). */
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    private var stills: Job? = null

    fun refresh() {
        viewModelScope.launch { tabLayout.refresh() }
        viewModelScope.launch { quickActions.refresh() }
        viewModelScope.launch { loadCameras() }
    }

    /** The start page is in front: previews every few seconds; gone: stop. */
    fun setVisible(visible: Boolean) {
        stills?.cancel()
        if (!visible) return
        stills = viewModelScope.launch {
            while (true) {
                _cameras.value.forEach { cam -> loadPreview(cam.id) }
                delay(CameraStillEveryMs)
            }
        }
    }

    private suspend fun loadCameras() {
        when (val r = doorApi.homeCameras()) {
            is DoorResult.Ok -> _cameras.value = if (r.value.supported) r.value.cameras else emptyList()
            is DoorResult.Failed -> if (r.http == 403 || r.http == 404) _cameras.value = emptyList()
            else -> Unit
        }
        _cameras.value.forEach { cam -> media.peekPreview(cam.id)?.let { bmp -> _previews.value = _previews.value + (cam.id to bmp) } }
    }

    private suspend fun loadPreview(cameraId: String) {
        val image = (doorApi.homeStill(cameraId) as? DoorResult.Ok)?.value ?: return
        val bmp = media.preview(cameraId, image) ?: return
        _previews.value = _previews.value + (cameraId to bmp)
    }

    val liveUi: StateFlow<TvLiveUi> get() = live.ui

    /** The page "Kameras" shows [cameraId] live (null: the first online one). */
    fun showLive(cameraId: String?) {
        val id = com.gatecontrol.android.door.HomeCameraLogic.pick(_cameras.value, cameraId, live.ui.value.cameraId) ?: return
        live.start(viewModelScope, id)
    }

    fun stopLive() = live.stop()

    override fun onCleared() {
        live.stop()
        super.onCleared()
    }

    // ─── Running a tile ─────────────────────────────────────────────

    /** OK on [tile]: its action, or first the PIN (door, if set so) and / or the confirmation. */
    fun press(tile: TvTile) {
        val item = when (tile) {
            is TvTile.Action -> tile.item
            is TvTile.Device -> tile.power ?: tile.carrier ?: return
        }
        if (item.key in _busy.value) return
        val door = tile is TvTile.Action && tile.door
        val needsPin = door && pins.needsPin(TvPinFunction.DOOR)
        if (needsPin || item.needsConfirm) {
            _pending.value = TvPendingRun(tile, item, needsPin, item.needsConfirm)
            return
        }
        run(item)
    }

    /** The PIN was right (or the "Wirklich?" answered): the next step, finally the run. */
    fun pinPassed() {
        val p = _pending.value ?: return
        if (p.needsConfirm) _pending.value = p.copy(needsPin = false) else {
            _pending.value = null
            run(p.item)
        }
    }

    fun confirmPending() {
        val p = _pending.value ?: return
        if (p.needsPin) return
        _pending.value = null
        run(p.item)
    }

    fun cancelPending() {
        _pending.value = null
    }

    private fun run(item: QuickActionItem) {
        _busy.value = _busy.value + item.key
        viewModelScope.launch {
            val result = try {
                quickActions.run(item)
            } finally {
                _busy.value = _busy.value - item.key
            }
            notice(item, result)
        }
    }

    /** A target temperature (or slider) set with ◀ ▶, sent once the remote leaves it alone ([TvSetpoint]). */
    fun setControl(tile: TvTile.Device, controlId: String, value: Any) {
        val carrier = tile.carrier ?: return
        viewModelScope.launch {
            val result = quickActions.control(carrier, controlId, value)
            if (!result.ok) notice(carrier, result)
        }
    }

    private fun notice(item: QuickActionItem, result: QuickActionResult) {
        _notice.value = TvRunNotice(item.title, result.ok, result.message, result.reason)
        viewModelScope.launch {
            val shown = _notice.value
            delay(NoticeMs)
            if (_notice.value === shown) _notice.value = null
        }
    }

    companion object {
        const val CameraStillEveryMs = 15_000L
        private const val NoticeMs = 4_000L
    }
}
