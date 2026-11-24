package com.gatecontrol.android.notizen

import com.gatecontrol.android.notizen.NotizenJson.arr
import com.gatecontrol.android.notizen.NotizenJson.bool
import com.gatecontrol.android.notizen.NotizenJson.int
import com.gatecontrol.android.notizen.NotizenJson.long
import com.gatecontrol.android.notizen.NotizenJson.num
import com.gatecontrol.android.notizen.NotizenJson.obj
import com.gatecontrol.android.notizen.NotizenJson.str
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * „Aufgaben und Erinnerungen“, stage 3 (plugin 1.5.0, `places` / `placeSearch` in `GET me` → features): a task may hang
 * on a place. The phone watches the places (geofences) and shows the reminder itself; the server keeps the places, says
 * which ones this phone is to watch and what the notification says — and never learns where the phone is: it gets a
 * position rounded on the phone for the search, and "this place fired" from a trigger.
 */

/** `Task.place`: the place a task hangs on. [mode] [PLACE] (one place) or [ANY] („Jeder Lidl“: no coordinates). */
data class NPlace(
    val mode: String,
    val id: Long?,
    val name: String,
    val address: String?,
    val lat: Double?,
    val lon: Double?,
    val radius: Int?,
    /** `osm:…` (a hit of the search), `familie:home` / `familie:place:<n>`, null (a point, an own place). */
    val ref: String?,
    /** One of „Meine Orte“ of this person. */
    val own: Boolean,
    /** [ENTER] | [EXIT]. */
    val trigger: String,
    /** What the place was chosen for: `kind:<art>` | `name:<name>`; null: an own place, a point, an address. */
    val key: String?,
    val label: String?,
    val by: Long?,
    /** [ACCEPTED] | [PENDING] | [DECLINED]. */
    val consent: String,
    /** This phone is to watch the place (what counts for the geofences is `POST places/watch`). */
    val watch: Boolean,
    /** The task also speaks up at other places of the kind. */
    val nearby: Boolean,
    val skipped: Int,
    val later: String?,
    /** Plugin 1.6.0 (`placeMine`): the phone of THIS person is to watch the place (null: an older plugin). */
    val mine: Boolean? = null,
    /** Plugin 1.6.0 (`placeMine`): this person may agree or decline (null: an older plugin — the app guesses). */
    val canConsent: Boolean? = null,
) {
    val any: Boolean get() = mode == ANY

    companion object {
        const val PLACE = "place"
        const val ANY = "any"
        const val ENTER = "enter"
        const val EXIT = "exit"
        const val ACCEPTED = "accepted"
        const val PENDING = "pending"
        const val DECLINED = "declined"
    }
}

/** What a place is remembered for (`favFor`, `favs`). */
data class NPlaceKey(val key: String, val label: String)

/**
 * A place to choose: one of „Meine Orte“ ([placeId]), a place of the family ([familyId]), a hit of the search ([ref]) or
 * a point of the map (none of the three). [distanceM] / [minutes] as the server estimated them from the rounded
 * position — or as the phone worked them out again ([PlaceLogic.measured]).
 */
data class NSpot(
    val name: String,
    val address: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val radius: Int? = null,
    val placeId: Long? = null,
    val familyId: String? = null,
    val ref: String? = null,
    /** An own place the person named. */
    val named: Boolean = false,
    val favFor: List<NPlaceKey> = emptyList(),
    val distanceM: Int? = null,
    val minutes: Int? = null,
    /** The remembered place of the kind searched for. */
    val fav: Boolean = false,
    val uses: Int = 0,
) {
    val family: Boolean get() = familyId != null

    /** Tells the choices of a dialog apart. */
    val uid: String get() = placeId?.let { "p:$it" } ?: familyId?.let { "f:$it" } ?: ref ?: "x:$lat,$lon"
}

/** `GET places`: „Meine Orte“, the places of the family, the remembered place per kind. */
data class NPlaces(val places: List<NSpot> = emptyList(), val family: List<NSpot> = emptyList(), val favs: List<NFav> = emptyList())

data class NFav(val key: String, val label: String, val placeId: Long, val uses: Int)

/** `place` of `POST parse`: the place the typed line names. */
data class NPlaceSuggest(
    /** `own` | `family` | `kind` | `address` | `name`. */
    val via: String,
    /** The words in the line, as written („beim Baumarkt“). */
    val word: String?,
    val query: String?,
    val key: String?,
    val label: String,
    /** The title without the words of the place. */
    val title: String?,
    /** A place is surely meant; false: show it as a suggestion, do not take it. */
    val certain: Boolean,
    val trigger: String,
    /** Decided already: the own place, the place of the family, the remembered one of [key]. */
    val place: NSpot?,
    val uses: Int,
    /** The own places chosen for [key] before, the remembered one first. */
    val options: List<NSpot>,
    /** `on` | `off` | `unavailable`: may `places/search` be asked. */
    val search: String,
)

/** What `POST parse` says about a line: the list ([NAttach], 1.4.0), the place (1.5.0) and the title the server read. */
data class NParsed(val attach: NAttach?, val place: NPlaceSuggest?, val title: String?)

data class NAttribution(val text: String, val url: String?)

/** `POST places/search`. [kind] `kind` | `name` | `address`. */
data class NPlaceSearch(
    val key: String?,
    val label: String?,
    val kind: String?,
    val radiusKm: Int,
    val results: List<NSpot>,
    /** „Jeder Baumarkt“ may be offered. */
    val any: Boolean,
    val attribution: NAttribution?,
)

/** Quiet hours for the hint „in der Nähe“ (`HH:MM`, over midnight). */
data class NQuiet(val from: String, val to: String)

/** `me.places` — what the server offers and how this person set it. */
data class NPlacesInfo(
    /** [ON] | `off` | `unavailable` — the one field the app asks. */
    val searchState: String = "off",
    /** The administrator's switch (false: the person cannot switch the search on). */
    val searchEnabled: Boolean = false,
    val searchMine: Boolean = true,
    val radiusKm: Int = 15,
    /** The phone rounds a position to this many decimals before it is sent. */
    val decimals: Int = 2,
    val attribution: NAttribution? = null,
    val nearby: Boolean = true,
    val quiet: NQuiet? = null,
    val perTask: Int = 5,
    val radiusMin: Int = 50,
    val radiusMax: Int = 2000,
    val radiusDefault: Int = 150,
    val family: Boolean = false,
) {
    val searchOn: Boolean get() = searchState == ON

    companion object {
        const val ON = "on"
    }
}

/** One task of a fence: what the notification says, ready to show. [kind] [PLACE] | [NEARBY]. */
data class NFenceTask(
    val taskId: Long,
    val listId: Long?,
    val area: String?,
    val title: String,
    val kind: String,
    val trigger: String,
    /** `nearby`: the place that was planned. */
    val planned: String?,
    val tag: String,
    val important: Boolean,
    val notifyTitle: String,
    val notifyBody: String,
    val collapseKey: String,
    val actions: List<NReminderAction>,
    val attachedList: NAttached?,
) {
    val nearby: Boolean get() = kind == NEARBY

    companion object {
        const val PLACE = "place"
        const val NEARBY = "nearby"
    }
}

/** One place this phone is to watch (`fences[]` of `POST places/watch`): one geofence, however many tasks hang on it. */
data class NFence(
    val id: String,
    val ref: String?,
    val lat: Double,
    val lon: Double,
    val radius: Int,
    val name: String,
    val address: String?,
    val enter: Boolean,
    val exit: Boolean,
    val tasks: List<NFenceTask>,
)

/** `POST places/watch`. [cellLat] / [cellLon]: the rounded position the additional places were chosen for. */
data class NWatch(
    val enabled: Boolean = false,
    val max: Int = 0,
    val refreshM: Int = 3000,
    val cellLat: Double? = null,
    val cellLon: Double? = null,
    val nearby: Boolean = true,
    val quiet: NQuiet? = null,
    val search: String = "off",
    val dropped: Int = 0,
    val fences: List<NFence> = emptyList(),
)

/** `POST places/fired` → may the reminder be shown; [reason] when not. */
data class NFired(val notify: Boolean, val reason: String?)


/** Reading the answers of stage 3 (tolerant: what cannot be read is left out). */
object NotizenPlacesJson {
    private val FENCE = Regex("^(?:p:\\d{1,15}|osm:[NWR]\\d{1,18})$")
    private val REF = Regex("^(?:osm:[NWR]\\d{1,18}|familie:[a-z0-9:]{1,40})$")
    private val FAMILY_ID = Regex("^[a-z0-9:]{1,40}$")
    private val KEY = Regex("^(?:kind|name):.{1,80}$")
    private val TAG = Regex("^place:[1-9]\\d{0,14}:.{1,40}$")
    private val COLLAPSE = Regex("^task:[1-9]\\d{0,14}$")
    private val STATES = setOf("on", "off", "unavailable")

    fun fenceId(v: String?): String? = v?.takeIf { FENCE.matches(it) }
    fun key(v: String?): String? = v?.takeIf { KEY.matches(it) }
    private fun trigger(v: String?): String = if (v == NPlace.EXIT) NPlace.EXIT else NPlace.ENTER
    private fun JsonObject.id(k: String): Long? = long(k)?.takeIf { it > 0 }
    private fun JsonObject.text(k: String, max: Int): String? = str(k)?.take(max)
    private fun JsonObject.lat(): Double? = num("lat")?.takeIf { it in -90.0..90.0 }
    private fun JsonObject.lon(): Double? = num("lon")?.takeIf { it in -180.0..180.0 }
    private fun <T> JsonArray?.each(f: (JsonObject) -> T?): List<T> = this?.mapNotNull { e -> (e as? JsonObject)?.let { runCatching { f(it) }.getOrNull() } }.orEmpty()

    /** `Task.place`; null: no place (also with an older plugin, which has no such field). */
    fun place(o: JsonObject?): NPlace? {
        if (o == null) return null
        val mode = if (o.str("mode") == NPlace.ANY) NPlace.ANY else NPlace.PLACE
        val lat = o.lat()
        val lon = o.lon()
        if (mode == NPlace.PLACE && (lat == null || lon == null)) return null
        return NPlace(
            mode = mode,
            id = o.id("id"),
            name = o.text("name", 80)?.takeIf { it.isNotBlank() } ?: return null,
            address = o.text("address", 160)?.takeIf { it.isNotBlank() },
            lat = lat,
            lon = lon,
            radius = o.int("radius")?.coerceIn(1, 100_000),
            ref = o.str("ref")?.takeIf { REF.matches(it) },
            own = o.bool("own") ?: false,
            trigger = trigger(o.str("trigger")),
            key = key(o.str("key")),
            label = o.text("label", 80)?.takeIf { it.isNotBlank() },
            by = o.id("by"),
            consent = o.str("consent")?.takeIf { it == NPlace.PENDING || it == NPlace.DECLINED } ?: NPlace.ACCEPTED,
            watch = o.bool("watch") ?: false,
            nearby = o.bool("nearby") ?: false,
            skipped = o.int("skipped")?.coerceAtLeast(0) ?: 0,
            later = o.text("later", 40),
            mine = o.bool("mine"),
            canConsent = o.bool("canConsent"),
        )
    }

    private fun keys(a: JsonArray?): List<NPlaceKey> = a.each { k -> NPlaceKey(key(k.str("key")) ?: return@each null, k.text("label", 80) ?: return@each null) }

    /** A place to choose: an own one, one of the family, a hit; without a name or a position it is none. */
    fun spot(o: JsonObject?): NSpot? {
        if (o == null) return null
        val familyId = o.str("familyId")?.takeIf { FAMILY_ID.matches(it) }
        return NSpot(
            name = o.text("name", 80)?.takeIf { it.isNotBlank() } ?: return null,
            address = o.text("address", 160)?.takeIf { it.isNotBlank() },
            lat = o.lat() ?: return null,
            lon = o.lon() ?: return null,
            radius = o.int("radius")?.coerceIn(1, 100_000),
            // a hit that is one of the own places already carries `placeId`; an own place its `id`
            placeId = if (familyId != null) null else o.id("placeId") ?: o.id("id"),
            familyId = familyId,
            ref = o.str("ref")?.takeIf { REF.matches(it) },
            named = o.bool("named") ?: false,
            favFor = keys(o.arr("favFor")),
            distanceM = o.int("distanceM")?.coerceAtLeast(0),
            minutes = o.int("minutes")?.coerceAtLeast(0),
            fav = o.bool("fav") ?: false,
            uses = o.int("uses")?.coerceAtLeast(0) ?: 0,
        )
    }

    /** `GET places`. */
    fun places(o: JsonObject): NPlaces = NPlaces(
        places = o.arr("places").each(::spot).filter { it.placeId != null }.take(200),
        family = o.arr("family").each(::spot).filter { it.familyId != null }.take(100),
        favs = favs(o),
    )

    /** `favs` of `GET places` / `PUT places/favs`. */
    fun favs(o: JsonObject): List<NFav> = o.arr("favs").each { f ->
        NFav(key(f.str("key")) ?: return@each null, f.text("label", 80).orEmpty(), f.id("placeId") ?: return@each null, f.int("uses")?.coerceAtLeast(0) ?: 0)
    }

    /** `{ place }` of `POST places` / `PATCH places/<id>`. */
    fun placeAnswer(o: JsonObject): NSpot? = spot(o.obj("place"))?.takeIf { it.placeId != null }

    /** `place` of `POST parse`; null: the line names no place (or an older plugin). */
    fun suggest(o: JsonObject?): NPlaceSuggest? {
        if (o == null) return null
        val via = o.str("via")?.takeIf { it in setOf("own", "family", "kind", "address", "name") } ?: return null
        val label = (o.text("label", 80) ?: o.text("query", 80))?.takeIf { it.isNotBlank() } ?: return null
        return NPlaceSuggest(
            via = via,
            word = o.text("word", 120)?.takeIf { it.isNotBlank() },
            query = o.text("query", 80)?.takeIf { it.isNotBlank() },
            key = key(o.str("key")),
            label = label,
            title = o.text("title", 500)?.takeIf { it.isNotBlank() },
            certain = o.bool("certain") ?: false,
            trigger = trigger(o.str("trigger")),
            place = spot(o.obj("place")),
            uses = o.int("uses")?.coerceAtLeast(0) ?: 0,
            options = o.arr("options").each(::spot).filter { it.placeId != null }.take(20),
            search = o.str("search")?.takeIf { it in STATES } ?: "off",
        )
    }

    /** The whole answer of `POST parse` as far as the app uses it. */
    fun parsed(o: JsonObject): NParsed = NParsed(NotizenJson.attach(o.obj("attach")), suggest(o.obj("place")), o.obj("parsed")?.str("title"))

    private fun attribution(o: JsonObject?): NAttribution? = o?.let { a ->
        NAttribution(a.text("text", 80)?.takeIf { it.isNotBlank() } ?: return null, a.str("url")?.takeIf { it.startsWith("https://") && it.length < 300 })
    }

    /** `POST places/search`. */
    fun search(o: JsonObject): NPlaceSearch = NPlaceSearch(
        key = key(o.str("key")),
        label = o.text("label", 80)?.takeIf { it.isNotBlank() },
        kind = o.str("kind")?.takeIf { it == "kind" || it == "name" || it == "address" },
        radiusKm = o.int("radiusKm")?.coerceIn(1, 500) ?: 15,
        results = o.arr("results").each(::spot).take(10),
        any = o.bool("any") ?: false,
        attribution = attribution(o.obj("attribution")),
    )

    private fun quiet(o: JsonObject?): NQuiet? = o?.let { q ->
        val from = NotizenJson.time(q.str("from")) ?: return null
        val to = NotizenJson.time(q.str("to")) ?: return null
        if (from == to) null else NQuiet(from, to)
    }

    /** `me.places`; null with an older plugin. */
    fun info(o: JsonObject?): NPlacesInfo? = o?.let { p ->
        val s = p.obj("search")
        val r = p.obj("radius")
        NPlacesInfo(
            searchState = s?.str("state")?.takeIf { it in STATES } ?: "off",
            searchEnabled = s?.bool("enabled") ?: false,
            searchMine = s?.bool("mine") ?: true,
            radiusKm = s?.int("radiusKm")?.coerceIn(1, 500) ?: 15,
            decimals = s?.int("decimals")?.coerceIn(0, 3) ?: 2,
            attribution = attribution(s?.obj("attribution")),
            nearby = p.bool("nearby") ?: true,
            quiet = quiet(p.obj("quiet")),
            perTask = p.int("perTask")?.coerceIn(0, 20) ?: 5,
            radiusMin = r?.int("min")?.coerceIn(10, 100_000) ?: 50,
            radiusMax = r?.int("max")?.coerceIn(10, 100_000) ?: 2000,
            radiusDefault = r?.int("default")?.coerceIn(10, 100_000) ?: 150,
            family = p.bool("family") ?: false,
        )
    }

    private fun fenceTask(t: JsonObject): NFenceTask? {
        val n = t.obj("notification") ?: return null
        val title = t.text("title", 120) ?: return null
        return NFenceTask(
            taskId = t.id("taskId") ?: return null,
            listId = t.id("listId"),
            area = t.str("area")?.takeIf { it == NArea.PRIVATE || it == NArea.WORK },
            title = title,
            kind = if (t.str("kind") == NFenceTask.NEARBY) NFenceTask.NEARBY else NFenceTask.PLACE,
            trigger = trigger(t.str("trigger")),
            planned = t.text("planned", 80)?.takeIf { it.isNotBlank() },
            tag = t.str("tag")?.takeIf { TAG.matches(it) } ?: return null,
            important = t.bool("important") ?: false,
            notifyTitle = n.text("title", 120)?.takeIf { it.isNotBlank() } ?: title,
            notifyBody = n.text("body", 300).orEmpty(),
            collapseKey = n.str("collapseKey")?.takeIf { COLLAPSE.matches(it) } ?: return null,
            actions = n.arr("actions").each { a -> NReminderAction(a.text("id", 40) ?: return@each null, a.text("label", 60)?.takeIf { it.isNotBlank() } ?: return@each null) }.take(4),
            attachedList = NotizenJson.attached(t.obj("attachedList")),
        )
    }

    private fun fence(f: JsonObject): NFence? {
        val transitions = f.arr("transitions")?.mapNotNull { runCatching { it.asString }.getOrNull() }.orEmpty()
        val tasks = f.arr("tasks").each(::fenceTask).take(40)
        if (tasks.isEmpty()) return null
        return NFence(
            id = fenceId(f.str("id")) ?: return null,
            ref = f.str("ref")?.takeIf { REF.matches(it) },
            lat = f.lat() ?: return null,
            lon = f.lon() ?: return null,
            radius = f.int("radius")?.coerceIn(30, 5000) ?: return null,
            name = f.text("name", 80).orEmpty(),
            address = f.text("address", 160)?.takeIf { it.isNotBlank() },
            enter = NPlace.ENTER in transitions,
            exit = NPlace.EXIT in transitions,
            tasks = tasks,
        ).takeIf { it.enter || it.exit }
    }

    /** `POST places/watch`. */
    fun watch(o: JsonObject): NWatch {
        val cell = o.obj("cell")
        return NWatch(
            enabled = o.bool("enabled") ?: false,
            max = o.int("max")?.coerceIn(0, 100) ?: 0,
            refreshM = o.int("refreshM")?.coerceIn(500, 100_000) ?: 3000,
            cellLat = cell?.lat(),
            cellLon = cell?.lon(),
            nearby = o.bool("nearby") ?: true,
            quiet = quiet(o.obj("quiet")),
            search = o.str("search")?.takeIf { it in STATES } ?: "off",
            dropped = o.int("dropped")?.coerceAtLeast(0) ?: 0,
            fences = o.arr("fences").each(::fence).distinctBy { it.id }.take(100),
        )
    }

    /** `POST places/fired`. */
    fun fired(o: JsonObject): NFired = NFired(o.bool("notify") ?: false, o.text("reason", 20))

    // ── bodies ──────────────────────────────────────────────

    /** `{ lat, lon }` — rounded already ([PlaceLogic.near]). */
    private fun nearBody(near: Pair<Double, Double>) = JsonObject().apply { addProperty("lat", near.first); addProperty("lon", near.second) }

    /** `POST places/search { key | q, near }`: [near] rounded on the phone; without it near [nearSpot] (one of my places), else only an address can be looked up. */
    fun searchBody(key: String?, q: String?, near: Pair<Double, Double>?, nearSpot: NSpot? = null) = JsonObject().apply {
        if (key != null) addProperty("key", key) else addProperty("q", q.orEmpty().trim().take(80))
        when {
            near != null -> add("near", nearBody(near))
            // the phone has no position: near one of the person's places, as the portal searches
            nearSpot?.familyId != null -> addProperty("nearFamilyId", nearSpot.familyId)
            nearSpot?.placeId != null -> addProperty("nearPlaceId", nearSpot.placeId)
        }
    }

    /** `POST places/watch { near?, budget }`. */
    fun watchBody(near: Pair<Double, Double>?, budget: Int) = JsonObject().apply {
        near?.let { add("near", nearBody(it)) }
        addProperty("budget", budget.coerceAtLeast(0))
    }

    fun firedBody(taskId: Long, fence: String, transition: String) = JsonObject().apply {
        addProperty("taskId", taskId); addProperty("fence", fence); addProperty("transition", transition)
    }

    fun skipBody(fence: String) = JsonObject().apply { addProperty("fence", fence) }

    fun consentBody(accept: Boolean) = JsonObject().apply { addProperty("accept", accept) }

    /** `POST places { name, lat, lon, address?, radius? }` — a named place of mine. */
    fun newPlaceBody(name: String, lat: Double, lon: Double, address: String?, radius: Int?, ref: String?) = JsonObject().apply {
        addProperty("name", name.trim().take(80))
        addProperty("lat", lat)
        addProperty("lon", lon)
        address?.takeIf { it.isNotBlank() }?.let { addProperty("address", it.take(160)) }
        radius?.let { addProperty("radius", it) }
        ref?.let { addProperty("ref", it) }
    }

    fun favBody(key: String, placeId: Long?) = JsonObject().apply {
        addProperty("key", key)
        if (placeId != null) addProperty("placeId", placeId) else add("placeId", JsonNull.INSTANCE)
    }

    /** `PUT prefs { places: { … } }` — only what is given (the server merges). [quiet]: a pair to set, `false` to clear. */
    fun placePrefsBody(search: Boolean? = null, nearby: Boolean? = null, quiet: NQuiet? = null, noQuiet: Boolean = false) = JsonObject().apply {
        add("places", JsonObject().apply {
            search?.let { addProperty("search", it) }
            nearby?.let { addProperty("nearby", it) }
            if (noQuiet) add("quiet", JsonNull.INSTANCE)
            else quiet?.let { q -> add("quiet", JsonObject().apply { addProperty("from", q.from); addProperty("to", q.to) }) }
        })
    }

    /**
     * `place` of `POST tasks` / `PATCH tasks/<id>`: [any] („Jeder Lidl“, needs [key]) or [spot] — one of my places
     * (`placeId`), a place of the family (`familyId`), a hit of the search with its fields unchanged, or a point of the
     * map. [remember]: the place becomes the remembered one of [key] (only with a key and one place). null: nothing to
     * send (neither a place nor „Jeder …“).
     */
    fun placeArg(spot: NSpot?, any: Boolean, key: String?, label: String?, trigger: String, remember: Boolean = false): JsonObject? {
        val o = JsonObject()
        when {
            any -> {
                o.addProperty("any", true)
                o.addProperty("key", key ?: return null)
            }
            spot == null -> return null
            spot.familyId != null -> o.addProperty("familyId", spot.familyId)
            spot.placeId != null -> o.addProperty("placeId", spot.placeId)
            else -> {
                spot.ref?.let { o.addProperty("ref", it) }
                o.addProperty("name", spot.name)
                spot.address?.let { o.addProperty("address", it) }
                o.addProperty("lat", spot.lat ?: return null)
                o.addProperty("lon", spot.lon ?: return null)
                spot.radius?.let { o.addProperty("radius", it) }
            }
        }
        if (!any && spot?.familyId == null) key?.let { o.addProperty("key", it) }
        if (key != null) label?.let { o.addProperty("label", it) }
        o.addProperty("trigger", if (trigger == NPlace.EXIT) NPlace.EXIT else NPlace.ENTER)
        if (remember && !any && key != null && spot?.familyId == null) o.addProperty("remember", true)
        return o
    }

    /** Only the trigger of the place a task has already. */
    fun triggerArg(trigger: String) = JsonObject().apply { addProperty("trigger", if (trigger == NPlace.EXIT) NPlace.EXIT else NPlace.ENTER) }
}
