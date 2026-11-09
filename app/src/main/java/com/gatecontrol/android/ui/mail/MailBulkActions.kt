package com.gatecontrol.android.ui.mail

import com.gatecontrol.android.mail.MFolder
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MResult
import com.gatecontrol.android.mail.MSummary
import com.gatecontrol.android.mail.MailBulk
import com.gatecontrol.android.mail.MailBulk.Action
import com.gatecontrol.android.mail.MailLogic
import com.gatecontrol.android.mail.MailRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * "Rückgängig" after an action on several mails (or one swipe): what happened to how many, the folder for a move or a
 * copy, the server's tokens to take it back (none: no button). [purged]: deleted for good (they lay in the trash).
 */
data class MailSnack(
    val id: Long,
    val action: Action,
    val count: Int,
    val folder: MFolder? = null,
    val tokens: List<String> = emptyList(),
    val purged: Boolean = false,
)

/** The question before a delete: [count] mails, [purge] for good (they lie in the trash; always asked). */
data class MailDeleteAsk(val ids: List<Long>, val count: Int, val purge: Boolean, val fromSelection: Boolean)

/**
 * The folder picker for [ids] ([count] mails of [accounts]): [mode] move or copy ([copy]: the server can copy),
 * [current] the folder they lie in (when it is one), [fromSelection] the ticked mails (else one row: a swipe, its menu).
 */
data class MailPicker(
    val ids: List<Long>,
    val count: Int,
    val accounts: Set<Long>,
    val mode: Action,
    val copy: Boolean,
    val current: Long?,
    val fromSelection: Boolean,
)

/**
 * The selection of a Mail list and what it does with several mails at once (Mail 1.13 "Mehrfachauswahl"): archive,
 * delete (with the question or "Rückgängig"), move or copy into a chosen folder, read / unread, flag, Relevant /
 * Sonstige, spam. The list changes at once ([setItems]) and goes back when the server says no; the calls go in parts
 * of [MailBulk.MAX_IDS]. A swipe and the menu of one row go the same way ([run] with ids).
 *
 * Held by the screen's view model ([scope]): [items] are the mails on screen; [tabbed] tells whether the list is one
 * tab of an inbox (Relevant / Sonstige take mails out of it); [listQuery] is the query of `GET messages` for "Alle"
 * on the server (null: the loaded mails only, e.g. the search); [conversations] whether the rows are conversations
 * (`threads: true`); [here] the folder shown; [accountsOfAll] the accounts the whole list covers; [changed] loads it again.
 */
internal class MailBulkActions(
    private val scope: CoroutineScope,
    private val repository: MailRepository,
    private val items: () -> List<MSummary>,
    private val setItems: ((List<MSummary>) -> List<MSummary>) -> Unit,
    private val tabbed: () -> Boolean = { false },
    private val listQuery: () -> Map<String, String>? = { null },
    private val conversations: () -> Boolean = { false },
    private val here: () -> MFolder? = { null },
    private val accountsOfAll: () -> Set<Long> = { emptySet() },
    private val changed: () -> Unit = {},
) {
    private val api = repository.api
    private val me: MMe? get() = repository.me.value
    private val snackIds = AtomicLong(0)

    private val _selection = MutableStateFlow(MailBulk.Selection())
    val selection: StateFlow<MailBulk.Selection> = _selection.asStateFlow()

    private val _snack = MutableStateFlow<MailSnack?>(null)
    /** The snackbar to show once (with "Rückgängig" when it has tokens). */
    val snack: StateFlow<MailSnack?> = _snack.asStateFlow()

    private val _ask = MutableStateFlow<MailDeleteAsk?>(null)
    val ask: StateFlow<MailDeleteAsk?> = _ask.asStateFlow()

    private val _picker = MutableStateFlow<MailPicker?>(null)
    val picker: StateFlow<MailPicker?> = _picker.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** A failure's code to show once. */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _loadingAll = MutableStateFlow(false)
    /** "Alle" asks the server for every mail of the list. */
    val loadingAll: StateFlow<Boolean> = _loadingAll.asStateFlow()

    // ─── the selection ──────────────────────────────────────────────

    /** The ticked mails that are loaded, in the order of the list. */
    fun selectedItems(): List<MSummary> {
        val ids = _selection.value.ids
        if (ids.isEmpty()) return emptyList()
        return items().filter { it.id in ids }
    }

    /** A long press or a tap on the picture: the mail is ticked, the selection starts. */
    fun select(id: Long) = _selection.update { MailBulk.select(it, id) }

    fun toggle(id: Long) = _selection.update { MailBulk.toggle(it, id) }

    fun clear() {
        _selection.value = MailBulk.Selection()
    }

    /** The list loaded again: mails gone from it are no longer ticked (but with "Alle"). */
    fun onListChanged() = _selection.update { MailBulk.prune(it, items().map { m -> m.id }) }

    /**
     * "Alle" / "Keine": everything ticked → none (the selection ends); else every mail of the list — with Mail 1.13
     * (`bulkAll`) also those not loaded yet (`GET messages/ids`), else the loaded ones.
     */
    fun allOrNone() {
        val loaded = items().map { it.id }
        val s = _selection.value
        if (MailBulk.allSelected(s, loaded)) {
            clear()
            return
        }
        val query = listQuery()?.takeIf { MailBulk.canSelectAll(me) }
        if (query == null) {
            _selection.value = MailBulk.selectLoaded(s, loaded)
            return
        }
        _selection.value = MailBulk.selectLoaded(s, loaded)
        _loadingAll.value = true
        scope.launch {
            when (val r = api.messageIds(query)) {
                // only while the selection is still on (✕ meanwhile ends it)
                is MResult.Ok -> if (_selection.value.active) _selection.value = MailBulk.selectEverything(items().map { it.id }, r.value)
                else -> _message.value = MailHomeViewModel.code(r)
            }
            _loadingAll.value = false
        }
    }

    /** The accounts of the ticked mails ("Alle": those of the whole list). */
    fun selectedAccounts(): Set<Long> {
        val s = _selection.value
        val loaded = MailBulk.accounts(selectedItems())
        return if (s.all != null) loaded + accountsOfAll() else loaded
    }

    // ─── actions ────────────────────────────────────────────────────

    /**
     * An action on the ticked mails. Read / unread and the flag keep the selection (the next action can follow);
     * everything else ends it.
     */
    fun onSelection(action: Action) {
        val ids = _selection.value.ids.toList()
        if (ids.isEmpty()) return
        when (action) {
            Action.DELETE -> delete(ids, fromSelection = true)
            Action.MOVE, Action.COPY -> openPicker(action, ids, fromSelection = true)
            in KEEPS_SELECTION -> run(action, ids)
            else -> {
                clear()
                run(action, ids)
            }
        }
    }

    /** Delete [ids]: asks first when the person wants it or the mails lie in the trash (for good), else at once. */
    fun delete(ids: List<Long>, fromSelection: Boolean = false) {
        val chosen = items().filter { it.id in ids.toSet() }
        val purge = MailBulk.purges(chosen, repository.folders.value, here())
        if (purge || MailBulk.confirmDelete(me)) {
            _ask.value = MailDeleteAsk(ids, ids.size, purge, fromSelection)
            return
        }
        if (fromSelection) clear()
        run(Action.DELETE, ids)
    }

    /** "Löschen" of the question; [dontAsk]: "Nicht mehr fragen" (the setting goes off). */
    fun deleteConfirmed(dontAsk: Boolean) {
        val a = _ask.value ?: return
        _ask.value = null
        if (dontAsk && !a.purge) scope.launch { repository.putSettings(confirmDelete = false)?.let { _message.value = it } }
        if (a.fromSelection) clear()
        run(Action.DELETE, a.ids)
    }

    fun cancelDelete() {
        _ask.value = null
    }

    /** "Verschieben" / "Kopieren": the folder picker for [ids] (copy only when the server can). */
    fun openPicker(mode: Action, ids: List<Long>, fromSelection: Boolean = false) {
        val chosen = items().filter { it.id in ids.toSet() }
        val accounts = if (fromSelection) selectedAccounts() else MailBulk.accounts(chosen)
        val copy = MailBulk.canCopy(me)
        _picker.value = MailPicker(
            ids = ids,
            count = ids.size,
            accounts = accounts,
            mode = if (mode == Action.COPY && copy) Action.COPY else Action.MOVE,
            copy = copy,
            current = MailBulk.commonFolder(chosen) ?: here()?.id,
            fromSelection = fromSelection,
        )
    }

    fun setPickerMode(mode: Action) = _picker.update { p -> p?.copy(mode = if (mode == Action.COPY && p.copy) Action.COPY else Action.MOVE) }

    fun closePicker() {
        _picker.value = null
    }

    /** A folder picked: the mails go (or are copied) there; it comes first in "Zuletzt". */
    fun pick(folder: MFolder) {
        val p = _picker.value ?: return
        _picker.value = null
        if (p.fromSelection) clear()
        repository.useFolder(folder.id)
        run(p.mode, p.ids, folder)
    }

    /**
     * [action] on [ids]: the list changes at once, the calls go in parts; the list goes back when no part went
     * through (with a part done it loads again). Afterwards the snackbar (with "Rückgängig" when the server keeps a
     * token) for archive, delete, move, copy and spam.
     */
    fun run(action: Action, ids: List<Long>, folder: MFolder? = null) {
        val parts = MailBulk.chunks(ids)
        if (parts.isEmpty() || (MailBulk.needsFolder(action) && folder == null)) return
        val tabbed = tabbed()
        val idSet = ids.toHashSet()
        val before = items()
        setItems { MailBulk.optimistic(it, idSet, action, tabbed) }
        val threads = MailBulk.threads(me, conversations())
        val all = _selection.value.all != null
        scope.launch {
            val done = ArrayList<com.gatecontrol.android.mail.MBulkDone>(parts.size)
            var failure: MResult<*>? = null
            for (part in parts) {
                val r = api.bulk(action.path, MailBulk.body(action, part, threads, folder?.id))
                if (r is MResult.Ok) done += r.value else {
                    failure = r
                    break
                }
            }
            if (done.isEmpty()) {
                // nothing went through: as it was
                setItems { now -> MailBulk.restore(now, before, idSet) }
                failure?.let { _message.value = MailHomeViewModel.code(it) }
                return@launch
            }
            val sum = MailBulk.combine(done)
            val leaves = MailBulk.leaves(action, tabbed)
            if (!leaves && sum.items.isNotEmpty()) setItems { MailLogic.apply(it, sum.items) }
            if (leaves) repository.cache.forgetMessages(ids)
            repository.refreshSoon(0)
            if (failure != null) _message.value = MailHomeViewModel.code(failure)
            // a part failed, whole conversations or mails not loaded changed: the list as the server has it
            if (failure != null || threads || all || action == Action.COPY) changed()
            if (action in SNACKED) {
                _snack.value = MailSnack(
                    id = snackIds.incrementAndGet(),
                    action = action,
                    count = sum.count.coerceAtLeast(1),
                    folder = folder,
                    tokens = if (MailBulk.undoable(action, me)) sum.tokens else emptyList(),
                    purged = action == Action.DELETE && sum.purged.isNotEmpty(),
                )
            }
        }
    }

    fun snackShown() {
        _snack.value = null
    }

    /** "Rückgängig": every token of the action; the list loads again. */
    fun undo(s: MailSnack) {
        if (s.tokens.isEmpty()) return
        scope.launch {
            var failure: MResult<*>? = null
            for (t in s.tokens) {
                val r = api.undo(t)
                if (r !is MResult.Ok) failure = r
            }
            failure?.let { _message.value = undoCode(it) }
            repository.refreshSoon(0)
            changed()
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    companion object {
        /** The actions after which the mails stay ticked. */
        val KEEPS_SELECTION = setOf(Action.READ, Action.UNREAD, Action.FLAG, Action.UNFLAG)

        /** The actions that say what happened (and offer "Rückgängig"). */
        val SNACKED = setOf(Action.ARCHIVE, Action.DELETE, Action.MOVE, Action.COPY, Action.JUNK)

        /** "Rückgängig" too late (`410 undo_expired`) or already done (`404`): one sentence for both. */
        internal fun undoCode(r: MResult<*>): String = when ((r as? MResult.Failed)?.code) {
            "undo_expired", "http_410", "not_found", "http_404" -> UNDO_TOO_LATE
            else -> MailHomeViewModel.code(r)
        }

        const val UNDO_TOO_LATE = "undo_too_late"
    }
}
