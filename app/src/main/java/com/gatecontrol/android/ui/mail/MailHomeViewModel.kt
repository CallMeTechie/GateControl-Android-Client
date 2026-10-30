package com.gatecontrol.android.ui.mail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.mail.MAccount
import com.gatecontrol.android.mail.MCounts
import com.gatecontrol.android.mail.MFolder
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MResult
import com.gatecontrol.android.mail.MSummary
import com.gatecontrol.android.mail.MUndo
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

    fun selectBox(box: MailLogic.Box) {
        if (box == _box.value) return
        _box.value = box
        saved[KEY_ACCOUNT] = box.accountId ?: 0L
        saved[KEY_FOLDER] = box.folderId ?: 0L
        _list.value = MailListState()
        load(refresh = false)
    }

    fun selectTab(tab: String) {
        if (tab == _tab.value) return
        _tab.value = tab
        saved[KEY_TAB] = tab
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

    /** A swipe or an action of the row's menu. */
    fun act(item: MSummary, action: String, folderId: Long? = null) {
        val ids = listOf(item.id)
        val leaves = when (action) {
            MailLogic.SWIPE_READ, "unread", "flag" -> false
            "tab" -> inbox() && _tab.value != MSummary.TAB_ALL
            else -> true
        }
        // at once on the screen, put right when the server says no
        val before = _list.value.items
        _list.update { s ->
            if (leaves) s.copy(items = s.items.filter { it.id != item.id }) else s.copy(
                items = s.items.map {
                    when {
                        it.id != item.id -> it
                        action == MailLogic.SWIPE_READ -> it.copy(unread = !it.unread)
                        action == "unread" -> it.copy(unread = true)
                        action == "flag" -> it.copy(flagged = !it.flagged)
                        else -> it
                    }
                },
            )
        }
        viewModelScope.launch {
            val r = when (action) {
                MailLogic.SWIPE_ARCHIVE -> api.archive(ids)
                MailLogic.SWIPE_DELETE -> api.delete(ids)
                MailLogic.SWIPE_READ -> api.flags(ids, unread = !item.unread)
                "unread" -> api.flags(ids, unread = true)
                "flag" -> api.flags(ids, flagged = !item.flagged)
                "junk" -> api.junk(ids)
                "tab" -> api.setTab(ids, if (item.tab == MSummary.TAB_OTHER) MSummary.TAB_RELEVANT else MSummary.TAB_OTHER)
                "move" -> api.move(ids, folderId ?: return@launch)
                else -> return@launch
            }
            when (r) {
                is MResult.Ok -> {
                    if (!leaves && r.value.isNotEmpty()) _list.update { s -> s.copy(items = MailLogic.apply(s.items, r.value)) }
                    if (leaves) cache.forgetMessages(ids)
                    repository.refreshSoon(0)
                }
                else -> {
                    _list.update { it.copy(items = before) }
                    _message.value = code(r)
                }
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
