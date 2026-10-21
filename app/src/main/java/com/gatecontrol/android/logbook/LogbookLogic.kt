package com.gatecontrol.android.logbook

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Decisions of the logbook screens, kept pure for the tests: month
 * switching, the list by day, the purpose bar, what a trip draws on the map
 * and the grouping of trips on the same route for the all-trips map.
 */
object LogbookLogic {

    // ─── months ─────────────────────────────────────────────────────

    /** Earliest month the switcher goes back to. */
    private val FIRST_MONTH: YearMonth = YearMonth.of(2000, 1)

    fun parseMonth(month: String?): YearMonth? =
        month?.takeIf { LogbookJson.MONTH_RE.matches(it) }?.let { runCatching { YearMonth.parse(it) }.getOrNull() }

    /** [month] moved by [by] months (`YYYY-MM`); null for an invalid month. */
    fun shift(month: String, by: Int): String? = parseMonth(month)?.plusMonths(by.toLong())?.toString()

    /** The month after [month], null when it would be after [currentMonth] (no future months). */
    fun next(month: String, currentMonth: String): String? {
        val m = parseMonth(month) ?: return null
        val current = parseMonth(currentMonth) ?: return null
        return m.plusMonths(1).takeIf { !it.isAfter(current) }?.toString()
    }

    fun previous(month: String): String? = parseMonth(month)?.minusMonths(1)?.takeIf { !it.isBefore(FIRST_MONTH) }?.toString()

    // ─── list ───────────────────────────────────────────────────────

    /** Trips newest first (day, then start). */
    fun newestFirst(trips: List<LogbookTrip>): List<LogbookTrip> =
        trips.sortedWith(compareByDescending<LogbookTrip> { it.date }.thenByDescending { it.startedAt ?: it.startTime ?: "" }.thenByDescending { it.id })

    /** The list by day (`YYYY-MM-DD`), newest day first, each day's trips newest first. */
    fun byDay(trips: List<LogbookTrip>): List<Pair<String, List<LogbookTrip>>> =
        newestFirst(trips).groupBy { it.date }.toList()

    /** Shares of the purpose bar in its order (Arbeitsweg, Geschäftlich, Privat, offen); only parts > 0, summing to 1. */
    fun shares(summary: LogbookSummary): List<Pair<String?, Float>> {
        val parts = listOf<Pair<String?, Double>>(
            LogbookPurpose.COMMUTE to summary.commute,
            LogbookPurpose.BUSINESS to summary.business,
            LogbookPurpose.PRIVATE to summary.private,
            null to summary.open,
        ).filter { it.second > 0 }
        val sum = parts.sumOf { it.second }
        if (sum <= 0) return emptyList()
        return parts.map { (p, km) -> p to (km / sum).toFloat() }
    }

    /**
     * [month] with [trip] as the server saved it: the trip replaced and its
     * kilometres moved to the new purpose in the sums, so the page is right
     * at once (the next load brings the server's own sums).
     */
    fun withTrip(month: LogbookMonth, trip: LogbookTrip): LogbookMonth {
        val old = month.trips.firstOrNull { it.id == trip.id } ?: return month
        var s = month.summary
        if (old.purpose != trip.purpose) {
            val km = distanceKm(old) ?: 0.0
            s = s.add(old.purpose, -km).add(trip.purpose, km)
            s = s.copy(
                openCount = (s.openCount + (if (trip.purpose == null) 1 else 0) - (if (old.purpose == null) 1 else 0)).coerceAtLeast(0),
            )
        }
        return month.copy(trips = month.trips.map { if (it.id == trip.id) trip else it }, summary = s)
    }

    private fun LogbookSummary.add(purpose: String?, km: Double): LogbookSummary = when (purpose) {
        LogbookPurpose.PRIVATE -> copy(private = (private + km).coerceAtLeast(0.0))
        LogbookPurpose.BUSINESS -> copy(business = (business + km).coerceAtLeast(0.0))
        LogbookPurpose.COMMUTE -> copy(commute = (commute + km).coerceAtLeast(0.0))
        else -> copy(open = (open + km).coerceAtLeast(0.0))
    }

    /** Distance shown for a trip: the odometer's, else its ends' difference, else the route's. */
    fun distanceKm(trip: LogbookTrip): Double? {
        trip.distanceKm?.let { return it }
        if (trip.startKm != null && trip.endKm != null && trip.endKm >= trip.startKm) return trip.endKm - trip.startKm
        return trip.route?.km
    }

    /** Minutes driven: from the timestamps, else from the clock times (over midnight too). */
    fun durationMinutes(trip: LogbookTrip): Long? {
        val from = trip.startedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val to = trip.endedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (from != null && to != null && !to.isBefore(from)) return Duration.between(from, to).toMinutes()
        val a = trip.startTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return null
        val b = trip.endTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return null
        val minutes = Duration.between(a, b).toMinutes()
        return if (minutes >= 0) minutes else minutes + 24 * 60
    }

    /** "Lindenweg 4, 12345 Ort" → "Lindenweg 4" (street part); null when unknown. */
    fun shortAddress(address: String?): String? = address?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }

    /** Shown as a number: "64", "4,5" (one decimal under 10 km). */
    fun formatKm(km: Double, decimalComma: Boolean = true): String {
        val rounded = if (km < 10) (km * 10).roundToLong() / 10.0 else km.roundToLong().toDouble()
        val text = if (rounded == rounded.roundToLong().toDouble()) rounded.roundToLong().toString() else rounded.toString()
        return if (decimalComma) text.replace('.', ',') else text
    }

    // ─── map ────────────────────────────────────────────────────────

    /**
     * The line a trip draws: its route, else the straight line between its
     * ends (when both are known); null = nothing to draw. [dashed]: only a
     * straight line (no calculated street route).
     */
    data class TripLine(val points: List<LogbookPoint>, val dashed: Boolean)

    fun line(trip: LogbookTrip): TripLine? {
        trip.route?.let { return TripLine(it.points, dashed = !it.isRoad) }
        val a = trip.start ?: return null
        val b = trip.end ?: return null
        return TripLine(listOf(a, b), dashed = true)
    }

    /** Filter chips of the all-trips map. */
    enum class MapFilter(val purpose: String?) {
        ALL(null), COMMUTE(LogbookPurpose.COMMUTE), BUSINESS(LogbookPurpose.BUSINESS), PRIVATE(LogbookPurpose.PRIVATE), OPEN(null);

        fun matches(trip: LogbookTrip): Boolean = when (this) {
            ALL -> true
            OPEN -> trip.purpose == null
            else -> trip.purpose == purpose
        }

        companion object {
            fun parse(name: String?): MapFilter = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: ALL
        }
    }

    /**
     * Trips on the same route with the same purpose, drawn once on the
     * all-trips map with their count. [trips] newest first; [line] the
     * newest trip's way (a calculated one preferred); [from]/[to] the points
     * of its two places.
     */
    data class RouteGroup(
        val key: String,
        val purpose: String?,
        val trips: List<LogbookTrip>,
        val line: TripLine,
        val from: LogbookPoint,
        val to: LogbookPoint,
        val fromAddress: String?,
        val toAddress: String?,
        /** Ids of the two places ([PlaceIndex]). */
        val fromPlace: Int,
        val toPlace: Int,
    ) {
        val count: Int get() = trips.size
        val km: Double get() = trips.sumOf { distanceKm(it) ?: 0.0 }
        val latest: LogbookTrip get() = trips.first()
    }

    /** Ends at most this far apart are the same place. */
    const val SAME_PLACE_M = 150.0
    private const val EARTH_M = 6_371_000.0

    /** Distance in metres (flat-earth approximation, fine for a few km). */
    fun distanceM(a: LogbookPoint, b: LogbookPoint): Double {
        val x = Math.toRadians(b.lon - a.lon) * cos(Math.toRadians((a.lat + b.lat) / 2))
        val y = Math.toRadians(b.lat - a.lat)
        return sqrt(x * x + y * y) * EARTH_M
    }

    /**
     * The places of a month: an end within [SAME_PLACE_M] of a known place
     * is that place (the first end seen there is its point), else a new one.
     */
    class PlaceIndex {
        private val points = mutableListOf<LogbookPoint>()

        fun id(p: LogbookPoint): Int {
            val known = points.indexOfFirst { distanceM(it, p) <= SAME_PLACE_M }
            if (known >= 0) return known
            points += p
            return points.lastIndex
        }

        fun point(id: Int): LogbookPoint = points[id]
    }

    /**
     * Groups [trips] (newest first) by purpose and their two places
     * (direction ignored: there and back is one route). Trips without
     * anything to draw are left out. Most frequent first.
     */
    fun groups(trips: List<LogbookTrip>): List<RouteGroup> {
        data class Drawable(val trip: LogbookTrip, val line: TripLine, val from: LogbookPoint, val to: LogbookPoint, val a: Int, val b: Int)
        val index = PlaceIndex()
        val drawable = newestFirst(trips).mapNotNull { trip ->
            val line = line(trip) ?: return@mapNotNull null
            val from = trip.start ?: line.points.first()
            val to = trip.end ?: line.points.last()
            Drawable(trip, line, from, to, index.id(from), index.id(to))
        }
        return drawable.groupBy { d -> "${d.trip.purpose ?: "open"}|${minOf(d.a, d.b)}|${maxOf(d.a, d.b)}" }.map { (key, list) ->
            val shown = list.firstOrNull { !it.line.dashed } ?: list.first()
            RouteGroup(
                key = key,
                purpose = list.first().trip.purpose,
                trips = list.map { it.trip },
                line = shown.line,
                fromAddress = shortAddress(shown.trip.startAddress),
                toAddress = shortAddress(shown.trip.endAddress),
                fromPlace = shown.a,
                toPlace = shown.b,
                from = index.point(shown.a),
                to = index.point(shown.b),
            )
        }.sortedWith(compareByDescending<RouteGroup> { it.count }.thenByDescending { it.latest.date })
    }

    /**
     * Places to mark on the all-trips map: the ends of [groups], one per
     * place, coloured by the purpose of the most trips ending there.
     */
    data class Place(val id: Int, val point: LogbookPoint, val purpose: String?, val trips: Int)

    fun places(groups: List<RouteGroup>): List<Place> {
        val byPlace = LinkedHashMap<Int, MutableList<RouteGroup>>()
        val points = HashMap<Int, LogbookPoint>()
        groups.forEach { g ->
            listOf(g.fromPlace to g.from, g.toPlace to g.to).distinctBy { it.first }.forEach { (id, p) ->
                byPlace.getOrPut(id) { mutableListOf() }.add(g)
                points.putIfAbsent(id, p)
            }
        }
        return byPlace.map { (id, gs) ->
            val purpose = gs.groupBy { it.purpose }.maxByOrNull { (_, list) -> list.sumOf { it.count } }?.key
            Place(id, points.getValue(id), purpose, gs.sumOf { it.count })
        }
    }

    /** The point halfway along [points] (by vertices; good enough for a count badge). */
    fun middle(points: List<LogbookPoint>): LogbookPoint = points[points.size / 2]

    /** South-west and north-east corner of [points]; null without any. */
    fun bounds(points: List<LogbookPoint>): Pair<LogbookPoint, LogbookPoint>? {
        if (points.isEmpty()) return null
        return LogbookPoint(points.minOf { it.lat }, points.minOf { it.lon }) to LogbookPoint(points.maxOf { it.lat }, points.maxOf { it.lon })
    }

    /** Whether [bounds] is (almost) one point: then a fixed zoom instead of fitting. */
    fun isTiny(bounds: Pair<LogbookPoint, LogbookPoint>): Boolean =
        abs(bounds.second.lat - bounds.first.lat) < 0.0005 && abs(bounds.second.lon - bounds.first.lon) < 0.0005
}
