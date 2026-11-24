package com.gatecontrol.android.media.player

import androidx.media3.common.C
import androidx.media3.common.Player

/** What the player tells the source about a playback ("Weiterschauen" in Jellyfin). */
enum class PlaybackEvent { START, PROGRESS, STOP }

/**
 * The progress of a playback to its source (Jellyfin: "Weiterschauen" on every device; a share: on the device), apart
 * from any player UI: started once the video is ready, every [EVERY_MS] while it plays ([tick]) and on pause or
 * play, stopped at its end or when it is left. [at]: the index of the video in the player's queue, handed through
 * to [report] as it was when the event happened.
 */
class PlaybackReporter(
    private val report: (at: Int, event: PlaybackEvent, positionMs: Long, paused: Boolean, durationMs: Long) -> Unit,
) {
    /** The source was told the playback started (and has not been told it stopped). */
    var started: Boolean = false
        private set

    /** `onIsPlayingChanged`: paused or playing again. */
    fun playingChanged(player: Player, at: Int, isPlaying: Boolean) {
        if (started && player.playbackState != Player.STATE_ENDED) {
            report(at, PlaybackEvent.PROGRESS, player.currentPosition, !isPlaying, lengthOf(player))
        }
    }

    /** `onPlaybackStateChanged` to ready: the start, once. */
    fun ready(player: Player, at: Int) {
        if (started) return
        started = true
        report(at, PlaybackEvent.START, player.currentPosition, !player.playWhenReady, lengthOf(player))
    }

    /** The end of the video: stopped at its length. */
    fun ended(player: Player, at: Int) {
        if (!started) return
        started = false
        report(at, PlaybackEvent.STOP, player.duration.takeIf { it != C.TIME_UNSET } ?: player.currentPosition, false, lengthOf(player))
    }

    /** Every [EVERY_MS] while it plays. */
    fun tick(player: Player, at: Int) {
        if (started && player.isPlaying) report(at, PlaybackEvent.PROGRESS, player.currentPosition, false, lengthOf(player))
    }

    /** The video is left (another one, closed): stopped where it is. */
    fun stop(player: Player, at: Int) {
        if (!started) return
        started = false
        report(at, PlaybackEvent.STOP, player.currentPosition.coerceAtLeast(0), true, lengthOf(player))
    }

    companion object {
        /** The progress goes to the source this often while it plays. */
        const val EVERY_MS = 10_000L

        /** The length as the player knows it (0: not yet). */
        fun lengthOf(player: Player): Long = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
    }
}
