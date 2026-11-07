package com.gatecontrol.android.ui.start

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.gatecontrol.android.R
import com.gatecontrol.android.layout.StartPages
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.vpn.HomeColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Start's page dots lie over the pages (no strip of their own): the cards reach down to the bar, and the last card
 * can still scroll fully above the dots. Taps and the dots' descriptions stay as they were.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PagesWithDotsTest {

    @get:Rule
    val rule = createComposeRule()

    private val ids = listOf(START_PAGE, StartPages.CHATS, PLUS_PAGE)
    private val selected = mutableListOf<Int>()

    private fun setContent(names: Boolean = false) {
        rule.setContent {
            GateControlTheme {
                Box(Modifier.size(360.dp, 640.dp).background(MaterialTheme.colorScheme.background)) {
                    PagesWithDots(
                        dots = { PageDots(ids = ids, current = 0, badges = emptyMap(), names = names, onSelect = { selected += it }) },
                    ) { dotsHeight ->
                        CompositionLocalProvider(LocalStartPagerDotsInset provides dotsHeight) {
                            HomeColumn(Modifier.testTag("page")) {
                                repeat(8) { i ->
                                    Box(Modifier.fillMaxWidth().height(120.dp).background(MaterialTheme.colorScheme.surface).testTag("card$i"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private val dotsLabel get() = ApplicationProvider.getApplicationContext<Application>().getString(R.string.pages_dots_desc)

    @Test
    fun `the page reaches down to the bar and the dots lie over its lower edge`() {
        setContent()
        val root = rule.onRoot().getBoundsInRoot()
        val page = rule.onNodeWithTag("page").getBoundsInRoot()
        val dots = rule.onNodeWithContentDescription(dotsLabel).getBoundsInRoot()
        assertEquals(root.bottom, page.bottom)
        assertTrue("dots above the bottom edge", dots.bottom <= root.bottom)
        assertTrue("dots over the page", dots.top > page.top && dots.top < page.bottom)
    }

    private fun assertLastCardAboveDots() {
        rule.onNodeWithTag("page").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        rule.waitForIdle()
        val dots = rule.onNodeWithContentDescription(dotsLabel).getBoundsInRoot()
        val last = rule.onNodeWithTag("card7").getBoundsInRoot()
        assertTrue("last card ${last.bottom} ends above the dots ${dots.top}", last.bottom <= dots.top)
    }

    @Test
    fun `the last card scrolls fully above the dots`() {
        setContent()
        assertLastCardAboveDots()
    }

    @Test
    fun `with the names line the last card still scrolls above the dots`() {
        setContent(names = true)
        assertLastCardAboveDots()
    }

    @Test
    fun `the dots stay tabs with their descriptions and a tap shows the page`() {
        setContent()
        val tabs = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        val nodes = rule.onAllNodes(tabs).fetchSemanticsNodes()
        assertEquals(ids.size, nodes.size)
        rule.onAllNodes(tabs)[0].assertIsSelected()
        rule.onAllNodes(tabs)[2].performClick()
        assertEquals(listOf(2), selected)
        // One description per dot ("Seite 1 von 3: Start" …), none lost.
        val context = ApplicationProvider.getApplicationContext<Application>()
        val first = context.getString(R.string.pages_dot_desc, 1, ids.size, context.getString(R.string.nav_start))
        rule.onAllNodesWithContentDescription(first).fetchSemanticsNodes().let { assertEquals(1, it.size) }
    }
}
