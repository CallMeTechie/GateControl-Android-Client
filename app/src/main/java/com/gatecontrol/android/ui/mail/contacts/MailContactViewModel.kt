package com.gatecontrol.android.ui.mail.contacts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.mail.MActivity
import com.gatecontrol.android.mail.MBooks
import com.gatecontrol.android.mail.MCalendars
import com.gatecontrol.android.mail.MCard
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
)

/** One contact (mockup "Kontakt"): its fields, its last mails and shared events, delete. */
@HiltViewModel
class MailContactViewModel @Inject constructor(
    private val repository: MailRepository,
    saved: SavedStateHandle,
) : ViewModel() {

    private val pim = repository.pim
    private val api = repository.api
    val me: StateFlow<MMe?> = repository.me
    val books: StateFlow<MBooks?> = pim.books
    val calendars: StateFlow<MCalendars?> = pim.calendars
    val offline: StateFlow<Boolean> = pim.offline

    val id: Long = saved.get<String>(Screen.MailContact.ARG_ID)?.toLongOrNull() ?: 0L

    private val _state = MutableStateFlow(ContactState())
    val state: StateFlow<ContactState> = _state.asStateFlow()

    init {
        if (pim.books.value == null) pim.refreshBooksSoon()
        load()
        viewModelScope.launch {
            pim.contactsChanged.collect { c -> if (c.contactIds.isEmpty() || id in c.contactIds) load(quiet = true) }
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
                is MResult.Ok -> _state.update { it.copy(card = r.value, loading = false, error = null) }
                else -> _state.update { it.copy(loading = false, error = MailHomeViewModel.code(r)) }
            }
            api.activity(id).valueOrNull()?.let { a -> _state.update { it.copy(activity = a) } }
        }
    }

    fun delete() {
        viewModelScope.launch {
            when (val r = api.deleteCard(id)) {
                is MResult.Ok -> {
                    pim.forgetCard(id)
                    _state.update { it.copy(gone = true) }
                }
                else -> _state.update { it.copy(message = MailHomeViewModel.code(r)) }
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
