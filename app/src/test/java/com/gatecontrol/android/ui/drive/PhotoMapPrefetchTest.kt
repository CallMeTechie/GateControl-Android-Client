package com.gatecontrol.android.ui.drive

import android.app.Application
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.gatecontrol.android.ui.familie.OsmFitter
import com.gatecontrol.android.ui.familie.configureOsm
import com.gatecontrol.android.ui.familie.showBox
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.TileSystemWebMercator
import org.osmdroid.views.MapView
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * ANR in Drive › Fotos (1.76): the list measures the "Orte" item ahead while scrolling back, Compose lays its map out
 * at 0 × 0 before placing it, and osmdroid's zoomToBoundingBox then never returned. The map has to wait for its size.
 * (Drive 1.18: the small map is on the overview, as a still picture — DriveOverviewScrollTest.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class PhotoMapPrefetchTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    // a hang must fail the test, not the whole run: after a minute the main thread's stack, then the JVM ends
    private val main = Thread.currentThread()
    private val watchdog = Thread {
        try {
            Thread.sleep(60_000)
        } catch (_: InterruptedException) {
            return@Thread
        }
        System.err.println("PhotoMapPrefetchTest hangs:\n" + main.stackTrace.joinToString("\n") { "  at $it" })
        Runtime.getRuntime().halt(1)
    }.apply { isDaemon = true }

    @Before
    fun start() = watchdog.start()

    @After
    fun stop() = watchdog.interrupt()

    @Test
    fun `osmdroid's zoom of a box in a view smaller than its border is NaN`() {
        // what zoomToBoundingBox computes at 0 × 0 with 2 × 63 px border; its Projection then loops on this
        val zoom = TileSystemWebMercator().getBoundingBoxZoom(BoundingBox(53.6, 10.1, 53.5, 9.9), -126, -126)
        assertTrue(zoom.isNaN())
    }

    @Test
    fun `the fitter waits for a size, then shows the box once`() {
        val context = rule.activity
        configureOsm(context)
        lateinit var map: MapView
        lateinit var fitter: OsmFitter
        var shown = 0
        val show: (MapView) -> Boolean = { it.showBox(53.6, 10.1, 53.5, 9.9, border = 63, closeZoom = 14.0) }
        rule.runOnUiThread {
            map = MapView(context).apply {
                minZoomLevel = 2.0
                maxZoomLevel = 19.0
                controller.setZoom(3.0)
            }
            context.setContentView(FrameLayout(context).apply { addView(map, FrameLayout.LayoutParams(0, 0)) })
            fitter = OsmFitter(map)
            fitter.onShown = { shown++ }
            fitter.request("hamburg", show)
        }
        ShadowLooper.idleMainLooper()
        rule.runOnUiThread {
            assertEquals(0, map.height)
            assertEquals(3.0, map.zoomLevelDouble, 0.0)
            assertEquals(0, shown)
            map.layoutParams = FrameLayout.LayoutParams(1080, 315)
        }
        ShadowLooper.idleMainLooper()
        rule.runOnUiThread {
            assertEquals(315, map.height)
            assertEquals(1, shown)
            assertTrue("zoom ${map.zoomLevelDouble}", map.zoomLevelDouble in 9.0..14.0)
            assertEquals(53.55, map.mapCenter.latitude, 0.01)
            assertEquals(10.0, map.mapCenter.longitude, 0.01)
            // moved by hand: the same view again is not shown again, not even after a new layout
            map.controller.setZoom(5.0)
            fitter.request("hamburg", show)
            map.layoutParams = FrameLayout.LayoutParams(1080, 400)
        }
        ShadowLooper.idleMainLooper()
        rule.runOnUiThread {
            assertEquals(5.0, map.zoomLevelDouble, 0.0)
            assertEquals(1, shown)
            fitter.release()
            map.onDetach()
        }
    }
}
