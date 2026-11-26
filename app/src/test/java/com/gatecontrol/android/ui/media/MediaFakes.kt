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
) : MediaLibrary {
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
