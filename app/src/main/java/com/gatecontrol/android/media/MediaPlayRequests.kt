package com.gatecontrol.android.media

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one way the phone's media pages start playing (detail page, "Weiterschauen" in Medien and on Start, the search):
 * the queue is made here ([MediaBrowse.queue]: an episode with the rest of its series), then handed to the host in the
 * app's navigation, which gives it to [MediaPlayback] (the phone's player, [MediaPlayerSession]).
 */
@Singleton
class MediaPlayRequests @Inject constructor(
    private val hub: MediaHub,
    val playback: MediaPlayback,
) {
    private val _queues = MutableSharedFlow<MediaQueue>(extraBufferCapacity = 4)
    val queues: SharedFlow<MediaQueue> = _queues.asSharedFlow()

    /**
     * Plays [title], [fromStart] or where it was left, with the sound, subtitle and quality of the detail page (null:
     * the series' choice, the server's default, the quality setting). False when its source is gone.
     */
    suspend fun play(title: MediaTitle, fromStart: Boolean = false, audio: Int? = null, subtitle: Int? = null, original: Boolean? = null): Boolean {
        val q = MediaBrowse(hub) {}.queue(title, fromStart) ?: return false
        _queues.emit(q.copy(audioIndex = audio, subtitleIndex = subtitle, original = original))
        return true
    }
}
