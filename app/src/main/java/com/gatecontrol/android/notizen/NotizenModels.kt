package com.gatecontrol.android.notizen

import com.google.gson.JsonObject

/** The plugin id of „Notizen & Aufgaben“. */
const val NOTIZEN_PLUGIN = "gatecontrol-notizen"

/** An answer of the plugin's API (the shape of Mail's and Drive's results). */
sealed class NResult<out T> {
    data class Ok<T>(val value: T) : NResult<T>()
    /** Refused or not understood: the plugin's `code` (or `http_<n>`), the HTTP status, the whole refusal. */
    data class Failed(val code: String, val http: Int, val body: JsonObject? = null) : NResult<Nothing>()
    data object Offline : NResult<Nothing>()
    data object NotConfigured : NResult<Nothing>()

    fun valueOrNull(): T? = (this as? Ok)?.value
}

internal fun <T> NResult<*>.notOk(): NResult<T> = when (this) {
    is NResult.Ok -> NResult.Failed(NotizenJson.CODE_INVALID, 0)
    is NResult.Failed -> this
    NResult.Offline -> NResult.Offline
    NResult.NotConfigured -> NResult.NotConfigured
}

object NArea {
    const val PRIVATE = "private"
    const val WORK = "work"
    fun of(v: String?): String = if (v == WORK) WORK else PRIVATE
}

data class NImports(val mstodo: Boolean, val mstodoSync: Boolean, val dsnote: Boolean)

data class NSourcePrefs(
    val on: Boolean? = null,
    /** Familie: `all` | `mine` | `none`. */
    val kids: String? = null,
    val shoppingInMyDay: Boolean? = null,
    /** Mail: flagged mails as tasks. */
    val flagged: Boolean? = null,
    /** Alltag: „Erinnern um“ (HH:MM). */
    val evening: String? = null,
)

data class NPrefs(
    val area: String = NArea.PRIVATE,
    val evening: String = "18:00",
    val sources: Map<String, NSourcePrefs> = emptyMap(),
    /** `<plugin>|<group id>` → `private` | `work` | `hide`. */
    val groups: Map<String, String> = emptyMap(),
    /** The quick entry's words: morning, noon, afternoon, evening, night → HH:MM. */
    val times: Map<String, String> = emptyMap(),
)

/** `GET me`. */
data class NMe(
    val userId: Long,
    val userName: String?,
    val lang: String,
    /** The hub's wall-clock date and time (`YYYY-MM-DD`, `HH:MM`) in its time zone. */
    val today: String,
    val time: String?,
    val timezone: String?,
    val features: Set<String>,
    val prefs: NPrefs,
    val sharing: String,
    val vaults: Boolean,
    val imports: NImports,
    val attachMB: Int,
    /** A viewer the server knows by device trust only: may read, not change. */
    val readOnly: Boolean,
) {
    fun has(feature: String) = feature in features
}

data class NDue(val date: String, val time: String? = null)
data class NPerson(val id: Long?, val name: String?)
data class NProgress(val done: Int, val total: Int)
data class NOpen(val plugin: String, val route: String)
data class NSourceRef(val plugin: String, val id: String)

/** One row of a list: an own task or an item of another plugin. */
sealed interface NEntry {
    val uid: String
    val title: String
    val done: Boolean
    val important: Boolean
}

/** A task of the hub (README `Task`). */
data class NTask(
    val id: Long,
    val listId: Long?,
    val parentId: Long?,
    override val title: String,
    val hasNote: Boolean,
    override val important: Boolean,
    override val done: Boolean,
    val doneAt: String?,
    val due: NDue?,
    val overdue: Boolean,
    /** ISO date-time with offset. */
    val remindAt: String?,
    val repeat: NRepeat.Rule?,
    val repeatText: String?,
    val assignee: NPerson?,
    val myDay: Boolean,
    val progress: NProgress?,
    val attachments: Int,
    /** The item of another plugin this task carries (completing it ticks the item off). */
    val source: NSourceRef?,
    val noteId: Long?,
    val position: Long,
    val updatedAt: Long,
    /** Made or changed on this phone and not yet on the server ([NotizenOutbox]); a negative [id] was made offline. */
    val pending: Boolean = false,
    /** A change of this phone the server refused (`409`): the person decides when it is opened. */
    val conflict: Boolean = false,
) : NEntry {
    override val uid: String get() = "t:$id"
}

/** An item of another plugin (README `Item`); the original stays there. */
data class NItem(
    val id: Long,
    val kind: String,
    override val title: String,
    val subtitle: String?,
    val plugin: String,
    val sourceLabel: String,
    val open: NOpen?,
    val due: NDue?,
    /** The day and time it shows on (the evening before for lead `evening`). */
    val show: NDue?,
    val allDay: Boolean,
    val lead: String?,
    val overdue: Boolean,
    val area: String,
    override val important: Boolean,
    override val done: Boolean,
    val doneAt: Long?,
    val canComplete: Boolean,
    val assignee: NPerson?,
    val progress: NProgress?,
    val groupId: String?,
    val groupLabel: String?,
    /** Flat strings, numbers, booleans of the source (as text): `type`, `amount`, `child`, `stars`, `flagged` … */
    val meta: Map<String, String>,
    val updatedAt: Long,
) : NEntry {
    override val uid: String get() = "s:$id"
}

data class NSection(val id: String, val title: String, val count: Int, val items: List<NEntry>, val collapsed: Boolean)

data class NView(val view: String, val title: String, val sections: List<NSection>, val list: NList? = null, val plugin: String? = null)

data class NList(
    val id: Long,
    val name: String,
    val area: String,
    val color: String?,
    val isDefault: Boolean,
    val owner: NPerson?,
    val role: String?,
    val groupId: Long?,
    val position: Long,
    val shared: Boolean,
    val members: List<NPerson>,
    val open: Int,
    /** Kept in step with Microsoft To Do (`ext.kind`). */
    val ext: String?,
    /** Made on this phone and not yet on the server. */
    val pending: Boolean = false,
)

data class NGroup(val id: Long, val name: String, val area: String)

data class NVaultRef(val id: String, val nameCipher: String?, val keyVersion: Int, val needsRotation: Boolean)

data class NBound(val plugin: String, val key: String, val kind: String?, val label: String?)

data class NNotebook(
    val id: Long,
    /** Null in a Tresor (its name is [vault]'s name cipher). */
    val name: String?,
    val area: String,
    val color: String?,
    val isDefault: Boolean,
    val role: String?,
    val owner: NPerson?,
    val members: List<NPerson>,
    val shared: Boolean,
    val vault: NVaultRef?,
    val bound: NBound?,
    val count: Int,
    val position: Long,
    /** Made on this phone and not yet on the server. */
    val pending: Boolean = false,
)

data class NTag(val tag: String, val count: Int)

data class NNavSource(val plugin: String, val label: String, val hint: String?, val open: Int)

data class NNav(
    val area: String,
    val smart: Map<String, Int>,
    val sources: List<NNavSource>,
    val lists: List<NList>,
    val groups: List<NGroup>,
    val notesTotal: Int,
    val notebooks: List<NNotebook>,
    val tags: List<NTag>,
)

data class NNoteSummary(
    val id: Long,
    val notebookId: Long,
    /** Null in a Tresor ([titleCipher]). */
    val title: String?,
    val snippet: String?,
    val titleCipher: String?,
    val keyVersion: Int?,
    val encrypted: Boolean,
    val pinned: Boolean,
    val color: String?,
    val due: String?,
    val expiresAt: Long?,
    val check: NProgress?,
    val images: Int,
    val files: Int,
    val tags: List<String>,
    /** `dsnote` | `familie` … where it came from. */
    val ext: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val rev: Long,
    /** Made or changed on this phone and not yet on the server. */
    val pending: Boolean = false,
    /** A change of this phone the server refused (`409`): the person decides when it is opened. */
    val conflict: Boolean = false,
)

data class NAttachment(
    val id: Long,
    val kind: String,
    val refId: Long,
    val name: String?,
    val nameCipher: String?,
    val mime: String?,
    val bytes: Long,
    val encrypted: Boolean,
    val hasText: Boolean,
    /** A signed `/plugin-media/…` path (1 h). */
    val url: String?,
)

data class NNoteTask(val id: Long, val title: String, val due: NDue?, val done: Boolean)

data class NNote(
    val summary: NNoteSummary,
    val body: String?,
    val cipher: String?,
    val notebookName: String?,
    val notebookVault: Boolean,
    val attachments: List<NAttachment>,
    val tasks: List<NNoteTask>,
)

data class NSubtask(val id: Long, val title: String, val done: Boolean, val pending: Boolean = false)

data class NTaskDetail(
    val task: NTask,
    val note: String?,
    val subtasks: List<NSubtask>,
    val listName: String?,
    val listArea: String?,
    val attachmentList: List<NAttachment>,
    val sourceItem: NItem?,
)

data class NSrcGroup(val id: String, val label: String, val count: Int)

data class NSourceStatus(
    val plugin: String,
    val label: String,
    val hint: String?,
    val enabled: Boolean,
    val available: Boolean,
    val lastSync: Long?,
    val error: String?,
    val groups: List<NSrcGroup>,
)

data class NPersonPick(val id: Long, val name: String, val initials: String?, val colorHex: String?, val me: Boolean)

data class NSearchResult(val tasks: List<NTask>, val notes: List<NNoteSummary>, val items: List<NItem>)

data class NNotesPage(val notes: List<NNoteSummary>, val next: String?)

// ── moving over ─────────────────────────────────────────────

data class NMsAccount(val id: Long, val email: String, val label: String?, val tasks: Boolean)
data class NMsAccounts(val available: Boolean, val reason: String?, val accounts: List<NMsAccount>)

/** `POST import/mstodo/consent`: the device code to show, or the `status` of an earlier one. */
data class NMsConsent(val deviceId: String?, val userCode: String?, val verificationUri: String?, val expiresIn: Int, val interval: Int, val status: String?)

data class NMsList(val id: String, val name: String, val open: Int, val done: Int, val attachments: Int, val area: String, val include: Boolean)
data class NMsPreview(val lists: List<NMsList>, val totalLists: Int, val totalOpen: Int, val totalAttachments: Int)
data class NMsLink(val id: Long, val listName: String, val twoWay: Boolean, val error: String?)

data class NJob(val id: Long, val kind: String, val state: String, val stats: Map<String, Int>, val error: String?)

// ── Tresor ──────────────────────────────────────────────────

/** `GET vaults/<id>` of a Tresor notebook (Drive's vault shape, with the notebook instead of a node). */
data class NVault(
    val id: String,
    val notebookId: Long,
    val area: String,
    val nameCipher: String?,
    val keyVersion: Int,
    val recoveryPub: String?,
    val needsRotation: Boolean,
    val myDevices: List<com.gatecontrol.android.drive.DVaultMyDevice>,
    val pending: Int,
)
