package com.gatecontrol.android.ui.mail.contacts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.mail.MActivity
import com.gatecontrol.android.mail.MBooks
import com.gatecontrol.android.mail.MCalendars
import com.gatecontrol.android.layout.StartArrangement
import com.gatecontrol.android.mail.MCard
import com.gatecontrol.android.mail.MCardConflict
import com.gatecontrol.android.mail.MCardPhone
import com.gatecontrol.android.mail.MContactsChanged
import com.gatecontrol.android.mail.MSource
import com.gatecontrol.android.mail.MailContactLogic
import com.gatecontrol.android.mail.MailPimJson
import com.gatecontrol.android.mail.MailStartPins
import com.gatecontrol.android.mail.StartContactPin
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MResult
import com.gatecontrol.android.mail.MailRepository
import com.gatecontrol.android.navigation.Screen
import com.gatecontrol.android.ui.mail.MailHomeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ContactState(
    val card: MCard? = null,
    val activity: MActivity? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    val gone: Boolean = false,
    /** A hint or a link being answered (its buttons wait). */
    val busy: Boolean = false,
)

/** One contact (mockup "Kontakt"): its fields, its last mails and shared events, delete. */
@HiltViewModel
class MailContactViewModel @Inject constructor(
    private val repository: MailRepository,
    private val arrangement: StartArrangement,
    saved: SavedStateHandle,
) : ViewModel() {

    private val pim = repository.pim
    private val api = repository.api
    val me: StateFlow<MMe?> = repository.me
    val books: StateFlow<MBooks?> = pim.books
    val calendars: StateFlow<MCalendars?> = pim.calendars
    val offline: StateFlow<Boolean> = pim.offline

    /** The contact shown: the route's, or the GC contact that stands for it (Mail 1.4.0, a linked card). */
    var id: Long = saved.get<String>(Screen.MailContact.ARG_ID)?.toLongOrNull() ?: 0L
        private set

    private val _state = MutableStateFlow(ContactState())
    val state: StateFlow<ContactState> = _state.asStateFlow()

    /** What Mail pins to Start (the favourite's tile). */
    val startPins: StateFlow<MailStartPins> = pim.startPins

    init {
        if (pim.books.value == null) pim.refreshBooksSoon()
        load()
        viewModelScope.launch {
            pim.contactsChanged.collect { c ->
                when {
                    // merged into a GC contact: that one is shown from now on
                    c.kind == MContactsChanged.MERGED && id in c.contactIds && c.masterId != null && c.masterId != id -> {
                        id = c.masterId
                        load(quiet = true)
                    }
                    c.contactIds.isEmpty() || id in c.contactIds -> load(quiet = true)
                }
            }
        }
    }

    fun load(quiet: Boolean = false) {
        if (id <= 0) {
            _state.update { it.copy(loading = false, error = "not_found") }
            return
        }
        if (!quiet) _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = pim.card(id)) {
                is MResult.Ok -> {
                    val master = r.value.masterId
                    if (master != null && master != id) {
                        // a linked card: its GC contact is the contact (Mail 1.4.0)
                        id = master
                        load(quiet)
                        return@launch
                    }
                    _state.update { it.copy(card = r.value, loading = false, error = null) }
                }
                else -> _state.update { it.copy(loading = false, error = MailHomeViewModel.code(r)) }
            }
            api.activity(id).valueOrNull()?.let { a -> _state.update { it.copy(activity = a) } }
        }
    }

    private fun shown(card: MCard) {
        _state.update { it.copy(card = card, busy = false) }
        pim.putFavorite(card)
    }

    private fun failed(r: MResult<*>) {
        val code = (r as? MResult.Failed)?.code
        _state.update { it.copy(busy = false, message = if (code == "limit") "fav_limit" else MailHomeViewModel.code(r)) }
    }

    /** The star: a favourite or not (GateControl's own, never to the provider). */
    fun toggleFavorite() {
        val c = _state.value.card ?: return
        val on = c.favorite != true
        _state.update { it.copy(card = c.copy(favorite = on)) }
        viewModelScope.launch {
            when (val r = api.patchOwn(c.id, MailPimJson.ownBody(favorite = on))) {
                is MResult.Ok -> {
                    shown(r.value.card)
                    // no favourite any more: its tile leaves Start
                    if (!on) arrangement.pinContact(StartContactPin(c.id, c.label), on = false)
                }
                else -> {
                    _state.update { it.copy(card = c) }
                    failed(r)
                }
            }
        }
    }

    /** "Als Hauptnummer": the number a tile on Start calls. */
    fun setMainPhone(p: MCardPhone) {
        val c = _state.value.card ?: return
        viewModelScope.launch {
            when (val r = api.patchOwn(c.id, MailPimJson.ownBody(mainPhone = p.value))) {
                is MResult.Ok -> {
                    shown(r.value.card)
                    _state.update { it.copy(message = "main_phone") }
                }
                else -> failed(r)
            }
        }
    }

    /** "Übernehmen" ([add]: "Zusätzlich") of a differing value. */
    fun accept(x: MCardConflict, add: Boolean) {
        val c = _state.value.card ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val r = api.acceptConflict(c.id, x.id, MailContactLogic.acceptMode(x, add))) {
                is MResult.Ok -> shown(r.value)
                else -> {
                    failed(r)
                    load(quiet = true)
                }
            }
        }
    }

    /** "Verwerfen": the value stays away until it changes at the provider. */
    fun dismiss(x: MCardConflict) {
        val c = _state.value.card ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val r = api.dismissConflict(c.id, x.id)) {
                is MResult.Ok -> shown(r.value)
                else -> {
                    failed(r)
                    load(quiet = true)
                }
            }
        }
    }

    /** "Verknüpfung lösen": the linked card [source] stands on its own again (nothing changes at the provider). */
    fun unlink(source: MSource) {
        val c = _state.value.card ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val r = api.unmerge(c.id, listOf(source.id))) {
                is MResult.Ok -> {
                    pim.syncCardsSoon()
                    val card = r.value.card
                    if (card == null) {
                        pim.forgetCard(c.id)
                        _state.update { it.copy(busy = false, gone = true) }
                    } else {
                        _state.update { it.copy(card = card, busy = false, message = "unlinked") }
                    }
                }
                else -> failed(r)
            }
        }
    }

    /** The favourite's tile on Start ([on]) or off it. */
    fun pinToStart(on: Boolean) {
        val c = _state.value.card ?: return
        viewModelScope.launch {
            arrangement.pinContact(MailContactLogic.pinOf(c), on)
            _state.update { it.copy(message = if (on) "pinned" else "unpinned") }
        }
    }

    /** Deletes the contact; [linkedToo] (a GC contact with links): its linked cards at their provider too. */
    fun delete(linkedToo: Boolean = false) {
        viewModelScope.launch {
            when (val r = api.deleteCard(id, linkedToo)) {
                is MResult.Ok -> {
                    pim.forgetCard(id)
                    arrangement.pinContact(StartContactPin(id, ""), on = false)
                    if (linkedToo) pim.syncCardsSoon()
                    _state.update { it.copy(gone = true) }
                }
                else -> _state.update { it.copy(message = MailHomeViewModel.code(r)) }
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
