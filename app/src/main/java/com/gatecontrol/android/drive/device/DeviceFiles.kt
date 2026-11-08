package com.gatecontrol.android.drive.device

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.MediaStore
import android.content.ContentResolver
import com.gatecontrol.android.BuildConfig
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveLive
import com.gatecontrol.android.drive.DriveTransfers
import com.gatecontrol.android.drive.device.DeviceFilesLogic.Category
import com.gatecontrol.android.drive.device.DeviceFilesLogic.InDrive
import com.gatecontrol.android.support.CrashReports
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Auf dem Gerät": the phone's own files, read with "Zugriff auf alle Dateien" (MANAGE_EXTERNAL_STORAGE, only in the
 * builds that have it — [available]). The places come from Android's StorageManager (internal, SD card, USB stick:
 * [volumes], [volumeChanges] when one is attached or removed); the categories and "Zuletzt hinzugefügt" from
 * MediaStore (one pass over its file table, off the main thread, kept for [FRESH_MS] in [summary]); the Download
 * folder is counted by walking it (what the folder page shows); a folder is listed from the file system.
 * "In Drive" / "Nur hier" asks Drive's search for the name ([inDrive], the rule in [DeviceFilesLogic.inDrive]).
 */
@Singleton
class DeviceFiles @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DriveApi,
    transfers: DriveTransfers,
    live: DriveLive,
) {
    data class Volume(
        val path: String,
        val label: String,
        val kind: DeviceFilesLogic.VolumeKind,
        val total: Long,
        val free: Long,
        val primary: Boolean,
        val readOnly: Boolean,
    )

    data class Count(val files: Int = 0, val bytes: Long = 0) {
        operator fun plus(size: Long) = Count(files + 1, bytes + size.coerceAtLeast(0))
    }

    data class Summary(
        val at: Long,
        val total: Long,
        val used: Long,
        val bar: List<Pair<DeviceFilesLogic.Part, Float>>,
        val categories: Map<Category, Count>,
        val recent: List<DeviceEntry>,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)
    private val storage = context.getSystemService(StorageManager::class.java)

    /** This build may ask for "Zugriff auf alle Dateien" (not the Google Play build until Play allowed it). */
    val available: Boolean get() = BuildConfig.ALL_FILES_ACCESS

    /** The person allowed it (Android › Apps › GateControl › Berechtigungen). */
    fun hasAccess(): Boolean = available && Environment.isExternalStorageManager()

    // ─── places ────────────────────────────────────────────────────

    /** The mounted volumes: internal first, then SD cards, then USB sticks. */
    fun volumes(): List<Volume> {
        val list = runCatching { storage.storageVolumes }.getOrDefault(emptyList())
        return list.mapNotNull(::volumeOf).sortedBy { it.kind.ordinal }
    }

    private fun volumeOf(v: StorageVolume): Volume? {
        val state = v.state
        if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) return null
        val dir = v.directory ?: return null
        val label = runCatching { v.getDescription(context) }.getOrNull().orEmpty()
        val (total, free) = if (v.isPrimary) internalSpace() else space(dir)
        return Volume(dir.path, label, DeviceFilesLogic.volumeKind(v.isPrimary, label), total, free, v.isPrimary, state == Environment.MEDIA_MOUNTED_READ_ONLY)
    }

    /** The whole device as Android's settings show it (e.g. 256 GB), not only the data partition. */
    private fun internalSpace(): Pair<Long, Long> = runCatching {
        val stats = context.getSystemService(StorageStatsManager::class.java)
        stats.getTotalBytes(StorageManager.UUID_DEFAULT) to stats.getFreeBytes(StorageManager.UUID_DEFAULT)
    }.getOrElse { space(Environment.getExternalStorageDirectory()) }

    private fun space(dir: File): Pair<Long, Long> = runCatching { StatFs(dir.path).let { it.totalBytes to it.availableBytes } }.getOrDefault(0L to 0L)

    /** Free bytes where [path] lies. */
    fun freeAt(path: String): Long = runCatching { StatFs(path).availableBytes }.getOrDefault(0L)

    /** Something was attached, removed, mounted or ejected. */
    val volumeChanges: Flow<Unit> = callbackFlow {
        val cb = object : StorageManager.StorageVolumeCallback() {
            override fun onStateChanged(volume: StorageVolume) {
                trySend(Unit)
            }
        }
        storage.registerStorageVolumeCallback(context.mainExecutor, cb)
        awaitClose { runCatching { storage.unregisterStorageVolumeCallback(cb) } }
    }

    /** The volume [path] lies on (the longest root that contains it). */
    fun volumeOf(path: String, all: List<Volume> = volumes()): Volume? =
        all.filter { path == it.path || DeviceFilesLogic.inside(it.path, path) }.maxByOrNull { it.path.length }

    // ─── the start page ────────────────────────────────────────────

    private val _summary = MutableStateFlow<Summary?>(null)
    /** The last summary (shown at once next time), null before the first. */
    val summary: StateFlow<Summary?> = _summary.asStateFlow()
    private val summaryLock = Mutex()

    /** Counts again unless the last summary is younger than [FRESH_MS] ([force]: anyway). */
    suspend fun refresh(force: Boolean = false) = summaryLock.withLock {
        val last = _summary.value
        if (!force && last != null && System.currentTimeMillis() - last.at < FRESH_MS) return@withLock
        if (!hasAccess()) return@withLock
        val s = withContext(Dispatchers.IO) { runCatching { compute() }.onFailure { Timber.d("Device: summary failed (%s)", it.javaClass.simpleName) }.getOrNull() }
        if (s != null) _summary.value = s
    }

    private suspend fun compute(): Summary {
        val counts = HashMap<Category, Count>()
        var media = 0L
        var documents = 0L
        var audio = 0L
        val recent = ArrayList<Pair<Long, DeviceEntry>>()
        scanStore { row ->
            val type = DeviceFilesLogic.typeOf(row.entry.name, row.entry.mime)
            val cat = DeviceFilesLogic.categoryOf(type)
            if (cat != null) counts[cat] = (counts[cat] ?: Count()) + row.entry.size
            if (row.primary) {
                when (cat) {
                    Category.IMAGES, Category.VIDEOS -> media += row.entry.size
                    Category.DOCUMENTS, Category.ARCHIVES -> documents += row.entry.size
                    Category.AUDIO -> audio += row.entry.size
                    else -> Unit
                }
            }
            recent += row.added to row.entry
            if (recent.size > RECENT_KEEP * 4) {
                recent.sortByDescending { it.first }
                while (recent.size > RECENT_KEEP) recent.removeAt(recent.size - 1)
            }
        }
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        counts[Category.DOWNLOAD] = walk(downloads)
        val (total, free) = internalSpace()
        val used = (total - free).coerceAtLeast(0)
        return Summary(
            at = System.currentTimeMillis(),
            total = total,
            used = used,
            bar = DeviceFilesLogic.bar(total, used, media, documents, audio),
            categories = counts,
            recent = recent.sortedByDescending { it.first }.take(RECENT_KEEP).map { it.second },
        )
    }

    /** Files and their bytes below [dir] (not hidden ones), at most [WALK_MAX] files. */
    private suspend fun walk(dir: File): Count {
        var c = Count()
        var seen = 0
        val stack = ArrayDeque<File>().apply { add(dir) }
        while (stack.isNotEmpty() && seen < WALK_MAX) {
            currentCoroutineContext().ensureActive()
            val d = stack.removeLast()
            for (f in d.listFiles().orEmpty()) {
                if (f.name.startsWith('.')) continue
                if (f.isDirectory) stack += f else { c += f.length(); seen++ }
            }
        }
        return c
    }

    private class Row(val entry: DeviceEntry, val added: Long, val primary: Boolean)

    /** Every file of MediaStore's table on all volumes (not hidden ones, not the apps' own folders). */
    private suspend fun scanStore(each: (Row) -> Unit) {
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val cols = arrayOf(
            MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.VOLUME_NAME,
        )
        val args = Bundle().apply { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.MIME_TYPE} IS NOT NULL") }
        context.contentResolver.query(uri, cols, args, null)?.use { c ->
            while (c.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val path = c.getString(0) ?: continue
                if (DeviceFilesLogic.hidden(path)) continue
                val name = c.getString(1) ?: path.substringAfterLast('/')
                val entry = DeviceEntry(
                    path = path,
                    name = name,
                    isDir = false,
                    size = if (c.isNull(2)) 0L else c.getLong(2),
                    modified = (if (c.isNull(5)) 0L else c.getLong(5)) * 1000,
                    mime = c.getString(3),
                )
                each(Row(entry, if (c.isNull(4)) 0L else c.getLong(4), c.getString(6) == MediaStore.VOLUME_EXTERNAL_PRIMARY))
            }
        }
    }

    /** The files of [category] on all volumes (MediaStore; the Download folder is a folder page instead). */
    suspend fun category(category: Category): List<DeviceEntry> = withContext(Dispatchers.IO) {
        val out = ArrayList<DeviceEntry>()
        runCatching {
            scanStore { row ->
                if (DeviceFilesLogic.categoryOf(DeviceFilesLogic.typeOf(row.entry.name, row.entry.mime)) == category) out += row.entry
            }
        }.onFailure { Timber.d("Device: category failed (%s)", it.javaClass.simpleName) }
        out
    }

    /** "Zuletzt hinzugefügt" › Alle: the files added in the last [days] days, newest first, at most [max]. */
    suspend fun recent(days: Int = 30, max: Int = 500): List<DeviceEntry> = withContext(Dispatchers.IO) {
        val since = System.currentTimeMillis() / 1000 - days * 86_400L
        val out = ArrayList<Pair<Long, DeviceEntry>>()
        runCatching { scanStore { row -> if (row.added >= since) out += row.added to row.entry } }
        out.sortedByDescending { it.first }.take(max).map { it.second }
    }

    /** The content of folder [dir] (hidden ones left out); null when Android does not let the app read it. */
    suspend fun list(dir: String): List<DeviceEntry>? = withContext(Dispatchers.IO) {
        val files = File(dir).listFiles() ?: return@withContext null
        files.filter { !it.name.startsWith('.') }.map { f ->
            val isDir = f.isDirectory
            DeviceEntry(
                path = f.path,
                name = f.name,
                isDir = isDir,
                size = if (isDir) 0L else f.length(),
                modified = f.lastModified(),
                mime = null,
                children = if (isDir) f.list()?.count { !it.startsWith('.') } else null,
            )
        }
    }

    // ─── "In Drive" ────────────────────────────────────────────────

    private class Hits(val hits: List<DeviceFilesLogic.Hit>, val at: Long)

    private val hits = ConcurrentHashMap<String, Hits>()
    private val asking = Semaphore(SEARCHES)

    init {
        // an upload from here, a change in Drive: what Drive has is asked again
        scope.launch { transfers.changed.collect { hits.clear() } }
        scope.launch { live.changed.collect { hits.clear() } }
    }

    /** "In Drive", "Nur hier" or nothing ([InDrive.UNKNOWN]) for [e] — Drive's search by name, kept a few minutes. */
    suspend fun inDrive(e: DeviceEntry): InDrive {
        if (e.isDir || !DeviceFilesLogic.searchable(e.name)) return InDrive.UNKNOWN
        val key = e.name.trim().lowercase()
        val now = System.currentTimeMillis()
        val cached = hits[key]?.takeIf { now - it.at < HITS_MS }
        val found = cached?.hits ?: asking.withPermit {
            hits[key]?.takeIf { now - it.at < HITS_MS }?.hits ?: when (val r = api.search(e.name.trim())) {
                is DResult.Ok -> r.value.map { DeviceFilesLogic.Hit(it.name, it.size, it.isDir) }.also { hits[key] = Hits(it, System.currentTimeMillis()) }
                else -> null
            }
        }
        return DeviceFilesLogic.inDrive(e.name, e.size, found)
    }

    private companion object {
        const val FRESH_MS = 60_000L
        const val HITS_MS = 5 * 60_000L
        const val SEARCHES = 3
        const val RECENT_KEEP = 3
        const val WALK_MAX = 50_000
    }
}
