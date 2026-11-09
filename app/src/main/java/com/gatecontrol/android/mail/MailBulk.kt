package com.gatecontrol.android.mail

import com.google.gson.JsonObject

/**
 * Several mails at once (Mail 1.13 "Mehrfachauswahl"): the selection, what an action does to the list at once, the
 * calls in parts of at most [MAX_IDS], the answers summed up and what the server offers (pure, unit-tested).
 */
object MailBulk {

    /** At most this many ids per bulk call (contract: `ids` 1…500). */
    const val MAX_IDS = 500

    /** The folders used last ("Zuletzt"), kept on the device. */
    const val RECENT_MAX = 4

    /** What the app does with mails; [path] is the plugin's route under `messages/`. */
    enum class Action(val path: String) {
        ARCHIVE("archive"),
        DELETE("delete"),
        MOVE("move"),
        COPY("copy"),
        JUNK("junk"),
        READ("flags"),
        UNREAD("flags"),
        FLAG("flags"),
        UNFLAG("flags"),
        TO_OTHER("tab"),
        TO_RELEVANT("tab"),
    }

    /** The routes [MailApi.bulk] takes. */
    val PATHS: Set<String> = Action.entries.mapTo(HashSet()) { it.path }

    /** The actions the server can take back (`undo`); flags and the tab not (the app would call the opposite). */
    private val UNDOABLE = setOf(Action.MOVE, Action.COPY, Action.ARCHIVE, Action.DELETE, Action.JUNK)

    /** The ids in parts of at most [size], each id once, in their order. */
    fun chunks(ids: Collection<Long>, size: Int = MAX_IDS): List<List<Long>> = ids.filter { it > 0 }.distinct().chunked(size.coerceAtLeast(1))

    /**
     * Whether [action] takes the mails out of the list shown: archive, delete, move, spam always; the tab only where
     * the list is one tab of an inbox ([tabbed]). A copy and the flags leave them.
     */
    fun leaves(action: Action, tabbed: Boolean): Boolean = when (action) {
        Action.ARCHIVE, Action.DELETE, Action.MOVE, Action.JUNK -> true
        Action.TO_OTHER, Action.TO_RELEVANT -> tabbed
        else -> false
    }

    /** The list at once after [action] on [ids] (before the server answers). */
    fun optimistic(items: List<MSummary>, ids: Set<Long>, action: Action, tabbed: Boolean): List<MSummary> {
        if (ids.isEmpty()) return items
        if (leaves(action, tabbed)) return items.filter { it.id !in ids }
        return items.map { m ->
            if (m.id !in ids) m else when (action) {
                Action.READ -> m.copy(unread = false)
                Action.UNREAD -> m.copy(unread = true)
                Action.FLAG -> m.copy(flagged = true)
                Action.UNFLAG -> m.copy(flagged = false)
                Action.TO_OTHER -> m.copy(tab = MSummary.TAB_OTHER)
                Action.TO_RELEVANT -> m.copy(tab = MSummary.TAB_RELEVANT)
                else -> m
            }
        }
    }

    /**
     * The list when the server said no: the mails of [ids] as they were [before], at their places; other rows as they
     * are [now] (one gone meanwhile stays gone, one new stays at the end).
     */
    fun restore(now: List<MSummary>, before: List<MSummary>, ids: Set<Long>): List<MSummary> {
        val nowById = now.associateBy { it.id }
        val beforeIds = before.mapTo(HashSet()) { it.id }
        return before.mapNotNull { b -> if (b.id in ids) b else nowById[b.id] } + now.filter { it.id !in beforeIds }
    }

    /** The body of one call: the ids, `threads: true` with [threads], the action's own field. */
    fun body(action: Action, ids: Collection<Long>, threads: Boolean, folderId: Long? = null): JsonObject =
        MailJson.bulkBody(ids, threads) {
            when (action) {
                Action.READ -> addProperty("unread", false)
                Action.UNREAD -> addProperty("unread", true)
                Action.FLAG -> addProperty("flagged", true)
                Action.UNFLAG -> addProperty("flagged", false)
                Action.TO_OTHER -> addProperty("tab", MSummary.TAB_OTHER)
                Action.TO_RELEVANT -> addProperty("tab", MSummary.TAB_RELEVANT)
                Action.MOVE, Action.COPY -> folderId?.let { addProperty("folderId", it) }
                else -> Unit
            }
        }

    /** A move or a copy needs the folder; every other action none. */
    fun needsFolder(action: Action): Boolean = action == Action.MOVE || action == Action.COPY

    /** Whether "Rückgängig" can be offered after [action]: the server has `undo` and keeps a token for it. */
    fun undoable(action: Action, me: MMe?): Boolean = action in UNDOABLE && me?.has(MMe.FEATURE_UNDO) == true

    /** Whether the calls go with `threads: true`: the list shows conversations and the server has `threadScope`. */
    fun threads(me: MMe?, conversations: Boolean): Boolean = conversations && me?.settings?.threads == true && me.has(MMe.FEATURE_THREAD_SCOPE)

    /** Whether "Kopieren" is offered (Mail 1.13 `copy`). */
    fun canCopy(me: MMe?): Boolean = me?.has(MMe.FEATURE_COPY) == true

    /** Whether "Alle" ticks the whole list on the server (`GET messages/ids`, Mail 1.13 `bulkAll`), else the loaded mails. */
    fun canSelectAll(me: MMe?): Boolean = me?.has(MMe.FEATURE_BULK_ALL) == true

    /** The answers of the parts as one: their mails, how many there were, the tokens to take it back, the mails gone for good. */
    data class Done(val items: List<MSummary>, val count: Int, val tokens: List<String>, val purged: Set<Long>)

    fun combine(parts: List<MBulkDone>): Done = Done(
        items = parts.flatMap { it.items }.distinctBy { it.id },
        count = parts.sumOf { it.count },
        tokens = parts.mapNotNull { it.undo?.token }.distinct(),
        purged = parts.flatMapTo(LinkedHashSet()) { it.purged },
    )

    // ─── the selection ──────────────────────────────────────────────

    /**
     * The ticked mails ([ids], in the order they were ticked). [all]: "Alle" ticked the whole list on the server, also
     * the mails not loaded yet (at most the server's limit, [All.truncated] when there were more).
     */
    data class Selection(val ids: Set<Long> = emptySet(), val all: All? = null) {
        val active: Boolean get() = ids.isNotEmpty()
        val count: Int get() = ids.size
    }

    /** The whole list: [total] mails on the server, [truncated] when only the newest could be ticked. */
    data class All(val total: Int, val truncated: Boolean)

    /** A long press or a tap on the picture: the mail ticked (starts the selection). */
    fun select(s: Selection, id: Long): Selection = if (id in s.ids) s else s.copy(ids = s.ids + id)

    /** A tap while selecting: on or off. Once a mail is taken out, "Alle" no longer holds. */
    fun toggle(s: Selection, id: Long): Selection =
        if (id in s.ids) Selection(s.ids - id, all = null) else s.copy(ids = s.ids + id)

    /** Whether everything is ticked: the whole list ([Selection.all]) or every loaded mail. */
    fun allSelected(s: Selection, loaded: List<Long>): Boolean =
        s.all != null || (loaded.isNotEmpty() && loaded.all { it in s.ids })

    /** "Alle" with the loaded mails only (a server without `bulkAll`, the search). */
    fun selectLoaded(s: Selection, loaded: List<Long>): Selection = Selection(s.ids + loaded, all = null)

    /** "Alle" with the whole list from the server ([ids] of `GET messages/ids`) and the loaded ones. */
    fun selectEverything(loaded: List<Long>, ids: MIds): Selection =
        Selection(LinkedHashSet(loaded + ids.ids), All(total = ids.total, truncated = ids.truncated))

    /**
     * The list loaded again: mails that are no longer in it are no longer ticked — except with "Alle" (those not
     * loaded belong to it).
     */
    fun prune(s: Selection, loaded: List<Long>): Selection {
        if (s.all != null || s.ids.isEmpty()) return s
        val keep = loaded.toHashSet()
        val ids = s.ids.filterTo(LinkedHashSet()) { it in keep }
        return if (ids.size == s.ids.size) s else Selection(ids)
    }

    // ─── what the bar offers ────────────────────────────────────────

    /** The head's icon: "Als gelesen markieren" while one of them is unread, else "Als ungelesen markieren". */
    fun readAction(items: List<MSummary>): Action = if (items.isEmpty() || items.any { it.unread }) Action.READ else Action.UNREAD

    /** "Markieren" while one of them is not flagged, else "Markierung entfernen". */
    fun flagAction(items: List<MSummary>): Action = if (items.isEmpty() || items.any { !it.flagged }) Action.FLAG else Action.UNFLAG

    /** "Nach Sonstige", or "Nach Relevant" when all of them are in Sonstige already. */
    fun tabAction(items: List<MSummary>): Action =
        if (items.isNotEmpty() && items.all { it.tab == MSummary.TAB_OTHER }) Action.TO_RELEVANT else Action.TO_OTHER

    /** The accounts of the ticked mails (a move and a copy stay in one account). */
    fun accounts(items: List<MSummary>): Set<Long> = items.mapTo(LinkedHashSet()) { it.accountId }

    /** Whether a delete removes for good: a mail lies in the trash already (or the list is the trash, [here]). */
    fun purges(items: List<MSummary>, folders: Map<Long, List<MFolder>>, here: MFolder? = null): Boolean {
        if (here?.role == MFolder.ROLE_TRASH) return true
        return items.any { m -> m.folderId != null && folders[m.accountId]?.firstOrNull { it.id == m.folderId }?.role == MFolder.ROLE_TRASH }
    }

    /** The one folder all [items] lie in (null: several or not known). */
    fun commonFolder(items: List<MSummary>): Long? = items.mapNotNull { it.folderId }.distinct().singleOrNull()?.takeIf { items.all { m -> m.folderId == it } }

    // ─── the folder picker ──────────────────────────────────────────

    /** "Zuletzt": [folderId] in front, each once, at most [max]. */
    fun recent(list: List<Long>, folderId: Long, max: Int = RECENT_MAX): List<Long> = (listOf(folderId) + list.filter { it != folderId }).take(max)

    /** The recent folders that still exist in [folders] (of the accounts that can take the mails), in order. */
    fun recentFolders(recent: List<Long>, folders: List<MFolder>): List<MFolder> {
        val byId = folders.associateBy { it.id }
        return recent.mapNotNull { byId[it] }
    }

    /** "Ordner suchen": the folders whose name ([label]) holds [query] (any case), in the tree's order. */
    fun search(folders: List<MFolder>, query: String, label: (MFolder) -> String = { it.name }): List<MFolder> {
        val q = query.trim()
        if (q.isEmpty()) return folders
        return MailLogic.folderTree(folders).nodes.map { it.folder }.filter { label(it).contains(q, ignoreCase = true) || it.path.contains(q, ignoreCase = true) }
    }

    // ─── swipes ─────────────────────────────────────────────────────

    /** The actions the settings offer: the seven with `swipeV2`, else the old four. */
    fun swipeOptions(me: MMe?): List<String> = if (me?.has(MMe.FEATURE_SWIPE_V2) == true) MSettings.SWIPES_V2 else MSettings.SWIPES

    /** What a swipe set to [setting] does (null: nothing, the row does not move). */
    fun swipe(setting: String?): String? = setting?.takeIf { it in MSettings.SWIPES_V2 && it != MSettings.SWIPE_NONE }

    /** Whether "Vor dem Löschen fragen" is on (only a server with `swipeV2` keeps it). */
    fun confirmDelete(me: MMe?): Boolean = me?.has(MMe.FEATURE_SWIPE_V2) == true && me.settings.confirmDelete
}
