package com.gatecontrol.android.logbook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Months, the list by day, the purpose bar and the grouping of routes on the map. */
class LogbookLogicTest {

    private val home = LogbookPoint(51.2000, 6.7000)
    private val work = LogbookPoint(51.2500, 6.8000)
    private val shop = LogbookPoint(51.1800, 6.6900)

    private fun trip(
        id: Long,
        date: String = "2026-10-20",
        start: LogbookPoint? = home,
        end: LogbookPoint? = work,
        purpose: String? = LogbookPurpose.COMMUTE,
        km: Double? = 14.0,
        route: LogbookRoute? = null,
        startTime: String? = "07:30",
        endTime: String? = "07:52",
        startedAt: String? = null,
        endedAt: String? = null,
    ) = LogbookTrip(
        id = id, date = date, dateLabel = null, startTime = startTime, endTime = endTime, startedAt = startedAt, endedAt = endedAt,
        startKm = null, endKm = null, distanceKm = km, startAddress = "Lindenweg 4, 12345 Ort", endAddress = "Industriestraße 8, 12345 Ort",
        start = start, end = end, purpose = purpose, note = null, source = "auto", edited = false, driver = null, route = route,
    )

    // ─── months ─────────────────────────────────────────────────────

    @Test
    fun `months move across years`() {
        assertEquals("2026-11", LogbookLogic.shift("2026-10", 1))
        assertEquals("2027-01", LogbookLogic.shift("2026-12", 1))
        assertEquals("2025-12", LogbookLogic.previous("2026-01"))
        assertNull(LogbookLogic.shift("2026-13", 1))
        assertNull(LogbookLogic.previous("2000-01"))
    }

    @Test
    fun `no month after the current one`() {
        assertNull(LogbookLogic.next("2026-10", "2026-10"))
        assertEquals("2026-10", LogbookLogic.next("2026-09", "2026-10"))
        assertEquals("2026-01", LogbookLogic.next("2025-12", "2026-10"))
        assertNull(LogbookLogic.next("2026-10", "garbage"))
    }

    // ─── list ───────────────────────────────────────────────────────

    @Test
    fun `trips by day, newest day and trip first`() {
        val days = LogbookLogic.byDay(
            listOf(
                trip(1, date = "2026-10-20", startTime = "07:30"),
                trip(2, date = "2026-10-21", startTime = "07:31"),
                trip(3, date = "2026-10-21", startTime = "17:42"),
                trip(4, date = "2026-10-20", startTime = "15:40"),
            ),
        )
        assertEquals(listOf("2026-10-21", "2026-10-20"), days.map { it.first })
        assertEquals(listOf(3L, 2L), days[0].second.map { it.id })
        assertEquals(listOf(4L, 1L), days[1].second.map { it.id })
    }

    @Test
    fun `the purpose bar in its order, empty parts left out`() {
        val shares = LogbookLogic.shares(LogbookSummary(425.0, 41.0, 128.0, 252.0, 4.0, 1, 25))
        assertEquals(listOf(LogbookPurpose.COMMUTE, LogbookPurpose.BUSINESS, LogbookPurpose.PRIVATE, null), shares.map { it.first })
        assertEquals(1f, shares.sumOf { it.second.toDouble() }.toFloat(), 0.0001f)
        assertEquals(252f / 425f, shares[0].second, 0.0001f)
        assertEquals(listOf(LogbookPurpose.PRIVATE), LogbookLogic.shares(LogbookSummary(5.0, 5.0, 0.0, 0.0, 0.0, 0, 1)).map { it.first })
        assertTrue(LogbookLogic.shares(LogbookSummary.EMPTY).isEmpty())
    }

    @Test
    fun `distance falls back to the odometer and the route`() {
        assertEquals(14.0, LogbookLogic.distanceKm(trip(1)))
        assertEquals(64.0, LogbookLogic.distanceKm(trip(1, km = null).copy(startKm = 23418.0, endKm = 23482.0)))
        assertEquals(61.8, LogbookLogic.distanceKm(trip(1, km = null, route = LogbookRoute("road", 61.8, listOf(home, work)))))
        assertNull(LogbookLogic.distanceKm(trip(1, km = null)))
    }

    @Test
    fun `duration from the timestamps, else the clock times`() {
        assertEquals(57L, LogbookLogic.durationMinutes(trip(1, startedAt = "2026-10-20T07:05:00.000Z", endedAt = "2026-10-20T08:02:00.000Z")))
        assertEquals(22L, LogbookLogic.durationMinutes(trip(1)))
        assertEquals(40L, LogbookLogic.durationMinutes(trip(1, startTime = "23:50", endTime = "00:30")))
        assertNull(LogbookLogic.durationMinutes(trip(1, startTime = null)))
    }

    @Test
    fun `addresses and km as shown`() {
        assertEquals("Lindenweg 4", LogbookLogic.shortAddress("Lindenweg 4, 12345 Ort"))
        assertEquals("Hof", LogbookLogic.shortAddress("Hof"))
        assertNull(LogbookLogic.shortAddress(" , x"))
        assertEquals("64", LogbookLogic.formatKm(63.6))
        assertEquals("4,5", LogbookLogic.formatKm(4.46))
        assertEquals("4.5", LogbookLogic.formatKm(4.46, decimalComma = false))
        assertEquals("4", LogbookLogic.formatKm(4.04))
    }

    @Test
    fun `a saved purpose moves the trip's km in the sums`() {
        val month = LogbookMonth(
            month = "2026-10", currentMonth = "2026-10", vehicles = emptyList(), vehicleId = 3, canEdit = true,
            summary = LogbookSummary(18.0, 0.0, 0.0, 14.0, 4.0, 1, 2), moving = false,
            trips = listOf(trip(1), trip(2, purpose = null, km = 4.0)),
        )
        val saved = LogbookLogic.withTrip(month, trip(2, purpose = LogbookPurpose.PRIVATE, km = 4.0))
        assertEquals(LogbookPurpose.PRIVATE, saved.trips[1].purpose)
        assertEquals(LogbookSummary(18.0, 4.0, 0.0, 14.0, 0.0, 0, 2), saved.summary)
        // An unknown trip changes nothing.
        assertEquals(month, LogbookLogic.withTrip(month, trip(9)))
    }

    // ─── map ────────────────────────────────────────────────────────

    @Test
    fun `a trip draws its route, else the straight line, else nothing`() {
        val road = LogbookRoute(LogbookRoute.KIND_ROAD, 15.0, listOf(home, LogbookPoint(51.22, 6.75), work))
        assertEquals(LogbookLogic.TripLine(road.points, dashed = false), LogbookLogic.line(trip(1, route = road)))
        val line = LogbookRoute(LogbookRoute.KIND_LINE, null, listOf(home, work))
        assertTrue(LogbookLogic.line(trip(1, route = line))!!.dashed)
        assertEquals(LogbookLogic.TripLine(listOf(home, work), dashed = true), LogbookLogic.line(trip(1)))
        assertNull(LogbookLogic.line(trip(1, end = null)))
    }

    @Test
    fun `the same route both ways with the same purpose is one group`() {
        val road = LogbookRoute(LogbookRoute.KIND_ROAD, 14.0, listOf(home, LogbookPoint(51.22, 6.75), work))
        val groups = LogbookLogic.groups(
            listOf(
                trip(1, date = "2026-10-20", route = road),
                trip(2, date = "2026-10-20", start = work, end = home, startTime = "17:42"),
                // ~60 m away from home and work: still the same places.
                trip(3, date = "2026-10-21", start = LogbookPoint(51.2004, 6.7004), end = LogbookPoint(51.2496, 6.7996)),
                trip(4, date = "2026-10-21", purpose = LogbookPurpose.PRIVATE),
                trip(5, date = "2026-10-22", end = shop, purpose = LogbookPurpose.PRIVATE),
                trip(6, start = null, end = null),
            ),
        )
        assertEquals(3, groups.size)
        val commute = groups.first()
        assertEquals(LogbookPurpose.COMMUTE, commute.purpose)
        assertEquals(listOf(3L, 2L, 1L), commute.trips.map { it.id })
        assertEquals(3, commute.count)
        assertEquals(42.0, commute.km)
        assertEquals(3L, commute.latest.id)
        // A calculated route is drawn for the group, not the newest straight line.
        assertFalse(commute.line.dashed)
        assertEquals(road.points, commute.line.points)
        assertEquals("Lindenweg 4", commute.fromAddress)
        assertEquals("Industriestraße 8", commute.toAddress)
        assertEquals(setOf(listOf(4L), listOf(5L)), groups.drop(1).map { g -> g.trips.map { it.id } }.toSet())
    }

    @Test
    fun `ends within 150 m are one place, farther ones not`() {
        val index = LogbookLogic.PlaceIndex()
        val a = index.id(home)
        assertEquals(a, index.id(LogbookPoint(51.2009, 6.7009)))
        // ~560 m north, ~560 m east: other places.
        assertTrue(a != index.id(LogbookPoint(51.2050, 6.7000)))
        assertTrue(a != index.id(LogbookPoint(51.2000, 6.7080)))
        assertEquals(home, index.point(a))
        assertEquals(111.2, LogbookLogic.distanceM(home, LogbookPoint(51.201, 6.7)), 0.5)
    }

    @Test
    fun `places take the purpose of most trips there`() {
        val groups = LogbookLogic.groups(
            listOf(trip(1), trip(2), trip(3, end = shop, purpose = LogbookPurpose.PRIVATE)),
        )
        val places = LogbookLogic.places(groups)
        assertEquals(3, places.size)
        val atHome = places.first { it.point == home }
        assertEquals(LogbookPurpose.COMMUTE, atHome.purpose)
        assertEquals(3, atHome.trips)
    }

    @Test
    fun `filters by purpose`() {
        assertTrue(LogbookLogic.MapFilter.OPEN.matches(trip(1, purpose = null)))
        assertFalse(LogbookLogic.MapFilter.OPEN.matches(trip(1)))
        assertTrue(LogbookLogic.MapFilter.COMMUTE.matches(trip(1)))
        assertFalse(LogbookLogic.MapFilter.BUSINESS.matches(trip(1)))
        assertTrue(LogbookLogic.MapFilter.ALL.matches(trip(1, purpose = null)))
        assertEquals(LogbookLogic.MapFilter.PRIVATE, LogbookLogic.MapFilter.parse("private"))
        assertEquals(LogbookLogic.MapFilter.ALL, LogbookLogic.MapFilter.parse(null))
    }

    @Test
    fun `bounds and the middle of a line`() {
        val b = LogbookLogic.bounds(listOf(home, work, shop))!!
        assertEquals(LogbookPoint(51.18, 6.69), b.first)
        assertEquals(LogbookPoint(51.25, 6.8), b.second)
        assertFalse(LogbookLogic.isTiny(b))
        assertTrue(LogbookLogic.isTiny(LogbookLogic.bounds(listOf(home))!!))
        assertNull(LogbookLogic.bounds(emptyList()))
        assertNotNull(LogbookLogic.middle(listOf(home)))
        assertEquals(work, LogbookLogic.middle(listOf(home, work, shop)))
    }
}
