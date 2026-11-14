package com.gatecontrol.android.ui.notizen

import com.gatecontrol.android.notizen.NAttribution
import com.gatecontrol.android.notizen.NFeature
import com.gatecontrol.android.notizen.NMe
import com.gatecontrol.android.notizen.NPlace
import com.gatecontrol.android.notizen.NPlaceSuggest
import com.gatecontrol.android.notizen.NPlaces
import com.gatecontrol.android.notizen.NPlacesInfo
import com.gatecontrol.android.notizen.NResult
import com.gatecontrol.android.notizen.NSpot
import com.gatecontrol.android.notizen.NotizenJson
import com.gatecontrol.android.notizen.NotizenPlacesJson
import com.gatecontrol.android.notizen.NotizenRepository
import com.gatecontrol.android.notizen.PlaceLogic
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the choice „Welcher Baumarkt?“ shows (plugin 1.5.0; see [PlaceDialog]). */
data class PlaceChoice(
    val open: Boolean = false,
    /** „Baumarkt“, „Lidl“ — what is looked for; null: just „Ort wählen“. */
    val label: String? = null,
    val key: String? = null,
    val query: String = "",
    /** `on` | `off` | `unavailable` — only with `on` a search is offered. */
    val searchState: String = "off",
    val places: NPlaces = NPlaces(),
    /** The own places chosen for [key] before (from `POST parse`). */
    val options: List<NSpot> = emptyList(),
    /** The hits of the last search; null: not searched yet. */
    val results: List<NSpot>? = null,
    /** „Jeder Baumarkt“ may be offered. */
    val anyOffered: Boolean = false,
    val searching: Boolean = false,
    /** `none` | `off` | `down` | `busy` | `near` | `offline`. */
    val problem: String? = null,
    val radiusKm: Int = 15,
    val attribution: NAttribution? = null,
    val selected: NSpot? = null,
    val any: Boolean = false,
    val trigger: String = NPlace.ENTER,
    val remember: Boolean = false,
    /** The task has a place: „Ohne Ort“ is offered. */
    val canRemove: Boolean = false,
    /** A point the person set on the map in this dialog. */
    val point: NSpot? = null,
    val lang: String = "de",
) {
    val searchOn: Boolean get() = searchState == NPlacesInfo.ON
}

/** What the person decided in the choice: [arg] is `place` of `POST` / `PATCH tasks`; [name] what the chip shows. */
data class PlacePicked(val arg: JsonObject, val name: String, val trigger: String, val spot: NSpot? = null)

/** The rules of the choice (pure, unit-tested). */
object PlaceChoices {
    /** The id of the choice that stands for the place the task has already. */
    const val CURRENT = "current"

    /** The place a task has as a choice (not for „Jeder …“). */
    fun currentSpot(p: NPlace?): NSpot? = p?.takeIf { !it.any }?.let { NSpot(name = it.name, address = it.address, lat = it.lat, lon = it.lon, radius = it.radius, ref = CURRENT) }

    /**
     * „Meine Orte“ of the dialog: the place the task has, the ones chosen for this kind before, the places of the
     * family, the own places — each once, and none that is among the hits above.
     */
    fun mine(c: PlaceChoice): List<NSpot> {
        val hits = c.results.orEmpty().mapNotNull { it.placeId }.toSet()
        val current = c.point?.takeIf { it.ref == CURRENT }
        val all = listOfNotNull(c.point) + c.options + c.places.family + c.places.places
        return all.distinctBy { it.uid }.filter { it.placeId == null || it.placeId !in hits }
            // the place the task has is one of „Meine Orte“ as well: once
            .filter { current == null || it.ref == CURRENT || !(it.name == current.name && it.lat == current.lat && it.lon == current.lon) }
    }

    /** May „Als meinen Ort für … merken“ be offered: a kind or name is known and one place of my own or of the search is chosen. */
    fun canRemember(c: PlaceChoice): Boolean {
        val s = c.selected ?: return false
        return !c.any && c.key != null && s.familyId == null && s.ref != CURRENT && !s.fav
    }

    /** The key goes with a hit of the search and with a place known for this kind — not with „Oma“ picked for „Baumarkt“. */
    private fun keyFor(c: PlaceChoice, s: NSpot): String? {
        val key = c.key ?: return null
        val hit = c.results.orEmpty().any { it.uid == s.uid } || c.options.any { it.uid == s.uid }
        return key.takeIf { hit || s.favFor.any { it.key == key } }
    }

    /** What is sent: null while nothing is chosen. The place the task has already: only its trigger. */
    fun picked(c: PlaceChoice, anyName: (String) -> String): PlacePicked? {
        if (c.any) {
            val label = c.label ?: return null
            return NotizenPlacesJson.placeArg(null, true, c.key, label, c.trigger)?.let { PlacePicked(it, anyName(label), c.trigger) }
        }
        val s = c.selected ?: return null
        if (s.ref == CURRENT) return PlacePicked(NotizenPlacesJson.triggerArg(c.trigger), s.name, c.trigger)
        val key = keyFor(c, s)
        return NotizenPlacesJson.placeArg(s, false, key, c.label.takeIf { key != null }, c.trigger, c.remember && canRemember(c) && key != null)
            ?.let { PlacePicked(it, s.name, c.trigger, s) }
    }

    /** The search word as typed is the kind the dialog was opened for: ask by its key (the server's own word table). */
    fun byKey(c: PlaceChoice): String? = c.key?.takeIf { c.query.isBlank() || c.query.trim().equals(c.label, ignoreCase = true) }

    /** Without a position: near one of my places, as the portal searches — „Zuhause“ first. */
    fun nearSpot(p: NPlaces): NSpot? = p.family.firstOrNull { it.familyId == "home" } ?: p.family.firstOrNull() ?: p.places.firstOrNull { it.named } ?: p.places.firstOrNull()
}

/**
 * The choice of a place for a task, shared by the task's page and the quick entry: „Meine Orte“ (`GET places`) and — only
 * when the person presses „Suchen“ or opened the choice from the suggested chip — `POST places/search`. Never while
 * typing, never by itself in intervals; never a map service directly. [position]: the phone's exact position, which
 * stays on the phone (it sorts the hits); what is sent is rounded ([PlaceLogic.near]).
 */
class PlaceChooser(
    private val repo: NotizenRepository,
    private val scope: CoroutineScope,
    private val position: suspend () -> Pair<Double, Double>?,
) {
    private val _ui = MutableStateFlow(PlaceChoice())
    val ui: StateFlow<PlaceChoice> = _ui.asStateFlow()
    private var job: Job? = null

    /**
     * Opens the choice: for the place a task has ([current]) or for what the typed line names ([suggest]). [search]:
     * look for [suggest]'s kind at once (the chip was tapped — that is the confirmation the search waits for).
     */
    fun open(current: NPlace?, suggest: NPlaceSuggest?, me: NMe?, search: Boolean = false, picked: NSpot? = null, trigger: String? = null, removable: Boolean = current != null) {
        job?.cancel()
        val state = when {
            me?.has(NFeature.PLACE_SEARCH) != true -> "off"
            else -> suggest?.search ?: me.places?.searchState ?: "off"
        }
        val label = suggest?.label ?: current?.label
        val key = suggest?.key ?: current?.key
        val cur = PlaceChoices.currentSpot(current)
        _ui.value = PlaceChoice(
            open = true,
            label = label,
            key = key,
            query = label.orEmpty(),
            searchState = state,
            options = suggest?.options.orEmpty(),
            radiusKm = me?.places?.radiusKm ?: 15,
            attribution = me?.places?.attribution,
            selected = picked ?: cur ?: suggest?.place,
            any = current?.any == true,
            anyOffered = current?.any == true,
            trigger = trigger ?: current?.trigger ?: suggest?.trigger ?: NPlace.ENTER,
            canRemove = removable,
            point = cur ?: picked?.takeIf { it.placeId == null && it.familyId == null && it.ref == null },
            lang = me?.lang ?: "de",
        )
        scope.launch { (repo.api.places() as? NResult.Ok)?.value?.let { p -> _ui.update { it.copy(places = p) } } }
        if (search && state == NPlacesInfo.ON && key != null && picked == null && suggest?.place == null) search()
    }

    fun close() {
        job?.cancel()
        _ui.update { it.copy(open = false, searching = false) }
    }

    fun setQuery(q: String) = _ui.update { it.copy(query = q.take(80)) }
    fun select(s: NSpot) = _ui.update { it.copy(selected = s, any = false) }
    fun selectAny() = _ui.update { it.copy(any = true, selected = null) }
    fun setTrigger(t: String) = _ui.update { it.copy(trigger = if (t == NPlace.EXIT) NPlace.EXIT else NPlace.ENTER) }
    fun setRemember(v: Boolean) = _ui.update { it.copy(remember = v) }

    /** A point set on the map: a choice of its own, taken at once. */
    fun setPoint(name: String, lat: Double, lon: Double) {
        val s = NSpot(name = name.trim().take(80), lat = lat, lon = lon)
        if (s.name.isBlank()) return
        _ui.update { it.copy(point = s, selected = s, any = false) }
    }

    /** „Suchen“ was pressed (or the suggested chip tapped): one question to the own server. */
    fun search() {
        val c = _ui.value
        if (c.searching || !c.searchOn) return
        val key = PlaceChoices.byKey(c)
        val q = c.query.trim()
        if (key == null && q.length < 2) return
        job?.cancel()
        job = scope.launch {
            _ui.update { it.copy(searching = true, problem = null) }
            val pos = runCatching { position() }.getOrNull()
            val decimals = repo.cachedMe()?.places?.decimals ?: 2
            val near = PlaceLogic.near(pos?.first, pos?.second, decimals)
            val r = repo.api.placeSearch(key, q.takeIf { key == null }, near, if (near == null) PlaceChoices.nearSpot(_ui.value.places) else null)
            _ui.update { u ->
                when (r) {
                    is NResult.Ok -> {
                        val v = r.value
                        u.copy(
                            searching = false,
                            results = PlaceLogic.measured(v.results, pos?.first, pos?.second),
                            // an address has no kind: nothing to remember it for, no „Jeder …“
                            key = if (v.kind == "address") null else v.key ?: u.key,
                            label = v.label ?: u.label ?: q.takeIf { it.isNotEmpty() },
                            anyOffered = v.any && v.key != null,
                            any = false,
                            radiusKm = v.radiusKm,
                            attribution = v.attribution ?: u.attribution,
                            problem = if (v.results.isEmpty()) "none" else null,
                        )
                    }
                    is NResult.Failed -> u.copy(
                        searching = false,
                        results = if (r.http == 503) emptyList() else u.results,
                        searchState = if (r.code == "module_off") "off" else u.searchState,
                        problem = when {
                            r.code == "module_off" -> "off"
                            r.http == 429 -> "busy"
                            r.http == 400 && r.body?.let { b -> runCatching { b.get("field").asString }.getOrNull() } == "near" -> "near"
                            r.http == 404 && NotizenJson.reason(r.body) == null && r.code != "not_found" -> "off"
                            r.http == 404 -> "near"
                            else -> "down"
                        },
                    )
                    else -> u.copy(searching = false, problem = "offline")
                }
            }
        }
    }

    /** „Speichern“: what was chosen (null: nothing yet); the choice closes. */
    fun confirm(anyName: (String) -> String): PlacePicked? = PlaceChoices.picked(_ui.value, anyName)?.also { close() }
}
