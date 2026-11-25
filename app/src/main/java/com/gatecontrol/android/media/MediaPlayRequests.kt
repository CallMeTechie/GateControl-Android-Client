package com.gatecontrol.android.media

import com.gatecontrol.android.media.offline.MediaOffline
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one way the phone's media pages start playing (detail page, "Weiterschauen" in Medien and on Start, the search):
 * the queue is made here ([MediaBrowse.queue]: an episode with the rest of its series; the page "Ordner" makes its
 * own, the videos of the folder), then handed to the host in the
 * app's navigation, which gives it to [MediaPlayback] (the phone's player, [MediaPlayerSession]).
 */
@Singleton
class MediaPlayRequests @Inject constructor(
    private val hub: MediaHub,
    val playback: MediaPlayback,
    private val offline: MediaOffline,
) {
    private val _queues = MutableSharedFlow<MediaQueue>(extraBufferCapacity = 4)
    val queues: SharedFlow<MediaQueue> = _queues.asSharedFlow()

    /**
     * Plays [title], [fromStart] or where it was left, with the sound, subtitle and quality of the detail page (null:
     * the series' choice, the server's default, the quality setting). False when its source is gone.
     */
    suspend fun play(title: MediaTitle, fromStart: Boolean = false, audio: Int? = null, subtitle: Int? = null, original: Boolean? = null): Boolean {
        val online = MediaBrowse(hub) {}.queue(title, fromStart) ?: return false
        // without a connection the series' episodes come from the downloads
        val q = if (online.items.size <= 1) offlineQueue(title, fromStart) ?: online else online
        _queues.emit(q.copy(audioIndex = audio, subtitleIndex = subtitle, original = original))
        return true
    }

    /** An episode with the other downloaded episodes of its series after it; null when there are none. */
    private fun offlineQueue(title: MediaTitle, fromStart: Boolean): MediaQueue? {
        if (title.kind != MediaTitle.Kind.EPISODE || title.seriesId == null) return null
        val episodes = offline.items.value.filter { it.ready && it.sourceId == title.sourceId && it.title.seriesId == title.seriesId }
            .map { it.titleNow() }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        val at = episodes.indexOfFirst { it.id == title.id }
        if (at < 0 || episodes.size <= 1) return null
        return MediaQueue(title.sourceId, episodes, at, if (fromStart) 0L else null)
    }

    /** Plays [queue] as it is (the videos of a share's folder from the one tapped: the page "Ordner"). */
    suspend fun play(queue: MediaQueue) {
        _queues.emit(queue)
    }
}
