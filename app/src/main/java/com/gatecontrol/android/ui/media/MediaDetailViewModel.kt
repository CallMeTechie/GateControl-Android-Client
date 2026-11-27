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
import com.gatecontrol.android.media.MediaRowsCache
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.jellyfin.JellyfinDeviceCaps
import com.gatecontrol.android.media.jellyfin.JellyfinProfile
import com.gatecontrol.android.media.server.MediaConvert
import com.gatecontrol.android.media.server.MediaConvertHint
import com.gatecontrol.android.media.offline.MediaOffline
import com.gatecontrol.android.media.offline.OfflineAllowed
import com.gatecontrol.android.media.offline.OfflineItem
import com.gatecontrol.android.media.offline.OfflineQuality
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
    /** The download of this film or episode (question 22 a); null: none. */
    val offline: OfflineItem? = null,
    /** The downloads of the episodes listed, by the episode's id. */
    val episodesOffline: Map<String, OfflineItem> = emptyMap(),
    /** Whether Jellyfin lets the user download ("Laden" or the note). */
    val allowed: OfflineAllowed = OfflineAllowed.UNKNOWN,
    /** Shown from the download, without a connection. */
    val fromPhone: Boolean = false,
    /** A collection's titles ("Sammlungen"). */
    val items: List<MediaTitle> = emptyList(),
    /**
     * „Videos umwandeln“ (plugin Medien 1.6.0): the hint before playing a file this phone does not play directly
     * ([convertProblem], e.g. „H.264 10-Bit (High 10)“); null: none (plays here, converted, or the plugin without it).
     */
    val convert: MediaConvertHint? = null,
    val convertProblem: String? = null,
    /** „Für heute Nacht vormerken“ is being sent. */
    val convertBusy: Boolean = false,
    /** „Für heute Nacht vormerken“ was refused (no connection, too many at once). */
    val convertFailed: Boolean = false,
) {
    /** The heart is offered: a Jellyfin source, not from the download (a share has no favourites, decision 74). */
    val canFavorite: Boolean get() = source?.kind == com.gatecontrol.android.media.MediaSourceKind.JELLYFIN && !fromPhone && detail != null

    /** "Laden" is offered: Jellyfin, not refused. */
    val canLoad: Boolean get() = source?.kind == com.gatecontrol.android.media.MediaSourceKind.JELLYFIN && allowed != OfflineAllowed.NO && !fromPhone
}

@HiltViewModel
class MediaDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val hub: MediaHub,
    private val requests: MediaPlayRequests,
    private val settings: MediaPhoneSettings,
    private val network: MediaNetwork,
    val pictures: MediaPictures,
    private val offline: MediaOffline,
    /** The overview's rows: asked again at the next opening after a heart or "Gesehen" here. */
    private val rowsCache: MediaRowsCache,
    /** „Videos umwandeln“ of the plugin Medien: the states asked together and kept a few minutes. */
    private val convert: MediaConvert,
) : ViewModel() {

    private val browse = MediaBrowse(hub) {}

    private val start = MediaTitle(
        id = savedState.get<String>(ARG_ID).orEmpty(),
        sourceId = savedState.get<String>(ARG_SOURCE).orEmpty(),
        kind = MediaTitle.Kind.MOVIE,
        name = savedState.get<String>(ARG_NAME).orEmpty(),
    )

    private val _ui = MutableStateFlow(MediaDetailUi(start, source = hub.store.source(start.sourceId), seasonId = savedState.get<String>(ARG_SEASON)))
    val ui: StateFlow<MediaDetailUi> = _ui.asStateFlow()

    val link: StateFlow<MediaLink> = hub.link

    /** Away the VPN for media („VPN wird verbunden …“, the consent, „Unterwegs nur über VPN“). */
    val gate: StateFlow<com.gatecontrol.android.media.MediaGate> = hub.gate

    /** Android's VPN consent was asked: given → the VPN starts and the page loads. */
    fun consent(granted: Boolean) {
        viewModelScope.launch {
            hub.consent(granted)
            if (granted) load()
        }
    }

    /** On Wi-Fi now ("wartet auf WLAN"). */
    val wifi: StateFlow<Boolean> get() = offline.wifi

    /** The quality "Laden" offers first (Medien › Quellen). */
    val offlineQuality: StateFlow<OfflineQuality> = settings.offlineQuality

    init {
        load()
        viewModelScope.launch {
            offline.items.collect { list -> _ui.update { offlineInto(it, list) } }
        }
    }

    private fun offlineInto(u: MediaDetailUi, list: List<OfflineItem>): MediaDetailUi {
        val mine = list.firstOrNull { it.sourceId == u.title.sourceId && it.itemId == u.title.id }
        val eps = u.episodes.mapNotNull { e -> list.firstOrNull { it.sourceId == e.sourceId && it.itemId == e.id }?.let { e.id to it } }.toMap()
        return u.copy(offline = mine, episodesOffline = eps)
    }

    /** Bytes used by the downloads and the limit ("Belegt 7,4 / 20 GB" in the sheet). */
    fun storage(): Pair<Long, Long> = offline.usedBytes() to offline.limitBytes()

    /** Loaded (again: back from the player, "Erneut versuchen"). */
    fun load() {
        val t = _ui.value.title
        if (hub.store.source(t.sourceId) == null) {
            _ui.update { it.copy(loading = false, problem = MediaProblem.FAILED) }
            return
        }
        viewModelScope.launch {
            // home or away; away the VPN is started when the source needs it (Netz-Regel)
            hub.store.source(t.sourceId)?.let { hub.prepare(it) }
            val (loaded, problem) = browse.detail(t)
            if (loaded == null) {
                // no connection (or away without the VPN): a downloaded title shows (and plays) from the phone
                val kept = offline.ready(t.sourceId, t.id)
                if (kept != null && (problem == MediaProblem.OFFLINE || problem == MediaProblem.VPN || problem == MediaProblem.NO_ROUTE)) {
                    val title = kept.titleNow()
                    _ui.update { offlineInto(it.copy(title = title, detail = MediaDetail(title, listOfNotNull(kept.version)), loading = false, problem = null, fromPhone = true, caps = hub.caps()), offline.items.value) }
                    return@launch
                }
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
                    fromPhone = false,
                )
            }
            _ui.value.source?.let { s -> launch { val a = offline.canDownload(s); _ui.update { it.copy(allowed = a) } } }
            launch { convertHint() }
            if (detail.title.kind == MediaTitle.Kind.COLLECTION) {
                val items = browse.collection(detail.title)
                _ui.update { it.copy(items = items) }
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
            _ui.update { if (it.seasonId == seasonId) offlineInto(it.copy(episodes = episodes), offline.items.value) else it }
            // the season's states in one question: an episode opened next shows its hint at once
            if (jellyfin()) convert.prefetch(episodes.map { it.id })
        }
    }

    private fun jellyfin(): Boolean = _ui.value.source?.kind == com.gatecontrol.android.media.MediaSourceKind.JELLYFIN && !_ui.value.fromPhone

    /**
     * „Videos umwandeln“: when this phone does not play the file's picture itself (the same rule as its device
     * profile), what the plugin knows of it (tonight, being converted, or „Für heute Nacht vormerken“).
     */
    private suspend fun convertHint() {
        val u = _ui.value
        if (!jellyfin() || !u.title.playable) return
        val problem = JellyfinProfile.videoProblem(u.detail?.version?.video, u.caps) ?: run {
            _ui.update { it.copy(convert = null, convertProblem = null) }
            return
        }
        val hint = convert.hint(u.title.id, problem)
        _ui.update { if (it.title.id == u.title.id) it.copy(convert = hint, convertProblem = problem) else it }
    }

    /**
     * „Andere Folge“ of the hint (mockup picture 5): an episode leads to its series on its season, a film (or an
     * episode without its series) back to where it was opened from. The series' page, or null for „back“.
     */
    fun otherTitle(): MediaTitle? {
        val t = _ui.value.title
        val seriesId = t.seriesId ?: return null
        if (t.kind != MediaTitle.Kind.EPISODE) return null
        return MediaTitle(seriesId, t.sourceId, MediaTitle.Kind.SERIES, t.seriesName.orEmpty(), seasonId = t.seasonId)
    }

    /** „Für heute Nacht vormerken“: onto the plugin's list (the admin queues it); playing stays possible. */
    fun requestConvert() {
        val u = _ui.value
        if (u.convertBusy || u.convert != MediaConvertHint.Offer) return
        _ui.update { it.copy(convertBusy = true, convertFailed = false) }
        viewModelScope.launch {
            val hint = convert.request(u.title.id)
            _ui.update { it.copy(convertBusy = false, convert = hint ?: it.convert, convertFailed = hint == null) }
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
                overviewChanged()
            }
        }
    }

    /** The heart (decision 74): kept in Jellyfin for the person, the row "Favoriten" follows at the next opening. */
    fun toggleFavorite() {
        val t = _ui.value.title
        val favorite = !t.favorite
        // shown at once, back when the server did not take it
        _ui.update { it.copy(title = it.title.copy(favorite = favorite)) }
        viewModelScope.launch {
            if (browse.setFavorite(t, favorite)) {
                overviewChanged()
            } else {
                _ui.update { it.copy(title = it.title.copy(favorite = !favorite)) }
            }
        }
    }

    private fun overviewChanged() {
        _ui.value.source?.let { rowsCache.markStale(it) }
    }

    /** An episode's "Gesehen" in the list of a season. */
    fun toggleEpisodePlayed(episode: MediaTitle) {
        val played = !episode.played
        viewModelScope.launch {
            if (browse.setPlayed(episode, played)) {
                _ui.update { u -> u.copy(episodes = u.episodes.map { if (it.id == episode.id) it.copy(played = played, positionMs = if (played) 0 else it.positionMs) else it }) }
                overviewChanged()
            }
        }
    }

    /** "Weiter ab …" ([fromStart] false) or "Von vorn", with the sound and subtitle chosen here. */
    fun play(fromStart: Boolean) {
        val u = _ui.value
        viewModelScope.launch { requests.play(u.title, fromStart, u.audio, u.subtitle) }
    }

    /** "Laden" of this film or episode in [quality], with the sound chosen here; the NAS converts first if asked. */
    fun download(quality: OfflineQuality) {
        val u = _ui.value
        settings.setOfflineQuality(quality)
        offline.enqueue(listOf(u.title), quality, u.audio)
    }

    /** "Staffel laden": the episodes of the season shown, those not loaded yet. */
    fun downloadSeason(quality: OfflineQuality) {
        val u = _ui.value
        settings.setOfflineQuality(quality)
        offline.enqueue(u.episodes.filter { e -> u.episodesOffline[e.id] == null }, quality)
    }

    /** "Abbrechen" (loading or waiting) or "Löschen" (ready). */
    fun removeDownload(item: OfflineItem) = offline.delete(item.id)

    fun retryDownload(item: OfflineItem) = offline.retry(item.id)

    /** An episode of the list: on from where it was left (its own sound and subtitle: the series' choice). */
    fun playEpisode(episode: MediaTitle) {
        viewModelScope.launch { requests.play(episode) }
    }

    companion object {
        const val ARG_SOURCE = "source"
        const val ARG_ID = "id"
        const val ARG_NAME = "name"

        /** A series opened on this season (optional). */
        const val ARG_SEASON = "season"
    }
}
