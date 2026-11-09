package com.gatecontrol.android.ui.mail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.mail.MAccount
import com.gatecontrol.android.mail.MCounts
import com.gatecontrol.android.mail.MFolder
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MResult
import com.gatecontrol.android.mail.MSettings
import com.gatecontrol.android.mail.MSummary
import com.gatecontrol.android.mail.MUndo
import com.gatecontrol.android.mail.MailBulk
import com.gatecontrol.android.mail.MailLogic
import com.gatecontrol.android.mail.MailRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A list of mails as the screen shows it. */
data class MailListState(
    val items: List<MSummary> = emptyList(),
    val next: String? = null,
    val counts: MCounts? = null,
    /** The first load is running (nothing to show yet). */
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    /** Shown from the device's copy: the server could not be reached. */
    val offline: Boolean = false,
    val error: String? = null,
)

/**
 * The inboxes (mockup "Posteingang"): "Alle Posteingänge" or one account (a folder of it from the menu),
 * Relevant / Sonstige, the swipes, the counts of the menu and the bar to undo a send.
 */
@HiltViewModel
class MailHomeViewModel @Inject constructor(
    private val repository: MailRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {

    private val api = repository.api
    private val cache = repository.cache

    val me: StateFlow<MMe?> = repository.me
    val meError: StateFlow<String?> = repository.meError
    val folders: StateFlow<Map<Long, List<MFolder>>> = repository.folders
    val undo: StateFlow<MUndo?> = repository.undo
    /** The menu's folded accounts ([MailLogic.foldOpen]). */
    val folds: StateFlow<Map<Long, Boolean>> = repository.folds
    /** The menu's folded folders ([MailLogic.folderOpen]). */
    val folderFolds: StateFlow<Map<Long, Boolean>> = repository.folderFolds
    /** "Zuletzt" of the folder picker. */
    val recentFolders: StateFlow<List<Long>> = repository.recentFolders

    private val _box = MutableStateFlow(MailLogic.Box(saved.get<Long>(KEY_ACCOUNT)?.takeIf { it > 0 }, saved.get<Long>(KEY_FOLDER)?.takeIf { it > 0 }))
    val box: StateFlow<MailLogic.Box> = _box.asStateFlow()

    private val _tab = MutableStateFlow(saved.get<String>(KEY_TAB) ?: MSummary.TAB_RELEVANT)
    val tab: StateFlow<String> = _tab.asStateFlow()

    private val _list = MutableStateFlow(MailListState())
    val list: StateFlow<MailListState> = _list.asStateFlow()

    /** "Zum Ablegen" (Drive): how many wait. */
    private val _filing = MutableStateFlow(0)
    val filing: StateFlow<Int> = _filing.asStateFlow()

    /** "Postausgang": planned or failed sends. */
    private val _outbox = MutableStateFlow(0)
    val outbox: StateFlow<Int> = _outbox.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** A short result to show once (an error code, `undone`). */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _reopen = MutableStateFlow<Long?>(null)
    /** A draft to open again (undo after sending). */
    val reopen: StateFlow<Long?> = _reopen.asStateFlow()

    /**
     * Several mails at once (Mail 1.13): the selection of this list and its actions; a swipe and the menu of one
     * row go the same way ([act]).
     */
    internal val bulk = MailBulkActions(
        scope = viewModelScope,
        repository = repository,
        items = { _list.value.items },
        setItems = { f -> _list.update { it.copy(items = f(it.items)) } },
        tabbed = { inbox() && _tab.value != MSummary.TAB_ALL },
        listQuery = { MailLogic.listQuery(_box.value, _tab.value, inbox()) },
        conversations = { true },
        here = ::here,
        accountsOfAll = { _box.value.accountId?.let { setOf(it) } ?: repository.me.value?.accounts.orEmpty().mapTo(LinkedHashSet()) { it.id } },
        changed = { load(refresh = true, quiet = true) },
    )

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            repository.changed.collect { c ->
                val b = _box.value
                if (c.accountId == null || b.accountId == null || c.accountId == b.accountId) {
                    delay(300)
                    load(refresh = true, quiet = true)
                }
            }
        }
        load(refresh = false)
    }

    fun onShown() {
        repository.refreshSoon()
        loadCounts()
    }

    private fun loadCounts() {
        viewModelScope.launch {
            val me = repository.me.value ?: repository.refresh().valueOrNull()
            if (me?.has(MMe.FEATURE_DRIVE) == true) api.filing().valueOrNull()?.let { _filing.value = it.size }
            api.outbox().valueOrNull()?.let { _outbox.value = it.size }
        }
    }

    /** A tap on an account in the menu: its folders open or close, the menu stays. */
    fun toggleFold(accountId: Long) = repository.toggleFold(accountId, _box.value)

    /** A tap on a folder's arrow in the menu: its children open or close, the menu stays. */
    fun toggleFolder(accountId: Long, folderId: Long) = repository.toggleFolder(accountId, folderId, _box.value)

    fun selectBox(box: MailLogic.Box) {
        if (box == _box.value) return
        _box.value = box
        repository.revealFolder(box)
        saved[KEY_ACCOUNT] = box.accountId ?: 0L
        saved[KEY_FOLDER] = box.folderId ?: 0L
        bulk.clear()
        _list.value = MailListState()
        load(refresh = false)
    }

    fun selectTab(tab: String) {
        if (tab == _tab.value) return
        _tab.value = tab
        saved[KEY_TAB] = tab
        bulk.clear()
        _list.value = MailListState()
        load(refresh = false)
    }

    private fun inbox(): Boolean = _box.value.isInbox(folders.value[_box.value.accountId].orEmpty())

    private fun cacheKey(): String = _box.value.key(if (inbox()) _tab.value else MSummary.TAB_ALL)

    /** The list from the start; [refresh] = pulled down (the list stays while it loads). */
    fun load(refresh: Boolean, quiet: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val box = _box.value
            val inbox = inbox()
            val key = cacheKey()
            if (!refresh && _list.value.items.isEmpty()) {
                cache.list(key)?.let { c -> _list.value = MailListState(items = c.items, counts = c.counts, loading = true) }
            }
            if (refresh && !quiet) _list.update { it.copy(refreshing = true) }
            val r = api.messages(MailLogic.listQuery(box, _tab.value, inbox))
            when (r) {
                is MResult.Ok -> {
                    val (page, raw) = r.value
                    _list.value = MailListState(items = page.items, next = page.next, counts = page.counts ?: _list.value.counts, loading = false)
                    bulk.onListChanged()
                    cache.putList(key, raw, first = true)
                }
                else -> _list.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        offline = r is MResult.Offline || it.items.isNotEmpty(),
                        error = if (it.items.isEmpty() || !quiet) code(r) else it.error,
                    )
                }
            }
            if (refresh) loadCounts()
        }
    }

    fun loadMore() {
        val s = _list.value
        val next = s.next ?: return
        if (s.loadingMore || s.loading) return
        _list.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val key = cacheKey()
            val r = api.messages(MailLogic.listQuery(_box.value, _tab.value, inbox(), before = next))
            if (r is MResult.Ok) {
                val (page, raw) = r.value
                _list.update { it.copy(items = MailLogic.merge(it.items, page.items), next = page.next, loadingMore = false) }
                cache.putList(key, raw, first = false)
            } else {
                _list.update { it.copy(loadingMore = false) }
            }
        }
    }

    /** The folder shown (null: an inbox of all or of one account). */
    private fun here(): MFolder? = _box.value.folderId?.let { id -> folders.value[_box.value.accountId]?.firstOrNull { it.id == id } }

    /**
     * A swipe or an action of the row's menu on one mail: read / unread and the flag flip, `tab` switches Relevant /
     * Sonstige, `move` with [folderId] moves (without: the folder picker), `delete` asks first when the person wants it.
     */
    fun act(item: MSummary, action: String, folderId: Long? = null) {
        val ids = listOf(item.id)
        when (action) {
            MailLogic.SWIPE_ARCHIVE -> bulk.run(MailBulk.Action.ARCHIVE, ids)
            MailLogic.SWIPE_DELETE -> bulk.delete(ids)
            MailLogic.SWIPE_READ -> bulk.run(if (item.unread) MailBulk.Action.READ else MailBulk.Action.UNREAD, ids)
            "unread" -> bulk.run(MailBulk.Action.UNREAD, ids)
            MSettings.SWIPE_FLAG -> bulk.run(if (item.flagged) MailBulk.Action.UNFLAG else MailBulk.Action.FLAG, ids)
            MSettings.SWIPE_JUNK -> bulk.run(MailBulk.Action.JUNK, ids)
            "tab" -> bulk.run(if (item.tab == MSummary.TAB_OTHER) MailBulk.Action.TO_RELEVANT else MailBulk.Action.TO_OTHER, ids)
            MSettings.SWIPE_MOVE -> {
                val folder = folderId?.let { id -> folders.value[item.accountId]?.firstOrNull { it.id == id } }
                if (folder != null) bulk.run(MailBulk.Action.MOVE, ids, folder) else bulk.openPicker(MailBulk.Action.MOVE, ids)
            }
        }
    }

    /** "Rückgängig" of the bar after sending: the send stops, its draft opens again. */
    fun undoSend(u: MUndo) {
        viewModelScope.launch {
            when (val r = repository.undo(u)) {
                is MResult.Ok -> {
                    _message.value = UNDONE
                    (r.value ?: u.draftId)?.let { _reopen.value = it }
                }
                else -> _message.value = (r as? MResult.Failed)?.code?.takeIf { it != "http_409" } ?: TOO_LATE
            }
        }
    }

    fun undoExpired(u: MUndo) = repository.clearUndo(u.outboxId)

    fun clearMessage() {
        _message.value = null
    }

    fun reopened() {
        _reopen.value = null
    }

    /** Accounts in the order of `me`. */
    fun accounts(): List<MAccount> = me.value?.accounts.orEmpty()

    companion object {
        private const val KEY_ACCOUNT = "account"
        private const val KEY_FOLDER = "folder"
        private const val KEY_TAB = "tab"
        const val UNDONE = "undone"
        const val TOO_LATE = "too_late"

        internal fun code(r: MResult<*>): String = when (r) {
            is MResult.Failed -> r.code
            MResult.Offline -> "offline"
            MResult.NotConfigured -> "not_configured"
            is MResult.Ok -> "ok"
        }
    }
}
