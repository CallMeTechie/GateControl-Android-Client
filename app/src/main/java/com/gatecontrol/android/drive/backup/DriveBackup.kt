package com.gatecontrol.android.drive.backup

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.DrivePreviews
import com.gatecontrol.android.drive.DriveSource
import com.gatecontrol.android.familie.PluginDirectory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.FileNotFoundException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Photo backup (concept Teil D): the camera's photos (and videos, if wanted) go up as originals into one Drive
 * folder, oldest first. A mark (date added, id) remembers how far it got, so a run that stops (no WLAN, the app is
 * killed) goes on where it was; a photo already in the folder with the same content is not sent again. Runs come
 * from [DriveBackupWork] (new photos, every hour, "Jetzt sichern").
 */
@Singleton
class DriveBackup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DriveApi,
    private val setup: SetupRepository,
    private val previews: DrivePreviews,
) {
    /** The run in progress: [done] of [total] photos, [current] the one going up. */
    data class Status(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val current: String? = null)

    /** What happened so far: photos backed up, the last finished run, the reason it stopped (a code). */
    data class Stats(val saved: Int = 0, val lastRunAt: Long = 0L, val error: String? = null)

    enum class Outcome { DONE, RETRY, STOPPED }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val runLock = Mutex()

    private val _config = MutableStateFlow(readConfig())
    val config: StateFlow<DriveBackupConfig> = _config.asStateFlow()
    private val _stats = MutableStateFlow(readStats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    // ─── settings ───────────────────────────────────────────────────

    private fun owner() = PluginDirectory.ownerKey(setup.getServerUrl(), setup.getApiToken())

    private fun readConfig(): DriveBackupConfig {
        if (prefs.getString(KEY_OWNER, null) != owner()) return DriveBackupConfig()
        return DriveBackupConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            folderId = prefs.getLong(KEY_FOLDER, 0L).takeIf { it > 0 },
            folderLabel = prefs.getString(KEY_LABEL, null),
            wifiOnly = prefs.getBoolean(KEY_WIFI, true),
            chargingOnly = prefs.getBoolean(KEY_CHARGING, false),
            videos = prefs.getBoolean(KEY_VIDEOS, false),
            cameraOnly = prefs.getBoolean(KEY_CAMERA, true),
        )
    }

    private fun readStats() = if (prefs.getString(KEY_OWNER, null) != owner()) Stats() else Stats(
        saved = prefs.getInt(KEY_SAVED, 0),
        lastRunAt = prefs.getLong(KEY_LAST, 0L),
        error = prefs.getString(KEY_ERROR, null),
    )

    /** Where the backup stands; null: never started for this login. */
    fun mark(): DriveBackupMark? {
        if (prefs.getString(KEY_OWNER, null) != owner() || !prefs.contains(KEY_MARK_ADDED)) return null
        return DriveBackupMark(prefs.getLong(KEY_MARK_ADDED, 0L), prefs.getLong(KEY_MARK_ID, 0L))
    }

    /** Changes the settings and the scheduled work with them. */
    fun update(f: (DriveBackupConfig) -> DriveBackupConfig) {
        val next = f(_config.value)
        prefs.edit {
            if (prefs.getString(KEY_OWNER, null) != owner()) {
                clear()
                putString(KEY_OWNER, owner())
            }
            putBoolean(KEY_ENABLED, next.enabled)
            putLong(KEY_FOLDER, next.folderId ?: 0L)
            putString(KEY_LABEL, next.folderLabel)
            putBoolean(KEY_WIFI, next.wifiOnly)
            putBoolean(KEY_CHARGING, next.chargingOnly)
            putBoolean(KEY_VIDEOS, next.videos)
            putBoolean(KEY_CAMERA, next.cameraOnly)
            if (next.folderId != _config.value.folderId || next.enabled != _config.value.enabled) remove(KEY_ERROR)
        }
        _config.value = next
        _stats.value = readStats()
        DriveBackupWork.apply(context, next)
    }

    /** Switches it on: into [folderId], from [mark] on (all photos, or only those from now on). */
    fun enable(folderId: Long, label: String, mark: DriveBackupMark) {
        // The mark first: switching on schedules a pass at once, which must not start from "all photos".
        prefs.edit {
            if (prefs.getString(KEY_OWNER, null) != owner()) {
                clear()
                putString(KEY_OWNER, owner())
            }
            putLong(KEY_MARK_ADDED, mark.dateAdded)
            putLong(KEY_MARK_ID, mark.id)
        }
        update { it.copy(enabled = true, folderId = folderId, folderLabel = label) }
        DriveBackupWork.runNow(context)
    }

    /** "Only new photos": the mark at the newest photo there is now. */
    fun markNow(): DriveBackupMark = DriveBackupMark(System.currentTimeMillis() / 1000, Long.MAX_VALUE)

    // ─── permission and counting ────────────────────────────────────

    /** Reading photos is allowed (all, or on Android 14 the ones the person picked). */
    fun canRead(videos: Boolean = _config.value.videos): Boolean {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> true
            Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES) && (!videos || granted(Manifest.permission.READ_MEDIA_VIDEO))
            else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /** Android 14: only some photos are shared with the app. */
    fun partialAccess(): Boolean = Build.VERSION.SDK_INT >= 34 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED

    private fun original(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Photos still to back up; null when it cannot tell. */
    suspend fun pending(): Int? = withContext(Dispatchers.IO) {
        val cfg = _config.value
        val mark = mark() ?: return@withContext null
        if (!cfg.enabled || !canRead(cfg.videos)) return@withContext null
        runCatching {
            val q = DriveBackupLogic.query(cfg, mark)
            context.contentResolver.query(COLLECTION, arrayOf(MediaStore.MediaColumns._ID), q.selection, q.args, null)?.use { it.count }
        }.getOrNull()
    }

    // ─── run ────────────────────────────────────────────────────────

    private data class Item(val id: Long, val uri: Uri, val name: String, val mime: String?, val mtime: Long?, val added: Long, val video: Boolean)

    /** One pass over what is new; [onProgress] hears each photo. */
    suspend fun run(onProgress: (Status) -> Unit = {}): Outcome = runLock.withLock {
        withContext(Dispatchers.IO) {
            try {
                pass(onProgress)
            } finally {
                _status.value = Status()
            }
        }
    }

    private suspend fun pass(onProgress: (Status) -> Unit): Outcome {
        val cfg = readConfig().also { _config.value = it }
        // Off, or another login since: no more work.
        if (!cfg.enabled) return Outcome.DONE.also { DriveBackupWork.apply(context, cfg) }
        val folder = cfg.folderId ?: return stop("no_folder")
        if (!canRead(cfg.videos)) return stop("no_permission")
        var done = 0
        while (true) {
            val mark = mark() ?: DriveBackupMark.ALL
            val (items, total) = list(cfg, mark)
            if (items.isEmpty()) break
            for (item in items) {
                currentCoroutineContext().ensureActive()
                _status.value = Status(true, done, done + total, item.name).also(onProgress)
                val code = one(item, folder)
                if (code == null) {
                    advance(item, saved = true)
                } else {
                    when (DriveBackupLogic.next(code)) {
                        DriveBackupLogic.Next.SKIP -> advance(item, saved = false).also { Timber.i("Drive backup: skipped a photo (%s)", code) }
                        DriveBackupLogic.Next.STOP -> return stop(code)
                        DriveBackupLogic.Next.RETRY -> return retry(code)
                        DriveBackupLogic.Next.TRY -> {
                            val tries = prefs.getInt(KEY_TRIES, 0) + 1
                            if (tries >= DriveBackupLogic.MAX_TRIES) {
                                advance(item, saved = false)
                                Timber.i("Drive backup: gave up on a photo (%s)", code)
                            } else {
                                prefs.edit { putInt(KEY_TRIES, tries) }
                                return retry(code)
                            }
                        }
                    }
                }
                done++
            }
        }
        prefs.edit {
            putLong(KEY_LAST, System.currentTimeMillis())
            remove(KEY_ERROR)
        }
        _stats.value = readStats()
        return Outcome.DONE
    }

    /** Up to [BATCH] items after [mark] and how many there are in all. */
    private fun list(cfg: DriveBackupConfig, mark: DriveBackupMark): Pair<List<Item>, Int> {
        val q = DriveBackupLogic.query(cfg, mark)
        val cols = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        val withOriginal = original()
        return context.contentResolver.query(COLLECTION, cols, q.selection, q.args, q.sort)?.use { c ->
            val out = ArrayList<Item>()
            while (out.size < BATCH && c.moveToNext()) {
                val id = c.getLong(0)
                val video = c.getInt(6) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                var uri = ContentUris.withAppendedId(
                    if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id,
                )
                if (withOriginal) uri = MediaStore.setRequireOriginal(uri)
                out += Item(
                    id = id,
                    uri = uri,
                    name = DriveBackupLogic.name(c.getString(1), id, video),
                    mime = c.getString(2),
                    mtime = DriveBackupLogic.mtime(if (c.isNull(4)) null else c.getLong(4), if (c.isNull(5)) null else c.getLong(5)),
                    added = c.getLong(3),
                    video = video,
                )
            }
            out to c.count
        } ?: (emptyList<Item>() to 0)
    }

    /** Backs up one photo; null when it is in Drive now (sent, or there already), else a code. */
    private suspend fun one(item: Item, folder: Long): String? {
        val (sha, length) = try {
            hash(item.uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e !is FileNotFoundException && e !is SecurityException) Timber.d("Drive backup: cannot read (%s)", e.javaClass.simpleName)
            return "read"
        }
        if (length == 0L) return "empty"
        when (val check = api.uploadCheck(folder, item.name, sha, length)) {
            is DResult.Ok -> if (check.value.same) return null
            else -> return code(check)
        }
        val media = api.upload(DriveSource.of(context, item.uri, length), item.mime)
        if (media !is DResult.Ok) return code(media)
        // HEIC / video: the server cannot make a preview — send the phone's own before the commit (best effort)
        previews.uploadOwn(media.value, item.uri, item.mime)
        return when (val r = api.commit(media.value, folder, item.name, item.mtime, DriveLogic.ON_CONFLICT_RENAME)) {
            is DResult.Ok -> null
            else -> code(r)
        }
    }

    private fun hash(uri: Uri): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        var length = 0L
        val input = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
        input.use {
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                length += n
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } to length
    }

    private fun advance(item: Item, saved: Boolean) {
        prefs.edit {
            putLong(KEY_MARK_ADDED, item.added)
            putLong(KEY_MARK_ID, item.id)
            remove(KEY_TRIES)
            if (saved) putInt(KEY_SAVED, prefs.getInt(KEY_SAVED, 0) + 1)
        }
        if (saved) _stats.value = _stats.value.copy(saved = _stats.value.saved + 1)
    }

    private fun stop(code: String): Outcome {
        Timber.i("Drive backup: stopped (%s)", code)
        prefs.edit { putString(KEY_ERROR, code) }
        _stats.value = readStats()
        return Outcome.STOPPED
    }

    private fun retry(code: String): Outcome {
        Timber.i("Drive backup: will retry (%s)", code)
        return Outcome.RETRY
    }

    private fun code(r: DResult<*>): String = when (r) {
        is DResult.Failed -> r.code
        DResult.Offline -> "offline"
        DResult.NotConfigured -> "not_configured"
        is DResult.Ok -> "invalid"
    }

    companion object {
        private val COLLECTION: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        private const val BATCH = 200
        private const val PREFS = "drive_backup"
        private const val KEY_OWNER = "owner"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_FOLDER = "folder"
        private const val KEY_LABEL = "label"
        private const val KEY_WIFI = "wifi"
        private const val KEY_CHARGING = "charging"
        private const val KEY_VIDEOS = "videos"
        private const val KEY_CAMERA = "camera"
        private const val KEY_MARK_ADDED = "mark_added"
        private const val KEY_MARK_ID = "mark_id"
        private const val KEY_TRIES = "tries"
        private const val KEY_SAVED = "saved"
        private const val KEY_LAST = "last"
        private const val KEY_ERROR = "error"
    }
}
