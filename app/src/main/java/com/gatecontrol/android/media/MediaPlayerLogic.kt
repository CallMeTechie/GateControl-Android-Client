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
