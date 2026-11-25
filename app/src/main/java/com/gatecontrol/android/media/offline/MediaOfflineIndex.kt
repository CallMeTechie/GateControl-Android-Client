package com.gatecontrol.android.media.offline

import com.gatecontrol.android.media.MediaStream
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.MediaVersion
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64

/**
 * The index of the downloads as JSON, written by hand (no reflection: R8 renames fields between versions of the
 * app). Unknown or broken entries are left out. Pure, unit-tested.
 */
object MediaOfflineIndex {
    private const val VERSION = 1

    fun encode(items: List<OfflineItem>): String = JsonObject().apply {
        addProperty("v", VERSION)
        add("items", JsonArray().apply { items.forEach { add(item(it)) } })
    }.toString()

    fun decode(text: String?): List<OfflineItem> {
        val o = runCatching { JsonParser.parseString(text ?: return emptyList()) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
        val arr = o.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return arr.mapNotNull { e -> runCatching { item(e.asJsonObject) }.getOrNull() }
    }

    private fun item(i: OfflineItem) = JsonObject().apply {
        addProperty("id", i.id)
        addProperty("source", i.sourceId)
        add("title", title(i.title))
        addProperty("quality", i.quality.wire)
        addProperty("state", i.state.wire)
        i.failure?.let { addProperty("failure", it.wire) }
        addProperty("added", i.addedAt)
        addProperty("loaded", i.loaded)
        addProperty("expected", i.expected)
        addProperty("size", i.size)
        i.version?.let { add("version", version(it)) }
        add("subs", JsonArray().apply { i.subtitles.forEach { s -> add(JsonObject().apply { addProperty("i", s.index); s.language?.let { addProperty("l", it) }; s.label?.let { addProperty("n", it) }; if (s.forced) addProperty("f", true) }) } })
        i.audioIndex?.let { addProperty("audio", it) }
        addProperty("pos", i.positionMs)
        addProperty("played", i.played)
        addProperty("playedAt", i.playedAt)
        addProperty("dirty", i.dirty)
        addProperty("key", Base64.getEncoder().encodeToString(i.key))
    }

    private fun item(o: JsonObject): OfflineItem? {
        val id = o.s("id")?.takeIf { Regex("^[0-9a-f]{32}$").matches(it) } ?: return null
        val key = o.s("key")?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }?.takeIf { it.size == 32 } ?: return null
        val title = o.obj("title")?.let(::title) ?: return null
        return OfflineItem(
            id = id,
            sourceId = o.s("source") ?: return null,
            title = title,
            quality = OfflineQuality.fromWire(o.s("quality")),
            state = OfflineState.fromWire(o.s("state")) ?: return null,
            failure = OfflineFailure.fromWire(o.s("failure")),
            addedAt = o.l("added") ?: 0,
            loaded = o.l("loaded") ?: 0,
            expected = o.l("expected") ?: 0,
            size = o.l("size") ?: 0,
            version = o.obj("version")?.let(::version),
            subtitles = o.arr("subs").mapNotNull { e ->
                val s = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                OfflineSubtitle(s.i("i") ?: return@mapNotNull null, s.s("l"), s.s("n"), s.b("f") == true)
            },
            audioIndex = o.i("audio"),
            positionMs = o.l("pos") ?: 0,
            played = o.b("played") == true,
            playedAt = o.l("playedAt") ?: 0,
            dirty = o.b("dirty") == true,
            key = key,
        )
    }

    private fun title(t: MediaTitle) = JsonObject().apply {
        addProperty("id", t.id)
        addProperty("source", t.sourceId)
        addProperty("kind", t.kind.name)
        addProperty("name", t.name)
        t.year?.let { addProperty("year", it) }
        t.runtimeMs?.let { addProperty("runtime", it) }
        addProperty("pos", t.positionMs)
        addProperty("played", t.played)
        t.seriesId?.let { addProperty("seriesId", it) }
        t.seriesName?.let { addProperty("seriesName", it) }
        t.seasonId?.let { addProperty("seasonId", it) }
        t.season?.let { addProperty("season", it) }
        t.episode?.let { addProperty("episode", it) }
        t.overview?.let { addProperty("overview", it) }
        if (t.genres.isNotEmpty()) add("genres", JsonArray().apply { t.genres.forEach { add(it) } })
        t.rating?.let { addProperty("rating", it) }
        t.posterId?.let { addProperty("posterId", it) }
        t.posterTag?.let { addProperty("posterTag", it) }
        t.backdropId?.let { addProperty("backdropId", it) }
        t.backdropTag?.let { addProperty("backdropTag", it) }
        t.addedAt?.let { addProperty("addedAt", it) }
        t.playedAt?.let { addProperty("playedAt", it) }
    }

    private fun title(o: JsonObject): MediaTitle? = MediaTitle(
        id = o.s("id") ?: return null,
        sourceId = o.s("source") ?: return null,
        kind = runCatching { MediaTitle.Kind.valueOf(o.s("kind") ?: return null) }.getOrNull() ?: return null,
        name = o.s("name") ?: return null,
        year = o.i("year"),
        runtimeMs = o.l("runtime"),
        positionMs = o.l("pos") ?: 0,
        played = o.b("played") == true,
        seriesId = o.s("seriesId"),
        seriesName = o.s("seriesName"),
        seasonId = o.s("seasonId"),
        season = o.i("season"),
        episode = o.i("episode"),
        overview = o.s("overview"),
        genres = o.arr("genres").mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString },
        rating = o.s("rating"),
        posterId = o.s("posterId"),
        posterTag = o.s("posterTag"),
        backdropId = o.s("backdropId"),
        backdropTag = o.s("backdropTag"),
        addedAt = o.l("addedAt"),
        playedAt = o.l("playedAt"),
    )

    private fun version(v: MediaVersion) = JsonObject().apply {
        addProperty("id", v.id)
        v.container?.let { addProperty("container", it) }
        v.bitrate?.let { addProperty("bitrate", it) }
        v.runtimeMs?.let { addProperty("runtime", it) }
        v.size?.let { addProperty("size", it) }
        v.defaultAudio?.let { addProperty("audio", it) }
        v.defaultSubtitle?.let { addProperty("subtitle", it) }
        add("streams", JsonArray().apply { v.streams.forEach { add(stream(it)) } })
    }

    private fun version(o: JsonObject): MediaVersion? = MediaVersion(
        id = o.s("id") ?: return null,
        container = o.s("container"),
        bitrate = o.l("bitrate"),
        runtimeMs = o.l("runtime"),
        size = o.l("size"),
        defaultAudio = o.i("audio"),
        defaultSubtitle = o.i("subtitle"),
        streams = o.arr("streams").mapNotNull { e -> e.takeIf { it.isJsonObject }?.asJsonObject?.let(::stream) },
        supportsDirectPlay = true,
    )

    private fun stream(s: MediaStream) = JsonObject().apply {
        addProperty("i", s.index)
        addProperty("t", s.type.name)
        s.codec?.let { addProperty("codec", it) }
        s.language?.let { addProperty("lang", it) }
        s.title?.let { addProperty("title", it) }
        s.displayTitle?.let { addProperty("display", it) }
        s.channels?.let { addProperty("ch", it) }
        if (s.external) addProperty("ext", true)
        if (s.textual) addProperty("text", true)
        if (s.isDefault) addProperty("def", true)
        if (s.forced) addProperty("forced", true)
        s.width?.let { addProperty("w", it) }
        s.height?.let { addProperty("h", it) }
        s.videoRange?.let { addProperty("range", it) }
        s.videoRangeType?.let { addProperty("rangeType", it) }
        s.bitrate?.let { addProperty("bps", it) }
    }

    private fun stream(o: JsonObject): MediaStream? = MediaStream(
        index = o.i("i") ?: return null,
        type = runCatching { MediaStream.Type.valueOf(o.s("t") ?: return null) }.getOrNull() ?: return null,
        codec = o.s("codec"),
        language = o.s("lang"),
        title = o.s("title"),
        displayTitle = o.s("display"),
        channels = o.i("ch"),
        external = o.b("ext") == true,
        textual = o.b("text") == true,
        isDefault = o.b("def") == true,
        forced = o.b("forced") == true,
        width = o.i("w"),
        height = o.i("h"),
        videoRange = o.s("range"),
        videoRangeType = o.s("rangeType"),
        bitrate = o.l("bps"),
    )

    private fun JsonObject.p(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
    private fun JsonObject.s(k: String): String? = p(k)?.let { runCatching { it.asString }.getOrNull() }
    private fun JsonObject.l(k: String): Long? = p(k)?.takeIf { it.isNumber }?.let { runCatching { it.asLong }.getOrNull() }
    private fun JsonObject.i(k: String): Int? = p(k)?.takeIf { it.isNumber }?.let { runCatching { it.asInt }.getOrNull() }
    private fun JsonObject.b(k: String): Boolean? = p(k)?.takeIf { it.isBoolean }?.asBoolean
    private fun JsonObject.obj(k: String): JsonObject? = get(k)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.arr(k: String): List<JsonElement> = get(k)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
}
