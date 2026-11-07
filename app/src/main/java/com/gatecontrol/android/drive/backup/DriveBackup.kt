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
import com.gatecontrol.android.drive.DriveMediaAccess
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
    private val labels: DrivePhotoLabels,
    private val places: com.gatecontrol.android.drive.DrivePlacesPrefs,
) {
    /**
     * The run in progress: [done] of [total] photos, [current] the one going up; [videos]: the older videos are being
     * caught up ("Ältere Videos …", after the new photos).
     */
    data class Status(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val current: String? = null, val videos: Boolean = false)

    /**
     * One that went up lately: its name, where it went ([work]: the work folder, [label] its name as the settings show
     * it; [path]: year and month), a video.
     */
    data class Sent(val name: String, val work: Boolean, val path: String, val video: Boolean, val label: String? = null)

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
    private val _skipped = MutableStateFlow(readSkipped())
    /** What the backup left out and why (newest first) — shown with "Erneut versuchen", never dropped silently. */
    internal val skipped: StateFlow<List<DriveBackupLogic.Skipped>> = _skipped.asStateFlow()
    private val _sent = MutableStateFlow(readSent())
    /** The last ones that went up, and where (newest first). */
    val sent: StateFlow<List<Sent>> = _sent.asStateFlow()

    init {
        migrateVideos()
    }

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

    private fun mineNow() = prefs.getString(KEY_OWNER, null) == owner()

    private fun readSkipped(): List<DriveBackupLogic.Skipped> =
        if (!mineNow()) emptyList() else DriveBackupLogic.skippedOf(prefs.getString(KEY_SKIPPED, null))

    private fun readSent(): List<Sent> = if (!mineNow()) emptyList() else prefs.getString(KEY_SENT, null).orEmpty().split('\n').mapNotNull { line ->
        val p = line.split('\t')
        if (p.size != 5) null else Sent(name = p[3], work = p[0] == "w", path = p[1], video = p[2] == "v", label = p[4].takeIf { it.isNotEmpty() })
    }

    private fun markAt(addedKey: String, idKey: String): DriveBackupMark? =
        if (!mineNow() || !prefs.contains(addedKey)) null else DriveBackupMark(prefs.getLong(addedKey, 0L), prefs.getLong(idKey, 0L))

    /** "Nur neue Videos": videos only after this (null: every video the pass comes to). */
    private fun videosFrom(): DriveBackupMark? = markAt(KEY_VFROM_ADDED, KEY_VFROM_ID)

    /** The catch-up of older videos: how far it got and up to where; null: none. */
    private fun videoCatchUp(): Pair<DriveBackupMark, DriveBackupMark>? {
        val until = markAt(KEY_VEND_ADDED, KEY_VEND_ID) ?: return null
        return (markAt(KEY_VCATCH_ADDED, KEY_VCATCH_ID) ?: DriveBackupMark.ALL) to until
    }

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
            // with "Auch Videos" already on, the videos follow the photos' choice
            if (_config.value.videos) putBoolean(KEY_VASKED, true)
        }
        update { it.copy(enabled = true) }
        DriveBackupWork.runNow(context)
    }

    // ─── "Auch Videos" ──────────────────────────────────────────────

    /**
     * "Auch Videos" is switched on while the backup has a mark: ask which videos ("Alle Videos" / "Nur neue"). Without
     * a mark the first switch-on asks for photos and videos at once.
     */
    fun videosNeedChoice(): Boolean = mark() != null

    /**
     * "Auch Videos" on: [all] — the videos already on the phone too: a catch-up of its own goes through the videos up
     * to the mark (after it the pass takes them), only videos, so no photo is sent again; else only the videos from now
     * on. Without a mark (never switched on): the first switch-on's choice holds for both.
     */
    fun videosOn(all: Boolean) {
        val mark = mark()
        prefs.edit {
            removeVideoState()
            if (mark != null) {
                if (all) {
                    putLong(KEY_VEND_ADDED, mark.dateAdded)
                    putLong(KEY_VEND_ID, mark.id)
                } else {
                    val now = markNow()
                    putLong(KEY_VFROM_ADDED, now.dateAdded)
                    putLong(KEY_VFROM_ID, now.id)
                }
                putBoolean(KEY_VASKED, true)
            }
        }
        update { it.copy(videos = true) }
        if (_config.value.enabled) DriveBackupWork.runNow(context)
    }

    /** "Auch Videos" off: no videos any more; switching on again asks anew. */
    fun videosOff() {
        prefs.edit { removeVideoState() }
        update { it.copy(videos = false) }
    }

    private fun android.content.SharedPreferences.Editor.removeVideoState() {
        listOf(KEY_VFROM_ADDED, KEY_VFROM_ID, KEY_VCATCH_ADDED, KEY_VCATCH_ID, KEY_VEND_ADDED, KEY_VEND_ID, KEY_VASKED).forEach { remove(it) }
    }

    /**
     * "Auch Videos" was on before the app asked which videos (up to 1.92): the videos on the phone before it was
     * switched on never went up, and those the app could not see (Android 14+: no READ_MEDIA_VIDEO — "Ausgewählte
     * Fotos" counted as enough) the mark went past. Once, without asking: a catch-up of every video up to the mark —
     * only those not in Drive yet go up (the same name and content in its folder is not sent again).
     */
    private fun migrateVideos() {
        if (!mineNow() || !prefs.getBoolean(KEY_VIDEOS, false) || prefs.getBoolean(KEY_VASKED, false)) return
        val mark = mark() ?: return
        prefs.edit {
            remove(KEY_VCATCH_ADDED)
            remove(KEY_VCATCH_ID)
            putLong(KEY_VEND_ADDED, mark.dateAdded)
            putLong(KEY_VEND_ID, mark.id)
            putBoolean(KEY_VASKED, true)
        }
        Timber.i("Drive backup: the older videos are caught up once")
    }

    /** Older videos still to catch up; null: none to catch up (or it cannot tell). */
    suspend fun pendingVideos(): Int? = withContext(Dispatchers.IO) {
        val cfg = _config.value
        val (from, until) = videoCatchUp() ?: return@withContext null
        if (!cfg.enabled || !cfg.videos || !canRead(true)) return@withContext null
        runCatching {
            val q = DriveBackupLogic.videoQuery(cfg, from, until, videosFrom())
            context.contentResolver.query(COLLECTION, arrayOf(MediaStore.MediaColumns._ID), q.selection, q.args, null)?.use { it.count }
        }.getOrNull()
    }

    // ─── skipped ────────────────────────────────────────────────────

    /** "Erneut versuchen": every skipped one is looked at again in the next pass (outside the mark). */
    fun retrySkipped() {
        val list = readSkipped()
        if (list.isEmpty()) return
        val now = System.currentTimeMillis() / 1000
        val again = DriveBackupLogic.againOf(prefs.getString(KEY_AGAIN, null)).filter { a -> list.none { it.id == a.id } } +
            list.map { DriveBackupLogic.Again(it.id, now, retry = true) }
        prefs.edit {
            putString(KEY_AGAIN, DriveBackupLogic.againText(again))
            remove(KEY_SKIPPED)
        }
        _skipped.value = emptyList()
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
    fun canRead(videos: Boolean = _config.value.videos): Boolean =
        DriveMediaAccess.of(context, videos) != DriveMediaAccess.Access.NONE

    /**
     * The app sees only some of the photos or videos: on Android 14 "Ausgewählte Fotos", or the videos not allowed
     * (READ_MEDIA_VISUAL_USER_SELECTED is granted with full access too, so it never meant the videos). New photos and
     * videos stay invisible to the backup then.
     */
    fun partialAccess(videos: Boolean = _config.value.videos): Boolean =
        DriveMediaAccess.of(context, videos) == DriveMediaAccess.Access.PARTIAL

    private fun original(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Photos still to back up; null when it cannot tell. */
    suspend fun pending(): Int? = withContext(Dispatchers.IO) {
        val cfg = _config.value
        val mark = mark() ?: return@withContext null
        if (!cfg.enabled || !canRead(cfg.videos)) return@withContext null
        runCatching {
            val q = DriveBackupLogic.query(cfg, mark, videosFrom())
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
        /** The media store's SIZE (what it saw when the file was closed); null when unknown. */
        val size: Long? = null,
        /** A video's length (ms) in the media store; null when unknown. */
        val duration: Long? = null,
    )

    /** What one item came to: sent, already in Drive, or a code ([DriveBackupLogic.next]). */
    private sealed interface One {
        data object Sent : One
        data object There : One
        data class Failed(val code: String) : One
    }

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
        val access = DriveMediaAccess.of(context, cfg.videos)
        if (access == DriveMediaAccess.Access.NONE) return stop("no_permission")
        // what the app may see — in the log, so a support bundle tells why photos or videos were not found
        if (access != DriveMediaAccess.Access.FULL) Timber.i("Drive backup: the app sees only some photos or videos (partial access)")
        // Stopped by Drive's ransomware protection: no photo is tried until it is lifted (that starts a pass).
        if (live.locked.value) return stop("device_locked")
        // work photos apart (Drive 1.13): the settings as the server has them, the WLAN now on the timeline
        if (work.settings.value.active || live.supports(DMe.FEATURE_PHOTO_CLASSES)) {
            work.refreshSettings()
            runCatching { wifi.sample() }
        }
        // Drive 1.16: whether places go with the photos and faces are found ("Aufnahmeort mitsichern", "Personen erkennen")
        if (live.supports(DMe.FEATURE_PHOTO_PLACES) || live.supports(DMe.FEATURE_PHOTO_PEOPLE)) {
            api.photoSettings().valueOrNull()?.let(places::took)
        }
        val startMark = mark() ?: DriveBackupMark.ALL
        val from = videosFrom()
        var done = 0
        val progress = { item: Item, total: Int, videos: Boolean -> _status.value = Status(true, done, done + total, item.name, videos).also(onProgress) }

        // 1. what is new, after the mark
        while (true) {
            val mark = mark() ?: DriveBackupMark.ALL
            val (items, total) = list(DriveBackupLogic.query(cfg, mark, from))
            if (items.isEmpty()) break
            for ((i, item) in items.withIndex()) {
                progress(item, total - i, false)
                handle(item, countThere = true) { saved -> advance(item, saved) }?.let { return it }
                done++
            }
        }
        // the videos were not visible (no READ_MEDIA_VIDEO, or only picked ones): the mark went past videos the pass
        // could not see — they go into the catch-up of older videos, which takes them once the app may see them
        if (cfg.videos && DriveMediaAccess.of(context, true) != DriveMediaAccess.Access.FULL) extendVideoCatchUp(startMark)

        // 2. what came late: a video still recording (`IS_PENDING`) has the date it was started — the mark may be past it
        val nowS = System.currentTimeMillis() / 1000
        kotlin.run {
            val recent = recent()
            val mark = mark() ?: DriveBackupMark.ALL
            val (items, _) = list(DriveBackupLogic.lateQuery(cfg, mark, from, nowS), limit = LATE_MAX) { it !in recent }
            for ((i, item) in items.withIndex()) {
                progress(item, items.size - i, false)
                handle(item) { _ -> }?.let { return it }
                done++
            }
        }

        // 3. looked at again: still being written then, or "Erneut versuchen"
        kotlin.run {
            val again = DriveBackupLogic.againOf(prefs.getString(KEY_AGAIN, null))
            if (again.isEmpty()) return@run
            val (items, _) = list(DriveBackupLogic.idsQuery(again.map { it.id }), limit = again.size)
            val waitedOut = again.filter { a -> items.none { it.id == a.id } && nowS - a.since > DriveBackupLogic.WAIT_MAX_S }
            if (waitedOut.isNotEmpty()) dropAgain(waitedOut.map { it.id }.toSet())
            for ((i, item) in items.withIndex()) {
                val a = again.first { it.id == item.id }
                progress(item, items.size - i, false)
                handle(item, force = nowS - a.since > DriveBackupLogic.WAIT_MAX_S, again = true) { _ -> dropAgain(setOf(item.id)) }?.let { return it }
                done++
            }
        }

        // 4. the older videos ("Alle Videos" when "Auch Videos" was switched on, or not visible before)
        if (cfg.videos && DriveMediaAccess.of(context, true) == DriveMediaAccess.Access.FULL) {
            var seen = 0
            while (true) {
                val (vFrom, until) = videoCatchUp() ?: break
                val recent = recent()
                val (items, total) = list(DriveBackupLogic.videoQuery(cfg, vFrom, until, from))
                if (items.isEmpty()) {
                    prefs.edit { listOf(KEY_VCATCH_ADDED, KEY_VCATCH_ID, KEY_VEND_ADDED, KEY_VEND_ID).forEach { remove(it) } }
                    Timber.i("Drive backup: the older videos are caught up")
                    break
                }
                for ((i, item) in items.withIndex()) {
                    // handled lately already (the late pass): passed over
                    if (item.id in recent) { advanceVideos(item); continue }
                    progress(item, total - i, true)
                    handle(item) { _ -> advanceVideos(item) }?.let { return it }
                    done++
                }
                if (++seen > 1_000) break
            }
        }

        prefs.edit {
            putLong(KEY_LAST, System.currentTimeMillis())
            remove(KEY_ERROR)
        }
        _stats.value = readStats()
        // work photos apart: learn from the work folder, then the marked photos again (in the background)
        work.afterRun()
        // Drive 1.16: the faces of the photos just sent (under its own conditions: WLAN, charging)
        if (places.pending().isNotEmpty()) com.gatecontrol.android.drive.faces.DrivePeopleWork.request(context)
        return Outcome.DONE
    }

    /**
     * One item through [one]: [after] when it is done with (sent, there, skipped — saved: counted), null to go on, else
     * the pass ends with the outcome. A skipped one is kept with its reason ([skipped]); one still being written waits
     * ([KEY_AGAIN]); [countThere]: one already in Drive counts as saved (the pass's own order, as before).
     */
    private suspend fun handle(item: Item, countThere: Boolean = false, force: Boolean = false, again: Boolean = false, after: (saved: Boolean) -> Unit): Outcome? {
        currentCoroutineContext().ensureActive()
        val r = one(item, force)
        val code = (r as? One.Failed)?.code
        if (code == null) {
            remember(item)
            clearSkipped(item.id)
            after(r == One.Sent || (countThere && r == One.There))
            // sent outside the pass's own order (late, again, older videos): counted here ([advance] counts its own)
            if (r == One.Sent && !countThere) countSaved()
            if (r == One.Sent) noteSent()
            return null
        }
        when (DriveBackupLogic.next(code)) {
            DriveBackupLogic.Next.WAIT -> {
                if (!again) waitFor(item)
                // the again list keeps it until it is written (or waited out)
                if (!again) after(false)
            }
            DriveBackupLogic.Next.SKIP -> skip(item, code, after)
            DriveBackupLogic.Next.STOP -> return stop(code)
            DriveBackupLogic.Next.RETRY -> return retry(code)
            DriveBackupLogic.Next.TRY -> {
                val tries = (if (prefs.getLong(KEY_TRIES_ID, -1L) == item.id) prefs.getInt(KEY_TRIES, 0) else 0) + 1
                if (tries >= DriveBackupLogic.MAX_TRIES) {
                    skip(item, code, after)
                } else {
                    prefs.edit { putInt(KEY_TRIES, tries); putLong(KEY_TRIES_ID, item.id) }
                    return retry(code)
                }
            }
        }
        return null
    }

    private fun skip(item: Item, code: String, after: (saved: Boolean) -> Unit) {
        Timber.i("Drive backup: skipped a %s (%s)", if (item.video) "video" else "photo", code)
        val list = DriveBackupLogic.withSkipped(readSkipped(), DriveBackupLogic.Skipped(item.id, item.name, code, System.currentTimeMillis(), item.video))
        prefs.edit { putString(KEY_SKIPPED, DriveBackupLogic.skippedText(list)) }
        _skipped.value = list
        remember(item)
        after(false)
    }

    private fun clearSkipped(id: Long) {
        val list = readSkipped()
        if (list.none { it.id == id }) return
        val next = list.filter { it.id != id }
        prefs.edit { putString(KEY_SKIPPED, DriveBackupLogic.skippedText(next)) }
        _skipped.value = next
    }

    private fun waitFor(item: Item) {
        Timber.i("Drive backup: a %s is still being written, looked at again later", if (item.video) "video" else "photo")
        val list = DriveBackupLogic.againOf(prefs.getString(KEY_AGAIN, null))
        if (list.any { it.id == item.id }) return
        prefs.edit { putString(KEY_AGAIN, DriveBackupLogic.againText(list + DriveBackupLogic.Again(item.id, System.currentTimeMillis() / 1000, retry = false))) }
    }

    private fun dropAgain(ids: Set<Long>) {
        val list = DriveBackupLogic.againOf(prefs.getString(KEY_AGAIN, null)).filter { it.id !in ids }
        prefs.edit { if (list.isEmpty()) remove(KEY_AGAIN) else putString(KEY_AGAIN, DriveBackupLogic.againText(list)) }
    }

    /** The fresh entries handled lately (ids): the late pass and the catch-up leave them out. */
    private fun recent(): Set<Long> = DriveBackupLogic.recentOf(prefs.getString(KEY_RECENT, null)).keys

    private fun remember(item: Item) {
        val nowS = System.currentTimeMillis() / 1000
        if (!DriveBackupLogic.fresh(item.added, nowS)) return
        val map = DriveBackupLogic.recentOf(prefs.getString(KEY_RECENT, null)) + (item.id to item.added)
        prefs.edit { putString(KEY_RECENT, DriveBackupLogic.recentText(map, nowS)) }
    }

    /** Everything the mark passed since [from] while the videos were not visible goes into the catch-up of videos. */
    private fun extendVideoCatchUp(from: DriveBackupMark) {
        val mark = mark() ?: return
        if (mark == from) return
        val have = videoCatchUp()
        prefs.edit {
            if (have == null) {
                putLong(KEY_VCATCH_ADDED, from.dateAdded)
                putLong(KEY_VCATCH_ID, from.id)
            }
            putLong(KEY_VEND_ADDED, mark.dateAdded)
            putLong(KEY_VEND_ID, mark.id)
        }
    }

    private fun advanceVideos(item: Item) {
        prefs.edit {
            putLong(KEY_VCATCH_ADDED, item.added)
            putLong(KEY_VCATCH_ID, item.id)
            remove(KEY_TRIES)
            remove(KEY_TRIES_ID)
        }
    }

    private fun noteSent() {
        val list = (listOf(lastSent ?: return) + _sent.value).take(SENT_MAX)
        lastSent = null
        val flat = { v: String -> v.replace('\t', ' ').replace('\n', ' ') }
        prefs.edit { putString(KEY_SENT, list.joinToString("\n") { listOf(if (it.work) "w" else "p", it.path, if (it.video) "v" else "p", flat(it.name), flat(it.label.orEmpty())).joinToString("\t") }) }
        _sent.value = list
    }

    /** Where the last one sent went (set by [one], kept by [noteSent]). */
    private var lastSent: Sent? = null

    /** Up to [limit] items of [q] (those [keep] says) and how many [q] has in all. */
    private fun list(q: DriveBackupLogic.Query, limit: Int = BATCH, keep: (Long) -> Boolean = { true }): Pair<List<Item>, Int> {
        val cols = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DURATION,
        )
        val withOriginal = original()
        return context.contentResolver.query(COLLECTION, cols, q.selection, q.args, q.sort)?.use { c ->
            val out = ArrayList<Item>()
            while (out.size < limit && c.moveToNext()) {
                val id = c.getLong(0)
                if (!keep(id)) continue
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
                    size = if (c.isNull(7)) null else c.getLong(7),
                    duration = if (!video || c.isNull(8)) null else c.getLong(8).takeIf { it > 0 },
                )
            }
            out to c.count
        } ?: (emptyList<Item>() to 0)
    }

    private fun plain(item: Item): Uri =
        ContentUris.withAppendedId(if (item.video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, item.id)

    /**
     * Backs up one photo: [One.Sent], [One.There] (already in its folder), else a code. [force]: waited long enough —
     * a file whose size never matched goes up as it is.
     */
    private suspend fun one(item: Item, force: Boolean = false): One {
        var uri = item.uri
        var read = runCatching { hash(uri) }
        // the original (with its location data) may be refused: the plain file then, without it
        if (read.isFailure && read.exceptionOrNull() !is CancellationException && uri != plain(item)) {
            uri = plain(item)
            read = runCatching { hash(uri) }
            if (read.isSuccess) Timber.i("Drive backup: the original was refused, sent without location data")
        }
        val (sha, length) = read.getOrElse { e ->
            if (e is CancellationException) throw e
            Timber.i("Drive backup: cannot read (%s)", e.javaClass.simpleName)
            return One.Failed("read")
        }
        // a camera app may still be writing into a visible entry: looked at again later, never skipped as empty
        if (!DriveBackupLogic.ready(item.size, length)) {
            if (!force) return One.Failed(DriveBackupLogic.NOT_READY)
            if (length == 0L) return One.Failed("empty")
        }
        val src = item.copy(uri = uri)
        return sendOne(src, sha, length)
    }

    private suspend fun sendOne(item: Item, sha: String, length: Long): One {
        // when it was taken — EXIF (with its time zone), else the media store's date: its year and month are its folder;
        // where (EXIF GPS of the original, for work photos apart) from the same read
        val shot = dates.shot(item.uri, item.mime, item.name)
        val own = shot.takenAt
        val path = DriveBackupLogic.cameraPath(own, item.taken, item.modified, item.added * 1000, ZoneId.systemDefault())
        // work photos apart: where this one goes (the WLAN it was taken in, its place, else the AI on the phone); null: Kamera-Uploads
        var placed = try {
            // the plain URI: the thumbnail does not need the original's location data
            work.placeNew(plain(item), DriveBackupLogic.takenMs(own, item.taken, item.modified), item.video, shot.at)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Drive backup: not placed (%s)", e.javaClass.simpleName)
            null
        }
        var toWork = false
        suspend fun target(): DResult<Long> {
            val p = placed
            toWork = false
            if (p != null && p.placement.dest == com.gatecontrol.android.drive.work.DriveWorkLogic.Dest.WORK) {
                when (val w = work.workFolderFor(path)) {
                    is DResult.Ok -> return w.also { toWork = true }
                    // the work folder is gone or read-only: the photo goes the private way, to be reviewed
                    is DResult.Failed -> if (w.code in WORK_GONE) placed = p.copy(placement = DriveBackupLogic.reviewInstead(p.placement)) else return w
                    else -> return w
                }
            }
            return camera.folder(path)
        }
        var folder = when (val f = target()) {
            is DResult.Ok -> f.value
            else -> return One.Failed(code(f))
        }
        var check = api.uploadCheck(folder, item.name, sha, length)
        if (check is DResult.Failed && check.code == "not_found") {
            // the month's folder (or Kamera-Uploads) went away since: made again
            camera.forget()
            folder = when (val f = target()) {
                is DResult.Ok -> f.value
                else -> return One.Failed(code(f))
            }
            check = api.uploadCheck(folder, item.name, sha, length)
        }
        when (check) {
            is DResult.Ok -> if (check.value.same) return One.There
            else -> return One.Failed(code(check))
        }
        val media = api.upload(DriveSource.of(context, item.uri, length), item.mime)
        if (media !is DResult.Ok) return One.Failed(code(media))
        // HEIC / video: the server cannot make a preview — send the phone's own before the commit (best effort)
        previews.uploadOwn(media.value, item.uri, item.mime)
        // Drive 1.7: the commit takes when it was taken
        val takenAt = if (live.supports(DMe.FEATURE_TAKEN_AT)) own ?: DriveLogic.msTakenAt(item.taken) else null
        // Drive 1.16 (`photoPlaces`): where it was taken, unless the person keeps places off the server
        val geo = shot.at?.takeIf { !item.video && places.settings.value.keepGeo && live.supports(DMe.FEATURE_PHOTO_PLACES) }
        // Drive 1.25 (`mediaFilter`): how long a video runs, for the play badge on its tile
        val duration = item.duration?.takeIf { item.video && live.supports(DMe.FEATURE_MEDIA_FILTER) }
        return when (val r = api.commit(media.value, folder, item.name, item.mtime, DriveLogic.ON_CONFLICT_RENAME, takenAt = takenAt, geo = geo, durationMs = duration)) {
            is DResult.Ok -> {
                // Drive 1.16 (`photoPeople`): its faces are found later from the original here
                // (a child's folder only with "Kinderordner einbeziehen")
                if (!item.video && places.settings.value.people && live.supports(DMe.FEATURE_PHOTO_PEOPLE)) {
                    val child = live.current()?.areas?.firstOrNull { it.id == r.value.first.areaId }?.kind == "child"
                    if (!child || places.settings.value.peopleKids) places.addPending(r.value.first.id, item.id)
                }
                placed?.let { p -> runCatching { work.afterBackup(r.value.first, p, item.id, item.video) }.onFailure { if (it is CancellationException) throw it } }
                // Drive 1.14 (`photoSearch`): what is in the photo, found on the phone (the thumbnail, no location data)
                runCatching { labels.afterBackup(r.value.first, plain(item), item.video) }.onFailure { if (it is CancellationException) throw it }
                lastSent = Sent(r.value.first.name, toWork, path.joinToString("/"), item.video, if (toWork) work.settings.value.folderLabel else null)
                One.Sent
            }
            else -> {
                if (r is DResult.Failed && r.code == "not_found") camera.forget()
                One.Failed(code(r))
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
            remove(KEY_TRIES_ID)
            if (saved) putInt(KEY_SAVED, prefs.getInt(KEY_SAVED, 0) + 1)
        }
        if (saved) _stats.value = _stats.value.copy(saved = _stats.value.saved + 1)
    }

    private fun countSaved() {
        prefs.edit { putInt(KEY_SAVED, prefs.getInt(KEY_SAVED, 0) + 1) }
        _stats.value = _stats.value.copy(saved = _stats.value.saved + 1)
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
        private const val KEY_TRIES_ID = "tries_id"
        /** What was left out and why ([DriveBackupLogic.skippedText]). */
        private const val KEY_SKIPPED = "skipped"
        /** Looked at again outside the mark ([DriveBackupLogic.againText]). */
        private const val KEY_AGAIN = "again"
        /** Fresh entries handled lately ([DriveBackupLogic.recentText]). */
        private const val KEY_RECENT = "recent"
        /** The last ones sent ([Sent]). */
        private const val KEY_SENT = "sent"
        /** "Nur neue Videos": videos only after this mark. */
        private const val KEY_VFROM_ADDED = "videos_from_added"
        private const val KEY_VFROM_ID = "videos_from_id"
        /** The catch-up of older videos: how far it got, up to where. */
        private const val KEY_VCATCH_ADDED = "videos_catch_added"
        private const val KEY_VCATCH_ID = "videos_catch_id"
        private const val KEY_VEND_ADDED = "videos_end_added"
        private const val KEY_VEND_ID = "videos_end_id"
        /** Which videos was asked (or followed the photos' choice). */
        private const val KEY_VASKED = "videos_asked"
        private const val SENT_MAX = 5
        /** At most this many late ones a pass. */
        private const val LATE_MAX = 500
        /** The work folder cannot take the photo (gone, read-only, a file in its way). */
        private val WORK_GONE = setOf("not_found", "forbidden", "no_folder", "no_work_folder")
    }
}
