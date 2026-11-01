package com.gatecontrol.android.ui.drive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.location.LocationManager
import android.view.MotionEvent
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.drawToBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DBbox
import com.gatecontrol.android.drive.DCluster
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DPerson
import com.gatecontrol.android.drive.DPlaceSummary
import com.gatecontrol.android.drive.DrivePlacesLogic
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.familie.OsmAttribution
import com.gatecontrol.android.ui.familie.OsmFitter
import com.gatecontrol.android.ui.familie.configureOsm
import com.gatecontrol.android.ui.familie.showBox
import com.gatecontrol.android.ui.theme.GateControlTheme
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.BitmapPool
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Where the Photos page's places and people lead (Drive 1.16). */
internal data class PhotosPlacesNav(
    val search: () -> Unit,
    val map: () -> Unit,
    val place: (String) -> Unit,
    val people: () -> Unit,
    val person: (Long) -> Unit,
)

// ─── faces ─────────────────────────────────────────────────────────

/** A person's colour while no face is shown (from the id, the avatar palette). */
internal fun personColor(id: Long): Color {
    val palette = listOf(0xFFFF8080, 0xFFF5B544, 0xFF79B8FF, 0xFF46E0A0, 0xFFB79CFF, 0xFF5FD4E8, 0xFFF29DC8, 0xFFB5D96B)
    return Color(palette[((id % palette.size).toInt() + palette.size) % palette.size])
}

/**
 * A face cut from a photo's preview by its [box] (0…1, x y w h), a little wider and square, in a circle; the initials
 * on the person's colour while the preview loads or when there is none.
 */
@Composable
internal fun FaceCrop(item: DNode?, box: List<Double>?, size: Dp, initials: String, color: Color, modifier: Modifier = Modifier) {
    val picture = item?.let { rememberDrivePreview(it, large = true) }
    Box(modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        if (picture != null && box != null && box.size == 4) {
            ComposeCanvas(Modifier.size(size)) {
                val iw = picture.width.toFloat()
                val ih = picture.height.toFloat()
                // the face with room around it, square, inside the picture
                val cx = ((box[0] + box[2] / 2) * iw).toFloat()
                val cy = ((box[1] + box[3] / 2) * ih).toFloat()
                val side = (max(box[2] * iw, box[3] * ih) * 1.5).toFloat().coerceAtMost(minOf(iw, ih)).coerceAtLeast(8f)
                val left = (cx - side / 2).coerceIn(0f, iw - side)
                val top = (cy - side / 2).coerceIn(0f, ih - side)
                drawImage(
                    picture,
                    srcOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    srcSize = IntSize(side.roundToInt(), side.roundToInt()),
                    dstSize = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()),
                )
            }
        } else {
            val sp = (size.value * 0.32f).coerceIn(11f, 30f)
            Text(initials, color = Color(0xFF0D1015), fontWeight = FontWeight.Bold, fontSize = sp.sp, maxLines = 1)
        }
    }
}

/** The face of [p] (its cover), or its initials. */
@Composable
internal fun PersonFace(p: DPerson, size: Dp, modifier: Modifier = Modifier) {
    FaceCrop(p.cover?.item, p.cover?.box, size, DrivePlacesLogic.initials(p.name), personColor(p.id), modifier)
}

/** "Wer ist das?" as a dashed circle with a question mark. */
@Composable
private fun WhoCircle(size: Dp) {
    val x = GateControlTheme.extraColors
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surface).border(1.dp, x.warn, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text("?", color = x.warn, fontWeight = FontWeight.Bold, fontSize = 20.sp) }
}

// ─── the Photos page: people and places ─────────────────────────────

/** A heading of the Photos page with a link on the right ("Alle", "Karte"). */
@Composable
internal fun PhotosSectionHead(title: String, link: String?, onLink: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() })
        if (link != null && onLink != null) {
            TextButton(onClick = onLink, modifier = Modifier.heightIn(min = 44.dp)) {
                Text(link, color = GateControlTheme.extraColors.accentText, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** "Personen": the named people as round faces, then "Wer ist das?" for someone without a name. */
@Composable
internal fun PeopleBar(named: List<DPerson>, unnamed: DPerson?, onPerson: (DPerson) -> Unit, onWho: (DPerson) -> Unit) {
    val x = GateControlTheme.extraColors
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        named.forEach { p ->
            Column(
                Modifier.width(66.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { onPerson(p) }.padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PersonFace(p, 58.dp)
                Text(p.name.orEmpty(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (unnamed != null) {
            Column(
                Modifier.width(72.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { onWho(unnamed) }.padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                WhoCircle(58.dp)
                Text(stringResource(R.string.drive_pp_who), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = x.warn, maxLines = 1)
            }
        }
    }
}

/**
 * "Orte": a small map of the places (a tap opens the map), the places with most photos as chips below. The list
 * builds this item again whenever it scrolls back: once the map's tiles were all there it is a still picture of it
 * ([PhotoMapStills]), no map of its own.
 */
@Composable
internal fun PlacesCard(places: List<DPlaceSummary>, clusters: List<DCluster>, onMap: () -> Unit, onPlace: (DPlaceSummary) -> Unit) {
    val x = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val desc = stringResource(R.string.drive_pp_map_open)
    val fit = remember(places) { DrivePlacesLogic.around(places.take(12).map { it.lat to it.lon }) }
    BoxWithConstraints(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(16.dp)).background(x.panel2)) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val key = remember(fit, clusters, w, h, scheme) {
            val bundles = clusters.joinToString(";") { "${it.lat},${it.lon},${it.count}" }
            "$fit|$bundles|${w}x$h|${scheme.primary.toArgb()},${scheme.surface.toArgb()},${scheme.background.toArgb()}"
        }
        val still = PhotoMapStills[key]
        if (still != null) {
            Image(still, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            OsmAttribution(Modifier.align(Alignment.BottomEnd))
        } else {
            PhotoMap(
                clusters = clusters,
                selected = null,
                fit = fit,
                interactive = false,
                onCluster = { onMap() },
                onMoved = { _, _ -> },
                contentDescription = desc,
                modifier = Modifier.fillMaxWidth().height(120.dp),
                stillKey = key,
                onStill = PhotoMapStills::put,
            )
        }
        // a still picture: a tap anywhere opens the map
        Box(Modifier.matchParentSize().clickable(role = Role.Button, onClickLabel = desc, onClick = onMap).semantics { contentDescription = desc })
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        places.take(8).forEach { p ->
            Box(
                Modifier.height(36.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, x.border2, RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button) { onPlace(p) }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text("${p.name} · ${countText(p.count)}", style = MaterialTheme.typography.titleSmall, maxLines = 1) }
        }
    }
}

/** 1.204 in the phone's language. */
internal fun countText(n: Int): String = java.text.NumberFormat.getIntegerInstance().format(n)

/** The search field of the Photos page: a tap opens the search with suggestions. */
@Composable
internal fun PhotosSearchField(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val x = GateControlTheme.extraColors
    val hint = stringResource(R.string.drive_pp_search_hint)
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp)).background(x.panel2)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.drive_pp_search_title), onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(GcIcons.Search, contentDescription = null, tint = x.muted, modifier = Modifier.size(18.dp))
        Text(hint, color = x.muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ─── the map ───────────────────────────────────────────────────────

/**
 * OpenStreetMap with the photos' bundles (osmdroid, the same tiles, User-Agent and cache as Familie's maps;
 * the attribution always shows). [fit] is shown first; [onMoved] hears the box and zoom after moving (osmdroid's
 * delay, the view model throttles further); a tap on a bundle picks it. Not [interactive]: a still picture. With a
 * [stillKey], [onStill] gets a picture of the map once its tiles are all there (again for a new key).
 */
@Composable
internal fun PhotoMap(
    clusters: List<DCluster>,
    selected: DCluster?,
    fit: DBbox?,
    interactive: Boolean,
    onCluster: (DCluster?) -> Unit,
    onMoved: (DBbox, Int) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    center: GeoPoint? = null,
    stillKey: String? = null,
    onStill: (String, ImageBitmap) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = LocalDensity.current.density
    val scheme = MaterialTheme.colorScheme
    val tap = rememberUpdatedState(onCluster)
    val moved = rememberUpdatedState(onMoved)
    val stillFor = rememberUpdatedState(stillKey)
    val still = rememberUpdatedState(onStill)
    val overlay = remember { ClusterOverlay(density) }
    val map = remember {
        configureOsm(context)
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(interactive)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 2.0
            maxZoomLevel = 19.0
            controller.setZoom(3.0)
            overlays.add(overlay)
            // a still picture: touches end here (a tap still counts as a click for accessibility)
            if (!interactive) setOnTouchListener { v, e -> if (e.action == MotionEvent.ACTION_UP) v.performClick(); true }
            addMapListener(
                DelayedMapListener(
                    object : MapListener {
                        override fun onScroll(event: ScrollEvent?): Boolean = report()
                        override fun onZoom(event: ZoomEvent?): Boolean = report()
                        private fun report(): Boolean {
                            val b = boundingBox ?: return false
                            moved.value(DBbox(b.lonWest, b.latSouth, b.lonEast, b.latNorth), zoomLevelDouble.roundToInt())
                            return false
                        }
                    },
                    250,
                ),
            )
        }
    }
    // the first view (and a new one) once, posted after a layout with a size: never in the layout pass itself
    val fitter = remember(map) {
        OsmFitter(map).apply {
            // the view's box once shown (moving reports it from then on)
            onShown = { map.post { map.boundingBox?.let { b -> moved.value(DBbox(b.lonWest, b.latSouth, b.lonEast, b.latNorth), map.zoomLevelDouble.roundToInt()) } } }
        }
    }
    DisposableEffect(fitter) { onDispose { fitter.release() } }
    overlay.onTap = { c -> tap.value(c) }
    overlay.colors(scheme.primary.toArgb(), scheme.onPrimary.toArgb(), scheme.onSurface.toArgb(), scheme.surface.toArgb(), scheme.background.toArgb())

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
        livePhotoMaps++
        // the still picture: after a frame whose tiles are all loaded (osmdroid runs this after drawing them)
        val tiles = map.overlayManager.tilesOverlay.tileStates
        var alive = true
        var taken: String? = null
        val capture = Runnable {
            val k = stillFor.value ?: return@Runnable
            if (k == taken || !fitter.shown || tiles.total == 0 || tiles.upToDate + tiles.expired < tiles.total) return@Runnable
            taken = k
            map.post {
                if (!alive || stillFor.value != k || map.width <= 0 || map.height <= 0) return@post
                runCatching { map.drawToBitmap() }.getOrNull()?.let { still.value(k, it.asImageBitmap()) }
            }
        }
        tiles.runAfters.add(capture)
        onDispose {
            alive = false
            tiles.runAfters.remove(capture)
            lifecycle.removeObserver(observer)
            map.onPause()
            map.onDetach()
            // the tiles osmdroid keeps for reuse (it never lets them go): emptied after the last of these maps
            if (--livePhotoMaps == 0) BitmapPool.getInstance().clearBitmapPool()
        }
    }

    Box(modifier.semantics { this.contentDescription = contentDescription }) {
        AndroidView(
            factory = { map },
            modifier = Modifier.matchParentSize(),
            update = { view ->
                overlay.clusters = clusters
                overlay.selected = selected
                // the first box (and a new one) is shown once; moving by hand is not undone
                if (center != null) {
                    fitter.request("c:${center.latitude},${center.longitude}") { m ->
                        m.controller.setZoom(CLOSE_ZOOM)
                        m.controller.setCenter(center)
                        true
                    }
                } else if (fit != null) {
                    fitter.request("b:${fit.west},${fit.south},${fit.east},${fit.north}") { m ->
                        m.showBox(fit.north, fit.east, fit.south, fit.west, (24 * density).roundToInt(), CLOSE_ZOOM)
                    }
                }
                view.invalidate()
            },
        )
        OsmAttribution(Modifier.align(Alignment.BottomEnd))
    }
}

/** A single place or a tiny box: this close (a town's streets). */
private const val CLOSE_ZOOM = 14.0

/** PhotoMaps on the screen (main thread): the last one gone empties osmdroid's bitmap pool. */
private var livePhotoMaps = 0

/**
 * The head map of Fotos as still pictures, by what they show (places, bundles, size, colours); the newest few are kept.
 * Main thread; a new one recomposes the card, which then lets its map go.
 */
internal object PhotoMapStills {
    private const val MAX = 3
    private val stills = mutableStateMapOf<String, ImageBitmap>()
    private val order = ArrayDeque<String>()

    operator fun get(key: String): ImageBitmap? = stills[key]

    fun put(key: String, still: ImageBitmap) {
        if (stills.put(key, still) == null) order.addLast(key)
        while (order.size > MAX) stills.remove(order.removeFirst())
    }
}

/** The bundles as round labels with their count; the chosen one light. A tap on one picks it, elsewhere none. */
private class ClusterOverlay(private val density: Float) : Overlay() {
    var clusters: List<DCluster> = emptyList()
    var selected: DCluster? = null
    var onTap: (DCluster?) -> Unit = {}
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private var accent = 0
    private var onAccent = 0
    private var light = 0
    private var dark = 0
    private var edge = 0
    private val drawn = ArrayList<Pair<DCluster, android.graphics.RectF>>()

    fun colors(accent: Int, onAccent: Int, light: Int, dark: Int, edge: Int) {
        this.accent = accent
        this.onAccent = onAccent
        this.light = light
        this.dark = dark
        this.edge = edge
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        drawn.clear()
        val p = android.graphics.Point()
        text.textSize = 13 * density
        ring.strokeWidth = 3 * density
        ring.color = edge
        for (c in clusters.sortedBy { it.count }) {
            mapView.projection.toPixels(GeoPoint(c.lat, c.lon), p)
            val label = countText(c.count)
            val w = max(text.measureText(label) + 20 * density, 44 * density)
            val h = 44 * density
            val r = android.graphics.RectF(p.x - w / 2, p.y - h / 2, p.x + w / 2, p.y + h / 2)
            val on = same(c, selected)
            fill.color = if (on) light else accent
            canvas.drawRoundRect(r, h / 2, h / 2, fill)
            canvas.drawRoundRect(r, h / 2, h / 2, ring)
            text.color = if (on) dark else onAccent
            canvas.drawText(label, p.x.toFloat(), p.y - (text.descent() + text.ascent()) / 2, text)
            drawn += c to r
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val hit = drawn.lastOrNull { it.second.contains(e.x, e.y) }?.first
            ?: drawn.minByOrNull { hypot(it.second.centerX() - e.x, it.second.centerY() - e.y) }?.takeIf { hypot(it.second.centerX() - e.x, it.second.centerY() - e.y) < 32 * density }?.first
        onTap(hit)
        return hit != null
    }

    private fun same(a: DCluster, b: DCluster?): Boolean =
        b != null && (if (a.key != null) a.key == b.key else abs(a.lat - b.lat) < 1e-9 && abs(a.lon - b.lon) < 1e-9)
}

/** The phone's last known position (only with the location permission); null otherwise. */
internal fun lastLocation(context: Context): GeoPoint? {
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION) && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) return null
    val lm = context.getSystemService(LocationManager::class.java) ?: return null
    return try {
        lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }?.let { GeoPoint(it.latitude, it.longitude) }
    } catch (e: SecurityException) {
        null
    }
}

/** A dot for a person in a chip (their face). */
@Composable
internal fun PersonDot(id: Long, name: String?, size: Dp = 24.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(personColor(id)), contentAlignment = Alignment.Center) {
        Text(DrivePlacesLogic.initials(name), color = Color(0xFF0D1015), fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** A round chip with a person's dot and a text (selected: accent). */
@Composable
internal fun PersonChip(id: Long, name: String?, text: String, selected: Boolean, onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    Row(
        Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(99.dp))
            .background(if (selected) x.accentBg else MaterialTheme.colorScheme.surface)
            .border(1.dp, if (selected) Color.Transparent else x.border2, RoundedCornerShape(99.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PersonDot(id, name, 28.dp)
        Text(text, style = MaterialTheme.typography.titleSmall, color = if (selected) x.accentText else MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

/** A plain round chip (a place). */
@Composable
internal fun TextChip(text: String, onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    Box(
        Modifier.height(36.dp).clip(RoundedCornerShape(99.dp)).border(1.dp, x.border2, RoundedCornerShape(99.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 1) }
}
