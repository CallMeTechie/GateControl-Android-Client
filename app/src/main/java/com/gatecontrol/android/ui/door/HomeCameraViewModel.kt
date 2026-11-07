package com.gatecontrol.android.ui.door

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.gatecontrol.android.door.CameraMediaUrls
import com.gatecontrol.android.door.DoorApi
import com.gatecontrol.android.door.DoorRepository
import com.gatecontrol.android.door.DoorResult
import com.gatecontrol.android.door.HomeCamera
import com.gatecontrol.android.door.HomeCameraLogic
import com.gatecontrol.android.door.HomeCameraMedia
import com.gatecontrol.android.door.HomeClip
import com.gatecontrol.android.door.HomeLive
import com.gatecontrol.android.door.HomeStill
import com.gatecontrol.android.door.errorCode
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/** One-shot results of the camera buttons (snackbar / share sheet). */
sealed interface HomeCameraEvent {
    data object StillSaved : HomeCameraEvent
    data object StillFailed : HomeCameraEvent
    data class Share(val uri: Uri) : HomeCameraEvent
    data class Error(val code: String) : HomeCameraEvent
}

/**
 * "Kameras" of the door screen (home-network cameras of the door plugin 1.6.0): the cameras
 * this person sees, the live picture of the chosen one — HLS through the VPN (or in the
 * server's LAN), else a new still every 10 s — and its clips of today. Everything runs only
 * while the door screen is in front ([start] / [stop]); media tokens (10 min) are renewed a
 * minute before they end, the player keeps running on the new token. Pictures stay in memory.
 */
@HiltViewModel
class HomeCameraViewModel @Inject constructor(
    private val api: DoorApi,
    private val media: HomeCameraMedia,
    private val repository: DoorRepository,
    private val tunnelManager: TunnelManager,
) : ViewModel() {

    data class Ui(
        /** The first answer of `GET cameras/home` is through. */
        val loaded: Boolean = false,
        /** Only the cameras the server lists for this person (column "sieht"). */
        val cameras: List<HomeCamera> = emptyList(),
        val selected: String? = null,
    ) {
        val camera: HomeCamera? get() = cameras.firstOrNull { it.id == selected }
    }

    data class LiveUi(
        val cameraId: String? = null,
        /** The newest answer of `GET …/live` (null while asking after a start or a change of camera). */
        val live: HomeLive? = null,
        /** Absolute HLS address with the newest token (live mode only). */
        val hlsUrl: String? = null,
        /** Counts up when the player has to start over (another camera, live again, a retry) — not on a renewed token. */
        val session: Int = 0,
        /** Snapshot mode: the newest still; live mode: the picture until the first frame. */
        val still: HomeStill? = null,
        /** The live video failed: the stills show until the next try. */
        val videoFailed: Boolean = false,
        val error: String? = null,
    )

    data class ClipsUi(
        val cameraId: String? = null,
        val loading: Boolean = false,
        val clips: List<HomeClip> = emptyList(),
        val loadedAtMs: Long = 0L,
        val error: String? = null,
    )

    /** A clip being played: [url] is its absolute address (token 1 h). */
    data class PlayingClip(val clip: HomeClip, val url: String, val cameraName: String)

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private val _live = MutableStateFlow(LiveUi())
    val live: StateFlow<LiveUi> = _live.asStateFlow()

    private val _clips = MutableStateFlow(ClipsUi())
    val clips: StateFlow<ClipsUi> = _clips.asStateFlow()

    private val _active = MutableStateFlow(false)
    /** The door screen is in front: the player may run. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _sound = MutableStateFlow(false)
    val sound: StateFlow<Boolean> = _sound.asStateFlow()

    private val _playing = MutableStateFlow<PlayingClip?>(null)
    val playing: StateFlow<PlayingClip?> = _playing.asStateFlow()

    private val _stillBusy = MutableStateFlow(false)
    val stillBusy: StateFlow<Boolean> = _stillBusy.asStateFlow()

    private val events = Channel<HomeCameraEvent>(Channel.BUFFERED)
    val event: Flow<HomeCameraEvent> = events.receiveAsFlow()

    private var wanted: String? = null
    private var listJob: Job? = null
    private var liveJob: Job? = null
    private var clipsJob: Job? = null
    private var retryJob: Job? = null
    private var videoFailures = 0
    private val stillCounter = AtomicLong(System.currentTimeMillis() / 1000)

    init {
        viewModelScope.launch {
            // "Personen an der Kamera": the list (last event) and today's clips changed.
            repository.pushes.collect { info ->
                if (!info.isHomeCamera || !_active.value) return@collect
                loadList()
                if (info.homeCameraId == _ui.value.selected) loadClips(info.homeCameraId!!)
            }
        }
        viewModelScope.launch {
            // VPN on or off: the server decides anew between live video and stills.
            tunnelManager.state.map { it is TunnelState.Connected }.distinctUntilChanged().drop(1).collect {
                if (!_active.value) return@collect
                delay(VPN_SETTLE_MS)
                _ui.value.selected?.let { restartLive(it) }
            }
        }
    }

    /** The newest HLS address — read by the player for every load (token renewal). */
    fun currentHls(): String? = _live.value.hlsUrl

    /** Data sources of the live player: live files of the own server only, always under the newest token. */
    @OptIn(UnstableApi::class)
    fun liveSourceFactory(): DataSource.Factory? =
        media.dataSourceFactory { requested -> currentHls()?.let { CameraMediaUrls.retoken(requested, it) } }

    /** Data sources of a clip: exactly its own address. */
    @OptIn(UnstableApi::class)
    fun clipSourceFactory(url: String): DataSource.Factory? =
        media.dataSourceFactory { requested -> requested.takeIf { CameraMediaUrls.sameFile(it, url) } }

    /** A push asked for this camera: shown once it is listed. */
    fun focus(cameraId: String?) {
        if (cameraId == null) return
        wanted = cameraId
        val ui = _ui.value
        if (ui.cameras.any { it.id == cameraId } && ui.selected != cameraId) select(cameraId)
    }

    fun start() {
        if (_active.value) return
        _active.value = true
        // Tokens may have ended meanwhile: the player waits for a fresh answer.
        _live.update { it.copy(live = null, hlsUrl = null, videoFailed = false, error = null) }
        listJob = viewModelScope.launch {
            while (isActive) {
                loadList()
                delay(HomeCameraLogic.LIST_POLL_MS)
            }
        }
        _ui.value.selected?.let { startCamera(it) }
    }

    fun stop() {
        _active.value = false
        listJob?.cancel()
        liveJob?.cancel()
        clipsJob?.cancel()
        retryJob?.cancel()
        listJob = null
        liveJob = null
        clipsJob = null
        retryJob = null
    }

    fun select(cameraId: String) {
        if (_ui.value.cameras.none { it.id == cameraId }) return
        wanted = null
        if (_ui.value.selected == cameraId) return
        _ui.update { it.copy(selected = cameraId) }
        _live.value = LiveUi(cameraId = cameraId, session = _live.value.session + 1)
        _clips.value = ClipsUi(cameraId = cameraId)
        videoFailures = 0
        if (_active.value) startCamera(cameraId)
    }

    fun toggleSound() {
        _sound.update { !it }
    }

    /**
     * Plugin 1.7.0: this person's push "Personen an der Kamera" for [cameraId] — optimistic,
     * reverted (with the error in the snackbar) when the server refuses.
     */
    fun setMyPush(cameraId: String, on: Boolean) {
        val before = _ui.value.cameras.firstOrNull { it.id == cameraId }?.myPush ?: return
        setMine(cameraId, on)
        viewModelScope.launch {
            when (val r = api.setHomePush(cameraId, on)) {
                is DoorResult.Ok -> setMine(cameraId, r.value)
                else -> {
                    setMine(cameraId, before)
                    events.trySend(HomeCameraEvent.Error(r.errorCode() ?: DoorApi.CODE_INVALID))
                }
            }
        }
    }

    private fun setMine(cameraId: String, on: Boolean) {
        _ui.update { ui -> ui.copy(cameras = ui.cameras.map { if (it.id == cameraId) it.copy(myPush = on) else it }) }
    }

    private fun startCamera(cameraId: String) {
        restartLive(cameraId)
        clipsJob?.cancel()
        clipsJob = viewModelScope.launch {
            while (isActive) {
                loadClips(cameraId)
                delay(HomeCameraLogic.CLIPS_POLL_MS)
            }
        }
    }

    private suspend fun loadList() {
        val r = api.homeCameras()
        val cameras = when (r) {
            is DoorResult.Ok -> if (r.value.supported) r.value.cameras else emptyList()
            // An older plugin (no such route) or no right any more: nothing to show.
            is DoorResult.Failed -> if (r.http == 404 || r.http == 403) emptyList() else _ui.value.cameras
            else -> _ui.value.cameras
        }
        val before = _ui.value.selected
        val pick = HomeCameraLogic.pick(cameras, wanted, before)
        if (pick != null && pick == wanted) wanted = null
        _ui.value = Ui(loaded = true, cameras = cameras, selected = pick)
        if (pick != before) {
            _live.value = LiveUi(cameraId = pick, session = _live.value.session + 1)
            _clips.value = ClipsUi(cameraId = pick)
            videoFailures = 0
            if (_active.value) {
                if (pick != null) startCamera(pick) else {
                    liveJob?.cancel()
                    clipsJob?.cancel()
                }
            }
        }
    }

    /** Asks for the live answer of [cameraId] again and keeps it fresh (tokens, stills). */
    private fun restartLive(cameraId: String) {
        liveJob?.cancel()
        retryJob?.cancel()
        liveJob = viewModelScope.launch {
            var failures = 0
            while (isActive && _live.value.cameraId == cameraId) {
                when (val r = api.homeLive(cameraId)) {
                    is DoorResult.Ok -> {
                        failures = 0
                        val l = r.value
                        val hls = if (l.isLive) media.resolve(l.hlsPath) else null
                        _live.update { cur ->
                            if (cur.cameraId != cameraId) return@update cur
                            // A renewed token keeps the running player; anything else starts it over.
                            val keep = cur.live?.isLive == true && cur.hlsUrl != null && hls != null
                            cur.copy(live = l, hlsUrl = hls, session = if (keep) cur.session else cur.session + 1, error = null)
                        }
                        keepStills(cameraId, l)
                    }
                    else -> {
                        failures++
                        _live.update { if (it.cameraId == cameraId) it.copy(error = r.errorCode() ?: "failed") else it }
                        delay(HomeCameraLogic.retryInMs(failures))
                    }
                }
            }
        }
    }

    /**
     * Until the tokens of [l] are renewed: in snapshot mode (and while the video failed) a new
     * still every `every` s, in live mode one still as the picture before the first frame.
     */
    private suspend fun keepStills(cameraId: String, l: HomeLive) {
        val now = System.currentTimeMillis()
        val renewAt = now + HomeCameraLogic.renewInMs(l.expiresAt, now)
        while (System.currentTimeMillis() < renewAt) {
            val cur = _live.value
            if (cur.cameraId != cameraId) return
            if (!l.isLive || cur.videoFailed || cur.still == null) {
                media.still(l.snapshotPath, stillCounter.incrementAndGet())?.let { s ->
                    _live.update { if (it.cameraId == cameraId) it.copy(still = s) else it }
                }
            }
            delay(minOf(l.every * 1000L, (renewAt - System.currentTimeMillis()).coerceAtLeast(1L)))
        }
    }

    /** The player could not play the live list (ring not ready yet, network): stills, then another try. */
    fun onVideoError() {
        val cameraId = _live.value.cameraId ?: return
        if (_live.value.videoFailed) return
        videoFailures++
        _live.update { it.copy(videoFailed = true) }
        retryJob?.cancel()
        retryJob = viewModelScope.launch {
            delay(HomeCameraLogic.retryInMs(videoFailures))
            _live.update { if (it.cameraId == cameraId && it.videoFailed) it.copy(videoFailed = false, session = it.session + 1) else it }
        }
    }

    fun onVideoPlaying() {
        videoFailures = 0
    }

    private suspend fun loadClips(cameraId: String) {
        _clips.update { if (it.cameraId == cameraId) it.copy(loading = true) else ClipsUi(cameraId = cameraId, loading = true) }
        val r = api.homeClips(cameraId)
        _clips.update { cur ->
            if (cur.cameraId != cameraId) return@update cur
            when (r) {
                is DoorResult.Ok -> cur.copy(loading = false, clips = r.value.clips, loadedAtMs = System.currentTimeMillis(), error = null)
                else -> cur.copy(loading = false, error = r.errorCode())
            }
        }
    }

    suspend fun thumb(clip: HomeClip): Bitmap? = media.peekThumb(clip.id) ?: media.thumb(clip.id, clip.thumbPath)

    fun peekThumb(clip: HomeClip): Bitmap? = media.peekThumb(clip.id)

    /** Plays [clip]; a list older than 50 min is fetched again first (its addresses live 1 h). */
    fun openClip(clip: HomeClip) {
        val cameraId = _clips.value.cameraId ?: return
        val name = _ui.value.camera?.name.orEmpty()
        viewModelScope.launch {
            var c = clip
            if (System.currentTimeMillis() - _clips.value.loadedAtMs > HomeCameraLogic.CLIPS_FRESH_MS) {
                loadClips(cameraId)
                c = _clips.value.clips.firstOrNull { it.id == clip.id } ?: run {
                    events.send(HomeCameraEvent.Error("not_found"))
                    return@launch
                }
            }
            val url = media.resolve(c.clipPath) ?: return@launch
            _playing.value = PlayingClip(c, url, name)
        }
    }

    fun closeClip() {
        _playing.value = null
    }

    /** "Standbild": a full picture of the camera, saved to the gallery or handed to the share sheet. */
    fun takeStill(share: Boolean) {
        val cam = _ui.value.camera ?: return
        if (_stillBusy.value) return
        _stillBusy.value = true
        viewModelScope.launch {
            try {
                when (val r = api.homeStill(cam.id)) {
                    is DoorResult.Ok -> {
                        val at = r.value.at?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.now()
                        if (share) {
                            val uri = media.shareFile(r.value.bytes, cam.name, at)
                            events.send(if (uri != null) HomeCameraEvent.Share(uri) else HomeCameraEvent.StillFailed)
                        } else {
                            events.send(if (media.saveToGallery(r.value.bytes, cam.name, at) != null) HomeCameraEvent.StillSaved else HomeCameraEvent.StillFailed)
                        }
                    }
                    else -> events.send(HomeCameraEvent.Error(r.errorCode() ?: "failed"))
                }
            } finally {
                _stillBusy.value = false
            }
        }
    }

    override fun onCleared() {
        stop()
        // Pictures stay in memory only as long as the screen; the shared still goes with it.
        _live.value = LiveUi()
        media.clearThumbs()
        media.clearShared()
    }

    companion object {
        /** After the VPN came up or went down: a moment for routes and the server's name. */
        private const val VPN_SETTLE_MS = 1_500L
    }
}
