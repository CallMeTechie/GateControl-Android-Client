package com.gatecontrol.android.media.share

import com.gatecontrol.android.media.MediaDetail
import com.gatecontrol.android.media.MediaLibrary
import com.gatecontrol.android.media.MediaPage
import com.gatecontrol.android.media.MediaPlan
import com.gatecontrol.android.media.MediaPlay
import com.gatecontrol.android.media.MediaProgress
import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaSideSubtitle
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.MediaVersion
import com.gatecontrol.android.media.PlayMethod
import com.gatecontrol.android.media.ShareProgress
import com.gatecontrol.android.media.ShareProgressStore
import com.gatecontrol.android.media.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * An SMB or WebDAV share as a [MediaLibrary]: its films and series from the folders ([ShareIndex], the file names and
 * the pictures beside them), always played as the original (no converting), the progress kept on the device
 * ([ShareProgressStore]; Jellyfin keeps its own). [source]: the share the titles carry (of a pair SMB + WebDAV the
 * same one, whichever [fs] is used now); [progressKey] the share's part of the progress keys.
 */
class ShareLibrary(
    override val source: MediaSource,
    private val fs: ShareFs,
    private val store: ShareProgressStore,
    private val progressKey: String = source.id,
    private val seasonName: (Int) -> String = { "Staffel $it" },
    private val now: () -> Long = System::currentTimeMillis,
) : MediaLibrary {

    private val lock = Mutex()

    @Volatile
    private var index: ShareIndex? = null

    @Volatile
    private var indexedAt = 0L

    /** When each file's progress was written last (the player reports every few seconds). */
    private val written = ConcurrentHashMap<String, Long>()

    /** The share's index, scanned again after [INDEX_MS]. */
    suspend fun index(force: Boolean = false): MediaResult<ShareIndex> = lock.withLock {
        val i = index
        if (i != null && !force && now() - indexedAt < INDEX_MS) return@withLock MediaResult.Ok(i)
        val r = ShareIndex.scan(fs, source.id, seasonName)
        if (r is MediaResult.Ok) {
            index = r.value
            indexedAt = now()
        }
        // not reachable now: what was found before is still right
        if (r !is MediaResult.Ok && i != null && r is MediaResult.Offline) MediaResult.Ok(i) else r
    }

    private fun key(path: String) = "$progressKey|$path"

    /** [t] with where it was left (a film or episode) or its unwatched episodes (a series). */
    private fun withProgress(t: MediaTitle, all: Map<String, ShareProgress>, i: ShareIndex): MediaTitle {
        val path = ShareIndex.pathOf(t.id)
        if (path != null) {
            val p = all[key(path)] ?: return t
            return t.copy(
                positionMs = if (p.played) 0 else p.positionMs,
                runtimeMs = p.durationMs.takeIf { it > 0 } ?: t.runtimeMs,
                played = p.played,
                playedAt = p.at.takeIf { it > 0 },
            )
        }
        if (t.kind == MediaTitle.Kind.SERIES || t.kind == MediaTitle.Kind.SEASON) {
            val eps = i.episodes[t.seriesId ?: t.id].orEmpty().filter { t.kind == MediaTitle.Kind.SERIES || it.season == t.season }
            val unplayed = eps.count { e -> ShareIndex.pathOf(e.id)?.let { all[key(it)]?.played } != true }
            return t.copy(unplayed = unplayed, played = eps.isNotEmpty() && unplayed == 0)
        }
        return t
    }

    private fun progressAll(): Map<String, ShareProgress> = store.all("$progressKey|")

    private suspend fun <T> withIndex(f: (ShareIndex, Map<String, ShareProgress>) -> T): MediaResult<T> =
        when (val r = index()) {
            is MediaResult.Ok -> MediaResult.Ok(f(r.value, progressAll()))
            is MediaResult.Offline -> r
            is MediaResult.Unauthorized -> r
            is MediaResult.NotFound -> r
            is MediaResult.Failed -> r
        }

    override suspend fun resume(limit: Int): MediaResult<List<MediaTitle>> = withIndex { i, all ->
        (i.movies + i.episodes.values.flatten()).map { withProgress(it, all, i) }
            .filter { it.resumable }
            .sortedByDescending { it.playedAt ?: 0 }
            .take(limit)
    }

    override suspend fun latest(limit: Int): MediaResult<List<MediaTitle>> = withIndex { i, all ->
        (i.movies + i.series).map { withProgress(it, all, i) }
            .sortedByDescending { it.addedAt ?: 0 }
            .take(limit)
    }

    override suspend fun movies(start: Int, limit: Int): MediaResult<MediaPage> = withIndex { i, all ->
        MediaPage(i.movies.drop(start).take(limit).map { withProgress(it, all, i) }, i.movies.size, start)
    }

    override suspend fun series(start: Int, limit: Int): MediaResult<MediaPage> = withIndex { i, all ->
        MediaPage(i.series.drop(start).take(limit).map { withProgress(it, all, i) }, i.series.size, start)
    }

    override suspend fun seasons(seriesId: String): MediaResult<List<MediaTitle>> = withIndex { i, all ->
        i.seasons(seriesId).map { withProgress(it, all, i) }
    }

    override suspend fun episodes(seriesId: String, seasonId: String?): MediaResult<List<MediaTitle>> = withIndex { i, all ->
        i.episodes[seriesId].orEmpty().filter { seasonId == null || it.seasonId == seasonId }.map { withProgress(it, all, i) }
    }

    override suspend fun detail(itemId: String): MediaResult<MediaDetail> {
        val r = index()
        if (r !is MediaResult.Ok) {
            // a file of the page "Ordner" is found by its folder too
            val loose = looseFile(itemId) ?: return r.map { error("unreachable") }
            return MediaResult.Ok(MediaDetail(withFileProgress(loose.title, progressAll()), listOf(version(loose.entry.path, loose.title))))
        }
        val i = r.value
        val all = progressAll()
        val file = i.file(itemId)
        if (file != null) return MediaResult.Ok(MediaDetail(withProgress(file, all, i), listOf(version(ShareIndex.pathOf(itemId)!!, file))))
        looseFile(itemId)?.let { return MediaResult.Ok(MediaDetail(withFileProgress(it.title, all), listOf(version(it.entry.path, it.title)))) }
        val series = i.seriesTitle(itemId) ?: return MediaResult.NotFound
        return MediaResult.Ok(MediaDetail(withProgress(series, all, i), emptyList()))
    }

    /**
     * A video the index does not have (deeper than it searches, in "Extras", added since): from its folder, as the
     * page "Ordner" shows it. Null when it is not there (or the folder cannot be read).
     */
    private suspend fun looseFile(itemId: String): ShareFolderVideo? {
        val path = ShareIndex.pathOf(itemId)?.let(SharePaths::clean)?.takeIf { it.isNotEmpty() } ?: return null
        val dir = SharePaths.parent(path)
        val entries = fs.list(dir).valueOrNull() ?: return null
        return ShareFolders.build(source.id, dir, entries, seasonName).videos.firstOrNull { it.entry.path == path }
    }

    /** [t] (a file) with where it was left. */
    private fun withFileProgress(t: MediaTitle, all: Map<String, ShareProgress>): MediaTitle {
        val path = ShareIndex.pathOf(t.id) ?: return t
        val p = all[key(path)] ?: return t
        return t.copy(
            positionMs = if (p.played) 0 else p.positionMs,
            runtimeMs = p.durationMs.takeIf { it > 0 } ?: t.runtimeMs,
            played = p.played,
            playedAt = p.at.takeIf { it > 0 },
        )
    }

    /**
     * Folder [dir] of the share for the page "Ordner": its sub-folders and videos (title from the file name, the
     * picture beside it, the progress kept on the phone, subtitle files beside it).
     */
    suspend fun folder(dir: String): MediaResult<ShareFolder> {
        val clean = SharePaths.clean(dir) ?: return MediaResult.NotFound
        return fs.list(clean).map { entries ->
            val f = ShareFolders.build(source.id, clean, entries, seasonName)
            val all = progressAll()
            f.copy(videos = f.videos.map { v -> v.copy(title = withFileProgress(v.title, all)) })
        }
    }

    /** How many folders and videos [dir] holds (the row of a folder); null when it cannot be read. */
    suspend fun count(dir: String): ShareFolderCount? =
        SharePaths.clean(dir)?.let { fs.list(it).valueOrNull() }?.let(ShareFolders::count)

    /**
     * "Im Ordner suchen": the videos below [dir] (all folders down, from the index) whose file name holds [text], at
     * most [limit], with their progress.
     */
    suspend fun searchBelow(dir: String, text: String, limit: Int = 60): MediaResult<List<ShareFolderVideo>> {
        val clean = SharePaths.clean(dir) ?: return MediaResult.NotFound
        val prefix = if (clean.isEmpty()) "" else "$clean/"
        return withIndex { i, all ->
            i.videos.asSequence()
                .filter { it.path.startsWith(prefix) && ShareFolders.matches(SharePaths.name(it.path), text) }
                .mapNotNull { v ->
                    i.file(ShareIndex.fileId(v.path))?.let { t ->
                        ShareFolderVideo(ShareEntry(SharePaths.name(v.path), v.path, directory = false, size = v.size, modifiedAt = v.modifiedAt), withFileProgress(t, all))
                    }
                }
                .sortedBy { ShareNames.sortKey(it.entry.name) }
                .take(limit)
                .toList()
        }
    }

    /** The file as it is: the container by its extension, the length once the player told it. */
    private fun version(path: String, t: MediaTitle) = MediaVersion(
        id = path,
        container = ShareNames.extension(path).ifEmpty { null },
        runtimeMs = t.runtimeMs,
        supportsDirectPlay = true,
    )

    /**
     * Always the original, straight from the share (there is no server that could convert), with the subtitle files
     * beside it (`Name.de.srt`): offered in the player, not switched on by themselves.
     */
    override suspend fun play(play: MediaPlay): MediaResult<MediaPlan> {
        val path = ShareIndex.pathOf(play.itemId) ?: return MediaResult.NotFound
        val clean = SharePaths.clean(path)?.takeIf { it.isNotEmpty() } ?: return MediaResult.NotFound
        val dir = SharePaths.parent(clean)
        val entries = fs.list(dir).valueOrNull()
        val title = index().valueOrNull()?.file(play.itemId)
            ?: entries?.let { e -> ShareFolders.build(source.id, dir, e, seasonName).videos.firstOrNull { it.entry.path == clean }?.title }
            ?: return MediaResult.NotFound
        val entry = entries?.firstOrNull { it.path == clean }
        val subtitles = entry?.let { ShareFolders.subtitlesOf(it, entries) }.orEmpty()
        return MediaResult.Ok(
            MediaPlan(
                itemId = play.itemId,
                url = ShareUri.of(clean),
                method = PlayMethod.DIRECT_PLAY,
                version = version(clean, title),
                playSessionId = null,
                hls = false,
                audioIndex = null,
                subtitleIndex = null,
                sideSubtitles = subtitles.mapIndexed { n, s ->
                    MediaSideSubtitle(
                        index = SIDE_SUBTITLE_BASE + n,
                        url = ShareUri.of(s.path),
                        mime = s.mime,
                        language = s.language,
                        label = if (s.language == null) ShareNames.extension(s.path).uppercase(java.util.Locale.ROOT) else null,
                        default = false,
                        forced = s.forced,
                    )
                },
            ),
        )
    }

    override suspend fun started(p: MediaProgress) = keep(p, force = true)

    override suspend fun progress(p: MediaProgress) = keep(p, force = false)

    override suspend fun stopped(p: MediaProgress) = keep(p, force = true)

    /** Where the player is, written at the start and the end and else every [WRITE_MS]. */
    private fun keep(p: MediaProgress, force: Boolean) {
        val path = ShareIndex.pathOf(p.plan.itemId) ?: return
        val t = now()
        val last = written[path] ?: 0
        if (!force && t - last < WRITE_MS) return
        written[path] = t
        val old = store.progress(key(path))
        val duration = p.durationMs.takeIf { it > 0 } ?: old?.durationMs ?: 0
        val played = duration > 0 && p.positionMs >= duration * PLAYED_SHARE
        val position = if (played || p.positionMs < MIN_RESUME_MS) 0 else p.positionMs
        // a short look into a file already watched does not take "gesehen" away
        store.setProgress(key(path), ShareProgress(position, duration, played || (old?.played == true && position == 0L), t))
    }

    override suspend fun setPlayed(itemId: String, played: Boolean): MediaResult<Unit> {
        val path = ShareIndex.pathOf(itemId) ?: return MediaResult.NotFound
        val old = store.progress(key(path))
        store.setProgress(key(path), ShareProgress(0, old?.durationMs ?: 0, played, now()))
        return MediaResult.Ok(Unit)
    }

    /** A share has no bandwidth test; it plays the original in any case. */
    override suspend fun measure(): Long? = null

    override fun imageUrl(title: MediaTitle, backdrop: Boolean, maxHeight: Int): String? = null

    override suspend fun imageBytes(title: MediaTitle, backdrop: Boolean): ByteArray? {
        val path = (if (backdrop) title.backdropId else title.posterId) ?: return null
        return fs.bytes(SharePaths.clean(path) ?: return null, MAX_PICTURE_BYTES)
    }

    override fun headers(): Map<String, String> = emptyMap()

    override fun files(): ShareFs = fs

    override fun close() = fs.close()

    companion object {
        /** The folders are searched again after this long (a new film on the NAS shows up). */
        const val INDEX_MS = 10 * 60_000L

        /** The player's progress is written at most this often. */
        const val WRITE_MS = 30_000L

        /** Watched to here (90 %, as Jellyfin): "gesehen". */
        const val PLAYED_SHARE = 0.9

        /** Less than this from the start is no place to go on from. */
        const val MIN_RESUME_MS = 30_000L

        const val MAX_PICTURE_BYTES = 4 * 1024 * 1024

        /** The indexes the subtitle files beside a video get (the file's own tracks are counted by the player). */
        const val SIDE_SUBTITLE_BASE = 1_000
    }
}
