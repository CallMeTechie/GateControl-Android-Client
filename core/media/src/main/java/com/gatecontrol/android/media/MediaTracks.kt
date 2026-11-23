package com.gatecontrol.android.media

import com.gatecontrol.android.media.jellyfin.JellyfinDeviceCaps
import com.gatecontrol.android.media.jellyfin.JellyfinProfile
import java.util.Locale

/** What happens with a sound track on this TV (mockup 9). */
enum class MediaAudioFate {
    /** Goes as it is to the AV receiver (DTS, Dolby). */
    PASSTHROUGH,
    /** The stick plays it itself. */
    DECODED,
    /** Jellyfin converts it (to Dolby Digital 5.1 or AAC). */
    CONVERTED,
}

/** The sound tracks and subtitles of a title: choosing, remembering per series, the labels. Pure, unit-tested. */
object MediaTracks {

    /** ISO 639-2/B codes (as ffprobe and Jellyfin write them) → the language tag Java knows. */
    private val BIBLIOGRAPHIC = mapOf(
        "ger" to "de", "fre" to "fr", "dut" to "nl", "chi" to "zh", "cze" to "cs", "gre" to "el", "per" to "fa",
        "rum" to "ro", "slo" to "sk", "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "geo" to "ka",
        "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms", "tib" to "bo", "wel" to "cy",
    )

    /** ISO 639-2/T codes ("deu", "eng") → the two-letter tag. */
    private val THREE_LETTER: Map<String, String> by lazy {
        Locale.getISOLanguages().mapNotNull { l -> runCatching { Locale(l).isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { it to l } }.toMap()
    }

    /** "ger" / "deu" / "de" → "Deutsch" in [locale]; null when it is not a language ("und", unknown). */
    fun languageName(code: String, locale: Locale = Locale.getDefault()): String? {
        val c = code.trim().lowercase(Locale.ROOT)
        if (c.isEmpty() || c == "und" || c == "mis" || c == "zxx") return null
        val tag = BIBLIOGRAPHIC[c] ?: THREE_LETTER[c] ?: c
        val name = Locale.forLanguageTag(tag).getDisplayLanguage(locale).takeIf { it.isNotBlank() && !it.equals(tag, ignoreCase = true) } ?: return null
        return name.replaceFirstChar { it.titlecase(locale) }
    }

    /** "DTS", "TrueHD", "Dolby Digital", "AAC" … */
    fun codecLabel(codec: String?): String? = when (codec?.lowercase(Locale.ROOT)) {
        null, "" -> null
        "dts", "dca" -> "DTS"
        "truehd", "mlp" -> "TrueHD"
        "ac3" -> "Dolby Digital"
        "eac3" -> "Dolby Digital Plus"
        "aac" -> "AAC"
        "mp3" -> "MP3"
        "flac" -> "FLAC"
        "opus" -> "Opus"
        "vorbis" -> "Vorbis"
        "subrip", "srt" -> "SRT"
        "ass", "ssa" -> "ASS"
        "webvtt", "vtt" -> "VTT"
        "pgssub", "pgs" -> "PGS"
        "dvdsub", "dvbsub" -> "DVD"
        "mov_text" -> "Text"
        else -> codec.uppercase(Locale.ROOT)
    }

    fun channelsLabel(channels: Int?): String? = when (channels) {
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        null, 0 -> null
        else -> "$channels ch"
    }

    /** "DTS 5.1", "AAC Stereo". */
    fun audioLabel(s: MediaStream): String = listOfNotNull(codecLabel(s.codec), channelsLabel(s.channels)).joinToString(" ").ifEmpty { "Audio" }

    fun fate(s: MediaStream, caps: JellyfinDeviceCaps): MediaAudioFate {
        val codec = s.codec?.lowercase(Locale.ROOT).orEmpty()
        val pass = caps.passthrough.map { it.lowercase(Locale.ROOT) }.toSet()
        val normalized = if (codec == "dca") "dts" else codec
        return when {
            normalized in pass -> MediaAudioFate.PASSTHROUGH
            normalized in JellyfinProfile.DECODED_AUDIO -> MediaAudioFate.DECODED
            else -> MediaAudioFate.CONVERTED
        }
    }

    /** The audio stream for a series' [choice] (its language), else the server's default, else the first. */
    fun pickAudio(version: MediaVersion, choice: MediaTrackChoice?): Int? {
        val audio = version.audio
        val lang = choice?.audioLanguage
        if (lang != null) {
            val match = audio.filter { it.language.equals(lang, ignoreCase = true) }
            (match.firstOrNull { it.isDefault } ?: match.firstOrNull())?.let { return it.index }
        }
        return version.defaultAudio?.takeIf { d -> audio.any { it.index == d } } ?: audio.firstOrNull()?.index
    }

    /** The subtitle for [choice]: off, the one of its language (not forced first), else the server's default (null). */
    fun pickSubtitle(version: MediaVersion, choice: MediaTrackChoice?): Int? {
        if (choice == null) return null
        if (choice.subtitlesOff) return MediaPlay.SUBTITLES_OFF
        val lang = choice.subtitleLanguage ?: return null
        val match = version.subtitles.filter { it.language.equals(lang, ignoreCase = true) }
        return (match.firstOrNull { !it.forced } ?: match.firstOrNull())?.index
    }

    /** What to remember for the series from what was chosen. */
    fun choiceOf(version: MediaVersion, audioIndex: Int?, subtitleIndex: Int?): MediaTrackChoice {
        val audio = version.audio.firstOrNull { it.index == audioIndex }
        val subtitle = version.subtitles.firstOrNull { it.index == subtitleIndex }
        return MediaTrackChoice(audio?.language, subtitle?.language, subtitlesOff = subtitleIndex == MediaPlay.SUBTITLES_OFF)
    }

    /**
     * The place of stream [index] among the streams of its kind inside the file (0, 1, …), as a player numbers the
     * file's own tracks; null for a stream beside the file (external) or one that is not there.
     */
    fun ordinal(version: MediaVersion, index: Int?): Int? {
        val s = version.streams.firstOrNull { it.index == index } ?: return null
        if (s.external) return null
        return version.streams.filter { it.type == s.type && !it.external }.sortedBy { it.index }.indexOfFirst { it.index == index }.takeIf { it >= 0 }
    }
}
