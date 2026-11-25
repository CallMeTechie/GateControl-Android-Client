package com.gatecontrol.android.ui.media

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.media.MediaAddProblem
import com.gatecontrol.android.media.MediaAddress
import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.MediaQuickConnectEnd
import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaServerCheck
import com.gatecontrol.android.media.MediaSetup
import com.gatecontrol.android.media.MediaShareAdded
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.jellyfin.JellyfinAuth
import com.gatecontrol.android.media.jellyfin.JellyfinServerHint
import com.gatecontrol.android.media.jellyfin.JellyfinServerInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Medien › Quellen › Quelle hinzufügen (mockup 6), one page: 1 · the kind, 2 · the server found in the Wi-Fi (or an
 * address typed), 3 · Quick Connect (the code here, confirmed in Jellyfin or by "Gerät anmelden" of another
 * GateControl) or name and password. A share: address, name and password at once.
 */
data class MediaAddUi(
    val kind: MediaSourceKind = MediaSourceKind.JELLYFIN,
    val searching: Boolean = false,
    val searched: Boolean = false,
    /** Nothing answered because this device is not in the home network (the search only works there). */
    val away: Boolean = false,
    val found: List<JellyfinServerHint> = emptyList(),
    val address: String = "",
    val checking: Boolean = false,
    val server: JellyfinServerInfo? = null,
    /** The base URL [server] answered at. */
    val serverAddress: String? = null,
    val problem: MediaAddProblem? = null,
    /** Quick Connect (code) or name and password. */
    val quickConnect: Boolean = true,
    val code: String? = null,
    val waiting: Boolean = false,
    val signingIn: Boolean = false,
    val done: Boolean = false,
    /** The new share was paired with this one (the same folder as SMB and as WebDAV). */
    val pairedWith: MediaSource? = null,
)

@HiltViewModel
class MediaAddViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val hub: MediaHub,
) : ViewModel() {
    private val setup = MediaSetup(hub)

    private val _ui = MutableStateFlow(MediaAddUi())
    val ui: StateFlow<MediaAddUi> = _ui.asStateFlow()

    private var quickJob: Job? = null

    init {
        // "Neu anmelden" of a source its server signed out: straight at its address
        val again = savedState.get<String>(ARG_SOURCE)?.let { hub.store.source(it) }?.takeIf { it.kind == MediaSourceKind.JELLYFIN }
        if (again != null) {
            _ui.value = MediaAddUi(address = again.address)
            check()
        } else {
            search()
        }
    }

    fun chooseKind(kind: MediaSourceKind) {
        if (kind == _ui.value.kind) return
        quickJob?.cancel()
        _ui.update { MediaAddUi(kind = kind, found = it.found, searched = it.searched, away = it.away) }
        if (kind == MediaSourceKind.JELLYFIN && !_ui.value.searched) search()
    }

    fun search() {
        _ui.update { it.copy(searching = true) }
        viewModelScope.launch {
            val search = setup.searchHome()
            val found = search.servers
            _ui.update { a -> a.copy(searching = false, searched = true, found = found, away = search.away, address = a.address.ifEmpty { found.firstOrNull()?.address.orEmpty() }) }
            if (found.size == 1 && _ui.value.server == null && _ui.value.kind == MediaSourceKind.JELLYFIN) check()
        }
    }

    fun setAddress(text: String) {
        quickJob?.cancel()
        _ui.update { it.copy(address = text, server = null, serverAddress = null, problem = null, code = null, waiting = false) }
    }

    fun pick(hint: JellyfinServerHint) {
        setAddress(hint.address)
        check()
    }

    /** Is a Jellyfin server at the address? Found: on to the sign-in (Quick Connect starts at once). */
    fun check() {
        val a = _ui.value
        if (MediaAddress.normalize(a.address) == null) {
            _ui.update { it.copy(problem = MediaAddProblem.BAD_ADDRESS) }
            return
        }
        _ui.update { it.copy(checking = true, problem = null) }
        viewModelScope.launch {
            when (val r = setup.check(a.address)) {
                is MediaServerCheck.Found -> {
                    _ui.update { it.copy(checking = false, server = r.server, serverAddress = r.address, problem = null) }
                    if (_ui.value.quickConnect) startQuickConnect()
                }
                is MediaServerCheck.Problem -> _ui.update { it.copy(checking = false, server = null, serverAddress = null, problem = r.problem) }
            }
        }
    }

    fun chooseQuickConnect(on: Boolean) {
        quickJob?.cancel()
        _ui.update { it.copy(quickConnect = on, code = null, waiting = false, problem = null) }
        if (on && _ui.value.serverAddress != null) startQuickConnect()
    }

    private fun startQuickConnect() {
        val base = _ui.value.serverAddress ?: return
        quickJob?.cancel()
        quickJob = viewModelScope.launch {
            val end = setup.quickConnect(
                base,
                onCode = { code -> _ui.update { it.copy(code = code, waiting = code != null) } },
                onSigningIn = { _ui.update { it.copy(signingIn = true, waiting = false) } },
            )
            when (end) {
                is MediaQuickConnectEnd.SignedIn -> saved(end.auth)
                is MediaQuickConnectEnd.Problem -> _ui.update {
                    if (end.problem == MediaAddProblem.NO_QUICK_CONNECT) {
                        it.copy(problem = end.problem, quickConnect = false, code = null, waiting = false)
                    } else {
                        it.copy(waiting = false, code = null, problem = end.problem)
                    }
                }
            }
        }
    }

    /** Name and password: sent once, never kept. */
    fun signIn(name: String, password: String) {
        val base = _ui.value.serverAddress ?: return
        if (name.isBlank() || _ui.value.signingIn) return
        _ui.update { it.copy(signingIn = true, problem = null) }
        viewModelScope.launch { saved(setup.signIn(base, name, password)) }
    }

    private fun saved(r: MediaResult<JellyfinAuth>) {
        val a = _ui.value
        val auth = r.valueOrNull()
        if (auth == null) {
            _ui.update { it.copy(signingIn = false, problem = setup.problemOf(r)) }
            return
        }
        setup.saveJellyfin(auth, a.server, a.serverAddress ?: return)
        _ui.update { it.copy(signingIn = false, done = true) }
    }

    /** "Verbinden" of a share: the root read once with the name and password, then kept (encrypted). */
    fun connectShare(name: String, password: String) {
        val a = _ui.value
        if (!a.kind.share || a.signingIn) return
        if (MediaAddress.normalizeShare(a.kind, a.address) == null) {
            _ui.update { it.copy(problem = MediaAddProblem.BAD_ADDRESS) }
            return
        }
        _ui.update { it.copy(signingIn = true, problem = null) }
        viewModelScope.launch {
            when (val r = setup.connectShare(a.kind, a.address, name, password)) {
                is MediaShareAdded.Problem -> _ui.update { it.copy(signingIn = false, problem = r.problem) }
                is MediaShareAdded.Saved -> _ui.update { it.copy(signingIn = false, done = true, pairedWith = r.partner) }
            }
        }
    }

    companion object {
        const val ARG_SOURCE = "source"
    }
}
