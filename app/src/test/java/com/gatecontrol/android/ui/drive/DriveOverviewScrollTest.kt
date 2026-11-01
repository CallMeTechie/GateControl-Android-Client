package com.gatecontrol.android.ui.drive

import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.SubcomposeLayoutState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Constraints
import com.gatecontrol.android.drive.DActivity
import com.gatecontrol.android.drive.DCluster
import com.gatecontrol.android.drive.DDeadline
import com.gatecontrol.android.drive.DMemoryCard
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DOverview
import com.gatecontrol.android.drive.DOverviewPeople
import com.gatecontrol.android.drive.DOverviewPlaces
import com.gatecontrol.android.drive.DOverviewStorage
import com.gatecontrol.android.drive.DPerson
import com.gatecontrol.android.drive.DPlaceTop
import com.gatecontrol.android.drive.DRecent
import com.gatecontrol.android.drive.DTodo
import com.gatecontrol.android.drive.DriveOverviewLogic
import com.gatecontrol.android.ui.theme.GateControlTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.views.MapView
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * The overview of Drive (1.18) scrolled up and down: it does not hang (ANR of 1.76: a map in a list item measured ahead
 * at 0 × 0), the list items make no map — the small map of "Orte" is drawn once outside the list, at its own size.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class DriveOverviewScrollTest {

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
        System.err.println("DriveOverviewScrollTest hangs:\n" + main.stackTrace.joinToString("\n") { "  at $it" })
        Runtime.getRuntime().halt(1)
    }.apply { isDaemon = true }

    @Before
    fun start() = watchdog.start()

    @After
    fun stop() = watchdog.interrupt()

    private fun node(id: Long, name: String, mime: String? = null) = DNode(id, "family:1", 2, name, false, 10, mime, null, 1, "2026-10-30T10:00:00Z", null, null)

    /** Every section, the places around [lat] / [lon] (another still picture per test). */
    private fun overview(lat: Double, lon: Double) = DOverview(
        todo = DTodo(
            deadlines = listOf(
                DDeadline(1, "payment", "2026-11-04", "Rechnung Stadtwerke zahlen", null, node = node(11, "Rechnung.pdf")),
                DDeadline(2, "cancellation", "2026-11-13", "Kfz-Versicherung kündigen", null),
            ),
            deadlinesTotal = 5,
            review = 12,
            toFile = 3,
            toFileHint = "Familie › Versicherungen",
        ),
        recent = (1L..10L).map { DRecent(node(100 + it, "Datei $it.pdf"), "2026-10-31T09:00:00Z", null, "Familie") },
        memories = (1..4).map { DMemoryCard(it, "${2026 - it}-11-01", node(200L + it, "m$it.jpg", "image/jpeg"), 10 * it) },
        people = DOverviewPeople((1L..8L).map { DPerson(it, "Person $it", 10, false, null, null) }, unnamed = 3),
        places = DOverviewPlaces(
            listOf(DPlaceTop("k:1", "Köln", 1629), DPlaceTop("k:2", "Hamburg", 214), DPlaceTop("k:3", "Palma", 30)),
            DCluster(lat, lon, 3871, null, null),
            3871,
        ),
        activity = (1..5).map {
            DActivity("2026-11-01T08:00:00Z", it, "Person $it", DriveOverviewLogic.ADDED_PHOTOS, 14, "family:1", "Familie", 50L + it, "Familie/Urlaub", node(300L + it, "x$it.jpg", "image/jpeg"))
        },
        storage = DOverviewStorage(212_000_000_000, 1_000_000_000_000, "NAS"),
    )

    private val actions = OverviewActions(
        search = {}, backup = {}, storage = {}, deadline = {}, deadlines = {}, review = {}, toFile = {}, file = {}, folder = {},
        files = {}, photos = {}, memory = {}, people = {}, person = {}, who = {}, map = {}, place = {},
    )

    private fun maps(v: View): List<MapView> = when (v) {
        is MapView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { maps(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test
    fun `scrolling the overview up and down does not hang, and no list item makes a map`() {
        val o = overview(50.94, 6.96)
        val sections = DriveOverviewLogic.sections(o, DriveOverviewLogic.Backup.Saved(1L))
        assertEquals(8, sections.size)
        val state = LazyListState()
        rule.setContent {
            GateControlTheme { OverviewPage(o, DriveOverviewLogic.Backup.Saved(System.currentTimeMillis() - 4 * 60_000), emptyList(), actions, state) }
        }
        rule.waitForIdle()
        repeat(6) {
            rule.onNodeWithTag(OVERVIEW_LIST_TAG).performScrollToIndex(sections.lastIndex)
            rule.waitForIdle()
            ShadowLooper.idleMainLooper()
            rule.onNodeWithTag(OVERVIEW_LIST_TAG).performScrollToIndex(0)
            rule.waitForIdle()
            ShadowLooper.idleMainLooper()
        }
        rule.runOnIdle {
            assertTrue("the list scrolls", state.layoutInfo.totalItemsCount == sections.size)
            // at most the one map that draws the still picture, never one per scroll (list items make none)
            val all = maps(rule.activity.window.decorView)
            assertTrue("maps: ${all.size}", all.size <= 1)
            for (m in all) {
                // outside the list at its own size: never 0 × 0, and its zoom is a number within the map's limits
                assertTrue("size ${m.width} x ${m.height}", m.width > 0 && m.height > 0)
                assertTrue("zoom ${m.zoomLevelDouble}", m.zoomLevelDouble in 2.0..19.0)
            }
        }
    }

    @Test
    fun `the overview measured ahead at 0 x 0 does not zoom its map there`() {
        val sub = SubcomposeLayoutState()
        rule.setContent {
            GateControlTheme {
                SubcomposeLayout(sub, Modifier.fillMaxSize()) { c -> layout(c.maxWidth, c.maxHeight) {} }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle {
            // what a pager or a list does with a page before it is placed
            val handle = sub.precompose("overview") { GateControlTheme { OverviewPage(overview(53.55, 10.0), null, emptyList(), actions) } }
            handle.premeasure(0, Constraints(maxWidth = 0, maxHeight = 0))
        }
        rule.waitForIdle()
        ShadowLooper.idleMainLooper()
        rule.runOnIdle {
            val m = maps(rule.activity.window.decorView).single()
            assertEquals(0, m.width)
            // the zoom it was made with: the fitter waits for a size
            assertEquals(3.0, m.zoomLevelDouble, 0.0)
        }
    }
}
