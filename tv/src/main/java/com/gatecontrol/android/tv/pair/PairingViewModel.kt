package com.gatecontrol.android.tv.pair

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.tv.TvSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Runs the pairing ([PairingMachine]): for each state the one call it needs (start, poll after the interval, enroll),
 * its answer goes back into the machine. A new state cancels the call of the old one (collectLatest), so a changed
 * server or a code that ran out never mixes with an older answer.
 */
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val api: TvPairingApi,
    private val settings: TvSettings,
    private val setupRepository: SetupRepository,
) : ViewModel() {

    private val now: () -> Long = System::currentTimeMillis

    private val _state = MutableStateFlow(initial())
    val state: StateFlow<PairState> = _state.asStateFlow()

    private val _pairedNow = MutableStateFlow(false)

    /** Paired in this run (not at the start of the app): the activity asks for the VPN consent and connects. */
    val pairedNow: StateFlow<Boolean> = _pairedNow.asStateFlow()

    /** The pairing screen is on the TV (activity started): only then codes are fetched and polled. */
    private val visible = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            combine(_state, visible) { s, v -> s to v }.collectLatest { (s, v) ->
                // In the background nothing is fetched or polled (a film in another app for hours); back on the
                // screen a code that ran out is replaced. A setup code being redeemed is finished either way.
                if (!v && s !is PairState.Enrolling) return@collectLatest
                when (s) {
                    is PairState.Starting -> {
                        settings.pairServer = s.server
                        dispatch(api.start(s.server, now))
                    }
                    is PairState.Waiting -> {
                        delay((minOf(s.nextPollAt, s.code.expiresAt) - now()).coerceAtLeast(0))
                        dispatch(if (now() >= s.code.expiresAt) PairEvent.Tick else api.poll(s.server, s.code))
                    }
                    is PairState.Enrolling -> {
                        settings.pairServer = s.server
                        val result = api.enroll(s.server, s.setupCode, s.name)
                        if (result == PairEvent.Enrolled) _pairedNow.value = true
                        dispatch(result)
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun initial(): PairState =
        PairingMachine.initial(paired = setupRepository.isConfigured() && setupRepository.hasWireGuardConfig(), server = settings.pairServer)

    fun setVisible(on: Boolean) {
        visible.value = on
    }

    fun dispatch(event: PairEvent) {
        _state.update { PairingMachine.reduce(it, event, now()) }
    }

    fun consumePairedNow() {
        _pairedNow.value = false
    }

    /** After "Kopplung aufheben": back to the start (a new code for the server typed in earlier). */
    fun reset() {
        _pairedNow.value = false
        _state.value = initial()
    }
}
