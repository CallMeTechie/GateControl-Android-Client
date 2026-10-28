package com.gatecontrol.android.drive.backup

import android.provider.MediaStore
import com.gatecontrol.android.drive.DriveLogic

/** What the photo backup does (Drive tab › Foto-Sicherung). */
data class DriveBackupConfig(
    val enabled: Boolean = false,
    /** The Drive folder the photos go to, and how it reads ("Familie › Fotos › Marc"). */
    val folderId: Long? = null,
    val folderLabel: String? = null,
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

    /** The default target: Familie › Fotos › <name> when the person writes to a family area, else Meine Dateien › Fotos. */
    fun defaultPath(name: String?, family: Boolean, photos: String): List<String> {
        val own = DriveLogic.safeName(name)
        return if (family && own != null) listOf(photos, own) else listOf(photos)
    }
}
