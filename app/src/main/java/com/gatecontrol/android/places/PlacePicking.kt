package com.gatecontrol.android.places

import com.google.gson.JsonObject
import java.text.Collator
import java.text.Normalizer
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The one choice of a place for every form (Ortsnachricht, Ankunft, Familie-Erinnerung, Regel, Aufgabe): at most three
 * chips plus „Alle Orte…“, and the sheet with „Zuletzt genutzt“, „In der Nähe“, A–Z and the search. Pure, unit-tested;
 * the screens only draw what these say.
 */

/**
 * A place to choose. [ref]: what the plugin keeps (`profile:<id>`, Familie `home` / `place:<id>`, or a plugin's own id);
 * null: a point picked only for this form (a hit of the search, the map). [usage]: uses in the plugin of the form (the
 * Messenger counts the last 90 days); [rank]: the plugin's own order (the Messenger sorts by use), lower first.
 */
data class PlaceOption(
    val ref: String?,
    val name: String,
    val icon: String = PlaceIcons.PIN,
    val lat: Double? = null,
    val lon: Double? = null,
    val radiusM: Int? = null,
    val address: String? = null,
    /** A Familie place (shared, kept by the parents): the blue symbol. */
    val familie: Boolean = false,
    val usage: Int = 0,
    val lastUsedMs: Long? = null,
    val rank: Int = Int.MAX_VALUE,
    /** The plugin knows the point, the phone does not (Familie's rule places): choosable without one. */
    val remote: Boolean = false,
) {
    val hasPoint: Boolean get() = MyPlaces.hasPoint(lat, lon)

    /** May be chosen: a real point, or one only the plugin knows. */
    val pickable: Boolean get() = hasPoint || remote

    /** Tells the choices apart: the reference, else the point. */
    val key: String get() = ref ?: "point:${lat ?: "-"},${lon ?: "-"}"

    /** One of „Meine Orte“. */
    val profileId: Long? get() = MyPlaces.idOf(ref)
}

/** A group of the sheet. */
data class PlaceGroup(val kind: Kind, val items: List<PlaceOption>, val letter: String? = null) {
    enum class Kind { RECENT, NEAR, LETTER, MINE, ADDRESSES }
}

object PlacePicking {
    const val CHIPS_MAX = 3
    const val RECENT_MAX = 3
    const val NEAR_M = 5_000

    private val collator: Collator = Collator.getInstance(Locale.GERMAN).apply { strength = Collator.PRIMARY }

    /** Great-circle distance in metres (haversine). */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return (2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))).roundToInt()
    }

    /** How far [o] is from [here], or null (no position, or the place has no point). */
    fun distanceOf(o: PlaceOption, here: Pair<Double, Double>?): Int? {
        if (here == null || !o.hasPoint) return null
        return distanceM(here.first, here.second, o.lat!!, o.lon!!)
    }

    /** „850 m“, „3,4 km“, „12 km“ — the decimal sign of [locale]. */
    fun distanceText(m: Int, locale: Locale = Locale.getDefault()): String = when {
        m < 1_000 -> "${(m / 10.0).roundToInt() * 10} m"
        m < 10_000 -> String.format(locale, "%.1f km", m / 1000.0)
        else -> "${(m / 1000.0).roundToInt()} km"
    }

    /** Lower case, without accents and „ß“: „Bäckerei“ matches „backerei“. */
    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT).replace("ß", "ss"), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trim()

    /** The letter of the A–Z group: the first letter without accent, „#“ for anything else. */
    fun letterOf(name: String): String {
        val c = normalize(name).firstOrNull() ?: return "#"
        return if (c in 'a'..'z') c.uppercaseChar().toString() else "#"
    }

    private fun byName(a: PlaceOption, b: PlaceOption): Int = collator.compare(a.name, b.name).takeIf { it != 0 } ?: a.key.compareTo(b.key)

    /** Most used first (in the plugin of the form), then the latest use, then the plugin's order, then the name. */
    val byUse: Comparator<PlaceOption> = compareByDescending<PlaceOption> { it.usage }
        .thenByDescending { it.lastUsedMs ?: Long.MIN_VALUE }
        .thenBy { it.rank }
        .thenComparator(::byName)

    /**
     * The chips in the form (Bild 8), at most [max]: 1. the chosen one, 2. the most used in this plugin, 3. filled with
     * the nearest. Without a position the rest follows the plugin's order (and the latest use). Each once; a place without
     * a point is never a chip (it cannot be watched).
     */
    fun chips(options: List<PlaceOption>, selected: PlaceOption?, here: Pair<Double, Double>?, max: Int = CHIPS_MAX): List<PlaceOption> {
        val out = LinkedHashMap<String, PlaceOption>()
        fun add(o: PlaceOption) {
            if (out.size < max && o.key !in out) out[o.key] = o
        }
        selected?.let(::add)
        val usable = options.filter { it.pickable }
        usable.filter { it.usage > 0 }.sortedWith(byUse).forEach(::add)
        if (here != null) usable.sortedBy { distanceOf(it, here) ?: Int.MAX_VALUE }.forEach(::add)
        usable.sortedWith(byUse).forEach(::add)
        return out.values.toList()
    }

    /**
     * The sheet without a search (Bild 6): „Zuletzt genutzt“ (at most [RECENT_MAX], by the latest use), „In der Nähe“ (up
     * to [NEAR_M], by distance), then all others A–Z, one group per letter. Familie places stand among them (the blue
     * symbol tells them apart). Each place once, in the first group it belongs to.
     */
    fun groups(options: List<PlaceOption>, here: Pair<Double, Double>?): List<PlaceGroup> {
        val all = options.distinctBy { it.key }
        val recent = all.filter { it.lastUsedMs != null }.sortedByDescending { it.lastUsedMs }.take(RECENT_MAX)
        val taken = recent.map { it.key }.toMutableSet()
        val near = if (here == null) emptyList() else all.filter { it.key !in taken }
            .mapNotNull { o -> distanceOf(o, here)?.takeIf { it <= NEAR_M }?.let { o to it } }
            .sortedBy { it.second }
            .map { it.first }
        taken += near.map { it.key }
        val rest = all.filter { it.key !in taken }.sortedWith(::byName)
        return buildList {
            if (recent.isNotEmpty()) add(PlaceGroup(PlaceGroup.Kind.RECENT, recent))
            if (near.isNotEmpty()) add(PlaceGroup(PlaceGroup.Kind.NEAR, near))
            rest.groupBy { letterOf(it.name) }
                .toSortedMap(compareBy<String> { it == "#" }.thenBy { it })
                .forEach { (letter, items) -> add(PlaceGroup(PlaceGroup.Kind.LETTER, items, letter)) }
        }
    }

    /**
     * The search in the sheet (Bild 7): first the own and the Familie places whose name or address holds [query] —
     * those whose name starts with it first, then nearest (with a position), then by name.
     */
    fun search(options: List<PlaceOption>, query: String, here: Pair<Double, Double>?): List<PlaceOption> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        return options.distinctBy { it.key }
            .filter { normalize(it.name).contains(q) || it.address?.let { a -> normalize(a).contains(q) } == true }
            .sortedWith(
                compareBy<PlaceOption> { !normalize(it.name).startsWith(q) }
                    .thenBy { distanceOf(it, here) ?: Int.MAX_VALUE }
                    .thenComparator(::byName),
            )
    }

    /** May the address search be asked: at least 3 characters (Nominatim policy), only on „Suchen“/Enter. */
    fun searchable(query: String): Boolean = query.trim().length in MyPlaces.SEARCH_MIN..MyPlaces.SEARCH_MAX

    /** The address hits that are not one of the places already listed (same point within 30 m). */
    fun newHits(hits: List<AddressHit>, options: List<PlaceOption>): List<AddressHit> = hits.filter { h ->
        options.none { o -> o.hasPoint && distanceM(h.lat, h.lon, o.lat!!, o.lon!!) <= 30 }
    }

    /** The chosen place among [options] by its reference (e.g. a stored `profile:12`), else null. */
    fun find(options: List<PlaceOption>, ref: String?): PlaceOption? = ref?.let { r -> options.firstOrNull { it.ref == r } }

    /** One of „Meine Orte“ as a choice. [usage]/[rank]: from the plugin of the form, when it knows them. */
    fun option(p: MyPlace, usage: Int = 0, rank: Int = Int.MAX_VALUE): PlaceOption = PlaceOption(
        ref = p.ref,
        name = p.name,
        icon = p.icon,
        lat = p.lat,
        lon = p.lon,
        radiusM = p.radiusM,
        address = p.address,
        familie = false,
        usage = usage,
        lastUsedMs = time(p.lastUsedAt),
        rank = rank,
    )

    /** A hit of the address search, used only here (not saved as a place). */
    fun option(h: AddressHit, radiusM: Int = MyPlaces.RADIUS_DEFAULT): PlaceOption =
        PlaceOption(ref = null, name = h.name, icon = PlaceIcons.PIN, lat = h.lat, lon = h.lon, radiusM = radiusM, address = h.address)

    /** `YYYY-MM-DD HH:MM:SS` (UTC, as the server writes it) or ISO 8601 → epoch ms; anything else → null. */
    fun time(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        val iso = if (Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$").matches(s)) s.replace(' ', 'T') + "Z" else s
        return runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
    }
}

/** The sources of the choice as the plugins give them (Messenger 1.28 `GET places`, the older `familie/places`). Pure. */
object PlaceSources {
    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.num(k: String): Double? = get(k)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.let { p ->
        when {
            p.isNumber -> p.asDouble
            p.isString -> p.asString.trim().toDoubleOrNull()
            else -> null
        }
    }?.takeIf { it.isFinite() }

    /**
     * Messenger 1.28 `GET places`: the own places of the profile and the Familie places, already in the order of the
     * chips (most used in the Messenger, latest use, name). Entries without a valid `ref` are left out.
     */
    fun messenger(o: JsonObject): List<PlaceOption>? {
        val arr = o.get("places")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        return arr.mapIndexedNotNull { i, e ->
            val p = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapIndexedNotNull null
            val ref = p.str("ref")?.takeIf { MyPlaces.isRef(it) } ?: return@mapIndexedNotNull null
            val name = p.str("name")?.filter { !it.isISOControl() }?.trim()?.take(80)?.ifEmpty { null } ?: return@mapIndexedNotNull null
            val lat = p.num("lat")
            val lon = p.num("lon")
            val point = MyPlaces.hasPoint(lat, lon)
            PlaceOption(
                ref = ref,
                name = name,
                icon = PlaceIcons.valid(p.str("icon")),
                lat = if (point) lat else null,
                lon = if (point) lon else null,
                radiusM = p.num("radiusM")?.toInt()?.coerceIn(10, 100_000),
                address = p.str("address")?.take(MyPlaces.ADDRESS_MAX),
                familie = p.str("source") == "familie" || !ref.startsWith("profile:"),
                usage = p.num("usageCount")?.toInt()?.coerceAtLeast(0) ?: 0,
                lastUsedMs = PlacePicking.time(p.str("lastUsedAt")),
                rank = i,
            )
        }.distinctBy { it.key }.take(200)
    }

    /** A Familie place of the Messenger before 1.28 (`GET familie/places`): its id stays what the plugin keeps. */
    fun familie(places: List<com.gatecontrol.android.chat.ChatPlace>): List<PlaceOption> = places.mapIndexed { i, p ->
        PlaceOption(
            ref = p.id,
            name = p.name,
            icon = if (p.id == "home") PlaceIcons.HOME else PlaceIcons.PIN,
            lat = p.lat?.takeIf { MyPlaces.hasPoint(p.lat, p.lon) },
            lon = p.lon?.takeIf { MyPlaces.hasPoint(p.lat, p.lon) },
            radiusM = p.radiusM,
            familie = true,
            rank = i,
        )
    }

    /** The own places merged with a plugin's list: what the plugin knows wins (its order and use), mine fill in. */
    fun merged(plugin: List<PlaceOption>, mine: List<MyPlace>): List<PlaceOption> {
        val known = plugin.map { it.key }.toSet()
        return plugin + mine.filter { it.ref !in known }.map { PlacePicking.option(it) }
    }
}
