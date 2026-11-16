package com.gatecontrol.android.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.remote.RemoteApi
import com.gatecontrol.android.remote.RemoteResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the sheet knows about the tmux sessions of the chosen device. */
sealed interface TmuxState {
    data object Loading : TmuxState
    /** No tmux on the device by its settings: only the shell. */
    data object None : TmuxState
    data class Loaded(val list: TmuxList) : TmuxState
    /** First connection: no list before the host key is pinned. */
    data object Unpinned : TmuxState
    /** `tmux_unavailable`: tmux is missing on the device, connect anyway. */
    data object Missing : TmuxState
    /** `host_key_mismatch`: locked, no connect. */
    data object Locked : TmuxState
    /** The list could not be loaded ([code]); connecting with the default session stays possible. */
    data class Failed(val code: String) : TmuxState
}

data class SshSheet(
    val device: SshDevice,
    val tmux: TmuxState = TmuxState.Loading,
    val connecting: Boolean = false,
    val error: String? = null,
)

/**
 * The SSH devices of the plugin „Fernzugriff“ in the Remote segment (app contract stage 3 §1–4) and the sheet that
 * picks the tmux session and recording, then fetches the ticket and hands it to [TermSessions].
 */
@HiltViewModel
class SshDevicesViewModel @Inject constructor(
    private val api: RemoteApi,
    private val sessions: TermSessions,
    plugins: PluginDirectory,
) : ViewModel() {
    private val _devices = MutableStateFlow<List<SshDevice>>(emptyList())
    val devices: StateFlow<List<SshDevice>> = _devices.asStateFlow()

    private val _sshRight = MutableStateFlow(true)
    val sshRight: StateFlow<Boolean> = _sshRight.asStateFlow()

    /** Devices whose host key changed (this run of the app): „Gesperrt“, no connect until the admin resets it. */
    private val _locked = MutableStateFlow<Set<Long>>(emptySet())
    val locked: StateFlow<Set<Long>> = _locked.asStateFlow()

    private val _sheet = MutableStateFlow<SshSheet?>(null)
    val sheet: StateFlow<SshSheet?> = _sheet.asStateFlow()

    val running: StateFlow<TermUi?> = sessions.ui

    private val _open = Channel<Unit>(Channel.CONFLATED)
    /** A ticket is in [TermSessions]: open the terminal. */
    val openTerminal = _open.receiveAsFlow()

    private var available = false
    private var tmuxJob: Job? = null

    init {
        // a changed host key found by the terminal locks the device in the list as well
        viewModelScope.launch {
            sessions.ui.collect { u ->
                val p = u?.phase
                if (p is TermPhase.Failed && p.verdict == TermProtocol.Verdict.LOCKED) lock(u.deviceId)
            }
        }
        viewModelScope.launch {
            plugins.state.map { it.remote }.distinctUntilChanged().collect { on ->
                available = on
                if (on) load() else _devices.value = emptyList()
            }
        }
    }

    fun load() {
        if (!available) return
        viewModelScope.launch {
            when (val r = api.sshDevices()) {
                is RemoteResult.Ok -> {
                    _devices.value = r.value.devices
                    _sshRight.value = r.value.sshRight
                }
                // keep what is shown; an older plugin without SSH simply has none
                is RemoteResult.Failed, RemoteResult.Offline -> Unit
            }
        }
    }

    fun select(device: SshDevice) {
        _sheet.value = SshSheet(device, if (device.id in _locked.value) TmuxState.Locked else if (device.tmux == null) TmuxState.None else TmuxState.Loading)
        if (device.id !in _locked.value && device.tmux != null) loadTmux(device)
    }

    fun refreshTmux() {
        _sheet.value?.device?.let { if (it.tmux != null && it.id !in _locked.value) loadTmux(it) }
    }

    private fun loadTmux(device: SshDevice) {
        tmuxJob?.cancel()
        _sheet.value = _sheet.value?.copy(tmux = TmuxState.Loading)
        tmuxJob = viewModelScope.launch {
            val state = when (val r = api.tmuxSessions(device.id)) {
                is RemoteResult.Ok -> when {
                    r.value.tmux == null -> TmuxState.None
                    r.value.hostKey == "unpinned" -> TmuxState.Unpinned
                    else -> TmuxState.Loaded(r.value)
                }
                is RemoteResult.Failed -> when (r.code) {
                    "tmux_unavailable" -> TmuxState.Missing
                    "host_key_mismatch" -> {
                        lock(device.id)
                        TmuxState.Locked
                    }
                    "host_key_unpinned" -> TmuxState.Unpinned
                    else -> TmuxState.Failed(r.code)
                }
                RemoteResult.Offline -> TmuxState.Failed("network")
            }
            if (_sheet.value?.device?.id == device.id) _sheet.value = _sheet.value?.copy(tmux = state)
        }
    }

    fun lock(deviceId: Long) {
        _locked.value = _locked.value + deviceId
    }

    /** After the recording hint (when one is due): the ticket, then the terminal. */
    fun connect(choice: TmuxChoice?, record: Boolean) {
        val s = _sheet.value ?: return
        if (s.connecting || s.tmux == TmuxState.Locked) return
        _sheet.value = s.copy(connecting = true, error = null)
        viewModelScope.launch {
            when (val r = api.termTicket(s.device.id, choice, s.device.recording, record)) {
                is RemoteResult.Ok -> {
                    sessions.start(r.value, s.device.name)
                    _sheet.value = null
                    _open.trySend(Unit)
                }
                is RemoteResult.Failed -> {
                    if (r.code == "host_key_mismatch") lock(s.device.id)
                    _sheet.value = _sheet.value?.copy(
                        connecting = false, error = r.code,
                        tmux = if (r.code == "host_key_mismatch") TmuxState.Locked else _sheet.value?.tmux ?: TmuxState.None,
                    )
                }
                RemoteResult.Offline -> _sheet.value = _sheet.value?.copy(connecting = false, error = "network")
            }
        }
    }

    fun dismiss() {
        tmuxJob?.cancel()
        _sheet.value = null
    }
}
