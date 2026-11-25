package com.gatecontrol.android.media

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Why a media page shows nothing. */
enum class MediaProblem { OFFLINE, SIGNED_OUT, FAILED }

/** What a failed answer of [source] means for a page; a Jellyfin server's refused access is dropped. */
internal fun mediaProblemOf(hub: MediaHub, r: MediaResult<*>, source: MediaSource): MediaProblem? = when (r) {
    is MediaResult.Ok -> null
    MediaResult.Offline -> MediaProblem.OFFLINE
    MediaResult.Unauthorized -> {
        // a share keeps its password (it may be the NAS that refuses just now); Jellyfin's token is gone
        if (source.kind == MediaSourceKind.JELLYFIN) hub.store.dropToken(source.id)
        MediaProblem.SIGNED_OUT
    }
    else -> MediaProblem.FAILED
}

/**
 * Rows of Medien: one source's as loaded (Jellyfin page by page, a share whole), or all sources' in one
 * ([MediaBrowse.rows]).
 */
data class MediaRows(
    val resume: List<MediaTitle> = emptyList(),
    val latest: List<MediaTitle> = emptyList(),
    val movies: List<MediaTitle> = emptyList(),
    val moviesTotal: Int = 0,
    val moviesNext: Int? = null,
    val series: List<MediaTitle> = emptyList(),
    val seriesTotal: Int = 0,
    val seriesNext: Int? = null,
)

/** A title's details as a detail page shows them first: the sound and subtitle picked, the quality offered. */
data class MediaDetailChoice(
    val detail: MediaDetail,
    val audio: Int?,
    val subtitle: Int?,
    val offer: MediaQualityOffer,
)

/**
 * Loading the rows of Medien over all sources (the Jellyfin server shown and the shares), without UI state: the page's
 * model shows [rows] after each [onChange]. A title in Jellyfin and on a share shows once, Jellyfin's. Also the
 * detail page's loading (sound, subtitle and quality), the seasons and episodes, "Als gesehen markieren" and the
 * queue for the player. Used from one thread (the page model's main scope), as the rows are plain fields.
 */
class MediaBrowse(private val hub: MediaHub, private val onChange: () -> Unit) {

    /** The rows as loaded: the Jellyfin server's, each share's (by id). */
    private var jellyfin = MediaRows()
    private var shareRows: Map<String, MediaRows> = emptyMap()

    /** The rows of all sources in one (mockup 7): a title in Jellyfin and on a share once, Jellyfin's. */
    fun rows(): MediaRows {
        val j = jellyfin
        val shares = hub.shares().mapNotNull { shareRows[it.id] }
        val known = j.resume + j.latest + j.movies + j.series
        fun fromShares(f: (MediaRows) -> List<MediaTitle>) = MediaLogic.withoutDuplicates(shares.flatMap(f), known)
        val resume = MediaLogic.byLastPlayed(listOf(j.resume, fromShares { it.resume }), RESUME)
        val latest = MediaLogic.byAdded(listOf(j.latest, fromShares { it.latest }), LATEST)
        val shareMovies = fromShares { it.movies }
        val shareSeries = fromShares { it.series }
        return MediaRows(
            resume = resume,
            latest = MediaLogic.latestWithout(latest, resume),
            movies = MediaLogic.alphabetical(j.movies, j.moviesNext == null, shareMovies),
            moviesTotal = j.moviesTotal + shareMovies.size,
            moviesNext = j.moviesNext,
            series = MediaLogic.alphabetical(j.series, j.seriesNext == null, shareSeries),
            seriesTotal = j.seriesTotal + shareSeries.size,
            seriesNext = j.seriesNext,
        )
    }

    /** No source left: nothing loaded. */
    fun clear() {
        jellyfin = MediaRows()
        shareRows = emptyMap()
    }

    /** Before loading again: without a Jellyfin server its rows go, a share removed takes its rows along. */
    fun keepOnly(source: MediaSource?, shares: List<MediaSource>) {
        if (source == null) jellyfin = MediaRows()
        shareRows = shareRows.filterKeys { id -> shares.any { it.id == id } }
    }

    /** The rows of the Jellyfin server [source], step by step ([onChange] after each); the problem, if any. */
    suspend fun loadJellyfin(source: MediaSource): MediaProblem? {
        if (source.token == null) {
            // signed out by the server earlier: sign in again
            jellyfin = MediaRows()
            onChange()
            return MediaProblem.SIGNED_OUT
        }
        val lib = hub.library(source)
        val resume = lib.resume(RESUME)
        val problem = problemOf(resume, source)
        if (problem != null) {
            jellyfin = MediaRows()
            onChange()
            return problem
        }
        jellyfin = jellyfin.copy(resume = resume.valueOrNull().orEmpty())
        onChange()
        jellyfin = jellyfin.copy(latest = lib.latest(LATEST).valueOrNull().orEmpty())
        onChange()
        val movies = lib.movies(0, MediaLogic.PAGE).valueOrNull()
        val series = lib.series(0, MediaLogic.PAGE).valueOrNull()
        jellyfin = jellyfin.copy(
            movies = movies?.items.orEmpty(),
            moviesTotal = movies?.total ?: 0,
            moviesNext = movies?.next,
            series = series?.items.orEmpty(),
            seriesTotal = series?.total ?: 0,
            seriesNext = series?.next,
        )
        onChange()
        return null
    }

    /**
     * A share whole (its folders are searched once, then all rows come from that). False: it cannot be read just now
     * ([onShareProblem] is told first, then [onChange]).
     */
    suspend fun loadShare(source: MediaSource, onShareProblem: (Boolean) -> Unit = {}): Boolean {
        val lib = hub.library(source)
        val resume = lib.resume(RESUME)
        if (resume !is MediaResult.Ok) {
            shareRows = shareRows - source.id
            onShareProblem(true)
            onChange()
            return false
        }
        val movies = lib.movies(0, SHARE_ALL).valueOrNull()
        val series = lib.series(0, SHARE_ALL).valueOrNull()
        shareRows = shareRows + (
            source.id to MediaRows(
                resume = resume.value,
                latest = lib.latest(LATEST).valueOrNull().orEmpty(),
                movies = movies?.items.orEmpty(),
                series = series?.items.orEmpty(),
            )
            )
        onShareProblem(false)
        onChange()
        return true
    }

    /** Only "Weiterschauen" (the start page): of the Jellyfin server [source] and of [shares], then [onChange]. */
    suspend fun loadResume(source: MediaSource?, shares: List<MediaSource>) {
        hub.check(source ?: shares.first())
        coroutineScope {
            if (source != null) {
                launch { hub.library(source).resume(RESUME).valueOrNull()?.let { r -> jellyfin = jellyfin.copy(resume = r) } }
            }
            for (s in shares) {
                launch {
                    hub.library(s).resume(RESUME).valueOrNull()?.let { r -> shareRows = shareRows + (s.id to (shareRows[s.id] ?: MediaRows()).copy(resume = r)) }
                }
            }
        }
        onChange()
    }

    /** Jellyfin has another page of "Filme" ([movies]) or "Serien" (a share has all). */
    fun hasMore(movies: Boolean): Boolean = (if (movies) jellyfin.moviesNext else jellyfin.seriesNext) != null

    /** Jellyfin's next page of "Filme" ([movies]) or "Serien", then [onChange]. */
    suspend fun more(movies: Boolean) {
        val lib = hub.currentSource()?.let(hub::library) ?: return
        val next = (if (movies) jellyfin.moviesNext else jellyfin.seriesNext) ?: return
        val page = (if (movies) lib.movies(next, MediaLogic.PAGE) else lib.series(next, MediaLogic.PAGE)).valueOrNull() ?: return
        val j = jellyfin
        jellyfin = if (movies) {
            j.copy(movies = (j.movies + page.items).distinctBy { t -> t.id }, moviesNext = page.next, moviesTotal = page.total)
        } else {
            j.copy(series = (j.series + page.items).distinctBy { t -> t.id }, seriesNext = page.next, seriesTotal = page.total)
        }
        onChange()
    }

    /** What a failed answer of [source] means for the page; a Jellyfin server's refused access is dropped. */
    fun problemOf(r: MediaResult<*>, source: MediaSource): MediaProblem? = mediaProblemOf(hub, r, source)

    /**
     * The search over all sources (each Jellyfin server signed in and each share): films and series whose name holds
     * [text]; a title of a share that a Jellyfin server has too once, Jellyfin's. Blank: none.
     */
    suspend fun search(text: String, limit: Int = SEARCH): List<MediaTitle> {
        if (text.isBlank()) return emptyList()
        val servers = hub.store.sources.value.filter { it.kind == MediaSourceKind.JELLYFIN && it.token != null }
        val shares = hub.shares()
        return coroutineScope {
            val fromServers = servers.map { s -> async { hub.library(s).search(text, limit).valueOrNull().orEmpty() } }
            val fromShares = shares.map { s -> async { hub.library(s).search(text, limit).valueOrNull().orEmpty() } }
            val j = fromServers.awaitAll().flatten()
            j + MediaLogic.withoutDuplicates(fromShares.awaitAll().flatten(), j)
        }
    }

    /**
     * [title]'s details with the sound and subtitle of its series' choice (else the server's default) and the quality
     * offered for the network now; null with the problem when they cannot be loaded.
     */
    suspend fun detail(title: MediaTitle): Pair<MediaDetailChoice?, MediaProblem?> {
        val source = hub.store.source(title.sourceId) ?: return null to MediaProblem.FAILED
        val d = hub.library(source).detail(title.id)
        val detail = d.valueOrNull() ?: return null to problemOf(d, source)
        val version = detail.version
        val choice = detail.title.seriesId?.let { hub.store.trackChoice(it) }
        val link = hub.link.value
        val offer = MediaQuality.offer(
            atHome = link.atHome == true,
            measuredBps = link.bps,
            originalBps = version?.bitrate,
            alwaysOriginal = source.alwaysOriginal,
            canConvert = source.kind.canConvert,
        )
        return MediaDetailChoice(
            detail = detail,
            audio = version?.let { v -> MediaTracks.pickAudio(v, choice) },
            subtitle = version?.let { v -> MediaTracks.pickSubtitle(v, choice) ?: v.defaultSubtitle },
            offer = offer,
        ) to null
    }

    /** The seasons of series [series]. */
    suspend fun seasons(series: MediaTitle): List<MediaTitle> {
        val source = hub.store.source(series.sourceId) ?: return emptyList()
        return hub.library(source).seasons(series.id).valueOrNull().orEmpty()
    }

    /** The episodes of [seasonId] of series [series]. */
    suspend fun episodes(series: MediaTitle, seasonId: String): List<MediaTitle> {
        val source = hub.store.source(series.sourceId) ?: return emptyList()
        return hub.library(source).episodes(series.id, seasonId).valueOrNull().orEmpty()
    }

    /** The series keeps the sound and subtitle chosen on a detail page ("merkt sich GateControl je Serie"). */
    fun rememberTracks(seriesId: String, version: MediaVersion, audio: Int?, subtitle: Int?) =
        hub.store.setTrackChoice(seriesId, MediaTracks.choiceOf(version, audio, subtitle))

    /** "Als gesehen markieren" (and back); true when the server took it. */
    suspend fun setPlayed(title: MediaTitle, played: Boolean): Boolean {
        val source = hub.store.source(title.sourceId) ?: return false
        return hub.library(source).setPlayed(title.id, played) is MediaResult.Ok
    }

    /**
     * What the player goes through for [title]: an episode with the rest of its series after it, a film alone;
     * [fromStart] or where it was left. Null when its source is gone.
     */
    suspend fun queue(title: MediaTitle, fromStart: Boolean = false): MediaQueue? {
        val source = hub.store.source(title.sourceId) ?: return null
        val episodes = if (title.kind == MediaTitle.Kind.EPISODE && title.seriesId != null) {
            hub.library(source).episodes(title.seriesId).valueOrNull().orEmpty()
        } else {
            emptyList()
        }
        return MediaLogic.queue(source.id, title, episodes, if (fromStart) 0L else null)
    }

    companion object {
        const val RESUME = 12
        const val LATEST = 16
        const val SEARCH = 40

        /** A share's rows are loaded whole (its index is on the device already). */
        const val SHARE_ALL = 2_000
    }
}
