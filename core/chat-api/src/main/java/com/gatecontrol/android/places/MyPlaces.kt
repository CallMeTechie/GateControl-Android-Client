package com.gatecontrol.android.places

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/*
 * GateControl 1.299 „Meine Orte“: the own places of the profile (`GET /client/me/profile/places`). The server keeps them,
 * the plugins (Messenger, Familie, Notizen) only keep the reference `profile:<id>`. Pure, unit-tested: parsing the
 * answers, the bodies of a change, the copy on the phone (offline choice).
 */

/** The seven symbols of a place (server: `options.icons`). */
object PlaceIcons {
    const val HOME = "home"
    const val WORK = "work"
    const val SCHOOL = "school"
    const val GYM = "gym"
    const val SHOP = "shop"
    const val FAMILY = "family"
    const val PIN = "pin"
    val ALL = listOf(HOME, WORK, SCHOOL, GYM, SHOP, FAMILY, PIN)

    fun valid(s: String?): String = s?.takeIf { it in ALL } ?: PIN
}

/** Who sees a place: only me, the family, everyone on the server. */
object PlaceVisibility {
    const val SELF = "self"
    const val FAMILY = "family"
    const val ALL = "all"
    val ALL_VALUES = listOf(SELF, FAMILY, ALL)

    fun valid(s: String?): String = s?.takeIf { it in ALL_VALUES } ?: SELF
}

/** „Genutzt von“: [count] uses of [kind] (`task`, `location_message`, `reminder`, `rule`, …) in [plugin]. */
data class PlaceUse(val plugin: String, val pluginName: String?, val kind: String, val count: Int)

/** One of „Meine Orte“. [lat]/[lon] null: no point yet (an old copy, Bild 4) — nothing watches it until it is set. */
data class MyPlace(
    val id: Long,
    val name: String,
    val icon: String = PlaceIcons.PIN,
    val lat: Double?,
    val lon: Double?,
    val radiusM: Int = MyPlaces.RADIUS_DEFAULT,
    val address: String? = null,
    val visibility: String = PlaceVisibility.SELF,
    val source: String = MyPlaces.SOURCE_MANUAL,
    val usedBy: List<PlaceUse> = emptyList(),
    val updatedAt: String? = null,
    val lastUsedAt: String? = null,
    /** A place of someone else (`?shared=1`): read only. */
    val ownerName: String? = null,
    val own: Boolean = true,
) {
    val ref: String get() = MyPlaces.ref(id)
    val hasPoint: Boolean get() = MyPlaces.hasPoint(lat, lon)
    val usedCount: Int get() = usedBy.sumOf { it.count }
}

/** `limits` of `GET profile/places`. */
data class PlacesLimits(
    val places: Int = 100,
    val name: Int = 60,
    val address: Int = 200,
    val radiusMin: Int = MyPlaces.RADIUS_MIN,
    val radiusMax: Int = MyPlaces.RADIUS_MAX,
    val radiusDefault: Int = MyPlaces.RADIUS_DEFAULT,
)

/** `GET profile/places`: the own places, whether the person is in a family, the limits and the address search. */
data class PlacesList(
    val places: List<MyPlace>,
    val family: Boolean = false,
    val limits: PlacesLimits = PlacesLimits(),
    /** `osm` | `own` | `off` — `off`: the server does not search, the phone's own geocoder is the fallback. */
    val search: String = MyPlaces.SEARCH_OFF,
)

/** A hit of `GET places/search` (or of the phone's geocoder). */
data class AddressHit(val name: String, val address: String?, val lat: Double, val lon: Double, val distanceM: Int? = null, val osm: Boolean = true)

/** What the editor sends. */
data class PlaceDraft(
    val name: String = "",
    val icon: String = PlaceIcons.PIN,
    val lat: Double? = null,
    val lon: Double? = null,
    val radiusM: Int = MyPlaces.RADIUS_DEFAULT,
    val address: String? = null,
    val visibility: String = PlaceVisibility.SELF,
)

object MyPlaces {
    const val RADIUS_MIN = 50
    const val RADIUS_MAX = 2000
    const val RADIUS_DEFAULT = 150
    const val RADIUS_STEP = 50
    const val NAME_MAX = 60
    const val ADDRESS_MAX = 200
    const val SEARCH_OSM = "osm"
    const val SEARCH_OWN = "own"
    const val SEARCH_OFF = "off"
    const val SOURCE_MANUAL = "manual"
    const val SEARCH_MIN = 3
    const val SEARCH_MAX = 120

    // the kinds of „Genutzt von“ the app names (others: their number)
    const val KIND_TASK = "task"
    const val KIND_LOCATION_MESSAGE = "location_message"
    const val KIND_REMINDER = "reminder"
    const val KIND_RULE = "rule"

    private const val REF_PREFIX = "profile:"
    private val REF_RE = Regex("^profile:([1-9][0-9]{0,15})$")
    /** All references the plugins take (Messenger 1.28, Familie 1.30). */
    val ANY_REF_RE = Regex("^(profile:[1-9][0-9]{0,15}|home|place:[1-9][0-9]{0,15})$")
    private val KIND_RE = Regex("^[a-z][a-z0-9_.-]{0,39}$")
    private val PLUGIN_RE = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
    private const val MAX_PLACES = 200

    fun ref(id: Long): String = "$REF_PREFIX$id"

    /** `profile:<id>` → the id; anything else (`home`, `place:3`, `profile:0`) → null. */
    fun idOf(ref: String?): Long? = ref?.let { REF_RE.matchEntire(it) }?.groupValues?.get(1)?.toLongOrNull()

    fun isRef(ref: String?): Boolean = ref != null && ANY_REF_RE.matches(ref)

    /** A real point: within range and never 0/0 (what an empty position turns into). */
    fun hasPoint(lat: Double?, lon: Double?): Boolean =
        lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)

    fun clampRadius(m: Int?, min: Int = RADIUS_MIN, max: Int = RADIUS_MAX): Int = (m ?: RADIUS_DEFAULT).coerceIn(min, max)

    /** The slider's value on 50 m steps. */
    fun snapRadius(v: Float, min: Int = RADIUS_MIN, max: Int = RADIUS_MAX): Int =
        clampRadius((Math.round(v / RADIUS_STEP) * RADIUS_STEP), min, max)

    /** The name as the server keeps it: control characters as spaces, spaces folded, trimmed, at most [max]. */
    fun cleanName(s: String, max: Int = NAME_MAX): String =
        s.map { if (it.isISOControl()) ' ' else it }.joinToString("").replace(Regex("\\s+"), " ").trim().take(max).trim()

    /** May the editor save: a name and a point (without one there is nothing to watch). */
    fun canSave(d: PlaceDraft): Boolean = cleanName(d.name).isNotEmpty() && hasPoint(d.lat, d.lon)

    /** The name is taken by another of my places (the server compares without case). */
    fun nameTaken(name: String, places: List<MyPlace>, exceptId: Long? = null): Boolean {
        val n = cleanName(name).lowercase()
        return n.isNotEmpty() && places.any { it.id != exceptId && it.name.lowercase() == n }
    }

    // ─── parsing ────────────────────────────────────────────────────

    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.num(k: String): Double? = get(k)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.let { p ->
        when {
            p.isNumber -> p.asDouble
            p.isString -> p.asString.trim().toDoubleOrNull()
            else -> null
        }
    }?.takeIf { it.isFinite() }
    private fun JsonObject.int(k: String): Int? = num(k)?.let { Math.round(it).toInt() }
    private fun JsonObject.long(k: String): Long? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
    private fun JsonObject.bool(k: String): Boolean? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
    private fun JsonObject.arr(k: String): JsonArray? = get(k)?.takeIf { it.isJsonArray }?.asJsonArray
    private fun JsonObject.obj(k: String): JsonObject? = get(k)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun line(s: String?, max: Int): String? = s?.filter { !it.isISOControl() }?.trim()?.take(max)?.trim()?.ifEmpty { null }

    fun use(o: JsonObject): PlaceUse? {
        val plugin = o.str("plugin")?.takeIf { PLUGIN_RE.matches(it) } ?: return null
        val kind = o.str("kind")?.takeIf { KIND_RE.matches(it) } ?: return null
        val count = o.int("count")?.takeIf { it > 0 } ?: return null
        return PlaceUse(plugin, line(o.str("pluginName"), 60), kind, count.coerceAtMost(1_000_000))
    }

    fun usedBy(a: JsonArray?): List<PlaceUse> = a?.mapNotNull { e -> e.takeIf { it.isJsonObject }?.asJsonObject?.let(::use) }.orEmpty().take(50)

    /** One place; without an id or a name it is none. A point outside the range (or 0/0) is kept as „no point“. */
    fun place(o: JsonObject?): MyPlace? {
        if (o == null) return null
        val id = o.long("id")?.takeIf { it > 0 } ?: return null
        val name = line(o.str("name"), NAME_MAX) ?: return null
        val lat = o.num("lat")
        val lon = o.num("lon")
        val point = hasPoint(lat, lon)
        return MyPlace(
            id = id,
            name = name,
            icon = PlaceIcons.valid(o.str("icon")),
            lat = if (point) lat else null,
            lon = if (point) lon else null,
            radiusM = clampRadius(o.int("radiusM")),
            address = line(o.str("address"), ADDRESS_MAX),
            visibility = PlaceVisibility.valid(o.str("visibility")),
            source = o.str("source")?.takeIf { KIND_RE.matches(it) } ?: SOURCE_MANUAL,
            usedBy = usedBy(o.arr("usedBy")),
            updatedAt = line(o.str("updatedAt"), 40),
            lastUsedAt = line(o.str("lastUsedAt"), 40),
            ownerName = line(o.str("ownerName"), 120),
            own = o.bool("own") ?: true,
        )
    }

    fun limits(o: JsonObject?): PlacesLimits {
        val d = PlacesLimits()
        if (o == null) return d
        val r = o.obj("radius")
        val min = r?.int("min")?.coerceIn(10, 10_000) ?: d.radiusMin
        val max = r?.int("max")?.coerceIn(min, 100_000) ?: d.radiusMax
        return PlacesLimits(
            places = o.int("places")?.coerceIn(0, 10_000) ?: d.places,
            name = o.int("name")?.coerceIn(1, 200) ?: d.name,
            address = o.int("address")?.coerceIn(0, 1000) ?: d.address,
            radiusMin = min,
            radiusMax = max,
            radiusDefault = r?.int("default")?.coerceIn(min, max) ?: d.radiusDefault.coerceIn(min, max),
        )
    }

    /** `GET profile/places`; null: not the answer of a server with „Meine Orte“. */
    fun list(o: JsonObject): PlacesList? {
        val arr = o.arr("places") ?: return null
        return PlacesList(
            places = arr.mapNotNull { e -> e.takeIf { it.isJsonObject }?.asJsonObject?.let(::place) }.distinctBy { it.id }.take(MAX_PLACES),
            family = o.bool("family") ?: false,
            limits = limits(o.obj("limits")),
            search = o.str("search")?.takeIf { it == SEARCH_OSM || it == SEARCH_OWN || it == SEARCH_OFF } ?: SEARCH_OFF,
        )
    }

    /** `{ place }` of a change. */
    fun one(o: JsonObject): MyPlace? = place(o.obj("place"))

    /** `GET places/search`: at most 8 hits with a real point. */
    fun hits(o: JsonObject): List<AddressHit> = o.arr("results")?.mapNotNull { e ->
        val h = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val lat = h.num("lat")
        val lon = h.num("lon")
        if (!hasPoint(lat, lon)) return@mapNotNull null
        val address = line(h.str("address"), ADDRESS_MAX)
        val name = line(h.str("name"), NAME_MAX) ?: address?.substringBefore(',')?.trim()?.take(NAME_MAX) ?: return@mapNotNull null
        AddressHit(name, address, lat!!, lon!!, h.int("distanceM")?.takeIf { it >= 0 })
    }.orEmpty().take(8)

    /** The refusal's `usedBy` (409 IN_USE). */
    fun refusalUses(o: JsonObject?): List<PlaceUse> = usedBy(o?.arr("usedBy"))

    // ─── writing ────────────────────────────────────────────────────

    /** `POST profile/places`: only the keys the server knows (an unknown one → 400). null: nothing to save. */
    fun createBody(d: PlaceDraft): JsonObject? {
        if (!canSave(d)) return null
        return JsonObject().apply {
            addProperty("name", cleanName(d.name))
            addProperty("lat", round6(d.lat!!))
            addProperty("lon", round6(d.lon!!))
            addProperty("icon", PlaceIcons.valid(d.icon))
            addProperty("radiusM", clampRadius(d.radiusM))
            d.address?.let { line(it, ADDRESS_MAX) }?.let { addProperty("address", it) }
            addProperty("visibility", PlaceVisibility.valid(d.visibility))
        }
    }

    /**
     * `PATCH profile/places/:id`: only what changed against [old]; `lat` and `lon` always together, an emptied address
     * as `null`. null: nothing to save (no point, no name); an empty object: nothing changed.
     */
    fun patchBody(old: MyPlace, d: PlaceDraft): JsonObject? {
        if (!canSave(d)) return null
        val o = JsonObject()
        val name = cleanName(d.name)
        if (name != old.name) o.addProperty("name", name)
        if (PlaceIcons.valid(d.icon) != old.icon) o.addProperty("icon", PlaceIcons.valid(d.icon))
        val lat = round6(d.lat!!)
        val lon = round6(d.lon!!)
        if (old.lat == null || old.lon == null || round6(old.lat) != lat || round6(old.lon) != lon) {
            o.addProperty("lat", lat)
            o.addProperty("lon", lon)
        }
        if (clampRadius(d.radiusM) != old.radiusM) o.addProperty("radiusM", clampRadius(d.radiusM))
        val address = d.address?.let { line(it, ADDRESS_MAX) }
        if (address != old.address) {
            if (address == null) o.add("address", JsonNull.INSTANCE) else o.addProperty("address", address)
        }
        if (PlaceVisibility.valid(d.visibility) != old.visibility) o.addProperty("visibility", PlaceVisibility.valid(d.visibility))
        return o
    }

    /** Only [plugin] uses the place (nothing else would lose it): `usedBy` of a refused deletion. */
    fun onlyBy(uses: List<PlaceUse>, plugin: String): Boolean = uses.isNotEmpty() && uses.all { it.plugin == plugin }

    fun draftOf(p: MyPlace): PlaceDraft = PlaceDraft(p.name, p.icon, p.lat, p.lon, p.radiusM, p.address, p.visibility)

    fun round6(v: Double): Double = Math.round(v * 1_000_000.0) / 1_000_000.0

    /** `near=<lat>,<lon>`: rounded on the phone to two places (~1 km), as the server would. */
    fun near(lat: Double?, lon: Double?): String? {
        if (!hasPoint(lat, lon)) return null
        return String.format(java.util.Locale.ROOT, "%.2f,%.2f", lat, lon)
    }

    // ─── the copy on the phone ──────────────────────────────────────

    private const val CACHE_VERSION = 1

    fun write(p: MyPlace): JsonObject = JsonObject().apply {
        addProperty("id", p.id)
        addProperty("name", p.name)
        addProperty("icon", p.icon)
        p.lat?.let { addProperty("lat", it) }
        p.lon?.let { addProperty("lon", it) }
        addProperty("radiusM", p.radiusM)
        p.address?.let { addProperty("address", it) }
        addProperty("visibility", p.visibility)
        addProperty("source", p.source)
        add("usedBy", JsonArray().apply {
            p.usedBy.forEach { u ->
                add(JsonObject().apply {
                    addProperty("plugin", u.plugin)
                    u.pluginName?.let { addProperty("pluginName", it) }
                    addProperty("kind", u.kind)
                    addProperty("count", u.count)
                })
            }
        })
        p.updatedAt?.let { addProperty("updatedAt", it) }
        p.lastUsedAt?.let { addProperty("lastUsedAt", it) }
    }

    fun writeList(l: PlacesList): JsonObject = JsonObject().apply {
        add("places", JsonArray().apply { l.places.forEach { add(write(it)) } })
        addProperty("family", l.family)
        add("limits", JsonObject().apply {
            addProperty("places", l.limits.places)
            addProperty("name", l.limits.name)
            addProperty("address", l.limits.address)
            add("radius", JsonObject().apply {
                addProperty("min", l.limits.radiusMin)
                addProperty("max", l.limits.radiusMax)
                addProperty("default", l.limits.radiusDefault)
            })
        })
        addProperty("search", l.search)
    }

    /** The copy for the encrypted storage, bound to the login ([owner]). */
    fun encodeCache(l: PlacesList, owner: String): String = JsonObject().apply {
        addProperty("v", CACHE_VERSION)
        addProperty("owner", owner)
        add("list", writeList(l))
    }.toString()

    /** The copy of [owner]; another login's, an older format or a broken one → none. */
    fun decodeCache(raw: String?, owner: String): PlacesList? {
        if (raw.isNullOrBlank()) return null
        val o = runCatching { JsonParser.parseString(raw) }.getOrNull()?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return null
        if (o.int("v") != CACHE_VERSION || o.str("owner") != owner) return null
        return o.obj("list")?.let(::list)
    }

    /** The copy after a change: [p] in place of the one with its id (new ones at the end). */
    fun upsert(l: PlacesList, p: MyPlace): PlacesList =
        l.copy(places = if (l.places.any { it.id == p.id }) l.places.map { if (it.id == p.id) p else it } else l.places + p)

    fun remove(l: PlacesList, id: Long): PlacesList = l.copy(places = l.places.filter { it.id != id })

    /** The list as the profile shows it: those without a point first (Bild 4), then by name. */
    fun sorted(places: List<MyPlace>): List<MyPlace> {
        val collator = java.text.Collator.getInstance(java.util.Locale.GERMAN).apply { strength = java.text.Collator.PRIMARY }
        return places.sortedWith(compareBy<MyPlace> { it.hasPoint }.thenComparator { a, b -> collator.compare(a.name, b.name) })
    }
}
