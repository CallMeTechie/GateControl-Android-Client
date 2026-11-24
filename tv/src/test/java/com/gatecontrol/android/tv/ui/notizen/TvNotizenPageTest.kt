package com.gatecontrol.android.tv.ui.notizen

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.gatecontrol.android.notizen.NArea
import com.gatecontrol.android.notizen.NDue
import com.gatecontrol.android.notizen.NList
import com.gatecontrol.android.notizen.NNote
import com.gatecontrol.android.notizen.NNoteSummary
import com.gatecontrol.android.notizen.NNotebook
import com.gatecontrol.android.notizen.NParsedLine
import com.gatecontrol.android.notizen.NProgress
import com.gatecontrol.android.notizen.NTask
import com.gatecontrol.android.tv.notizen.TvNotesAdd
import com.gatecontrol.android.tv.notizen.TvNotesPlace
import com.gatecontrol.android.tv.notizen.TvNotizenUi
import com.gatecontrol.android.tv.notizen.TvTasksToday
import com.gatecontrol.android.tv.ui.home.TvStartRows
import com.gatecontrol.android.tv.ui.home.TvStartUi
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import com.gatecontrol.android.tv.zuhause.TvSourceState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** The page "Notizen" and the start row's tasks card on Android 9 (Fire OS 7). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
class TvNotizenPageTest {

    @get:Rule
    val rule = createComposeRule()

    private val today = LocalDate.parse("2026-11-24")
    private val haushalt = NList(2, "Haushalt", NArea.PRIVATE, null, false, null, null, null, 1, false, emptyList(), open = 6, ext = null)
    private val rezepte = NNotebook(5, "Rezepte", NArea.PRIVATE, null, false, null, null, emptyList(), false, null, null, count = 1, position = 1)

    private fun task(id: Long, title: String, done: Boolean = false, overdue: Boolean = false, due: NDue? = null, progress: NProgress? = null) = NTask(
        id = id, listId = 2, parentId = null, title = title, hasNote = false, important = false, done = done, doneAt = null, due = due,
        overdue = overdue, remindAt = null, repeat = null, repeatText = null, assignee = null, myDay = true, progress = progress, attachments = 0,
        source = null, noteId = null, position = id, updatedAt = 0,
    )

    private val overdue = task(1, "Heizungsableser Termin bestätigen", overdue = true, due = NDue("2026-11-23"))
    private val tyres = task(2, "Winterreifen abholen", due = NDue("2026-11-24", "17:00"))
    private val gift = task(3, "Geschenk für Jonas besorgen", progress = NProgress(2, 5))
    private val glass = task(4, "Altglas wegbringen", done = true)

    private fun ui(place: TvNotesPlace = TvNotesPlace.MyDay, undo: NTask? = null, add: TvNotesAdd = TvNotesAdd.Closed) = TvNotizenUi(
        today = today,
        navState = TvSourceState.OK,
        myDayCount = 3,
        plannedCount = 9,
        lists = listOf(haushalt),
        notebooks = listOf(rezepte),
        place = place,
        placeState = TvSourceState.OK,
        entries = if (place is TvNotesPlace.Notebook) emptyList() else listOf(overdue, tyres, gift, glass),
        notes = if (place is TvNotesPlace.Notebook) listOf(NNoteSummary(8, 5, "Zählerstände", "Strom 48 213", null, null, false, false, null, null, null, null, 0, 0, emptyList(), null, 0, 0, 1)) else emptyList(),
        listNames = mapOf(2L to "Haushalt"),
        undo = undo,
        add = add,
    )

    @Test
    fun myDayShowsTheTasksBesideTheLists() {
        rule.setContent { GateControlTvTheme { TvNotizenPage(ui(), TvNotizenActions()) } }
        rule.onNodeWithText("Notes").assertExists()
        rule.onNodeWithContentDescription("My Day, 3").assertExists()
        rule.onNodeWithContentDescription("Upcoming, 9").assertExists()
        rule.onNodeWithText("LISTS").assertExists()
        rule.onNodeWithContentDescription("Haushalt, 6").assertExists()
        rule.onNodeWithText("NOTEBOOKS").assertExists()
        rule.onNodeWithText("Tuesday, November 24 · 3 open, 1 done").assertExists()
        rule.onNodeWithText("overdue · yesterday").assertExists()
        rule.onNodeWithText("today 17:00").assertExists()
        rule.onNodeWithText("2 of 5 sub tasks").assertExists()
        rule.onNodeWithText("Add task").assertExists()
    }

    @Test
    fun okTicksATaskAndUndoTakesItBack() {
        val toggled = mutableListOf<String>()
        var undone = 0
        rule.setContent {
            GateControlTvTheme {
                TvNotizenPage(ui(undo = tyres), TvNotizenActions(onToggle = { toggled += it.title }, onUndo = { undone++ }))
            }
        }
        rule.onNodeWithContentDescription("Winterreifen abholen, today 17:00, Haushalt").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("Winterreifen abholen"), toggled)
        rule.onNodeWithText("“Winterreifen abholen” ticked").assertExists()
        rule.onNodeWithText("Undo").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, undone)
    }

    @Test
    fun choosingAListOrNotebookAsksForIt() {
        val chosen = mutableListOf<TvNotesPlace>()
        rule.setContent { GateControlTvTheme { TvNotizenPage(ui(), TvNotizenActions(onChoose = { chosen += it })) } }
        rule.onNodeWithContentDescription("Haushalt, 6").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithContentDescription("Rezepte").performSemanticsAction(SemanticsActions.OnClick)
        // on opening: the place shown
        assertEquals(listOf(TvNotesPlace.MyDay, TvNotesPlace.List(2), TvNotesPlace.Notebook(5)), chosen)
    }

    @Test
    fun aNoteOpensAsACardWithItsMarkdown() {
        val note = NNote(
            NNoteSummary(8, 5, "Zählerstände", null, null, null, false, false, null, null, null, null, 0, 0, emptyList(), null, 0, 0, 1),
            "## Herbst\n- Strom **48 213**\n- [x] Gas abgelesen",
            null,
            "Wohnung",
            false,
            emptyList(),
            emptyList(),
        )
        rule.setContent { GateControlTvTheme { TvNotizenPage(ui(place = TvNotesPlace.Notebook(5)), TvNotizenActions(note = { note })) } }
        rule.onNodeWithText("1 note").assertExists()
        rule.onNodeWithContentDescription("Zählerstände, Strom 48 213").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithText("WOHNUNG · NOTE").assertExists()
        rule.onNodeWithText("Herbst").assertExists()
        rule.onNodeWithText("•  Strom 48 213").assertExists()
        rule.onNodeWithText("☑  Gas abgelesen").assertExists()
    }

    @Test
    fun addTaskReadsTheLineThenSavesIt() {
        val read = mutableListOf<String>()
        rule.setContent { GateControlTvTheme { TvNotizenPage(ui(add = TvNotesAdd.Typing), TvNotizenActions(onRead = { read += it })) } }
        rule.onNodeWithText("OK for the keyboard, then hold the microphone button and speak.").assertExists()
        rule.onNodeWithContentDescription("New task").performTextInput("Winterreifen abholen morgen 17 Uhr")
        rule.onNodeWithContentDescription("New task").performImeAction()
        assertEquals(listOf("Winterreifen abholen morgen 17 Uhr"), read)
    }

    @Test
    fun thePreviewShowsWhatTheServerRead() {
        var saved = 0
        val preview = TvNotesAdd.Preview("Winterreifen abholen morgen 17 Uhr", NParsedLine("Winterreifen abholen", NDue("2026-11-25", "17:00"), false), "c-0001")
        rule.setContent { GateControlTvTheme { TvNotizenPage(ui(add = preview), TvNotizenActions(onSave = { saved++ })) } }
        rule.onNodeWithText("This is saved").assertExists()
        rule.onNodeWithText("tomorrow · 17:00").assertExists()
        rule.onNodeWithText("Save").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, saved)
    }

    @Test
    fun theStartRowShowsTodaysTasks() {
        var opened = 0
        rule.setContent {
            GateControlTvTheme {
                TvStartRows(
                    ui = TvStartUi(emptyList(), emptySet(), emptyList(), emptyMap(), null, loading = false, today = today, tasks = TvTasksToday(3, 1)),
                    onTile = {},
                    onSetpoint = { _, _, _ -> },
                    onCamera = {},
                    onTasks = { opened++ },
                )
            }
        }
        rule.onNodeWithText("At home today").assertExists()
        rule.onNodeWithContentDescription("Tasks, 3 today · 1 overdue").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, opened)
    }

    @Test
    fun missingPluginSaysIt() {
        rule.setContent { GateControlTvTheme { TvNotizenPage(TvNotizenUi(today, navState = TvSourceState.MISSING, placeState = TvSourceState.MISSING), TvNotizenActions()) } }
        rule.onAllNodesWithText("Notes and tasks are not available.").assertCountEquals(2)
    }
}
