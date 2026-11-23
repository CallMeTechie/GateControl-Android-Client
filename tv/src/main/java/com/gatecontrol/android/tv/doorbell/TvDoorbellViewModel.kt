package com.gatecontrol.android.tv.doorbell

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.door.DoorApi
import com.gatecontrol.android.door.DoorLock
import com.gatecontrol.android.door.DoorLogic
import com.gatecontrol.android.door.DoorPushInfo
import com.gatecontrol.android.door.DoorRepository
import com.gatecontrol.android.door.DoorResult
import com.gatecontrol.android.door.HomeCameraJson
import com.gatecontrol.android.door.HomeCameraList
import com.gatecontrol.android.tv.TvSettings
import com.gatecontrol.android.tv.cameras.TvCameraMedia
import com.gatecontrol.android.tv.cameras.TvLiveCamera
import com.gatecontrol.android.tv.cameras.TvLiveUi
import com.gatecontrol.android.tv.security.TvPinFunction
import com.gatecontrol.android.tv.security.TvPinStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The overlay's state. [step]: what "Tür öffnen" waits for. */
data class TvDoorbellUi(
    val title: String = "",
    val body: String = "",
    val picture: Bitmap? = null,
    val lock: DoorLock? = null,
    val step: OpenStep = OpenStep.Idle,
    /** Text of the lock action's answer (or error code), null while none. */
    val result: String? = null,
    val resultOk: Boolean = false,
)

enum class OpenStep { Idle, Pin, Confirm, Running, Done }

@HiltViewModel
class TvDoorbellViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val api: DoorApi,
    private val door: DoorRepository,
    private val media: TvCameraMedia,
    private val pins: TvPinStore,
    private val settings: TvSettings,
    val live: TvLiveCamera,
) : ViewModel() {

    private val info = DoorPushInfo(
        type = savedState.get<String>(TvDoorbellActivity.EXTRA_TYPE) ?: DoorPushInfo.DING,
        eventId = savedState.get<Long>(TvDoorbellActivity.EXTRA_EVENT)?.takeIf { it > 0 },
        cameraId = savedState.get<Long>(TvDoorbellActivity.EXTRA_CAMERA)?.takeIf { it > 0 },
        lockId = null,
        homeCameraId = savedState.get<String>(TvDoorbellActivity.EXTRA_HOME_CAMERA)?.takeIf { HomeCameraJson.CAMERA_ID_RE.matches(it) },
    )

    private val _ui = MutableStateFlow(
        TvDoorbellUi(
            title = savedState.get<String>(TvDoorbellActivity.EXTRA_TITLE).orEmpty(),
            body = savedState.get<String>(TvDoorbellActivity.EXTRA_BODY).orEmpty(),
        ),
    )
    val ui: StateFlow<TvDoorbellUi> = _ui.asStateFlow()

    val liveUi: StateFlow<TvLiveUi> get() = live.ui

    private var action: String? = null

    init {
        viewModelScope.launch { loadPicture() }
        viewModelScope.launch {
            door.loadCached()
            door.refresh()
            val lock = DoorbellRules.openLock(info, door.overview.value)
            action = lock?.let(DoorbellRules::openAction)
            _ui.value = _ui.value.copy(lock = lock)
        }
    }

    /**
     * Live: the home camera assigned to the doorbell (push, else `GET me`), else the one chosen in the settings; else the
     * ring's own picture (the event's, else a snapshot of the doorbell).
     */
    private suspend fun loadPicture() {
        if (info.homeCameraId == null) door.loadCached()
        DoorbellRules.assignedCamera(info, door.overview.value)?.let { id ->
            live.start(viewModelScope, id)
            return
        }
        val list = (api.homeCameras() as? DoorResult.Ok<HomeCameraList>)?.value?.takeIf { it.supported }
        val camera = DoorbellRules.liveCamera(info, door.overview.value, settings.doorbell.value, list?.cameras)
        if (camera != null) {
            live.start(viewModelScope, camera)
            return
        }
        val image = info.eventId?.let { (api.event(it) as? DoorResult.Ok)?.value?.image }
            ?: info.cameraId?.let { (api.snapshot(it, refresh = false) as? DoorResult.Ok)?.value?.image }
            ?: return
        media.decodeImage(image)?.let { _ui.value = _ui.value.copy(picture = it) }
    }

    /** "Tür öffnen": the confirmation if the plugin asks for one, the TV PIN if set so, then the action. */
    fun open() {
        val lock = _ui.value.lock ?: return
        val a = action ?: return
        if (_ui.value.step == OpenStep.Running) return
        val steps = DoorLogic.steps(lock, a)
        _ui.value = _ui.value.copy(
            step = when {
                DoorLogic.Step.CONFIRM in steps -> OpenStep.Confirm
                pins.needsPin(TvPinFunction.DOOR) -> OpenStep.Pin
                else -> OpenStep.Running
            },
        )
        if (_ui.value.step == OpenStep.Running) run(lock, a)
    }

    fun confirmed() {
        val lock = _ui.value.lock ?: return
        val a = action ?: return
        if (pins.needsPin(TvPinFunction.DOOR)) {
            _ui.value = _ui.value.copy(step = OpenStep.Pin)
        } else {
            _ui.value = _ui.value.copy(step = OpenStep.Running)
            run(lock, a)
        }
    }

    suspend fun checkPin(pin: String) = pins.check(pin)

    fun pinPassed() {
        val lock = _ui.value.lock ?: return
        val a = action ?: return
        _ui.value = _ui.value.copy(step = OpenStep.Running)
        run(lock, a)
    }

    fun cancelStep() {
        if (_ui.value.step != OpenStep.Running) _ui.value = _ui.value.copy(step = OpenStep.Idle)
    }

    private fun run(lock: DoorLock, a: String) {
        viewModelScope.launch {
            when (val r = api.lockAction(lock.id, a)) {
                is DoorResult.Ok -> {
                    r.value.lock?.let(door::updateLock)
                    _ui.value = _ui.value.copy(step = OpenStep.Done, result = r.value.message.orEmpty(), resultOk = true)
                }
                is DoorResult.Failed -> _ui.value = _ui.value.copy(step = OpenStep.Idle, result = r.text ?: r.code, resultOk = false)
                else -> _ui.value = _ui.value.copy(step = OpenStep.Idle, result = DoorApi.CODE_OFFLINE, resultOk = false)
            }
        }
    }

    override fun onCleared() {
        live.stop()
        super.onCleared()
    }
}
