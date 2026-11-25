package com.gatecontrol.android.ui.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.MediaPhoneSettings
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.offline.MediaOffline
import com.gatecontrol.android.media.offline.OfflineQuality
import com.gatecontrol.android.media.server.MediaServerSources
import com.gatecontrol.android.media.server.MediaServerState
import com.gatecontrol.android.media.server.MediaSourceStatus
import com.gatecontrol.android.media.server.MediaSourcesLogic
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Medien › Quellen (mockup „Quellen (Anzeige)“): the sources the admin set up in the server, each with its status
 * (signed in as …, at home / away through the VPN / away direct / blocked, the error in plain words), when the list
 * came from the server, „Neu laden“; and what concerns only this phone („Auf diesem Gerät“): „Immer Original“, the mobile
 * data limit, the downloads for offline (#295), „Weiterschauen auf Start“. Nothing is added, signed in or removed here.
 */
@HiltViewModel
class MediaSourcesViewModel @Inject constructor(
    private val hub: MediaHub,
    private val settings: MediaPhoneSettings,
    private val offline: MediaOffline,
    private val servers: MediaServerSources,
) : ViewModel() {

    val sources: StateFlow<List<MediaSource>> = hub.store.sources
    val server: StateFlow<MediaServerState> = servers.state
    val mobileCap: StateFlow<Long?> = settings.mobileCap
    val startCard: StateFlow<Boolean> = settings.startCard
    val offlineQuality: StateFlow<OfflineQuality> = settings.offlineQuality
    val offlineLimitGb: StateFlow<Int> = settings.offlineLimitGb

    val rows: StateFlow<List<MediaSourceStatus>> = combine(hub.store.sources, servers.state, hub.link) { list, state, _ ->
        MediaSourcesLogic.rows(list, state, hub::route)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MediaSourcesLogic.rows(hub.store.sources.value, servers.state.value, hub::route))

    /** Bytes the downloads take now. */
    val offlineUsed: StateFlow<Long> = offline.items.map { list -> list.sumOf { if (it.ready) it.size else it.loaded } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun setOfflineQuality(q: OfflineQuality) = settings.setOfflineQuality(q)

    /** A new storage limit; waiting downloads that stopped at the old one try again. */
    fun setOfflineLimitGb(gb: Int) {
        settings.setOfflineLimitGb(gb)
        offline.items.value.filter { it.failure == com.gatecontrol.android.media.offline.OfflineFailure.LIMIT }.forEach { offline.retry(it.id) }
    }

    fun setAlwaysOriginal(source: MediaSource, on: Boolean) = hub.store.setAlwaysOriginal(source.id, on)

    fun setMobileCap(bps: Long?) = settings.setMobileCap(bps)

    fun setStartCard(on: Boolean) = settings.setStartCard(on)

    /** „Neu laden“: the list of the server, the way, then the sign-in where it is missing. */
    fun reload() {
        viewModelScope.launch {
            servers.refresh(force = true)
            servers.repair()
        }
    }

    fun onShown() {
        servers.refreshSoon()
        viewModelScope.launch { hub.check() }
    }
}
