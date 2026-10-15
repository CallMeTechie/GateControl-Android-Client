package com.gatecontrol.android.ui.door

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.door.DoorAction
import com.gatecontrol.android.door.DoorApi
import com.gatecontrol.android.door.DoorCamera
import com.gatecontrol.android.door.DoorEventDetail
import com.gatecontrol.android.door.DoorHistoryItem
import com.gatecontrol.android.door.DoorImage
import com.gatecontrol.android.door.DoorLock
import com.gatecontrol.android.door.DoorLogic
import com.gatecontrol.android.door.DoorOverview
import com.gatecontrol.android.door.DoorRepository
import com.gatecontrol.android.door.DoorResult
import com.gatecontrol.android.door.errorCode
import com.gatecontrol.android.push.NotifyMediaLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** One-shot message of the door screens (snackbar): the server's text, or an error code / own text. */
sealed interface DoorNotice {
    data class Text(val text: String) : DoorNotice
    data class Error(val code: String, val retryAfter: Int? = null, val text: String? = null) : DoorNotice
    data object Done : DoorNotice
    data object AuthUnavailable : DoorNotice
    data object AuthFailed : DoorNotice
}

fun DoorResult<*>.notice(): DoorNotice = when (this) {
    is DoorResult.Failed -> DoorNotice.Error(code, retryAfter, text)
    else -> DoorNotice.Error(errorCode() ?: "failed")
}

/** A camera's latest picture as the tab shows it (memory only, never stored). */
data class SnapshotUi(
    val bitmap: Bitmap? = null,
    val at: String? = null,
    val loading: Boolean = false,
    /** `no_image`, `offline`, … of the last load. */
    val error: String? = null,
    /** "Neues Bild" works again from this time (epoch ms; from `retryAfter`). */
    val retryUntilMs: Long = 0L,
)

/** Decodes a picture off the main thread for the tab (scaled to about [maxSide] px). */
internal suspend fun DoorImage.decode(maxSide: Int = 1280): Bitmap? =
    withContext(Dispatchers.Default) { NotifyMediaLoader.decode(bytes, maxSide) }

/**
 * Start screen of the door tab: locks with their state (polled every 10 s
 * while visible), actions, camera pictures and per-camera push switches.
 */
@HiltViewModel
class DoorHomeViewModel @Inject constructor(
    private val repository: DoorRepository,
    private val api: DoorApi,
) : ViewModel() {

    val overview: StateFlow<DoorOverview?> = repository.overview
    val fromCache: StateFlow<Boolean> = repository.fromCache

    private val _loaded = MutableStateFlow(false)
    /** The first answer (server or stored) is through. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** Error code of the last `GET me`, null after a success. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** "<lockId>:<action>" while that action runs. */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _snapshots = MutableStateFlow<Map<Long, SnapshotUi>>(emptyMap())
    val snapshots: StateFlow<Map<Long, SnapshotUi>> = _snapshots.asStateFlow()

    private val notices = Channel<DoorNotice>(Channel.BUFFERED)
    val notice: Flow<DoorNotice> = notices.receiveAsFlow()

    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            repository.loadCached()
            if (repository.overview.value != null) _loaded.value = true
        }
        viewModelScope.launch {
            // A door push (ring, automatic unlock, …): the states changed.
            repository.pushes.collect {
                refresh()
                if (it.isRing) it.cameraId?.let { cam -> loadSnapshot(cam, refresh = false) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { load(showSpinner = true) }
    }

    private suspend fun load(showSpinner: Boolean) {
        if (showSpinner) _refreshing.value = true
        val r = repository.refresh()
        _error.value = if (r is DoorResult.Ok) null else r.errorCode()
        _loaded.value = true
        if (showSpinner) _refreshing.value = false
        // Pictures of new cameras, and a newer stored picture (a ring) of known ones.
        repository.overview.value?.cameras?.forEach { cam ->
            val shown = _snapshots.value[cam.id]
            val newer = cam.lastImageAt != null && shown != null && !shown.loading && shown.error == null &&
                shown.at != null && cam.lastImageAt > shown.at
            if ((shown == null && cam.lastImageAt != null) || newer) loadSnapshot(cam.id, refresh = false)
        }
    }

    /** `GET me` every 10 s while the tab is in front ([stopPolling] when it leaves). */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            load(showSpinner = repository.overview.value == null)
            while (isActive) {
                delay(DoorLogic.POLL_MS)
                load(showSpinner = false)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Runs [action] on [lock] — the screen did the confirmation and device authentication. */
    fun runAction(lock: DoorLock, action: String) {
        if (_busy.value != null || DoorLogic.steps(lock, action).isEmpty()) return
        viewModelScope.launch {
            _busy.value = "${lock.id}:$action"
            val r = api.lockAction(lock.id, action)
            _busy.value = null
            when (r) {
                is DoorResult.Ok -> {
                    r.value.lock?.let(repository::updateLock)
                    notices.send(r.value.message?.let { DoorNotice.Text(it) } ?: DoorNotice.Done)
                }
                else -> notices.send(r.notice())
            }
            load(showSpinner = false)
        }
    }

    /** Latest picture of a camera; [refresh] asks the camera for a new one ("Neues Bild"). */
    fun loadSnapshot(cameraId: Long, refresh: Boolean) {
        val current = _snapshots.value[cameraId]
        if (current?.loading == true) return
        if (refresh && current != null && current.retryUntilMs > System.currentTimeMillis()) return
        _snapshots.update { it + (cameraId to (current ?: SnapshotUi()).copy(loading = true, error = null)) }
        viewModelScope.launch {
            val r = api.snapshot(cameraId, refresh)
            val before = _snapshots.value[cameraId] ?: SnapshotUi()
            val next = when (r) {
                is DoorResult.Ok -> {
                    val bmp = r.value.image.decode()
                    before.copy(bitmap = bmp ?: before.bitmap, at = r.value.image.at, loading = false, error = if (bmp == null) "invalid_answer" else null)
                }
                is DoorResult.Failed -> {
                    val until = r.retryAfter?.let { System.currentTimeMillis() + it * 1000L } ?: before.retryUntilMs
                    if (refresh) notices.send(r.notice())
                    before.copy(loading = false, error = r.code, retryUntilMs = until)
                }
                else -> before.copy(loading = false, error = r.errorCode())
            }
            _snapshots.update { it + (cameraId to next) }
        }
    }

    fun setPref(camera: DoorCamera, ding: Boolean? = null, motion: Boolean? = null) {
        viewModelScope.launch {
            // Shown at once; back to the server's answer on failure.
            repository.updatePrefs(camera.id, camera.prefs.copy(ding = ding ?: camera.prefs.ding, motion = motion ?: camera.prefs.motion))
            when (val r = api.setPrefs(camera.id, ding, motion)) {
                is DoorResult.Ok -> repository.updatePrefs(camera.id, r.value)
                else -> {
                    repository.updatePrefs(camera.id, camera.prefs)
                    notices.send(r.notice())
                }
            }
        }
    }

    fun notify(notice: DoorNotice) {
        notices.trySend(notice)
    }

    override fun onCleared() {
        // Pictures stay in memory only as long as the screen.
        _snapshots.value = emptyMap()
    }
}

/** "Verlauf": pages of `GET history` with the filter alle / Aktionen / Klingeln. */
@HiltViewModel
class DoorHistoryViewModel @Inject constructor(
    private val api: DoorApi,
) : ViewModel() {

    data class Ui(
        val filter: DoorLogic.HistoryFilter = DoorLogic.HistoryFilter.ALL,
        val items: List<DoorHistoryItem> = emptyList(),
        val nextCursor: Long? = null,
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()
    private var job: Job? = null

    init {
        reload()
    }

    fun setFilter(filter: DoorLogic.HistoryFilter) {
        if (filter == _ui.value.filter) return
        _ui.value = Ui(filter = filter)
        reload()
    }

    fun reload() {
        job?.cancel()
        val filter = _ui.value.filter
        _ui.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            when (val r = api.history(cursor = null, kind = filter.kind)) {
                is DoorResult.Ok -> _ui.update {
                    it.copy(items = r.value.items, nextCursor = r.value.nextCursor, loading = false, loaded = true, error = null)
                }
                else -> _ui.update { it.copy(loading = false, loaded = true, error = r.errorCode()) }
            }
        }
    }

    fun loadMore() {
        val state = _ui.value
        val cursor = state.nextCursor ?: return
        if (state.loading) return
        _ui.update { it.copy(loading = true) }
        job = viewModelScope.launch {
            when (val r = api.history(cursor = cursor, kind = state.filter.kind)) {
                is DoorResult.Ok -> _ui.update {
                    it.copy(items = (it.items + r.value.items).distinctBy { i -> i.id }, nextCursor = r.value.nextCursor, loading = false, error = null)
                }
                else -> _ui.update { it.copy(loading = false, error = r.errorCode()) }
            }
        }
    }
}

/** One ring / motion event: its picture and, with the `open` right, "Tür öffnen". */
@HiltViewModel
class DoorEventViewModel @Inject constructor(
    private val api: DoorApi,
    private val repository: DoorRepository,
) : ViewModel() {

    data class Ui(
        val loading: Boolean = true,
        val detail: DoorEventDetail? = null,
        val lock: DoorLock? = null,
        val picture: Bitmap? = null,
        val error: String? = null,
        val busy: Boolean = false,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()
    private val notices = Channel<DoorNotice>(Channel.BUFFERED)
    val notice: Flow<DoorNotice> = notices.receiveAsFlow()
    private var shown: Long = -1L

    fun show(eventId: Long) {
        if (eventId == shown) return
        shown = eventId
        load()
    }

    fun load() {
        val id = shown.takeIf { it > 0 } ?: return
        _ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = api.event(id)) {
                is DoorResult.Ok -> {
                    val bmp = r.value.image?.decode()
                    _ui.value = Ui(loading = false, detail = r.value, lock = r.value.lock, picture = bmp)
                }
                else -> _ui.update { it.copy(loading = false, error = r.errorCode()) }
            }
        }
    }

    /** "Tür öffnen" / "Summer" after confirmation and device authentication. */
    fun open(lock: DoorLock) {
        if (_ui.value.busy || !DoorLogic.canOpenFromEvent(lock)) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val r = api.lockAction(lock.id, DoorAction.OPEN)
            _ui.update { it.copy(busy = false) }
            when (r) {
                is DoorResult.Ok -> {
                    r.value.lock?.let { l ->
                        _ui.update { it.copy(lock = l) }
                        repository.updateLock(l)
                    }
                    notices.send(r.value.message?.let { DoorNotice.Text(it) } ?: DoorNotice.Done)
                }
                else -> notices.send(r.notice())
            }
        }
    }

    fun notify(notice: DoorNotice) {
        notices.trySend(notice)
    }

    override fun onCleared() {
        _ui.value = Ui()
    }
}
