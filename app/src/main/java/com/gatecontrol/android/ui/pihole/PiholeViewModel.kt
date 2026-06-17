package com.gatecontrol.android.ui.pihole

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.data.PiholePauseStore
import com.gatecontrol.android.di.NowMillis
import com.gatecontrol.android.network.PiholeBlocking
import com.gatecontrol.android.network.PiholeHistoryPoint
import com.gatecontrol.android.network.PiholeRepository
import com.gatecontrol.android.network.PiholeSummary
import com.gatecontrol.android.network.PiholeTopClient
import com.gatecontrol.android.network.PiholeTopDomain
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PiholeUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val summary: PiholeSummary? = null,
    val history: List<PiholeHistoryPoint> = emptyList(),
    val topDomains: List<PiholeTopDomain> = emptyList(),
    val topClients: List<PiholeTopClient> = emptyList(),
    val queryTypes: Map<String, Long> = emptyMap(),
    val canControl: Boolean = false,
    val actionPending: Boolean = false,
    val error: String? = null,
    // --- pause model ---
    val pauseEndAtMillis: Long? = null,   // epoch ms; null = not paused OR permanent
    val pausePermanent: Boolean = false,
    val pausedPresetSec: Int? = null,     // 30 | 300 | 1800 ; null = permanent/unknown
    val everLoaded: Boolean = false,
    // --- reconciliation against server lag ---
    val pendingIntent: String? = null,    // "disabled" | "enabled" | null
    val intentAtMillis: Long? = null,
    val pendingFromAction: Boolean = false, // true: in-session POST backs this intent; false: restored/external
    val postGraceMisses: Int = 0,
)

@HiltViewModel
class PiholeViewModel @Inject constructor(
    private val piholeRepository: PiholeRepository,
    private val licenseRepository: LicenseRepository,
    private val pauseStore: PiholePauseStore,
    @NowMillis private val nowMillis: () -> Long,
) : ViewModel() {

    companion object {
        /**
         * Grace window for optimistic reconciliation. Invariant: MUST exceed the real server
         * REFLECTION LATENCY (not necessarily the poll interval) — a stale poll only occurs inside
         * the latency window right after an action; later polls read the correct state and confirm,
         * where the grace value is irrelevant. Server read-back race fixed in gatecontrol v1.83.1
         * (PR #135): latency ~5s, so 15s ≈ 3× margin. WATCH: if server latency stays > 15s
         * (regression / slow Pi-hole / cellular tail), two consecutive contradicting polls raise a
         * false "blocking_failed" (the 2-miss tolerance) — raise this if that latency is observed.
         */
        const val RECONCILE_GRACE_MS = 15_000L
        private const val DRIFT_THRESHOLD_MS = 2_000L
        private const val PRESET_TOLERANCE_SEC = 10
    }

    private val _uiState = MutableStateFlow(PiholeUiState())
    val uiState: StateFlow<PiholeUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(canControl = licenseRepository.hasFeature("piholeControl")) }
        viewModelScope.launch {
            restoreFromStore()
            refresh()
        }
    }

    /** Reads persisted pause state and reinstates it with a FRESH grace window (so a lagging
     *  first poll cannot wipe it). MUST complete before the first reconciling refresh(). */
    private suspend fun restoreFromStore() {
        val saved = pauseStore.load() ?: return
        val now = nowMillis()
        val savedEnd = saved.endAtMillis
        when {
            saved.permanent -> _uiState.update {
                it.copy(
                    pausePermanent = true, pauseEndAtMillis = null, pausedPresetSec = null,
                    pendingIntent = "disabled", intentAtMillis = now,
                    pendingFromAction = false, postGraceMisses = 0,
                )
            }
            savedEnd != null && savedEnd > now -> _uiState.update {
                it.copy(
                    pauseEndAtMillis = savedEnd, pausePermanent = false,
                    pausedPresetSec = saved.presetSec,
                    pendingIntent = "disabled", intentAtMillis = now,
                    pendingFromAction = false, postGraceMisses = 0,
                )
            }
            else -> pauseStore.clear()
        }
    }

    /** Routine refresh — fetches data and reconciles blocking state against optimistic intent. */
    fun refresh() {
        viewModelScope.launch {
            if (!_uiState.value.actionPending) {
                _uiState.update { it.copy(isLoading = it.summary == null && !it.everLoaded) }
            }
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                val summary = piholeRepository.getSummary()
                val history = piholeRepository.getHistory()
                val topDomains = piholeRepository.getTopDomains()
                val topClients = piholeRepository.getTopClients()
                val queryTypes = piholeRepository.getQueryTypes()
                val canControl = licenseRepository.hasFeature("piholeControl")
                val before = _uiState.value
                // NOTE: error is intentionally NOT reset here — see refresh()/error ownership in the
                // plan's Global Constraints. Routine polls must not wipe a pending action error.
                val merged = before.copy(
                    isLoading = false,
                    summary = summary ?: before.summary,
                    everLoaded = before.everLoaded || summary != null,
                    history = history,
                    topDomains = topDomains,
                    topClients = topClients,
                    queryTypes = queryTypes,
                    canControl = canControl,
                )
                val reconciled = reconcile(merged, summary?.blocking)
                _uiState.value = reconciled
                if (pauseKey(before) != pauseKey(reconciled)) persistPause(reconciled)
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun pauseBlocking(timerSec: Int?) {
        val now = nowMillis()
        val end = if (timerSec != null) now + timerSec * 1000L else null
        _uiState.update {
            it.copy(
                pauseEndAtMillis = end,
                pausePermanent = timerSec == null,
                pausedPresetSec = timerSec,
                pendingIntent = "disabled",
                intentAtMillis = now,
                pendingFromAction = true,
                postGraceMisses = 0,
                actionPending = true,
                error = null,
            )
        }
        viewModelScope.launch {
            pauseStore.save(end, timerSec, permanent = timerSec == null)
            val ok = piholeRepository.setBlocking(false, timerSec)
            if (!ok) {
                _uiState.update {
                    it.copy(
                        pauseEndAtMillis = null, pausePermanent = false, pausedPresetSec = null,
                        pendingIntent = null, intentAtMillis = null, pendingFromAction = false,
                        postGraceMisses = 0, actionPending = false, error = "blocking_failed",
                    )
                }
                pauseStore.clear()
            } else {
                _uiState.update { it.copy(actionPending = false) }
            }
        }
    }

    fun resumeBlocking() {
        val now = nowMillis()
        val prev = _uiState.value
        _uiState.update {
            it.copy(
                pauseEndAtMillis = null, pausePermanent = false, pausedPresetSec = null,
                pendingIntent = "enabled", intentAtMillis = now,
                pendingFromAction = true, postGraceMisses = 0,
                actionPending = true, error = null,
            )
        }
        viewModelScope.launch {
            pauseStore.clear()
            val ok = piholeRepository.setBlocking(true, null)
            if (!ok) {
                _uiState.update {
                    it.copy(
                        pauseEndAtMillis = prev.pauseEndAtMillis,
                        pausePermanent = prev.pausePermanent,
                        pausedPresetSec = prev.pausedPresetSec,
                        pendingIntent = null, intentAtMillis = null, pendingFromAction = false,
                        postGraceMisses = 0, actionPending = false, error = "blocking_failed",
                    )
                }
                pauseStore.save(prev.pauseEndAtMillis, prev.pausedPresetSec, prev.pausePermanent)
            } else {
                _uiState.update { it.copy(actionPending = false) }
            }
        }
    }

    /** Local timer reached zero. Symmetric to resume but WITHOUT a POST (server self-re-enables
     *  on timer expiry). Idempotent: no-ops when already enabled. */
    fun onPauseExpired() {
        val s = _uiState.value
        if (s.pauseEndAtMillis == null && !s.pausePermanent) return
        val now = nowMillis()
        _uiState.update {
            it.copy(
                pauseEndAtMillis = null, pausePermanent = false, pausedPresetSec = null,
                pendingIntent = "enabled", intentAtMillis = now,
                pendingFromAction = true, postGraceMisses = 0,
            )
        }
        viewModelScope.launch { pauseStore.clear() }
        refresh()
    }

    // ---- Reconciliation (pure) ----

    private fun reconcile(state: PiholeUiState, blocking: PiholeBlocking?): PiholeUiState {
        if (blocking == null) return state // Rule 1: transient null → leave pause unchanged
        val serverState = blocking.state
        val now = nowMillis()

        if (state.pendingIntent != null) { // Rule 2: unconfirmed optimistic action / restored pause
            if (serverState == state.pendingIntent) {
                // Confirmed → server-authoritative from here; a confirmed action clears any prior error.
                val confirmed = state.copy(
                    pendingIntent = null, intentAtMillis = null, pendingFromAction = false,
                    postGraceMisses = 0, error = null,
                )
                return if (serverState == "disabled") applyServerState(confirmed, blocking, now) else confirmed
            }
            val withinGrace = state.intentAtMillis != null &&
                (now - state.intentAtMillis) < RECONCILE_GRACE_MS
            if (withinGrace) return state // ignore lagging server
            if (!state.pendingFromAction) {
                // Restored/external optimism has no in-session POST to vouch for it → the server is
                // authoritative once the grace passes; adopt it SILENTLY (no error toast).
                return trustServer(
                    state.copy(pendingIntent = null, intentAtMillis = null, postGraceMisses = 0),
                    blocking, now,
                )
            }
            val misses = state.postGraceMisses + 1
            if (misses < 2) return state.copy(postGraceMisses = misses) // tolerate one outlier
            return reconcileFailure(state, blocking, now) // 2 consecutive post-grace misses
        }

        // Rule 3: pendingIntent == null → trust server (covers external changes via web/other device)
        return trustServer(state, blocking, now)
    }

    /** Adopts the authoritative server blocking state onto the pause fields. Never sets an error. */
    private fun trustServer(state: PiholeUiState, blocking: PiholeBlocking, now: Long): PiholeUiState =
        when (blocking.state) {
            "disabled" -> applyServerState(state, blocking, now)
            "enabled" -> state.copy(
                pauseEndAtMillis = null, pausePermanent = false, pausedPresetSec = null, postGraceMisses = 0
            )
            else -> state // "partial"/"unknown" → leave as-is
        }

    /** Maps a server "disabled" state onto the pause fields, with drift + preset tolerance. */
    private fun applyServerState(state: PiholeUiState, blocking: PiholeBlocking, now: Long): PiholeUiState {
        val timer = blocking.timer
        return if (timer != null && timer > 0) {
            val newEnd = now + timer * 1000L
            val keepEnd = state.pauseEndAtMillis != null &&
                abs(newEnd - state.pauseEndAtMillis) <= DRIFT_THRESHOLD_MS
            val end = if (keepEnd) state.pauseEndAtMillis else newEnd
            val preset = state.pausedPresetSec
            val newPreset = if (preset == null || abs(timer - preset) > PRESET_TOLERANCE_SEC) null else preset
            state.copy(
                pauseEndAtMillis = end, pausePermanent = false, pausedPresetSec = newPreset, postGraceMisses = 0
            )
        } else {
            state.copy(
                pauseEndAtMillis = null, pausePermanent = true, pausedPresetSec = null, postGraceMisses = 0
            )
        }
    }

    private fun reconcileFailure(state: PiholeUiState, blocking: PiholeBlocking, now: Long): PiholeUiState {
        // Action-origin intent contradicted past grace for 2 polls → surface failure, adopt server.
        val base = state.copy(
            pendingIntent = null, intentAtMillis = null, pendingFromAction = false,
            postGraceMisses = 0, error = "blocking_failed",
        )
        return trustServer(base, blocking, now)
    }

    private fun pauseKey(s: PiholeUiState) = Triple(s.pauseEndAtMillis, s.pausedPresetSec, s.pausePermanent)

    private fun persistPause(s: PiholeUiState) {
        viewModelScope.launch {
            if (s.pauseEndAtMillis != null || s.pausePermanent) {
                pauseStore.save(s.pauseEndAtMillis, s.pausedPresetSec, s.pausePermanent)
            } else {
                pauseStore.clear()
            }
        }
    }
}
