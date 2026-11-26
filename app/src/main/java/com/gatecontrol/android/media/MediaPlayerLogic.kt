package com.gatecontrol.android.media

import com.gatecontrol.android.media.jellyfin.JellyfinDeviceCaps

/** Where the phone is for a playback (the info line): at home, away over Wi-Fi, away on mobile data. */
enum class MediaNet { HOME, AWAY, CELLULAR }

/**
 * The info line of the player (mockups 4): how the phone is connected ("Mobilfunk · 6 Mbit/s") and what the NAS does
 * with the video — plays it directly ("Direkt · 4K HDR"), converts the picture ("NAS rechnet um: 720p · 3 Mbit/s") or
 * only the sound ("Ton umgerechnet · AAC 5.1"). A share always plays the original, over SMB or WebDAV ([share]).
 */
data class MediaPlayLine(
    val net: MediaNet?,
    /** Measured just now (away); null at home or when not measured. */
    val measuredBps: Long? = null,
    /** "SMB" / "WebDAV": a file of a share (the original, nothing converted). */
    val share: String? = null,
    /** Played directly: the picture ("4K HDR"). */
    val video: String? = null,
    /** The NAS converts the picture: to this bit rate (null with [converted]: unknown). */
    val convertedBps: Long? = null,
    val converted: Boolean = false,
    /** A download on the phone in this quality ("1080p", "Original"): nothing comes from the network. */
    val offline: String? = null,
    /** Only the sound is converted (the picture as it is): to this ("AAC 5.1"). */
    val audioTo: String? = null,
    /** The limit on mobile data held the bit rate down (question 28). */
    val limitBps: Long? = null,
)

/** What the sheet "Ton & Untertitel" of a Jellyfin title offers (a share's file uses the player's own tracks). */
data class MediaPlayerChoices(
    val audio: List<MediaStream>,
    val subtitles: List<MediaStream>,
    val audioIndex: Int?,
    /** The chosen subtitle; [MediaPlay.SUBTITLES_OFF] or null: off. */
    val subtitleIndex: Int?,
    /** The steps of "Qualität"; empty: the source plays only the original ("Immer Original"). */
    val qualities: List<MediaQualitySetting>,
    val quality: MediaQualitySetting,
    /** The NAS converts what the phone cannot play (Jellyfin). */
    val canConvert: Boolean,
    val caps: JellyfinDeviceCaps,
) {
    val subtitlesOff: Boolean get() = subtitleIndex == null || subtitleIndex == MediaPlay.SUBTITLES_OFF
}

/** Small rules of the phone's player, kept apart from the player and the composables. Pure, unit-tested. */
object MediaPlayerLogic {

    /** Double tap left or right: this far back or ahead (as the Drive player). */
    const val SEEK_STEP_MS = 10_000L

    /** "Nächste Folge in 10 s". */
    const val NEXT_SECONDS = 10

    /** The bar hides after this long while it plays. */
    const val CONTROLS_HIDE_MS = 3_500L

    /** Back to the start first, then the video before (as other players). */
    const val PREVIOUS_RESTART_MS = 5_000L

    /**
     * No first picture after this long with play on (loading or "playing" without a picture): the file as it is does
     * not start on this phone, it is asked again packed anew or converted ([nextFallback]). At home sooner, away the
     * tunnel may take longer for the first seconds.
     */
    const val FIRST_PICTURE_HOME_MS = 8_000L
    const val FIRST_PICTURE_AWAY_MS = 15_000L

    /** After the start: loading this long counts as hanging; [STALLS] such times and it is asked another way. */
    const val STALL_MS = 10_000L
    const val STALLS = 3

    /** How often the playback is looked at ([MediaStallWatch]). */
    const val WATCH_STEP_MS = 1_000L

    /**
     * Converted on the NAS (the last way) a first picture may take this many times longer (the NAS starts ffmpeg);
     * after it only „kann es nicht umwandeln“ is left.
     */
    const val LAST_WAY_FACTOR = 2

    /** The wait for the first picture on [net] (away, on mobile data or not known: the longer one); [last]: converted. */
    fun firstPictureMs(net: MediaNet?, last: Boolean = false): Long =
        (if (net == MediaNet.HOME) FIRST_PICTURE_HOME_MS else FIRST_PICTURE_AWAY_MS) * (if (last) LAST_WAY_FACTOR else 1)

    /** The note "Direkt ging nicht – der NAS wandelt jetzt um" stays this long. */
    const val FELL_BACK_NOTE_MS = 6_000L

    /**
     * The next way to ask for a video that did not start ([now]: how it was asked): the file itself → packed anew
     * (remux; the picture and sound copied where the profile allows) → all converted; null: nothing left to try.
     * Already converted by the server (only the sound, say) → all converted.
     */
    fun nextFallback(plan: MediaPlan, now: MediaFallback): MediaFallback? = when (now) {
        MediaFallback.NONE -> if (plan.method == PlayMethod.TRANSCODE) MediaFallback.CONVERT else MediaFallback.REMUX
        MediaFallback.REMUX -> MediaFallback.CONVERT
        MediaFallback.CONVERT -> null
    }

    /**
     * The subtitle asked for [version] when it is asked another way ([fallback]): a picture subtitle (PGS, DVD) would
     * be burnt in on the NAS (the hardest work for it), so none then; played directly Media3 shows it itself. [chosen]
     * null: the server's default, which may be such a picture subtitle.
     */
    fun subtitleFor(version: MediaVersion?, chosen: Int?, fallback: MediaFallback): Int? {
        if (fallback == MediaFallback.NONE || version == null) return chosen
        val index = chosen ?: version.defaultSubtitle ?: return chosen
        val picture = version.subtitles.firstOrNull { it.index == index }?.picture == true
        return if (picture) MediaPlay.SUBTITLES_OFF else chosen
    }

    /** What the file is, for the note when nothing plays: "HEVC Main 10 10 Bit · DTS 5.1". */
    fun codecs(version: MediaVersion, audioIndex: Int?): String? {
        val v = version.video?.let { s ->
            listOfNotNull(s.codec?.uppercase(java.util.Locale.ROOT), s.profile, s.bitDepth?.takeIf { it > 8 }?.let { "$it Bit" }).joinToString(" ").ifEmpty { null }
        }
        val sound = (version.audio.firstOrNull { it.index == audioIndex } ?: version.audio.firstOrNull { it.index == version.defaultAudio } ?: version.audio.firstOrNull())
            ?.let { MediaTracks.audioLabel(it) }
        return listOfNotNull(v, sound, version.container?.uppercase(java.util.Locale.ROOT)).joinToString(" · ").ifEmpty { null }
    }

    /** A player fault worth another way (the file, its codec or the server's answer); a lost connection is not. */
    fun fallsBackOn(trouble: MediaPlayerTrouble): Boolean = trouble != MediaPlayerTrouble.NETWORK

    /** Where a seek by [deltaMs] from [positionMs] lands, inside the video (its [durationMs]; 0: unknown). */
    fun seekTarget(positionMs: Long, deltaMs: Long, durationMs: Long): Long {
        val to = (positionMs + deltaMs).coerceAtLeast(0)
        return if (durationMs > 0) to.coerceAtMost((durationMs - 500).coerceAtLeast(0)) else to
    }

    /** Which third of the picture a tap is in: -1 left (back), 1 right (ahead), 0 the middle. */
    fun zone(x: Float, width: Float): Int = when {
        width <= 0f -> 0
        x < width / 3f -> -1
        x > width * 2f / 3f -> 1
        else -> 0
    }

    /** Swiping up by [dy] px (negative: up) over a height of [height] px changes a level of 0..1 by as much. */
    fun swipe(level: Float, dy: Float, height: Float): Float {
        if (height <= 0f) return level.coerceIn(0f, 1f)
        return (level - dy / height).coerceIn(0f, 1f)
    }

    /** The window's brightness for a swipe: never quite dark (0.02), the system's value (negative) read as half. */
    fun brightness(current: Float, dy: Float, height: Float): Float =
        swipe(if (current < 0f) 0.5f else current, dy, height).coerceAtLeast(0.02f)

    /** The step of the sound (0..[max]) for a level of 0..1. */
    fun volumeStep(level: Float, max: Int): Int = (level * max + 0.5f).toInt().coerceIn(0, max.coerceAtLeast(0))

    /** Picture-in-picture takes ratios from 1:2.39 to 2.39:1; a video outside is shown in the nearest one. */
    fun pipRatio(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 16 to 9
        val r = width.toDouble() / height
        return when {
            r > MAX_PIP -> 239 to 100
            r < 1 / MAX_PIP -> 100 to 239
            else -> width to height
        }
    }

    private const val MAX_PIP = 2.39

    /** The bit rate to ask for: [base] of the quality setting, held down to the limit on mobile data when it applies. */
    fun capped(base: Long, limit: Long?): Long = if (limit != null && limit < base) limit else base

    /**
     * The limit on mobile data for [source]: on mobile data and not at home ([MediaPhoneLogic.mobileLimit]), only where
     * the NAS can convert (Jellyfin) and the source is not set to "Immer Original".
     */
    fun limit(source: MediaSource, cellular: Boolean, atHome: Boolean?, cap: Long?): Long? =
        MediaPhoneLogic.mobileLimit(cellular, atHome, cap)?.takeIf { source.kind.canConvert && !source.alwaysOriginal }

    /** How the phone is connected: at home, away on mobile data, away (Wi-Fi of a hotel); null: not known yet. */
    fun net(atHome: Boolean?, cellular: Boolean): MediaNet? = when {
        atHome == true -> MediaNet.HOME
        cellular -> MediaNet.CELLULAR
        atHome == false -> MediaNet.AWAY
        else -> null
    }

    /**
     * Whether the NAS converts the picture (not only the sound or the container): a reason about the video, the
     * subtitle (burnt in) or the bit rate; a conversion without reasons counts as one of the picture.
     */
    fun pictureConverted(plan: MediaPlan): Boolean {
        if (!plan.converted) return false
        val reasons = plan.version.transcodeReasons
        if (reasons.isEmpty()) return true
        return reasons.any { r -> PICTURE_REASONS.any { r.startsWith(it) } }
    }

    private val PICTURE_REASONS = listOf("Video", "Subtitle", "ContainerBitrate", "RefFrames", "Anamorphic", "Interlaced", "UnknownVideo", "DirectPlayError")

    /** The info line of [plan] (a share's over [shareVia]). */
    fun line(
        plan: MediaPlan,
        net: MediaNet?,
        measuredBps: Long?,
        caps: JellyfinDeviceCaps,
        canConvert: Boolean,
        limitBps: Long?,
        askedBps: Long,
        shareVia: String? = null,
    ): MediaPlayLine {
        val measured = measuredBps.takeIf { net != MediaNet.HOME }
        if (shareVia != null) return MediaPlayLine(net, measured, share = shareVia, video = MediaQuality.videoLabel(plan.version.video))
        val picture = pictureConverted(plan)
        val audio = plan.version.audio.firstOrNull { it.index == plan.audioIndex }
        val audioTo = audio?.let { MediaPhoneLogic.audioNote(it, caps) }?.takeIf { it.converted && canConvert && plan.converted }?.let { "AAC ${it.channels.orEmpty()}".trim() }
        val to = (plan.bitrate ?: askedBps).takeIf { it in 1 until MediaQuality.ORIGINAL_BPS }
        val limited = limitBps?.takeIf { picture && to != null && to <= it }
        return MediaPlayLine(
            net = net,
            measuredBps = measured,
            video = if (picture) null else MediaQuality.videoLabel(plan.version.video),
            convertedBps = if (picture) to else null,
            converted = picture,
            audioTo = if (picture) null else audioTo,
            limitBps = limited,
        )
    }

    /** The titles after [at] in the queue ("Nächste Folgen" under the picture), at most [max]. */
    fun nextUp(queue: MediaQueue, at: Int, max: Int = 8): List<Pair<Int, MediaTitle>> =
        queue.items.withIndex().filter { it.index > at }.take(max).map { it.index to it.value }

    /** "Station Eisvogel · S2 E4 · Die Brücke" for an episode, else the title's name. */
    fun name(t: MediaTitle): String {
        val series = t.seriesName
        if (t.kind != MediaTitle.Kind.EPISODE || series == null) return t.name
        val se = listOfNotNull(t.season?.let { "S$it" }, t.episode?.let { "E$it" }).joinToString(" ")
        return listOf(series, se, t.name).filter { it.isNotBlank() }.joinToString(" · ")
    }

    /** The countdown to the next episode after the end of [at]; null: none (a film, the last episode). */
    fun countdownAfter(queue: MediaQueue, at: Int): Int? =
        NEXT_SECONDS.takeIf { at < queue.items.size - 1 && queue.items.any { it.kind == MediaTitle.Kind.EPISODE } }
}

/**
 * Watches one video in the phone's player, a step at a time: with play on and no first picture for
 * [MediaPlayerLogic.firstPictureMs], or after the start [MediaPlayerLogic.STALLS] times loading longer than
 * [MediaPlayerLogic.STALL_MS], it is asked again another way. Paused or waiting (the note on the data volume, loading
 * ahead) does not count. [last]: the last way (converted on the NAS): a longer wait for the first picture; after it
 * only „schafft die Umwandlung nicht“ is left. Pure, unit-tested.
 */
class MediaStallWatch(private val last: Boolean = false) {
    private var waited = 0L
    private var stalled = 0L
    private var counted = false

    /** The times it hung after the start. */
    var stalls = 0
        private set

    /** Waited for the first picture so far. */
    val waitedMs: Long get() = waited

    /**
     * One step of [stepMs]: [playing] play is on, [loading] / [ready] the player's state, [pictured] the first picture
     * came, [net] home or away. True: ask for it another way now.
     */
    fun step(stepMs: Long, playing: Boolean, loading: Boolean, ready: Boolean, pictured: Boolean, net: MediaNet?): Boolean {
        if (!pictured) {
            if (playing && (loading || ready)) waited += stepMs
            return waited >= MediaPlayerLogic.firstPictureMs(net, last)
        }
        if (playing && loading) {
            stalled += stepMs
            if (stalled >= MediaPlayerLogic.STALL_MS && !counted) {
                counted = true
                stalls++
                return stalls >= MediaPlayerLogic.STALLS
            }
        } else {
            stalled = 0
            counted = false
        }
        return false
    }
}
