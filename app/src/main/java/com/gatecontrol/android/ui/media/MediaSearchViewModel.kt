package com.gatecontrol.android.ui.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.media.MediaBrowse
import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.MediaPictures
import com.gatecontrol.android.media.MediaTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MediaSearchUi(val query: String = "", val searching: Boolean = false, val results: List<MediaTitle>? = null)

/** "In allen Quellen suchen": each Jellyfin server and each share, a little after the last key. */
@HiltViewModel
class MediaSearchViewModel @Inject constructor(
    private val hub: MediaHub,
    val pictures: MediaPictures,
) : ViewModel() {
    private val browse = MediaBrowse(hub) {}

    private val _ui = MutableStateFlow(MediaSearchUi())
    val ui: StateFlow<MediaSearchUi> = _ui.asStateFlow()

    private var job: Job? = null

    fun setQuery(text: String) {
        _ui.update { it.copy(query = text) }
        job?.cancel()
        if (text.isBlank()) {
            _ui.update { it.copy(searching = false, results = null) }
            return
        }
        job = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            _ui.update { it.copy(searching = true) }
            if (hub.link.value.atHome == null) hub.check()
            val found = browse.search(text)
            _ui.update { if (it.query == text) it.copy(searching = false, results = found) else it }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 350L
    }
}
