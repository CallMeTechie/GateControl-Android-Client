package com.gatecontrol.android.tv.media

import android.graphics.Bitmap
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.media.MediaAddProblem
import com.gatecontrol.android.media.MediaAddress
import com.gatecontrol.android.media.MediaBrowse
import com.gatecontrol.android.media.MediaDetail
import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.MediaLink
import com.gatecontrol.android.media.MediaProblem
import com.gatecontrol.android.media.MediaQualityOffer
import com.gatecontrol.android.media.MediaQuickConnectEnd
import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaServerCheck
import com.gatecontrol.android.media.MediaSetup
import com.gatecontrol.android.media.MediaShareAdded
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.jellyfin.JellyfinAuth
import com.gatecontrol.android.media.jellyfin.JellyfinServerHint
import com.gatecontrol.android.media.jellyfin.JellyfinServerInfo
import com.gatecontrol.android.tv.drive.TvMediaSelection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

enum class TvAddStep { TYPE, ADDRESS, LOGIN }

/** Medien › Quellen › Quelle hinzufügen (mockup 10). */
data class TvAddUi(
    val step: TvAddStep = TvAddStep.TYPE,
    /** Jellyfin, or an SMB or WebDAV share (address, name and password on one step). */
    val kind: MediaSourceKind = MediaSourceKind.JELLYFIN,
    val searching: Boolean = false,
    val found: List<JellyfinServerHint> = emptyList(),
    val address: String = "",
    val checking: Boolean = false,
    val server: JellyfinServerInfo? = null,
    /** The base URL [server] answered at. */
    val serverAddress: String? = null,
    val problem: MediaAddProblem? = null,
    /** Quick Connect (code) or name and password. */
    val quickConnect: Boolean = true,
    val code: String? = null,
    val waiting: Boolean = false,
    val signingIn: Boolean = false,
    val done: Boolean = false,
    /** The new share was paired with this one (shown as "\\\\nas\\filme"): at home SMB, away WebDAV. */
    val pairedWith: MediaSource? = null,
)

/**
 * The page "Medien" of the rail: the rows of the current source, a title's details (sound, subtitles, quality), the
 * sources and adding one; and "Weiterschauen" of the start page. The player is [com.gatecontrol.android.tv.ui.drive
 * .TvMediaActivity] with [TvMediaVideos]. The loading and the steps of adding are the shared [MediaBrowse] and
 * [MediaSetup]; here only what the TV's pages show.
 */
@HiltViewModel
class TvMediaViewModel @Inject constructor(
    private val media: MediaHub,
    private val selection: TvMediaSelection,
) : ViewModel() {

    private val browse = MediaBrowse(media) { publish() }
    private val setup = MediaSetup(media)

    private val _ui = MutableStateFlow(TvMediaUi())
    val ui: StateFlow<TvMediaUi> = _ui.asStateFlow()

    /** The open detail pages, the top one shown (Zurück goes down). */
    private val _details = MutableStateFlow<List<TvDetailUi>>(emptyList())
    val details: StateFlow<List<TvDetailUi>> = _details.asStateFlow()

    private val _add = MutableStateFlow<TvAddUi?>(null)
    val add: StateFlow<TvAddUi?> = _add.asStateFlow()

    val link: StateFlow<MediaLink> = media.link

    /** Posters and backdrops loaded so far (key: [pictureKey]). */
    private val _pictures = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val pictures: StateFlow<Map<String, Bitmap>> = _pictures.asStateFlow()

    private val cache = object : LruCache<String, Bitmap>(PICTURE_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val loadingPictures = HashSet<String>()

    private var loadJob: Job? = null
    private var quickJob: Job? = null

    init {
        viewModelScope.launch {
            media.store.sources.collect { list ->
                _ui.update { it.copy(sources = list, source = media.currentSource(), shares = media.shares()) }
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
            media.check(source ?: shares.first())
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
            _ui.update { it.copy(loading = false) }
        }
    }

    /** Back from the player: "Weiterschauen" and the open detail page show where it is now. */
    fun onResumed() {
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
                    media.picture(url, lib.headers(), MediaAddress.host(source.address))
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

    fun remove(source: MediaSource) {
        media.store.remove(source.id)
        media.forget(source.id)
        refresh()
    }

    fun setAlwaysOriginal(source: MediaSource, on: Boolean) = media.store.setAlwaysOriginal(source.id, on)

    /** "Quelle hinzufügen"; for a source signed out by its server ("Neu anmelden") straight at its address. */
    fun startAdd() {
        val signedOut = media.currentSource()?.takeIf { it.token == null }
        if (signedOut == null) {
            _add.value = TvAddUi()
            return
        }
        _add.value = TvAddUi(step = TvAddStep.ADDRESS, address = signedOut.address)
        check()
    }

    fun cancelAdd() {
        quickJob?.cancel()
        _add.value = null
    }

    /** Step 1: Jellyfin; the search in the home network starts. */
    fun chooseJellyfin() {
        _add.update { it?.copy(step = TvAddStep.ADDRESS, kind = MediaSourceKind.JELLYFIN) }
        search()
    }

    /** Step 1: an SMB or WebDAV share; address, name and password follow on one step. */
    fun chooseShare(kind: MediaSourceKind) {
        if (!kind.share) return chooseJellyfin()
        quickJob?.cancel()
        _add.update { it?.copy(step = TvAddStep.ADDRESS, kind = kind, address = "", problem = null, server = null, serverAddress = null) }
    }

    /**
     * "Verbinden" of a share: the address as typed (`\\nas\filme`, `smb://…`, `https://…:5006/filme`), the root
     * read once with the name and password, then kept (encrypted). The same folder as SMB and as WebDAV is paired.
     */
    fun connectShare(name: String, password: String) {
        val a = _add.value ?: return
        if (MediaAddress.normalizeShare(a.kind, a.address) == null) {
            _add.update { it?.copy(problem = MediaAddProblem.BAD_ADDRESS) }
            return
        }
        _add.update { it?.copy(signingIn = true, problem = null) }
        viewModelScope.launch {
            when (val r = setup.connectShare(a.kind, a.address, name, password)) {
                is MediaShareAdded.Problem -> _add.update { it?.copy(signingIn = false, problem = r.problem) }
                is MediaShareAdded.Saved -> {
                    _add.update { it?.copy(signingIn = false, done = true, pairedWith = r.partner) }
                    refresh()
                }
            }
        }
    }

    fun search() {
        _add.update { it?.copy(searching = true) }
        viewModelScope.launch {
            val found = setup.search()
            _add.update { a ->
                a?.copy(searching = false, found = found, address = a.address.ifEmpty { found.firstOrNull()?.address.orEmpty() })
            }
            if (found.size == 1 && _add.value?.server == null) check()
        }
    }

    fun setAddress(text: String) = _add.update { it?.copy(address = text, server = null, serverAddress = null, problem = null) }

    fun pick(hint: JellyfinServerHint) {
        setAddress(hint.address)
        check()
    }

    /** Step 2: is a Jellyfin server at the address? */
    fun check() {
        val a = _add.value ?: return
        if (MediaAddress.normalize(a.address) == null) {
            _add.update { it?.copy(problem = MediaAddProblem.BAD_ADDRESS) }
            return
        }
        _add.update { it?.copy(checking = true, problem = null) }
        viewModelScope.launch {
            when (val r = setup.check(a.address)) {
                is MediaServerCheck.Found -> _add.update { it?.copy(checking = false, server = r.server, serverAddress = r.address, problem = null) }
                is MediaServerCheck.Problem -> _add.update {
                    if (r.problem == MediaAddProblem.OLD_SERVER) {
                        it?.copy(checking = false, server = null, serverAddress = null, problem = r.problem)
                    } else {
                        it?.copy(checking = false, problem = r.problem)
                    }
                }
            }
        }
    }

    /** On to step 3 ("Weiter" after the address was found). */
    fun toLogin() {
        if (_add.value?.server == null) return
        _add.update { it?.copy(step = TvAddStep.LOGIN, problem = null) }
        if (_add.value?.quickConnect == true) startQuickConnect()
    }

    fun chooseQuickConnect(on: Boolean) {
        quickJob?.cancel()
        _add.update { it?.copy(quickConnect = on, code = null, waiting = false, problem = null) }
        if (on) startQuickConnect()
    }

    /** Quick Connect: the code on the TV, then waiting until it is confirmed in Jellyfin (on the phone or PC). */
    fun startQuickConnect() {
        val base = _add.value?.serverAddress ?: return
        quickJob?.cancel()
        quickJob = viewModelScope.launch {
            val end = setup.quickConnect(
                base,
                onCode = { code -> _add.update { it?.copy(code = code, waiting = code != null) } },
                onSigningIn = { _add.update { it?.copy(signingIn = true, waiting = false) } },
            )
            when (end) {
                is MediaQuickConnectEnd.SignedIn -> saved(end.auth)
                is MediaQuickConnectEnd.Problem -> _add.update {
                    if (end.problem == MediaAddProblem.NO_QUICK_CONNECT) {
                        it?.copy(problem = end.problem, quickConnect = false)
                    } else {
                        it?.copy(waiting = false, code = null, problem = end.problem)
                    }
                }
            }
        }
    }

    /** Name and password: sent once, never kept. */
    fun signIn(name: String, password: String) {
        val base = _add.value?.serverAddress ?: return
        if (name.isBlank()) return
        _add.update { it?.copy(signingIn = true, problem = null) }
        viewModelScope.launch { saved(setup.signIn(base, name, password)) }
    }

    private fun saved(r: MediaResult<JellyfinAuth>) {
        val a = _add.value ?: return
        val auth = r.valueOrNull()
        if (auth == null) {
            _add.update { it?.copy(signingIn = false, problem = setup.problemOf(r)) }
            return
        }
        setup.saveJellyfin(auth, a.server, a.serverAddress ?: return)
        _add.update { it?.copy(signingIn = false, done = true) }
        refresh()
    }

    private companion object {
        const val PICTURE_CACHE_BYTES = 24 * 1024 * 1024
        const val MAX_SHOWN = 160
    }
}
