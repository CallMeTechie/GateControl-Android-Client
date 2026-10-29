package com.gatecontrol.android.ui.drive

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.drive.DAlbum
import com.gatecontrol.android.drive.DArea
import com.gatecontrol.android.drive.DMemories
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DPhotos
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveLive
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.DriveTransfers
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import javax.inject.Inject

/** A list of photos loaded page by page (`before`): what is there, whether more comes, what went wrong. */
data class PhotoPages(
    val items: List<DNode> = emptyList(),
    val next: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** The first page came (an empty list then means: no photos). */
    val loaded: Boolean = false,
) {
    val end: Boolean get() = loaded && next == null
}

/** Loads [PhotoPages] with [fetch] (`before` → a page); one load at a time. */
internal class PhotoPager(private val scope: kotlinx.coroutines.CoroutineScope, private val fetch: suspend (before: String?) -> DResult<DPhotos>) {
    private val _state = MutableStateFlow(PhotoPages())
    val state: StateFlow<PhotoPages> = _state.asStateFlow()
    private var job: Job? = null

    fun reload(quiet: Boolean = false) {
        job?.cancel()
        _state.update { if (quiet && it.loaded) it.copy(loading = true, error = null) else PhotoPages(loading = true) }
        job = scope.launch {
            val r = fetch(null)
            _state.value = when (r) {
                is DResult.Ok -> PhotoPages(r.value.items, r.value.next, loaded = true)
                else -> _state.value.copy(loading = false, error = r.errorCode())
            }
        }
    }

    fun more() {
        val s = _state.value
        if (s.loading || !s.loaded || s.next == null) return
        _state.value = s.copy(loading = true, error = null)
        job = scope.launch {
            val r = fetch(s.next)
            _state.update { cur ->
                when (r) {
                    is DResult.Ok -> cur.copy(items = DriveLogic.appendPage(cur.items, r.value.items), next = r.value.next, loading = false)
                    else -> cur.copy(loading = false, error = r.errorCode())
                }
            }
        }
    }

    /** After a failure: the first page again, or the next one. */
    fun retry() = if (_state.value.loaded) more() else reload()

    fun drop(ids: Set<Long>) = _state.update { it.copy(items = it.items.filter { n -> n.id !in ids }) }
}

/** The Drive tab's photos (Drive 1.7): the timeline by month, "Vor einem Jahr", the albums. */
@HiltViewModel
class DrivePhotosViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DriveApi,
    private val transfers: DriveTransfers,
    val live: DriveLive,
    val ops: com.gatecontrol.android.drive.DriveOps,
) : ViewModel() {

    private val _cameraOnly = MutableStateFlow(DriveViewPref.cameraOnly(context))
    /** "Nur Kamera-Uploads" (Drive 1.10, feature `photosFolder`); kept on the device. */
    val cameraOnly: StateFlow<Boolean> = _cameraOnly.asStateFlow()

    /** The filter as it is sent: only while Drive knows the camera uploads. */
    private fun camera(): Boolean = DriveLogic.cameraFilter(_cameraOnly.value, live.me.value)

    private val pager = PhotoPager(viewModelScope) { before -> api.photos(before = before, camera = camera()) }
    val timeline: StateFlow<PhotoPages> = pager.state

    private val _months = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Photos per month ("2026-10" → 120) for the headings. */
    val months: StateFlow<Map<String, Int>> = _months.asStateFlow()

    private val _memories = MutableStateFlow<List<DMemories>>(emptyList())
    val memories: StateFlow<List<DMemories>> = _memories.asStateFlow()

    private val _albums = MutableStateFlow<DriveLoad<List<DAlbum>>?>(null)
    val albums: StateFlow<DriveLoad<List<DAlbum>>?> = _albums.asStateFlow()

    /** The ticked photos and what is done with them (send, album, save, move, delete; Drive 1.12). */
    internal val bulk = DriveBulkActions(
        context = context,
        scope = viewModelScope,
        api = api,
        ops = ops,
        transfers = transfers,
        live = live,
        items = { timeline.value.items },
        changed = { load(quiet = true) },
    )

    /** Photos ticked (a long press, then taps or a swipe). */
    val selected: StateFlow<Set<Long>> = bulk.selected

    /** A short message after an action (a Drive code or `photos_added:<n>`); the screen shows and clears it. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var started = false

    /** The first time the photos are shown: load them. */
    fun start() {
        if (started) return
        started = true
        refresh()
    }

    fun refresh() = load(quiet = true)

    /** The filter of what is shown (null: nothing loaded yet). */
    private var loadedCamera: Boolean? = null

    /** Drive's features came or changed: the filter as sent may be another now (`photosFolder`). */
    fun featuresChanged() {
        val was = loadedCamera ?: return
        if (camera() != was) load(quiet = false)
    }

    private fun load(quiet: Boolean) {
        val camera = camera()
        loadedCamera = camera
        pager.reload(quiet = quiet)
        viewModelScope.launch { _months.value = api.photoMonths(camera = camera).valueOrNull()?.associate { it.month to it.count }.orEmpty() }
        viewModelScope.launch { _memories.value = api.memories(LocalDate.now().toString(), camera = camera).valueOrNull().orEmpty() }
        if (_albums.value != null) loadAlbums()
    }

    /** "Nur Kamera-Uploads" on or off: the timeline, its months and the memories again. */
    fun setCameraOnly(on: Boolean) {
        if (on == _cameraOnly.value) return
        _cameraOnly.value = on
        DriveViewPref.setCameraOnly(context, on)
        bulk.clear()
        _months.value = emptyMap()
        _memories.value = emptyList()
        load(quiet = false)
    }

    fun more() = pager.more()

    fun loadAlbums() {
        if (_albums.value !is DriveLoad.Ready) _albums.value = DriveLoad.Loading
        viewModelScope.launch { _albums.value = api.albums().toLoad { list -> list.sortedByDescending { it.updatedAt.orEmpty() } } }
    }

    fun toggle(node: DNode) = bulk.toggle(node)

    fun clearSelection() = bulk.clear()

    fun clearMessage() { _message.value = null }

    /** Areas a new album can belong to (the ones the person writes to; Meine Dateien first). */
    fun albumAreas(): List<DArea> = DriveLogic.sortAreas(live.me.value?.areas.orEmpty().filter { it.writable })

    /** The ticked photos into [album]. */
    fun addSelectedTo(album: DAlbum) {
        val ids = selected.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val r = api.addToAlbum(album.id, ids)
            _message.value = if (r is DResult.Ok) "photos_added:${ids.size}" else r.errorCode()
            if (r is DResult.Ok) {
                bulk.clear()
                loadAlbums()
            }
        }
    }

    /** A new album [name] in [areaId] (null: Meine Dateien), with the ticked photos when [withSelected]. */
    fun createAlbum(name: String, areaId: String?, withSelected: Boolean) {
        viewModelScope.launch {
            when (val r = api.createAlbum(name.trim().take(NAME_MAX), areaId)) {
                is DResult.Ok -> {
                    val ids = selected.value.toList()
                    if (withSelected && ids.isNotEmpty()) {
                        val add = api.addToAlbum(r.value.id, ids)
                        _message.value = if (add is DResult.Ok) "photos_added:${ids.size}" else add.errorCode()
                        if (add is DResult.Ok) bulk.clear()
                    } else {
                        _message.value = "album_created"
                    }
                    loadAlbums()
                }
                else -> _message.value = r.errorCode()
            }
        }
    }

    suspend fun fetch(node: DNode): DResult<File> = transfers.fetch(node)

    companion object {
        const val NAME_MAX = 100
    }
}

/** One album (Drive 1.7): its photos, and — with the right to change it — name, photo frame, adding and removing. */
@HiltViewModel
class DriveAlbumViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val api: DriveApi,
    private val transfers: DriveTransfers,
    val live: DriveLive,
) : ViewModel() {

    val albumId: String = savedStateHandle.get<String>("id").orEmpty()

    private val _album = MutableStateFlow<DriveLoad<DAlbum>>(DriveLoad.Loading)
    val album: StateFlow<DriveLoad<DAlbum>> = _album.asStateFlow()

    private val pager = PhotoPager(viewModelScope) { before ->
        val r = api.album(albumId, before = before)
        if (r is DResult.Ok) _album.value = DriveLoad.Ready(r.value.album) else if (before == null) _album.value = r.toLoad { error("never") }
        when (r) {
            is DResult.Ok -> DResult.Ok(DPhotos(r.value.items, r.value.next))
            is DResult.Failed -> r
            DResult.Offline -> DResult.Offline
            DResult.NotConfigured -> DResult.NotConfigured
        }
    }
    val photos: StateFlow<PhotoPages> = pager.state

    /** Choosing photos to add: the whole timeline. */
    private val pickerPager = PhotoPager(viewModelScope) { before -> api.photos(before = before) }
    val picker: StateFlow<PhotoPages> = pickerPager.state

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _gone = MutableStateFlow(false)
    /** The album was deleted here: the page closes. */
    val gone: StateFlow<Boolean> = _gone.asStateFlow()

    init {
        pager.reload()
        if (live.me.value == null) viewModelScope.launch { live.current() }
    }

    fun refresh() = pager.reload(quiet = true)

    fun more() = pager.more()

    fun toggle(node: DNode) = _selected.update { if (node.id in it) it - node.id else it + node.id }

    fun clearSelection() { _selected.value = emptySet() }

    fun clearMessage() { _message.value = null }

    private fun changed(r: DResult<DAlbum>, done: String) {
        _message.value = r.errorCode() ?: done
        if (r is DResult.Ok) _album.value = DriveLoad.Ready(r.value)
    }

    fun rename(name: String) {
        viewModelScope.launch { changed(api.updateAlbum(albumId, name = name.trim().take(DrivePhotosViewModel.NAME_MAX)), "album_renamed") }
    }

    /** Feeds the photo frame of the wall tablet, or not (feature `frame`). */
    fun setFrame(on: Boolean) {
        viewModelScope.launch { changed(api.updateAlbum(albumId, frame = on), if (on) "album_frame_on" else "album_frame_off") }
    }

    /** The ticked photo as the album's cover. */
    fun coverSelected() {
        val id = _selected.value.singleOrNull() ?: return
        viewModelScope.launch {
            changed(api.updateAlbum(albumId, coverId = id), "album_cover")
            _selected.value = emptySet()
        }
    }

    fun delete() {
        viewModelScope.launch {
            val r = api.deleteAlbum(albumId)
            _message.value = r.errorCode() ?: "album_deleted"
            if (r is DResult.Ok) _gone.value = true
        }
    }

    /** The ticked photos out of the album (they stay in Drive). */
    fun removeSelected() {
        val ids = _selected.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val r = api.removeFromAlbum(albumId, ids.toList())
            changed(r, "photos_removed:${ids.size}")
            if (r is DResult.Ok) {
                _selected.value = emptySet()
                pager.drop(ids)
            }
        }
    }

    // ─── adding photos ──────────────────────────────────────────────

    private val _picked = MutableStateFlow<Set<Long>?>(null)
    /** While photos are being chosen to add: the ticked ones. */
    val picked: StateFlow<Set<Long>?> = _picked.asStateFlow()

    fun openPicker() {
        _picked.value = emptySet()
        if (!pickerPager.state.value.loaded) pickerPager.reload()
    }

    fun pickerMore() = pickerPager.more()

    fun pickerRetry() = pickerPager.retry()

    fun pick(node: DNode) = _picked.update { cur -> cur?.let { if (node.id in it) it - node.id else it + node.id } }

    fun closePicker() { _picked.value = null }

    fun addPicked() {
        val ids = _picked.value?.toList().orEmpty()
        _picked.value = null
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val r = api.addToAlbum(albumId, ids)
            changed(r, "photos_added:${ids.size}")
            if (r is DResult.Ok) pager.reload(quiet = true)
        }
    }

    suspend fun fetch(node: DNode): DResult<File> = transfers.fetch(node)
}
