package com.gatecontrol.android.ui.drive

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DriveBulk
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.theme.GateControlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Photos page as it is built (title row, chips with the count of "Prüfen", the months, the bar below while
 * ticking; Drive 1.18: people, places and memories are on the overview): a long press ticks a photo — no photo on the screen may move, at the top of the list and
 * scrolled into it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class DrivePhotosSelectionJumpTest {

    @get:Rule
    val rule = createComposeRule()

    // a new month every 7 photos: headings between the rows on every screen
    private val nodes = (1L..300L).map { id ->
        val month = 12 - ((id - 1) / 7 % 12)
        val year = 2026 - ((id - 1) / 84)
        DNode(id, "mine:7", 2, "p$id.jpg", false, 10, "image/jpeg", null, 1, null, null, null, takenAt = "%04d-%02d-15T10:00:00Z".format(year, month))
    }

    private fun page(selected: () -> Set<Long>, onSelect: (Set<Long>) -> Unit) {
        val pages = PhotoPages(items = nodes, loaded = true)
        val months = DriveLogic_months(nodes)
        rule.setContent {
            GateControlTheme {
                val selecting = selected().isNotEmpty()
                Box(Modifier.fillMaxSize()) {
                    Column {
                        SelectionSwap(
                            selecting,
                            bar = { GcSubpageBar("Fotos", onBack = {}, backDescription = "Zurück") },
                            selection = { SelectionHead(count = selected().size, line = "Zu prüfen – wohin gehört es? Tippe auf Arbeit oder Privat.", allSelected = false, onClose = {}, onAll = null) },
                        )
                        Row(Modifier.inert(selecting).padding(horizontal = 12.dp)) {
                            WorkChip("Alle", selected = true, onClick = {})
                            WorkChip("Prüfen", selected = false, count = 3, onClick = {})
                        }
                        Box(Modifier.weight(1f)) {
                            PhotoGrid(
                                pages = pages,
                                selected = selected(),
                                onOpen = {},
                                onToggle = {},
                                onMore = {},
                                onRetry = {},
                                empty = "",
                                months = months,
                                onSelect = onSelect,
                            )
                        }
                        if (selecting) {
                            SelectionActionBar(
                                listOf(
                                    BarAction(GcIcons.Share, "Senden", DriveBulk.Can.YES) {},
                                    BarAction(GcIcons.Trash, "Löschen", DriveBulk.Can.YES) {},
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun ComposeContentTestRule.tile(id: Long) = try {
        onNode(SemanticsMatcher("photo $id") { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("p$id.jpg,") } == true
        }).fetchSemanticsNode().positionInRoot
    } catch (e: AssertionError) {
        throw AssertionError(e.message.orEmpty().take(400) + "\n" + onRoot().printToString().lines().filter { "ContentDescription" in it || "Text" in it }.takeLast(40).joinToString("\n"), e)
    }

    private fun assertStill(ids: List<Long>, tick: Long) {
        var selected by mutableStateOf(emptySet<Long>())
        page({ selected }, { selected = it })
        if (ids.first() > 20) {
            rule.onNode(hasScrollToKeyAction()).performScrollToKey("p-${ids.first()}")
        }
        rule.waitForIdle()
        val before = ids.associateWith { rule.tile(it) }
        selected = setOf(tick)
        rule.waitForIdle()
        ids.forEach { assertEquals("photo $it while ticking", before.getValue(it), rule.tile(it)) }
        selected = emptySet()
        rule.waitForIdle()
        ids.forEach { assertEquals("photo $it after ticking", before.getValue(it), rule.tile(it)) }
    }

    @Test
    fun `at the top no photo moves when ticking starts and ends`() = assertStill(listOf(1L, 3L, 4L), tick = 4L)

    @Test
    fun `scrolled into the list no photo moves when ticking starts and ends`() = assertStill(listOf(120L, 123L, 127L), tick = 127L)

    private companion object {
        @Suppress("FunctionName")
        fun DriveLogic_months(nodes: List<DNode>): Map<String, Int> =
            nodes.groupingBy { com.gatecontrol.android.drive.DriveLogic.monthOf(it, java.time.ZoneOffset.UTC) ?: "" }.eachCount()
    }
}
