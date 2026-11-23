package com.gatecontrol.android.media.share

import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaTitle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A video file of a share as found: where, what its name says, the pictures beside it. */
data class ShareVideo(
    val path: String,
    val parsed: ShareNames.Parsed,
    val size: Long,
    val modifiedAt: Long?,
    val poster: String? = null,
    val backdrop: String? = null,
    /** The picture of its series' folder (`poster.jpg` in `Graufeld/`). */
    val seriesPoster: String? = null,
)

/**
 * The videos of a share, its films and series as the rows want them (without progress: [ShareLibrary] adds it). Made
 * by [scan]: the folders from the root down, at most [MAX_DEPTH] deep, [MAX_DIRS] folders and [MAX_FILES] videos.
 */
class ShareIndex(val sourceId: String, val videos: List<ShareVideo>, val seasonName: (Int) -> String = { "Staffel $it" }) {

    private val byPath: Map<String, ShareVideo> = videos.associateBy { it.path }

    /** Films A–Z (and in a tie by year). */
    val movies: List<MediaTitle> = videos.filter { !it.parsed.isEpisode }.map(::movie)
        .sortedWith(compareBy<MediaTitle>({ ShareNames.sortKey(it.name) }, { it.year ?: 0 }, { it.id }))

    /** The episodes of each series ([seriesId]), in order. */
    val episodes: Map<String, List<MediaTitle>> = videos.filter { it.parsed.isEpisode }
        .groupBy { seriesId(it.parsed.title) }
        .mapValues { (_, list) -> list.map(::episode).sortedWith(compareBy<MediaTitle>({ it.season ?: 0 }, { it.episode ?: 0 }, { it.id })) }

    /** Series A–Z: their name as the first episode has it, the seasons, the picture of their folder. */
    val series: List<MediaTitle> = episodes.map { (id, list) ->
        val first = list.first()
        val video = byPath[pathOf(first.id)]
        MediaTitle(
            id = id,
            sourceId = sourceId,
            kind = MediaTitle.Kind.SERIES,
            name = first.seriesName ?: first.name,
            childCount = list.mapNotNull { it.season }.distinct().size,
            posterId = list.firstNotNullOfOrNull { byPath[pathOf(it.id)]?.seriesPoster } ?: video?.poster,
            posterTag = SHARE_TAG,
            backdropId = list.firstNotNullOfOrNull { byPath[pathOf(it.id)]?.backdrop },
            backdropTag = SHARE_TAG,
            addedAt = list.mapNotNull { it.addedAt }.maxOrNull(),
            fromShare = true,
        )
    }.sortedBy { ShareNames.sortKey(it.name) }

    fun video(path: String): ShareVideo? = byPath[path]

    /** A film or episode by its id ([fileId]). */
    fun file(id: String): MediaTitle? {
        val v = byPath[pathOf(id) ?: return null] ?: return null
        return if (v.parsed.isEpisode) episode(v) else movie(v)
    }

    fun seriesTitle(id: String): MediaTitle? = series.firstOrNull { it.id == id }

    fun seasons(seriesId: String): List<MediaTitle> {
        val list = episodes[seriesId] ?: return emptyList()
        val series = seriesTitle(seriesId)
        return list.groupBy { it.season ?: 0 }.toSortedMap().map { (n, eps) ->
            MediaTitle(
                id = "$seriesId:$n",
                sourceId = sourceId,
                kind = MediaTitle.Kind.SEASON,
                name = seasonName(n),
                seriesId = seriesId,
                seriesName = series?.name,
                season = n,
                posterId = series?.posterId,
                posterTag = SHARE_TAG,
                fromShare = true,
            )
        }
    }

    private fun movie(v: ShareVideo) = MediaTitle(
        id = fileId(v.path),
        sourceId = sourceId,
        kind = MediaTitle.Kind.MOVIE,
        name = v.parsed.title.take(200),
        year = v.parsed.year,
        posterId = v.poster,
        posterTag = SHARE_TAG,
        backdropId = v.backdrop,
        backdropTag = SHARE_TAG,
        addedAt = v.modifiedAt,
        fromShare = true,
    )

    private fun episode(v: ShareVideo): MediaTitle {
        val sid = seriesId(v.parsed.title)
        return MediaTitle(
            id = fileId(v.path),
            sourceId = sourceId,
            kind = MediaTitle.Kind.EPISODE,
            name = (v.parsed.episodeName ?: v.parsed.title).take(200),
            seriesId = sid,
            seriesName = v.parsed.title.take(200),
            seasonId = "$sid:${v.parsed.season ?: 0}",
            season = v.parsed.season,
            episode = v.parsed.episode,
            // an episode without its own picture shows its series' (as in Jellyfin)
            posterId = v.poster ?: v.seriesPoster,
            posterTag = SHARE_TAG,
            backdropId = v.backdrop,
            backdropTag = SHARE_TAG,
            addedAt = v.modifiedAt,
            fromShare = true,
        )
    }

    companion object {
        const val MAX_DEPTH = 6
        const val MAX_DIRS = 600
        const val MAX_FILES = 8_000
        const val SHARE_TAG = "share"

        /** Folders asked at the same time (WebDAV away: one round trip each). */
        private const val PARALLEL = 4

        fun fileId(path: String) = "f:$path"
        fun pathOf(id: String): String? = id.takeIf { it.startsWith("f:") }?.removePrefix("f:")
        fun seriesId(name: String) = "s:" + ShareNames.key(name).ifEmpty { name }

        /**
         * The share's videos from its root. The root not reachable (or the access refused) is the answer; a folder
         * below that fails is left out.
         */
        suspend fun scan(fs: ShareFs, sourceId: String, seasonName: (Int) -> String = { "Staffel $it" }): MediaResult<ShareIndex> {
            val root = fs.list("")
            if (root !is MediaResult.Ok) {
                @Suppress("UNCHECKED_CAST")
                return root as MediaResult<ShareIndex>
            }
            val videos = ArrayList<ShareVideo>()
            var dirs = 1
            var level = listOf(Triple("", emptyList<String>(), root.value))
            var depth = 0
            val gate = Semaphore(PARALLEL)
            val imagesByDir = HashMap<String, Map<String, String>>()
            while (level.isNotEmpty() && videos.size < MAX_FILES) {
                val next = ArrayList<Pair<String, List<String>>>()
                for ((dir, names, entries) in level) {
                    imagesByDir[dir] = imagesOf(entries)
                    // a season folder: the series' picture is in the folder above
                    val above = if (names.lastOrNull()?.let(ShareNames::seasonOfDir) != null) imagesByDir[SharePaths.parent(dir)] else null
                    videos += videosOf(names, entries, parentImages = above)
                    if (depth < MAX_DEPTH) {
                        for (e in entries) if (e.directory && !ShareNames.skipDir(e.name) && dirs < MAX_DIRS) {
                            dirs++
                            next += e.path to (names + e.name)
                        }
                    }
                }
                depth++
                level = coroutineScope {
                    next.map { (path, names) ->
                        async { gate.withPermit { fs.list(path).valueOrNull()?.let { Triple(path, names, it) } } }
                    }.awaitAll().filterNotNull()
                }
            }
            return MediaResult.Ok(ShareIndex(sourceId, attachSeriesPosters(videos).take(MAX_FILES), seasonName))
        }

        /** The videos of one folder with the pictures beside them (Kodi's and Jellyfin's names). */
        private fun imagesOf(entries: List<ShareEntry>): Map<String, String> =
            entries.filter { !it.directory && ShareNames.isImage(it.name) }.associate { it.name.substringBeforeLast('.').lowercase() to it.path }

        internal fun videosOf(names: List<String>, entries: List<ShareEntry>, parentImages: Map<String, String>?): List<ShareVideo> {
            val images = imagesOf(entries)
            val files = entries.filter { !it.directory && ShareNames.isVideo(it.name) }
            val alone = files.size == 1
            return files.map { f ->
                val base = f.name.substringBeforeLast('.').lowercase()
                val parsed = ShareNames.parse(f.name, names)
                val folderPoster = listOf("poster", "folder", "cover").firstNotNullOfOrNull { images[it] }
                val poster = listOf("$base-poster", base, "$base-thumb", "$base-cover").firstNotNullOfOrNull { images[it] }
                    ?: folderPoster.takeIf { alone && !parsed.isEpisode }
                val backdrop = listOf("$base-fanart", "$base-backdrop").firstNotNullOfOrNull { images[it] }
                    ?: listOf("fanart", "backdrop").firstNotNullOfOrNull { images[it] }.takeIf { alone || parsed.isEpisode }
                ShareVideo(
                    path = f.path,
                    parsed = parsed,
                    size = f.size,
                    modifiedAt = f.modifiedAt,
                    poster = poster,
                    backdrop = backdrop,
                    seriesPoster = if (!parsed.isEpisode) {
                        null
                    } else {
                        parentImages?.let { p -> listOf("poster", "folder", "cover").firstNotNullOfOrNull { p[it] } } ?: folderPoster
                    },
                )
            }
        }

        /** An episode in a season folder takes the picture of the series' folder above, when its own folder has none. */
        private fun attachSeriesPosters(videos: List<ShareVideo>): List<ShareVideo> {
            val bySeries = videos.filter { it.parsed.isEpisode }.groupBy { seriesId(it.parsed.title) }
            val posterOf = bySeries.mapValues { (_, list) -> list.firstNotNullOfOrNull { it.seriesPoster } }
            return videos.map { v ->
                if (v.parsed.isEpisode && v.seriesPoster == null) v.copy(seriesPoster = posterOf[seriesId(v.parsed.title)]) else v
            }
        }
    }
}
