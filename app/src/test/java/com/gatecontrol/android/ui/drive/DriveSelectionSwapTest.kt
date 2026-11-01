package com.gatecontrol.android.ui.drive

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.theme.GateControlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drive's selection mode: a long press ticks a photo and the head changes — the photos below must stay exactly where
 * they were (the finger is still on the screen; anything moving under it ticks the wrong photos).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DriveSelectionSwapTest {

    @get:Rule
    val rule = createComposeRule()

    private fun node(id: Long) = DNode(id, "mine:7", 2, "p$id.jpg", false, 10, "image/jpeg", null, 1, null, null, null, takenAt = "2026-10-0${id}T10:00:00Z")

    @Test
    fun `the photos below the head do not move when ticking starts and ends`() {
        var selecting by mutableStateOf(false)
        rule.setContent {
            GateControlTheme {
                Column {
                    SelectionSwap(
                        selecting,
                        bar = { GcSubpageBar("Fotos", onBack = {}, backDescription = "Zurück") },
                        selection = { SelectionHead(count = 1, line = "Zu prüfen – wohin gehört es? Tippe auf Arbeit oder Privat, die KI lernt daraus.", allSelected = false, onClose = {}, onAll = {}) },
                    )
                    Row(Modifier.inert(selecting)) {
                        GcFilterChip("Alle", selected = true, onClick = {})
                        GcFilterChip("Privat", selected = false, onClick = {})
                    }
                    Text("3 Fotos zu prüfen", Modifier.inert(selecting))
                    Box(Modifier.size(96.dp).testTag("tile"))
                }
            }
        }
        val before = rule.onNodeWithTag("tile").getBoundsInRoot()
        selecting = true
        rule.waitForIdle()
        assertEquals(before, rule.onNodeWithTag("tile").getBoundsInRoot())
        selecting = false
        rule.waitForIdle()
        assertEquals(before, rule.onNodeWithTag("tile").getBoundsInRoot())
    }

    @Test
    fun `a ticked tile shrinks inside its own cell, its neighbours stay`() {
        var selected by mutableStateOf(emptySet<Long>())
        val pages = PhotoPages(items = (1L..6L).map(::node), loaded = true)
        rule.setContent {
            GateControlTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    PhotoGrid(pages = pages, selected = selected, onOpen = {}, onToggle = {}, onMore = {}, onRetry = {}, empty = "", onSelect = { selected = it })
                }
            }
        }
        fun place(name: String) = rule.onNodeWithContentDescriptionPrefix(name).getBoundsInRoot()
        val second = place("p2.jpg")
        val first = place("p1.jpg")
        selected = setOf(1L)
        rule.waitForIdle()
        assertEquals(second, place("p2.jpg"))
        assertEquals(first, place("p1.jpg"))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithContentDescriptionPrefix(prefix: String) =
        onNode(androidx.compose.ui.test.SemanticsMatcher("starts with $prefix") { n ->
            n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.any { it.startsWith(prefix) } == true
        })
}
