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
    /** Plugin 1.2.0 (`listDays`): what „Mein Tag“ shows. */
    val myday: NMyDayPrefs = NMyDayPrefs(),
    /** Plugin 1.2.0: the area on opening — `last` (the one open last: [area]), `private` or `work`. */
    val startArea: String = "last",
    /** Plugin 1.2.0: which pushes the server sends to this person (on top of GateControl's own switches). */
    val notify: NNotifyPrefs = NNotifyPrefs(),
    /** Plugin 1.3.0 (`yesterdayOpen`): when the morning hint „Gestern offen geblieben“ comes (HH:MM). */
    val yesterdayAt: String = "07:30",
    /** Plugin 1.3.0 (`taskCalendar`, `prefs.calendar.tasks`): my tasks with a time go into my calendar in Mail. */
    val calendarTasks: Boolean = true,
    /** Plugin 1.5.0 (`places`, `prefs.places`): the place search for me, the hint „in der Nähe“ and its quiet hours. */
    val places: NPlacePrefs = NPlacePrefs(),
)

/** `prefs.places` (plugin 1.5.0). [quiet] null: no quiet hours. */
data class NPlacePrefs(val search: Boolean = true, val nearby: Boolean = true, val quiet: NQuiet? = NQuiet("21:00", "07:00"))

/** `prefs.myday` (plugin 1.2.0): the list-day cards on top, the tasks done today at the end. */
data class NMyDayPrefs(val listdays: Boolean = true, val showDone: Boolean = true)

/** `prefs.notify` (plugin 1.2.0): reminders, shared and assigned, the list day in the morning; 1.3.0: the morning hint. */
data class NNotifyPrefs(val reminders: Boolean = true, val shared: Boolean = true, val listday: Boolean = true, val yesterday: Boolean = true)

/** `me.calendar` (plugin 1.3.0): Mail offers the calendar „Aufgaben“, the administrator's switch, my own switch. */
data class NCalendarInfo(val available: Boolean = false, val enabled: Boolean = false, val mine: Boolean = false, val name: String? = null) {
    /** The chip „Kalender“ and the mark „im Kalender“ show only with all three. */
    val shown: Boolean get() = available && enabled && mine
}

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
    /** Plugin 1.2.0 (`listIcons`): the icons a list may have. */
    val listIcons: List<String> = emptyList(),
    /** Plugin 1.3.0 (`taskCalendar`); null with an older plugin. */
    val calendar: NCalendarInfo? = null,
    /** Plugin 1.4.0 (`shoppingLists`, `me.shopping.family`); null with an older plugin. */
    val shoppingFamily: NShoppingFamily? = null,
    /** Plugin 1.5.0 (`places`, `me.places`); null with an older plugin. */
    val places: NPlacesInfo? = null,
) {
    fun has(feature: String) = feature in features

    /** A task may hang on a place: `Task.place`, „Meine Orte“, the geofences of the phone (never a version — this word). */
    val taskPlaces: Boolean get() = has(NFeature.PLACES)

    /** `POST places/search` may be asked now: the route is there, the administrator and I have it on, the service is set up. */
    val placeSearch: Boolean get() = taskPlaces && has(NFeature.PLACE_SEARCH) && places?.searchOn == true

    /** Shopping lists in the hub: `GET shopping`, `POST shopping/ops`, `nav.shopping`. */
    val shoppingLists: Boolean get() = has(NFeature.SHOPPING_LISTS)

    /** A task may carry a shopping list (needs the lists themselves). */
    val taskList: Boolean get() = shoppingLists && has(NFeature.TASK_LIST)

    /** „Ich gehe einkaufen“ / „Kannst du einkaufen gehen?“ for family lists: the feature and the Familie plugin offering it. */
    val familyShopping: Boolean get() = shoppingLists && has(NFeature.FAMILY_SHOPPING) && shoppingFamily?.available == true

    /** Reminder pushes with buttons, „Später“, `GET reminders` (the phone's own alarms). */
    val reminderActions: Boolean get() = has(NFeature.REMINDER_ACTIONS)

    /** The card „Gestern offen geblieben“ and its morning hint. */
    val yesterdayOpen: Boolean get() = has(NFeature.YESTERDAY_OPEN)

    /** The chip „Kalender“ and the mark „im Kalender“: the feature and all three switches of [calendar]. */
    val calendarShown: Boolean get() = has(NFeature.TASK_CALENDAR) && calendar?.shown == true
}

data class NDue(val date: String, val time: String? = null)

/** `me.shopping.family` (plugin 1.4.0): Familie offers its part, the family is known to the hub (the move was made), I am a parent. */
data class NShoppingFamily(val available: Boolean = false, val bound: Boolean = false, val parent: Boolean = false)

/**
 * The shopping list a task carries (`Task.attachedList`, plugin 1.4.0 `taskList`): [id] the hub's number of the list,
 * [uid] the id the device knows it by ([com.gatecontrol.android.familie.shopping.ShoppingList.id]).
 */
data class NAttached(val id: Long, val uid: String, val name: String, val icon: String?, val open: Int, val total: Int, val done: Int, val family: Boolean)

/**
 * `attach` of `POST parse` (plugin 1.4.0): the server's suggestion for the line. [via] `tag` | `name` | `word`;
 * [list]: exactly one fits; else the choice among [lists].
 */
data class NAttach(val via: String, val word: String?, val list: NAttached?, val lists: List<NAttached>)

/** The answer of `POST parse` as far as the app uses it: the suggestion (the line itself is read on the phone). */
data class NParseAttach(val attach: NAttach?)

/** `notified` of „Kannst du einkaufen gehen?“ as the Familie plugin says it: [ok] null when nothing was sent. */
data class NDelivery(val ok: Boolean?, val code: String?)

/** A shopping list of the side menu (`nav.shopping`). */
data class NNavShop(val uid: String, val listNo: Long?, val name: String, val icon: String?, val open: Int, val total: Int, val family: Boolean, val shared: Boolean, val role: String?)

/**
 * The reminder of a task still to come (plugin 1.3.0, `reminderActions`). [tag] = `task:<id>:<atMs>` names one task at
 * one moment; [mine]: the server sends its push to me (only then the phone sets an alarm of its own).
 */
data class NReminder(val tag: String, val at: String?, val atMs: Long, val mine: Boolean, val snoozed: Boolean)

/** A task's event in Mail's calendar „Aufgaben“ (plugin 1.3.0, `taskCalendar`): `synced` | `pending`. */
data class NTaskCalendar(val state: String, val eventId: Long?, val calendarId: Long?, val mine: Boolean) {
    val synced: Boolean get() = state == "synced"
}
/**
 * A person of a list or notebook. [role] and [since] (plugin 1.2.0, `shareRoles`): `read` | `write` of a member,
 * since when (ms).
 */
data class NPerson(val id: Long?, val name: String?, val role: String? = null, val since: Long? = null)
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
    /** Plugin 1.2.0: false in a list I may only view, unless the task is assigned to me. */
    val canComplete: Boolean = true,
    /** Plugin 1.2.0: false in a list I may only view (no changing, deleting, sub tasks, attachments). */
    val canEdit: Boolean = true,
    /** Plugin 1.3.0: the reminder still to come (null: none, sent already, or the task is done). */
    val reminder: NReminder? = null,
    /** Plugin 1.3.0: the reminder was moved with „Später“. */
    val snoozed: Boolean = false,
    /** Plugin 1.3.0: the reminder could not be sent any more (the task is under „Gestern offen geblieben“). */
    val missedReminder: Boolean = false,
    /** Plugin 1.3.0: the task's event in the calendar (null: none). */
    val calendar: NTaskCalendar? = null,
    /** Plugin 1.4.0 (`taskList`): the shopping list this task carries (null: none, deleted, or not shared with me). */
    val attachedList: NAttached? = null,
    /** Plugin 1.5.0 (`places`): the place this task hangs on (null: none, or an older plugin). */
    val place: NPlace? = null,
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

data class NView(
    val view: String,
    val title: String,
    val sections: List<NSection>,
    val list: NList? = null,
    val plugin: String? = null,
    /** Plugin 1.2.0 (`listDays`, „Mein Tag“ only): the lists whose day is today, as cards on top. */
    val listdays: List<NListDay> = emptyList(),
    /** Plugin 1.3.0 (`yesterdayOpen`, „Mein Tag“ only): the card „Gestern offen geblieben“ (null with an older plugin). */
    val yesterday: NYesterday? = null,
)

/** `GET yesterday` / `yesterday` of „Mein Tag“ (plugin 1.3.0): what stayed open yesterday and where it can go. */
data class NYesterday(val day: String?, val title: String, val count: Int, val items: List<NTask>, val moveOptions: List<NMoveOption>)

/** A choice of „Verschieben“: `tomorrow` | `weekend`, its date and the server's word for it. */
data class NMoveOption(val to: String, val date: String?, val label: String)

/** `POST yesterday` → how many changed, which the person may not change, the card as it is now. */
data class NYesterdayResult(val changed: Int, val failed: List<Long>, val yesterday: NYesterday?)

/** A button of a reminder (`GET reminders`): `done`, `snooze_1h`, `snooze_eve`, `snooze_tom`; with a list (1.4.0) `open_list`. */
data class NReminderAction(val id: String, val label: String)

/** One reminder the server would send me (`GET reminders`) — what the phone's own alarm shows, unchanged. */
data class NReminderEntry(
    val tag: String,
    val taskId: Long,
    val listId: Long?,
    val area: String?,
    val title: String,
    val body: String,
    val atMs: Long,
    val important: Boolean,
    val collapseKey: String,
    val actions: List<NReminderAction>,
    /** Plugin 1.4.0: the list the task carries — the button `open_list` opens it. */
    val attachedList: NAttached? = null,
)

/** `GET reminders`: [enabled] false — the person switched „Erinnerungen an Aufgaben“ off (no alarms). */
data class NReminderFeed(val nowMs: Long, val enabled: Boolean, val reminders: List<NReminderEntry>)

/** A card „Heute ist Einkaufstag“ of „Mein Tag“: the list, its first open tasks (to tick off) and how many more. */
data class NListDay(val list: NList, val title: String, val open: Int, val assignedToMe: Int, val items: List<NTask>, val more: Int)

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
    /** Plugin 1.2.0 (`listIcons`): one of [NMe.listIcons]; null = the plain list. */
    val icon: String? = null,
    /** Plugin 1.2.0 (`shareRoles`): false when I may only view it. */
    val canEdit: Boolean = true,
    /** Plugin 1.2.0 (`listDays`): ISO week days (1 = Monday … 7 = Sunday) the list shows on top of „Mein Tag“. */
    val days: List<Int> = emptyList(),
    /** Plugin 1.2.0: the time of the morning message on those days (null: none). */
    val remind: String? = null,
    /** Plugin 1.2.0: I switched „In ‚Mein Tag‘ zeigen“ off for me. */
    val mydayOff: Boolean = false,
    /** Plugin 1.2.0: today is one of [days]. */
    val dayToday: Boolean = false,
) {
    val isOwner: Boolean get() = role == null || role == "owner"
}

/** A folder of lists (each person's own); [open] (plugin 1.2.0, `groupEdit`): the open tasks of its lists. */
data class NGroup(val id: Long, val name: String, val area: String, val position: Long = 0, val open: Int? = null)

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
    /** Plugin 1.2.0 (`shareRoles`): false when I may only view it. */
    val canEdit: Boolean = true,
) {
    val isOwner: Boolean get() = role == null || role == "owner"
}

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
    /** Plugin 1.4.0 (`shoppingLists`): the shopping lists of the area. */
    val shopping: List<NNavShop> = emptyList(),
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

/** `GET lists/<id>/usage` (plugin 1.2.0, `deleteKeep`): what deleting the list touches. */
data class NListUsage(val open: Int, val done: Int, val members: List<NPerson>, val isDefault: Boolean, val canDelete: Boolean, val target: NRef?)

/** `GET notebooks/<id>/usage` (plugin 1.2.0, `deleteKeep`). */
data class NNotebookUsage(
    val notes: Int,
    val withImages: Int,
    val withFiles: Int,
    val members: List<NPerson>,
    val isDefault: Boolean,
    val canDelete: Boolean,
    val vault: Boolean,
    val target: NRef?,
)

data class NRef(val id: Long, val name: String)

/** The answer of a delete (plugin 1.2.0): moved and removed, where to, until when „Rückgängig“ works (ms). */
data class NDeleted(val left: Boolean, val moved: Int, val removed: Int, val target: NRef?, val restoreUntil: Long?)

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

/** The abilities of plugin 1.2.0 in `GET me` → `features`; without them (1.1.x) the app hides what needs them. */
object NFeature {
    /** Lists with week days, a morning message and „In ‚Mein Tag‘ zeigen“; the cards in „Mein Tag“; push `listday`. */
    const val LIST_DAYS = "listDays"
    /** Members with `read` / `write`. */
    const val SHARE_ROLES = "shareRoles"
    /** `POST groups { lists }`, groups with `open`. */
    const val GROUP_EDIT = "groupEdit"
    /** Deleting with a choice (move or delete), `usage`, `restore` („Rückgängig“). */
    const val DELETE_KEEP = "deleteKeep"
    /** Lists with an icon. */
    const val LIST_ICONS = "listIcons"

    // plugin 1.3.0 — never a version, only these words
    /** Reminder pushes with buttons and a `tag`, `POST tasks/<id>/snooze`, `Task.reminder`, `GET reminders`. */
    const val REMINDER_ACTIONS = "reminderActions"
    /** „Gestern offen geblieben“: `GET/POST yesterday`, `POST tasks/<id>/move`, `yesterday` of „Mein Tag“, its push. */
    const val YESTERDAY_OPEN = "yesterdayOpen"
    /** `Task.calendar`, `me.calendar`, `prefs.calendar`. */
    const val TASK_CALENDAR = "taskCalendar"

    // plugin 1.4.0
    /** `GET shopping`, `POST shopping/ops`, `me.shopping`, the event `changed { what: ['shopping'] }`. */
    const val SHOPPING_LISTS = "shoppingLists"
    /** `Task.attachedList`, `attachedListId` when writing, `attach` of `POST parse`, „alles abgehakt“, the button „Liste öffnen“. */
    const val TASK_LIST = "taskList"
    /** Family lists: `POST shopping/lists/<id>/going`, `…/assign`. */
    const val FAMILY_SHOPPING = "familyShopping"

    // plugin 1.5.0
    /** `Task.place`, `place` when writing and in `POST parse`, `GET places`, `POST places/watch` / `fired`, `me.places`. */
    const val PLACES = "places"
    /** The route `POST places/search` is there; whether it is on says `me.places.search.state`. */
    const val PLACE_SEARCH = "placeSearch"
}
