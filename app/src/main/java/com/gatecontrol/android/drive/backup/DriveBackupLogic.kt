package com.gatecontrol.android.drive.backup

import android.provider.MediaStore
import com.gatecontrol.android.drive.DArea
import com.gatecontrol.android.drive.DriveLogic
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/**
 * What the photo backup does (Drive tab › Foto-Sicherung). Where the photos go is fixed: "Meine Dateien › Bilder ›
 * Kamera-Uploads › <Jahr> › <Monat>" ([DriveBackupLogic.cameraPath]).
 */
data class DriveBackupConfig(
    val enabled: Boolean = false,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val videos: Boolean = false,
    /** Only the camera's photos (DCIM, without screenshots), not every picture an app saved. */
    val cameraOnly: Boolean = true,
)

/** How far the backup got: everything added before (dateAdded, id) is done. Media store order. */
data class DriveBackupMark(val dateAdded: Long, val id: Long) {
    companion object {
        /** All photos there are. */
        val ALL = DriveBackupMark(0, 0)
    }
}

/** Pure part of the photo backup (unit-tested). */
internal object DriveBackupLogic {

    /**
     * What a failed step means for the run: RETRY later (no network, server busy), STOP until the person acts (no
     * licence, folder gone, no permission), SKIP the photo (too large, unreadable), TRY it again a few times (an error
     * the server does not name), then skip it.
     */
    enum class Next { RETRY, STOP, SKIP, TRY }

    /** A query of the media store for the photos (and videos) after [mark], oldest first. */
    data class Query(val selection: String, val args: Array<String>, val sort: String) {
        override fun equals(other: Any?) = other is Query && selection == other.selection && args.contentEquals(other.args) && sort == other.sort
        override fun hashCode() = 31 * selection.hashCode() + args.contentHashCode()
    }

    fun query(cfg: DriveBackupConfig, mark: DriveBackupMark): Query {
        val type = MediaStore.Files.FileColumns.MEDIA_TYPE
        val added = MediaStore.MediaColumns.DATE_ADDED
        val id = MediaStore.MediaColumns._ID
        val path = MediaStore.MediaColumns.RELATIVE_PATH
        val sel = StringBuilder()
        val args = ArrayList<String>()
        if (cfg.videos) {
            sel.append("$type IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})")
        } else {
            sel.append("$type = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}")
        }
        if (cfg.cameraOnly) {
            sel.append(" AND $path LIKE ? AND $path NOT LIKE ?")
            args += "DCIM/%"
            args += "%Screenshots%"
        }
        sel.append(" AND ($added > ? OR ($added = ? AND $id > ?))")
        args += mark.dateAdded.toString()
        args += mark.dateAdded.toString()
        args += mark.id.toString()
        return Query(sel.toString(), args.toTypedArray(), "$added ASC, $id ASC")
    }

    /** The name in Drive: the file's own, made safe; else one from the id. */
    fun name(displayName: String?, id: Long, video: Boolean): String =
        DriveLogic.safeName(displayName) ?: (if (video) "VID_$id.mp4" else "IMG_$id.jpg")

    /** When it was taken (ms), else when it was changed (s → ms); null when unknown. */
    fun mtime(dateTakenMs: Long?, dateModifiedS: Long?): Long? =
        dateTakenMs?.takeIf { it > 0 } ?: dateModifiedS?.takeIf { it > 0 }?.let { it * 1000 }

    fun next(code: String): Next = when {
        code == "offline" || code == "busy" || code == "timeout" || code == "http_429" || code.startsWith("http_5") -> Next.RETRY
        code in STOP_CODES -> Next.STOP
        code in SKIP_CODES -> Next.SKIP
        else -> Next.TRY
    }

    /** Tries of one photo before it is skipped (an error the server does not name). */
    const val MAX_TRIES = 3

    private val STOP_CODES = setOf(
        "not_licensed", "no_seat", "forbidden", "login_required", "not_found", "quota", "not_configured", "no_permission", "no_folder",
        // Drive stopped this device (ransomware protection): the backup waits until the stop is lifted, then goes on.
        "device_locked",
    )
    private val SKIP_CODES = setOf("too_large", "unsupported_type", "empty", "invalid", "read")

    // ─── Kamera-Uploads ─────────────────────────────────────────────

    /** The camera uploads' folder in "Meine Dateien" (the names as the server's `photos/camera` makes them). */
    val CAMERA_BASE = listOf("Bilder", "Kamera-Uploads")

    /** The area the camera uploads go to without `photos/camera`: the person's own "Meine Dateien" (`mine:<ich>`). */
    fun cameraArea(areas: List<DArea>, userId: Int?): DArea? {
        val mine = areas.filter { it.kind == "mine" && it.writable }
        return mine.firstOrNull { userId != null && it.id == "mine:$userId" } ?: mine.firstOrNull { it.via == null || it.via == "owner" }
    }

    /**
     * The folders below Kamera-Uploads a photo goes to: its year and month (`["2026", "10"]`) by when it was taken —
     * the file's own date ([takenAt], EXIF or the video's container) as it was where it was taken, a time in UTC in
     * [zone]; else the media store's date taken ([takenMs]), else when it was changed ([modifiedMs]), else [fallbackMs]
     * (when it came onto the phone), each in [zone] (the phone's). The same photo always lands in the same folder.
     */
    fun cameraPath(takenAt: String?, takenMs: Long?, modifiedMs: Long?, fallbackMs: Long, zone: ZoneId): List<String> {
        val own = takenAt?.let { s ->
            runCatching { OffsetDateTime.parse(s) }.getOrNull()?.let { if (it.offset == ZoneOffset.UTC) it.atZoneSameInstant(zone).toLocalDate() else it.toLocalDate() }
        }?.takeIf { it.year in YEARS }
        val date = own ?: listOf(takenMs, modifiedMs, fallbackMs).firstNotNullOfOrNull { ms ->
            ms?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }?.takeIf { it.year in YEARS }
        } ?: Instant.ofEpochMilli(fallbackMs.coerceAtLeast(0)).atZone(zone).toLocalDate()
        return listOf("%04d".format(Locale.ROOT, date.year), "%02d".format(Locale.ROOT, date.monthValue))
    }

    private val YEARS = 1900..9999

    /**
     * When a photo was taken (ms) for the WLAN timeline: the file's own date ([takenAt], ISO 8601 with its offset),
     * else the media store's date taken, else when it was changed; null when unknown.
     */
    fun takenMs(takenAt: String?, takenMs: Long?, modifiedMs: Long?): Long? =
        takenAt?.let { s -> runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull() }
            ?: takenMs?.takeIf { it > 0 }
            ?: modifiedMs?.takeIf { it > 0 }

    /** The work folder cannot take a photo: it goes the private way, marked to be reviewed (its hint kept). */
    fun reviewInstead(p: com.gatecontrol.android.drive.work.DriveWorkLogic.Placement) =
        p.copy(dest = com.gatecontrol.android.drive.work.DriveWorkLogic.Dest.PRIVATE, review = true, learn = null, why = com.gatecontrol.android.drive.work.DriveWorkLogic.Why.REVIEW)

    /**
     * Where a backup that is switched on starts: where it got to ([have]) — the photos before are in Drive already
     * (wherever they went then), so they are never sent again, also not into a new folder — else all photos or only
     * the new ones ([now]).
     */
    fun startMark(have: DriveBackupMark?, all: Boolean, now: DriveBackupMark): DriveBackupMark = have ?: if (all) DriveBackupMark.ALL else now

    /**
     * A login that backed up into a folder of its own choosing before Kamera-Uploads ([legacyFolder], what the app
     * kept then): it is told once that new photos go to Kamera-Uploads now.
     */
    fun targetNotice(legacyFolder: Long): Boolean = legacyFolder > 0
}
