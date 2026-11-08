package com.gatecontrol.android.ui.drive

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.gatecontrol.android.drive.DMemoryCard
import com.gatecontrol.android.drive.DMemoryCollection
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DOverview
import com.gatecontrol.android.ui.theme.GateControlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drive 1.28: „Erinnerungen“ of the overview on a day without „Heute vor X Jahren“ — the picks with the titles of the
 * page „Erinnerungen“; a card opens its collection, „Alle“ the page. With a day the days show as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "en-w411dp-h891dp")
class DriveOverviewMemoryPicksTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun node(id: Long) = DNode(id, "mine:7", 2, "IMG_$id.jpg", false, 10, "image/jpeg", null, 1, "2026-10-30T10:00:00Z", null, null)

    private val picks = listOf(
        DMemoryCollection("trip:2025-07-10:2025-07-14", "trip", 16, node(812), placeName = "Hamburg", places = listOf("Hamburg"), days = 5, firstDay = "2025-07-10", lastDay = "2025-07-14"),
        DMemoryCollection("person:7", "person", 11, node(640), personId = 7, personName = "Noah"),
        DMemoryCollection("month:2025-10", "month", 20, node(901), yearsAgo = 1, month = "2025-10"),
        DMemoryCollection("year:2024", "year", 71, null, year = 2024),
    )

    private val opened = mutableListOf<String>()
    private var all = 0
    private var photos = 0
    private val days = mutableListOf<DMemoryCard>()

    private val actions = OverviewActions(
        search = {}, backup = {}, storage = {}, deadline = {}, deadlines = {}, review = {}, toFile = {}, file = {}, folder = {},
        files = {}, photos = { photos++ }, memory = { days += it }, people = {}, person = {}, who = {}, map = {}, place = {},
        memories = { all++ }, pick = { opened += it.id },
    )

    private fun show(o: DOverview) = rule.setContent { GateControlTheme { OverviewPage(o, null, emptyList(), actions) } }

    @Test
    fun `without a day the picks show with the page's titles, a card opens its collection, Alle the page`() {
        show(DOverview(memoryPicks = picks))
        rule.onNodeWithText("Memories").assertExists()
        rule.onNodeWithText("Hamburg").assertExists()
        rule.onNodeWithText("With Noah").assertExists()
        rule.onNodeWithText("16 photos").assertExists()
        rule.onNodeWithText("Noah", substring = true).performClick()
        rule.onNodeWithText("All").performClick()
        rule.runOnIdle {
            assertEquals(listOf("person:7"), opened)
            assertEquals(1, all)
            assertEquals(0, photos)
            assertEquals(emptyList<DMemoryCard>(), days)
        }
    }

    @Test
    fun `a day shows as before and the picks stay out`() {
        val day = DMemoryCard(2, "2024-11-08", node(31), 30, id = "day:2024-11-08")
        show(DOverview(memories = listOf(day), memoryPicks = picks))
        rule.onAllNodesWithText("Hamburg").assertCountEquals(0)
        rule.onAllNodesWithText("With Noah").assertCountEquals(0)
        rule.onNodeWithText("30 photos", substring = true).performClick()
        rule.runOnIdle {
            assertEquals(listOf(day), days)
            assertEquals(emptyList<String>(), opened)
        }
    }

    @Test
    fun `without days and picks there is no Erinnerungen`() {
        show(DOverview(memoryPicks = emptyList()))
        rule.onAllNodesWithText("Memories").assertCountEquals(0)
    }

    @Test
    fun `an older Drive without the page - Alle opens Fotos`() {
        rule.setContent { GateControlTheme { OverviewPage(DOverview(memoryPicks = picks), null, emptyList(), actions.copy(memories = null)) } }
        rule.onNodeWithText("All").performClick()
        rule.runOnIdle { assertEquals(1, photos) }
    }
}
