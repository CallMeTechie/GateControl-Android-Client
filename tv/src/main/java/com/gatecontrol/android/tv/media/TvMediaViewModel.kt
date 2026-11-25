package com.gatecontrol.android.tv.media

import android.graphics.Bitmap
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.media.MediaBrowse
import com.gatecontrol.android.media.MediaDetail
import com.gatecontrol.android.media.MediaGate
import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.server.MediaPairEnd
import com.gatecontrol.android.media.server.MediaServerSources
import com.gatecontrol.android.media.server.MediaSignIn
import com.gatecontrol.android.media.server.MediaServerState
import com.gatecontrol.android.media.server.MediaSourceStatus
import com.gatecontrol.android.media.server.MediaSourcesLogic
import com.gatecontrol.android.media.MediaLink
import com.gatecontrol.android.media.MediaProblem
import com.gatecontrol.android.media.MediaQualityOffer
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.tv.drive.TvMediaSelection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The rows of Medien (mockup 7): the Jellyfin server shown ([source]) and the shares ([shares]) in one; a title in
 * both shows once, Jellyfin's.
 */
data class TvMediaUi(
    val sources: List<MediaSource> = emptyList(),
    val source: MediaSource? = null,
    val shares: List<MediaSource> = emptyList(),
    /** Shares that could not be read just now (by id). */
    val shareProblems: Set<String> = emptySet(),
    val loading: Boolean = false,
    val problem: MediaProblem? = null,
    val resume: List<MediaTitle> = emptyList(),
    val latest: List<MediaTitle> = emptyList(),
    val movies: List<MediaTitle> = emptyList(),
    val moviesTotal: Int = 0,
    val moviesNext: Int? = null,
    val series: List<MediaTitle> = emptyList(),
    val seriesTotal: Int = 0,
    val seriesNext: Int? = null,
    /** „Neu anmelden“ is running („Anmelden …“). */
    val signingIn: Boolean = false,
    /** Why „Neu anmelden“ did not work (a code as in Medien › Quellen); null: it did or was not tried. */
    val signInProblem: String? = null,
    /** A source set up by hand: Medien › Quellen opens (once; [TvMediaViewModel.sourcesOpened]). */
    val openSources: Boolean = false,
) {
    val any: Boolean get() = source != null || shares.isNotEmpty()
}

/** A title's detail page (mockup 8); a series with its seasons and episodes. */
data class TvDetailUi(
    val title: MediaTitle,
    val detail: MediaDetail? = null,
    val loading: Boolean = true,
    val problem: MediaProblem? = null,
    val seasons: List<MediaTitle> = emptyList(),
    val seasonId: String? = null,
    val episodes: List<MediaTitle> = emptyList(),
    val audio: Int? = null,
    val subtitle: Int? = null,
    /** "Original (direkt)" chosen; false: "Angepasst (NAS rechnet um)". */
    val original: Boolean = true,
    val offer: MediaQualityOffer? = null,
)

/**
 * The page "Medien" of the rail: the rows of the current source, a title's details (sound, subtitles, quality), the
 * sources the admin set up in the server (a display with their status); and "Weiterschauen" of the start page. The
 * player is [com.gatecontrol.android.tv.ui.drive.TvMediaActivity] with [TvMediaVideos]. The loading is the shared
 * [MediaBrowse], the sources come from [MediaServerSources] (plugin „Medien“); nothing is set up on the TV.
 */
@HiltViewModel
class TvMediaViewModel @Inject constructor(
    private val media: MediaHub,
    private val selection: TvMediaSelection,
    private val servers: MediaServerSources,
) : ViewModel() {

    private val browse = MediaBrowse(media) { publish() }

    /** What the server's plugin says (shown, the list's time, the takeover sheet). */
    val server: StateFlow<MediaServerState> = servers.state

    /** Away the VPN for media: „VPN wird verbunden …“, the consent, off. */
    val gate: StateFlow<MediaGate> = media.gate

    /** Medien › Quellen: each source with its status. */
    val rows: StateFlow<List<MediaSourceStatus>> = combine(media.store.sources, servers.state, media.link) { list, state, _ ->
        MediaSourcesLogic.rows(list, state, media::route)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ui = MutableStateFlow(TvMediaUi())
    val ui: StateFlow<TvMediaUi> = _ui.asStateFlow()

    /** The open detail pages, the top one shown (Zurück goes down). */
    private val _details = MutableStateFlow<List<TvDetailUi>>(emptyList())
    val details: StateFlow<List<TvDetailUi>> = _details.asStateFlow()

    val link: StateFlow<MediaLink> = media.link

    /** Posters and backdrops loaded so far (key: [pictureKey]). */
    private val _pictures = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val pictures: StateFlow<Map<String, Bitmap>> = _pictures.asStateFlow()

    private val cache = object : LruCache<String, Bitmap>(PICTURE_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val loadingPictures = HashSet<String>()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            media.store.sources.collect { list ->
                val before = _ui.value.source
                _ui.update { it.copy(sources = list, source = media.currentSource(), shares = media.shares()) }
                // signed in again in the background (after a revocation, by „Neu laden“): the page loads anew
                val now = _ui.value
                if (before != null && before.token == null && now.source?.id == before.id && now.source?.token != null &&
                    now.problem == MediaProblem.SIGNED_OUT && !now.signingIn
                ) {
                    refresh()
                }
            }
        }
    }

    /** The rows of all sources in one (mockup 7): a title in Jellyfin and on a share once, Jellyfin's. */
    private fun publish() {
        val rows = browse.rows()
        _ui.update {
            it.copy(
                resume = rows.resume,
                latest = rows.latest,
                movies = rows.movies,
                moviesTotal = rows.moviesTotal,
                moviesNext = rows.moviesNext,
                series = rows.series,
                seriesTotal = rows.seriesTotal,
                seriesNext = rows.seriesNext,
            )
        }
    }

    /** The page (or the start page) came to the front: home or away, then the rows of the server and the shares. */
    fun refresh() {
        // the list of the server (opening Medien), at most every half minute
        servers.refreshSoon()
        val source = media.currentSource()
        val shares = media.shares()
        _ui.update { it.copy(source = source, shares = shares, sources = media.store.sources.value) }
        loadJob?.cancel()
        if (source == null && shares.isEmpty()) {
            browse.clear()
            _ui.update { TvMediaUi(sources = it.sources) }
            return
        }
        browse.keepOnly(source, shares)
        loadJob = viewModelScope.launch {
            _ui.update { it.copy(loading = true, problem = null) }
            // home or away; away the VPN is started when a source needs it (Netz-Regel)
            media.prepare(source ?: shares.first())
            coroutineScope {
                if (source != null) {
                    launch { browse.loadJellyfin(source)?.let { p -> _ui.update { it.copy(problem = p) } } }
                }
                for (s in shares) {
                    launch {
                        browse.loadShare(s) { bad -> _ui.update { it.copy(shareProblems = if (bad) it.shareProblems + s.id else it.shareProblems - s.id) } }
                    }
                }
            }
            // signed in now (or another problem): the reason of a failed sign-in no longer counts
            _ui.update { it.copy(loading = false, signInProblem = it.signInProblem.takeIf { _ -> it.problem == MediaProblem.SIGNED_OUT }) }
        }
    }

    /** Back from the player: "Weiterschauen" and the open detail page show where it is now. */
    fun onResumed() {
        servers.refreshIfStale()
        if (media.store.sources.value.isEmpty()) return
        refreshResume()
        _details.value.lastOrNull()?.let { load(it) }
    }

    /** Only "Weiterschauen" (the start page): of the Jellyfin server and of the shares. */
    fun refreshResume() {
        val source = media.currentSource()?.takeIf { it.token != null }
        val shares = media.shares()
        if (source == null && shares.isEmpty()) {
            browse.clear()
            _ui.update { it.copy(resume = emptyList()) }
            return
        }
        viewModelScope.launch { browse.loadResume(source, shares) }
    }

    private var moreJob: Job? = null

    /** The remote came near the end of "Filme" ([movies]) or "Serien": Jellyfin's next page (a share has all). */
    fun more(movies: Boolean) {
        if (media.currentSource() == null) return
        if (!browse.hasMore(movies) || moreJob?.isActive == true) return
        moreJob = viewModelScope.launch { browse.more(movies) }
    }

    fun pictureKey(title: MediaTitle, backdrop: Boolean): String = "${title.sourceId}:${if (backdrop) title.backdropId else title.posterId}:${if (backdrop) "b" else "p"}"

    /** A card or the detail page wants [title]'s poster (or backdrop); away smaller. */
    fun wantPicture(title: MediaTitle, backdrop: Boolean = false) {
        val source = media.store.source(title.sourceId) ?: return
        val key = pictureKey(title, backdrop)
        if (_pictures.value.containsKey(key)) return
        cache.get(key)?.let { b ->
            _pictures.update { it + (key to b) }
            return
        }
        val lib = media.library(source)
        val away = media.link.value.atHome != true
        val height = if (backdrop) (if (away) 360 else 540) else (if (away) 240 else 360)
        val url = lib.imageUrl(title, backdrop, height)
        // a share's picture beside the film has no URL: its bytes
        if (url == null && (if (backdrop) title.backdropId else title.posterId) == null) return
        synchronized(loadingPictures) { if (!loadingPictures.add(key)) return }
        viewModelScope.launch {
            try {
                val b = if (url != null) {
                    media.picture(url, lib.headers(), source)
                } else {
                    lib.imageBytes(title, backdrop)?.let { media.decode(it, height) }
                } ?: return@launch
                cache.put(key, b)
                _pictures.update { m -> (m + (key to b)).let { if (it.size > MAX_SHOWN) it.entries.drop(it.size - MAX_SHOWN).associate { e -> e.key to e.value } else it } }
            } finally {
                synchronized(loadingPictures) { loadingPictures.remove(key) }
            }
        }
    }

    // --- the detail page ---

    /** OK (or ☰) on a poster: its details; a series with its seasons. */
    fun open(title: MediaTitle) {
        val page = TvDetailUi(title)
        _details.update { it + page }
        load(page)
    }

    /** Zurück on a detail page; false when none was open. */
    fun back(): Boolean {
        if (_details.value.isEmpty()) return false
        _details.update { it.dropLast(1) }
        return true
    }

    fun closeDetails() {
        _details.value = emptyList()
    }

    private fun replaceTop(id: String, f: (TvDetailUi) -> TvDetailUi) {
        _details.update { list -> list.map { if (it.title.id == id) f(it) else it } }
    }

    private fun load(page: TvDetailUi) {
        media.store.source(page.title.sourceId) ?: return
        val id = page.title.id
        viewModelScope.launch {
            val (loaded, problem) = browse.detail(page.title)
            if (loaded == null) {
                replaceTop(id) { it.copy(loading = false, problem = problem) }
                return@launch
            }
            val detail = loaded.detail
            replaceTop(id) {
                it.copy(
                    title = detail.title,
                    detail = detail,
                    loading = false,
                    audio = loaded.audio,
                    subtitle = loaded.subtitle,
                    original = !loaded.offer.suggestAdapted,
                    offer = loaded.offer,
                )
            }
            if (detail.title.kind == MediaTitle.Kind.SERIES) {
                val seasons = browse.seasons(page.title)
                replaceTop(id) { it.copy(seasons = seasons) }
                val first = seasons.firstOrNull { (it.unplayed ?: 0) > 0 } ?: seasons.firstOrNull()
                first?.let { season(id, it.id) }
            }
        }
    }

    /** A season of the open series. */
    fun season(seriesId: String, seasonId: String) {
        val top = _details.value.lastOrNull { it.title.id == seriesId } ?: return
        media.store.source(top.title.sourceId) ?: return
        replaceTop(seriesId) { it.copy(seasonId = seasonId) }
        viewModelScope.launch {
            val episodes = browse.episodes(top.title, seasonId)
            replaceTop(seriesId) { if (it.seasonId == seasonId) it.copy(episodes = episodes) else it }
        }
    }

    fun chooseAudio(index: Int) = top { it.copy(audio = index) }

    fun chooseSubtitle(index: Int?) = top { it.copy(subtitle = index) }

    fun chooseOriginal(original: Boolean) = top { it.copy(original = original) }

    private fun top(f: (TvDetailUi) -> TvDetailUi) {
        val t = _details.value.lastOrNull() ?: return
        replaceTop(t.title.id, f)
        val now = _details.value.lastOrNull() ?: return
        val seriesId = now.title.seriesId
        val version = now.detail?.version
        if (seriesId != null && version != null && (now.audio != t.audio || now.subtitle != t.subtitle)) {
            browse.rememberTracks(seriesId, version, now.audio, now.subtitle)
        }
    }

    /** "Als gesehen markieren" (and back). */
    fun togglePlayed() {
        val t = _details.value.lastOrNull() ?: return
        media.store.source(t.title.sourceId) ?: return
        val played = !t.title.played
        viewModelScope.launch {
            if (browse.setPlayed(t.title, played)) {
                replaceTop(t.title.id) { it.copy(title = it.title.copy(played = played, positionMs = if (played) 0 else it.title.positionMs)) }
                refreshResume()
            }
        }
    }

    /**
     * Plays [title] (an episode with the rest of its series after it), [fromStart] or where it was left; with the
     * open detail page's sound, subtitle and quality. [then] opens the player once the queue is ready.
     */
    fun play(title: MediaTitle, fromStart: Boolean = false, then: () -> Unit) {
        media.store.source(title.sourceId) ?: return
        val page = _details.value.lastOrNull()?.takeIf { it.title.id == title.id }
        viewModelScope.launch {
            val q = browse.queue(title, fromStart)?.copy(
                audioIndex = page?.audio,
                subtitleIndex = page?.subtitle,
                original = page?.offer?.takeIf { it.adaptedBps != null }?.let { page.original },
            ) ?: return@launch
            selection.setMedia(q)
            then()
        }
    }

    // --- sources ---

    fun select(source: MediaSource) {
        media.select(source.id)
        closeDetails()
        refresh()
    }

    fun setAlwaysOriginal(source: MediaSource, on: Boolean) = media.store.setAlwaysOriginal(source.id, on)

    /** „Neu laden“ of Medien › Quellen: the server's list, then the sign-in where it is missing. */
    fun reload() {
        viewModelScope.launch {
            servers.refresh(force = true)
            servers.repair()
        }
    }

    /**
     * „Neu anmelden“: the TV is signed in again at the Jellyfin server shown (the plugin's Quick Connect, no code on the
     * screen; the server's list first when it may be old), waited for with „Anmelden …“; then the rows load anew. Did
     * it not work, the page says why („Erneut versuchen“); a source set up by hand opens Medien › Quellen.
     */
    fun repair() {
        if (_ui.value.signingIn) return
        val source = media.currentSource() ?: return refresh()
        _ui.update { it.copy(signingIn = true, signInProblem = null) }
        viewModelScope.launch {
            val end = try {
                media.prepare(source)
                servers.signIn(source.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MediaSignIn.Problem(MediaPairEnd.FAILED)
            }
            when (end) {
                MediaSignIn.Done -> {
                    _ui.update { it.copy(signingIn = false, signInProblem = null) }
                    refresh()
                }
                MediaSignIn.Manual -> _ui.update { it.copy(signingIn = false, openSources = true) }
                is MediaSignIn.Problem -> _ui.update { it.copy(signingIn = false, signInProblem = end.code) }
            }
        }
    }

    /** Medien › Quellen was opened for [TvMediaUi.openSources]. */
    fun sourcesOpened() = _ui.update { it.copy(openSources = false) }

    /** Android's VPN consent was asked: given → the VPN starts and the page loads. */
    fun consent(granted: Boolean) {
        viewModelScope.launch {
            media.consent(granted)
            if (granted) refresh()
        }
    }

    /** „Verstanden“ of „Medien kommen jetzt vom Server“. */
    fun acknowledgeMigration() = servers.acknowledgeMigration()

    private companion object {
        const val PICTURE_CACHE_BYTES = 24 * 1024 * 1024
        const val MAX_SHOWN = 160
    }
}
