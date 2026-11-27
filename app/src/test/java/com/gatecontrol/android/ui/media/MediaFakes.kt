package com.gatecontrol.android.ui.media

import com.gatecontrol.android.media.MediaDetail
import com.gatecontrol.android.media.MediaLibrary
import com.gatecontrol.android.media.MediaOrder
import com.gatecontrol.android.media.MediaPage
import com.gatecontrol.android.media.MediaPlan
import com.gatecontrol.android.media.MediaPlay
import com.gatecontrol.android.media.MediaProgress
import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaTitle

/** A library in memory for the media pages' tests: what it has, what was asked of it. */
internal class FakeLibrary(
    override val source: MediaSource,
    var resume: List<MediaTitle> = emptyList(),
    var latest: List<MediaTitle> = emptyList(),
    var movies: List<MediaTitle> = emptyList(),
    var series: List<MediaTitle> = emptyList(),
    var details: Map<String, MediaDetail> = emptyMap(),
    var episodes: List<MediaTitle> = emptyList(),
    var seasons: List<MediaTitle> = emptyList(),
    /** The rows of „Ergänzt“: null keeps the library's own default (a share's). */
    var nextUp: List<MediaTitle>? = null,
    var latestEpisodes: List<MediaTitle>? = null,
    var favorites: List<MediaTitle>? = null,
    var collections: List<MediaTitle>? = null,
    var collectionItems: Map<String, List<MediaTitle>> = emptyMap(),
    var genres: List<String>? = null,
) : MediaLibrary {
    /** The hearts set: id to favourite. */
    val hearts = mutableListOf<Pair<String, Boolean>>()

    /** Every row asked for, in the order asked (they go out side by side). */
    val asked = java.util.Collections.synchronizedList(mutableListOf<String>())

    val orders = mutableListOf<MediaOrder>()
    val played = mutableListOf<Pair<String, Boolean>>()

    /** What the player asked for, and the answer (null: not found). */
    val plays = mutableListOf<MediaPlay>()
    var plan: (MediaPlay) -> MediaPlan? = { null }

    /** The progress reported: "start" / "progress" / "stop" with the position. */
    val reports = mutableListOf<Pair<String, Long>>()

    /** How often "Weiterschauen" was asked for (each load of the overview). */
    var resumeCalls = 0

    /** Set: "Weiterschauen" answers this (not reached, signed out) instead of [resume]. */
    var resumeAnswer: MediaResult<List<MediaTitle>>? = null

    override suspend fun resume(limit: Int): MediaResult<List<MediaTitle>> {
        resumeCalls++
        return resumeAnswer ?: MediaResult.Ok(resume.take(limit))
    }
    override suspend fun latest(limit: Int) = MediaResult.Ok(latest.take(limit))
    override suspend fun nextUp(limit: Int): MediaResult<MediaPage> {
        asked += "nextUp"
        return nextUp?.let { MediaResult.Ok(MediaPage(it.take(limit), it.size, 0)) } ?: super.nextUp(limit)
    }
    override suspend fun latestEpisodes(limit: Int): MediaResult<List<MediaTitle>> {
        asked += "latestEpisodes"
        return latestEpisodes?.let { MediaResult.Ok(it.take(limit)) } ?: super.latestEpisodes(limit)
    }
    override suspend fun favorites(limit: Int): MediaResult<MediaPage> {
        asked += "favorites"
        return favorites?.let { MediaResult.Ok(MediaPage(it.take(limit), it.size, 0)) } ?: super.favorites(limit)
    }
    override suspend fun collections(limit: Int): MediaResult<MediaPage> {
        asked += "collections"
        return collections?.let { MediaResult.Ok(MediaPage(it.take(limit), it.size, 0)) } ?: super.collections(limit)
    }
    override suspend fun collection(id: String): MediaResult<List<MediaTitle>> = MediaResult.Ok(collectionItems[id].orEmpty())
    override suspend fun genres(movies: Boolean): MediaResult<List<String>> = genres?.let { MediaResult.Ok(it) } ?: super.genres(movies)
    override suspend fun setFavorite(itemId: String, favorite: Boolean): MediaResult<Unit> {
        hearts += itemId to favorite
        return if (source.kind == com.gatecontrol.android.media.MediaSourceKind.JELLYFIN) MediaResult.Ok(Unit) else MediaResult.Failed(0)
    }
    override suspend fun movies(start: Int, limit: Int) = MediaResult.Ok(MediaPage(movies.drop(start).take(limit), movies.size, start))
    override suspend fun series(start: Int, limit: Int) = MediaResult.Ok(MediaPage(series.drop(start).take(limit), series.size, start))
    override suspend fun list(movies: Boolean, start: Int, limit: Int, order: MediaOrder): MediaResult<MediaPage> {
        orders += order
        return super.list(movies, start, limit, order)
    }
    override suspend fun seasons(seriesId: String) = MediaResult.Ok(seasons)
    override suspend fun episodes(seriesId: String, seasonId: String?) = MediaResult.Ok(episodes.filter { seasonId == null || it.seasonId == seasonId })
    override suspend fun detail(itemId: String): MediaResult<MediaDetail> = details[itemId]?.let { MediaResult.Ok(it) } ?: MediaResult.NotFound
    override suspend fun play(play: MediaPlay): MediaResult<MediaPlan> {
        plays += play
        return plan(play)?.let { MediaResult.Ok(it) } ?: MediaResult.NotFound
    }
    override suspend fun started(p: MediaProgress) { reports += "start" to p.positionMs }
    override suspend fun progress(p: MediaProgress) { reports += "progress" to p.positionMs }
    override suspend fun stopped(p: MediaProgress) { reports += "stop" to p.positionMs }
    override suspend fun setPlayed(itemId: String, played: Boolean): MediaResult<Unit> {
        this.played += itemId to played
        return MediaResult.Ok(Unit)
    }
    override suspend fun measure(): Long? = null
    override fun imageUrl(title: MediaTitle, backdrop: Boolean, maxHeight: Int): String? = null
    override fun headers(): Map<String, String> = emptyMap()
}

/**
 * The plugin's „Videos umwandeln“ in memory: [status] answers `v1/convert/status` (null: the route is missing, as
 * before gatecontrol-medien 1.6.0), [request] answers `v1/convert/request`; what was asked is kept.
 */
internal class FakeConvertApi(
    var status: com.gatecontrol.android.media.server.MediaConvertStatus? = null,
    var request: com.gatecontrol.android.media.server.MediaConvertItem? = null,
) : com.gatecontrol.android.media.server.MediaConvertApi {
    val asked = mutableListOf<List<String>>()
    val requested = mutableListOf<String>()

    override suspend fun convertStatus(itemIds: List<String>): com.gatecontrol.android.media.server.MediaServerResult<com.gatecontrol.android.media.server.MediaConvertStatus> {
        asked += itemIds
        val s = status ?: return com.gatecontrol.android.media.server.MediaServerResult.Refused(com.gatecontrol.android.media.server.MediaServerError(404, "not_found"))
        return com.gatecontrol.android.media.server.MediaServerResult.Ok(s.copy(items = s.items.filterKeys { it in itemIds }))
    }

    override suspend fun convertRequest(itemId: String): com.gatecontrol.android.media.server.MediaServerResult<com.gatecontrol.android.media.server.MediaConvertItem> {
        requested += itemId
        val r = request ?: return com.gatecontrol.android.media.server.MediaServerResult.Refused(com.gatecontrol.android.media.server.MediaServerError(429, "rate_limited"))
        return com.gatecontrol.android.media.server.MediaServerResult.Ok(r)
    }
}

/** „Videos umwandeln“ without the plugin route: no hint anywhere (the pages as before). */
internal fun noConvert() = com.gatecontrol.android.media.server.MediaConvert(FakeConvertApi())
