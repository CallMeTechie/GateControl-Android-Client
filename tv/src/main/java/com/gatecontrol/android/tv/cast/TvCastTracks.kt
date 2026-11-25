package com.gatecontrol.android.tv.cast

import com.gatecontrol.android.media.cast.CastPhase
import com.gatecontrol.android.media.cast.CastProtocol
import com.gatecontrol.android.media.cast.CastTrack
import com.gatecontrol.android.media.player.PlayerTrack
import com.gatecontrol.android.media.player.PlayerTrackMenu
import com.gatecontrol.android.tv.ui.drive.TvChoice
import com.gatecontrol.android.tv.ui.drive.TvChoices

/** What the phone may pick on the TV: a line of Jellyfin's panel ([TvChoice]) or a track of the file itself. */
sealed interface TvCastPick {
    data class Choice(val choice: TvChoice) : TvCastPick
    data class Track(val track: PlayerTrack) : TvCastPick
    data object SubtitlesOff : TvCastPick
}

/**
 * The TV's sound tracks and subtitles as the phone sees them ([CastTrack], ids of the TV's own) and back. Jellyfin:
 * the panel's lines (`a:<stream>`, `t:<stream>`); a share's file: its tracks (`a:g<group>.<index>`); "Aus" always
 * [CastProtocol.TRACK_OFF]. Pure, unit-tested.
 */
object TvCastTracks {

    fun audio(choices: TvChoices?, menu: PlayerTrackMenu, name: (PlayerTrack, Int) -> String): List<CastTrack> =
        choices?.audio?.map { CastTrack("a:" + it.id, it.label, it.selected) }
            ?: menu.audio.mapIndexed { i, t -> CastTrack("a:" + trackId(t), name(t, i + 1), t.selected) }

    fun subtitles(choices: TvChoices?, menu: PlayerTrackMenu, off: String, name: (PlayerTrack, Int) -> String): List<CastTrack> {
        if (choices != null) return choices.text.map { c -> CastTrack(if (c.id == OFF) CastProtocol.TRACK_OFF else "t:" + c.id, c.label, c.selected) }
        if (menu.text.isEmpty()) return emptyList()
        return listOf(CastTrack(CastProtocol.TRACK_OFF, off, menu.textOff)) + menu.text.mapIndexed { i, t -> CastTrack("t:" + trackId(t), name(t, i + 1), t.selected) }
    }

    /** The line or track the phone's [id] names; null when the TV does not have it (any more). */
    fun pick(id: String, audio: Boolean, choices: TvChoices?, menu: PlayerTrackMenu): TvCastPick? {
        if (choices != null) {
            val list = if (audio) choices.audio else choices.text
            val c = list.firstOrNull { (if (it.id == OFF && !audio) CastProtocol.TRACK_OFF else (if (audio) "a:" else "t:") + it.id) == id } ?: return null
            return TvCastPick.Choice(c)
        }
        if (!audio && id == CastProtocol.TRACK_OFF) return TvCastPick.SubtitlesOff
        val list = if (audio) menu.audio else menu.text
        val prefix = if (audio) "a:" else "t:"
        return list.firstOrNull { prefix + trackId(it) == id }?.let { TvCastPick.Track(it) }
    }

    /** The phase for the phone from the player's state. */
    fun phase(trouble: Boolean, idle: Boolean, buffering: Boolean, ended: Boolean, playWhenReady: Boolean, countdown: Boolean, last: Boolean): CastPhase = when {
        trouble -> CastPhase.FAILED
        countdown -> CastPhase.LOADING
        ended -> if (last) CastPhase.ENDED else CastPhase.LOADING
        idle -> CastPhase.LOADING
        buffering -> if (playWhenReady) CastPhase.BUFFERING else CastPhase.PAUSED
        playWhenReady -> CastPhase.PLAYING
        else -> CastPhase.PAUSED
    }

    private fun trackId(t: PlayerTrack) = "g${t.group}.${t.index}"

    /** The "Aus" line of Jellyfin's panel ([com.gatecontrol.android.tv.media.TvMediaVideos]). */
    private const val OFF = "off"
}
