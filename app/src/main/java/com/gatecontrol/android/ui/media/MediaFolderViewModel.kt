package com.gatecontrol.android.ui.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.MediaPictures
import com.gatecontrol.android.media.MediaPlayRequests
import com.gatecontrol.android.media.MediaProblem
import com.gatecontrol.android.media.MediaQueue
import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaShareProbes
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.jellyfin.JellyfinDeviceCaps
import com.gatecontrol.android.media.share.ShareEntry
import com.gatecontrol.android.media.share.ShareFolderCount
import com.gatecontrol.android.media.share.ShareFolderVideo
import com.gatecontrol.android.media.share.ShareFolders
import com.gatecontrol.android.media.share.ShareFormatProblem
import com.gatecontrol.android.media.share.ShareLibrary
import com.gatecontrol.android.media.share.ShareProbe
import com.gatecontrol.android.media.share.ShareProbeResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A sub-folder's row: its name and, once read, what it holds ("12 Videos"). */
data class MediaFolderRow(val entry: ShareEntry, val count: ShareFolderCount? = null)

/**
 * A video's row: the file, its title with the progress kept on the phone, the length (from the progress or from
 * looking into the file) and what the phone cannot play of it ("Ton DTS: am Handy kein Ton").
 */
data class MediaFileRow(val video: ShareFolderVideo, val probe: ShareProbeResult? = null, val problem: ShareFormatProblem? = null) {
    val title: MediaTitle get() = video.title
    val entry: ShareEntry get() = video.entry
    val durationMs: Long? get() = video.title.runtimeMs?.takeIf { it > 0 } ?: probe?.durationMs?.takeIf { it > 0 }

    /** "noch 19 Min." once begun and its length is known. */
    val leftMs: Long? get() = durationMs?.takeIf { title.resumable }?.let { (it - title.positionMs).coerceAtLeast(0) }

    /** The bar under the picture: watched share, 0 when not begun. */
    val progress: Float get() = durationMs?.takeIf { title.resumable }?.let { (title.positionMs.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
}

/**
 * The page "Ordner" (mockup 5): the shares, then one share's folders and videos. [via]: how the share is read now
 * (SMB at home, WebDAV away for a pair), [paired]: set up as both. [query]: "Im Ordner suchen", [found] its videos
 * below the folder (null: no search).
 */
data class MediaFolderUi(
    val shares: List<MediaSource> = emptyList(),
    val share: MediaSource? = null,
    val path: String = "",
    val via: MediaSourceKind? = null,
    val atHome: Boolean? = null,
    val paired: Boolean = false,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val problem: MediaProblem? = null,
    val folders: List<MediaFolderRow> = emptyList(),
    val files: List<MediaFileRow> = emptyList(),
    val query: String = "",
    val searching: Boolean = false,
    val found: List<MediaFileRow>? = null,
) {
    /** A step up is possible: inside a folder, or inside one of several shares. */
    val canGoUp: Boolean get() = share != null && (path.isNotEmpty() || shares.size > 1)

    /** The folders shown: while searching only those whose name holds the words. */
    val shownFolders: List<MediaFolderRow>
        get() = if (query.isBlank()) folders else folders.filter { ShareFolders.matches(it.entry.name, query, folder = true) }
}

/**
 * The page "Ordner" of the Medien tab (stage 4d): the folders of the SMB and WebDAV shares, including what Jellyfin
 * does not have. Always the original; the progress stays on the phone ([ShareLibrary]: watched from 90 %, under 30 s
 * no place kept). A pair is read at home by SMB and away by WebDAV: when the network changes, the folder is read
 * again the other way, at the same place.
 */
@HiltViewModel
class MediaFolderViewModel @Inject constructor(
    private val hub: MediaHub,
    private val requests: MediaPlayRequests,
    private val probes: MediaShareProbes,
    val pictures: MediaPictures,
) : ViewModel() {

    private val _ui = MutableStateFlow(MediaFolderUi())
    val ui: StateFlow<MediaFolderUi> = _ui.asStateFlow()

    private var loadJob: Job? = null
    private var detailJob: Job? = null
    private var searchJob: Job? = null
    private var caps: JellyfinDeviceCaps? = null

    init {
        viewModelScope.launch {
            // home ↔ away: a pair changes from SMB to WebDAV, the folder is read again the other way
            hub.link.map { it.atHome }.distinctUntilChanged().drop(1).collect {
                if (_ui.value.share != null) load(quiet = true)
            }
        }
    }

    private fun library(s: MediaSource): ShareLibrary? = hub.library(s) as? ShareLibrary

    /** The page came to the front (or back from the player): the shares, the folder again (the progress). */
    fun refresh() {
        val shares = hub.shares()
        val open = _ui.value.share?.let { s -> shares.firstOrNull { it.id == s.id } }
        _ui.update { it.copy(shares = shares, share = open, path = if (open == null) "" else it.path) }
        when {
            open != null -> load(quiet = _ui.value.loaded)
            shares.size == 1 -> openShare(shares.single())
            else -> _ui.update { it.copy(folders = emptyList(), files = emptyList(), loaded = false, problem = null) }
        }
    }

    fun openShare(s: MediaSource) {
        _ui.update { it.copy(share = s, path = "", folders = emptyList(), files = emptyList(), loaded = false, query = "", found = null) }
        load(quiet = false)
    }

    /** A folder of the list or of the path bar ("" the share's root). */
    fun openFolder(path: String) {
        if (_ui.value.share == null) return
        _ui.update { it.copy(path = path, folders = emptyList(), files = emptyList(), loaded = false, query = "", found = null) }
        load(quiet = false)
    }

    /** One step up (back): false when there is none (the page's back then leaves the tab). */
    fun up(): Boolean {
        val u = _ui.value
        if (!u.canGoUp) return false
        if (u.path.isNotEmpty()) {
            openFolder(u.path.substringBeforeLast('/', ""))
        } else {
            allShares()
        }
        return true
    }

    /** "Alle Freigaben" of the path bar: back to the list of shares. */
    fun allShares() {
        loadJob?.cancel()
        detailJob?.cancel()
        searchJob?.cancel()
        _ui.update { it.copy(share = null, path = "", folders = emptyList(), files = emptyList(), loaded = false, problem = null, query = "", found = null) }
    }

    fun retry() = load(quiet = false)

    private fun load(quiet: Boolean) {
        val s = _ui.value.share ?: return
        val path = _ui.value.path
        loadJob?.cancel()
        detailJob?.cancel()
        loadJob = viewModelScope.launch {
            _ui.update { it.copy(loading = true, problem = if (quiet) it.problem else null) }
            val lib = library(s)
            if (lib == null) {
                _ui.update { it.copy(loading = false, problem = MediaProblem.FAILED) }
                return@launch
            }
            val via = lib.files().kind
            val paired = s.pairWith != null && hub.store.source(s.pairWith!!)?.pairWith == s.id
            when (val r = lib.folder(path)) {
                is MediaResult.Ok -> {
                    val old = _ui.value.folders.associateBy { it.entry.path }
                    val c = caps()
                    _ui.update { u ->
                        u.copy(
                            via = via,
                            atHome = hub.link.value.atHome,
                            paired = paired,
                            loading = false,
                            loaded = true,
                            problem = null,
                            folders = r.value.folders.map { e -> MediaFolderRow(e, old[e.path]?.count) },
                            files = r.value.videos.map { v -> row(s, v, c) },
                        )
                    }
                    details(s, lib)
                    if (_ui.value.query.isNotBlank()) search(_ui.value.query, delayMs = 0)
                }
                else -> _ui.update {
                    it.copy(via = via, paired = paired, loading = false, loaded = true, problem = if (r is MediaResult.Offline) MediaProblem.OFFLINE else MediaProblem.FAILED)
                }
            }
        }
    }

    private fun caps(): JellyfinDeviceCaps = caps ?: hub.caps().also { caps = it }

    /** A video's row with what is known of it already (the name's words until the file was looked into). */
    private fun row(s: MediaSource, v: ShareFolderVideo, c: JellyfinDeviceCaps): MediaFileRow {
        val known = probes.cached(s.id, v.entry)
        val probe = if (known != null) known.getOrNull() ?: ShareProbe.fromName(v.entry.name) else ShareProbe.fromName(v.entry.name)
        return MediaFileRow(v, probe, ShareProbe.problem(probe, c))
    }

    /** What each sub-folder holds and what each video holds, a few at a time, after the list is shown. */
    private fun details(s: MediaSource, lib: ShareLibrary) {
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            val c = caps()
            for (f in _ui.value.folders.take(MAX_COUNTS)) {
                if (f.count != null) continue
                val count = lib.count(f.entry.path) ?: continue
                _ui.update { u -> u.copy(folders = u.folders.map { if (it.entry.path == f.entry.path) it.copy(count = count) else it }) }
            }
            for (f in _ui.value.files.take(MAX_PROBES)) {
                if (probes.cached(s.id, f.entry) != null) continue
                val r = probes.probe(s.id, lib.files(), f.entry) ?: ShareProbe.fromName(f.entry.name)
                val problem = ShareProbe.problem(r, c)
                _ui.update { u ->
                    u.copy(
                        files = u.files.map { if (it.entry.path == f.entry.path) it.copy(probe = r, problem = problem) else it },
                        found = u.found?.map { if (it.entry.path == f.entry.path) it.copy(probe = r, problem = problem) else it },
                    )
                }
            }
        }
    }

    /** "Im Ordner suchen": this folder's videos at once, those of the folders below (the share's index) after a moment. */
    fun setQuery(text: String) {
        _ui.update { it.copy(query = text) }
        search(text, delayMs = SEARCH_DELAY_MS)
    }

    private fun search(text: String, delayMs: Long) {
        searchJob?.cancel()
        val s = _ui.value.share ?: return
        if (text.isBlank()) {
            _ui.update { it.copy(found = null, searching = false) }
            return
        }
        val here = _ui.value.files.filter { ShareFolders.matches(it.entry.name, text) }
        _ui.update { it.copy(found = here, searching = true) }
        searchJob = viewModelScope.launch {
            delay(delayMs)
            val lib = library(s) ?: return@launch
            val c = caps()
            val below = lib.searchBelow(_ui.value.path, text).valueOrNull().orEmpty()
            val seen = here.map { it.entry.path }.toSet()
            val more = below.filter { it.entry.path !in seen }.map { row(s, it, c) }
            _ui.update { it.copy(found = here + more, searching = false) }
        }
    }

    /** A video tapped: it and the rest of the list after it (the folder's, or the search's), where it was left. */
    fun play(row: MediaFileRow, fromStart: Boolean = false) {
        val s = _ui.value.share ?: return
        val list = (_ui.value.found ?: _ui.value.files).map { it.title }
        val at = list.indexOfFirst { it.id == row.title.id }.coerceAtLeast(0)
        val items = if (list.isEmpty()) listOf(row.title) else list
        viewModelScope.launch {
            requests.play(MediaQueue(row.title.sourceId.ifEmpty { s.id }, items, at, startMs = if (fromStart) 0L else null))
        }
    }

    /** "Als gesehen markieren" / "ungesehen" from a video's menu. */
    fun setPlayed(row: MediaFileRow, played: Boolean) {
        val s = _ui.value.share ?: return
        viewModelScope.launch {
            val lib = library(s) ?: return@launch
            if (lib.setPlayed(row.title.id, played) is MediaResult.Ok) load(quiet = true)
        }
    }

    private companion object {
        const val MAX_COUNTS = 40
        const val MAX_PROBES = 80
        const val SEARCH_DELAY_MS = 300L
    }
}
