package com.gatecontrol.android.ui.places

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatExtrasRepository
import com.gatecontrol.android.familie.FamilieGeocoder
import com.gatecontrol.android.location.DeviceLocator
import com.gatecontrol.android.location.LocateResult
import com.gatecontrol.android.places.AddressHit
import com.gatecontrol.android.places.MyPlace
import com.gatecontrol.android.places.MyPlaces
import com.gatecontrol.android.places.PlaceDraft
import com.gatecontrol.android.places.PlaceEdit
import com.gatecontrol.android.places.PlaceOption
import com.gatecontrol.android.places.PlacePicking
import com.gatecontrol.android.places.PlaceSearch
import com.gatecontrol.android.places.PlaceUse
import com.gatecontrol.android.places.PlacesRepository
import com.gatecontrol.android.places.PlacesState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

/** What the address search and „Aktueller Standort“ show. */
data class FinderState(
    val searching: Boolean = false,
    /** The hits of the last search; null: not searched yet. */
    val hits: List<AddressHit>? = null,
    val attribution: String? = null,
    /** The hits came from the phone's own geocoder (the server's search is off). */
    val viaDevice: Boolean = false,
    /** `none` | `busy` | `down` | `offline` | `off` (neither the server nor the phone can search). */
    val problem: String? = null,
    val retryMin: Int? = null,
    val locating: Boolean = false,
)

/**
 * The address search of „Meine Orte“ and of the choice: the server's (`GET places/search`, only on „Suchen“) and — when
 * the admin switched it off — the phone's own geocoder. „Aktueller Standort“ asks the phone once.
 */
class PlaceFinder(private val context: Context, private val repo: PlacesRepository, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(FinderState())
    val state: StateFlow<FinderState> = _state.asStateFlow()
    private var job: Job? = null

    fun search(query: String, near: Pair<Double, Double>?) {
        val q = query.trim()
        if (!PlacePicking.searchable(q)) return
        job?.cancel()
        job = scope.launch {
            _state.update { it.copy(searching = true, problem = null, retryMin = null) }
            when (val r = repo.search(q, near)) {
                is PlaceSearch.Hits -> _state.update {
                    it.copy(searching = false, hits = r.hits, attribution = r.attribution, viaDevice = false, problem = if (r.hits.isEmpty()) "none" else null)
                }
                is PlaceSearch.Refused -> {
                    if (r.code == PlacesRepository.CODE_SEARCH_OFF) {
                        val hits = deviceSearch(q, near)
                        _state.update {
                            it.copy(
                                searching = false,
                                hits = hits.orEmpty(),
                                attribution = null,
                                viaDevice = true,
                                problem = if (hits == null) "off" else if (hits.isEmpty()) "none" else null,
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                searching = false,
                                problem = when (r.code) {
                                    "RATE_LIMITED" -> "busy"
                                    PlacesRepository.CODE_OFFLINE -> "offline"
                                    else -> "down"
                                },
                                retryMin = r.retryAfterS?.let { s -> ((s + 59) / 60).coerceAtLeast(1) },
                            )
                        }
                    }
                }
            }
        }
    }

    /** The phone's geocoder; null: the phone has none. */
    private suspend fun deviceSearch(q: String, near: Pair<Double, Double>?): List<AddressHit>? {
        if (!FamilieGeocoder.available()) return null
        val hits = withContext(Dispatchers.IO) { FamilieGeocoder.search(context, q, Locale.getDefault()) }
        return hits.filter { MyPlaces.hasPoint(it.lat, it.lon) }.map { h ->
            AddressHit(
                name = h.label.substringBefore(',').trim().take(MyPlaces.NAME_MAX).ifEmpty { q.take(MyPlaces.NAME_MAX) },
                address = h.label.take(MyPlaces.ADDRESS_MAX),
                lat = h.lat,
                lon = h.lon,
                distanceM = near?.let { PlacePicking.distanceM(it.first, it.second, h.lat, h.lon) },
                osm = false,
            )
        }
    }

    fun clear() {
        job?.cancel()
        _state.value = FinderState()
    }

    /** One position; [onFix] with it, [onFail] with the text why not. */
    fun locate(onFix: (Double, Double) -> Unit, onFail: (Int) -> Unit) {
        if (_state.value.locating) return
        scope.launch {
            _state.update { it.copy(locating = true) }
            val r = DeviceLocator.locate(context)
            _state.update { it.copy(locating = false) }
            when (r) {
                is LocateResult.Fix -> onFix(r.location.latitude, r.location.longitude)
                LocateResult.NoPermission -> onFail(R.string.places_locate_no_permission)
                LocateResult.LocationOff -> onFail(R.string.places_locate_off)
                LocateResult.NoFix -> onFail(R.string.places_locate_failed)
            }
        }
    }

    /** The address of a point, best effort (the phone's geocoder); null without one. */
    suspend fun addressOf(lat: Double, lon: Double): String? =
        if (!FamilieGeocoder.available()) null else runCatching { FamilieGeocoder.address(context, lat, lon) }.getOrNull()?.take(MyPlaces.ADDRESS_MAX)
}

/** The phone's last known position for the distances in a list (no new fix). */
internal fun lastKnown(context: Context): Pair<Double, Double>? =
    DeviceLocator.lastKnown(context)?.let { it.latitude to it.longitude }?.takeIf { MyPlaces.hasPoint(it.first, it.second) }

/** A one-shot notice: a text resource (with arguments) or a ready sentence. */
sealed interface PlacesNotice {
    data class Res(val res: Int, val args: List<Any> = emptyList()) : PlacesNotice
    data class Text(val text: String) : PlacesNotice
}

/**
 * Profil › Meine Orte: the list, a place, the editor. The Familie places (read only) come from the Messenger (`GET places`,
 * else `familie/places`) — the app's one source that has their points.
 */
@HiltViewModel
class MyPlacesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: PlacesRepository,
    private val extras: ChatExtrasRepository,
) : ViewModel() {
    val state: StateFlow<PlacesState> = repo.state

    val familie: StateFlow<List<PlaceOption>> = extras.pickPlaces
        .map { list -> list.orEmpty().filter { it.familie } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _here = MutableStateFlow<Pair<Double, Double>?>(null)
    val here: StateFlow<Pair<Double, Double>?> = _here.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    val finder = PlaceFinder(context, repo, viewModelScope)

    private val noticeCh = Channel<PlacesNotice>(Channel.BUFFERED)
    val notices: Flow<PlacesNotice> = noticeCh.receiveAsFlow()

    /** Foreground: the list (unless just loaded), the Familie places and where the phone is. */
    fun load() {
        _here.value = lastKnown(context)
        viewModelScope.launch {
            _refreshing.value = true
            repo.refresh()
            _refreshing.value = false
        }
        viewModelScope.launch { runCatching { extras.refreshPickPlaces() } }
    }

    fun place(id: Long): MyPlace? = state.value.places.firstOrNull { it.id == id }

    fun save(editing: MyPlace?, d: PlaceDraft, onDone: (MyPlace?) -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val r = if (editing == null) repo.create(d) else repo.update(editing, d)
            _busy.value = false
            when (r) {
                is PlaceEdit.Done -> {
                    noticeCh.trySend(PlacesNotice.Res(R.string.places_saved))
                    onDone(r.place)
                }
                is PlaceEdit.Refused -> noticeCh.trySend(PlacesNotice.Text(placeErrorText(context, r, state.value.list?.limits?.places ?: 100)))
            }
        }
    }

    /** Without [force]: a place still in use comes back to [onInUse] with who uses it (the question „trotzdem löschen“). */
    fun delete(p: MyPlace, force: Boolean, onInUse: (List<PlaceUse>) -> Unit, onDone: () -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val r = repo.delete(p.id, force)
            _busy.value = false
            when {
                r is PlaceEdit.Done -> {
                    noticeCh.trySend(PlacesNotice.Res(R.string.places_deleted))
                    onDone()
                }
                r is PlaceEdit.Refused && r.code == PlacesRepository.CODE_IN_USE -> onInUse(r.usedBy.ifEmpty { p.usedBy })
                r is PlaceEdit.Refused -> noticeCh.trySend(PlacesNotice.Text(placeErrorText(context, r)))
            }
        }
    }

    fun locate(onFix: (Double, Double) -> Unit) = finder.locate(onFix) { noticeCh.trySend(PlacesNotice.Res(it)) }

    suspend fun addressOf(lat: Double, lon: Double): String? = finder.addressOf(lat, lon)
}

/**
 * The choice of a place in a form: „Meine Orte“ from the profile (the copy works offline), where the phone is, the
 * address search and a new place saved from a hit, the map or the current position.
 */
@HiltViewModel
class PlacePickerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: PlacesRepository,
) : ViewModel() {
    val state: StateFlow<PlacesState> = repo.state

    private val _here = MutableStateFlow<Pair<Double, Double>?>(null)
    val here: StateFlow<Pair<Double, Double>?> = _here.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val finder = PlaceFinder(context, repo, viewModelScope)

    private val noticeCh = Channel<PlacesNotice>(Channel.BUFFERED)
    val notices: Flow<PlacesNotice> = noticeCh.receiveAsFlow()

    init {
        _here.value = lastKnown(context)
        repo.refreshIfStale()
    }

    fun refreshHere() {
        _here.value = lastKnown(context) ?: _here.value
    }

    /** A new one of „Meine Orte“ from the choice; [onDone] with it as a choice. */
    fun create(d: PlaceDraft, onDone: (PlaceOption) -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val r = repo.create(d)
            _busy.value = false
            when (r) {
                is PlaceEdit.Done -> r.place?.let { onDone(PlacePicking.option(it)) }
                is PlaceEdit.Refused -> noticeCh.trySend(PlacesNotice.Text(placeErrorText(context, r)))
            }
        }
    }

    /** The choice took one of „Meine Orte“: its „zuletzt genutzt“ on the server. */
    fun used(o: PlaceOption) {
        o.profileId?.let(repo::markUsed)
    }

    fun locate(onFix: (Double, Double) -> Unit) = finder.locate({ lat, lon ->
        _here.value = lat to lon
        onFix(lat, lon)
    }) { noticeCh.trySend(PlacesNotice.Res(it)) }

    suspend fun addressOf(lat: Double, lon: Double): String? = finder.addressOf(lat, lon)
}
