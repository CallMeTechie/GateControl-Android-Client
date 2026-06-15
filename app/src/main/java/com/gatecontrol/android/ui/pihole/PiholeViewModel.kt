package com.gatecontrol.android.ui.pihole

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.network.PiholeRepository
import com.gatecontrol.android.network.PiholeHistoryPoint
import com.gatecontrol.android.network.PiholeSummary
import com.gatecontrol.android.network.PiholeTopClient
import com.gatecontrol.android.network.PiholeTopDomain
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PiholeUiState(
    val isLoading: Boolean = false,
    val summary: PiholeSummary? = null,
    val history: List<PiholeHistoryPoint> = emptyList(),
    val topDomains: List<PiholeTopDomain> = emptyList(),
    val topClients: List<PiholeTopClient> = emptyList(),
    val queryTypes: Map<String, Long> = emptyMap(),
    val canControl: Boolean = false,
    val actionPending: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class PiholeViewModel @Inject constructor(
    private val piholeRepository: PiholeRepository,
    private val licenseRepository: LicenseRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PiholeUiState())
    val uiState: StateFlow<PiholeUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(canControl = licenseRepository.hasFeature("piholeControl")) }
        refresh()
    }

    /** Routine refresh — does NOT overwrite an optimistic pending state. */
    fun refresh() {
        viewModelScope.launch {
            if (!_uiState.value.actionPending) {
                _uiState.update { it.copy(isLoading = it.summary == null) }
            }
            val summary = piholeRepository.getSummary()
            // While an action is pending, only the confirmation path updates state.
            if (_uiState.value.actionPending) return@launch
            val history = piholeRepository.getHistory()
            val topDomains = piholeRepository.getTopDomains()
            val topClients = piholeRepository.getTopClients()
            val queryTypes = piholeRepository.getQueryTypes()
            val canControl = licenseRepository.hasFeature("piholeControl")
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = null,
                    summary = summary,
                    history = history,
                    topDomains = topDomains,
                    topClients = topClients,
                    queryTypes = queryTypes,
                    canControl = canControl,
                )
            }
        }
    }

    fun pauseBlocking(timerSec: Int?) = applyBlocking(enabled = false, timerSec = timerSec, expected = "disabled")
    fun resumeBlocking() = applyBlocking(enabled = true, timerSec = null, expected = "enabled")

    /** Optimistic with confirmation: keep "pending" until a read shows the expected state. */
    private fun applyBlocking(enabled: Boolean, timerSec: Int?, expected: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(actionPending = true, error = null) }
            val ok = piholeRepository.setBlocking(enabled, timerSec)
            if (!ok) {
                _uiState.update { it.copy(actionPending = false, error = "blocking_failed") }
                return@launch
            }
            // Bounded confirmation polling (server cache is ~30s + async resync).
            repeat(12) { attempt ->
                val s = piholeRepository.getSummary()
                if (s?.blocking?.state == expected) {
                    _uiState.update { it.copy(actionPending = false, summary = s) }
                    refresh()
                    return@launch
                }
                if (attempt < 11) kotlinx.coroutines.delay(5_000)
            }
            // Give up waiting but reflect latest known state; clear pending.
            _uiState.update { it.copy(actionPending = false) }
            refresh()
        }
    }
}
