package com.gatecontrol.android.tv.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class TvTracksTest {

    private fun audio(lang: String?, channels: Int, label: String? = null) =
        Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage(lang).setChannelCount(channels).setLabel(label).build()

    private fun text(lang: String?) = Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).setLanguage(lang).build()

    private fun group(f: Format, selected: Boolean, supported: Boolean = true) =
        Tracks.Group(TrackGroup(f), false, intArrayOf(if (supported) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE), booleanArrayOf(selected))

    private val tracks = Tracks(
        listOf(
            Tracks.Group(TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setHeight(2160).build()), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)),
            group(audio("de", 6), selected = true),
            group(audio("en", 2), selected = false),
            group(audio("fr", 2), selected = false, supported = false),
            group(text("de"), selected = false),
            group(text(null), selected = false),
        ),
    )

    @Test
    fun `the panel lists the playable sound tracks and the subtitles`() {
        val menu = TvTracks.menu(TvTracks.of(tracks))
        assertEquals(listOf("de", "en"), menu.audio.map { it.language })
        assertEquals(2, menu.text.size)
        assertTrue(menu.audio.first().selected)
        assertTrue(menu.textOff)
        assertTrue(menu.any)
    }

    @Test
    fun `one sound track and no subtitles need no panel`() {
        val only = Tracks(listOf(group(audio("de", 2), selected = true)))
        assertFalse(TvTracks.menu(TvTracks.of(only)).any)
    }

    @Test
    fun `names in the panel`() {
        val menu = TvTracks.menu(TvTracks.of(tracks))
        assertEquals("Deutsch · 5.1", TvTracks.name(menu.audio[0], 1, "Spur", Locale.GERMAN))
        assertEquals("Englisch · Stereo", TvTracks.name(menu.audio[1], 2, "Spur", Locale.GERMAN))
        assertEquals("Deutsch", TvTracks.name(menu.text[0], 1, "Untertitel", Locale.GERMAN))
        assertEquals("Untertitel 2", TvTracks.name(menu.text[1], 2, "Untertitel", Locale.GERMAN))
        val labeled = TvTrack(TvTrack.Kind.AUDIO, 0, 0, "Kommentar", "de", 0, false)
        assertEquals("Kommentar", TvTracks.name(labeled, 1, "Spur", Locale.GERMAN))
    }

    @Test
    fun `choosing a subtitle switches subtitles on, off switches them off`() {
        val menu = TvTracks.menu(TvTracks.of(tracks))
        val off = TvTracks.subtitlesOff(TrackSelectionParameters.DEFAULT)
        assertTrue(C.TRACK_TYPE_TEXT in off.disabledTrackTypes)
        val on = TvTracks.choose(off, tracks, menu.text[0])
        assertFalse(C.TRACK_TYPE_TEXT in on.disabledTrackTypes)
        assertEquals(1, on.overrides.size)
        assertEquals(tracks.groups[4].mediaTrackGroup, on.overrides.keys.single())
        // a track that is not there changes nothing
        val gone = TvTrack(TvTrack.Kind.TEXT, 42, 0, null, null, 0, false)
        assertEquals(off, TvTracks.choose(off, tracks, gone))
    }
}
