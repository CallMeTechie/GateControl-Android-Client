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

/** What the person ends on the device (questions T2–T4 of the design, always asked first). */
sealed interface TmuxKill {
    data class Session(val session: TmuxSession) : TmuxKill
    data class Window(val session: TmuxSession, val window: TmuxWindow) : TmuxKill
    data class Detached(val sessions: List<TmuxSession>) : TmuxKill
}

/** [killing]: ending sessions/windows runs; [killError]: why it failed (shown above the list, the list stays). */
data class SshSheet(
    val device: SshDevice,
    val tmux: TmuxState = TmuxState.Loading,
    val connecting: Boolean = false,
    val error: String? = null,
    val killing: Boolean = false,
    val killError: String? = null,
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

    /** [quiet]: keep the list on screen while it loads again (after `tmux_gone`/`tmux_changed`: no spinner, no error). */
    private fun loadTmux(device: SshDevice, quiet: Boolean = false) {
        tmuxJob?.cancel()
        if (!quiet) _sheet.value = _sheet.value?.copy(tmux = TmuxState.Loading)
        tmuxJob = viewModelScope.launch {
            val state = when (val r = api.tmuxSessions(device.id)) {
                is RemoteResult.Ok -> stateOf(r.value)
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

    private fun stateOf(list: TmuxList): TmuxState = when {
        list.tmux == null -> TmuxState.None
        list.hostKey == "unpinned" -> TmuxState.Unpinned
        else -> TmuxState.Loaded(list)
    }

    /**
     * Ends a session, closes a window or ends the chosen detached sessions — after the person's answer to the
     * question. The answer carries the new list; without one, or after `tmux_gone`/`tmux_changed` (gone or replaced
     * meanwhile: nothing ended), the list loads again without an error. The server checks the right (`mayUse`).
     */
    fun kill(k: TmuxKill) {
        val s = _sheet.value ?: return
        if (s.killing || s.connecting || (s.tmux as? TmuxState.Loaded)?.list?.kill != true) return
        val device = s.device
        _sheet.value = s.copy(killing = true, killError = null)
        tmuxJob?.cancel()
        viewModelScope.launch {
            val r = when (k) {
                is TmuxKill.Session -> api.tmuxKill(device.id, k.session)
                is TmuxKill.Window -> api.tmuxKill(device.id, k.session, k.window)
                is TmuxKill.Detached -> api.tmuxKillDetached(device.id, k.sessions)
            }
            if (_sheet.value?.device?.id != device.id) return@launch
            when (r) {
                is RemoteResult.Ok -> {
                    val list = r.value.list
                    _sheet.value = _sheet.value?.copy(killing = false, tmux = list?.let { stateOf(it) } ?: _sheet.value!!.tmux)
                    if (list == null) loadTmux(device, quiet = true)
                }
                is RemoteResult.Failed -> {
                    val silent = r.code == "tmux_gone" || r.code == "tmux_changed"
                    if (r.code == "host_key_mismatch") lock(device.id)
                    _sheet.value = _sheet.value?.copy(
                        killing = false,
                        killError = if (silent) null else r.code,
                        tmux = if (r.code == "host_key_mismatch") TmuxState.Locked else _sheet.value!!.tmux,
                    )
                    if (r.code != "host_key_mismatch") loadTmux(device, quiet = true)
                }
                RemoteResult.Offline -> _sheet.value = _sheet.value?.copy(killing = false, killError = "network")
            }
        }
    }

    fun lock(deviceId: Long) {
        _locked.value = _locked.value + deviceId
    }

    /** After the recording hint (when one is due): the ticket, then the terminal; [window]: „Fenster öffnen“. */
    fun connect(choice: TmuxChoice?, record: Boolean, window: Int? = null) {
        val s = _sheet.value ?: return
        if (s.connecting || s.killing || s.tmux == TmuxState.Locked) return
        _sheet.value = s.copy(connecting = true, error = null)
        viewModelScope.launch {
            when (val r = api.termTicket(s.device.id, choice, s.device.recording, record)) {
                is RemoteResult.Ok -> {
                    sessions.start(r.value, s.device.name, window.takeIf { choice is TmuxChoice.Attach })
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
