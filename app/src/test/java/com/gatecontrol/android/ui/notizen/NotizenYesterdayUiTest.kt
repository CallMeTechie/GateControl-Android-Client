package com.gatecontrol.android.ui.notizen

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.gatecontrol.android.notizen.NDue
import com.gatecontrol.android.notizen.NMoveOption
import com.gatecontrol.android.notizen.NReminder
import com.gatecontrol.android.notizen.NTask
import com.gatecontrol.android.notizen.NTaskCalendar
import com.gatecontrol.android.notizen.NYesterday
import com.gatecontrol.android.ui.theme.GateControlTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * „Gestern offen geblieben“ in „Mein Tag“ (plugin 1.3.0, mockup „Dasselbe in ‚Mein Tag‘“): the card above „Heute“, each
 * row with Erledigt · Heute · Verschieben, „Alle heute“ at the head; and the marks „Erinnerung“ / „im Kalender“ of a
 * row. With `NOTIZEN_SHOTS=<dir>` the drawn pages are saved there (for a look, not part of CI).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w411dp-h891dp")
class NotizenYesterdayUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun task(id: Long, title: String, due: NDue? = NDue("2026-11-18"), canEdit: Boolean = true, canComplete: Boolean = true) =
        NTask(id, 3, null, title, false, false, false, null, due, due != null, null, null, null, null, false, null, 0, null, null, 0, 0, canComplete = canComplete, canEdit = canEdit)

    private val card = NYesterday(
        "2026-11-18", "Gestern offen geblieben", 2,
        listOf(task(41, "Schrauben kaufen"), task(42, "Rezept abholen", NDue("2026-11-18", "17:00"))),
        listOf(NMoveOption("tomorrow", "2026-11-20", "Morgen"), NMoveOption("weekend", "2026-11-21", "Am Wochenende")),
    )

    private val calls = mutableListOf<String>()
    private val actions = YesterdayActions(
        open = { calls += "open:${it.id}" },
        done = { calls += "done:${it.id}" },
        move = { t, to -> calls += "move:${t.id}:$to" },
        allToday = { calls += "all" },
    )

    private fun show(dark: Boolean = false, canWrite: Boolean = true, card: NYesterday = this.card) {
        rule.setContent {
            GateControlTheme(darkTheme = dark) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("shot")) {
                    val path: (Long?) -> String? = { "Privat › Baumarkt" }
                    val rc = rowCtx("2026-11-19", "de", path, calendar = true)
                    YesterdayCard(card, rc, canWrite, actions)
                    NSectionHeader("Heute", 1)
                    val today = task(60, "Mülltonne rausstellen", NDue("2026-11-19", "09:00")).copy(
                        overdue = false,
                        reminder = NReminder("task:60:1795075200000", null, 1795075200000, mine = true, snoozed = false),
                        calendar = NTaskCalendar("synced", 912, 7, mine = true),
                    )
                    EntryCardForTest(today, rowCtx("2026-11-19", "de", { null }, showDay = false, calendar = true))
                }
            }
        }
        rule.waitForIdle()
        System.getenv("NOTIZEN_SHOTS")?.let { dir ->
            val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
            File(dir).mkdirs()
            File(dir, "yesterday-${if (dark) "dark" else "light"}${if (canWrite) "" else "-readonly"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun `the card - its title, Alle heute and three buttons per row`() {
        show()
        rule.onNodeWithText("Gestern offen geblieben").assertExists()
        rule.onNodeWithText("Alle heute").assertExists()
        rule.onAllNodesWithText("Erledigt").assertCountEquals(2)
        rule.onAllNodesWithText("Heute").assertCountEquals(3) // two rows and the section below
        rule.onAllNodesWithText("Verschieben").assertCountEquals(2)
        // the card stands above „Heute“
        val cardBottom = rule.onNodeWithText("Rezept abholen").getBoundsInRoot().bottom
        assertTrue(rule.onNodeWithText("Mülltonne rausstellen").getBoundsInRoot().top > cardBottom)

        rule.onNodeWithContentDescription("Erledigt: Schrauben kaufen").performClick()
        rule.onNodeWithContentDescription("Heute: Rezept abholen").performClick()
        rule.onNodeWithText("Alle heute").performClick()
        // the row itself (beside its buttons) opens the task
        rule.onNodeWithText("Schrauben kaufen").performTouchInput { click(Offset(24f, 24f)) }
        assertEquals(listOf("done:41", "move:42:today", "all", "open:41"), calls)
    }

    @Test
    fun `Verschieben offers the server's days and another date`() {
        show()
        rule.onNodeWithContentDescription("Verschieben: Schrauben kaufen").performClick()
        rule.onNodeWithText("Morgen").assertExists()
        rule.onNodeWithText("Anderes Datum").assertExists()
        rule.onNodeWithText("Am Wochenende").performClick()
        assertEquals(listOf("move:41:weekend"), calls)
    }

    @Test
    fun `a viewer sees the rows without buttons, a list only to read has no Heute`() {
        show(canWrite = false)
        rule.onAllNodesWithText("Erledigt").assertCountEquals(0)
        rule.onAllNodesWithText("Alle heute").assertCountEquals(0)
        rule.onNodeWithText("Schrauben kaufen").assertExists()
    }

    @Test
    fun `assigned to me in a list I may only read - Erledigt only`() {
        show(card = card.copy(items = listOf(task(41, "Schrauben kaufen", canEdit = false))))
        rule.onAllNodesWithText("Erledigt").assertCountEquals(1)
        rule.onAllNodesWithText("Verschieben").assertCountEquals(0)
        rule.onAllNodesWithText("Alle heute").assertCountEquals(0)
    }

    @Test
    fun `the marks of a task - a reminder to come, its event in my calendar`() {
        show(dark = true)
        rule.onNodeWithText("Erinnerung").assertExists()
        rule.onNodeWithText("im Kalender").assertExists()
    }
}

/** One row in a card, as „Mein Tag“ draws it. */
@androidx.compose.runtime.Composable
private fun EntryCardForTest(task: NTask, ctx: RowCtx) {
    NCard { NEntryRow(task, ctx, {}, {}, divider = false) }
}
