package com.gatecontrol.android.ui.drive

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.drive.DBbox
import com.gatecontrol.android.drive.DCluster
import com.gatecontrol.android.drive.DDistrict
import com.gatecontrol.android.drive.DFaceRef
import com.gatecontrol.android.drive.DMe
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DPeople
import com.gatecontrol.android.drive.DPerson
import com.gatecontrol.android.drive.DPersonContact
import com.gatecontrol.android.drive.DPersonDetail
import com.gatecontrol.android.drive.DPhotoFilter
import com.gatecontrol.android.drive.DPlaceSummary
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DSimilar
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveGeoBackfill
import com.gatecontrol.android.drive.DriveGeoBackfillWork
import com.gatecontrol.android.drive.DriveLive
import com.gatecontrol.android.drive.DrivePlacesJson
import com.gatecontrol.android.drive.DrivePlacesLogic
import com.gatecontrol.android.drive.DrivePlacesPrefs
import com.gatecontrol.android.drive.DriveTransfers
import com.gatecontrol.android.drive.PlacesSettings
import com.gatecontrol.android.drive.faces.DriveFaceModel
import com.gatecontrol.android.drive.faces.DriveFaceModelWork
import com.gatecontrol.android.drive.faces.DrivePeopleWork
import com.gatecontrol.android.drive.faces.FaceModelState
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.mail.MailPimRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** The people's names by id (for chips of who is on photos). */
private fun names(people: DPeople?): Map<Long, String> =
    people?.people.orEmpty().filter { !it.hidden && it.named }.associate { it.id to it.name!! }

/** "Karte" (Drive 1.16, `photoPlaces`): bundles of photos on OpenStreetMap, one chosen with its photos below. */
@HiltViewModel
class DriveMapViewModel @Inject constructor(
    private val api: DriveApi,
    val live: DriveLive,
    private val transfers: DriveTransfers,
) : ViewModel() {
    private val _clusters = MutableStateFlow<List<DCluster>>(emptyList())
    val clusters: StateFlow<List<DCluster>> = _clusters.asStateFlow()
    private val _places = MutableStateFlow<List<DPlaceSummary>>(emptyList())
    /** Every place (for "Ort suchen" and the sheet's time and count). */
    val places: StateFlow<List<DPlaceSummary>> = _places.asStateFlow()
    private val _people = MutableStateFlow<Map<Long, String>>(emptyMap())
    val people: StateFlow<Map<Long, String>> = _people.asStateFlow()
    private val _cls = MutableStateFlow<String?>(null)
    /** "Privat und Arbeit" (null), "Privat", "Arbeit" (Drive 1.13 classes). */
    val cls: StateFlow<String?> = _cls.asStateFlow()

    /** The chosen bundle and its first photos (with the people on them). */
    data class Selected(val cluster: DCluster, val items: List<DNode>, val loading: Boolean, val total: Int?)

    private val _selected = MutableStateFlow<Selected?>(null)
    val selected: StateFlow<Selected?> = _selected.asStateFlow()

    private var loaded: DrivePlacesLogic.Loaded? = null
    private var view: DrivePlacesLogic.View? = null
    private var mapJob: Job? = null
    private var selJob: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        viewModelScope.launch { _places.value = api.photoPlaces(cls = _cls.value).valueOrNull().orEmpty() }
        viewModelScope.launch { _people.value = names(api.people().valueOrNull()) }
    }

    /** The map moved or zoomed: the bundles again when the view left what was loaded (throttled). */
    fun moved(bbox: DBbox, zoom: Int) {
        val v = DrivePlacesLogic.View(bbox, zoom)
        view = v
        if (!DrivePlacesLogic.needsLoad(loaded, v)) return
        mapJob?.cancel()
        mapJob = viewModelScope.launch {
            delay(THROTTLE_MS)
            val want = DrivePlacesLogic.padded(v.bbox)
            val r = api.photoMap(want, v.zoom, cls = _cls.value)
            if (r is DResult.Ok) {
                loaded = DrivePlacesLogic.Loaded(want, v.zoom)
                _clusters.value = r.value
            }
        }
    }

    fun setClass(c: String?) {
        if (c == _cls.value) return
        _cls.value = c
        loaded = null
        _selected.value = null
        view?.let { moved(it.bbox, it.zoom) }
        viewModelScope.launch { _places.value = api.photoPlaces(cls = c).valueOrNull().orEmpty() }
    }

    /** A bundle tapped: its photos (the place's, else those in a small box around it). */
    fun select(c: DCluster?) {
        selJob?.cancel()
        if (c == null) {
            _selected.value = null
            return
        }
        _selected.value = Selected(c, emptyList(), loading = true, total = c.count)
        selJob = viewModelScope.launch {
            val r = api.photos(limit = 12, cls = _cls.value, filter = filterOf(c).copy(withPeople = true))
            _selected.value = Selected(c, r.valueOrNull()?.items.orEmpty(), loading = false, total = c.count)
        }
    }

    /** The filter of a bundle's photos: its place, else a box around it as large as a bundle at this zoom. */
    fun filterOf(c: DCluster): DPhotoFilter {
        if (c.key != null) return DPhotoFilter(place = c.key)
        val v = view
        val half = if (v == null) 0.05 else maxOf(v.bbox.east - v.bbox.west, v.bbox.north - v.bbox.south) / 12.0
        return DPhotoFilter(bbox = DrivePlacesLogic.clamp(DBbox(c.lon - half, c.lat - half, c.lon + half, c.lat + half)))
    }

    suspend fun fetch(node: DNode): DResult<File> = transfers.fetch(node)

    companion object {
        /** Moving and zooming load at most this often. */
        const val THROTTLE_MS = 450L
    }
}

/** One place ("Ort"): its photos by day with their district, who is on them (a tap filters), saved as an album. */
@HiltViewModel
class DrivePlaceViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val api: DriveApi,
    val live: DriveLive,
    private val transfers: DriveTransfers,
) : ViewModel() {
    val key: String = savedStateHandle.get<String>("key").orEmpty()
    private val bbox: DBbox? = savedStateHandle.get<String>("bbox")?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 4 }
        ?.let { DBbox(it[0], it[1], it[2], it[3]) }

    private val _people = MutableStateFlow<List<Long>>(savedStateHandle.get<String>("person")?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty())
    /** The people the photos are filtered by (all of them on a photo). */
    val people: StateFlow<List<Long>> = _people.asStateFlow()

    private val _summary = MutableStateFlow<DPlaceSummary?>(null)
    val summary: StateFlow<DPlaceSummary?> = _summary.asStateFlow()
    private val _districts = MutableStateFlow<List<DDistrict>>(emptyList())
    val districts: StateFlow<List<DDistrict>> = _districts.asStateFlow()
    private val _names = MutableStateFlow<Map<Long, String>>(emptyMap())
    val names: StateFlow<Map<Long, String>> = _names.asStateFlow()
    /** Who is on the place's photos how often (from the photos loaded without the people filter). */
    private val _onPhotos = MutableStateFlow<List<Pair<Long, Int>>>(emptyList())
    val onPhotos: StateFlow<List<Pair<Long, Int>>> = _onPhotos.asStateFlow()
    private val _clusters = MutableStateFlow<List<DCluster>>(emptyList())
    val clusters: StateFlow<List<DCluster>> = _clusters.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private fun filter() = DPhotoFilter(place = key.ifEmpty { null }, bbox = bbox.takeIf { key.isEmpty() }, people = _people.value, districts = key.isNotEmpty(), withPeople = true)

    private val pager = PhotoPager(viewModelScope) { before ->
        api.photos(before = before, filter = filter()).also { r ->
            if (before == null && r is DResult.Ok) {
                if (r.value.districts.isNotEmpty() || _districts.value.isEmpty()) _districts.value = r.value.districts
                if (_people.value.isEmpty()) _onPhotos.value = DrivePlacesLogic.peopleCounts(r.value.items)
            }
        }
    }
    val photos: StateFlow<PhotoPages> = pager.state

    init {
        pager.reload()
        viewModelScope.launch {
            if (key.isNotEmpty()) _summary.value = api.photoPlaces().valueOrNull()?.firstOrNull { it.key == key }
            _names.value = names(api.people().valueOrNull())
            val s = _summary.value
            val box = when {
                bbox != null -> bbox
                s != null -> DrivePlacesLogic.around(listOf(s.lat to s.lon), margin = 0.0)?.let { b -> DrivePlacesLogic.clamp(DBbox(b.west - 0.08, b.south - 0.05, b.east + 0.08, b.north + 0.05)) }
                else -> null
            }
            if (box != null) _clusters.value = api.photoMap(box, 12, filter = DPhotoFilter(place = key.ifEmpty { null })).valueOrNull().orEmpty()
        }
    }

    fun togglePerson(id: Long) {
        _people.value = DrivePlacesLogic.toggle(_people.value, id)
        pager.reload()
    }

    fun more() = pager.more()

    fun retry() = pager.retry()

    fun clearMessage() { _message.value = null }

    /** "Als Album speichern": an album named after the place with its photos (as far as they are listed, at most 500). */
    fun saveAsAlbum(name: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val ids = ArrayList<Long>()
                var cursor: String? = null
                do {
                    val page = api.photos(before = cursor, limit = 200, filter = filter().copy(withPeople = false, districts = false)).valueOrNull() ?: break
                    ids += page.items.map { it.id }
                    cursor = page.next
                } while (cursor != null && ids.size < ALBUM_MAX)
                when (val a = api.createAlbum(name.take(DrivePhotosViewModel.NAME_MAX))) {
                    is DResult.Ok -> {
                        val add = if (ids.isEmpty()) DResult.Ok(a.value) else api.addToAlbum(a.value.id, ids.take(ALBUM_MAX))
                        _message.value = if (add is DResult.Ok) "photos_added:${ids.size.coerceAtMost(ALBUM_MAX)}" else add.errorCode()
                    }
                    else -> _message.value = a.errorCode()
                }
            } finally {
                _busy.value = false
            }
        }
    }

    suspend fun fetch(node: DNode): DResult<File> = transfers.fetch(node)

    companion object {
        private const val ALBUM_MAX = 500
    }
}

/** "Personen": how far the faces are, the named people, those without a name, the hidden ones. */
@HiltViewModel
class DrivePeopleViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DriveApi,
    val live: DriveLive,
    private val prefs: DrivePlacesPrefs,
) : ViewModel() {
    /** The feature model's download (the banner instead of the progress while it is not here). */
    val model: kotlinx.coroutines.flow.Flow<FaceModelState> = DriveFaceModelWork.state(context)

    fun loadModel(mobile: Boolean) = if (mobile) DriveFaceModelWork.start(context, mobile = true) else DriveFaceModelWork.retry(context)

    private val _people = MutableStateFlow<DriveLoad<DPeople>>(DriveLoad.Loading)
    val people: StateFlow<DriveLoad<DPeople>> = _people.asStateFlow()
    val settings: StateFlow<PlacesSettings> = prefs.settings

    fun load() {
        viewModelScope.launch {
            api.photoSettings().valueOrNull()?.let(prefs::took)
            val r = api.people()
            if (r is DResult.Ok || _people.value !is DriveLoad.Ready) _people.value = r.toLoad { it }
        }
    }

    /** A hidden person shown again. */
    fun unhide(p: DPerson) {
        viewModelScope.launch {
            api.patchPerson(p.id, hidden = false)
            load()
        }
    }
}

/** One person: the link, the unsure faces, who is often with them, their places, their photos by year. */
@HiltViewModel
class DrivePersonViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val api: DriveApi,
    val live: DriveLive,
    private val transfers: DriveTransfers,
) : ViewModel() {
    private val _id = MutableStateFlow(savedStateHandle.get<String>("id")?.toLongOrNull() ?: 0L)
    val id: StateFlow<Long> = _id.asStateFlow()

    private val _detail = MutableStateFlow<DriveLoad<DPersonDetail>>(DriveLoad.Loading)
    val detail: StateFlow<DriveLoad<DPersonDetail>> = _detail.asStateFlow()
    private val _uncertain = MutableStateFlow<List<DFaceRef>>(emptyList())
    val uncertain: StateFlow<List<DFaceRef>> = _uncertain.asStateFlow()
    private val _with = MutableStateFlow<Long?>(null)
    /** "Zusammen mit": only photos with this person too. */
    val with: StateFlow<Long?> = _with.asStateFlow()
    private val _all = MutableStateFlow<List<DPerson>>(emptyList())
    /** Everyone (for "Mit anderer Person zusammenführen"). */
    val all: StateFlow<List<DPerson>> = _all.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _gone = MutableStateFlow(false)
    /** Merged into another: the page closes. */
    val gone: StateFlow<Boolean> = _gone.asStateFlow()

    private val pager = PhotoPager(viewModelScope) { before -> api.photos(before = before, filter = DPhotoFilter(people = listOfNotNull(_id.value, _with.value))) }
    val photos: StateFlow<PhotoPages> = pager.state

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val r = api.person(_id.value)
            if (r is DResult.Ok || _detail.value !is DriveLoad.Ready) _detail.value = r.toLoad { it }
            val n = r.valueOrNull()?.uncertain ?: 0
            _uncertain.value = if (n > 0) api.uncertainFaces(_id.value, limit = UNCERTAIN_MAX).valueOrNull().orEmpty() else emptyList()
        }
        pager.reload(quiet = true)
        viewModelScope.launch { _all.value = api.people().valueOrNull()?.people.orEmpty().filter { !it.hidden && it.id != _id.value } }
    }

    fun setWith(other: Long?) {
        _with.value = if (_with.value == other) null else other
        pager.reload()
    }

    fun more() = pager.more()

    fun retry() = pager.retry()

    fun clearMessage() { _message.value = null }

    /** "Ja, alle" / the unsure faces one by one: [yes] they are this person, else never suggested again. */
    fun confirm(faceIds: List<Long>, yes: Boolean) {
        if (faceIds.isEmpty()) return
        _uncertain.value = _uncertain.value.filter { it.faceId !in faceIds }
        viewModelScope.launch {
            val r = api.confirmFaces(_id.value, faceIds, yes)
            if (r !is DResult.Ok) _message.value = r.errorCode()
            load()
        }
    }

    fun setHidden(hidden: Boolean) = patch { api.patchPerson(_id.value, hidden = hidden) }

    fun link(contact: DPersonContact?) = patch { api.patchPerson(_id.value, contact = contact, clearContact = contact == null) }

    private fun patch(call: suspend () -> DResult<*>) {
        viewModelScope.launch {
            val r = call()
            if (r !is DResult.Ok) _message.value = r.errorCode()
            load()
        }
    }

    /** "Mit anderer Person zusammenführen": this one goes into [into]. */
    fun mergeInto(into: Long) {
        viewModelScope.launch {
            when (val r = api.mergePerson(_id.value, into)) {
                is DResult.Ok -> {
                    _id.value = r.value.id
                    _message.value = "people_merged"
                    load()
                }
                else -> _message.value = r.errorCode()
            }
        }
    }

    /** After naming: the name may have merged this person into another. */
    fun renamed(into: Long?) {
        if (into != null && into != _id.value) _id.value = into
        load()
    }

    suspend fun fetch(node: DNode): DResult<File> = transfers.fetch(node)

    companion object {
        private const val UNCERTAIN_MAX = 60
    }
}

/**
 * "Wer ist das?": a person's name — typed or picked from the family's members and the mail contacts the app already has
 * —, "Sieht aus wie …" to merge, "Ausblenden"; afterwards "Rückgängig".
 */
@HiltViewModel
class DriveNameViewModel @Inject constructor(
    private val api: DriveApi,
    private val familie: FamilieRepository,
    private val mail: MailPimRepository,
) : ViewModel() {
    data class State(
        val person: DPersonDetail? = null,
        val similar: DSimilar? = null,
        val family: List<DrivePlacesLogic.NameOption> = emptyList(),
        val contacts: List<DrivePlacesLogic.NameOption> = emptyList(),
        val named: Map<String, Int> = emptyMap(),
        val busy: Boolean = false,
        /** What was done: `save` | `merge` | `hide`, the person it ended in and how many photos it has. */
        val done: Done? = null,
        val error: String? = null,
    )

    data class Done(val what: String, val name: String?, val into: Long?, val count: Int, val undo: (() -> Unit)?)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var loading: Job? = null

    /** The person [id] afresh (each time the sheet opens). */
    fun load(id: Long) {
        loading?.cancel()
        _state.value = State()
        loading = viewModelScope.launch {
            val detail = api.person(id).valueOrNull()
            val similar = api.similarPeople(id).valueOrNull()?.filter { !it.name.isNullOrBlank() }?.maxByOrNull { it.score }
            val everyone = api.people().valueOrNull()?.people.orEmpty()
            val profile = runCatching { familie.cachedProfile() }.getOrNull()
            val family = profile?.members.orEmpty().map { m -> DrivePlacesLogic.NameOption(DrivePlacesJson.CONTACT_FAMILY, m.userId.toString(), m.name, null) }
            val contacts = mail.cards.value.map { c -> DrivePlacesLogic.NameOption(DrivePlacesJson.CONTACT_MAIL, c.id.toString(), c.name, c.company) }
            _state.value = State(
                person = detail,
                similar = similar,
                family = family,
                contacts = contacts,
                named = everyone.filter { it.named && it.id != id }.associate { DrivePlacesLogic.fold(it.name!!) to it.count },
            )
        }
    }

    fun options(typed: String): List<DrivePlacesLogic.NameOption> {
        val s = _state.value
        return DrivePlacesLogic.nameOptions(typed, s.family, s.contacts, s.named)
    }

    /** Saves [name] (and links the person picked from family or contacts). */
    fun save(id: Long, name: String, picked: DrivePlacesLogic.NameOption?) {
        val clean = name.trim().take(DrivePlacesJson.NAME_MAX)
        if (clean.isEmpty() || _state.value.busy) return
        val before = _state.value.person?.person
        act {
            val contact = picked?.takeIf { DrivePlacesLogic.fold(it.name) == DrivePlacesLogic.fold(clean) }?.let { DPersonContact(it.kind, it.id, it.name) }
            when (val r = api.patchPerson(id, name = clean, contact = contact)) {
                is DResult.Ok -> {
                    val into = r.value.mergedInto
                    val count = r.value.person?.count ?: before?.count ?: 0
                    Done("save", clean, into ?: id, count, undo = if (into == null) ({ undo(id) { api.patchPerson(id, name = before?.name.orEmpty()) } }) else null)
                }
                else -> null.also { _state.value = _state.value.copy(error = r.errorCode()) }
            }
        }
    }

    fun merge(id: Long, into: DSimilar) {
        act {
            when (val r = api.mergePerson(id, into.id)) {
                is DResult.Ok -> Done("merge", r.value.name ?: into.name, r.value.id, r.value.count, undo = null)
                else -> null.also { _state.value = _state.value.copy(error = r.errorCode()) }
            }
        }
    }

    fun hide(id: Long) {
        val count = _state.value.person?.person?.count ?: 0
        act {
            when (val r = api.patchPerson(id, hidden = true)) {
                is DResult.Ok -> Done("hide", null, id, count, undo = { undo(id) { api.patchPerson(id, hidden = false) } })
                else -> null.also { _state.value = _state.value.copy(error = r.errorCode()) }
            }
        }
    }

    private fun act(call: suspend () -> Done?) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            val done = call()
            _state.value = _state.value.copy(busy = false, done = done ?: _state.value.done)
        }
    }

    private fun undo(id: Long, call: suspend () -> DResult<*>) {
        viewModelScope.launch {
            call()
            load(id)
        }
    }

    fun clearError() { _state.value = _state.value.copy(error = null) }
}

/** "Orte und Personen" (Drive › Einstellungen): the person's switches, this phone's "Nur beim Laden", deleting. */
@HiltViewModel
class DrivePlacesSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DriveApi,
    val live: DriveLive,
    private val prefs: DrivePlacesPrefs,
    private val backfill: DriveGeoBackfill,
    private val faceModel: DriveFaceModel,
) : ViewModel() {
    val settings: StateFlow<PlacesSettings> = prefs.settings
    val geo: StateFlow<DrivePlacesPrefs.GeoProgress> = prefs.geo
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun load() {
        prefs.reload()
        viewModelScope.launch {
            when (val r = api.photoSettings()) {
                is DResult.Ok -> prefs.took(r.value)
                else -> if (!prefs.settings.value.known) _message.value = r.errorCode()
            }
        }
    }

    fun canLook(): Boolean = backfill.canLook()

    private fun put(call: suspend () -> DResult<com.gatecontrol.android.drive.DPhotoSettings>, after: (Boolean) -> Unit = {}) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                when (val r = call()) {
                    is DResult.Ok -> prefs.took(r.value).also { after(true) }
                    else -> _message.value = r.errorCode()
                }
            } finally {
                _busy.value = false
            }
        }
    }

    fun setKeepGeo(on: Boolean) = put({ api.putPlacesSettings(keepGeo = on) })

    fun setStripGeo(on: Boolean) = put({ api.putPlacesSettings(stripGeoOnShare = on) })

    fun setKids(on: Boolean) = put({ api.putPlacesSettings(peopleKids = on) })

    /** "Personen erkennen": on starts the phone's job; off deletes every face, person and name on the server. */
    fun setPeople(on: Boolean) = put({ api.putPlacesSettings(people = on) }) { ok ->
        if (!ok) return@put
        if (on) {
            // the feature model first (in WLAN); its job starts the faces' job
            if (faceModel.ready()) DrivePeopleWork.restart(context) else DriveFaceModelWork.start(context)
        } else {
            DrivePeopleWork.cancel(context)
            DriveFaceModelWork.cancelAndDelete(context)
            _message.value = "people_deleted"
        }
    }

    /** The feature model's download (only while "Personen erkennen" is on). */
    val model: kotlinx.coroutines.flow.Flow<FaceModelState> = DriveFaceModelWork.state(context)

    /** "Jetzt über mobile Daten laden" ([mobile]) or "Erneut versuchen". */
    fun loadModel(mobile: Boolean) = if (mobile) DriveFaceModelWork.start(context, mobile = true) else DriveFaceModelWork.retry(context)

    fun setChargingOnly(on: Boolean) {
        prefs.setChargingOnly(on)
        if (prefs.settings.value.people) DrivePeopleWork.restart(context)
    }

    fun fillPlaces() {
        DriveGeoBackfillWork.start(context)
        _message.value = "places_filling"
    }

    /** Every place of the photos this person uploaded goes. */
    fun deletePlaces() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val r = api.deleteGeo()
                _message.value = if (r is DResult.Ok) "places_deleted:${r.value}" else r.errorCode()
            } finally {
                _busy.value = false
            }
        }
    }

    fun clearMessage() { _message.value = null }
}
