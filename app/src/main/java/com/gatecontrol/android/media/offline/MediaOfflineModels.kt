package com.gatecontrol.android.media.offline

import com.gatecontrol.android.media.MediaQuality
import com.gatecontrol.android.media.MediaSideSubtitle
import com.gatecontrol.android.media.MediaStream
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.MediaVersion
import com.gatecontrol.android.media.jellyfin.JellyfinLibrary
import java.security.MessageDigest
import java.util.Locale

/**
 * The quality of a download (question 22 a): the original file, or converted on the NAS before it is loaded. The
 * steps are those of the player ([MediaQuality.LADDER]): 1080p at 8 Mbit/s, 720p at 4, 480p at 1,5.
 */
enum class OfflineQuality(val wire: String, val videoBps: Long?, val maxWidth: Int, val maxHeight: Int) {
    ORIGINAL("original", null, 0, 0),
    P1080("1080p", 8_000_000, 1920, 1080),
    P720("720p", 4_000_000, 1280, 720),
    P480("480p", 1_500_000, 854, 480),
    ;

    val converted: Boolean get() = videoBps != null

    /** "1080p" / "Original" as the lists show it. */
    val label: String get() = if (this == ORIGINAL) "Original" else wire

    companion object {
        val DEFAULT = P1080

        fun fromWire(value: String?): OfflineQuality = entries.firstOrNull { it.wire == value } ?: DEFAULT
    }
}

/** Where a download is. "Wartet auf WLAN" is [QUEUED] while the phone has no Wi-Fi. */
enum class OfflineState(val wire: String) {
    QUEUED("queued"),
    LOADING("loading"),
    READY("ready"),
    FAILED("failed"),
    ;

    companion object {
        fun fromWire(value: String?): OfflineState? = entries.firstOrNull { it.wire == value }
    }
}

/** Why a download stopped for good (until "Nochmal"). */
enum class OfflineFailure(val wire: String) {
    /** It would go over the storage limit of Medien › Quellen. */
    LIMIT("limit"),
    /** The phone has no room for it. */
    SPACE("space"),
    /** Jellyfin does not let this user download. */
    NOT_ALLOWED("not_allowed"),
    /** The title or its source is gone. */
    GONE("gone"),
    /** The phone is signed out of the server. */
    SIGNED_OUT("signed_out"),
    FAILED("failed"),
    ;

    companion object {
        fun fromWire(value: String?): OfflineFailure? = entries.firstOrNull { it.wire == value }
    }
}

/** A subtitle file loaded beside the video (WebVTT, encrypted like it). */
data class OfflineSubtitle(val index: Int, val language: String?, val label: String?, val forced: Boolean = false)

/**
 * One film or episode for offline: the title as it was listed, what was asked ([quality], the sound), where the
 * download is, what the file holds ([version]: the original's streams, or those of the converted file) and the
 * progress watched offline ([positionMs], [played], [playedAt]; [dirty]: not yet told to Jellyfin). [key]: the
 * file's own key (32 bytes), kept only in the encrypted index.
 */
data class OfflineItem(
    val id: String,
    val sourceId: String,
    val title: MediaTitle,
    val quality: OfflineQuality,
    val state: OfflineState = OfflineState.QUEUED,
    val failure: OfflineFailure? = null,
    val addedAt: Long = 0,
    /** Bytes loaded so far and about how many there will be (converted: estimated). */
    val loaded: Long = 0,
    val expected: Long = 0,
    /** Bytes on the phone once ready (video, subtitles, poster). */
    val size: Long = 0,
    val version: MediaVersion? = null,
    val subtitles: List<OfflineSubtitle> = emptyList(),
    val audioIndex: Int? = null,
    val positionMs: Long = 0,
    val played: Boolean = false,
    val playedAt: Long = 0,
    val dirty: Boolean = false,
    val key: ByteArray = ByteArray(0),
    /**
     * A converted download was cut off: the NAS converts as it goes and cannot go on at a place, so it starts over
     * („beginnt von vorn“) when it is loaded next. Cleared once it loads again.
     */
    val restarted: Boolean = false,
) {
    val itemId: String get() = title.id
    val ready: Boolean get() = state == OfflineState.READY
    val active: Boolean get() = state == OfflineState.QUEUED || state == OfflineState.LOADING

    /** 0..1 of the download. */
    val progress: Float get() = if (expected > 0) (loaded.toFloat() / expected).coerceIn(0f, 0.99f) else 0f

    /** The title with the progress watched here, when that is newer than the one it was listed with. */
    fun titleNow(): MediaTitle = if (playedAt > (title.playedAt ?: 0)) title.copy(positionMs = if (played) 0 else positionMs, played = played, playedAt = playedAt) else title

    override fun equals(other: Any?): Boolean = other is OfflineItem && other.id == id && other.state == state && other.loaded == loaded &&
        other.title == title && other.quality == quality && other.failure == failure && other.size == size && other.expected == expected &&
        other.positionMs == positionMs && other.played == played && other.playedAt == playedAt && other.dirty == dirty &&
        other.version == version && other.subtitles == subtitles && other.addedAt == addedAt && other.audioIndex == audioIndex && other.key.contentEquals(key) &&
        other.restarted == restarted

    override fun hashCode(): Int = id.hashCode() * 31 + state.hashCode()

    override fun toString(): String = "OfflineItem($id, ${state.wire}, ${quality.wire}, $loaded/$expected)"
}

/** A row of the page "Offline": a film, or the episodes of one season of a series together ("S2 F5–F6"). */
data class OfflineGroup(val key: String, val items: List<OfflineItem>) {
    val first: OfflineItem get() = items.first()
    val episodes: Boolean get() = first.title.kind == MediaTitle.Kind.EPISODE
    val size: Long get() = items.sumOf { if (it.ready) it.size else it.loaded }
    val ready: Boolean get() = items.all { it.ready }
    val loading: OfflineItem? get() = items.firstOrNull { it.state == OfflineState.LOADING }
    val waiting: Boolean get() = items.any { it.state == OfflineState.QUEUED } && loading == null
    val failed: OfflineItem? get() = items.firstOrNull { it.state == OfflineState.FAILED }

    /** 0..1 over all its items (by bytes). */
    val progress: Float get() {
        val expected = items.sumOf { if (it.ready) it.size else it.expected }
        if (expected <= 0) return 0f
        return (items.sumOf { if (it.ready) it.size else it.loaded }.toFloat() / expected).coerceIn(0f, 0.99f)
    }
}

/** The rules of the downloads, kept apart from the files and the network. Pure, unit-tested. */
object MediaOfflineLogic {
    /** The scheme of the player's URIs of a download (`gcoffline://<id>/video`, `…/sub-3`). */
    const val SCHEME = "gcoffline"

    /** The labels of the files' encryption (the format of Drive's vault folders, its own labels). */
    const val PREFIX = "gcmedia-v1"

    /** The storage limit's steps (GB) in Medien › Quellen; 20 GB by default (mockup 7). */
    val LIMITS_GB: List<Int> = listOf(5, 10, 20, 50, 100)
    const val DEFAULT_LIMIT_GB = 20

    /** Room left on the phone besides a download. */
    const val SPARE_BYTES = 1_000_000_000L

    /** Watched this far, a title counts as seen (as Jellyfin: 90 %). */
    const val PLAYED_SHARE = 0.9

    /** Below this a place is not kept (as Jellyfin: the first 5 %). */
    const val MIN_RESUME_SHARE = 0.05

    /** The id of [itemId] of [sourceId] on this phone (a file name): 32 hex characters. */
    fun idOf(sourceId: String, itemId: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest("$sourceId|$itemId".toByteArray(Charsets.UTF_8))
        return d.copyOf(16).joinToString("") { "%02x".format(it) }
    }

    fun gb(gb: Int): Long = gb * 1_000_000_000L

    /**
     * About how many bytes [title] needs in [quality]: the original's size (or bit rate × length), converted the step
     * and the sound × length. Null without a length.
     */
    fun estimate(quality: OfflineQuality, runtimeMs: Long?, version: MediaVersion?): Long? {
        val ms = runtimeMs?.takeIf { it > 0 } ?: version?.runtimeMs?.takeIf { it > 0 }
        if (quality == OfflineQuality.ORIGINAL) {
            version?.size?.let { return it }
            val bps = version?.bitrate ?: return null
            return ms?.let { bps / 8 * it / 1000 }
        }
        ms ?: return null
        val bps = quality.videoBps!! + JellyfinLibrary.DOWNLOAD_AUDIO_BPS
        return (bps / 8 * ms / 1000 * 105) / 100
    }

    /** The original is no smaller than the step: converting gains nothing (it is then loaded as it is). */
    fun convertGains(quality: OfflineQuality, version: MediaVersion?): Boolean {
        val step = quality.videoBps ?: return false
        val bps = version?.bitrate ?: return true
        return bps > step + JellyfinLibrary.DOWNLOAD_AUDIO_BPS
    }

    /** Whether [more] bytes still fit under [limit] with [used] taken and [free] left on the phone. */
    fun fits(more: Long, used: Long, limit: Long, free: Long): OfflineFailure? = when {
        used + more > limit -> OfflineFailure.LIMIT
        free in 0 until more + SPARE_BYTES -> OfflineFailure.SPACE
        else -> null
    }

    /** "7,4 / 20 GB". */
    fun usedText(used: Long, limit: Long, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.1f / %d GB", used / 1e9, limit / 1_000_000_000L)

    /** "3,1 GB" / "850 MB". */
    fun sizeText(bytes: Long, locale: Locale = Locale.getDefault()): String =
        if (bytes >= 1_000_000_000L) String.format(locale, "%.1f GB", bytes / 1e9) else "${(bytes / 1_000_000L).coerceAtLeast(1)} MB"

    /** The place and "Gesehen" kept after watching to [positionMs] of [durationMs] offline. */
    fun watched(positionMs: Long, durationMs: Long): Pair<Long, Boolean> {
        if (durationMs <= 0) return positionMs.coerceAtLeast(0) to false
        val share = positionMs.toDouble() / durationMs
        return when {
            share >= PLAYED_SHARE -> 0L to true
            share < MIN_RESUME_SHARE -> 0L to false
            else -> positionMs to false
        }
    }

    /** The offline progress goes to Jellyfin unless the server has a newer one (watched on the TV meanwhile). */
    fun shouldSend(item: OfflineItem, serverPlayedAt: Long?): Boolean = item.dirty && (serverPlayedAt == null || serverPlayedAt < item.playedAt)

    /** The streams of a converted file: H.264 at the step, the one AAC stereo sound, the subtitles beside it. */
    fun convertedVersion(original: MediaVersion, quality: OfflineQuality, audio: MediaStream?, subtitles: List<OfflineSubtitle>, runtimeMs: Long?): MediaVersion {
        val video = original.video
        val h = minOf(quality.maxHeight, video?.height ?: quality.maxHeight)
        val w = video?.width?.takeIf { it > 0 && (video.height ?: 0) > 0 }?.let { it * h / video.height!! } ?: quality.maxWidth
        val streams = buildList {
            add(MediaStream(0, MediaStream.Type.VIDEO, codec = "h264", width = w, height = h, bitrate = quality.videoBps))
            add(
                MediaStream(
                    1,
                    MediaStream.Type.AUDIO,
                    codec = "aac",
                    language = audio?.language,
                    title = audio?.title,
                    channels = 2,
                    isDefault = true,
                    bitrate = JellyfinLibrary.DOWNLOAD_AUDIO_BPS,
                ),
            )
            subtitles.forEach { s -> add(MediaStream(s.index, MediaStream.Type.SUBTITLE, codec = "webvtt", language = s.language, title = s.label, external = true, textual = true, forced = s.forced)) }
        }
        return MediaVersion(
            id = original.id,
            container = "ts",
            bitrate = quality.videoBps!! + JellyfinLibrary.DOWNLOAD_AUDIO_BPS,
            runtimeMs = runtimeMs ?: original.runtimeMs,
            streams = streams,
            defaultAudio = 1,
            supportsDirectPlay = true,
        )
    }

    /**
     * The text subtitles loaded beside the video: of the original only those that are files of their own (the
     * others are in the file), of a converted one all text subtitles except the one burnt in.
     */
    fun sideSubtitles(version: MediaVersion, quality: OfflineQuality, burnt: Int?): List<OfflineSubtitle> =
        version.subtitles.filter { it.textual && (quality.converted || it.external) && it.index != burnt }
            .take(MAX_SUBTITLES)
            .map { OfflineSubtitle(it.index, it.language, it.displayTitle ?: it.title, it.forced) }

    /** The URI the player reads part [part] of download [id] by. */
    fun uri(id: String, part: String): String = "$SCHEME://$id/$part"

    const val VIDEO = "video"

    fun subPart(index: Int): String = "sub-$index"

    /** [uri]'s download id and part; null: not a download's URI. */
    fun parse(uri: String): Pair<String, String>? {
        val rest = uri.removePrefix("$SCHEME://").takeIf { it != uri } ?: return null
        val id = rest.substringBefore('/')
        val part = rest.substringAfter('/', "")
        if (!Regex("^[0-9a-f]{32}$").matches(id)) return null
        if (part != VIDEO && !Regex("^sub-\\d{1,4}$").matches(part)) return null
        return id to part
    }

    /** The side subtitles of a ready download for the player (only offered, as a share's files). */
    fun sideOf(item: OfflineItem): List<MediaSideSubtitle> = item.subtitles.map { s ->
        MediaSideSubtitle(s.index, uri(item.id, subPart(s.index)), "text/vtt", s.language, s.label, default = false, forced = s.forced)
    }

    /** The rows of the page: films alone, episodes by series and season, the newest first. */
    fun groups(items: List<OfflineItem>): List<OfflineGroup> =
        items.sortedByDescending { it.addedAt }
            .groupBy { i -> if (i.title.kind == MediaTitle.Kind.EPISODE && i.title.seriesId != null) "s:${i.sourceId}:${i.title.seriesId}:${i.title.season}" else "m:${i.id}" }
            .map { (k, list) -> OfflineGroup(k, list.sortedWith(compareBy({ it.title.season ?: 0 }, { it.title.episode ?: 0 }))) }

    /** "S2 F5–F6" / "S2 F5" of the episodes of a group; null for a film. */
    fun episodeRange(group: OfflineGroup, season: String = "S", episode: String = "F"): String? {
        if (!group.episodes) return null
        val s = group.first.title.season
        val numbers = group.items.mapNotNull { it.title.episode }.sorted()
        val head = s?.let { "$season$it" }
        val tail = when {
            numbers.isEmpty() -> null
            numbers.size == 1 -> "$episode${numbers.first()}"
            else -> "$episode${numbers.first()}–$episode${numbers.last()}"
        }
        return listOfNotNull(head, tail).joinToString(" ").ifEmpty { null }
    }

    private const val MAX_SUBTITLES = 12
}
