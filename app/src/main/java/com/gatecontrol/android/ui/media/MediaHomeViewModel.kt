package com.gatecontrol.android.ui.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.media.MediaBrowse
import com.gatecontrol.android.media.MediaGrid
import com.gatecontrol.android.media.MediaGate
import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.server.MediaMigration
import com.gatecontrol.android.media.server.MediaPairEnd
import com.gatecontrol.android.media.server.MediaSignIn
import com.gatecontrol.android.media.server.MediaServerSources
import com.gatecontrol.android.media.MediaLink
import com.gatecontrol.android.media.MediaOrder
import com.gatecontrol.android.media.MediaPictures
import com.gatecontrol.android.media.MediaPlayRequests
import com.gatecontrol.android.media.MediaProblem
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.offline.MediaOffline
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The pages of the Medien tab (mockup 2, 3). */
enum class MediaTab { OVERVIEW, MOVIES, SERIES, FOLDERS }

/** "Übersicht": the rows of all sources in one (a title in Jellyfin and on a share once, Jellyfin's). */
data class MediaHomeUi(
    val sources: List<MediaSource> = emptyList(),
    /** The Jellyfin server shown ("Jellyfin · DS918+ ▾"). */
    val source: MediaSource? = null,
    val servers: List<MediaSource> = emptyList(),
    val shares: List<MediaSource> = emptyList(),
    val shareProblems: Set<String> = emptySet(),
    val loading: Boolean = false,
    val problem: MediaProblem? = null,
    val resume: List<MediaTitle> = emptyList(),
    val latest: List<MediaTitle> = emptyList(),
    val series: List<MediaTitle> = emptyList(),
    val seriesTotal: Int = 0,
    /** „Neu anmelden“: running, or why it did not work. */
    val signIn: MediaSignInState = MediaSignInState(),
    /** A source set up by hand to show on Medien › Quellen (once; [MediaHomeViewModel.sourceOpened]). */
    val openSource: String? = null,
) {
    val any: Boolean get() = sources.isNotEmpty()
}

/** "Filme" or "Serien" as a grid: the chips' order, what is loaded, the genres for the chip. */
data class MediaGridUi(
    val order: MediaOrder = MediaOrder(),
    val items: List<MediaTitle> = emptyList(),
    val total: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val problem: MediaProblem? = null,
    val genres: List<String> = emptyList(),
)

/**
 * The Medien tab: "Übersicht" (Weiterschauen, Zuletzt hinzugefügt, Serien), "Filme" and "Serien" as grids with the
 * chips, the Jellyfin server shown. The loading is the shared [MediaBrowse] and [MediaGrid]; playing goes through
 * [MediaPlayRequests] (the player is stage 4c).
 */
@HiltViewModel
class MediaHomeViewModel @Inject constructor(
    private val hub: MediaHub,
    private val requests: MediaPlayRequests,
    val pictures: MediaPictures,
    private val offline: MediaOffline,
    private val servers: MediaServerSources,
) : ViewModel() {

    /** "Offline verfügbar": the ready downloads, the newest first (also without a connection). */
    val offlineReady: StateFlow<List<MediaTitle>> = offline.items
        .map { list -> list.filter { it.ready }.sortedByDescending { it.addedAt }.map { it.titleNow() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Any download at all (the icon to the page "Offline"). */
    val anyOffline: StateFlow<Boolean> = offline.items.map { it.isNotEmpty() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)


    private val browse = MediaBrowse(hub) { publish() }
    private val movieGrid = MediaGrid(hub, movies = true)
    private val seriesGrid = MediaGrid(hub, movies = false)

    private val _ui = MutableStateFlow(MediaHomeUi())
    val ui: StateFlow<MediaHomeUi> = _ui.asStateFlow()

    private val _movies = MutableStateFlow(MediaGridUi())
    val movies: StateFlow<MediaGridUi> = _movies.asStateFlow()

    private val _series = MutableStateFlow(MediaGridUi())
    val series: StateFlow<MediaGridUi> = _series.asStateFlow()

    val link: StateFlow<MediaLink> = hub.link

    /** Away the VPN for media: „VPN wird verbunden …“, the consent to ask, off („Unterwegs nur über VPN“). */
    val gate: StateFlow<MediaGate> = hub.gate

    /** „Medien kommen jetzt vom Server“: the device's own sources, taken over or to be removed; empty: nothing to show. */
    val migration: StateFlow<List<MediaMigration>> = servers.state.map { it.migration }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), servers.state.value.migration)

    /** „Verstanden“ on the sheet. */
    fun acknowledgeMigration() = servers.acknowledgeMigration()

    /** Android's VPN consent was asked: given → the VPN starts and the page loads; refused → it stays blocked. */
    fun consent(granted: Boolean) {
        viewModelScope.launch {
            hub.consent(granted)
            if (granted) refresh()
        }
    }

    /**
     * „Neu anmelden“: the Jellyfin server shown is signed in again (the plugin's Quick Connect, the list of the server
     * first when it may be old), waited for with „Anmelden …“; then the overview and the grids opened load anew. Did it
     * not work, the card says why („Erneut versuchen“); a source set up by hand opens on Medien › Quellen.
     */
    fun repair() {
        if (_ui.value.signIn.running) return
        val source = hub.currentSource() ?: return refresh()
        _ui.update { it.copy(signIn = MediaSignInState(running = true, source = source)) }
        viewModelScope.launch {
            val end = try {
                // away the VPN first, when a source needs it
                hub.prepare(source)
                servers.signIn(source.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MediaSignIn.Problem(MediaPairEnd.FAILED)
            }
            when (end) {
                MediaSignIn.Done -> {
                    _ui.update { it.copy(signIn = MediaSignInState()) }
                    refresh()
                }
                MediaSignIn.Manual -> _ui.update { it.copy(signIn = MediaSignInState(), openSource = source.id) }
                is MediaSignIn.Problem -> _ui.update { it.copy(signIn = MediaSignInState(problem = end.code, source = source)) }
            }
        }
    }

    /** Medien › Quellen was opened for [MediaHomeUi.openSource]. */
    fun sourceOpened() = _ui.update { it.copy(openSource = null) }

    private var loadJob: Job? = null
    private val gridJobs = HashMap<Boolean, Job>()
    private val moreJobs = HashMap<Boolean, Job>()

    init {
        viewModelScope.launch {
            hub.store.sources.collect { list ->
                val before = _ui.value.source
                _ui.update { sourcesInto(it, list) }
                // signed in again in the background (after a revocation, by Medien › Quellen): the page loads anew
                val now = _ui.value
                if (before != null && before.token == null && now.source?.id == before.id && now.source?.token != null &&
                    now.problem == MediaProblem.SIGNED_OUT && !now.signIn.running
                ) {
                    refresh()
                }
            }
        }
    }

    private fun sourcesInto(u: MediaHomeUi, list: List<MediaSource>) = u.copy(
        sources = list,
        source = hub.currentSource(),
        servers = list.filter { it.kind == com.gatecontrol.android.media.MediaSourceKind.JELLYFIN },
        shares = hub.shares(),
    )

    private fun publish() {
        val rows = browse.rows()
        _ui.update { it.copy(resume = rows.resume, latest = rows.latest, series = rows.series, seriesTotal = rows.seriesTotal) }
    }

    /** The tab came to the front: home or away, then the rows (and the grids already opened) again. */
    fun refresh() {
        // the list of the server (opening Medien), at most every half minute
        servers.refreshSoon()
        val source = hub.currentSource()
        val shares = hub.shares()
        _ui.update { sourcesInto(it, hub.store.sources.value) }
        loadJob?.cancel()
        if (source == null && shares.isEmpty()) {
            browse.clear()
            _ui.update { MediaHomeUi(sources = it.sources) }
            _movies.value = MediaGridUi()
            _series.value = MediaGridUi()
            return
        }
        browse.keepOnly(source, shares)
        loadJob = viewModelScope.launch {
            _ui.update { it.copy(loading = true, problem = null) }
            // home or away; away the VPN is started when a source needs it (Netz-Regel)
            hub.prepare(source ?: shares.first())
            coroutineScope {
                if (source != null) launch { browse.loadJellyfin(source)?.let { p -> _ui.update { it.copy(problem = p) } } }
                for (s in shares) {
                    launch {
                        browse.loadShare(s) { bad -> _ui.update { it.copy(shareProblems = if (bad) it.shareProblems + s.id else it.shareProblems - s.id) } }
                    }
                }
            }
            // signed in now (or another problem): the reason of a failed sign-in no longer counts
            _ui.update { it.copy(loading = false, signIn = if (it.problem == MediaProblem.SIGNED_OUT) it.signIn else MediaSignInState()) }
            if (_movies.value.loaded) grid(true, _movies.value.order)
            if (_series.value.loaded) grid(false, _series.value.order)
        }
    }

    private fun state(movies: Boolean) = if (movies) _movies else _series

    private fun gridOf(movies: Boolean) = if (movies) movieGrid else seriesGrid

    /** "Filme" or "Serien" opened: its first page (once; again with [order] from the chips). */
    fun openGrid(movies: Boolean) {
        val s = state(movies).value
        if (!s.loaded && !s.loading) grid(movies, s.order)
    }

    fun setOrder(movies: Boolean, order: MediaOrder) {
        if (state(movies).value.order == order && state(movies).value.loaded) return
        grid(movies, order)
    }

    /** "Erneut versuchen" of a grid. */
    fun reloadGrid(movies: Boolean) = grid(movies, state(movies).value.order)

    private fun grid(movies: Boolean, order: MediaOrder) {
        val g = gridOf(movies)
        val st = state(movies)
        gridJobs[movies]?.cancel()
        moreJobs[movies]?.cancel()
        st.update { it.copy(order = order, loading = true, problem = null) }
        gridJobs[movies] = viewModelScope.launch {
            val problem = g.load(order)
            st.update { it.copy(items = g.items(), total = g.total, hasMore = g.hasMore, loading = false, loaded = true, problem = problem) }
            if (st.value.genres.isEmpty()) {
                val genres = g.genres()
                st.update { it.copy(genres = genres) }
            }
        }
    }

    /** Scrolled near the end of the grid: Jellyfin's next page. */
    fun more(movies: Boolean) {
        val g = gridOf(movies)
        if (!g.hasMore || moreJobs[movies]?.isActive == true || gridJobs[movies]?.isActive == true) return
        moreJobs[movies] = viewModelScope.launch {
            if (g.more()) state(movies).update { it.copy(items = g.items(), total = g.total, hasMore = g.hasMore) }
        }
    }

    /** "Jellyfin · DS918+ ▾": another server. */
    fun select(source: MediaSource) {
        hub.select(source.id)
        _movies.update { MediaGridUi(order = it.order) }
        _series.update { MediaGridUi(order = it.order) }
        refresh()
    }

    /** A card of "Weiterschauen" tapped: on from where it was left. */
    fun play(title: MediaTitle) {
        viewModelScope.launch { requests.play(title) }
    }
}
