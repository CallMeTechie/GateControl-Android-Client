package com.gatecontrol.android.ui.gatedrop

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.gatedrop.GateDropLogic
import com.gatecontrol.android.gatedrop.GateDropNotifier
import com.gatecontrol.android.gatedrop.GateDropReceiveWorker
import com.gatecontrol.android.gatedrop.GateDropRepository
import com.gatecontrol.android.gatedrop.GdEntry
import com.gatecontrol.android.gatedrop.GdInboxKind
import com.gatecontrol.android.gatedrop.GdLink
import com.gatecontrol.android.gatedrop.GdReceiveFrom
import com.gatecontrol.android.gatedrop.GdReceived
import com.gatecontrol.android.gatedrop.GdResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SharedUi(
    val tab: String = GateDropNotifier.TAB_RECEIVED,
    val loading: Boolean = false,
    /** The server could not be asked: the lists are what this device knows. */
    val offline: Boolean = false,
    val received: List<GdReceived> = emptyList(),
    val sent: List<GdEntry> = emptyList(),
    val links: List<GdLink> = emptyList(),
    val saving: Set<String> = emptySet(),
    /** Who may send to this device ([GdReceiveFrom]); null while unknown or without the setting (GateDrop < 1.2). */
    val receiveFrom: String? = null,
    /** Saving it failed: the previous value is shown again. */
    val receiveFromFailed: Boolean = false,
)

/**
 * "Geteilt" (Empfangen / Gesendet / Links): the plugin's inbox lists,
 * merged with what this device received directly and where it saved it.
 */
@HiltViewModel
class SharedViewModel @Inject constructor(
    app: Application,
    private val repository: GateDropRepository,
) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(SharedUi())
    val ui: StateFlow<SharedUi> = _ui.asStateFlow()

    private var serverReceived: List<GdEntry>? = null

    init {
        loadReceiveFrom()
        viewModelScope.launch {
            repository.store.received.collect { local ->
                _ui.update { it.copy(received = GateDropLogic.mergeReceived(serverReceived, local), saving = it.saving.filter { key -> local.firstOrNull { e -> e.key == key }?.files?.all { f -> f.uri != null } != true }.toSet()) }
            }
        }
    }

    fun select(tab: String) {
        if (tab !in TABS) return
        _ui.update { it.copy(tab = tab) }
        load()
    }

    fun load() {
        val tab = _ui.value.tab
        _ui.update { it.copy(loading = true) }
        viewModelScope.launch {
            when (tab) {
                GateDropNotifier.TAB_SENT -> {
                    val r = repository.api.inbox(GdInboxKind.SENT)
                    _ui.update { it.copy(loading = false, offline = r !is GdResult.Ok, sent = (r as? GdResult.Ok)?.value ?: it.sent) }
                }
                GateDropNotifier.TAB_LINKS -> {
                    val r = repository.api.links()
                    _ui.update { it.copy(loading = false, offline = r !is GdResult.Ok, links = (r as? GdResult.Ok)?.value ?: it.links) }
                }
                else -> {
                    val r = repository.api.inbox(GdInboxKind.RECEIVED)
                    if (r is GdResult.Ok) serverReceived = r.value
                    _ui.update { it.copy(loading = false, offline = r !is GdResult.Ok, received = GateDropLogic.mergeReceived(serverReceived, repository.store.received.value)) }
                }
            }
        }
    }

    /** This device's "who may send to it"; stays hidden when the server does not know the setting. */
    private fun loadReceiveFrom() {
        viewModelScope.launch {
            val r = repository.api.receiveFrom()
            if (r is GdResult.Ok) _ui.update { it.copy(receiveFrom = r.value) }
        }
    }

    /** "Nur ich" / "Alle im GateControl-Netz" — shown at once, back to the old value when the server refuses. */
    fun setReceiveFrom(value: String) {
        val old = _ui.value.receiveFrom ?: return
        if (GdReceiveFrom.of(value) == null || value == old) return
        _ui.update { it.copy(receiveFrom = value, receiveFromFailed = false) }
        viewModelScope.launch {
            val r = repository.api.setReceiveFrom(value)
            if (r is GdResult.Ok) _ui.update { it.copy(receiveFrom = r.value) }
            else _ui.update { it.copy(receiveFrom = old, receiveFromFailed = true) }
        }
    }

    /** Files of a send over the server → Downloads/GateControl. */
    fun save(entry: GdReceived) {
        val sendId = entry.sendId ?: return
        _ui.update { it.copy(saving = it.saving + entry.key) }
        // Known locally first, so the worker's notification has its content.
        if (repository.store.get(entry.key) == null) repository.store.upsert(entry)
        GateDropReceiveWorker.enqueueSend(getApplication(), sendId, save = true, autoSave = false, show = false)
    }

    fun opened(entry: GdReceived) {
        val sendId = entry.sendId ?: return
        viewModelScope.launch { runCatching { repository.api.opened(sendId) } }
    }

    fun endLink(token: String) {
        viewModelScope.launch {
            val r = repository.api.endLink(token)
            if (r is GdResult.Ok) {
                _ui.update { u -> u.copy(links = u.links.map { if (it.token == token) it.copy(state = GdLink.STATE_REVOKED) else it }) }
            }
            load()
        }
    }

    companion object {
        val TABS = listOf(GateDropNotifier.TAB_RECEIVED, GateDropNotifier.TAB_SENT, GateDropNotifier.TAB_LINKS)
    }
}
