package com.gatecontrol.android.ui.familie

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.familie.Agenda
import com.gatecontrol.android.familie.AgendaEntry
import com.gatecontrol.android.familie.EventDraft
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.familie.errorCode
import com.gatecontrol.android.familie.valueOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** Termine: the next 60 days of Familie dates, merged with Alltag appointments when that plugin is there. */
@HiltViewModel
class DatesViewModel @Inject constructor(
    private val api: FamilieApi,
    private val repository: FamilieRepository,
    private val plugins: PluginDirectory,
) : ViewModel() {

    val profile: StateFlow<FamilieProfile?> = repository.profile

    private val _agenda = MutableStateFlow<List<AgendaEntry>?>(null)
    val agenda: StateFlow<List<AgendaEntry>?> = _agenda.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Alltag could not be loaded (its appointments are missing). */
    private val _alltagMissing = MutableStateFlow(false)
    val alltagMissing: StateFlow<Boolean> = _alltagMissing.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _busy.value = true
            val from = LocalDate.now().toString()
            val to = LocalDate.now().plusDays(Agenda.DAYS.toLong()).toString()
            val withAlltag = plugins.state.value.alltag
            val alltag = if (withAlltag) async { api.alltagEvents(from, to) } else null
            val own = api.dates(from, to)
            val alltagResult = alltag?.await()
            _alltagMissing.value = withAlltag && alltagResult !is FamilieResult.Ok
            when (own) {
                is FamilieResult.Ok -> {
                    _agenda.value = Agenda.merge(own.value, alltagResult?.valueOrNull(), from, to)
                    _error.value = null
                }
                else -> _error.value = own.errorCode()
            }
            _busy.value = false
        }
    }

    private fun act(call: suspend () -> FamilieResult<Unit>) {
        viewModelScope.launch {
            val r = call()
            if (r is FamilieResult.Ok) refresh() else _message.value = r.errorCode()
        }
    }

    fun save(id: String?, draft: EventDraft) = act { if (id == null) api.createDate(draft) else api.updateDate(id, draft) }

    fun delete(id: String) = act { api.deleteDate(id) }

    fun clearMessage() {
        _message.value = null
    }
}
