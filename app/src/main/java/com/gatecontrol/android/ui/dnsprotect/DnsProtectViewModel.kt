package com.gatecontrol.android.ui.dnsprotect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.dnsprotect.DnsDuration
import com.gatecontrol.android.dnsprotect.DnsFailure
import com.gatecontrol.android.dnsprotect.DnsLogic
import com.gatecontrol.android.dnsprotect.DnsPeople
import com.gatecontrol.android.dnsprotect.DnsRepository
import com.gatecontrol.android.dnsprotect.DnsResult
import com.gatecontrol.android.dnsprotect.DnsSnapshot
import com.gatecontrol.android.dnsprotect.DnsTarget
import com.gatecontrol.android.di.NowMillis
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the screen says besides the state. */
sealed interface DnsNotice {
    /** No answer: the last known state stays. */
    data object Offline : DnsNotice
    /** The plugin or the filter service does not answer right now. */
    data object Busy : DnsNotice
    /** The server refused a change; [text] its ready sentence (null: a general one). */
    data class Refused(val text: String?) : DnsNotice
}

data class DnsUiState(
    /** `permissions.vpnDns.state` — enough for the start tile before the first status. */
    val coarse: String? = null,
    val snapshot: DnsSnapshot? = null,
    /** A first answer or refusal is in. */
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    /** A change is on its way. */
    val working: Boolean = false,
    /** The plugin says there is no DNS protection (any more). */
    val gone: Boolean = false,
    val notice: DnsNotice? = null,
    val pollMs: Long = DnsLogic.POLL_MS,
    /** "Für andere unterbrechen": the people; null until loaded. */
    val people: DnsPeople? = null,
    val peopleLoading: Boolean = false,
    val peopleFailed: Boolean = false,
)

/**
 * "DNS-Schutz": the state of the server's DNS filter for this device,
 * suspending it for a while and switching it back on; for administrators
 * and parents also for others. Shared by the start tile and the screen in
 * the Netzwerk tab (activity scope). There are no live events: the screen
 * asks while it is visible.
 */
@HiltViewModel
class DnsProtectViewModel @Inject constructor(
    private val repository: DnsRepository,
    private val licenseRepository: LicenseRepository,
    @NowMillis private val nowMillis: () -> Long,
) : ViewModel() {

    private val _ui = MutableStateFlow(DnsUiState(snapshot = repository.snapshot.value))
    val ui: StateFlow<DnsUiState> = _ui.asStateFlow()

    private var refreshJob: Job? = null

    init {
        viewModelScope.launch { repository.snapshot.collect { s -> _ui.update { it.copy(snapshot = s) } } }
        viewModelScope.launch {
            var last: LicenseRepository.VpnDns? = null
            licenseRepository.permissions.collect { p ->
                val dns = p.vpnDns
                _ui.update { it.copy(coarse = dns?.state) }
                // The server (newly) reports a DNS protection: its state for the tile.
                if (dns != null && dns.plugin != last?.plugin) _ui.update { it.copy(gone = false) }
                // … or its filter changed (down, back): what that means for this device.
                if (dns != null && dns != last && !_ui.value.gone) refresh()
                last = dns
            }
        }
    }

    /** `GET status`; [byUser] shows the pull-to-refresh indicator. */
    fun refresh(byUser: Boolean = false) {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            if (byUser) _ui.update { it.copy(refreshing = true) }
            took(repository.refresh(nowMillis()), fromAction = false)
            _ui.update { it.copy(refreshing = false, loaded = true) }
        }
    }

    fun pause(duration: DnsDuration, target: DnsTarget = DnsTarget.Own) = change(target) { repository.pause(duration, target, nowMillis()) }

    fun resume(target: DnsTarget = DnsTarget.Own) = change(target) { repository.resume(target, nowMillis()) }

    /** `GET people` for "Für andere unterbrechen". */
    fun loadPeople() {
        if (_ui.value.peopleLoading) return
        viewModelScope.launch {
            _ui.update { it.copy(peopleLoading = true, peopleFailed = false) }
            when (val r = repository.people()) {
                is DnsResult.Ok -> _ui.update { it.copy(people = r.value, peopleLoading = false) }
                else -> _ui.update { it.copy(peopleLoading = false, peopleFailed = true) }
            }
        }
    }

    fun dismissNotice() = _ui.update { it.copy(notice = null) }

    private fun change(target: DnsTarget, call: suspend () -> DnsResult<DnsSnapshot>) {
        if (_ui.value.working) return
        _ui.update { it.copy(working = true, notice = null) }
        viewModelScope.launch {
            val r = call()
            took(r, fromAction = true)
            _ui.update { it.copy(working = false) }
            // A change for somebody else: their rows show it. A refusal: what holds now.
            if (target != DnsTarget.Own && _ui.value.people != null) loadPeople()
            if (r is DnsResult.Failed && !_ui.value.gone) took(repository.refresh(nowMillis()), fromAction = false, keepNotice = true)
        }
    }

    private suspend fun took(r: DnsResult<DnsSnapshot>, fromAction: Boolean, keepNotice: Boolean = false) {
        when (r) {
            is DnsResult.Ok -> _ui.update {
                it.copy(
                    gone = false,
                    pollMs = DnsLogic.POLL_MS,
                    // A refusal stays readable until the next change; "offline" ends with an answer.
                    notice = it.notice.takeIf { n -> n is DnsNotice.Refused && (keepNotice || !fromAction) },
                )
            }
            DnsResult.Offline -> if (!keepNotice) _ui.update { it.copy(notice = DnsNotice.Offline) }
            DnsResult.Unavailable -> _ui.update { it.copy(gone = true) }
            is DnsResult.Failed -> when (DnsLogic.failure(r)) {
                DnsFailure.GONE -> {
                    _ui.update { it.copy(gone = true, notice = null) }
                    repository.reloadPermissions()
                }
                DnsFailure.BUSY -> if (!keepNotice) _ui.update { it.copy(notice = DnsNotice.Busy) }
                DnsFailure.RATE -> _ui.update { it.copy(pollMs = DnsLogic.POLL_SLOW_MS) }
                DnsFailure.STALE -> Unit
                DnsFailure.REFUSED -> if (fromAction) _ui.update { it.copy(notice = DnsNotice.Refused(r.message)) }
            }
        }
    }
}
