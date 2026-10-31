package com.gatecontrol.android.drive.work

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DriveWorkPlacesTest {

    // Berlin, Alexanderplatz; about 111 m per 0.001° of latitude
    private val office = GeoPoint(52.52190, 13.41320)
    private val home = GeoPoint(52.48000, 13.35000)

    private fun north(p: GeoPoint, m: Double) = GeoPoint(p.lat + m / 111_195.0, p.lon)

    private fun pt(i: Int, at: GeoPoint, work: Boolean) = PlacePoint("n:$i", at, work, i.toLong())

    @Test
    fun `distance in metres`() {
        assertEquals(0.0, DriveWorkPlaces.distanceM(office, office), 0.001)
        assertEquals(250.0, DriveWorkPlaces.distanceM(office, north(office, 250.0)), 1.0)
        // Alexanderplatz – Brandenburger Tor, about 2.6 km
        assertEquals(2_600.0, DriveWorkPlaces.distanceM(office, GeoPoint(52.51628, 13.37770)), 150.0)
        assertEquals(DriveWorkPlaces.distanceM(office, home), DriveWorkPlaces.distanceM(home, office), 0.001)
    }

    @Test
    fun `EXIF location read with care`() {
        assertEquals(GeoPoint(52.5219, 13.4132), DriveWorkPlaces.of(52.521900004, 13.413200001))
        assertNull(DriveWorkPlaces.of(0.0, 0.0), "stripped by Android")
        assertNull(DriveWorkPlaces.of(null, 13.0))
        assertNull(DriveWorkPlaces.of(91.0, 13.0))
        assertNull(DriveWorkPlaces.of(Double.NaN, 13.0))
        assertNull(DriveWorkPlaces.of(null as DoubleArray?))
        assertEquals(GeoPoint(1.0, 2.0), DriveWorkPlaces.of(doubleArrayOf(1.0, 2.0)))
    }

    @Test
    fun `photos close together make a place once enough support it`() {
        val three = (1..3).map { pt(it, north(office, it * 20.0), work = true) }
        assertTrue(DriveWorkPlaces.cluster(three).isEmpty(), "three photos are not enough")
        val four = three + pt(4, north(office, -30.0), work = true)
        val places = DriveWorkPlaces.cluster(four)
        assertEquals(1, places.size)
        val p = places[0]
        assertTrue(p.work)
        assertEquals(4, p.support)
        assertTrue(DriveWorkPlaces.distanceM(p.center, office) < 40)
        assertTrue(p.radiusM in DriveWorkPlaces.MIN_RADIUS_M..DriveWorkPlaces.CLUSTER_M)
    }

    @Test
    fun `places of both kinds apart, far photos alone count for nothing`() {
        val pts = (1..5).map { pt(it, north(office, it * 10.0), work = true) } +
            (6..10).map { pt(it, north(home, it * 10.0), work = false) } +
            pt(11, GeoPoint(48.137, 11.575), work = true) // München, once
        val places = DriveWorkPlaces.cluster(pts)
        assertEquals(2, places.size)
        assertEquals(1, places.count { it.work })
        assertEquals(WifiZone.WORK, DriveWorkPlaces.zoneAt(places, north(office, 60.0)))
        assertEquals(WifiZone.HOME, DriveWorkPlaces.zoneAt(places, home))
        assertNull(DriveWorkPlaces.zoneAt(places, north(office, 1_000.0)), "a kilometre off")
        assertNull(DriveWorkPlaces.zoneAt(places, GeoPoint(48.137, 11.575)))
        assertNull(DriveWorkPlaces.zoneAt(places, null))
    }

    @Test
    fun `a place of both kinds (a home office) is none`() {
        val mixed = (1..3).map { pt(it, north(home, it * 5.0), work = true) } + (4..6).map { pt(it, north(home, it * 5.0), work = false) }
        assertTrue(DriveWorkPlaces.cluster(mixed).isEmpty())
        // nearly all of one kind still counts
        val mostly = (1..9).map { pt(it, north(home, it * 5.0), work = false) } + pt(10, home, work = true)
        val places = DriveWorkPlaces.cluster(mostly)
        assertEquals(1, places.size)
        assertFalse(places[0].work)
        // two places of both kinds reaching the same spot: none
        val both = listOf(WorkPlace(office, 200.0, true, 5), WorkPlace(north(office, 150.0), 200.0, false, 5))
        assertNull(DriveWorkPlaces.zoneAt(both, north(office, 75.0)))
    }

    @Test
    fun `one point per photo, the newest kept`() {
        var list = emptyList<PlacePoint>()
        list = DriveWorkPlaces.add(list, pt(1, office, true))
        list = DriveWorkPlaces.add(list, PlacePoint("n:1", home, false, 5))
        assertEquals(1, list.size)
        assertFalse(list[0].work)
        repeat(DriveWorkPlaces.MAX_POINTS + 5) { list = DriveWorkPlaces.add(list, pt(100 + it, office, true)) }
        assertEquals(DriveWorkPlaces.MAX_POINTS, list.size)
        assertFalse(list.any { it.key == "n:1" })
    }

    @Test
    fun `kept on the phone and read back`() {
        val b = PlaceBook(
            points = listOf(pt(1, office, true), pt(2, home, false)),
            geo = mapOf(10L to office, 11L to null),
            auto = setOf(20L, 21L),
            held = setOf(30L),
        )
        val back = DriveWorkPlaces.decode(DriveWorkPlaces.encode(b))
        assertEquals(b, back)
        assertNotNull(back.geo[10L])
        assertTrue(11L in back.geo)
        assertEquals(PlaceBook(), DriveWorkPlaces.decode("{broken"))
        assertEquals(PlaceBook(), DriveWorkPlaces.decode(null))
    }
}
