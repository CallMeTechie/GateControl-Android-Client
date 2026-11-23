package com.gatecontrol.android.tv.media

import android.graphics.Bitmap
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.media.MediaAddress
import com.gatecontrol.android.media.MediaDetail
import com.gatecontrol.android.media.MediaLibrary
import com.gatecontrol.android.media.MediaQuality
import com.gatecontrol.android.media.MediaQualityOffer
import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.MediaTracks
import com.gatecontrol.android.media.jellyfin.JellyfinQuickConnect
import com.gatecontrol.android.media.jellyfin.JellyfinServerHint
import com.gatecontrol.android.media.jellyfin.JellyfinServerInfo
import com.gatecontrol.android.media.jellyfin.JellyfinSignIn
import com.gatecontrol.android.tv.drive.TvMediaSelection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Why the media page shows nothing. */
enum class TvMediaProblem { OFFLINE, SIGNED_OUT, FAILED }

/** The rows of Medien (mockup 7). */
data class TvMediaUi(
    val sources: List<MediaSource> = emptyList(),
    val source: MediaSource? = null,
    val loading: Boolean = false,
    val problem: TvMediaProblem? = null,
    val resume: List<MediaTitle> = emptyList(),
    val latest: List<MediaTitle> = emptyList(),
    val movies: List<MediaTitle> = emptyList(),
    val moviesTotal: Int = 0,
    val moviesNext: Int? = null,
    val series: List<MediaTitle> = emptyList(),
    val seriesTotal: Int = 0,
    val seriesNext: Int? = null,
)

/** A title's detail page (mockup 8); a series with its seasons and episodes. */
data class TvDetailUi(
    val title: MediaTitle,
    val detail: MediaDetail? = null,
    val loading: Boolean = true,
    val problem: TvMediaProblem? = null,
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

enum class TvAddProblem { BAD_ADDRESS, NOT_FOUND, OLD_SERVER, NO_QUICK_CONNECT, WRONG_LOGIN, OFFLINE, FAILED }

/** Medien › Quellen › Quelle hinzufügen (mockup 10). */
data class TvAddUi(
    val step: TvAddStep = TvAddStep.TYPE,
    val searching: Boolean = false,
    val found: List<JellyfinServerHint> = emptyList(),
    val address: String = "",
    val checking: Boolean = false,
    val server: JellyfinServerInfo? = null,
    /** The base URL [server] answered at. */
    val serverAddress: String? = null,
    val problem: TvAddProblem? = null,
    /** Quick Connect (code) or name and password. */
    val quickConnect: Boolean = true,
    val code: String? = null,
    val waiting: Boolean = false,
    val signingIn: Boolean = false,
    val done: Boolean = false,
)

/**
 * The page "Medien" of the rail: the rows of the current source, a title's details (sound, subtitles, quality), the
 * sources and adding one; and "Weiterschauen" of the start page. The player is [com.gatecontrol.android.tv.ui.drive
 * .TvMediaActivity] with [TvMediaVideos].
 */
@HiltViewModel
class TvMediaViewModel @Inject constructor(
    private val media: TvMedia,
    private val selection: TvMediaSelection,
) : ViewModel() {

    private val _ui = MutableStateFlow(TvMediaUi())
    val ui: StateFlow<TvMediaUi> = _ui.asStateFlow()

    /** The open detail pages, the top one shown (Zurück goes down). */
    private val _details = MutableStateFlow<List<TvDetailUi>>(emptyList())
    val details: StateFlow<List<TvDetailUi>> = _details.asStateFlow()

    private val _add = MutableStateFlow<TvAddUi?>(null)
    val add: StateFlow<TvAddUi?> = _add.asStateFlow()

    val link: StateFlow<TvMediaLink> = media.link

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
                _ui.update { it.copy(sources = list, source = media.currentSource()) }
            }
        }
    }

    private fun library(): MediaLibrary? = media.currentSource()?.let(media::library)

    /** The page (or the start page) came to the front: home or away, then the rows. */
    fun refresh() {
        val source = media.currentSource()
        _ui.update { it.copy(source = source, sources = media.store.sources.value) }
        if (source == null) {
            _ui.update { TvMediaUi(sources = it.sources) }
            return
        }
        loadJob?.cancel()
        if (source.kind == MediaSourceKind.JELLYFIN && source.token == null) {
            // signed out by the server earlier: sign in again
            _ui.update { it.copy(loading = false, problem = TvMediaProblem.SIGNED_OUT, resume = emptyList()) }
            return
        }
        loadJob = viewModelScope.launch {
            _ui.update { it.copy(loading = true, problem = null) }
            media.check(source)
            val lib = media.library(source)
            val resume = lib.resume(RESUME)
            val problem = problemOf(resume, source)
            if (problem != null) {
                _ui.update { it.copy(loading = false, problem = problem) }
                return@launch
            }
            val r = resume.valueOrNull().orEmpty()
            _ui.update { it.copy(resume = r) }
            val latest = lib.latest(LATEST).valueOrNull().orEmpty()
            _ui.update { it.copy(latest = TvMediaLogic.latestWithout(latest, r)) }
            val movies = lib.movies(0, TvMediaLogic.PAGE).valueOrNull()
            val series = lib.series(0, TvMediaLogic.PAGE).valueOrNull()
            _ui.update {
                it.copy(
                    loading = false,
                    movies = movies?.items.orEmpty(),
                    moviesTotal = movies?.total ?: 0,
                    moviesNext = movies?.next,
                    series = series?.items.orEmpty(),
                    seriesTotal = series?.total ?: 0,
                    seriesNext = series?.next,
                )
            }
        }
    }

    /** Back from the player: "Weiterschauen" and the open detail page show where it is now. */
    fun onResumed() {
        if (media.store.sources.value.isEmpty()) return
        refreshResume()
        _details.value.lastOrNull()?.let { load(it) }
    }

    /** Only "Weiterschauen" (the start page). */
    fun refreshResume() {
        val source = media.currentSource()?.takeIf { it.token != null } ?: run {
            _ui.update { it.copy(resume = emptyList()) }
            return
        }
        viewModelScope.launch {
            media.check(source)
            media.library(source).resume(RESUME).valueOrNull()?.let { r -> _ui.update { it.copy(resume = r) } }
        }
    }

    private fun problemOf(r: MediaResult<*>, source: MediaSource): TvMediaProblem? = when (r) {
        is MediaResult.Ok -> null
        MediaResult.Offline -> TvMediaProblem.OFFLINE
        MediaResult.Unauthorized -> {
            media.store.dropToken(source.id)
            TvMediaProblem.SIGNED_OUT
        }
        else -> TvMediaProblem.FAILED
    }

    private var moreJob: Job? = null

    /** The remote came near the end of "Filme" ([movies]) or "Serien": the next page. */
    fun more(movies: Boolean) {
        val lib = library() ?: return
        val next = if (movies) _ui.value.moviesNext else _ui.value.seriesNext
        if (next == null || moreJob?.isActive == true) return
        moreJob = viewModelScope.launch {
            val page = (if (movies) lib.movies(next, TvMediaLogic.PAGE) else lib.series(next, TvMediaLogic.PAGE)).valueOrNull() ?: return@launch
            _ui.update {
                if (movies) {
                    it.copy(movies = (it.movies + page.items).distinctBy { t -> t.id }, moviesNext = page.next, moviesTotal = page.total)
                } else {
                    it.copy(series = (it.series + page.items).distinctBy { t -> t.id }, seriesNext = page.next, seriesTotal = page.total)
                }
            }
        }
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
        val url = lib.imageUrl(title, backdrop, height) ?: return
        synchronized(loadingPictures) { if (!loadingPictures.add(key)) return }
        viewModelScope.launch {
            try {
                val b = media.picture(url, lib.headers(), MediaAddress.host(source.address)) ?: return@launch
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
        val source = media.store.source(page.title.sourceId) ?: return
        val lib = media.library(source)
        val id = page.title.id
        viewModelScope.launch {
            val d = lib.detail(id)
            val detail = d.valueOrNull()
            if (detail == null) {
                replaceTop(id) { it.copy(loading = false, problem = problemOf(d, source)) }
                return@launch
            }
            val version = detail.version
            val choice = (detail.title.seriesId)?.let { media.store.trackChoice(it) }
            val link = media.link.value
            val offer = MediaQuality.offer(
                atHome = link.atHome == true,
                measuredBps = link.bps,
                originalBps = version?.bitrate,
                alwaysOriginal = source.alwaysOriginal,
                canConvert = source.kind.canConvert,
            )
            replaceTop(id) {
                it.copy(
                    title = detail.title,
                    detail = detail,
                    loading = false,
                    audio = version?.let { v -> MediaTracks.pickAudio(v, choice) },
                    subtitle = version?.let { v -> MediaTracks.pickSubtitle(v, choice) ?: v.defaultSubtitle },
                    original = !offer.suggestAdapted,
                    offer = offer,
                )
            }
            if (detail.title.kind == MediaTitle.Kind.SERIES) {
                val seasons = lib.seasons(id).valueOrNull().orEmpty()
                replaceTop(id) { it.copy(seasons = seasons) }
                val first = seasons.firstOrNull { (it.unplayed ?: 0) > 0 } ?: seasons.firstOrNull()
                first?.let { season(id, it.id) }
            }
        }
    }

    /** A season of the open series. */
    fun season(seriesId: String, seasonId: String) {
        val top = _details.value.lastOrNull { it.title.id == seriesId } ?: return
        val source = media.store.source(top.title.sourceId) ?: return
        replaceTop(seriesId) { it.copy(seasonId = seasonId) }
        viewModelScope.launch {
            val episodes = media.library(source).episodes(seriesId, seasonId).valueOrNull().orEmpty()
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
            media.store.setTrackChoice(seriesId, MediaTracks.choiceOf(version, now.audio, now.subtitle))
        }
    }

    /** "Als gesehen markieren" (and back). */
    fun togglePlayed() {
        val t = _details.value.lastOrNull() ?: return
        val source = media.store.source(t.title.sourceId) ?: return
        val played = !t.title.played
        viewModelScope.launch {
            if (media.library(source).setPlayed(t.title.id, played) is MediaResult.Ok) {
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
        val source = media.store.source(title.sourceId) ?: return
        val page = _details.value.lastOrNull()?.takeIf { it.title.id == title.id }
        viewModelScope.launch {
            val episodes = if (title.kind == MediaTitle.Kind.EPISODE && title.seriesId != null) {
                media.library(source).episodes(title.seriesId!!).valueOrNull().orEmpty()
            } else {
                emptyList()
            }
            val start = if (fromStart) 0L else null
            val q = TvMediaLogic.queue(source.id, title, episodes, start).copy(
                audioIndex = page?.audio,
                subtitleIndex = page?.subtitle,
                original = page?.offer?.takeIf { it.adaptedBps != null }?.let { page.original },
            )
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

    /** Step 1: Jellyfin (SMB and WebDAV come later); the search in the home network starts. */
    fun chooseJellyfin() {
        _add.update { it?.copy(step = TvAddStep.ADDRESS) }
        search()
    }

    fun search() {
        _add.update { it?.copy(searching = true) }
        viewModelScope.launch {
            val found = runCatching { media.discover() }.getOrDefault(emptyList())
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
        val base = MediaAddress.normalize(a.address) ?: run {
            _add.update { it?.copy(problem = TvAddProblem.BAD_ADDRESS) }
            return
        }
        _add.update { it?.copy(checking = true, problem = null) }
        viewModelScope.launch {
            media.check(null)
            when (val r = media.signIn(base).server()) {
                is MediaResult.Ok -> {
                    val ok = r.value.supported
                    _add.update {
                        it?.copy(checking = false, server = r.value.takeIf { ok }, serverAddress = base.takeIf { ok }, problem = if (ok) null else TvAddProblem.OLD_SERVER)
                    }
                }
                MediaResult.Offline -> _add.update { it?.copy(checking = false, problem = TvAddProblem.OFFLINE) }
                else -> _add.update { it?.copy(checking = false, problem = TvAddProblem.NOT_FOUND) }
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
            val signIn = media.signIn(base)
            if (!signIn.quickConnectEnabled()) {
                _add.update { it?.copy(problem = TvAddProblem.NO_QUICK_CONNECT, quickConnect = false) }
                return@launch
            }
            val q = signIn.quickConnect().valueOrNull() ?: run {
                _add.update { it?.copy(problem = TvAddProblem.FAILED) }
                return@launch
            }
            _add.update { it?.copy(code = q.code, waiting = true) }
            val until = System.currentTimeMillis() + QUICK_CONNECT_MS
            while (System.currentTimeMillis() < until) {
                delay(QUICK_POLL_MS)
                when (val c = signIn.confirmed(q)) {
                    is MediaResult.Ok -> if (c.value) {
                        finish(signIn, q)
                        return@launch
                    }
                    MediaResult.Offline -> Unit
                    else -> {
                        // the code ran out on the server: a new one
                        _add.update { it?.copy(code = null, waiting = false) }
                        startQuickConnect()
                        return@launch
                    }
                }
            }
            _add.update { it?.copy(waiting = false, code = null, problem = TvAddProblem.FAILED) }
        }
    }

    private suspend fun finish(signIn: JellyfinSignIn, q: JellyfinQuickConnect) {
        _add.update { it?.copy(signingIn = true, waiting = false) }
        saved(signIn.signIn(q))
    }

    /** Name and password: sent once, never kept. */
    fun signIn(name: String, password: String) {
        val base = _add.value?.serverAddress ?: return
        if (name.isBlank()) return
        _add.update { it?.copy(signingIn = true, problem = null) }
        viewModelScope.launch { saved(media.signIn(base).signIn(name.trim(), password)) }
    }

    private fun saved(r: MediaResult<com.gatecontrol.android.media.jellyfin.JellyfinAuth>) {
        val a = _add.value ?: return
        val auth = r.valueOrNull()
        if (auth == null) {
            _add.update {
                it?.copy(signingIn = false, problem = if (r is MediaResult.Unauthorized) TvAddProblem.WRONG_LOGIN else if (r is MediaResult.Offline) TvAddProblem.OFFLINE else TvAddProblem.FAILED)
            }
            return
        }
        val source = media.store.save(
            MediaSource(
                id = UUID.randomUUID().toString(),
                kind = MediaSourceKind.JELLYFIN,
                name = a.server?.name ?: "Jellyfin",
                address = a.serverAddress ?: return,
                userId = auth.userId,
                userName = auth.userName,
                token = auth.token,
                serverId = auth.serverId ?: a.server?.id,
            ),
        )
        media.select(source.id)
        _add.update { it?.copy(signingIn = false, done = true) }
        refresh()
    }

    private companion object {
        const val RESUME = 12
        const val LATEST = 16
        const val QUICK_POLL_MS = 3_000L
        const val QUICK_CONNECT_MS = 10 * 60_000L
        const val PICTURE_CACHE_BYTES = 24 * 1024 * 1024
        const val MAX_SHOWN = 160
    }
}
