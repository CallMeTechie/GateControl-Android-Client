package com.gatecontrol.android.media.jellyfin

import com.gatecontrol.android.media.MediaStream
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.MediaVersion
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.time.OffsetDateTime

/** `GET /System/Info/Public`: the server's name, version and id. */
data class JellyfinServerInfo(val name: String, val version: String?, val id: String) {
    /** 10.9 or newer (the API this client speaks: `/UserViews`, `/UserItems/Resume`, `/UserPlayedItems`). */
    val supported: Boolean get() = JellyfinJson.versionAtLeast(version, 10, 9)
}

/** The answer of a sign-in: the access token and the person. */
data class JellyfinAuth(val token: String, val userId: String, val userName: String?, val serverId: String?) {
    override fun toString(): String = "JellyfinAuth(user=$userId, server=$serverId)"
}

/** A library of the person (`/UserViews`) or a folder in it, for the page "Ordner". */
data class JellyfinFolder(val id: String, val name: String)

/** What a library or folder holds: its sub-folders and its films, series and videos ([total]: as the server counts). */
data class JellyfinFolderPage(val folders: List<JellyfinFolder>, val titles: List<MediaTitle>, val total: Int? = null)

/** Quick Connect: the [code] shown on the TV and the [secret] the TV asks with. */
data class JellyfinQuickConnect(val code: String, val secret: String) {
    override fun toString(): String = "JellyfinQuickConnect(code=$code)"
}

/**
 * Jellyfin's JSON read by hand (no reflection: R8 keeps nothing for it). Unknown or wrong fields are left out, never
 * thrown. Pure, unit-tested.
 */
object JellyfinJson {
    const val TICKS_PER_MS = 10_000L

    fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }
        ?.filterNot { it.isISOControl() && it != '\n' }?.trim()?.ifEmpty { null }

    fun JsonObject.int(key: String): Int? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.let { runCatching { it.asInt }.getOrNull() }

    fun JsonObject.long(key: String): Long? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.let { runCatching { it.asLong }.getOrNull() }

    fun JsonObject.bool(key: String): Boolean? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    fun JsonObject.objects(key: String): List<JsonObject> =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e -> e.takeIf { it.isJsonObject }?.asJsonObject }.orEmpty()

    fun JsonObject.strings(key: String): List<String> =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifEmpty { null } }.orEmpty()

    fun ticksToMs(ticks: Long?): Long? = ticks?.takeIf { it >= 0 }?.div(TICKS_PER_MS)

    fun msToTicks(ms: Long): Long = ms.coerceAtLeast(0) * TICKS_PER_MS

    /** "10.10.3" ≥ [major].[minor]. */
    fun versionAtLeast(version: String?, major: Int, minor: Int): Boolean {
        val parts = version?.split('.')?.map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 } ?: return false
        val ma = parts.getOrElse(0) { 0 }
        val mi = parts.getOrElse(1) { 0 }
        return ma > major || (ma == major && mi >= minor)
    }

    fun serverInfo(o: JsonObject): JellyfinServerInfo? {
        val id = o.str("Id") ?: return null
        return JellyfinServerInfo(name = (o.str("ServerName") ?: "Jellyfin").take(64), version = o.str("Version"), id = id)
    }

    fun auth(o: JsonObject): JellyfinAuth? {
        val token = o.str("AccessToken") ?: return null
        val user = o.obj("User") ?: return null
        val userId = user.str("Id") ?: return null
        return JellyfinAuth(token, userId, user.str("Name"), o.str("ServerId"))
    }

    fun quickConnect(o: JsonObject): JellyfinQuickConnect? {
        val code = o.str("Code") ?: return null
        val secret = o.str("Secret") ?: return null
        return JellyfinQuickConnect(code, secret)
    }

    /** The items of `{ "Items": [ … ] }` or of a bare array (`/Items/Latest`). */
    fun items(e: JsonElement?, sourceId: String): List<MediaTitle> {
        val list = when {
            e == null -> emptyList()
            e.isJsonArray -> e.asJsonArray.mapNotNull { it.takeIf { x -> x.isJsonObject }?.asJsonObject }
            e.isJsonObject -> e.asJsonObject.objects("Items")
            else -> emptyList()
        }
        return list.mapNotNull { title(it, sourceId) }
    }

    /** The libraries without video (music, books, photos, live TV): not on the page "Ordner". */
    private val NO_VIDEO_COLLECTIONS = setOf("music", "books", "photos", "livetv", "audiobooks")

    /** Folders without video (an album, a photo album, a book). */
    private val NO_VIDEO_FOLDERS = setOf("MusicAlbum", "MusicArtist", "PhotoAlbum", "AudioBook", "Book", "Photo", "Audio")

    private fun objectsOf(e: JsonElement?): List<JsonObject> = when {
        e == null -> emptyList()
        e.isJsonArray -> e.asJsonArray.mapNotNull { it.takeIf { x -> x.isJsonObject }?.asJsonObject }
        e.isJsonObject -> e.asJsonObject.objects("Items")
        else -> emptyList()
    }

    private fun folder(o: JsonObject): JellyfinFolder? {
        val id = o.str("Id") ?: return null
        return JellyfinFolder(id, (o.str("Name") ?: return null).take(200))
    }

    /** `GET /UserViews`: the person's libraries with video, in the server's order. */
    fun views(e: JsonElement?): List<JellyfinFolder> = objectsOf(e)
        .filterNot { (it.str("CollectionType")?.lowercase(java.util.Locale.ROOT)) in NO_VIDEO_COLLECTIONS }
        .mapNotNull { folder(it) }

    /**
     * `GET /Items?parentId=…`: a library's or folder's content. Films, series, seasons, episodes and videos are titles
     * (a series opens its page); other folders (a box set, a plain folder) are folders; music, books and photos are
     * left out.
     */
    fun folderPage(e: JsonElement?, sourceId: String): JellyfinFolderPage {
        val folders = mutableListOf<JellyfinFolder>()
        val titles = mutableListOf<MediaTitle>()
        for (o in objectsOf(e)) {
            val t = title(o, sourceId)
            when {
                t != null -> titles += t
                o.bool("IsFolder") == true && o.str("Type") !in NO_VIDEO_FOLDERS &&
                    (o.str("CollectionType")?.lowercase(java.util.Locale.ROOT)) !in NO_VIDEO_COLLECTIONS -> folder(o)?.let { folders += it }
            }
        }
        return JellyfinFolderPage(folders, titles, total(e))
    }

    fun total(e: JsonElement?): Int? = e?.takeIf { it.isJsonObject }?.asJsonObject?.int("TotalRecordCount")

    fun kind(type: String?): MediaTitle.Kind? = when (type) {
        "Movie" -> MediaTitle.Kind.MOVIE
        "Series" -> MediaTitle.Kind.SERIES
        "Season" -> MediaTitle.Kind.SEASON
        "Episode" -> MediaTitle.Kind.EPISODE
        "Video", "MusicVideo" -> MediaTitle.Kind.VIDEO
        else -> null
    }

    /** One item → [MediaTitle]; null for kinds the TV does not show (music, books, folders). */
    fun title(o: JsonObject, sourceId: String): MediaTitle? {
        val id = o.str("Id") ?: return null
        val kind = kind(o.str("Type")) ?: return null
        val user = o.obj("UserData")
        val tags = o.obj("ImageTags")
        val ownPrimary = tags?.str("Primary")
        // an episode without its own picture shows its series' poster
        val (posterId, posterTag) = when {
            ownPrimary != null -> id to ownPrimary
            o.str("SeriesPrimaryImageTag") != null -> o.str("SeriesId") to o.str("SeriesPrimaryImageTag")
            else -> null to null
        }
        val backdrops = o.strings("BackdropImageTags")
        val (backdropId, backdropTag) = when {
            backdrops.isNotEmpty() -> id to backdrops.first()
            o.strings("ParentBackdropImageTags").isNotEmpty() -> o.str("ParentBackdropItemId") to o.strings("ParentBackdropImageTags").first()
            else -> null to null
        }
        return MediaTitle(
            id = id,
            sourceId = sourceId,
            kind = kind,
            name = (o.str("Name") ?: return null).take(200),
            year = o.int("ProductionYear"),
            runtimeMs = ticksToMs(o.long("RunTimeTicks")),
            positionMs = ticksToMs(user?.long("PlaybackPositionTicks")) ?: 0,
            played = user?.bool("Played") == true,
            seriesId = o.str("SeriesId"),
            seriesName = o.str("SeriesName"),
            seasonId = o.str("SeasonId"),
            season = o.int("ParentIndexNumber"),
            episode = o.int("IndexNumber"),
            overview = o.str("Overview")?.take(4000),
            genres = o.strings("Genres").take(4),
            rating = o.str("OfficialRating"),
            childCount = o.int("ChildCount"),
            unplayed = user?.int("UnplayedItemCount"),
            posterId = posterId,
            posterTag = posterTag,
            backdropId = backdropId,
            backdropTag = backdropTag,
            addedAt = o.str("DateCreated")?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() },
            playedAt = user?.str("LastPlayedDate")?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() },
        )
    }

    fun stream(o: JsonObject): MediaStream? {
        val index = o.int("Index") ?: return null
        val type = when (o.str("Type")) {
            "Video" -> MediaStream.Type.VIDEO
            "Audio" -> MediaStream.Type.AUDIO
            "Subtitle" -> MediaStream.Type.SUBTITLE
            else -> MediaStream.Type.OTHER
        }
        return MediaStream(
            index = index,
            type = type,
            codec = o.str("Codec")?.lowercase(),
            language = o.str("Language")?.takeIf { it.length <= 16 },
            title = o.str("Title")?.take(120),
            displayTitle = o.str("DisplayTitle")?.take(160),
            channels = o.int("Channels"),
            external = o.bool("IsExternal") == true,
            textual = o.bool("IsTextSubtitleStream") == true,
            isDefault = o.bool("IsDefault") == true,
            forced = o.bool("IsForced") == true,
            width = o.int("Width"),
            height = o.int("Height"),
            videoRange = o.str("VideoRange"),
            videoRangeType = o.str("VideoRangeType"),
            bitrate = o.long("BitRate"),
            deliveryMethod = o.str("DeliveryMethod"),
            deliveryUrl = o.str("DeliveryUrl"),
        )
    }

    fun version(o: JsonObject): MediaVersion? {
        val id = o.str("Id") ?: return null
        return MediaVersion(
            id = id,
            container = o.str("Container")?.lowercase(),
            bitrate = o.long("Bitrate"),
            runtimeMs = ticksToMs(o.long("RunTimeTicks")),
            streams = o.objects("MediaStreams").mapNotNull(::stream),
            defaultAudio = o.int("DefaultAudioStreamIndex"),
            defaultSubtitle = o.int("DefaultSubtitleStreamIndex"),
            supportsDirectPlay = o.bool("SupportsDirectPlay") == true,
            supportsDirectStream = o.bool("SupportsDirectStream") == true,
            supportsTranscoding = o.bool("SupportsTranscoding") == true,
            transcodingUrl = o.str("TranscodingUrl"),
            transcodingProtocol = o.str("TranscodingSubProtocol")?.lowercase(),
            transcodeReasons = reasons(o.str("TranscodingUrl")),
            size = o.long("Size")?.takeIf { it > 0 },
        )
    }

    fun versions(o: JsonObject): List<MediaVersion> = o.objects("MediaSources").mapNotNull(::version)

    /** `TranscodeReasons=AudioCodecNotSupported,SubtitleCodecNotSupported` of a converting URL. */
    fun reasons(url: String?): List<String> {
        val q = url?.substringAfter('?', "") ?: return emptyList()
        val value = q.split('&').firstOrNull { it.startsWith("TranscodeReasons=", ignoreCase = true) }?.substringAfter('=') ?: return emptyList()
        return java.net.URLDecoder.decode(value, "UTF-8").split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
