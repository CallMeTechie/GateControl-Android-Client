package com.gatecontrol.android.ui.familie

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.emergency.EmergencyContact
import com.gatecontrol.android.familie.emergency.EmergencyMemberDraft
import com.gatecontrol.android.familie.emergency.EmergencyRepository
import com.gatecontrol.android.familie.emergency.EmergencyState
import com.gatecontrol.android.familie.errorCode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Notfall-Karte (mockup AppEmergency): the offline copy at once, then the
 * server's card; edit my own card, parents any card and the family numbers.
 */
@HiltViewModel
class EmergencyViewModel @Inject constructor(
    private val repository: FamilieRepository,
    private val emergency: EmergencyRepository,
) : ViewModel() {

    val profile: StateFlow<FamilieProfile?> = repository.profile
    val state: StateFlow<EmergencyState> = emergency.state

    private val _message = MutableStateFlow<String?>(null)
    /** Error code of the last save, shown once. */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _fieldError = MutableStateFlow<String?>(null)
    /** The field the server refused (`allergies`, `contacts.0.phone`, …), shown at that field of the open form. */
    val fieldError: StateFlow<String?> = _fieldError.asStateFlow()

    private val _saved = MutableStateFlow(0)
    /** Counts successful saves: the open form closes. */
    val saved: StateFlow<Int> = _saved.asStateFlow()

    init {
        viewModelScope.launch {
            emergency.loadCached()
            if (repository.profile.value == null) repository.cachedProfile()
        }
    }

    /** On every open / resume: the server's current card (the copy stays when offline). */
    fun refresh() {
        viewModelScope.launch {
            if (repository.profile.value == null) repository.refreshProfile()
            emergency.refresh()
        }
    }

    private fun save(call: suspend () -> FamilieResult<Unit>) {
        viewModelScope.launch {
            _saving.value = true
            _fieldError.value = null
            val r = call()
            _saving.value = false
            when {
                r is FamilieResult.Ok -> _saved.value += 1
                r is FamilieResult.Failed && r.field != null -> _fieldError.value = r.field
                else -> _message.value = r.errorCode()
            }
        }
    }

    fun saveMember(userId: Int, draft: EmergencyMemberDraft) = save { emergency.saveMember(userId, draft) }

    fun saveContacts(contacts: List<EmergencyContact>) = save { emergency.saveContacts(contacts) }

    fun clearFieldError() {
        _fieldError.value = null
    }

    fun clearMessage() {
        _message.value = null
    }
}
