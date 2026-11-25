package com.gatecontrol.android.media.jellyfin

import com.gatecontrol.android.media.MediaClientInfo
import com.gatecontrol.android.media.MediaDetail
import com.gatecontrol.android.media.MediaLibrary
import com.gatecontrol.android.media.MediaOrder
import com.gatecontrol.android.media.MediaPage
import com.gatecontrol.android.media.MediaPlan
import com.gatecontrol.android.media.MediaPlay
import com.gatecontrol.android.media.MediaProgress
import com.gatecontrol.android.media.MediaQuality
import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaSideSubtitle
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaStream
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.MediaVersion
import com.gatecontrol.android.media.PlayMethod
import com.gatecontrol.android.media.jellyfin.JellyfinJson.bool
import com.gatecontrol.android.media.jellyfin.JellyfinJson.objects
import com.gatecontrol.android.media.jellyfin.JellyfinJson.str
import com.gatecontrol.android.media.map
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * A Jellyfin server as a [MediaLibrary]: the rows of Medien (Weiterschauen, Zuletzt hinzugefügt, Filme A–Z, Serien),
 * a title's details, how it is played (direct or converted by [JellyfinProfile] and the bit rate) and the progress
 * ("Weiterschauen" lives in Jellyfin, decision 11).
 */
class JellyfinLibrary(
    override val source: MediaSource,
    http: OkHttpClient,
    private val info: MediaClientInfo,
    /** What the device plays itself (on the TV the HDMI sink's sound formats, on a phone its decoders). */
    private val caps: () -> JellyfinDeviceCaps = { JellyfinDeviceCaps() },
) : MediaLibrary {

    private val client = JellyfinClient(source.address, http, info, source.token)
    private val user: String get() = source.userId.orEmpty()

    override suspend fun resume(limit: Int): MediaResult<List<MediaTitle>> =
        client.get(
            "UserItems/Resume",
            mapOf("userId" to user, "limit" to limit, "mediaTypes" to "Video", "fields" to FIELDS, "enableUserData" to true, "enableImageTypes" to IMAGES),
        ).map { JellyfinJson.items(it, source.id) }

    override suspend fun latest(limit: Int): MediaResult<List<MediaTitle>> =
        client.get(
            "Items/Latest",
            mapOf("userId" to user, "limit" to limit, "includeItemTypes" to "Movie,Series", "fields" to FIELDS, "enableUserData" to true, "enableImageTypes" to IMAGES),
        ).map { JellyfinJson.items(it, source.id) }

    override suspend fun movies(start: Int, limit: Int): MediaResult<MediaPage> = page("Movie", start, limit)

    override suspend fun series(start: Int, limit: Int): MediaResult<MediaPage> = page("Series", start, limit)

    private suspend fun page(type: String, start: Int, limit: Int, order: MediaOrder = MediaOrder()): MediaResult<MediaPage> =
        client.get(
            "Items",
            mapOf(
                "userId" to user,
                "includeItemTypes" to type,
                "recursive" to true,
                "sortBy" to sortBy(order.sort),
                "sortOrder" to sortOrder(order.sort),
                "filters" to (if (order.unplayed) "IsUnplayed" else null),
                "genres" to order.genre,
                "startIndex" to start,
                "limit" to limit,
                "fields" to "$FIELDS,ChildCount",
                "enableUserData" to true,
                "enableTotalRecordCount" to true,
                "enableImageTypes" to IMAGES,
            ),
        ).map { e ->
            val items = JellyfinJson.items(e, source.id)
            MediaPage(items, JellyfinJson.total(e) ?: (start + items.size), start)
        }

    /** The server sorts and filters ("Zuletzt hinzugefügt", "Jahr", "Ungesehen", a genre) and pages. */
    override suspend fun list(movies: Boolean, start: Int, limit: Int, order: MediaOrder): MediaResult<MediaPage> =
        page(if (movies) "Movie" else "Series", start, limit, order)

    /** The server's own search over films and series. */
    override suspend fun search(text: String, limit: Int): MediaResult<List<MediaTitle>> {
        val term = text.trim()
        if (term.isEmpty()) return MediaResult.Ok(emptyList())
        return client.get(
            "Items",
            mapOf(
                "userId" to user,
                "searchTerm" to term,
                "includeItemTypes" to "Movie,Series",
                "recursive" to true,
                "limit" to limit,
                "fields" to "$FIELDS,ChildCount",
                "enableUserData" to true,
                "enableImageTypes" to IMAGES,
            ),
        ).map { JellyfinJson.items(it, source.id) }
    }

    override suspend fun genres(movies: Boolean): MediaResult<List<String>> =
        client.get(
            "Genres",
            mapOf("userId" to user, "includeItemTypes" to if (movies) "Movie" else "Series", "recursive" to true, "sortBy" to "SortName"),
        ).map { e ->
            val names = e?.takeIf { it.isJsonObject }?.asJsonObject?.objects("Items").orEmpty().mapNotNull { it.str("Name")?.trim()?.ifEmpty { null } }
            names.distinctBy { it.lowercase(java.util.Locale.ROOT) }
        }

    /** "Ordner": the person's libraries (`/UserViews`), those with video. */
    suspend fun views(): MediaResult<List<JellyfinFolder>> =
        client.get("UserViews", mapOf("userId" to user)).map { JellyfinJson.views(it) }

    /** "Ordner": what a library or folder holds, A–Z (at most [limit]); the page shows the folders first. */
    suspend fun children(parentId: String, limit: Int = FOLDER_LIMIT): MediaResult<JellyfinFolderPage> =
        client.get(
            "Items",
            mapOf(
                "userId" to user,
                "parentId" to parentId,
                "sortBy" to "SortName",
                "sortOrder" to "Ascending",
                "limit" to limit,
                "fields" to "$FIELDS,ChildCount",
                "enableUserData" to true,
                "enableTotalRecordCount" to true,
                "enableImageTypes" to IMAGES,
            ),
        ).map { JellyfinJson.folderPage(it, source.id) }

    override suspend fun seasons(seriesId: String): MediaResult<List<MediaTitle>> =
        client.get("Shows/$seriesId/Seasons", mapOf("userId" to user, "fields" to FIELDS, "enableUserData" to true))
            .map { JellyfinJson.items(it, source.id) }

    override suspend fun episodes(seriesId: String, seasonId: String?): MediaResult<List<MediaTitle>> =
        client.get(
            "Shows/$seriesId/Episodes",
            mapOf("userId" to user, "seasonId" to seasonId, "fields" to "$FIELDS,Overview", "enableUserData" to true),
        ).map { JellyfinJson.items(it, source.id) }

    override suspend fun detail(itemId: String): MediaResult<MediaDetail> {
        val r = client.get("Items/$itemId", mapOf("userId" to user))
        if (r !is MediaResult.Ok) return r.map { error("unreachable") }
        val o = r.value?.takeIf { it.isJsonObject }?.asJsonObject ?: return MediaResult.Failed(200)
        val title = JellyfinJson.title(o, source.id) ?: return MediaResult.NotFound
        return MediaResult.Ok(MediaDetail(title, JellyfinJson.versions(o)))
    }

    override suspend fun play(play: MediaPlay): MediaResult<MediaPlan> {
        val body = playbackBody(play, caps())
        val r = client.post("Items/${play.itemId}/PlaybackInfo", mapOf("userId" to user), body)
        if (r !is MediaResult.Ok) return r.map { error("unreachable") }
        val o = r.value?.takeIf { it.isJsonObject }?.asJsonObject ?: return MediaResult.Failed(200)
        if (o.str("ErrorCode") != null) {
            Timber.d("Jellyfin: playback refused (%s)", o.str("ErrorCode"))
            return MediaResult.Failed(200)
        }
        val versions = JellyfinJson.versions(o)
        val version = versions.firstOrNull { it.id == play.versionId } ?: versions.firstOrNull() ?: return MediaResult.NotFound
        return plan(play, version, o.str("PlaySessionId"))?.let { MediaResult.Ok(it) } ?: MediaResult.Failed(200)
    }

    /** The `PlaybackInfo` body: the device profile, the cap, the start and the chosen streams. */
    internal fun playbackBody(play: MediaPlay, caps: JellyfinDeviceCaps): JsonObject = JsonObject().apply {
        addProperty("UserId", user)
        addProperty("MaxStreamingBitrate", play.maxBitrate)
        addProperty("StartTimeTicks", JellyfinJson.msToTicks(play.startMs))
        play.audioIndex?.let { addProperty("AudioStreamIndex", it) }
        play.subtitleIndex?.let { addProperty("SubtitleStreamIndex", it) }
        play.versionId?.let { addProperty("MediaSourceId", it) }
        addProperty("EnableDirectPlay", true)
        addProperty("EnableDirectStream", true)
        addProperty("EnableTranscoding", true)
        addProperty("AllowVideoStreamCopy", true)
        addProperty("AllowAudioStreamCopy", true)
        addProperty("AutoOpenLiveStream", true)
        add("DeviceProfile", JellyfinProfile.build(caps, play.maxBitrate))
    }

    /**
     * How [version] is played: the file itself when the server allows it (direct), else its converting URL; the
     * chosen subtitle beside the video when the server delivers it as a file. Null: neither is offered.
     */
    internal fun plan(play: MediaPlay, version: MediaVersion, playSessionId: String?): MediaPlan? {
        val transcoding = version.transcodingUrl
        val (url, method) = when {
            version.supportsDirectPlay -> staticUrl(play.itemId, version, playSessionId) to PlayMethod.DIRECT_PLAY
            transcoding != null -> client.absolute(transcoding) to
                if (version.transcodeReasons.isNotEmpty() && version.transcodeReasons.all { it == "ContainerNotSupported" }) PlayMethod.DIRECT_STREAM else PlayMethod.TRANSCODE
            version.supportsDirectStream -> staticUrl(play.itemId, version, playSessionId) to PlayMethod.DIRECT_STREAM
            else -> return null
        }
        val subtitle = play.subtitleIndex?.takeIf { it >= 0 } ?: version.defaultSubtitle.takeIf { play.subtitleIndex == null }
        val side = version.subtitles.filter { it.index == subtitle && it.deliveryMethod == "External" && it.deliveryUrl != null }.map { s ->
            MediaSideSubtitle(s.index, client.absolute(s.deliveryUrl!!), subtitleMime(s), s.language, s.displayTitle ?: s.title)
        }
        val hls = version.transcodingProtocol == "hls" || url.substringBefore('?').endsWith(".m3u8")
        return MediaPlan(
            itemId = play.itemId,
            url = url,
            method = method,
            version = version,
            playSessionId = playSessionId,
            hls = method != PlayMethod.DIRECT_PLAY && hls,
            audioIndex = play.audioIndex ?: version.defaultAudio,
            subtitleIndex = subtitle,
            bitrate = play.maxBitrate.takeIf { method == PlayMethod.TRANSCODE && it < MediaQuality.ORIGINAL_BPS },
            sideSubtitles = side,
        )
    }

    private fun staticUrl(itemId: String, version: MediaVersion, playSessionId: String?): String =
        client.url(
            "Videos/$itemId/stream",
            mapOf("static" to true, "mediaSourceId" to version.id, "playSessionId" to playSessionId, "deviceId" to info.deviceId),
        ).toString()

    private fun subtitleMime(s: MediaStream): String {
        val path = s.deliveryUrl.orEmpty().substringBefore('?').lowercase()
        return when {
            path.endsWith(".srt") || s.codec == "subrip" || s.codec == "srt" -> "application/x-subrip"
            path.endsWith(".ass") || path.endsWith(".ssa") -> "text/x-ssa"
            path.endsWith(".ttml") -> "application/ttml+xml"
            else -> "text/vtt"
        }
    }

    /** The body of the three progress reports. */
    internal fun progressBody(p: MediaProgress, stopped: Boolean = false): JsonObject = JsonObject().apply {
        addProperty("ItemId", p.plan.itemId)
        addProperty("MediaSourceId", p.plan.version.id)
        p.plan.playSessionId?.let { addProperty("PlaySessionId", it) }
        addProperty("PositionTicks", JellyfinJson.msToTicks(p.positionMs))
        addProperty("IsPaused", p.paused)
        addProperty("IsMuted", false)
        addProperty("CanSeek", true)
        addProperty("PlayMethod", p.plan.method.wire)
        p.plan.audioIndex?.let { addProperty("AudioStreamIndex", it) }
        addProperty("SubtitleStreamIndex", p.plan.subtitleIndex ?: MediaPlay.SUBTITLES_OFF)
        if (stopped) addProperty("Failed", false)
    }

    override suspend fun started(p: MediaProgress) = report("Sessions/Playing", progressBody(p))

    override suspend fun progress(p: MediaProgress) = report("Sessions/Playing/Progress", progressBody(p))

    override suspend fun stopped(p: MediaProgress) = report("Sessions/Playing/Stopped", progressBody(p, stopped = true))

    private suspend fun report(path: String, body: JsonObject) {
        try {
            val r = client.post(path, body = body)
            if (r !is MediaResult.Ok) Timber.d("Jellyfin: %s not taken (%s)", path, r.javaClass.simpleName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Jellyfin: %s failed (%s)", path, e.javaClass.simpleName)
        }
    }

    override suspend fun setPlayed(itemId: String, played: Boolean): MediaResult<Unit> {
        val path = "UserPlayedItems/$itemId"
        val q = mapOf("userId" to user)
        return (if (played) client.post(path, q) else client.delete(path, q)).map { }
    }

    // ── downloads for offline (question 22 a: Jellyfin only) ──

    /**
     * Whether the server lets this user download (`Policy.EnableContentDownloading` of `GET Users/Me`, the
     * dashboard's "Allow media downloading"); a server that does not say counts as yes.
     */
    suspend fun canDownload(): MediaResult<Boolean> =
        client.get("Users/Me").map { e ->
            e?.takeIf { it.isJsonObject }?.asJsonObject?.let { o ->
                o.get("Policy")?.takeIf { it.isJsonObject }?.asJsonObject?.bool("EnableContentDownloading")
            } ?: true
        }

    /** The original file of [itemId] (`Items/{id}/Download`: the file as it is, with ranges). */
    fun downloadUrl(itemId: String, versionId: String?): String =
        client.url("Items/$itemId/Download", mapOf("mediaSourceId" to versionId)).toString()

    /**
     * [itemId] converted on the NAS into one MPEG-TS file (H.264 at most [videoBps] and [maxHeight] px, AAC stereo), the
     * sound of [audioIndex]; a picture subtitle [burnSubtitle] burnt in. MPEG-TS because a file written as it is
     * converted can still be wound through (MP4 and Matroska put their index at the end).
     */
    fun convertedUrl(itemId: String, versionId: String?, videoBps: Long, maxWidth: Int, maxHeight: Int, audioIndex: Int?, burnSubtitle: Int?): String =
        client.url(
            "Videos/$itemId/stream.ts",
            mapOf(
                "static" to false,
                "mediaSourceId" to versionId,
                "deviceId" to info.deviceId,
                "container" to "ts",
                "videoCodec" to "h264",
                "audioCodec" to "aac",
                "videoBitRate" to videoBps,
                "audioBitRate" to DOWNLOAD_AUDIO_BPS,
                "maxAudioChannels" to 2,
                "maxWidth" to maxWidth,
                "maxHeight" to maxHeight,
                "audioStreamIndex" to audioIndex,
                "subtitleStreamIndex" to burnSubtitle,
                "subtitleMethod" to burnSubtitle?.let { "Encode" },
                "breakOnNonKeyFrames" to false,
            ),
        ).toString()

    /** A text subtitle of the file as WebVTT (the server converts SRT, ASS and embedded ones). */
    fun subtitleUrl(itemId: String, versionId: String, index: Int): String =
        client.url("Videos/$itemId/$versionId/Subtitles/$index/Stream.vtt").toString()

    /**
     * Progress watched offline, sent at the next contact (`POST UserItems/{id}/UserData`): the place, whether it was
     * seen and when.
     */
    suspend fun saveUserData(itemId: String, positionMs: Long, played: Boolean, playedAt: Long): MediaResult<Unit> {
        val body = JsonObject().apply {
            addProperty("PlaybackPositionTicks", JellyfinJson.msToTicks(if (played) 0 else positionMs))
            addProperty("Played", played)
            addProperty("LastPlayedDate", java.time.Instant.ofEpochMilli(playedAt).toString())
        }
        return client.post("UserItems/$itemId/UserData", mapOf("userId" to user), body).map { }
    }

    override suspend fun measure(): Long? = client.measure("Playback/BitrateTest", mapOf("size" to MEASURE_BYTES))

    override fun imageUrl(title: MediaTitle, backdrop: Boolean, maxHeight: Int): String? {
        val (id, tag) = if (backdrop) title.backdropId to title.backdropTag else title.posterId to title.posterTag
        if (id == null || tag == null) return null
        return client.url(
            "Items/$id/Images/${if (backdrop) "Backdrop" else "Primary"}",
            mapOf("maxHeight" to maxHeight, "quality" to 85, "tag" to tag),
        ).toString()
    }

    override fun headers(): Map<String, String> = mapOf("Authorization" to client.authorization())

    companion object {
        fun sortBy(sort: MediaOrder.Sort): String = when (sort) {
            MediaOrder.Sort.NAME -> "SortName"
            MediaOrder.Sort.ADDED -> "DateCreated,SortName"
            MediaOrder.Sort.YEAR -> "ProductionYear,SortName"
        }

        fun sortOrder(sort: MediaOrder.Sort): String = if (sort == MediaOrder.Sort.NAME) "Ascending" else "Descending,Ascending"

        const val FIELDS = "PrimaryImageAspectRatio,DateCreated,Genres,OfficialRating"
        const val IMAGES = "Primary,Backdrop,Thumb"

        /** The most of a folder shown on the page "Ordner". */
        const val FOLDER_LIMIT = 500

        /** The bytes of one bandwidth measurement (about a second at 20 Mbit/s). */
        const val MEASURE_BYTES = 2_500_000

        /** The sound of a converted download: AAC stereo at this bit rate. */
        const val DOWNLOAD_AUDIO_BPS = 192_000L
    }
}

/**
 * Signing this device in at a Jellyfin source of the server (plugin „Medien“, [com.gatecontrol.android.media.server.MediaAutoPair]):
 * the server's public info, then Quick Connect — started here, the code confirmed by the plugin, never shown.
 */
class JellyfinSignIn(baseUrl: String, http: OkHttpClient, info: MediaClientInfo) {
    private val client = JellyfinClient(baseUrl, http, info)

    /** Why the last call did not reach the server (null: it did, or no call yet). */
    val lastFailure: java.io.IOException? get() = client.lastFailure

    suspend fun server(): MediaResult<JellyfinServerInfo> {
        val r = client.get("System/Info/Public")
        if (r !is MediaResult.Ok) return r.map { error("unreachable") }
        val o = r.value?.takeIf { it.isJsonObject }?.asJsonObject ?: return MediaResult.Failed(200)
        return JellyfinJson.serverInfo(o)?.let { MediaResult.Ok(it) } ?: MediaResult.Failed(200)
    }

    suspend fun quickConnectEnabled(): Boolean =
        client.get("QuickConnect/Enabled").valueOrNull()?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } == true

    suspend fun quickConnect(): MediaResult<JellyfinQuickConnect> {
        val r = client.post("QuickConnect/Initiate")
        if (r !is MediaResult.Ok) return r.map { error("unreachable") }
        val o = r.value?.takeIf { it.isJsonObject }?.asJsonObject ?: return MediaResult.Failed(200)
        return JellyfinJson.quickConnect(o)?.let { MediaResult.Ok(it) } ?: MediaResult.Failed(200)
    }

    /** Whether the code was confirmed in Jellyfin. */
    suspend fun confirmed(q: JellyfinQuickConnect): MediaResult<Boolean> =
        client.get("QuickConnect/Connect", mapOf("secret" to q.secret)).map { e ->
            e?.takeIf { it.isJsonObject }?.asJsonObject?.bool("Authenticated") == true
        }

    suspend fun signIn(q: JellyfinQuickConnect): MediaResult<JellyfinAuth> =
        auth(client.post("Users/AuthenticateWithQuickConnect", body = JsonObject().apply { addProperty("Secret", q.secret) }))


    private fun auth(r: MediaResult<com.google.gson.JsonElement?>): MediaResult<JellyfinAuth> {
        if (r !is MediaResult.Ok) return r.map { error("unreachable") }
        val o = r.value?.takeIf { it.isJsonObject }?.asJsonObject ?: return MediaResult.Failed(200)
        return JellyfinJson.auth(o)?.let { MediaResult.Ok(it) } ?: MediaResult.Failed(200)
    }
}
