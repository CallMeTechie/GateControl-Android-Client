package com.gatecontrol.android.tv.cast

import com.gatecontrol.android.media.cast.CastPhase
import com.gatecontrol.android.media.cast.CastProtocol
import com.gatecontrol.android.media.cast.CastTrack
import com.gatecontrol.android.media.player.PlayerTrack
import com.gatecontrol.android.media.player.PlayerTrackMenu
import com.gatecontrol.android.tv.ui.drive.TvChoice
import com.gatecontrol.android.tv.ui.drive.TvChoiceKind
import com.gatecontrol.android.tv.ui.drive.TvChoices
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TvCastTracksTest {

    private val name: (PlayerTrack, Int) -> String = { t, n -> t.label ?: "Spur $n" }

    private val jellyfin = TvChoices(
        audio = listOf(TvChoice(TvChoiceKind.AUDIO, "1", "Deutsch · DTS 5.1", selected = true), TvChoice(TvChoiceKind.AUDIO, "2", "English · AC3")),
        text = listOf(TvChoice(TvChoiceKind.TEXT, "off", "Aus", selected = true), TvChoice(TvChoiceKind.TEXT, "4", "Deutsch")),
    )

    private val de = PlayerTrack(PlayerTrack.Kind.AUDIO, 1, 0, "Deutsch", "de", 6, true)
    private val en = PlayerTrack(PlayerTrack.Kind.AUDIO, 2, 0, null, "en", 2, false)
    private val sub = PlayerTrack(PlayerTrack.Kind.TEXT, 3, 0, "Deutsch (erzwungen)", "de", 0, false)
    private val file = PlayerTrackMenu(listOf(de, en), listOf(sub))

    @Test
    fun `Jellyfin's panel lines go to the phone with their stream ids`() {
        assertEquals(listOf(CastTrack("a:1", "Deutsch · DTS 5.1", true), CastTrack("a:2", "English · AC3")), TvCastTracks.audio(jellyfin, file, name))
        assertEquals(listOf(CastTrack(CastProtocol.TRACK_OFF, "Aus", true), CastTrack("t:4", "Deutsch")), TvCastTracks.subtitles(jellyfin, file, "Aus", name))
        assertEquals(TvCastPick.Choice(jellyfin.audio[1]), TvCastTracks.pick("a:2", true, jellyfin, file))
        assertEquals(TvCastPick.Choice(jellyfin.text[0]), TvCastTracks.pick(CastProtocol.TRACK_OFF, false, jellyfin, file))
        assertNull(TvCastTracks.pick("a:9", true, jellyfin, file))
    }

    @Test
    fun `a share's file offers its own tracks and off`() {
        assertEquals(listOf(CastTrack("a:g1.0", "Deutsch", true), CastTrack("a:g2.0", "Spur 2")), TvCastTracks.audio(null, file, name))
        assertEquals(
            listOf(CastTrack(CastProtocol.TRACK_OFF, "Aus", true), CastTrack("t:g3.0", "Deutsch (erzwungen)")),
            TvCastTracks.subtitles(null, file, "Aus", name),
        )
        assertEquals(emptyList<CastTrack>(), TvCastTracks.subtitles(null, PlayerTrackMenu(listOf(de), emptyList()), "Aus", name))
        assertEquals(TvCastPick.Track(en), TvCastTracks.pick("a:g2.0", true, null, file))
        assertEquals(TvCastPick.Track(sub), TvCastTracks.pick("t:g3.0", false, null, file))
        assertEquals(TvCastPick.SubtitlesOff, TvCastTracks.pick(CastProtocol.TRACK_OFF, false, null, file))
        assertNull(TvCastTracks.pick("t:g1.0", false, null, file))
    }

    @Test
    fun `the phase for the phone`() {
        fun phase(trouble: Boolean = false, idle: Boolean = false, buffering: Boolean = false, ended: Boolean = false, play: Boolean = true, countdown: Boolean = false, last: Boolean = true) =
            TvCastTracks.phase(trouble, idle, buffering, ended, play, countdown, last)
        assertEquals(CastPhase.PLAYING, phase())
        assertEquals(CastPhase.PAUSED, phase(play = false))
        assertEquals(CastPhase.BUFFERING, phase(buffering = true))
        assertEquals(CastPhase.PAUSED, phase(buffering = true, play = false))
        assertEquals(CastPhase.LOADING, phase(idle = true))
        assertEquals(CastPhase.ENDED, phase(ended = true))
        // an episode with one after it: "Nächste Folge in 10 s"
        assertEquals(CastPhase.LOADING, phase(ended = true, last = false))
        assertEquals(CastPhase.LOADING, phase(countdown = true))
        assertEquals(CastPhase.FAILED, phase(trouble = true))
    }
}
