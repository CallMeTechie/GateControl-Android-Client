package com.gatecontrol.android.ui.media

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.media.MediaBrowse
import com.gatecontrol.android.media.MediaDetail
import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.MediaLink
import com.gatecontrol.android.media.MediaNetwork
import com.gatecontrol.android.media.MediaPhoneLogic
import com.gatecontrol.android.media.MediaPhoneSettings
import com.gatecontrol.android.media.MediaPictures
import com.gatecontrol.android.media.MediaPlayRequests
import com.gatecontrol.android.media.MediaProblem
import com.gatecontrol.android.media.MediaQualityOffer
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.jellyfin.JellyfinDeviceCaps
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A title's detail page (mockup 3): sound, subtitle and quality before the start; a series with seasons and episodes. */
data class MediaDetailUi(
    val title: MediaTitle,
    val source: MediaSource? = null,
    val detail: MediaDetail? = null,
    val loading: Boolean = true,
    val problem: MediaProblem? = null,
    val seasons: List<MediaTitle> = emptyList(),
    val seasonId: String? = null,
    val episodes: List<MediaTitle> = emptyList(),
    val audio: Int? = null,
    val subtitle: Int? = null,
    val offer: MediaQualityOffer? = null,
    /** What this phone plays itself (the notes of the sound tracks). */
    val caps: JellyfinDeviceCaps = JellyfinDeviceCaps(),
    /** On mobile data the limit of the settings holds (bit/s); null: it does not apply now. */
    val mobileLimit: Long? = null,
)

@HiltViewModel
class MediaDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val hub: MediaHub,
    private val requests: MediaPlayRequests,
    private val settings: MediaPhoneSettings,
    private val network: MediaNetwork,
    val pictures: MediaPictures,
) : ViewModel() {

    private val browse = MediaBrowse(hub) {}

    private val start = MediaTitle(
        id = savedState.get<String>(ARG_ID).orEmpty(),
        sourceId = savedState.get<String>(ARG_SOURCE).orEmpty(),
        kind = MediaTitle.Kind.MOVIE,
        name = savedState.get<String>(ARG_NAME).orEmpty(),
    )

    private val _ui = MutableStateFlow(MediaDetailUi(start, source = hub.store.source(start.sourceId)))
    val ui: StateFlow<MediaDetailUi> = _ui.asStateFlow()

    val link: StateFlow<MediaLink> = hub.link

    init {
        load()
    }

    /** Loaded (again: back from the player, "Erneut versuchen"). */
    fun load() {
        val t = _ui.value.title
        if (hub.store.source(t.sourceId) == null) {
            _ui.update { it.copy(loading = false, problem = MediaProblem.FAILED) }
            return
        }
        viewModelScope.launch {
            if (hub.link.value.atHome == null) hub.check()
            val (loaded, problem) = browse.detail(t)
            if (loaded == null) {
                _ui.update { it.copy(loading = false, problem = problem) }
                return@launch
            }
            val detail = loaded.detail
            _ui.update {
                it.copy(
                    title = detail.title,
                    detail = detail,
                    loading = false,
                    problem = null,
                    audio = if (it.detail != null) it.audio ?: loaded.audio else loaded.audio,
                    subtitle = if (it.detail != null) it.subtitle else loaded.subtitle,
                    offer = loaded.offer,
                    caps = hub.caps(),
                    mobileLimit = MediaPhoneLogic.mobileLimit(network.cellular(), hub.link.value.atHome, settings.mobileCap.value),
                )
            }
            if (detail.title.kind == MediaTitle.Kind.SERIES) {
                val seasons = browse.seasons(detail.title)
                _ui.update { it.copy(seasons = seasons) }
                val keep = _ui.value.seasonId?.let { id -> seasons.firstOrNull { it.id == id } }
                (keep ?: seasons.firstOrNull { (it.unplayed ?: 0) > 0 } ?: seasons.firstOrNull())?.let { season(it.id) }
            }
        }
    }

    fun season(seasonId: String) {
        val series = _ui.value.title
        _ui.update { it.copy(seasonId = seasonId) }
        viewModelScope.launch {
            val episodes = browse.episodes(series, seasonId)
            _ui.update { if (it.seasonId == seasonId) it.copy(episodes = episodes) else it }
        }
    }

    fun chooseAudio(index: Int) = choose { it.copy(audio = index) }

    fun chooseSubtitle(index: Int?) = choose { it.copy(subtitle = index) }

    private fun choose(f: (MediaDetailUi) -> MediaDetailUi) {
        val before = _ui.value
        _ui.update(f)
        val now = _ui.value
        val seriesId = now.title.seriesId
        val version = now.detail?.version
        if (seriesId != null && version != null && (now.audio != before.audio || now.subtitle != before.subtitle)) {
            browse.rememberTracks(seriesId, version, now.audio, now.subtitle)
        }
    }

    /** "Gesehen" (and back). */
    fun togglePlayed() {
        val t = _ui.value.title
        val played = !t.played
        viewModelScope.launch {
            if (browse.setPlayed(t, played)) {
                _ui.update { it.copy(title = it.title.copy(played = played, positionMs = if (played) 0 else it.title.positionMs)) }
            }
        }
    }

    /** An episode's "Gesehen" in the list of a season. */
    fun toggleEpisodePlayed(episode: MediaTitle) {
        val played = !episode.played
        viewModelScope.launch {
            if (browse.setPlayed(episode, played)) {
                _ui.update { u -> u.copy(episodes = u.episodes.map { if (it.id == episode.id) it.copy(played = played, positionMs = if (played) 0 else it.positionMs) else it }) }
            }
        }
    }

    /** "Weiter ab …" ([fromStart] false) or "Von vorn", with the sound and subtitle chosen here. */
    fun play(fromStart: Boolean) {
        val u = _ui.value
        viewModelScope.launch { requests.play(u.title, fromStart, u.audio, u.subtitle) }
    }

    /** An episode of the list: on from where it was left (its own sound and subtitle: the series' choice). */
    fun playEpisode(episode: MediaTitle) {
        viewModelScope.launch { requests.play(episode) }
    }

    companion object {
        const val ARG_SOURCE = "source"
        const val ARG_ID = "id"
        const val ARG_NAME = "name"
    }
}
