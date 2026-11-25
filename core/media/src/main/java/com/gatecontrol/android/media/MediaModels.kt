package com.gatecontrol.android.media

/**
 * What a media source is. Jellyfin first (posters, progress, converting on the NAS); SMB and WebDAV shares behind the
 * same [MediaLibrary] (only the original, progress kept on the device). NFS is planned, not offered.
 */
enum class MediaSourceKind(val wire: String) {
    JELLYFIN("jellyfin"),
    SMB("smb"),
    WEBDAV("webdav"),
    ;

    /** Only Jellyfin converts on the NAS; a share always plays the original. */
    val canConvert: Boolean get() = this == JELLYFIN

    /** A folder of the NAS or a PC (SMB, WebDAV): file names and pictures beside them, no server of its own. */
    val share: Boolean get() = this == SMB || this == WEBDAV

    companion object {
        fun fromWire(value: String?): MediaSourceKind? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * A source of Medien › Quellen. [address]: the server's base URL (`http://192.168.1.20:8096`), a share's
 * `smb://nas/filme` or `https://192.168.1.20:5006/filme`; [token] the access of this device (a Jellyfin access token),
 * [password] a share's password — secrets: never logged, kept only in the encrypted storage.
 */
data class MediaSource(
    val id: String,
    val kind: MediaSourceKind,
    /** The server's own name ("DS918+"). */
    val name: String,
    val address: String,
    val userId: String? = null,
    val userName: String? = null,
    val token: String? = null,
    /** The server's id (Jellyfin `ServerId`): the same server found again is not added twice. */
    val serverId: String? = null,
    /** "Immer Original": never converted, also away (per source, decision 10). */
    val alwaysOriginal: Boolean = false,
    /** A share's password (with [userName]); null: as guest. */
    val password: String? = null,
    /**
     * The same share set up once more with the other protocol (SMB ↔ WebDAV): the id of that source. Both show as
     * one; at home the app takes SMB, away WebDAV.
     */
    val pairWith: String? = null,
) {
    /** Can be asked: a Jellyfin source signed in, a share always (its password goes along on each connection). */
    val ready: Boolean get() = kind.share || token != null

    override fun toString(): String =
        "MediaSource($id, ${kind.wire}, $name, token=${if (token != null) "set" else "none"}, password=${if (password != null) "set" else "none"})"
}

/** A film, a series, a season, an episode or another video of a source. */
data class MediaTitle(
    val id: String,
    val sourceId: String,
    val kind: Kind,
    val name: String,
    val year: Int? = null,
    val runtimeMs: Long? = null,
    /** Where it was left (Jellyfin's user data); 0: not started. */
    val positionMs: Long = 0,
    val played: Boolean = false,
    val seriesId: String? = null,
    val seriesName: String? = null,
    val seasonId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    /** The age rating as the server has it ("FSK-12", "12"). */
    val rating: String? = null,
    /** A series: how many seasons. */
    val childCount: Int? = null,
    /** Unwatched episodes of a series or season. */
    val unplayed: Int? = null,
    /** The poster: the item whose primary picture it is (an episode may use its series') and the picture's tag. */
    val posterId: String? = null,
    val posterTag: String? = null,
    /** The wide picture behind the detail page. */
    val backdropId: String? = null,
    val backdropTag: String? = null,
    /** When it came to the library (ms since 1970), for "Zuletzt hinzugefügt". */
    val addedAt: Long? = null,
    /** When it was watched last (ms since 1970): "Weiterschauen" of several sources in one row. */
    val playedAt: Long? = null,
    /** From an SMB or WebDAV share (the "NAS" mark): only its file name and the picture beside it. */
    val fromShare: Boolean = false,
) {
    enum class Kind { MOVIE, SERIES, SEASON, EPISODE, VIDEO }

    val playable: Boolean get() = kind == Kind.MOVIE || kind == Kind.EPISODE || kind == Kind.VIDEO

    /** Started and not finished: "Weiter ab …". */
    val resumable: Boolean get() = positionMs > 0 && !played

    /** The time left ("noch 18 Min."); null without a length or position. */
    val leftMs: Long? get() = runtimeMs?.takeIf { it > 0 && positionMs > 0 }?.let { (it - positionMs).coerceAtLeast(0) }

    /** Watched share 0..1 for the bar of a card. */
    val progress: Float get() = runtimeMs?.takeIf { it > 0 }?.let { (positionMs.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
}

/** One page of a long list (Filme A–Z). [total]: how many there are in all. */
data class MediaPage(val items: List<MediaTitle>, val total: Int, val start: Int) {
    /** The start of the next page; null: all loaded. */
    val next: Int? get() = (start + items.size).takeIf { items.isNotEmpty() && it < total }
}

/**
 * How "Filme" or "Serien" are listed (the phone's chips): A–Z, recently added or by year, only the unwatched ones,
 * one genre.
 */
data class MediaOrder(val sort: Sort = Sort.NAME, val unplayed: Boolean = false, val genre: String? = null) {
    enum class Sort { NAME, ADDED, YEAR }

    /** A–Z of everything: the library's own pages ([MediaLibrary.movies], [MediaLibrary.series]). */
    val plain: Boolean get() = sort == Sort.NAME && !unplayed && genre == null
}

/** A stream in a media file (ffprobe's view, as Jellyfin lists it). */
data class MediaStream(
    /** Jellyfin's stream index (for AudioStreamIndex / SubtitleStreamIndex). */
    val index: Int,
    val type: Type,
    val codec: String? = null,
    val language: String? = null,
    val title: String? = null,
    /** The server's own label ("Deutsch - DTS - 5.1 - Standard"). */
    val displayTitle: String? = null,
    val channels: Int? = null,
    val external: Boolean = false,
    val textual: Boolean = false,
    val isDefault: Boolean = false,
    val forced: Boolean = false,
    val width: Int? = null,
    val height: Int? = null,
    /** "SDR", "HDR" (Jellyfin `VideoRange`) and the type ("HDR10", "DOVI", Jellyfin `VideoRangeType`). */
    val videoRange: String? = null,
    val videoRangeType: String? = null,
    val bitrate: Long? = null,
    /** A subtitle the player loads beside the video: how (`External`, `Embed`, `Encode`, `Hls`) and from where. */
    val deliveryMethod: String? = null,
    val deliveryUrl: String? = null,
) {
    enum class Type { VIDEO, AUDIO, SUBTITLE, OTHER }

    /** A picture subtitle (PGS, DVD): converting means burning it in. */
    val picture: Boolean get() = type == Type.SUBTITLE && !textual
}

/** One version of a title (Jellyfin's media source): its file, length, bit rate and streams. */
data class MediaVersion(
    val id: String,
    val container: String? = null,
    val bitrate: Long? = null,
    val runtimeMs: Long? = null,
    val streams: List<MediaStream> = emptyList(),
    val defaultAudio: Int? = null,
    val defaultSubtitle: Int? = null,
    val supportsDirectPlay: Boolean = false,
    val supportsDirectStream: Boolean = false,
    val supportsTranscoding: Boolean = false,
    /** The converting stream the server offers (relative URL with its own `api_key`), when it would convert. */
    val transcodingUrl: String? = null,
    val transcodingProtocol: String? = null,
    /** Why the server would convert (`ContainerNotSupported`, `AudioCodecNotSupported`, `SubtitleCodecNotSupported` …). */
    val transcodeReasons: List<String> = emptyList(),
) {
    val video: MediaStream? get() = streams.firstOrNull { it.type == MediaStream.Type.VIDEO }
    val audio: List<MediaStream> get() = streams.filter { it.type == MediaStream.Type.AUDIO }
    val subtitles: List<MediaStream> get() = streams.filter { it.type == MediaStream.Type.SUBTITLE }
}

/** The detail page of a title: the title and its versions (the first is played). */
data class MediaDetail(val title: MediaTitle, val versions: List<MediaVersion>) {
    val version: MediaVersion? get() = versions.firstOrNull()
}

/** What to play: the title, from where, which sound and subtitle ([MediaPlay.SUBTITLES_OFF]: none), how fast at most. */
data class MediaPlay(
    val itemId: String,
    val versionId: String? = null,
    val startMs: Long = 0,
    val audioIndex: Int? = null,
    val subtitleIndex: Int? = null,
    /** At most this many bit/s; [MediaQuality.ORIGINAL_BPS]: the original whenever the TV can play it. */
    val maxBitrate: Long = MediaQuality.ORIGINAL_BPS,
) {
    companion object {
        const val SUBTITLES_OFF = -1
    }
}

/** How the server sends it: the file as it is, remuxed, or converted on the NAS. */
enum class PlayMethod(val wire: String) {
    DIRECT_PLAY("DirectPlay"),
    DIRECT_STREAM("DirectStream"),
    TRANSCODE("Transcode"),
}

/**
 * A subtitle the player loads beside the video (a URL of the server, or a file beside a share's video). [default]:
 * shown at once (Jellyfin's choice); a share's file is only offered. [forced]: only the foreign parts.
 */
data class MediaSideSubtitle(
    val index: Int,
    val url: String,
    val mime: String,
    val language: String?,
    val label: String?,
    val default: Boolean = true,
    val forced: Boolean = false,
)

/**
 * The answer of the server for [MediaPlay]: the URL the player streams ([hls]: an HLS playlist), how, and the session
 * the progress goes to. [audioIndex] / [subtitleIndex]: what was asked and is played now.
 */
data class MediaPlan(
    val itemId: String,
    val url: String,
    val method: PlayMethod,
    val version: MediaVersion,
    val playSessionId: String?,
    val hls: Boolean,
    val audioIndex: Int?,
    val subtitleIndex: Int?,
    /** Converting: the bit rate asked for. */
    val bitrate: Long? = null,
    val sideSubtitles: List<MediaSideSubtitle> = emptyList(),
) {
    val converted: Boolean get() = method == PlayMethod.TRANSCODE
}

/** Where a playback is, for the server ("Weiterschauen" is kept in Jellyfin, decision 11). */
data class MediaProgress(
    val plan: MediaPlan,
    val positionMs: Long,
    val paused: Boolean,
    /** The length the player found (0: unknown); a share knows it only from there. */
    val durationMs: Long = 0,
)

/** The answer of a call to a source. */
sealed interface MediaResult<out T> {
    data class Ok<T>(val value: T) : MediaResult<T>
    /** No connection (not reachable, timeout). */
    data object Offline : MediaResult<Nothing>
    /** The access is no longer valid (401 / 403): sign in again. */
    data object Unauthorized : MediaResult<Nothing>
    data object NotFound : MediaResult<Nothing>
    data class Failed(val code: Int) : MediaResult<Nothing>

    fun valueOrNull(): T? = (this as? Ok<T>)?.value
}

inline fun <T, R> MediaResult<T>.map(f: (T) -> R): MediaResult<R> = when (this) {
    is MediaResult.Ok -> MediaResult.Ok(f(value))
    is MediaResult.Offline -> this
    is MediaResult.Unauthorized -> this
    is MediaResult.NotFound -> this
    is MediaResult.Failed -> this
}

/** Who asks (the `Authorization: MediaBrowser …` of Jellyfin): the app, the device, its stable id, the version. */
data class MediaClientInfo(
    val client: String,
    val device: String,
    val deviceId: String,
    val version: String,
)

/**
 * One source as the TV goes through it: Jellyfin, or an SMB or WebDAV share
 * ([com.gatecontrol.android.media.share.ShareLibrary]: lists from its folders, [play] always the original, progress on
 * the device).
 */
interface MediaLibrary {
    val source: MediaSource

    suspend fun resume(limit: Int = 12): MediaResult<List<MediaTitle>>
    suspend fun latest(limit: Int = 16): MediaResult<List<MediaTitle>>
    suspend fun movies(start: Int, limit: Int): MediaResult<MediaPage>
    suspend fun series(start: Int, limit: Int): MediaResult<MediaPage>
    suspend fun seasons(seriesId: String): MediaResult<List<MediaTitle>>
    suspend fun episodes(seriesId: String, seasonId: String? = null): MediaResult<List<MediaTitle>>
    suspend fun detail(itemId: String): MediaResult<MediaDetail>

    /**
     * "Filme" ([movies]) or "Serien" in [order], page by page. Here: the whole list ordered on the device (a share has
     * its index there); Jellyfin asks the server.
     */
    suspend fun list(movies: Boolean, start: Int, limit: Int, order: MediaOrder): MediaResult<MediaPage> {
        if (order.plain) return if (movies) movies(start, limit) else series(start, limit)
        return (if (movies) movies(0, ALL) else series(0, ALL)).map { all ->
            val items = MediaLogic.ordered(all.items, order)
            MediaPage(items.drop(start).take(limit), items.size, start)
        }
    }

    /** Films and series whose name holds [text] (the search over all sources); here from the whole lists. */
    suspend fun search(text: String, limit: Int): MediaResult<List<MediaTitle>> {
        val movies = movies(0, ALL)
        if (movies !is MediaResult.Ok) return movies.map { emptyList() }
        val series = series(0, ALL).valueOrNull()?.items.orEmpty()
        return MediaResult.Ok(MediaLogic.search(movies.value.items + series, text).take(limit))
    }

    /** The genres of "Filme" ([movies]) or "Serien" for the chip "Genre", A–Z. */
    suspend fun genres(movies: Boolean): MediaResult<List<String>> =
        (if (movies) movies(0, ALL) else series(0, ALL)).map { MediaLogic.genres(it.items) }

    /** How [play] is sent (the server decides with the device profile and the bit rate). */
    suspend fun play(play: MediaPlay): MediaResult<MediaPlan>

    suspend fun started(p: MediaProgress)
    suspend fun progress(p: MediaProgress)
    suspend fun stopped(p: MediaProgress)
    suspend fun setPlayed(itemId: String, played: Boolean): MediaResult<Unit>

    /** The bit/s from the server to this device just now; null when it could not be measured. */
    suspend fun measure(): Long?

    /** The URL of a title's picture ([backdrop]: the wide one), at most [maxHeight] px high; null: it has none. */
    fun imageUrl(title: MediaTitle, backdrop: Boolean, maxHeight: Int): String?

    /** The headers the player and the picture loads send (the access); never logged. */
    fun headers(): Map<String, String>

    /** A picture that has no URL (a share's `poster.jpg` beside the film), at most a few MB; null: none. */
    suspend fun imageBytes(title: MediaTitle, backdrop: Boolean): ByteArray? = null

    /** A share's files for the player ([MediaPlan.url] is then a [com.gatecontrol.android.media.share.ShareUri]); null: HTTP. */
    fun files(): com.gatecontrol.android.media.share.ShareFs? = null

    /** The library is no longer used (the network or the access changed): its connection may go. */
    fun close() {}

    companion object {
        /** "All of it" for a list kept on the device (a share's index). */
        const val ALL = 2_000
    }
}
