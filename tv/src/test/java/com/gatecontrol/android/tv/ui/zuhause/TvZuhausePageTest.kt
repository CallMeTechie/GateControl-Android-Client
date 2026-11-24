package com.gatecontrol.android.tv.ui.zuhause

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.gatecontrol.android.familie.AlltagPayload
import com.gatecontrol.android.familie.AlltagWaste
import com.gatecontrol.android.familie.FamilieEvent
import com.gatecontrol.android.familie.shopping.ShoppingItem
import com.gatecontrol.android.familie.shopping.ShoppingList
import com.gatecontrol.android.tv.ui.home.TvStartRows
import com.gatecontrol.android.tv.ui.home.TvStartUi
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import com.gatecontrol.android.tv.zuhause.TvShoppingState
import com.gatecontrol.android.tv.zuhause.TvSourceState
import com.gatecontrol.android.tv.zuhause.TvZuhauseLogic
import com.gatecontrol.android.tv.zuhause.TvZuhauseUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

/** The page "Zuhause" and the start row "Heute zu Hause" on Android 9 (Fire OS 7). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
class TvZuhausePageTest {

    @get:Rule
    val rule = createComposeRule()

    private val today = LocalDate.parse("2026-11-24")
    private val list = ShoppingList("list-0001", "Wocheneinkauf", sort = 1)
    private val milk = ShoppingItem("item-0001", "list-0001", "Milch", qty = "2 l")
    private val bread = ShoppingItem("item-0002", "list-0001", "Brot", checked = true, checkedBy = "Papa")

    private fun ui(undo: ShoppingItem? = null): TvZuhauseUi {
        val alltag = AlltagPayload(emptyList(), listOf(AlltagWaste("2026-11-25", listOf("Gelbe Tonne"), null)))
        val own = listOf(FamilieEvent("1", "Elternabend", "2026-11-24", "18:30", null, false, "none", emptyList(), FamilieEvent.KIND_EVENT, null, "Grundschule"))
        return TvZuhauseUi(
            today = today,
            now = LocalTime.of(20, 30),
            waste = TvZuhauseLogic.waste(alltag.waste, today, 20),
            wasteState = TvSourceState.OK,
            days = TvZuhauseLogic.calendar(own, alltag, today),
            calendarState = TvSourceState.OK,
            lists = listOf(list, ShoppingList("list-0002", "Drogerie", sort = 2)),
            list = list,
            items = listOf(milk, bread),
            open = 1,
            openByList = mapOf("list-0001" to 1),
            shoppingState = TvShoppingState.OK,
            undo = undo,
        )
    }

    @Test
    fun threeColumnsShowWasteCalendarAndShopping() {
        rule.setContent { GateControlTvTheme { TvZuhausePage(ui(), TvZuhauseActions()) } }
        // the column's head, and the bin's line in the calendar
        rule.onAllNodesWithText("Waste collection").assertCountEquals(2)
        rule.onNodeWithContentDescription("Gelbe Tonne, tomorrow", substring = true).assertExists()
        rule.onNodeWithText("TODAY · TUE 24.11.").assertExists()
        rule.onNodeWithText("Elternabend").assertExists()
        rule.onNodeWithText("Grundschule").assertExists()
        rule.onNodeWithText("Wocheneinkauf · 1 open").assertExists()
        rule.onNodeWithText("done by Papa").assertExists()
        rule.onAllNodesWithText("Other list").filter(hasClickAction()).onFirst().assertExists()
    }

    @Test
    fun okTicksAnEntryAndUndoTakesItBack() {
        val toggled = mutableListOf<String>()
        var undone = 0
        rule.setContent {
            GateControlTvTheme {
                TvZuhausePage(ui(undo = milk), TvZuhauseActions(onToggle = { toggled += it.text }, onUndo = { undone++ }))
            }
        }
        rule.onNodeWithContentDescription("Milch, 2 l").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("Milch"), toggled)
        rule.onNodeWithText("“Milch” ticked").assertExists()
        rule.onNodeWithText("Undo").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, undone)
    }

    @Test
    fun addSomethingSendsTheTypedText() {
        val added = mutableListOf<String>()
        rule.setContent { GateControlTvTheme { TvZuhausePage(ui(), TvZuhauseActions(onAdd = { added += it })) } }
        rule.onNodeWithText("Add something").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("OK for the keyboard, then hold the microphone button and speak.").assertExists()
        rule.onNodeWithContentDescription("New entry").performTextInput("Butter und Eier")
        rule.onNodeWithContentDescription("New entry").performImeAction()
        assertEquals(listOf("Butter und Eier"), added)
        rule.onNodeWithText("Add something").assertExists()
    }

    @Test
    fun anotherListIsChosenFromTheDialog() {
        val chosen = mutableListOf<String>()
        rule.setContent { GateControlTvTheme { TvZuhausePage(ui(), TvZuhauseActions(onChooseList = { chosen += it })) } }
        rule.onAllNodesWithText("Other list").filter(hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("Choose a list").assertExists()
        rule.onNodeWithText("Drogerie").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("list-0002"), chosen)
    }

    @Test
    fun startRowShowsBinDateAndShoppingAndOpensThePage() {
        var opened = 0
        // at noon the evening's date is still to come
        val home = ui().copy(now = LocalTime.NOON).home
        rule.setContent {
            GateControlTvTheme {
                TvStartRows(
                    ui = TvStartUi(emptyList(), emptySet(), emptyList(), emptyMap(), null, loading = false, home = home, today = today),
                    onTile = {},
                    onSetpoint = { _, _, _ -> },
                    onCamera = {},
                    onHome = { opened++ },
                )
            }
        }
        rule.onNodeWithText("At home today").assertExists()
        rule.onNodeWithText("put out tomorrow morning").assertExists()
        rule.onNodeWithText("18:30 Elternabend").assertExists()
        rule.onNodeWithText("1 open · Wocheneinkauf").assertExists()
        rule.onNodeWithContentDescription("Gelbe Tonne, put out tomorrow morning").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, opened)
    }

    @Test
    fun missingPluginsSayItInsteadOfAnEmptyPage() {
        val missing = TvZuhauseUi(
            today = today,
            now = LocalTime.NOON,
            wasteState = TvSourceState.MISSING,
            calendarState = TvSourceState.OFFLINE,
            shoppingState = TvShoppingState.MISSING,
        )
        rule.setContent { GateControlTvTheme { TvZuhausePage(missing, TvZuhauseActions()) } }
        rule.onNodeWithText("The Alltag plugin is not available.").assertExists()
        rule.onNodeWithText("No connection – the last state shows.").assertExists()
        rule.onNodeWithText("Shopping lists are not available.").assertExists()
    }
}
