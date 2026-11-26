package com.gatecontrol.android.media.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import java.util.Locale

/** One sound track or subtitle of the video: [group] and [index] as Media3 counts them in `currentTracks`. */
data class PlayerTrack(
    val kind: Kind,
    val group: Int,
    val index: Int,
    val label: String?,
    val language: String?,
    val channels: Int,
    val selected: Boolean,
) {
    enum class Kind { AUDIO, TEXT }
}

/** The panel "Ton & Untertitel": the sound tracks, the subtitles and whether they are off. */
data class PlayerTrackMenu(val audio: List<PlayerTrack>, val text: List<PlayerTrack>) {
    /** No subtitle chosen: "Aus" is ticked. */
    val textOff: Boolean get() = text.none { it.selected }

    /** Worth the panel: more than one sound track, or any subtitle. */
    val any: Boolean get() = audio.size > 1 || text.isNotEmpty()
}

/** The sound tracks and subtitles of a video in Media3 (Drive, Medien; TV and phone), without a player UI. */
object PlayerTracks {
    /** The panel's lists from the playable tracks. */
    fun menu(tracks: List<PlayerTrack>): PlayerTrackMenu =
        PlayerTrackMenu(tracks.filter { it.kind == PlayerTrack.Kind.AUDIO }, tracks.filter { it.kind == PlayerTrack.Kind.TEXT })

    /**
     * The name of a track in the panel: its own label, else its language in [locale] ("Deutsch"), else "[fallback] n";
     * for sound with the channels ("5.1").
     */
    fun name(t: PlayerTrack, n: Int, fallback: String, locale: Locale = Locale.getDefault()): String {
        val language = t.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
            ?.let { Locale.forLanguageTag(it).getDisplayLanguage(locale) }?.takeIf { it.isNotBlank() }
            ?.replaceFirstChar { it.titlecase(locale) }
        val base = t.label?.takeIf { it.isNotBlank() } ?: language ?: "$fallback $n"
        val channels = when (t.channels) {
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> null
        }
        return if (t.kind == PlayerTrack.Kind.AUDIO && channels != null) "$base · $channels" else base
    }

    /** The supported sound tracks and subtitles of [tracks] (Media3's `currentTracks`). */
    @OptIn(UnstableApi::class)
    fun of(tracks: Tracks): List<PlayerTrack> {
        val out = ArrayList<PlayerTrack>()
        tracks.groups.forEachIndexed { g, group ->
            val kind = when (group.type) {
                C.TRACK_TYPE_AUDIO -> PlayerTrack.Kind.AUDIO
                C.TRACK_TYPE_TEXT -> PlayerTrack.Kind.TEXT
                else -> return@forEachIndexed
            }
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                out += PlayerTrack(kind, g, i, f.label, f.language, f.channelCount, group.isTrackSelected(i))
            }
        }
        return out
    }

    /**
     * The [n]th sound track or subtitle ([kind]) of the file as it lists them, counted over all its tracks of that kind
     * (Jellyfin's place of a stream); null when there is none or this device does not play it (DTS without a decoder:
     * Media3 then keeps a sound it plays instead of silence).
     */
    @OptIn(UnstableApi::class)
    fun byOrdinal(tracks: Tracks, kind: PlayerTrack.Kind, n: Int): PlayerTrack? {
        val type = if (kind == PlayerTrack.Kind.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
        var seen = 0
        tracks.groups.forEachIndexed { g, group ->
            if (group.type != type) return@forEachIndexed
            for (i in 0 until group.length) {
                if (seen++ != n) continue
                if (!group.isTrackSupported(i)) return null
                val f = group.getTrackFormat(i)
                return PlayerTrack(kind, g, i, f.label, f.language, f.channelCount, group.isTrackSelected(i))
            }
        }
        return null
    }

    /**
     * The file has sound, but none of it plays on this device (DTS, TrueHD without a decoder): the name of its format
     * ("DTS") for a note instead of silence; null when a sound plays or the file has none.
     */
    @OptIn(UnstableApi::class)
    fun silentAudio(tracks: Tracks): String? {
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }
        if (audio.isEmpty()) return null
        if (audio.any { g -> (0 until g.length).any { g.isTrackSupported(it) } }) return null
        return codecName(audio.first().getTrackFormat(0).sampleMimeType ?: audio.first().getTrackFormat(0).codecs)
    }

    /** "DTS", "TrueHD", "Dolby Digital" from a sound's MIME type (or its codec string). */
    fun codecName(mime: String?): String {
        val m = mime?.lowercase(Locale.ROOT).orEmpty()
        return when {
            "dts" in m || "dca" in m -> "DTS"
            "truehd" in m || "true-hd" in m -> "TrueHD"
            "eac3" in m || "ec-3" in m -> "Dolby Digital Plus"
            "ac3" in m || "ac-3" in m -> "Dolby Digital"
            "ac4" in m || "ac-4" in m -> "Dolby AC-4"
            m.isEmpty() -> "?"
            else -> m.substringAfter('/').uppercase(Locale.ROOT)
        }
    }

    /** [params] with [track] chosen (a subtitle also switches subtitles on). */
    fun choose(params: TrackSelectionParameters, tracks: Tracks, track: PlayerTrack): TrackSelectionParameters {
        val group = tracks.groups.getOrNull(track.group) ?: return params
        val type = if (track.kind == PlayerTrack.Kind.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
        if (track.index !in 0 until group.length) return params
        return params.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track.index))
            .build()
    }

    /** [params] without subtitles ("Aus"). */
    fun subtitlesOff(params: TrackSelectionParameters): TrackSelectionParameters =
        params.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
}
