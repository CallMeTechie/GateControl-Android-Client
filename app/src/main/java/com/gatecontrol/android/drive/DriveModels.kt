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
) {
    fun has(feature: String): Boolean = feature in features

    companion object {
        const val FEATURE_EMPTY_FILES = "emptyFiles"
        const val FEATURE_LINKS = "links"
        const val FEATURE_GUARD = "guard"
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
)

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
