package com.gatecontrol.android.ui.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.media.MediaPhoneSettings
import com.gatecontrol.android.media.MediaPictures
import com.gatecontrol.android.media.MediaPlayRequests
import com.gatecontrol.android.media.offline.MediaOffline
import com.gatecontrol.android.media.offline.MediaOfflineLogic
import com.gatecontrol.android.media.offline.OfflineGroup
import com.gatecontrol.android.media.offline.OfflineItem
import com.gatecontrol.android.media.offline.OfflineQuality
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The page "Offline" (mockup 7): the room taken of the limit, the rules, the downloads as rows. */
data class MediaOfflineUi(
    val groups: List<OfflineGroup> = emptyList(),
    val used: Long = 0,
    val limit: Long = 0,
    val quality: OfflineQuality = OfflineQuality.DEFAULT,
    val wifi: Boolean = true,
)

@HiltViewModel
class MediaOfflineViewModel @Inject constructor(
    private val offline: MediaOffline,
    private val requests: MediaPlayRequests,
    settings: MediaPhoneSettings,
    val pictures: MediaPictures,
) : ViewModel() {

    val ui: StateFlow<MediaOfflineUi> = combine(offline.items, offline.wifi, settings.offlineQuality, settings.offlineLimitGb) { items, wifi, quality, gb ->
        MediaOfflineUi(
            groups = MediaOfflineLogic.groups(items),
            used = items.sumOf { if (it.ready) it.size else it.loaded },
            limit = MediaOfflineLogic.gb(gb),
            quality = quality,
            wifi = wifi,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MediaOfflineUi(limit = offline.limitBytes()))

    init {
        offline.ensure()
    }

    /** A ready download from where it was left (the next downloaded episodes after it). */
    fun play(item: OfflineItem) {
        viewModelScope.launch { requests.play(item.titleNow()) }
    }

    /** "Löschen" / "Abbrechen" of a row: all its downloads. */
    fun delete(group: OfflineGroup) = group.items.forEach { offline.delete(it.id) }

    fun delete(item: OfflineItem) = offline.delete(item.id)

    fun retry(group: OfflineGroup) = group.items.filter { it.failure != null }.forEach { offline.retry(it.id) }
}
