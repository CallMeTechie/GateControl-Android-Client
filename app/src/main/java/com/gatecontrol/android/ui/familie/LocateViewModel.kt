package com.gatecontrol.android.ui.familie

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieGeocoder
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.LocateRequest
import com.gatecontrol.android.familie.errorCode
import com.gatecontrol.android.familie.push.FamiliePushEvent
import com.gatecontrol.android.familie.valueOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Kind orten (mockup AppLocateParent): asks one child's phone for its
 * location once, follows the request until it is answered, declined,
 * failed or expired (push + polling), shows the result and the history.
 * Also the parent's view of an SOS.
 */
@HiltViewModel
class LocateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: FamilieApi,
    private val repository: FamilieRepository,
) : ViewModel() {

    val profile: StateFlow<FamilieProfile?> = repository.profile

    private val _current = MutableStateFlow<LocateRequest?>(null)
    /** The request on screen (the last one, or the one a push pointed to). */
    val current: StateFlow<LocateRequest?> = _current.asStateFlow()

    private val _history = MutableStateFlow<List<LocateRequest>?>(null)
    val history: StateFlow<List<LocateRequest>?> = _history.asStateFlow()

    private val _address = MutableStateFlow<String?>(null)
    val address: StateFlow<String?> = _address.asStateFlow()

    private val _requesting = MutableStateFlow(false)
    val requesting: StateFlow<Boolean> = _requesting.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _sosAcked = MutableStateFlow(false)
    val sosAcked: StateFlow<Boolean> = _sosAcked.asStateFlow()

    private var childId = -1
    private var poll: Job? = null
    private var addressFor: Int? = null

    fun start(childId: Int, requestId: Int?) {
        if (this.childId == childId) return
        this.childId = childId
        viewModelScope.launch {
            if (repository.profile.value == null) repository.refreshProfile()
            if (requestId != null) api.locateRequest(requestId).valueOrNull()?.let { show(it) }
            loadHistory(pickLatest = requestId == null)
        }
        viewModelScope.launch {
            repository.events.collect { e ->
                if (e is FamiliePushEvent.LocateUpdate && (e.childId == null || e.childId == childId)) {
                    val id = e.requestId ?: _current.value?.id
                    if (id != null) api.locateRequest(id).valueOrNull()?.let { show(it) }
                    loadHistory(pickLatest = false)
                }
            }
        }
    }

    private suspend fun loadHistory(pickLatest: Boolean) {
        when (val r = api.locateRequests(childId, HISTORY_LIMIT)) {
            is FamilieResult.Ok -> {
                val list = r.value.filter { it.childId == childId }.sortedByDescending { it.createdAt ?: "" }
                _history.value = list
                if (pickLatest && _current.value == null) list.firstOrNull()?.let { show(it) }
            }
            else -> {
                if (_history.value == null) _history.value = emptyList()
                _error.value = r.errorCode()
            }
        }
    }

    private fun show(request: LocateRequest) {
        _current.value = request
        if (request.status == LocateRequest.PENDING) startPolling(request.id)
        val lat = request.lat
        val lon = request.lon
        if (request.status == LocateRequest.ANSWERED && lat != null && lon != null && addressFor != request.id) {
            addressFor = request.id
            _address.value = null
            viewModelScope.launch { _address.value = FamilieGeocoder.address(context, lat, lon) }
        }
    }

    /** "Standort anfragen". */
    fun request() {
        if (_requesting.value || childId <= 0) return
        _requesting.value = true
        _error.value = null
        viewModelScope.launch {
            when (val r = api.requestLocation(childId)) {
                is FamilieResult.Ok -> {
                    val created = r.value.value
                    if (created != null) show(created) else loadHistory(pickLatest = false).also {
                        _history.value?.firstOrNull()?.let { show(it) }
                    }
                }
                is FamilieResult.Failed -> {
                    // "One open request per child": show that one instead of an error.
                    val open = r.requestId?.let { api.locateRequest(it).valueOrNull() }
                    if (open != null) show(open) else _error.value = r.code
                }
                else -> _error.value = r.errorCode()
            }
            _requesting.value = false
        }
    }

    /** While a request is pending: ask the server every few seconds (the push may be late). */
    private fun startPolling(id: Int) {
        poll?.cancel()
        poll = viewModelScope.launch {
            repeat(POLL_ROUNDS) {
                delay(POLL_MS)
                val r = api.locateRequest(id).valueOrNull() ?: return@repeat
                if (r.status != LocateRequest.PENDING) {
                    show(r)
                    loadHistory(pickLatest = false)
                    return@launch
                }
                _current.value = r
            }
        }
    }

    fun ackSos(sosId: Int) {
        viewModelScope.launch {
            val r = api.sosAck(sosId)
            if (r is FamilieResult.Ok) _sosAcked.value = true else _error.value = r.errorCode()
        }
    }

    fun clearError() {
        _error.value = null
    }

    private companion object {
        const val HISTORY_LIMIT = 30
        const val POLL_MS = 4_000L
        /** Requests expire after 10 minutes. */
        const val POLL_ROUNDS = 160
    }
}
