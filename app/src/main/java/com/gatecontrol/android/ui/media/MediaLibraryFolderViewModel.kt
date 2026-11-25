package com.gatecontrol.android.ui.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.MediaProblem
import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaRoute
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.jellyfin.JellyfinFolder
import com.gatecontrol.android.media.jellyfin.JellyfinLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A library of a Jellyfin server on the page "Ordner" ("Filme", "Serien", "Heimvideos"). */
data class MediaLibraryRow(val source: MediaSource, val folder: JellyfinFolder)

/** An open library or folder of Jellyfin: the way into it ([trail], the library first), its folders and titles. */
data class MediaLibraryPlace(
    val source: MediaSource,
    val trail: List<JellyfinFolder>,
    val folders: List<JellyfinFolder> = emptyList(),
    val titles: List<MediaTitle> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val problem: MediaProblem? = null,
)

/**
 * The libraries of the Jellyfin servers on the page "Ordner": [libraries] of all servers, [open] the library or
 * folder looked into. [problem]: no server could be read (a server read is enough to show its libraries).
 */
data class MediaLibraryFoldersUi(
    val servers: List<MediaSource> = emptyList(),
    val libraries: List<MediaLibraryRow> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val problem: MediaProblem? = null,
    val open: MediaLibraryPlace? = null,
)

/**
 * "Ordner" of the Medien tab for Jellyfin: the person's libraries (`/UserViews`), then a library's or folder's
 * folders and titles (`/Items?parentId=`). Before, the page knew only SMB and WebDAV shares: with Jellyfin alone it
 * stayed an empty card.
 */
@HiltViewModel
class MediaLibraryFolderViewModel @Inject constructor(private val hub: MediaHub) : ViewModel() {

    private val _ui = MutableStateFlow(MediaLibraryFoldersUi())
    val ui: StateFlow<MediaLibraryFoldersUi> = _ui.asStateFlow()

    private var listJob: Job? = null
    private var placeJob: Job? = null

    private fun servers(): List<MediaSource> = hub.store.sources.value.filter { it.kind == MediaSourceKind.JELLYFIN }

    private fun library(s: MediaSource): JellyfinLibrary? = runCatching { hub.library(s) as? JellyfinLibrary }.getOrNull()

    private fun problemOf(r: MediaResult<*>, s: MediaSource): MediaProblem? = when (r) {
        is MediaResult.Ok -> null
        MediaResult.Offline -> if (runCatching { hub.route(s) }.getOrNull() == MediaRoute.BLOCKED) MediaProblem.VPN else MediaProblem.OFFLINE
        MediaResult.Unauthorized -> MediaProblem.SIGNED_OUT
        else -> MediaProblem.FAILED
    }

    /** The page came to the front: the libraries again, the open folder again (quietly). */
    fun refresh() {
        val servers = servers()
        _ui.update { u -> u.copy(servers = servers, open = u.open?.takeIf { o -> servers.any { it.id == o.source.id } }) }
        loadLibraries(quiet = _ui.value.loaded)
        _ui.value.open?.let { load(it, quiet = true) }
    }

    fun retry() {
        val open = _ui.value.open
        if (open != null) load(open, quiet = false) else loadLibraries(quiet = false)
    }

    private fun loadLibraries(quiet: Boolean) {
        val servers = _ui.value.servers
        listJob?.cancel()
        if (servers.isEmpty()) {
            _ui.update { it.copy(libraries = emptyList(), loading = false, loaded = true, problem = null) }
            return
        }
        listJob = viewModelScope.launch {
            _ui.update { it.copy(loading = true, problem = if (quiet) it.problem else null) }
            val rows = mutableListOf<MediaLibraryRow>()
            var problem: MediaProblem? = null
            var anyRead = false
            for (s in servers) {
                val lib = library(s)
                if (lib == null) {
                    problem = problem ?: MediaProblem.FAILED
                    continue
                }
                when (val r = lib.views()) {
                    is MediaResult.Ok -> {
                        anyRead = true
                        rows += r.value.map { MediaLibraryRow(s, it) }
                    }
                    else -> problem = problem ?: problemOf(r, s)
                }
            }
            _ui.update {
                it.copy(
                    libraries = rows,
                    loading = false,
                    loaded = true,
                    // one server read is enough to show its libraries
                    problem = if (anyRead) null else problem,
                )
            }
        }
    }

    /** A library of the list. */
    fun openLibrary(row: MediaLibraryRow) {
        val place = MediaLibraryPlace(row.source, listOf(row.folder))
        _ui.update { it.copy(open = place) }
        load(place, quiet = false)
    }

    /** A folder inside the open one. */
    fun openFolder(folder: JellyfinFolder) {
        val open = _ui.value.open ?: return
        val place = MediaLibraryPlace(open.source, open.trail + folder)
        _ui.update { it.copy(open = place) }
        load(place, quiet = false)
    }

    /** A part of the path bar: back to that folder ([index] in the trail). */
    fun openTrail(index: Int) {
        val open = _ui.value.open ?: return
        if (index !in open.trail.indices || index == open.trail.lastIndex) return
        val place = MediaLibraryPlace(open.source, open.trail.take(index + 1))
        _ui.update { it.copy(open = place) }
        load(place, quiet = false)
    }

    /** One step up (back): false when no library is open. */
    fun up(): Boolean {
        val open = _ui.value.open ?: return false
        if (open.trail.size > 1) {
            openTrail(open.trail.lastIndex - 1)
        } else {
            allLibraries()
        }
        return true
    }

    /** Back to the list of libraries and shares. */
    fun allLibraries() {
        placeJob?.cancel()
        _ui.update { it.copy(open = null) }
    }

    private fun load(place: MediaLibraryPlace, quiet: Boolean) {
        placeJob?.cancel()
        placeJob = viewModelScope.launch {
            update(place) { it.copy(loading = true, problem = if (quiet) it.problem else null) }
            val lib = library(place.source)
            if (lib == null) {
                update(place) { it.copy(loading = false, loaded = true, problem = MediaProblem.FAILED) }
                return@launch
            }
            when (val r = lib.children(place.trail.last().id)) {
                is MediaResult.Ok -> update(place) {
                    it.copy(folders = r.value.folders, titles = r.value.titles, loading = false, loaded = true, problem = null)
                }
                else -> update(place) { it.copy(loading = false, loaded = true, problem = problemOf(r, place.source)) }
            }
        }
    }

    /** Changes the open place if it is still [place] (the person may have gone on). */
    private fun update(place: MediaLibraryPlace, change: (MediaLibraryPlace) -> MediaLibraryPlace) {
        _ui.update { u ->
            val open = u.open
            if (open != null && open.source.id == place.source.id && open.trail == place.trail) u.copy(open = change(open)) else u
        }
    }
}
