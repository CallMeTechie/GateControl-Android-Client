package com.gatecontrol.android.ui.familie

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Where a map shows a box: always a number between the map's limits, whatever the box and the view's size. */
class OsmFitTest {

    // a phone's head map: 1080 × 315 px, 24 dp border at 2.625, tiles scaled to 672 px
    private fun plan(
        north: Double,
        east: Double,
        south: Double,
        west: Double,
        width: Int = 1080,
        height: Int = 315,
        border: Int = 63,
    ) = OsmFit.plan(north, east, south, west, width, height, border, tileSize = 672, minZoom = 2.0, maxZoom = 19.0, closeZoom = 14.0)

    private fun assertSane(p: OsmFit.Plan?) {
        assertNotNull(p)
        p!!
        assertTrue(p.zoom.isFinite() && p.zoom in 2.0..14.0, "zoom ${p.zoom}")
        assertTrue(p.lat in -OsmFit.MAX_LAT..OsmFit.MAX_LAT, "lat ${p.lat}")
        assertTrue(p.lon in -180.0..180.0, "lon ${p.lon}")
    }

    @Test
    fun `a box of Germany fits by its height, centred on it`() {
        val p = plan(55.0, 15.0, 47.0, 6.0)!!
        assertSane(p)
        assertEquals(10.5, p.lon, 1e-9)
        // Mercator's middle lies a little north of the degrees' middle
        assertTrue(p.lat > 51.0 && p.lat < 51.5, "lat ${p.lat}")
        // 8° of latitude in 315 - 2 × 63 px: about zoom 3
        assertEquals(2.99, p.zoom, 0.01)
    }

    @Test
    fun `a single place and a tiny box get the close zoom`() {
        val p = plan(53.55, 10.0, 53.55, 10.0)!!
        assertEquals(14.0, p.zoom)
        assertEquals(53.55, p.lat, 1e-9)
        assertEquals(10.0, p.lon, 1e-9)
        assertEquals(14.0, plan(53.5501, 10.0001, 53.55, 10.0)!!.zoom)
    }

    @Test
    fun `a box only one way wide fits by the other`() {
        // north = south: by the width
        val flat = plan(53.55, 12.0, 53.55, 8.0)
        assertSane(flat)
        assertTrue(flat!!.zoom < 14.0)
        // east = west: by the height
        val thin = plan(54.0, 10.0, 53.0, 10.0)
        assertSane(thin)
        assertTrue(thin!!.zoom < 14.0)
    }

    @Test
    fun `latitudes beyond Web Mercator are clamped`() {
        val p = plan(89.9, 20.0, 88.0, 10.0)
        assertSane(p)
        assertEquals(OsmFit.MAX_LAT, p!!.lat, 1e-6)
        assertSane(plan(90.0, 180.0, -90.0, -180.0))
        // south above north: the same box
        assertEquals(plan(55.0, 15.0, 47.0, 6.0), plan(47.0, 15.0, 55.0, 6.0))
    }

    @Test
    fun `east before west goes over the date line`() {
        val p = plan(-15.0, -175.0, -20.0, 175.0)
        assertSane(p)
        assertEquals(180.0, kotlin.math.abs(p!!.lon), 1e-9)
        // 10° wide, not 350°
        assertTrue(p.zoom > 4.0, "zoom ${p.zoom}")
        assertSane(plan(10.0, 200.0, -10.0, -200.0))
    }

    @Test
    fun `no size yet gives no plan, a border larger than the view shrinks`() {
        assertNull(plan(55.0, 15.0, 47.0, 6.0, width = 0, height = 0))
        assertNull(plan(55.0, 15.0, 47.0, 6.0, width = 1080, height = 0))
        // osmdroid takes 0 - 2 × 63 here and loops for ever on its NaN zoom
        assertSane(plan(55.0, 15.0, 47.0, 6.0, width = 100, height = 100, border = 63))
        assertSane(plan(55.0, 15.0, 47.0, 6.0, width = 1, height = 1, border = 63))
    }

    @Test
    fun `a box that is no number gives no plan, the zoom stays within the limits`() {
        assertNull(plan(Double.NaN, 15.0, 47.0, 6.0))
        assertNull(plan(55.0, Double.POSITIVE_INFINITY, 47.0, 6.0))
        val world = OsmFit.plan(85.0, 180.0, -85.0, -180.0, 1080, 315, 63, 672, minZoom = 2.0, maxZoom = 19.0, closeZoom = 14.0)
        assertEquals(2.0, world!!.zoom)
        val far = OsmFit.plan(53.55, 10.0, 53.55, 10.0, 1080, 315, 63, 672, minZoom = 2.0, maxZoom = 12.0, closeZoom = 14.0)
        assertEquals(12.0, far!!.zoom)
    }

    @Test
    fun `Mercator's y and back`() {
        listOf(-85.0, -45.0, 0.0, 53.55, 85.0).forEach { assertEquals(it, OsmFit.latOf(OsmFit.y01(it)), 1e-9) }
        assertEquals(0.5, OsmFit.y01(0.0), 1e-12)
    }
}
