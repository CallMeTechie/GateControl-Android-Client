package com.gatecontrol.android.ui.mail.calendar

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.mail.MCalendars
import com.gatecontrol.android.mail.MInvitation
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MResult
import com.gatecontrol.android.mail.MailPimJson
import com.gatecontrol.android.mail.MailRepository
import com.gatecontrol.android.navigation.Screen
import com.gatecontrol.android.ui.mail.MailHomeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

data class InviteState(
    val invitation: MInvitation? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    /** The calendar it goes into ("Eintragen in"), null: the server's choice. */
    val calendarId: Long? = null,
    /** "Ändern" of an answered one: the buttons again. */
    val changing: Boolean = false,
)

/**
 * The card of an invitation (mockup "Einladung", contract C6/D3): answer with a comment and a calendar, propose
 * another time, take over a counter proposal, remove a cancelled one, dismiss. In a mail it starts from
 * `Message.invite`; from a push it loads by id.
 */
@HiltViewModel
class MailInviteViewModel @Inject constructor(
    private val repository: MailRepository,
    saved: SavedStateHandle,
) : ViewModel() {

    private val pim = repository.pim
    private val api = repository.api
    val me: StateFlow<MMe?> = repository.me
    val calendars: StateFlow<MCalendars?> = pim.calendars
    val offline: StateFlow<Boolean> = pim.offline
    val zone: ZoneId = ZoneId.systemDefault()

    private val _state = MutableStateFlow(InviteState())
    val state: StateFlow<InviteState> = _state.asStateFlow()

    init {
        if (pim.calendars.value == null) pim.refreshCalendarsSoon()
        saved.get<String>(Screen.MailInvitation.ARG_ID)?.toLongOrNull()?.takeIf { it > 0 }?.let(::load)
        viewModelScope.launch {
            pim.calChanged.collect { c ->
                val inv = _state.value.invitation ?: return@collect
                if (c.invitationId == inv.id) load(inv.id, quiet = true)
            }
        }
    }

    /** The invitation of a mail (shown at once; changes come with the mail or the live event). */
    fun set(inv: MInvitation) {
        val cur = _state.value.invitation
        if (cur != null && cur.id == inv.id && cur.state == inv.state && cur.myResponse == inv.myResponse) return
        _state.update { it.copy(invitation = inv, calendarId = it.calendarId ?: inv.calendarId) }
    }

    fun load(id: Long, quiet: Boolean = false) {
        if (!quiet) _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = api.invitation(id)) {
                is MResult.Ok -> _state.update { it.copy(invitation = r.value, loading = false, calendarId = it.calendarId ?: r.value.calendarId) }
                else -> _state.update { it.copy(loading = false, error = MailHomeViewModel.code(r)) }
            }
        }
    }

    fun pickCalendar(id: Long) = _state.update { it.copy(calendarId = id) }

    fun change() = _state.update { it.copy(changing = true) }

    /** Zusagen / Mit Vorbehalt / Absagen (also with a proposed time). */
    fun respond(response: String, comment: String?, proposeStart: Long? = null, proposeEnd: Long? = null) {
        val inv = _state.value.invitation ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val body = MailPimJson.respondBody(response, comment, proposeStart = proposeStart, proposeEnd = proposeEnd, calendarId = _state.value.calendarId)
            when (val r = api.respondInvitation(inv.id, body)) {
                is MResult.Ok -> _state.update { it.copy(invitation = r.value.invitation, busy = false, changing = false, message = "cal_answered") }
                else -> _state.update { it.copy(busy = false, message = MailHomeViewModel.code(r)) }
            }
        }
    }

    /** counter: take the proposal; cancel: remove the event from the calendar. */
    fun apply() {
        val inv = _state.value.invitation ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val r = api.applyInvitation(inv.id)) {
                is MResult.Ok -> _state.update { it.copy(invitation = r.value.invitation, busy = false) }
                else -> _state.update { it.copy(busy = false, message = MailHomeViewModel.code(r)) }
            }
        }
    }

    /** counter: decline the proposal ([decline]); otherwise only hide the card. */
    fun dismiss(decline: Boolean) {
        val inv = _state.value.invitation ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val r = api.dismissInvitation(inv.id, decline)) {
                is MResult.Ok -> load(inv.id, quiet = true).also { _state.update { s -> s.copy(busy = false) } }
                else -> _state.update { it.copy(busy = false, message = MailHomeViewModel.code(r)) }
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
