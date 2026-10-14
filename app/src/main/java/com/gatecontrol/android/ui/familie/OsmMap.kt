package com.gatecontrol.android.ui.familie

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gatecontrol.android.BuildConfig
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.theme.GateControlTheme
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.io.File

/** A point on the map (WGS 84). */
data class LatLon(val lat: Double, val lon: Double)

/**
 * OpenStreetMap map (osmdroid, standard OSM tiles) with one marker and an
 * optional circle (accuracy or reminder radius). Tiles are requested with
 * the app id as User-Agent, as the OSM tile policy asks, and cached in the
 * app's cache; the attribution is always shown. [onTap] makes the map a
 * picker (place reminders).
 */
@Composable
fun OsmMap(
    point: LatLon?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    radiusM: Double? = null,
    zoom: Double = 16.0,
    onTap: ((LatLon) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val accent = MaterialTheme.colorScheme.primary
    val tap = rememberUpdatedState(onTap)
    val map = remember {
        configureOsm(context)
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
            controller.setZoom(zoom)
        }
    }

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

    Box(modifier.clip(RoundedCornerShape(16.dp)).semantics { this.contentDescription = contentDescription }) {
        AndroidView(
            factory = { map },
            update = { view ->
                view.overlays.clear()
                if (tap.value != null) {
                    view.overlays.add(
                        MapEventsOverlay(object : MapEventsReceiver {
                            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                                tap.value?.invoke(LatLon(p.latitude, p.longitude))
                                return true
                            }

                            override fun longPressHelper(p: GeoPoint): Boolean = false
                        }),
                    )
                }
                if (point != null) {
                    val gp = GeoPoint(point.lat, point.lon)
                    if (radiusM != null && radiusM > 0) {
                        view.overlays.add(
                            Polygon(view).apply {
                                points = Polygon.pointsAsCircle(gp, radiusM)
                                fillPaint.color = accent.copy(alpha = 0.18f).toArgb()
                                outlinePaint.color = accent.toArgb()
                                outlinePaint.strokeWidth = 3f
                                setOnClickListener { _, _, _ -> false }
                            },
                        )
                    }
                    view.overlays.add(
                        Marker(view).apply {
                            position = gp
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            setInfoWindow(null)
                            setOnMarkerClickListener { _, _ -> true }
                        },
                    )
                    // Only a new point moves the map (typing elsewhere must not re-centre it);
                    // one picked on the world view is zoomed in to.
                    if (view.tag != point) {
                        view.tag = point
                        if (view.zoomLevelDouble < MIN_POINT_ZOOM) view.controller.setZoom(zoom.coerceAtLeast(MIN_POINT_ZOOM))
                        view.controller.setCenter(gp)
                    }
                }
                view.invalidate()
            },
        )
        Text(
            stringResource(R.string.familie_osm_attribution),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .background(GateControlTheme.extraColors.panel2.copy(alpha = 0.85f), RoundedCornerShape(topStart = 8.dp))
                .clickable { openUrl(context, "https://www.openstreetmap.org/copyright") }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private const val MIN_POINT_ZOOM = 14.0

/** osmdroid settings: User-Agent = application id (OSM tile policy), caches in the app's cache dir. */
private fun configureOsm(context: Context) {
    val config = Configuration.getInstance()
    config.userAgentValue = BuildConfig.APPLICATION_ID
    val base = File(context.cacheDir, "osmdroid")
    config.osmdroidBasePath = base
    config.osmdroidTileCache = File(base, "tiles")
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
