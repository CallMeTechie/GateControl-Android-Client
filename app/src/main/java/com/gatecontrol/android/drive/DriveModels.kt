package com.gatecontrol.android.drive

import com.google.gson.JsonObject

/** Plugin id of Drive (files of the family and the team on the own storage). */
const val DRIVE_PLUGIN = "gatecontrol-drive"

/** Outcome of a Drive API call. Never an exception (except cancellation). */
sealed class DResult<out T> {
    data class Ok<T>(val value: T) : DResult<T>()
    /** The server refused: `code` as the plugin README lists it (`not_licensed`, `no_seat`, `exists`, …); `body` carries extras. */
    data class Failed(val code: String, val http: Int, val body: JsonObject? = null) : DResult<Nothing>()
    /** No answer (no network, timeout). */
    data object Offline : DResult<Nothing>()
    data object NotConfigured : DResult<Nothing>()

    fun valueOrNull(): T? = (this as? Ok)?.value
}

/** A result that is not Ok, retyped (an Ok here is a programming error). */
internal fun <T> DResult<*>.notOk(): DResult<T> = when (this) {
    is DResult.Ok -> DResult.Failed(DriveApi.CODE_INVALID, 0)
    is DResult.Failed -> this
    DResult.Offline -> DResult.Offline
    DResult.NotConfigured -> DResult.NotConfigured
}

/** `GET me`: who I am for Drive. */
data class DMe(
    val userId: Int?,
    val name: String?,
    /** `family` | `business`; null: not switched on (Drive answers `no_seat` everywhere else). */
    val seat: String?,
    val licenceValid: Boolean,
    val tier: String?,
    val areas: List<DArea>,
    /** Sequence of the newest change (`GET changes?since=`). */
    val latest: Long,
    val trashDays: Int?,
    val maxBytes: Long?,
    val people: Map<Int, String>,
    /** Files deleted "everywhere" on another device that this one still has to remove (Drive 1.2+). */
    val wipes: Int = 0,
    /** How scans are saved by default: `pdf` | `image` (setting "Scans speichern als", Drive 1.3+). */
    val scanFormat: String = "pdf",
    /** What this Drive can do beyond the basics (`devices`, `deleteAfter`, `emptyFiles`, `links`, `guard` …, Drive 1.5+). */
    val features: Set<String> = emptySet(),
    /** Direct links into the portal (feature `links`); null when the server has none. */
    val links: DPortalLinks? = null,
    /** Protection against ransomware (feature `guard`): is this device stopped, and why. */
    val guard: DGuard? = null,
    /** Open incidents of the person's devices (feature `guard`). */
    val incidents: List<DIncident> = emptyList(),
    /** An administrator of GateControl (the storage overview may count everything, `GET storage?all=1`). */
    val admin: Boolean = false,
    /** This device as Drive knows it (the app's device token; null without the permission `devices`). */
    val deviceId: String? = null,
    /** Drive 1.14: "Original-Scan behalten" by default (setting *scan_keep_original*); null when the server says nothing. */
    val scanKeepOriginal: Boolean? = null,
) {
    fun has(feature: String): Boolean = feature in features

    companion object {
        const val FEATURE_EMPTY_FILES = "emptyFiles"
        const val FEATURE_LINKS = "links"
        const val FEATURE_GUARD = "guard"
        const val FEATURE_DELETE_AFTER = "deleteAfter"
        /** Drive 1.7: a folder back to an earlier state. */
        const val FEATURE_TIME_TRAVEL = "timeTravel"
        /** Drive 1.7: the commit takes when a photo was taken. */
        const val FEATURE_TAKEN_AT = "takenAt"
        const val FEATURE_PHOTOS = "photos"
        const val FEATURE_ALBUMS = "albums"
        /** Drive 1.7: albums can feed the photo frame of the wall tablet. */
        const val FEATURE_FRAME = "frame"
        const val FEATURE_STORAGE = "storage"
        /** Drive 1.8: AI for documents (what a file is, a suggested name and folder, its deadlines). */
        const val FEATURE_DOC_AI = "docAi"
        /** Drive 1.8: the import from Google Drive and OneDrive (only in the portal). */
        const val FEATURE_CLOUD_IMPORT = "cloudImport"
        /** Drive 1.9: vault folders, end to end encrypted. */
        const val FEATURE_VAULT = "vault"
        /** Drive 1.9: office documents in the browser (only while an office server is set up). */
        const val FEATURE_OFFICE = "office"
        /** Drive 1.10: the camera uploads' folder (`photos/camera`) and the Photos tab's filter `camera=1`. */
        const val FEATURE_PHOTOS_FOLDER = "photosFolder"
        /**
         * Drive 1.11: vault folders stay out of the lists (`children`), `children?dirs=1` gives the menu's tree with
         * `hasDirs`, a new vault always lies in its area's top folder.
         */
        const val FEATURE_VAULTS_APART = "vaultsApart"
        /** Drive 1.12: jobs over several items at once (`ops`: move, copy, trash, restore, purge; undo). */
        const val FEATURE_BULK = "bulk"
        /** Drive 1.12: a ZIP of a selection (`POST zip`). */
        const val FEATURE_ZIP = "zip"
        /** Drive 1.12: one shared link for several files. */
        const val FEATURE_MULTI_LINK = "multiLink"
        /** Drive 1.12 on GateControl 1.215+: the server copies the bytes itself (only then the app offers "Kopieren"). */
        const val FEATURE_COPY_SERVER = "copyServer"
        /** Drive 1.12: a ZIP and one link of any selection (folders and files mixed, more than 20). */
        const val FEATURE_ZIP_MIXED = "zipMixed"
        /**
         * Drive 1.13: work photos apart (`photos/settings` with the work folder, the review mark `photos/review`, the
         * filter `class=work|private|review` and the counts `photos/classes`).
         */
        const val FEATURE_PHOTO_CLASSES = "photoClasses"
        /** Drive 1.14: questions to the documents (`POST ask`). */
        const val FEATURE_DOC_ASK = "docAsk"
        /** Drive 1.14: a scan becomes a document (`scans/classify`, `scans/convert`: Word, Excel). */
        const val FEATURE_SCAN_DOCS = "scanDocs"
        /** Drive 1.14: photos found by what is in them (`PUT nodes/<id>/labels`, `GET photos?q=`). */
        const val FEATURE_PHOTO_SEARCH = "photoSearch"
        /** Drive 1.14: a deadline into the family calendar (`POST deadlines/<id>/calendar`). */
        const val FEATURE_DEADLINE_CALENDAR = "deadlineCalendar"
        /** Drive 1.16: where photos were taken (`geo` on the commit, `photos/places`, `photos/map`, `place=`, `bbox=`). */
        const val FEATURE_PHOTO_PLACES = "photoPlaces"
        /** Drive 1.16: people in the photos (faces found on the phones, `people`, `person=`). */
        const val FEATURE_PHOTO_PEOPLE = "photoPeople"
        /** Drive 1.17: deadlines into a calendar of the mail plugin too (`deadlines/calendars`, `deadlines/targets`). */
        const val FEATURE_DEADLINE_MAIL_CALENDAR = "deadlineMailCalendar"
    }
}

/** `me.links`: the portal page of a file or folder (template with `{id}`) and of Drive itself. */
data class DPortalLinks(val node: String?, val drive: String?)

/**
 * A ransomware incident (Drive 1.6, feature `guard`): Drive saw unusual changes from a device ([deviceName]) and
 * stopped its writes; [files] were touched, [sample] names some of them.
 */
data class DIncident(
    val id: String,
    val at: String?,
    val device: String?,
    val deviceName: String?,
    val files: Int,
    val sample: List<String>,
    /** `open` (or missing) while it waits for the person; anything else is settled. */
    val state: String? = null,
) {
    val open: Boolean get() = state == null || state == "open"
}

/** `me.guard`: whether this device's writes are stopped, and the incident behind it. */
data class DGuard(val locked: Boolean, val incident: DIncident?)

/** `POST guard/incidents/<id>/undo|release` → the incident now, files restored and files moved to the trash. */
data class DGuardResult(val incident: DIncident?, val restored: Int, val trashed: Int)

/** Push event `changed` of the Drive plugin: something changed in [areas] (empty: unknown, all). */
data class DChanged(val areas: Set<String>, val seq: Long?)

/** A storage location of an area (`GET areas`). */
data class DStorage(val bytes: Long?, val quota: Long?, val form: String?, val target: String?, val state: String?)

/** An area: Meine Dateien, a family, a child's folder or a team folder. */
data class DArea(
    val id: String,
    /** `mine` | `family` | `child` | `team`. */
    val kind: String,
    val label: String,
    /** `read` | `write` | `manage`. */
    val role: String,
    /** `owner` | `member` | `parent` (a parent looking into a child's folder: the child is told). */
    val via: String?,
    val rootId: Long,
    val archived: Boolean,
    val storage: DStorage? = null,
    /** AI for documents in this area (Drive 1.8, `PATCH areas/<id> { docAi }`); null while the feature is off. */
    val docAi: Boolean? = null,
    /** New deadlines into the family calendar on their own (Drive 1.14, with `deadlineCalendar`); null while not offered. */
    val deadlinesToCalendar: Boolean? = null,
) {
    val writable: Boolean get() = !archived && (role == "write" || role == "manage")
}

/** A folder or a file of the tree. */
data class DNode(
    val id: Long,
    val areaId: String,
    val parentId: Long?,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val mime: String?,
    val mediaId: String?,
    val rev: Int,
    /** Last change (ISO 8601) — the file's own time when it has one, else the server's. */
    val mtime: String?,
    val updatedAt: String?,
    val updatedBy: Int?,
    /** Search results and the trash: the folder path (`Fotos/2026`). */
    val path: String? = null,
    /** Trash: the folder it was deleted from; folders: how many files went with it. */
    val from: String? = null,
    val files: Int? = null,
    val deletedAt: String? = null,
    /** The server has (or makes) a preview picture of the file (Drive 1.1 on core 1.208+). */
    val preview: Boolean = false,
    /** SHA-256 of the content (hex), when the server knows it: finds the same file on the phone. */
    val sha256: String? = null,
    /** Search hit in the recognised text of a scan (Drive 1.3+): the words around it. */
    val snippet: String? = null,
    /** When a photo or video was taken (ISO 8601, Drive 1.7 `takenAt`); null when unknown. */
    val takenAt: String? = null,
    /** Marked "deleted on a device": goes into the trash then (ISO 8601, Drive 1.5 `deleteAfter`). */
    val deleteAt: String? = null,
    /** AI for documents (Drive 1.8, feature `docAi`): what the file was recognised as; null when the server says nothing. */
    val insight: DInsightShort? = null,
    /**
     * Drive 1.9 (feature `vault`): the vault this node is (or lies in). Then [name] is opaque (32 hex characters) and
     * [nameCipher] the encrypted name; a file's content is ciphertext of key version [keyVersion].
     */
    val vaultId: String? = null,
    val nameCipher: String? = null,
    val keyVersion: Int? = null,
    /** Drive 1.13 (`photoClasses`): marked "zu prüfen" (work or private?), with the AI's hint; null when not marked. */
    val review: DReview? = null,
    /** Drive 1.13: a photo item below my work folder (the small blue bag). */
    val work: Boolean = false,
    /** Drive 1.14 (`photoSearch`): a phone looked at the photo and sent what is on it; null when the server says nothing. */
    val labeled: Boolean? = null,
    /** Drive 1.16 (`photoPlaces`): where it was taken; null without a place. */
    val geo: DGeo? = null,
    /** Drive 1.16: Drive has a place for it, or knows there is none (null: the server says nothing). */
    val located: Boolean? = null,
    /** Drive 1.16 (`photoPeople`, `with=people`): the named people on it (ids); null when not asked. */
    val people: List<Long>? = null,
) {
    /** In a vault: nothing of it may go to a preview, a link, the office, the AI or a backup. */
    val encrypted: Boolean get() = vaultId != null
}

/** The review mark of a photo (Drive 1.13): [hint] the AI's probability "work" in percent, null when it had none. */
data class DReview(val hint: Int?)

/**
 * `GET|PUT photos/settings` (Drive 1.13): where work photos go ([workFolder], its path [folderPath] in [folderAreaId]),
 * whether below it by year and month, whether they may show in the memories and the photo frame.
 */
data class DPhotoSettings(
    val workFolder: Long? = null,
    val workSubfolders: Boolean = true,
    val workInMemories: Boolean = false,
    val folderPath: String? = null,
    val folderAreaId: String? = null,
    /** Drive 1.16 (`photoPlaces`): the apps send where photos were taken; null when the server does not say. */
    val keepGeo: Boolean? = null,
    /** Drive 1.16: links and GateDrop give photos without GPS data; null: not offered (the switch is hidden). */
    val stripGeoOnShare: Boolean? = null,
    /** Drive 1.16 (`photoPeople`): faces are recognised; null when the server does not say. */
    val people: Boolean? = null,
    /** Drive 1.16: the children's folders too. */
    val peopleKids: Boolean? = null,
)

/** `GET photos/classes`: how many photos are work, private and to be reviewed. */
data class DPhotoClasses(val work: Int, val private: Int, val review: Int) {
    val all: Int get() = work + private + review
}

/** `DELETE nodes/<id>?after=`: the file goes into the trash at [deleteAt] (ISO 8601; null when the server did not say). */
data class DMark(val deleteAt: String?)

/** A file deleted "everywhere" on another device: this device removes its own copy, then reports it done. */
data class DWipe(val id: Long, val nodeId: Long?, val name: String, val sha256: String?, val size: Long, val mime: String?)

data class DCrumb(val id: Long, val name: String)

/** A folder with its place: `GET nodes/<id>` + `GET nodes/<id>/children`. */
data class DFolder(
    val node: DNode,
    val crumbs: List<DCrumb>,
    val role: String,
    val items: List<DNode>,
    val total: Int,
    val next: Int?,
) {
    val writable: Boolean get() = role == "write" || role == "manage"
}

data class DVersion(val id: Long, val rev: Int, val size: Long, val at: String?, val by: Int?)

data class DVersions(val current: DNode, val versions: List<DVersion>)

/** `POST uploads/check`: is the name taken, and is it the same file already? */
data class DUploadCheck(val exists: Boolean, val same: Boolean, val node: DNode?)

/** One change of the log (`GET changes?since=`). */
data class DChange(val seq: Long, val op: String, val areaId: String?, val nodeId: Long?, val at: String?, val node: DNode?)

data class DChanges(val changes: List<DChange>, val next: Long, val more: Boolean)

/** A shared link (GateControl's public links v2). */
data class DLink(
    val token: String,
    val url: String?,
    val nodeId: Long?,
    /** `files` | `folder` | `upload`. */
    val kind: String?,
    /** `view` | `download` | `upload`. */
    val mode: String?,
    val name: String?,
    val active: Boolean,
    val expiresAt: String?,
    val pin: Boolean,
    val opens: Int,
    val downloads: Int,
)

data class DLinks(val links: List<DLink>, val enabled: Boolean)

/** What an upload of a picked file came to. */
sealed class DUploadOutcome {
    data class Done(val node: DNode, val created: Boolean) : DUploadOutcome()
    /** Skipped: the same file is there already. */
    data class Same(val node: DNode) : DUploadOutcome()
    data class Failed(val code: String) : DUploadOutcome()
}

// ─── Drive 1.7: photos, albums, time travel, storage ────────────────

/** `GET photos` / `GET albums/<id>`: photos and videos, newest first; [next] is the cursor for `before` (null: the end). */
data class DPhotos(
    val items: List<DNode>,
    val next: String?,
    /** Drive 1.14 `photos?q=`: how many photos match in all (null: not said). */
    val total: Int? = null,
    /** Drive 1.14 `photos?q=`: the most frequent terms of the hits (the chips). */
    val terms: List<String> = emptyList(),
    /** Drive 1.16: the people and places the words of `q` were taken as. */
    val marks: DSearchMarks = DSearchMarks(),
    /** Drive 1.16 `photos?place=&districts=1`: the place's districts. */
    val districts: List<DDistrict> = emptyList(),
)

/** `GET photos/months`: how many photos a month (`2026-10`) has. */
data class DMonth(val month: String, val count: Int)

/** `GET photos/memories`: the photos of this day [yearsAgo] years ago. */
/** `photos/camera` (Drive 1.10, feature `photosFolder`): the camera uploads' folder ("Meine Dateien › Bilder › Kamera-Uploads"). */
data class DCameraFolder(val id: Long, val path: String?, val areaId: String)

data class DMemories(val yearsAgo: Int, val date: String?, val items: List<DNode>)

/** An album: points to files, copies nothing. [frame]: it feeds the photo frame of the wall tablet. */
data class DAlbum(
    val id: String,
    val name: String,
    val areaId: String?,
    val count: Int,
    val coverId: Long?,
    val frame: Boolean,
    val updatedAt: String?,
    val canEdit: Boolean,
    /** May switch [frame] (the area's rights, for family areas also an administrator or a parent). */
    val canFrame: Boolean = false,
)

/** `GET albums/<id>`: the album and a page of its photos. */
data class DAlbumPage(val album: DAlbum, val items: List<DNode>, val next: String?)

/** One example of a time travel: what happens to [name] (`restore` | `revert` | `trash` | `move`). */
data class DTimeTravelSample(val op: String, val name: String, val path: String?)

/** `GET nodes/<id>/timetravel?at=`: what a time travel would do (changes nothing). */
data class DTimeTravelPreview(
    val at: String?,
    /** From then on Drive knows renames and moves; earlier ones it cannot turn back ([limited]). */
    val historySince: String?,
    val restore: Int,
    val revert: Int,
    val trash: Int,
    val move: Int,
    val sample: List<DTimeTravelSample>,
    val limited: Boolean,
    /** Files whose version of then is gone already (they stay as they are). */
    val lost: Int = 0,
) {
    val total: Int get() = restore + revert + trash + move
}

/** `POST nodes/<id>/timetravel`: what it did; [before] is the moment just before ("Rückgängig" travels there). */
data class DTimeTravelResult(
    val at: String?,
    val before: String?,
    val restored: Int,
    val reverted: Int,
    val trashed: Int,
    val moved: Int,
    val failed: Int,
    val lost: Int = 0,
)

/** `GET storage`: how much is used, where, by what. */
data class DStorageReport(
    val total: DStorageUse,
    val maxBytes: Long?,
    val areas: List<DStorageArea>,
    val people: List<DStoragePerson>,
    val largest: List<DStorageFile>,
    val duplicates: List<DDuplicates>,
)

data class DStorageUse(val bytes: Long, val files: Int, val versionsBytes: Long, val trashBytes: Long)

data class DStorageArea(val id: String, val label: String, val kind: String, val use: DStorageUse, val quota: Long?)

data class DStoragePerson(val user: Int?, val name: String, val bytes: Long, val files: Int)

/** A file of the storage overview (one of the largest, or a copy of a duplicate). */
data class DStorageFile(
    val id: Long,
    val name: String,
    val size: Long,
    val areaId: String?,
    val parentId: Long?,
    val path: String?,
    val updatedAt: String?,
    val canDelete: Boolean,
)

/** Files with the same content: [wasted] bytes would be free with one copy. */
data class DDuplicates(val sha256: String, val size: Long, val count: Int, val wasted: Long, val items: List<DStorageFile>)

// ─── Drive 1.8: AI for documents (feature `docAi`) ──────────────────

/** `node.insight`: `none` | `pending` | `ready` | `failed` | `off`; [kind] only when ready. */
data class DInsightShort(val status: String, val kind: String?)

/** `GET nodes/<id>/insight`: what the AI read in the file, its suggestion and the file's deadlines. */
data class DInsight(
    val status: String,
    /** `invoice` | `contract` | `notice` | `medical` | `certificate` | `statement` | `insurance` | `warranty` | `other`. */
    val kind: String?,
    val title: String?,
    /** The document's date (`YYYY-MM-DD`). */
    val date: String?,
    val sender: String?,
    val amount: Double?,
    val currency: String?,
    val error: String?,
    val at: String?,
    val suggestion: DSuggestion?,
    val deadlines: List<DDeadline>,
)

/** A suggested place: [folderId] (with its path, `''` the top) and / or a [name]; a part is null when it fits already. */
data class DSuggestion(val folderId: Long?, val folderPath: String?, val name: String?)

/** A payment, cancellation or objection deadline of a file; [reminder] is the asking person's own. */
data class DDeadline(
    val id: Long,
    /** `payment` | `cancellation` | `objection` | `warranty` (end of the warranty) | `renewal` (Drive 1.14: the last day to cancel before a contract renews). */
    val kind: String,
    /** `YYYY-MM-DD`. */
    val date: String,
    val label: String?,
    val reminder: DReminder?,
    /** `GET deadlines`: the file it belongs to. */
    val node: DNode? = null,
    /** Drive 1.14 (`deadlineCalendar`): my entry in the family calendar; null when there is none. */
    val calendar: DCalendarEntry? = null,
    /** Drive 1.14 `ask`: the file of the deadline (the answer has no node). */
    val nodeId: Long? = null,
)

/** A deadline's entry in the family calendar (Drive 1.14) or, Drive 1.17, in a mail calendar ([target]; null: the family's). */
data class DCalendarEntry(val eventId: String?, val at: String?, val target: DDeadlineTarget? = null)

data class DReminder(val at: String?, val daysBefore: Int, val sent: Boolean)

/** `POST nodes/<id>/insight/apply` → the renamed / moved file and its insight now. */
data class DInsightApplied(val node: DNode, val insight: DInsight?)

// ─── Drive 1.9: office in the browser (feature `office`) ────────────

/** `POST nodes/<id>/office`: the address to open, `edit` or `view`. */
data class DOffice(val url: String, val mode: String, val expiresAt: String?)

// ─── Drive 1.9: vault folders (feature `vault`) ─────────────────────

/** A vault as the person sees it (`GET vaults`, `GET vaults/<id>`). */
data class DVault(
    val id: String,
    val nodeId: Long,
    val areaId: String?,
    val parentId: Long?,
    val nameCipher: String?,
    /** The current key version: new names and contents use it. */
    val keyVersion: Int,
    val recoveryPub: String?,
    /** A person lost the area: the portal (or an app) makes a new key. */
    val needsRotation: Boolean,
    val myDevices: List<DVaultMyDevice>,
    /** Requests of new devices that one of my approved devices could answer. */
    val pending: Int,
)

data class DVaultMyDevice(val deviceId: String, val state: String, val name: String?)

/** A device of a vault: `pending` | `approved` | `revoked`. */
data class DVaultDevice(
    val deviceId: String,
    val userId: Int?,
    val name: String?,
    val platform: String?,
    val publicKey: String,
    val state: String,
    val createdAt: String?,
)

data class DVaultWrap(val keyVersion: Int, val wrap: String)

/** `GET vaults/<id>/keys?deviceId=`: my device's state and its wrapped keys (only when approved). */
data class DVaultKeys(val deviceId: String, val state: String, val keyVersion: Int?, val keys: List<DVaultWrap>)

/** `GET vaults/<id>/recovery`: the recovery key's public key and wraps (useless without the written-down key). */
data class DVaultRecovery(val recoveryPub: String?, val keyVersion: Int?, val keys: List<DVaultWrap>)

/** `POST vaults` → the new vault and its folder. */
data class DVaultCreated(val vault: DVault, val node: DNode?)

/** Live event `vault { vault, event: request | approved | rotated, device?, keyVersion? }`. */
data class DVaultEvent(val vault: String, val event: String, val device: String?, val keyVersion: Int?)

// ─── Drive 1.12: several items at once (features `bulk`, `zip`, `multiLink`) ─

/**
 * A name taken at the target (`POST ops/check`): the item that comes ([path] inside a merged folder, its size and
 * time) and what is there; [same] the same content is there already (it counts as done).
 */
data class DOpConflict(
    val id: Long,
    val name: String,
    val existingId: Long?,
    val existingSize: Long?,
    val existingMtime: String?,
    val same: Boolean,
    val path: String? = null,
    val isDir: Boolean = false,
    val size: Long? = null,
    val mtime: String? = null,
    val existingIsDir: Boolean = false,
)

/** An item the job cannot take: `forbidden`, `root`, `into_itself`, `same_folder`, `encrypted`, `locked`, `not_found`, … */
data class DOpBlocked(val id: Long, val reason: String, val name: String? = null)

/** `POST ops/check`: what goes, how much (folders with their content), the names taken at the target, what cannot go. */
data class DOpCheck(
    val files: Int,
    val folders: Int,
    val bytes: Long,
    val conflicts: List<DOpConflict>,
    val blocked: List<DOpBlocked>,
    /** The ids that go (null: the server did not say; then all but the blocked ones). */
    val items: List<Long>? = null,
    /** More taken names than listed. */
    val conflictsMore: Int = 0,
    /** Folders of the same name that are merged. */
    val merges: Int = 0,
    /** Bytes the job needs and the space left (null: unknown) — copy, and a move into another area. */
    val quotaNeeded: Long? = null,
    val quotaFree: Long? = null,
    /** More than the server takes in one job. */
    val tooMany: Boolean = false,
)

/** What a job came to: done, skipped (the same content was there), failed with the reason; the new items at the target. */
data class DOpResults(
    val done: Int,
    val skipped: Int,
    val failed: List<DOpBlocked>,
    val failedCount: Int = failed.size,
    val resultIds: List<Long> = emptyList(),
)

/** A job of Drive 1.12 (`POST ops`, `GET ops/<id>`, live event `op`); it runs on the server, the app only follows it. */
data class DOp(
    val id: String,
    /** `move` | `copy` | `trash` | `restore` | `purge` | `undo`. */
    val kind: String,
    /** `running` | `done` | `failed` | `cancelled`. */
    val state: String,
    /** Files in all (folders with their content) and done so far. */
    val total: Int,
    val done: Int,
    val bytesTotal: Long,
    val bytesDone: Long,
    val targetId: Long? = null,
    val targetPath: String? = null,
    val results: DOpResults? = null,
    /** Until when "Rückgängig" works (ISO 8601); null: not at all. */
    val undoUntil: String? = null,
    /** How many items were chosen ("N Elemente"). */
    val items: Int = 0,
    /** The first item's name (null in a vault). */
    val label: String? = null,
    /** The target folder's name (or the area's; null in a vault). */
    val targetName: String? = null,
    /** Who brings a copy's bytes: `server`, or `client` with [transfersLeft] files to go (not used by this app). */
    val transfer: String? = null,
    val transfersLeft: Int = 0,
    /** Why it failed: `abandoned`, `quota`, `device_locked`, `no_seat`, `internal`. */
    val error: String? = null,
    /** Its items one by one (Drive 1.12.1, `?files=1` and the live event; null: the server does not say). */
    val files: List<DOpFile>? = null,
    /** Items not in [files]. */
    val filesMore: Int = 0,
) {
    val running: Boolean get() = state == "running"
}

/**
 * One item of a job (Drive 1.12.1): a chosen file or folder (a copy: each file), its [state] `waiting` | `running` |
 * `done` | `skipped` | `failed` ([reason] for the last two), and the bytes done — null while it runs without a measure.
 * [seq]: its place in the job. A vault's item has no [name] ([encrypted]).
 */
data class DOpFile(
    val seq: Int,
    val nodeId: Long?,
    val name: String?,
    val size: Long,
    val isDir: Boolean,
    val state: String,
    val reason: String? = null,
    val bytesDone: Long? = null,
    val encrypted: Boolean = false,
)

/** `POST zip`: where the ZIP of a selection can be fetched for a short time, its file name. */
data class DZip(val url: String?, val path: String?, val mediaId: String?, val name: String, val expiresAt: String?)
