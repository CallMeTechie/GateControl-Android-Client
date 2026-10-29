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
import com.gatecontrol.android.drive.DriveLive
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.DrivePreviews
import com.gatecontrol.android.drive.DriveSource
import com.gatecontrol.android.drive.DriveTakenAt
import com.gatecontrol.android.drive.DMe
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
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Photo backup (concept Teil D): the camera's photos (and videos, if wanted) go up as originals, oldest first, into
 * "Meine Dateien › Bilder › Kamera-Uploads › <Jahr> › <Monat>" ([DriveCameraUploads]). A mark (date added, id)
 * remembers how far it got, so a run that stops (no WLAN, the app is killed) goes on where it was — and a photo before
 * the mark is never sent again, wherever it went then; a photo already in its month's folder with the same content is
 * not sent again either. Runs come from [DriveBackupWork] (new photos, every hour, "Jetzt sichern").
 */
@Singleton
class DriveBackup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DriveApi,
    private val setup: SetupRepository,
    private val previews: DrivePreviews,
    private val live: DriveLive,
    private val dates: DriveTakenAt,
    private val camera: DriveCameraUploads,
    private val work: com.gatecontrol.android.drive.work.DriveWorkPhotos,
    private val wifi: com.gatecontrol.android.drive.work.DriveWorkWifi,
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
            putBoolean(KEY_WIFI, next.wifiOnly)
            putBoolean(KEY_CHARGING, next.chargingOnly)
            putBoolean(KEY_VIDEOS, next.videos)
            putBoolean(KEY_CAMERA, next.cameraOnly)
            if (next.enabled != _config.value.enabled) remove(KEY_ERROR)
        }
        _config.value = next
        _stats.value = readStats()
        DriveBackupWork.apply(context, next)
    }

    /** Switches it on, from [mark] on (all photos, or only those from now on — [DriveBackupLogic.startMark]). */
    fun enable(mark: DriveBackupMark) {
        // The mark first: switching on schedules a pass at once, which must not start from "all photos".
        prefs.edit {
            if (prefs.getString(KEY_OWNER, null) != owner()) {
                clear()
                putString(KEY_OWNER, owner())
            }
            putLong(KEY_MARK_ADDED, mark.dateAdded)
            putLong(KEY_MARK_ID, mark.id)
        }
        update { it.copy(enabled = true) }
        DriveBackupWork.runNow(context)
    }

    /**
     * Once for a login that backed up into a folder of its own choosing before Kamera-Uploads: true the first time it
     * is asked (the settings are opened), then never again. The photos backed up then stay where they are.
     */
    fun takeTargetNotice(): Boolean {
        if (prefs.getString(KEY_OWNER, null) != owner()) return false
        val show = DriveBackupLogic.targetNotice(prefs.getLong(KEY_FOLDER, 0L))
        if (show) prefs.edit { remove(KEY_FOLDER); remove(KEY_LABEL) }
        return show
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

    private data class Item(
        val id: Long,
        val uri: Uri,
        val name: String,
        val mime: String?,
        val mtime: Long?,
        val added: Long,
        val video: Boolean,
        /** The media store's DATE_TAKEN (ms), when it has one. */
        val taken: Long? = null,
        /** The media store's DATE_MODIFIED (ms), when it has one. */
        val modified: Long? = null,
    )

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
        if (!canRead(cfg.videos)) return stop("no_permission")
        // Stopped by Drive's ransomware protection: no photo is tried until it is lifted (that starts a pass).
        if (live.locked.value) return stop("device_locked")
        // work photos apart (Drive 1.13): the settings as the server has them, the WLAN now on the timeline
        if (work.settings.value.active || live.supports(DMe.FEATURE_PHOTO_CLASSES)) {
            work.refreshSettings()
            runCatching { wifi.sample() }
        }
        var done = 0
        while (true) {
            val mark = mark() ?: DriveBackupMark.ALL
            val (items, total) = list(cfg, mark)
            if (items.isEmpty()) break
            for (item in items) {
                currentCoroutineContext().ensureActive()
                _status.value = Status(true, done, done + total, item.name).also(onProgress)
                val code = one(item)
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
                    taken = if (c.isNull(4)) null else c.getLong(4),
                    modified = if (c.isNull(5)) null else c.getLong(5) * 1000,
                )
            }
            out to c.count
        } ?: (emptyList<Item>() to 0)
    }

    /** Backs up one photo; null when it is in Drive now (sent, or there already), else a code. */
    private suspend fun one(item: Item): String? {
        val (sha, length) = try {
            hash(item.uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e !is FileNotFoundException && e !is SecurityException) Timber.d("Drive backup: cannot read (%s)", e.javaClass.simpleName)
            return "read"
        }
        if (length == 0L) return "empty"
        // when it was taken — EXIF (with its time zone), else the media store's date: its year and month are its folder
        val own = dates.of(item.uri, item.mime, item.name)
        val path = DriveBackupLogic.cameraPath(own, item.taken, item.modified, item.added * 1000, ZoneId.systemDefault())
        // work photos apart: where this one goes (the WLAN it was taken in, else the AI on the phone); null: Kamera-Uploads
        var placed = try {
            // the plain URI: the thumbnail does not need the original's location data
            val plain = ContentUris.withAppendedId(if (item.video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, item.id)
            work.placeNew(plain, DriveBackupLogic.takenMs(own, item.taken, item.modified), item.video)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Drive backup: not placed (%s)", e.javaClass.simpleName)
            null
        }
        suspend fun target(): DResult<Long> {
            val p = placed
            if (p != null && p.placement.dest == com.gatecontrol.android.drive.work.DriveWorkLogic.Dest.WORK) {
                when (val w = work.workFolderFor(path)) {
                    is DResult.Ok -> return w
                    // the work folder is gone or read-only: the photo goes the private way, to be reviewed
                    is DResult.Failed -> if (w.code in WORK_GONE) placed = p.copy(placement = DriveBackupLogic.reviewInstead(p.placement)) else return w
                    else -> return w
                }
            }
            return camera.folder(path)
        }
        var folder = when (val f = target()) {
            is DResult.Ok -> f.value
            else -> return code(f)
        }
        var check = api.uploadCheck(folder, item.name, sha, length)
        if (check is DResult.Failed && check.code == "not_found") {
            // the month's folder (or Kamera-Uploads) went away since: made again
            camera.forget()
            folder = when (val f = target()) {
                is DResult.Ok -> f.value
                else -> return code(f)
            }
            check = api.uploadCheck(folder, item.name, sha, length)
        }
        when (check) {
            is DResult.Ok -> if (check.value.same) return null
            else -> return code(check)
        }
        val media = api.upload(DriveSource.of(context, item.uri, length), item.mime)
        if (media !is DResult.Ok) return code(media)
        // HEIC / video: the server cannot make a preview — send the phone's own before the commit (best effort)
        previews.uploadOwn(media.value, item.uri, item.mime)
        // Drive 1.7: the commit takes when it was taken
        val takenAt = if (live.supports(DMe.FEATURE_TAKEN_AT)) own ?: DriveLogic.msTakenAt(item.taken) else null
        return when (val r = api.commit(media.value, folder, item.name, item.mtime, DriveLogic.ON_CONFLICT_RENAME, takenAt = takenAt)) {
            is DResult.Ok -> {
                placed?.let { p -> runCatching { work.afterBackup(r.value.first, p, item.id, item.video) }.onFailure { if (it is CancellationException) throw it } }
                null
            }
            else -> {
                if (r is DResult.Failed && r.code == "not_found") camera.forget()
                code(r)
            }
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
        /** The folder chosen before Kamera-Uploads (only read: [takeTargetNotice]). */
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
        /** The work folder cannot take the photo (gone, read-only, a file in its way). */
        private val WORK_GONE = setOf("not_found", "forbidden", "no_folder", "no_work_folder")
    }
}
