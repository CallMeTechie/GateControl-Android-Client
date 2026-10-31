package com.gatecontrol.android.ui.mail.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.mail.MBooks
import com.gatecontrol.android.mail.MCardSummary
import com.gatecontrol.android.mail.MExport
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MResult
import com.gatecontrol.android.mail.MailContactLogic
import com.gatecontrol.android.mail.MailPimJson
import com.gatecontrol.android.mail.MailPimLogic
import com.gatecontrol.android.mail.MailRepository
import com.gatecontrol.android.ui.mail.MailHomeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the list shows: all contacts, one address book or one group. */
data class ContactsFilter(val book: Long? = null, val group: Long? = null)

data class ContactsState(
    val query: String = "",
    /** contacts | groups */
    val tab: String = TAB_CONTACTS,
    val filter: ContactsFilter = ContactsFilter(),
    val loading: Boolean = true,
    val error: String? = null,
    /** Possible duplicates (the band and the menu). */
    val duplicates: Int = 0,
    /** Found by the server while nothing is copied yet. */
    val remote: List<MCardSummary>? = null,
    val message: String? = null,
    /** The vCard file to share or save. */
    val export: MExport? = null,
    val busy: Boolean = false,
) {
    companion object {
        const val TAB_CONTACTS = "contacts"
        const val TAB_GROUPS = "groups"
    }
}

/**
 * The contacts area of the Mail tab (contract D4): every contact (copied, searched on the device), the address books
 * and groups of the menu, the duplicates' count, a new group, vCard import (Gmail) and export.
 */
@HiltViewModel
class MailContactsViewModel @Inject constructor(
    private val repository: MailRepository,
) : ViewModel() {

    private val pim = repository.pim
    private val api = repository.api
    val me: StateFlow<MMe?> = repository.me
    val books: StateFlow<MBooks?> = pim.books
    val offline: StateFlow<Boolean> = pim.offline
    val relogins: StateFlow<Set<Long>> = pim.relogins

    private val _state = MutableStateFlow(ContactsState())
    val state: StateFlow<ContactsState> = _state.asStateFlow()

    /** The list shown: filtered by address book or group, searched on the device. */
    val shown: StateFlow<List<MCardSummary>> = combine(pim.cards, _state) { cards, s ->
        // the GC contacts stand for their linked cards (Mail 1.4.0): an account's address book shows the ones linked from it
        val list = when {
            s.filter.group != null -> MailContactLogic.listed(cards).filter { s.filter.group in it.groups }
            s.filter.book != null -> cards.filter { c -> c.addressbookId == s.filter.book || (c.masterId == null && c.sources.any { it.addressbookId == s.filter.book }) }
            else -> MailContactLogic.listed(cards)
        }
        if (s.query.isBlank()) list else (s.remote?.takeIf { cards.isEmpty() } ?: MailPimLogic.search(list, s.query))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val total: StateFlow<List<MCardSummary>> = pim.cards

    private var searchJob: Job? = null

    init {
        refresh()
        viewModelScope.launch {
            pim.contactsChanged.collect { refreshDuplicates() }
        }
    }

    fun refresh() {
        _state.update { it.copy(loading = pim.cards.value.isEmpty(), error = null) }
        viewModelScope.launch {
            pim.refreshBooks()
            when (val r = pim.syncCards()) {
                is MResult.Ok -> _state.update { it.copy(loading = false, error = null) }
                else -> _state.update { it.copy(loading = false, error = MailHomeViewModel.code(r).takeIf { pim.cards.value.isEmpty() }) }
            }
            refreshDuplicates()
        }
    }

    fun onShown() {
        pim.refreshBooksSoon()
        pim.syncCardsSoon()
    }

    private fun refreshDuplicates() {
        viewModelScope.launch {
            // the certain ones the server merges by itself (Mail 1.4.0): only the ones to look at count
            api.duplicates().valueOrNull()?.let { d -> _state.update { it.copy(duplicates = d.count { x -> x.certain != true }) } }
        }
    }

    fun setQuery(q: String) {
        _state.update { it.copy(query = q.take(100), remote = null) }
        searchJob?.cancel()
        if (pim.cards.value.isNotEmpty() || q.trim().length < 2) return
        // nothing copied yet: the server searches
        searchJob = viewModelScope.launch {
            delay(300)
            api.cards(q = q).valueOrNull()?.let { (page, _) -> _state.update { it.copy(remote = page.items) } }
        }
    }

    fun setTab(tab: String) = _state.update { it.copy(tab = tab) }

    fun setFilter(f: ContactsFilter) = _state.update { it.copy(filter = f, tab = ContactsState.TAB_CONTACTS) }

    fun toggleBook(id: Long) {
        val b = pim.books.value?.book(id) ?: return
        viewModelScope.launch {
            when (val r = api.patchAddressbook(id, !b.visible)) {
                is MResult.Ok -> {
                    pim.refreshBooks()
                    pim.syncCards(full = true)
                }
                else -> _state.update { it.copy(message = MailHomeViewModel.code(r)) }
            }
        }
    }

    fun createGroup(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            when (val r = api.createGroup(name)) {
                is MResult.Ok -> pim.refreshBooks()
                else -> _state.update { it.copy(message = MailHomeViewModel.code(r)) }
            }
        }
    }

    /** vCard import (a file of Google Contacts, …) into the default (or [book]) address book. */
    fun importVcf(text: String, book: Long? = null) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val r = api.importVcf(text, book)) {
                is MResult.Ok -> {
                    _state.update { it.copy(busy = false, message = "import:${r.value.imported}:${r.value.updated}:${r.value.skipped}") }
                    pim.syncCards()
                    pim.refreshBooks()
                }
                else -> _state.update { it.copy(busy = false, message = MailHomeViewModel.code(r)) }
            }
        }
    }

    /** vCard export of the list shown (an address book, a group or all). */
    fun export() {
        val f = _state.value.filter
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val r = api.exportVcf(f.book, f.group)) {
                is MResult.Ok -> _state.update { it.copy(busy = false, export = r.value) }
                else -> _state.update { it.copy(busy = false, message = MailHomeViewModel.code(r)) }
            }
        }
    }

    fun exported() = _state.update { it.copy(export = null) }

    fun failed(code: String) = _state.update { it.copy(message = code) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun relogin(accountId: Long) = pim.markRelogin(accountId)

    /** The star (Mail 1.4.0): a favourite or not; shown at once. */
    fun toggleFavorite(c: MCardSummary) {
        val on = c.favorite != true
        viewModelScope.launch {
            when (val r = api.patchOwn(c.id, MailPimJson.ownBody(favorite = on))) {
                is MResult.Ok -> pim.putFavorite(r.value.card)
                is MResult.Failed -> _state.update { it.copy(message = if (r.code == "limit") "fav_limit" else r.code) }
                else -> _state.update { it.copy(message = MailHomeViewModel.code(r)) }
            }
        }
    }
}
