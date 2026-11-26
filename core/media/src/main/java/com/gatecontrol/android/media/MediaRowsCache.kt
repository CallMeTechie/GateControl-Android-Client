package com.gatecontrol.android.media

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

/**
 * The rows of Medien last loaded, per source (a Jellyfin server and its person, a share): kept while the app runs and
 * in [dir] (the app's cache folder), so that the overview shows at once on opening and is only checked again in the
 * background (stale-while-revalidate). Only what the page shows (titles, picture ids and tags), never an access: the
 * file name is a hash of the source's identity, the token is not part of it. Older than [MAX_AGE_MS]: not used.
 * [dir] null: only in memory (tests, a device without a cache folder).
 */
class MediaRowsCache(
    private val dir: File?,
    private val now: () -> Long = System::currentTimeMillis,
    /** Where the files are written and deleted (the app: one background thread; tests: at once). */
    private val writer: (Runnable) -> Unit = { it.run() },
) {
    /** What was loaded for one source, and when. */
    data class Entry(val rows: MediaRows, val savedAt: Long)

    private val memory = HashMap<String, Entry>()

    /**
     * The key of [source]: its id, its server and person (another Jellyfin user, another server, another plugin
     * source never share an entry). Not the token, which changes with each sign-in.
     */
    fun key(source: MediaSource): String =
        sha256(listOf(VERSION, source.kind.name, source.id, source.remoteId, source.serverId, source.userId, source.linkedUserId, source.address).joinToString("|") { it?.toString().orEmpty() })

    /** [source]'s rows (memory first, then the file); null when none or older than [MAX_AGE_MS]. */
    @Synchronized
    fun get(source: MediaSource): Entry? {
        val key = key(source)
        val entry = memory[key] ?: readFile(key)?.also { memory[key] = it }
        if (entry == null) return null
        val age = now() - entry.savedAt
        if (age < 0 || age >= MAX_AGE_MS) {
            remove(key)
            return null
        }
        return entry
    }

    /** [source]'s rows: from memory at once, else read from the disk in the background. */
    suspend fun load(source: MediaSource): Entry? =
        peek(source) ?: if (dir == null) null else withContext(Dispatchers.IO) { get(source) }

    /** Only from memory (without touching the disk; the page's first frame). */
    @Synchronized
    fun peek(source: MediaSource): Entry? = memory[key(source)]?.takeIf { now() - it.savedAt in 0 until MAX_AGE_MS }

    /** Whether [source]'s rows were loaded within [FRESH_MS] (then opening the page asks nothing). */
    fun fresh(source: MediaSource): Boolean = peek(source)?.let { now() - it.savedAt < FRESH_MS } == true

    /** [source]'s rows as just loaded (all rows of the overview, the first page of "Filme" and "Serien"). */
    @Synchronized
    fun put(source: MediaSource, rows: MediaRows) {
        val key = key(source)
        val entry = Entry(rows, now())
        memory[key] = entry
        if (dir != null) writer { writeFile(key, entry) }
    }

    /**
     * [source]'s rows are to be asked again at the next opening (a heart or "Gesehen" set on a detail page), still
     * shown until then.
     */
    @Synchronized
    fun markStale(source: MediaSource) {
        val key = key(source)
        val entry = memory[key] ?: return
        memory[key] = entry.copy(savedAt = minOf(entry.savedAt, now() - FRESH_MS))
    }

    /** Only the entries of [sources] stay (a source removed, unpaired: its rows go, also from the disk). */
    @Synchronized
    fun keepOnly(sources: List<MediaSource>) {
        val keep = sources.map(::key).toSet()
        memory.keys.retainAll(keep)
        val d = dir ?: return
        writer { runCatching { d.listFiles()?.filter { it.name.removeSuffix(SUFFIX) !in keep }?.forEach { it.delete() } } }
    }

    @Synchronized
    fun clear() {
        memory.clear()
        runCatching { dir?.listFiles()?.forEach { it.delete() } }
    }

    private fun remove(key: String) {
        memory.remove(key)
        runCatching { dir?.let { File(it, key + SUFFIX).delete() } }
    }

    private fun readFile(key: String): Entry? {
        val f = dir?.let { File(it, key + SUFFIX) } ?: return null
        return runCatching { if (f.isFile) decode(f.readText()) else null }.getOrNull()
    }

    @Synchronized
    private fun writeFile(key: String, entry: Entry) {
        val d = dir ?: return
        try {
            d.mkdirs()
            val tmp = File(d, "$key.tmp")
            tmp.writeText(encode(entry))
            if (!tmp.renameTo(File(d, key + SUFFIX))) tmp.delete()
        } catch (e: Exception) {
            Timber.d("Medien: rows not kept (%s)", e.javaClass.simpleName)
        }
    }

    companion object {
        /** Loaded within this long: opening the page again asks nothing (switching tabs, back from a title). */
        const val FRESH_MS = 60_000L

        /** Older rows are not shown (a week: "Weiterschauen" and "Neu" would be too far off). */
        const val MAX_AGE_MS = 7 * 24 * 3600_000L

        /** 2: the rows of „Ergänzt“ (Als Nächstes, Neue Filme/Folgen, Favoriten, Filme, Sammlungen, Genres). */
        private const val VERSION = 2
        private const val SUFFIX = ".json"

        private fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        /** The entry as JSON, written by hand (no reflection: R8 renames fields between versions of the app). */
        fun encode(entry: Entry): String = JsonObject().apply {
            addProperty("v", VERSION)
            addProperty("at", entry.savedAt)
            val r = entry.rows
            add("resume", titles(r.resume))
            add("nextUp", titles(r.nextUp))
            addProperty("nextUpTotal", r.nextUpTotal)
            add("latestMovies", titles(r.latestMovies))
            add("latestEpisodes", titles(r.latestEpisodes))
            add("movies", titles(r.movies))
            addProperty("moviesTotal", r.moviesTotal)
            r.moviesNext?.let { addProperty("moviesNext", it) }
            add("series", titles(r.series))
            addProperty("seriesTotal", r.seriesTotal)
            r.seriesNext?.let { addProperty("seriesNext", it) }
            add("favorites", titles(r.favorites))
            addProperty("favoritesTotal", r.favoritesTotal)
            add("collections", titles(r.collections))
            addProperty("collectionsTotal", r.collectionsTotal)
            add("genres", JsonArray().apply { r.genres.forEach { add(it) } })
        }.toString()

        /** Null when it is not an entry of this version (then the page loads as without one). */
        fun decode(text: String): Entry? {
            val o = runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            if (o.i("v") != VERSION) return null
            val at = o.l("at") ?: return null
            return Entry(
                MediaRows(
                    resume = titles(o.arr("resume")),
                    nextUp = titles(o.arr("nextUp")),
                    nextUpTotal = o.i("nextUpTotal") ?: 0,
                    latestMovies = titles(o.arr("latestMovies")),
                    latestEpisodes = titles(o.arr("latestEpisodes")),
                    movies = titles(o.arr("movies")),
                    moviesTotal = o.i("moviesTotal") ?: 0,
                    moviesNext = o.i("moviesNext"),
                    series = titles(o.arr("series")),
                    seriesTotal = o.i("seriesTotal") ?: 0,
                    seriesNext = o.i("seriesNext"),
                    favorites = titles(o.arr("favorites")),
                    favoritesTotal = o.i("favoritesTotal") ?: 0,
                    collections = titles(o.arr("collections")),
                    collectionsTotal = o.i("collectionsTotal") ?: 0,
                    genres = o.arr("genres").mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString },
                ),
                at,
            )
        }

        private fun titles(list: List<MediaTitle>) = JsonArray().apply { list.forEach { add(title(it)) } }

        private fun titles(arr: List<JsonElement>): List<MediaTitle> =
            arr.mapNotNull { e -> e.takeIf { it.isJsonObject }?.asJsonObject?.let { runCatching { title(it) }.getOrNull() } }

        private fun title(t: MediaTitle) = JsonObject().apply {
            addProperty("id", t.id)
            addProperty("source", t.sourceId)
            addProperty("kind", t.kind.name)
            addProperty("name", t.name)
            t.year?.let { addProperty("year", it) }
            t.runtimeMs?.let { addProperty("runtime", it) }
            if (t.positionMs != 0L) addProperty("pos", t.positionMs)
            if (t.played) addProperty("played", true)
            t.seriesId?.let { addProperty("seriesId", it) }
            t.seriesName?.let { addProperty("seriesName", it) }
            t.seasonId?.let { addProperty("seasonId", it) }
            t.season?.let { addProperty("season", it) }
            t.episode?.let { addProperty("episode", it) }
            t.overview?.let { addProperty("overview", it) }
            if (t.genres.isNotEmpty()) add("genres", JsonArray().apply { t.genres.forEach { add(it) } })
            t.rating?.let { addProperty("rating", it) }
            t.childCount?.let { addProperty("children", it) }
            t.unplayed?.let { addProperty("unplayed", it) }
            t.posterId?.let { addProperty("posterId", it) }
            t.posterTag?.let { addProperty("posterTag", it) }
            t.backdropId?.let { addProperty("backdropId", it) }
            t.backdropTag?.let { addProperty("backdropTag", it) }
            t.addedAt?.let { addProperty("addedAt", it) }
            t.playedAt?.let { addProperty("playedAt", it) }
            if (t.fromShare) addProperty("share", true)
            if (t.favorite) addProperty("fav", true)
            t.newCount?.let { addProperty("new", it) }
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
            childCount = o.i("children"),
            unplayed = o.i("unplayed"),
            posterId = o.s("posterId"),
            posterTag = o.s("posterTag"),
            backdropId = o.s("backdropId"),
            backdropTag = o.s("backdropTag"),
            addedAt = o.l("addedAt"),
            playedAt = o.l("playedAt"),
            fromShare = o.b("share") == true,
            favorite = o.b("fav") == true,
            newCount = o.i("new"),
        )

        private fun JsonObject.p(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        private fun JsonObject.s(k: String): String? = p(k)?.let { runCatching { it.asString }.getOrNull() }
        private fun JsonObject.l(k: String): Long? = p(k)?.takeIf { it.isNumber }?.let { runCatching { it.asLong }.getOrNull() }
        private fun JsonObject.i(k: String): Int? = p(k)?.takeIf { it.isNumber }?.let { runCatching { it.asInt }.getOrNull() }
        private fun JsonObject.b(k: String): Boolean? = p(k)?.takeIf { it.isBoolean }?.asBoolean
        private fun JsonObject.arr(k: String): List<JsonElement> = get(k)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
    }
}
