package com.gatecontrol.android.drive.work

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** Where a photo was taken (EXIF GPS, degrees). */
data class GeoPoint(val lat: Double, val lon: Double)

/**
 * A photo that teaches the places: taken at [at], work ([work] true: "Firma") or private (false: "Zuhause"); one per
 * photo ([key], as the AI's examples: `n:<nodeId>`), [time] when it was learned.
 */
data class PlacePoint(val key: String, val at: GeoPoint, val work: Boolean, val time: Long)

/** A place learned from the photos: its centre, how far it reaches ([radiusM]), Firma or Zuhause, how many photos say so. */
data class WorkPlace(val center: GeoPoint, val radiusM: Double, val work: Boolean, val support: Int)

/**
 * What the phone keeps of the places: the photos they are learned from ([points]), where the marked photos were taken
 * ([geo]: node id → its point, null: looked, none), the photos the phone sorted alone ([auto], never examples) and those
 * the person took back from it ([held], never sorted alone again).
 */
data class PlaceBook(
    val points: List<PlacePoint> = emptyList(),
    val geo: Map<Long, GeoPoint?> = emptyMap(),
    val auto: Set<Long> = emptySet(),
    val held: Set<Long> = emptySet(),
)

/**
 * The places "Firma" and "Zuhause" (pure, unit-tested): photos with a location that were taken in such a WLAN or that
 * the person assigned are grouped into places of about [CLUSTER_M] ([cluster]); a place counts once [MIN_SUPPORT]
 * photos support it and nearly all of one kind. Nothing of it leaves the phone.
 */
object DriveWorkPlaces {

    /** Photos this close to a place's centre belong to it. */
    const val CLUSTER_M = 250.0

    /** A place reaches at least this far (a few photos close together), at most [CLUSTER_M]. */
    const val MIN_RADIUS_M = 100.0

    /** Added to the farthest photo of a place. */
    const val MARGIN_M = 50.0

    /** Photos a place needs before it counts. */
    const val MIN_SUPPORT = 4

    /** Of those at least this share of one kind (a home office of both is no place). */
    const val PURITY = 0.8

    /** At most this many photos are kept (the oldest go). */
    const val MAX_POINTS = 600

    /** At most this many locations of photos in Drive are kept. */
    const val MAX_GEO = 3000

    /** At most this many photos sorted alone / held are kept. */
    const val MAX_IDS = 5000

    private const val EARTH_M = 6_371_008.8

    /** Distance in metres (haversine). */
    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** EXIF's latitude and longitude as a point (rounded to about a metre); null when missing, broken or 0/0 (stripped). */
    fun of(lat: Double?, lon: Double?): GeoPoint? {
        if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite()) return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        if (lat == 0.0 && lon == 0.0) return null
        return GeoPoint(round(lat), round(lon))
    }

    fun of(latLong: DoubleArray?): GeoPoint? = if (latLong == null || latLong.size < 2) null else of(latLong[0], latLong[1])

    private fun round(x: Double): Double = (x * 100_000).roundToLong() / 100_000.0

    /** [p] added: an earlier point of the same photo is replaced, the oldest go beyond [MAX_POINTS]. */
    fun add(list: List<PlacePoint>, p: PlacePoint): List<PlacePoint> {
        val out = list.filter { it.key != p.key } + p
        return if (out.size > MAX_POINTS) out.sortedBy { it.time }.takeLast(MAX_POINTS) else out
    }

    /**
     * The places of [points]: each photo (the oldest first) joins the nearest group whose centre is within [CLUSTER_M],
     * else starts one; a group with [MIN_SUPPORT] photos of which [PURITY] are one kind is a place of that kind, its
     * radius the farthest photo plus [MARGIN_M] (between [MIN_RADIUS_M] and [CLUSTER_M]).
     */
    fun cluster(points: List<PlacePoint>): List<WorkPlace> {
        class Group(var lat: Double, var lon: Double) {
            val members = ArrayList<PlacePoint>()
            val center: GeoPoint get() = GeoPoint(lat, lon)
            fun join(p: PlacePoint) {
                members += p
                lat += (p.at.lat - lat) / members.size
                lon += (p.at.lon - lon) / members.size
            }
        }
        val groups = ArrayList<Group>()
        for (p in points.sortedBy { it.time }) {
            val near = groups.map { it to distanceM(it.center, p.at) }.filter { it.second <= CLUSTER_M }.minByOrNull { it.second }?.first
            (near ?: Group(p.at.lat, p.at.lon).also { groups += it }).join(p)
        }
        return groups.mapNotNull { g ->
            val n = g.members.size
            val work = g.members.count { it.work }
            if (n < MIN_SUPPORT || maxOf(work, n - work) < PURITY * n) return@mapNotNull null
            val far = g.members.maxOf { distanceM(g.center, it.at) }
            WorkPlace(g.center, (far + MARGIN_M).coerceIn(MIN_RADIUS_M, CLUSTER_M), work * 2 > n, n)
        }
    }

    /** The place [at] lies in: Firma ([WifiZone.WORK]) or Zuhause ([WifiZone.HOME]); null: none, or both at once. */
    fun zoneAt(places: List<WorkPlace>, at: GeoPoint?): WifiZone? {
        if (at == null) return null
        val inside = places.filter { distanceM(it.center, at) <= it.radiusM }
        if (inside.isEmpty()) return null
        val work = inside.any { it.work }
        val home = inside.any { !it.work }
        return when {
            work && home -> null
            work -> WifiZone.WORK
            else -> WifiZone.HOME
        }
    }

    // ─── on the phone ───────────────────────────────────────────────

    /** `{ v:1, p:[[key, lat, lon, work, time], …], g:{ "<id>": [lat, lon] | null }, a:[ids], h:[ids] }`. */
    fun encode(b: PlaceBook): String = JsonObject().apply {
        addProperty("v", 1)
        add("p", JsonArray().apply {
            b.points.forEach { p ->
                add(JsonArray().apply { add(p.key); add(p.at.lat); add(p.at.lon); add(p.work); add(p.time) })
            }
        })
        add("g", JsonObject().apply {
            b.geo.forEach { (id, g) -> add(id.toString(), if (g == null) JsonNull.INSTANCE else JsonArray().apply { add(g.lat); add(g.lon) }) }
        })
        add("a", JsonArray().apply { b.auto.forEach { add(it) } })
        add("h", JsonArray().apply { b.held.forEach { add(it) } })
    }.toString()

    /** Anything broken reads as nothing learned. */
    fun decode(text: String?): PlaceBook {
        if (text.isNullOrBlank()) return PlaceBook()
        return runCatching {
            val o = JsonParser.parseString(text).asJsonObject
            val points = o.getAsJsonArray("p")?.mapNotNull { e ->
                val a = e as? JsonArray ?: return@mapNotNull null
                if (a.size() < 5) return@mapNotNull null
                val key = a[0].takeIf { it.isJsonPrimitive }?.asString?.take(80) ?: return@mapNotNull null
                val at = of(runCatching { a[1].asDouble }.getOrNull(), runCatching { a[2].asDouble }.getOrNull()) ?: return@mapNotNull null
                val work = runCatching { a[3].asBoolean }.getOrNull() ?: return@mapNotNull null
                PlacePoint(key, at, work, runCatching { a[4].asLong }.getOrDefault(0L))
            }.orEmpty()
            val geo = LinkedHashMap<Long, GeoPoint?>()
            o.getAsJsonObject("g")?.entrySet()?.forEach { (k, v) ->
                val id = k.toLongOrNull() ?: return@forEach
                geo[id] = (v as? JsonArray)?.takeIf { it.size() >= 2 }?.let { a ->
                    of(runCatching { a[0].asDouble }.getOrNull(), runCatching { a[1].asDouble }.getOrNull())
                }
            }
            fun ids(name: String): Set<Long> = o.getAsJsonArray(name)?.mapNotNull { runCatching { it.asLong }.getOrNull() }?.toCollection(LinkedHashSet()).orEmpty()
            PlaceBook(points, geo, ids("a"), ids("h"))
        }.getOrDefault(PlaceBook())
    }
}
