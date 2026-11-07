package com.gatecontrol.android.drive.backup

import android.provider.MediaStore
import com.gatecontrol.android.drive.DArea
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.scan.MediaScanJson
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
     * the server does not name), then skip it — a skipped one is kept with its reason ([Skipped]) — or WAIT for a
     * file still being written ([NOT_READY]: looked at again later, the pass goes on).
     */
    enum class Next { RETRY, STOP, SKIP, TRY, WAIT }

    /** A query of the media store, oldest first. */
    data class Query(val selection: String, val args: Array<String>, val sort: String) {
        override fun equals(other: Any?) = other is Query && selection == other.selection && args.contentEquals(other.args) && sort == other.sort
        override fun hashCode() = 31 * selection.hashCode() + args.contentHashCode()
    }

    private val TYPE = MediaStore.Files.FileColumns.MEDIA_TYPE
    private val ADDED = MediaStore.MediaColumns.DATE_ADDED
    private val MODIFIED = MediaStore.MediaColumns.DATE_MODIFIED
    private val ID = MediaStore.MediaColumns._ID
    private val PATH = MediaStore.MediaColumns.RELATIVE_PATH
    private val IMAGE = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
    private val VIDEO = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO

    /** `(date_added, _id)` after [m]: three arguments. */
    private fun after(m: DriveBackupMark, args: MutableList<String>): String {
        args += m.dateAdded.toString()
        args += m.dateAdded.toString()
        args += m.id.toString()
        return "($ADDED > ? OR ($ADDED = ? AND $ID > ?))"
    }

    /** `(date_added, _id)` up to and with [m]: three arguments. */
    private fun upTo(m: DriveBackupMark, args: MutableList<String>): String {
        args += m.dateAdded.toString()
        args += m.dateAdded.toString()
        args += m.id.toString()
        return "($ADDED < ? OR ($ADDED = ? AND $ID <= ?))"
    }

    /**
     * Photos, and with "Auch Videos" videos — those only after [videosFrom] when the person chose "Nur neue Videos"
     * (null: every video the pass comes to).
     */
    private fun types(cfg: DriveBackupConfig, videosFrom: DriveBackupMark?, args: MutableList<String>): String = when {
        !cfg.videos -> "$TYPE = $IMAGE"
        videosFrom == null || videosFrom == DriveBackupMark.ALL -> "$TYPE IN ($IMAGE, $VIDEO)"
        else -> "($TYPE = $IMAGE OR ($TYPE = $VIDEO AND ${after(videosFrom, args)}))"
    }

    private fun camera(cfg: DriveBackupConfig, args: MutableList<String>): String {
        if (!cfg.cameraOnly) return ""
        args += "DCIM/%"
        args += "%Screenshots%"
        return " AND $PATH LIKE ? AND $PATH NOT LIKE ?"
    }

    /** The photos (and videos) after [mark], oldest first — the backup's own pass. */
    fun query(cfg: DriveBackupConfig, mark: DriveBackupMark, videosFrom: DriveBackupMark? = null): Query {
        val args = ArrayList<String>()
        val sel = types(cfg, videosFrom, args) + camera(cfg, args) + " AND " + after(mark, args)
        return Query(sel, args.toTypedArray(), "$ADDED ASC, $ID ASC")
    }

    /**
     * The videos that were on the phone before "Auch Videos" was switched on with "Alle Videos": after [from] (how far
     * this catch-up got) up to [until] (the backup's mark then — the pass itself takes every video after it), oldest
     * first. Only videos: a photo already sent is never listed again.
     */
    fun videoQuery(cfg: DriveBackupConfig, from: DriveBackupMark, until: DriveBackupMark, videosFrom: DriveBackupMark? = null): Query {
        val args = ArrayList<String>()
        val start = if (videosFrom != null && (videosFrom.dateAdded > from.dateAdded || (videosFrom.dateAdded == from.dateAdded && videosFrom.id > from.id))) videosFrom else from
        val sel = "$TYPE = $VIDEO" + camera(cfg, args) + " AND " + after(start, args) + " AND " + upTo(until, args)
        return Query(sel, args.toTypedArray(), "$ADDED ASC, $ID ASC")
    }

    /**
     * What may have come late (a video still recording, `IS_PENDING`): the media store hides such an entry until it is
     * finished, but its `date_added` is when the recording started — a photo after it moves the mark past it, and the
     * pass would never come back to it. So the pass also looks at the fresh entries (added in the last [LATE_WINDOW_S]
     * before [nowS]) up to the mark that were written after they were added ([LATE_GRACE_S]), newest first; the ones it
     * has already handled ([recentOf]) are left out by the caller.
     */
    fun lateQuery(cfg: DriveBackupConfig, mark: DriveBackupMark, videosFrom: DriveBackupMark?, nowS: Long): Query {
        val args = ArrayList<String>()
        val sel = StringBuilder(types(cfg, videosFrom, args)).append(camera(cfg, args))
        sel.append(" AND ").append(upTo(mark, args))
        sel.append(" AND $ADDED >= ?")
        args += (nowS - LATE_WINDOW_S).toString()
        sel.append(" AND $MODIFIED >= $ADDED + $LATE_GRACE_S")
        return Query(sel.toString(), args.toTypedArray(), "$ADDED ASC, $ID ASC")
    }

    /** Entries the pass looks at by their ids ([again]: waiting until written, "Erneut versuchen"). */
    fun idsQuery(ids: Collection<Long>): Query {
        val list = ids.distinct()
        return Query("$ID IN (${list.joinToString(",") { "?" }})", list.map { it.toString() }.toTypedArray(), "$ADDED ASC, $ID ASC")
    }

    /** How long back the pass looks for entries that came late (a long recording, a slow camera app). */
    const val LATE_WINDOW_S = 12 * 3600L
    /** Written this long after it was added: it may have been hidden while it was written. */
    const val LATE_GRACE_S = 2L
    /** How many handled fresh entries are remembered ([recentText]). */
    const val RECENT_MAX = 2000

    /** The fresh entries the pass handled: `id:dateAdded,…` → id → date added (s). */
    fun recentOf(s: String?): Map<Long, Long> = s.orEmpty().split(',').mapNotNull { e ->
        val (a, b) = e.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        (a.toLongOrNull() ?: return@mapNotNull null) to (b.toLongOrNull() ?: return@mapNotNull null)
    }.toMap()

    /** [recent] without what is out of the window, at most [RECENT_MAX] (the newest). */
    fun recentText(recent: Map<Long, Long>, nowS: Long): String = recent.entries
        .filter { it.value >= nowS - LATE_WINDOW_S }
        .sortedByDescending { it.value }
        .take(RECENT_MAX)
        .joinToString(",") { "${it.key}:${it.value}" }

    /** Is an entry added at [addedS] fresh enough to be remembered as handled? */
    fun fresh(addedS: Long, nowS: Long): Boolean = addedS >= nowS - LATE_WINDOW_S

    // ─── files still being written ──────────────────────────────────

    /**
     * The file is complete: it has bytes, and as many as the media store says ([storeSize], what it saw when the file
     * was closed; null: unknown). A camera app that writes into a visible entry (no `IS_PENDING`) has 0 or fewer
     * there while it records — such a file is looked at again later, never skipped.
     */
    fun ready(storeSize: Long?, length: Long): Boolean = length > 0 && (storeSize == null || storeSize == length)

    /** How long an entry is waited for (still being written) before it goes up as it is (or is skipped when empty). */
    const val WAIT_MAX_S = 6 * 3600L

    /** An entry looked at again outside the mark: [since] (s) it waits, [retry] "Erneut versuchen" (else written). */
    data class Again(val id: Long, val since: Long, val retry: Boolean)

    fun againOf(s: String?): List<Again> = s.orEmpty().split(',').mapNotNull { e ->
        val p = e.split(':')
        if (p.size != 3) return@mapNotNull null
        Again(p[0].toLongOrNull() ?: return@mapNotNull null, p[1].toLongOrNull() ?: return@mapNotNull null, p[2] == "r")
    }.distinctBy { it.id }

    fun againText(list: List<Again>): String = list.distinctBy { it.id }.joinToString(",") { "${it.id}:${it.since}:${if (it.retry) "r" else "w"}" }

    // ─── skipped ────────────────────────────────────────────────────

    /** A photo or video the backup left out: [code] why (shown in the backup screen with "Erneut versuchen"). */
    data class Skipped(val id: Long, val name: String, val code: String, val at: Long, val video: Boolean)

    /** How many skipped ones are kept (the newest). */
    const val SKIPPED_MAX = 200

    fun skippedOf(s: String?): List<Skipped> = s.orEmpty().split('\n').mapNotNull { line ->
        val p = line.split('\t')
        if (p.size != 5) return@mapNotNull null
        Skipped(
            id = p[0].toLongOrNull() ?: return@mapNotNull null,
            video = p[1] == "v",
            code = p[2].takeIf { it.isNotBlank() } ?: return@mapNotNull null,
            at = p[3].toLongOrNull() ?: 0L,
            name = p[4],
        )
    }

    fun skippedText(list: List<Skipped>): String = list.take(SKIPPED_MAX).joinToString("\n") {
        listOf(it.id.toString(), if (it.video) "v" else "p", flat(it.code), it.at.toString(), flat(it.name)).joinToString("\t")
    }

    /** [list] with [s] at the top (an earlier entry of the same photo replaced). */
    fun withSkipped(list: List<Skipped>, s: Skipped): List<Skipped> = (listOf(s) + list.filter { it.id != s.id }).take(SKIPPED_MAX)

    private fun flat(v: String) = v.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').take(200)

    /** The name in Drive: the file's own, made safe; else one from the id. */
    fun name(displayName: String?, id: Long, video: Boolean): String =
        DriveLogic.safeName(displayName) ?: (if (video) "VID_$id.mp4" else "IMG_$id.jpg")

    /** When it was taken (ms), else when it was changed (s → ms); null when unknown. */
    fun mtime(dateTakenMs: Long?, dateModifiedS: Long?): Long? =
        dateTakenMs?.takeIf { it > 0 } ?: dateModifiedS?.takeIf { it > 0 }?.let { it * 1000 }

    fun next(code: String): Next = when {
        code == NOT_READY -> Next.WAIT
        code == "offline" || code == "busy" || code == "timeout" || code == "http_429" || code.startsWith("http_5") ||
            code == MediaScanJson.SCAN_UNAVAILABLE -> Next.RETRY
        code in STOP_CODES -> Next.STOP
        code in SKIP_CODES -> Next.SKIP
        else -> Next.TRY
    }

    /** The file is still being written ([ready]). */
    const val NOT_READY = "not_ready"

    /** Tries of one photo before it is skipped (an error the server does not name). */
    const val MAX_TRIES = 3

    private val STOP_CODES = setOf(
        "not_licensed", "no_seat", "forbidden", "login_required", "not_found", "quota", "not_configured", "no_permission", "no_folder",
        // Drive stopped this device (ransomware protection): the backup waits until the stop is lifted, then goes on.
        "device_locked",
    )
    private val SKIP_CODES = setOf(
        "too_large", "unsupported_type", "empty", "invalid", "read",
        // the virus scan rejected the photo (`422 infected`): it never goes up, the next one does
        MediaScanJson.INFECTED,
    )

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
