package com.gatecontrol.android.ui.familie

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.LocateRequest
import com.gatecontrol.android.familie.SosState
import com.gatecontrol.android.familie.errorCode
import com.gatecontrol.android.familie.locate.AnswerOutcome
import com.gatecontrol.android.familie.locate.LocateAnswerer
import com.gatecontrol.android.familie.push.FamiliePushEvent
import com.gatecontrol.android.location.DeviceLocator
import com.gatecontrol.android.location.LocateResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/** "Bin angekommen" that went out: label and when. */
data class ArrivalSent(val label: String?, val atMs: Long)

/** One-shot feedback of the child screen. */
sealed interface ChildNotice {
    data class LocationSent(val accuracyM: Int) : ChildNotice
    data object Declined : ChildNotice
    data class Error(val code: String) : ChildNotice
}

/**
 * Kinder-Handy (mockup AppLocateChild): who located me, open requests to
 * confirm, "Bin angekommen", SOS and the parent's acknowledgement.
 */
@HiltViewModel
class ChildViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: FamilieApi,
    private val repository: FamilieRepository,
    private val answerer: LocateAnswerer,
) : ViewModel() {

    val profile: StateFlow<FamilieProfile?> = repository.profile
    val sos: StateFlow<SosState?> = repository.sos

    private val _requests = MutableStateFlow<List<LocateRequest>?>(null)
    val requests: StateFlow<List<LocateRequest>?> = _requests.asStateFlow()

    private val _arrival = MutableStateFlow<ArrivalSent?>(null)
    val arrival: StateFlow<ArrivalSent?> = _arrival.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    /** What is being sent right now: "arrival", "sos", "locate:<id>". */
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _notice = MutableStateFlow<ChildNotice?>(null)
    val notice: StateFlow<ChildNotice?> = _notice.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            repository.events.collect { if (it is FamiliePushEvent.LocateRequest) refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            if (repository.profile.value == null) repository.refreshProfile()
            when (val r = api.locateRequests(null, 30)) {
                is FamilieResult.Ok -> _requests.value = r.value.sortedByDescending { it.createdAt ?: "" }
                else -> if (_requests.value == null) _requests.value = emptyList()
            }
        }
    }

    /** Confirm mode: send my location for this request (the screen is visible). */
    fun send(requestId: Int) {
        if (_busy.value != null) return
        _busy.value = "locate:$requestId"
        viewModelScope.launch {
            _notice.value = when (val o = answerer.answer(requestId, appVisible = true)) {
                is AnswerOutcome.Sent -> ChildNotice.LocationSent(o.accuracyM)
                is AnswerOutcome.Failed -> ChildNotice.Error(o.code.wire)
                is AnswerOutcome.NotDelivered -> ChildNotice.Error(o.code)
                AnswerOutcome.Busy -> null
            }
            _busy.value = null
            refresh()
        }
    }

    fun decline(requestId: Int) {
        viewModelScope.launch {
            val r = api.decline(requestId)
            _notice.value = if (r is FamilieResult.Ok) ChildNotice.Declined else ChildNotice.Error(r.errorCode() ?: "failed")
            refresh()
        }
    }

    /** A quick position for arrival / SOS, never required. */
    private suspend fun quickFix(): Triple<Double, Double, Double?>? {
        if (!DeviceLocator.hasPermission(context) || !DeviceLocator.isLocationOn(context)) return null
        val r = DeviceLocator.locate(context, timeoutMs = QUICK_FIX_MS, staleMs = 2 * 60_000L) as? LocateResult.Fix ?: return null
        val l = r.location
        return Triple(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy.toDouble() else null)
    }

    fun arrived(label: String?) {
        if (_busy.value != null) return
        _busy.value = "arrival"
        viewModelScope.launch {
            val fix = quickFix()
            val r = api.arrival(label, fix?.first, fix?.second, fix?.third)
            if (r is FamilieResult.Ok) _arrival.value = ArrivalSent(label, System.currentTimeMillis())
            else _notice.value = ChildNotice.Error(r.errorCode() ?: "failed")
            _busy.value = null
        }
    }

    /** SOS (after the 2-second hold): to all parents, critical, with the position if there is one. */
    fun sos() {
        if (_busy.value == "sos") return
        _busy.value = "sos"
        viewModelScope.launch {
            val fix = quickFix()
            val at = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
            when (val r = api.sos(fix?.first, fix?.second, fix?.third, at)) {
                is FamilieResult.Ok -> repository.sosSent(r.value.value, System.currentTimeMillis())
                else -> _notice.value = ChildNotice.Error(r.errorCode() ?: "failed")
            }
            _busy.value = null
        }
    }

    fun clearSos() = repository.clearSos()

    fun clearNotice() {
        _notice.value = null
    }

    private companion object {
        const val QUICK_FIX_MS = 10_000L
    }
}
