package com.gatecontrol.android.ui.familie

import android.view.View
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.TileSystem
import org.osmdroid.views.MapView
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Where a map shows a box: its centre and zoom, worked out here instead of osmdroid's `zoomToBoundingBox`. That one
 * takes the view's size minus the border as it is: at 0 × 0 (a list item measured ahead, laid out before it is placed)
 * or a border larger than the view its zoom is NaN, and `Projection` then loops for ever (ANR in Drive › Fotos, 1.76).
 * Here the border shrinks with the view, a point or a tiny box gets [plan]'s close zoom, latitudes stay within Web
 * Mercator, a box whose east lies before its west goes over the date line, and the zoom is always a number between
 * the map's limits. Pure: no Android.
 */
internal object OsmFit {

    /** Web Mercator's (and osmdroid's) latitude limit. */
    const val MAX_LAT = 85.05112877980659

    /** Below this (of the world's width or height) a box counts as a point. */
    private const val POINT = 1e-9

    data class Plan(val lat: Double, val lon: Double, val zoom: Double)

    /**
     * The centre and zoom that show the box [north] [east] [south] [west] in a view of [width] × [height] pixels with
     * [border] pixels free around it ([tileSize]: osmdroid's tile size in pixels). Never above [closeZoom] (a point or
     * a tiny box: that zoom), always within [minZoom] … [maxZoom]. Null while the view has no size or for a box
     * that is no number.
     */
    fun plan(
        north: Double,
        east: Double,
        south: Double,
        west: Double,
        width: Int,
        height: Int,
        border: Int,
        tileSize: Int,
        minZoom: Double,
        maxZoom: Double,
        closeZoom: Double,
    ): Plan? {
        if (width <= 0 || height <= 0 || tileSize <= 0) return null
        if (!north.isFinite() || !east.isFinite() || !south.isFinite() || !west.isFinite()) return null
        val n = max(north, south).coerceIn(-MAX_LAT, MAX_LAT)
        val s = min(north, south).coerceIn(-MAX_LAT, MAX_LAT)
        val w = west.coerceIn(-180.0, 180.0)
        val e = east.coerceIn(-180.0, 180.0)
        val spanLon = when {
            east - west >= 360.0 -> 360.0
            e >= w -> e - w
            else -> e + 360.0 - w // over the date line
        }
        val lon = wrapLon(w + spanLon / 2)
        val yNorth = y01(n)
        val ySouth = y01(s)
        val lat = latOf((yNorth + ySouth) / 2)
        // the border never takes more than half the view
        val b = border.coerceAtLeast(0).coerceAtMost(min(width, height) / 4)
        val spanX = spanLon / 360.0
        val spanY = ySouth - yNorth
        val zoomX = if (spanX > POINT) log2((width - 2 * b) / (spanX * tileSize)) else Double.POSITIVE_INFINITY
        val zoomY = if (spanY > POINT) log2((height - 2 * b) / (spanY * tileSize)) else Double.POSITIVE_INFINITY
        var zoom = min(min(zoomX, zoomY), closeZoom)
        if (!zoom.isFinite()) zoom = closeZoom
        zoom = max(minZoom, min(zoom, maxZoom))
        if (!zoom.isFinite()) return null
        return Plan(lat, lon, zoom)
    }

    /** Web Mercator's y of [lat]: 0 at the top, 1 at the bottom. */
    internal fun y01(lat: Double): Double = 0.5 - ln(tan(PI / 4 + lat * PI / 360)) / (2 * PI)

    internal fun latOf(y01: Double): Double = atan(sinh(PI * (1 - 2 * y01))) * 180 / PI

    private fun wrapLon(lon: Double): Double {
        val x = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return if (x == -180.0 && lon > 0) 180.0 else x
    }

    private fun log2(x: Double): Double = ln(x) / ln(2.0)
}

/** Shows the box on this map ([OsmFit.plan] with its size, tiles and zoom limits); false while it has no size. */
internal fun MapView.showBox(north: Double, east: Double, south: Double, west: Double, border: Int, closeZoom: Double): Boolean {
    val plan = OsmFit.plan(north, east, south, west, width, height, border, TileSystem.getTileSize(), minZoomLevel, maxZoomLevel, closeZoom) ?: return false
    // the zoom first, so that the centre is exact
    controller.setZoom(plan.zoom)
    controller.setCenter(GeoPoint(plan.lat, plan.lon))
    return true
}

/**
 * Shows a map's first view (and a new one) once, never inside a layout pass: [request] runs its step posted, after the
 * map has a size — at 0 × 0 (a list item measured ahead) it waits for the next layout. Moving by hand is not undone.
 */
internal class OsmFitter(private val map: MapView) : View.OnLayoutChangeListener {
    private var key: Any? = null
    private var shownKey: Any? = null
    private var step: (MapView) -> Boolean = { true }
    private var posted = false
    var onShown: () -> Unit = {}

    /** Whether the view last asked for is on the map. */
    val shown: Boolean get() = key != null && shownKey == key

    private val run = Runnable {
        posted = false
        val k = key ?: return@Runnable
        if (shownKey == k || map.width <= 0 || map.height <= 0) return@Runnable
        if (step(map)) {
            shownKey = k
            onShown()
        }
    }

    init {
        map.addOnLayoutChangeListener(this)
    }

    /** Shows [key] by [step] (false: not yet); the same key again does nothing. */
    fun request(key: Any, step: (MapView) -> Boolean) {
        if (key == this.key) return
        this.key = key
        this.step = step
        schedule()
    }

    override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
        if (key != null && shownKey != key) schedule()
    }

    private fun schedule() {
        if (posted) return
        posted = true
        map.post(run)
    }

    fun release() {
        map.removeOnLayoutChangeListener(this)
        map.removeCallbacks(run)
        posted = false
        key = null
        onShown = {}
    }
}
