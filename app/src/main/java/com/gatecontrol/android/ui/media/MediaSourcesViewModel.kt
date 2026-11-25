package com.gatecontrol.android.ui.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.MediaPhoneSettings
import com.gatecontrol.android.media.MediaSetup
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.jellyfin.JellyfinAuthorize
import com.gatecontrol.android.media.offline.MediaOffline
import com.gatecontrol.android.media.offline.OfflineQuality
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** "Gerät anmelden": the code typed, sending, how it ended. */
data class MediaAuthorizeUi(val code: String = "", val sending: Boolean = false, val result: JellyfinAuthorize? = null)

/**
 * Medien › Quellen (mockup 6): the sources (sign out, remove, "Immer Original"), the phone's settings (mobile data
 * limit, "Weiterschauen auf Start") and "Gerät anmelden" — another device's Quick Connect code confirmed with this
 * phone's access to Jellyfin.
 */
@HiltViewModel
class MediaSourcesViewModel @Inject constructor(
    private val hub: MediaHub,
    private val settings: MediaPhoneSettings,
    private val offline: MediaOffline,
) : ViewModel() {
    private val setup = MediaSetup(hub)

    val sources: StateFlow<List<MediaSource>> = hub.store.sources
    val mobileCap: StateFlow<Long?> = settings.mobileCap
    val startCard: StateFlow<Boolean> = settings.startCard
    val offlineQuality: StateFlow<OfflineQuality> = settings.offlineQuality
    val offlineLimitGb: StateFlow<Int> = settings.offlineLimitGb

    /** Bytes the downloads take now. */
    val offlineUsed: StateFlow<Long> = offline.items.map { list -> list.sumOf { if (it.ready) it.size else it.loaded } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun setOfflineQuality(q: OfflineQuality) = settings.setOfflineQuality(q)

    /** A new storage limit; waiting downloads that stopped at the old one try again. */
    fun setOfflineLimitGb(gb: Int) {
        settings.setOfflineLimitGb(gb)
        offline.items.value.filter { it.failure == com.gatecontrol.android.media.offline.OfflineFailure.LIMIT }.forEach { offline.retry(it.id) }
    }

    private val _authorize = MutableStateFlow(MediaAuthorizeUi())
    val authorize: StateFlow<MediaAuthorizeUi> = _authorize.asStateFlow()

    /** The Jellyfin server "Gerät anmelden" uses: the one shown, signed in. */
    fun authorizeSource(): MediaSource? = hub.currentSource()?.takeIf { it.token != null }
        ?: hub.store.sources.value.firstOrNull { it.kind == MediaSourceKind.JELLYFIN && it.token != null }

    fun setAlwaysOriginal(source: MediaSource, on: Boolean) = hub.store.setAlwaysOriginal(source.id, on)

    fun setMobileCap(bps: Long?) = settings.setMobileCap(bps)

    fun setStartCard(on: Boolean) = settings.setStartCard(on)

    /** "Abmelden": this phone's access to the server is gone, the source stays ("Neu anmelden"). */
    fun signOut(source: MediaSource) {
        hub.store.dropToken(source.id)
        hub.forget(source.id)
    }

    /** "Entfernen": the source with its access (a pair's partner stays on its own). */
    fun remove(source: MediaSource) {
        offline.removeSource(source.id)
        hub.store.remove(source.id)
        hub.forget(source.id)
    }

    fun setCode(text: String) {
        val clean = text.filter { it.isLetterOrDigit() || it == ' ' }.take(MAX_CODE)
        _authorize.update { it.copy(code = clean, result = null) }
    }

    fun confirm() {
        val source = authorizeSource() ?: return
        val code = _authorize.value.code
        if (code.isBlank() || _authorize.value.sending) return
        _authorize.update { it.copy(sending = true, result = null) }
        viewModelScope.launch {
            val r = setup.authorize(source, code)
            _authorize.update { it.copy(sending = false, result = r, code = if (r == JellyfinAuthorize.CONFIRMED) "" else it.code) }
        }
    }

    private companion object {
        const val MAX_CODE = 12
    }
}
