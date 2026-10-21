package com.gatecontrol.android.ui.logbook

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gatecontrol.android.logbook.LogbookLogic
import com.gatecontrol.android.logbook.LogbookPoint
import com.gatecontrol.android.ui.familie.OsmAttribution
import com.gatecontrol.android.ui.familie.configureOsm
import com.gatecontrol.android.ui.theme.GateControlTheme
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * A route on the map: [color] (ARGB, [mapColor]) over a white halo, dashed
 * when only a straight line is known; [badge] ("18×") at its middle;
 * [emphasized] (the chosen one) wider and on top.
 */
internal data class MapLine(
    val key: String,
    val points: List<LogbookPoint>,
    val color: Int,
    val dashed: Boolean,
    val badge: String? = null,
    val emphasized: Boolean = false,
)

/** A place: a white dot in a coloured ring, or a lettered one (A/B of a trip). */
internal data class MapDot(
    val key: String,
    val point: LogbookPoint,
    val ring: Int,
    val fill: Int = WHITE,
    val label: String? = null,
    val labelColor: Int = INK,
)

private const val WHITE = 0xFFFFFFFF.toInt()
internal const val INK = 0xFF0F1720.toInt()

/**
 * OpenStreetMap (osmdroid, set up like the Familie map: app id as
 * User-Agent, tiles in the app's cache, attribution always shown) with the
 * logbook's routes and places. Zooms to fit everything whenever [fitKey]
 * changes, keeping [padTopDp] / [padBottomDp] free for what floats over the
 * map. [interactive] false: no pinch zoom — a preview, the caller lays a
 * tap target over the whole map, so it gets no touch at all.
 */
@Composable
internal fun LogbookMapView(
    lines: List<MapLine>,
    dots: List<MapDot>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    fitKey: Any? = lines.map { it.key },
    padTopDp: Int = 0,
    padBottomDp: Int = 0,
    /** What covers the map's bottom edge (a sheet): the attribution stays above it. */
    attributionBottom: Dp = 0.dp,
    onLineTap: ((String) -> Unit)? = null,
    onDotTap: ((String) -> Unit)? = null,
    onMapTap: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val extra = GateControlTheme.extraColors
    val lineTap = rememberUpdatedState(onLineTap)
    val dotTap = rememberUpdatedState(onDotTap)
    val mapTap = rememberUpdatedState(onMapTap)
    val map = remember {
        configureOsm(context)
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(interactive)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
            controller.setZoom(6.0)
            controller.setCenter(GeoPoint(51.0, 10.0))
        }
    }
    map.overlayManager.tilesOverlay.loadingBackgroundColor = extra.panel2.toArgb()
    map.overlayManager.tilesOverlay.loadingLineColor = extra.border.toArgb()

    DisposableEffect(lifecycle, map) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) map.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            map.onPause()
            map.onDetach()
        }
    }

    LaunchedEffect(map, fitKey) {
        val points = lines.flatMap { it.points } + dots.map { it.point }
        // After the layout pass: a map that just grew (trip map made large) fits its new size.
        if (map.width > 0 && map.height > 0) map.post { fit(map, points, padTopDp, padBottomDp) }
        else map.addOnFirstLayoutListener { _, _, _, _, _ -> fit(map, points, padTopDp, padBottomDp) }
    }

    Box(modifier.semantics { this.contentDescription = contentDescription }) {
        AndroidView(
            factory = { map },
            update = { view ->
                val content = lines to dots
                if (view.tag != content) {
                    view.tag = content
                    draw(view, lines, dots, { lineTap.value }, { dotTap.value }, { mapTap.value })
                }
            },
        )
        OsmAttribution(Modifier.align(Alignment.BottomEnd).padding(bottom = attributionBottom))
    }
}

/** All overlays anew: taps on the empty map at the bottom, then halos and lines, the chosen line, places, badges. */
private fun draw(
    view: MapView,
    lines: List<MapLine>,
    dots: List<MapDot>,
    lineTap: () -> ((String) -> Unit)?,
    dotTap: () -> ((String) -> Unit)?,
    mapTap: () -> (() -> Unit)?,
) {
    val res = view.resources
    val d = res.displayMetrics.density
    view.overlays.clear()
    view.overlays.add(
        MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                val tap = mapTap() ?: return false
                tap()
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean = false
        }),
    )
    val ordered = lines.sortedBy { it.emphasized }
    ordered.forEach { line ->
        val width = (if (line.emphasized) 7.5f else 5f) * d
        val geo = line.points.map { GeoPoint(it.lat, it.lon) }
        val click = Polyline.OnClickListener { _, _, _ ->
            val tap = lineTap() ?: return@OnClickListener false
            tap(line.key)
            true
        }
        view.overlays.add(polyline(view, geo, WHITE, width + 4f * d, dashed = false, click))
        view.overlays.add(polyline(view, geo, line.color, width, line.dashed, click))
    }
    dots.forEach { dot ->
        view.overlays.add(
            marker(view, dot.point, dotBitmap(res, dot).toDrawable(res)) {
                val tap = dotTap() ?: return@marker false
                tap(dot.key)
                true
            },
        )
    }
    ordered.forEach { line ->
        val badge = line.badge ?: return@forEach
        view.overlays.add(
            marker(view, LogbookLogic.middle(line.points), badgeBitmap(res, badge, line.color).toDrawable(res)) {
                val tap = lineTap() ?: return@marker false
                tap(line.key)
                true
            },
        )
    }
    view.invalidate()
}

private fun polyline(view: MapView, points: List<GeoPoint>, color: Int, width: Float, dashed: Boolean, click: Polyline.OnClickListener): Polyline =
    Polyline(view).apply {
        setPoints(points)
        // Drawn as one path: round joins and the dashes run along the whole line.
        usePath(true)
        infoWindow = null
        outlinePaint.apply {
            this.color = color
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
            pathEffect = if (dashed) DashPathEffect(floatArrayOf(width * 0.4f, width * 1.9f), 0f) else null
        }
        setOnClickListener(click)
    }

private fun marker(view: MapView, at: LogbookPoint, icon: BitmapDrawable, onClick: () -> Boolean): Marker =
    Marker(view).apply {
        position = GeoPoint(at.lat, at.lon)
        this.icon = icon
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        setInfoWindow(null)
        setOnMarkerClickListener { _, _ -> onClick() }
    }

/** A place: ring and fill, a letter inside when given (A/B bigger). */
private fun dotBitmap(res: Resources, dot: MapDot): Bitmap {
    val d = res.displayMetrics.density
    val radius = (if (dot.label != null) 11f else 8f) * d
    val stroke = (if (dot.label != null) 3f else 4f) * d
    val size = ((radius + stroke) * 2 + 2 * d).toInt()
    val bmp = createBitmap(size, size)
    val c = Canvas(bmp)
    val cx = size / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.style = Paint.Style.FILL
    paint.color = dot.fill
    c.drawCircle(cx, cx, radius, paint)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = stroke
    paint.color = dot.ring
    c.drawCircle(cx, cx, radius, paint)
    dot.label?.let { label ->
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = dot.labelColor
            textSize = 11f * d
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        c.drawText(label, cx, cx - (text.descent() + text.ascent()) / 2, text)
    }
    return bmp
}

/** "18×" as a pill in the route's colour with a white edge. */
private fun badgeBitmap(res: Resources, label: String, color: Int): Bitmap {
    val d = res.displayMetrics.density
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = WHITE
        textSize = 13f * d
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    val h = 24f * d
    val w = maxOf(h + 8f * d, text.measureText(label) + 18f * d)
    val edge = 1.5f * d
    val bmp = createBitmap((w + 2 * edge).toInt(), (h + 2 * edge).toInt())
    val c = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = WHITE
    c.drawRoundRect(RectF(0f, 0f, w + 2 * edge, h + 2 * edge), (h + 2 * edge) / 2, (h + 2 * edge) / 2, paint)
    paint.color = color
    c.drawRoundRect(RectF(edge, edge, w + edge, h + edge), h / 2, h / 2, paint)
    c.drawText(label, (w + 2 * edge) / 2, (h + 2 * edge) / 2 - (text.descent() + text.ascent()) / 2, text)
    return bmp
}

/**
 * Shows all [points]: a close-up for (almost) one point, else their box
 * with a border, stretched so [padTopDp] / [padBottomDp] stay free.
 */
private fun fit(map: MapView, points: List<LogbookPoint>, padTopDp: Int, padBottomDp: Int) {
    val bounds = LogbookLogic.bounds(points) ?: return
    val (sw, ne) = bounds
    if (LogbookLogic.isTiny(bounds)) {
        map.controller.setZoom(15.0)
        map.controller.setCenter(GeoPoint((sw.lat + ne.lat) / 2, (sw.lon + ne.lon) / 2))
        return
    }
    val d = map.resources.displayMetrics.density
    val border = (32 * d).toInt()
    val usable = (map.height - 2 * border - (padTopDp + padBottomDp) * d).coerceAtLeast(map.height / 3f)
    val span = ne.lat - sw.lat
    val north = (ne.lat + span * padTopDp * d / usable).coerceAtMost(85.0)
    val south = (sw.lat - span * padBottomDp * d / usable).coerceAtLeast(-85.0)
    map.zoomToBoundingBox(BoundingBox(north, ne.lon, south, sw.lon), false, border, 17.0, null)
}
