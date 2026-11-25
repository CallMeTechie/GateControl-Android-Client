package com.gatecontrol.android.ui.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.media.MediaBrowse
import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.server.MediaServerSources
import com.gatecontrol.android.media.MediaPhoneSettings
import com.gatecontrol.android.media.MediaPictures
import com.gatecontrol.android.media.MediaPlayRequests
import com.gatecontrol.android.media.MediaTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The card "Weiterschauen" on Start (mockup 2): Jellyfin's and the shares' titles left half-way, while a source is set
 * up and the card is switched on (Medien › Quellen). A tap plays on, a long press opens the detail page.
 */
@HiltViewModel
class MediaStartViewModel @Inject constructor(
    private val hub: MediaHub,
    private val requests: MediaPlayRequests,
    settings: MediaPhoneSettings,
    val pictures: MediaPictures,
    servers: MediaServerSources,
) : ViewModel() {
    private val _resume = MutableStateFlow<List<MediaTitle>>(emptyList())

    private val browse: MediaBrowse = MediaBrowse(hub) { publish() }

    private fun publish() {
        _resume.value = browse.rows().resume
    }

    /** The card's titles; empty: no card. */
    val resume: StateFlow<List<MediaTitle>> = combine(_resume, settings.startCard, hub.store.sources, servers.state) { r, on, sources, server ->
        if (on && sources.isNotEmpty() && server.visible) r else emptyList()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var job: Job? = null

    /** Start came to the front. */
    fun refresh() {
        val source = hub.currentSource()?.takeIf { it.token != null }
        val shares = hub.shares()
        if (source == null && shares.isEmpty()) {
            browse.clear()
            _resume.value = emptyList()
            return
        }
        if (job?.isActive == true) return
        job = viewModelScope.launch { browse.loadResume(source, shares) }
    }

    fun play(title: MediaTitle) {
        viewModelScope.launch { requests.play(title) }
    }
}

/**
 * Whether Medien shows (the tab, a page beside Start, the settings' row): while the server's plugin „Medien“ gives an
 * app tab (the right „Medien“, a source, its own licence) and answers — no longer after a source set up on the phone.
 */
@HiltViewModel
class MediaAvailabilityViewModel @Inject constructor(hub: MediaHub, servers: MediaServerSources) : ViewModel() {
    val any: StateFlow<Boolean> = servers.state.map { it.visible }
        .stateIn(viewModelScope, SharingStarted.Eagerly, servers.state.value.visible)

    /** The sources as Medien › Quellen lists them (a pair once). */
    val count: StateFlow<Int> = hub.store.sources.map { list -> list.count { !it.kind.share } + com.gatecontrol.android.media.MediaLogic.shares(list).size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
}

/** Hands the queues of the pages to the player (stage 4c) or says it comes with the next update. */
@HiltViewModel
class MediaPlayHostViewModel @Inject constructor(val requests: MediaPlayRequests) : ViewModel()
