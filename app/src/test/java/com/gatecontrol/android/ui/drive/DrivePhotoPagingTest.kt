package com.gatecontrol.android.ui.drive

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DPhotos
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * The Photos timeline with thousands of photos: scrolled to the end, every photo is there once, nothing crashes — also
 * with photos of the same moment at the page boundaries, photos that move between two pages while scrolling, a failed
 * page and a reload in between (the background sorting of marked photos reloads the timeline).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DrivePhotoPagingTest {

    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After
    fun tearDown() = scope.cancel()

    /** Drive's `GET photos` (photos.js `page`): newest first, `before` exclusive, a page ends after every photo of its last moment. */
    private class FakeDrive(count: Int) {
        val photos = HashMap<Long, Long>()
        var calls = 0
        var failAt = -1
        var between: (FakeDrive) -> Unit = {}

        init {
            var t = Instant.parse("2026-10-31T12:00:00Z").toEpochMilli()
            for (id in 1L..count) {
                // many photos of one second (a burst), now and then a long gap (another month)
                if (id % 9L != 0L && id !in 230L..560L) t -= if (id % 97L == 0L) 20L * 24 * 3_600_000 else 60_000L
                photos[id] = t
            }
        }

        fun node(id: Long) = DNode(id, "mine:7", 2, "p$id.jpg", false, 10, "image/jpeg", null, 1, null, null, null, takenAt = Instant.ofEpochMilli(photos.getValue(id)).toString())

        fun page(before: String?, limit: Int): DResult<DPhotos> {
            val n = calls++
            between(this)
            if (n == failAt) return DResult.Offline
            val b = before?.let { Instant.parse(it).toEpochMilli() }
            val sorted = photos.entries.filter { b == null || it.value < b }.sortedWith(compareByDescending<Map.Entry<Long, Long>> { it.value }.thenByDescending { it.key })
            if (sorted.size <= limit) return DResult.Ok(DPhotos(sorted.map { node(it.key) }, null))
            val out = sorted.take(limit).toMutableList()
            val last = out.last()
            out += sorted.drop(limit).filter { it.value == last.value }
            val more = sorted.any { it.value < last.value }
            return DResult.Ok(DPhotos(out.map { node(it.key) }, if (more) Instant.ofEpochMilli(last.value).toString() else null))
        }
    }

    private fun scrollToEnd(pager: PhotoPager, onMore: () -> Unit = {}) {
        rule.setContent {
            GateControlTheme {
                val pages by pager.state.collectAsState()
                Box(Modifier.size(400.dp, 800.dp)) {
                    PhotoGrid(
                        pages = pages,
                        selected = emptySet(),
                        onOpen = {},
                        onToggle = {},
                        onMore = { onMore(); pager.more() },
                        onRetry = pager::retry,
                        empty = "",
                        months = mapOf("2026-10" to 4500),
                        header = {
                            item(span = { GridItemSpan(maxLineSpan) }) { Text("Personen") }
                            item(span = { GridItemSpan(maxLineSpan) }) { Text("Orte") }
                        },
                        onSelect = {},
                    )
                }
            }
        }
        pager.reload()
        repeat(400) {
            rule.waitForIdle()
            if (pager.state.value.end) return
            rule.onNode(hasScrollToKeyAction()).performScrollToKey("footer")
        }
    }

    private fun assertComplete(pager: PhotoPager, drive: FakeDrive) {
        val items = pager.state.value.items
        assertTrue("paging ended", pager.state.value.end)
        assertEquals(items.size, items.map { it.id }.toSet().size)
        assertEquals(drive.photos.keys, items.map { it.id }.toSet())
    }

    @Test
    fun `4500 photos in pages with ties at the boundaries are all there once`() {
        val drive = FakeDrive(4500)
        val pager = PhotoPager(scope) { before -> drive.page(before, 120) }
        scrollToEnd(pager)
        assertComplete(pager, drive)
    }

    @Test
    fun `photos moving between two pages while scrolling do not break the grid`() {
        val drive = FakeDrive(4500)
        // between the fetches: a photo already shown moves into a later page, a new one comes on top
        drive.between = { d ->
            if (d.calls in 3..30) {
                d.photos[d.calls * 50L] = d.photos.getValue(d.calls * 50L) - 40L * 24 * 3_600_000
                d.photos[100_000L + d.calls] = Instant.parse("2026-11-01T12:00:00Z").toEpochMilli()
            }
        }
        val pager = PhotoPager(scope) { before -> drive.page(before, 120) }
        scrollToEnd(pager)
        val ids = pager.state.value.items.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(pager.state.value.end)
    }

    @Test
    fun `a failed page does not stop the paging for good`() {
        val drive = FakeDrive(1500).apply { failAt = 4 }
        val pager = PhotoPager(scope, retryMs = 0) { before -> drive.page(before, 120) }
        scrollToEnd(pager)
        assertComplete(pager, drive)
    }

    @Test
    fun `a reload while scrolling (sorted in the background) still ends with every photo`() {
        val drive = FakeDrive(2000)
        val pager = PhotoPager(scope) { before -> drive.page(before, 120) }
        var mores = 0
        scrollToEnd(pager) { if (++mores == 5 || mores == 9) pager.reload(quiet = true) }
        assertComplete(pager, drive)
        // at the end, sorted again: the list stays as long as it was (not back to its first page)
        pager.reload(quiet = true)
        rule.waitForIdle()
        assertComplete(pager, drive)
    }
}
