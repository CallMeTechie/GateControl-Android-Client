package com.gatecontrol.android.ui.mail.calendar

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.mail.MCalendars
import com.gatecontrol.android.mail.MEvent
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

data class EventState(
    val event: MEvent? = null,
    val loading: Boolean = true,
    val error: String? = null,
    /** An answer or a delete on its way. */
    val busy: Boolean = false,
    val message: String? = null,
    /** Deleted: the screen closes. */
    val gone: Boolean = false,
)

/** One event (contract D3 "Termin ansehen"): load it, answer (with a comment), delete (this one or the series). */
@HiltViewModel
class MailEventViewModel @Inject constructor(
    private val repository: MailRepository,
    saved: SavedStateHandle,
) : ViewModel() {

    private val pim = repository.pim
    private val api = repository.api
    val me: StateFlow<MMe?> = repository.me
    val calendars: StateFlow<MCalendars?> = pim.calendars
    val offline: StateFlow<Boolean> = pim.offline
    val zone: ZoneId = ZoneId.systemDefault()

    val id: Long = saved.get<String>(Screen.MailEvent.ARG_ID)?.toLongOrNull() ?: 0L
    val occurrence: String? = saved.get<String>(Screen.MailEvent.ARG_OCC)?.takeIf { it.isNotBlank() }

    private val _state = MutableStateFlow(EventState())
    val state: StateFlow<EventState> = _state.asStateFlow()

    init {
        if (pim.calendars.value == null) pim.refreshCalendarsSoon()
        load()
        viewModelScope.launch {
            pim.calChanged.collect { c -> if (c.eventId == null || c.eventId == id) load(quiet = true) }
        }
    }

    fun load(quiet: Boolean = false) {
        if (id <= 0) {
            _state.update { it.copy(loading = false, error = "not_found") }
            return
        }
        if (!quiet) _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = pim.event(id, occurrence)) {
                is MResult.Ok -> _state.update { it.copy(event = r.value, loading = false, error = null) }
                else -> _state.update { it.copy(loading = false, error = MailHomeViewModel.code(r)) }
            }
        }
    }

    /** Zusagen / Mit Vorbehalt / Absagen (with a comment; the occurrence of a series only this one). */
    fun respond(response: String, comment: String?) {
        val ev = _state.value.event ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val r = api.respondEvent(ev.id, MailPimJson.respondBody(response, comment, occurrence = ev.occ.occurrence))) {
                is MResult.Ok -> _state.update { it.copy(event = r.value, busy = false, message = "cal_answered") }
                else -> _state.update { it.copy(busy = false, message = MailHomeViewModel.code(r)) }
            }
        }
    }

    /** Delete this occurrence or the whole series ([notify]: the attendees hear of it / the organizer gets a no). */
    fun delete(series: Boolean, notify: Boolean, comment: String?) {
        val ev = _state.value.event ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val scope = if (series || ev.occ.occurrence == null) "series" else "occurrence"
            when (val r = api.deleteEvent(ev.id, scope, ev.occ.occurrence.takeIf { scope == "occurrence" }, notify, comment)) {
                is MResult.Ok -> _state.update { it.copy(busy = false, gone = true) }
                else -> _state.update { it.copy(busy = false, message = MailHomeViewModel.code(r)) }
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
