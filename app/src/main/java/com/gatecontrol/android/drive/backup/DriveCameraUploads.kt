package com.gatecontrol.android.drive.backup

import com.gatecontrol.android.drive.DMe
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveLive
import com.gatecontrol.android.drive.notOk
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the photo backup puts a photo: "Meine Dateien › Bilder › Kamera-Uploads › <Jahr> › <Monat>", the folders made
 * when they are missing. Kamera-Uploads itself comes from the server (`POST photos/camera`, Drive 1.10, feature
 * `photosFolder`), else the app finds or makes `Bilder/Kamera-Uploads` in the person's own area. The folders found are
 * kept until [forget] (one is gone).
 */
@Singleton
class DriveCameraUploads @Inject constructor(
    private val api: DriveApi,
    private val live: DriveLive,
) {
    private val lock = Mutex()
    private var base: Long? = null
    private val below = HashMap<String, Long>()
    private val other = HashMap<String, Long>()

    /** The folder for [path] (year, month — [DriveBackupLogic.cameraPath]) below Kamera-Uploads. */
    suspend fun folder(path: List<String>): DResult<Long> = lock.withLock { blocked(resolve(path)) }

    /**
     * The folder for [path] below the folder [base] (the work folder, Drive 1.13: `<Arbeitsordner>/<JJJJ>/<MM>`), made
     * when missing; [base] itself for an empty path.
     */
    suspend fun under(base: Long, path: List<String>): DResult<Long> = lock.withLock {
        val key = "$base:" + path.joinToString("/")
        other[key]?.let { return@withLock DResult.Ok(it) }
        var parent = base
        for (name in path) {
            parent = when (val c = child(parent, name)) {
                is DResult.Ok -> c.value
                else -> return@withLock blocked(c)
            }
        }
        other[key] = parent
        DResult.Ok(parent)
    }

    private suspend fun resolve(path: List<String>): DResult<Long> {
        val key = path.joinToString("/")
        below[key]?.let { return DResult.Ok(it) }
        var parent = base ?: when (val b = resolveBase()) {
            is DResult.Ok -> b.value.also { base = it }
            else -> return b
        }
        for (name in path) {
            parent = when (val c = child(parent, name)) {
                is DResult.Ok -> c.value
                else -> return c
            }
        }
        below[key] = parent
        return DResult.Ok(parent)
    }

    /** A file where a folder of the path belongs (`409 exists`): the backup stops with "not reachable" until it is gone. */
    private fun blocked(r: DResult<Long>): DResult<Long> = if (r is DResult.Failed && r.code == "exists") DResult.Failed("no_folder", r.http) else r

    /** A folder went missing (deleted, moved): look them up again next time. */
    suspend fun forget() = lock.withLock {
        base = null
        below.clear()
        other.clear()
    }

    /** Kamera-Uploads: the server's (`photosFolder`), else `Bilder/Kamera-Uploads` in the person's own area. */
    private suspend fun resolveBase(): DResult<Long> {
        if (live.supports(DMe.FEATURE_PHOTOS_FOLDER)) {
            return when (val r = api.cameraFolder()) {
                is DResult.Ok -> DResult.Ok(r.value.id)
                else -> r.notOk()
            }
        }
        val me = when (val r = api.me()) {
            is DResult.Ok -> r.value
            else -> return r.notOk()
        }
        val areas = when (val r = api.areas()) {
            is DResult.Ok -> r.value
            else -> return r.notOk()
        }
        var parent = DriveBackupLogic.cameraArea(areas, me.userId)?.rootId ?: return DResult.Failed("no_folder", 0)
        for (name in DriveBackupLogic.CAMERA_BASE) {
            parent = when (val c = child(parent, name)) {
                is DResult.Ok -> c.value
                else -> return c
            }
        }
        return DResult.Ok(parent)
    }

    /** The folder [name] in [parent], made when it is missing (`POST nodes`; a name compared without case, as the server does). */
    private suspend fun child(parent: Long, name: String): DResult<Long> {
        suspend fun find(): DResult<Long?> = when (val r = api.folder(parent, limit = 1000)) {
            is DResult.Ok -> DResult.Ok(r.value.items.firstOrNull { it.isDir && !it.encrypted && it.name.equals(name, ignoreCase = true) }?.id)
            else -> r.notOk()
        }
        when (val f = find()) {
            is DResult.Ok -> f.value?.let { return DResult.Ok(it) }
            else -> return f.notOk()
        }
        return when (val r = api.createFolder(parent, name, rename = false)) {
            is DResult.Ok -> DResult.Ok(r.value.id)
            // made at the same moment elsewhere
            is DResult.Failed -> if (r.code == "exists") (find() as? DResult.Ok)?.value?.let { DResult.Ok(it) } ?: r else r
            else -> r.notOk()
        }
    }
}
