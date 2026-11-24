package com.gatecontrol.android.tv.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.gatecontrol.android.tv.TvSettings
import com.gatecontrol.android.tv.ui.input.ComposeField
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Einstellungen › Eingabe: own text building blocks, the dictation hints, privacy and the ways without dictation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
class TvInputOptionsTest {

    @get:Rule
    val rule = createComposeRule()

    private var snippets by mutableStateOf(listOf("Danke, ist angekommen.", "Passt, bis dann!"))
    private var hints by mutableStateOf(true)

    private fun show() = rule.setContent {
        GateControlTvTheme { ComposeField { Column { TvInputOptions(snippets, { snippets = it }, hints, { hints = it }) } } }
    }

    @Test
    fun aNewBuildingBlockIsWrittenInTheComposerAndSaved() {
        show()
        rule.onNodeWithText("New text snippet").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithContentDescription("Your text").performTextInput("ich melde mich morgen")
        rule.onNodeWithContentDescription("Your text").performImeAction()
        // not smoothed (it goes into answers that are): the field and the preview show the same
        rule.onNodeWithText("Send like this?").assertExists()
        rule.onAllNodesWithText("ich melde mich morgen").assertCountEquals(2)
        rule.onNodeWithText("Save").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(listOf("Danke, ist angekommen.", "Passt, bis dann!", "ich melde mich morgen"), snippets)
        rule.onNodeWithText("3 of 3").assertExists()
    }

    @Test
    fun buildingBlocksMoveAndGo() {
        show()
        rule.onNodeWithText("Passt, bis dann!").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("Move up").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("Passt, bis dann!", "Danke, ist angekommen."), snippets)
        rule.onNodeWithText("Delete").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("Danke, ist angekommen."), snippets)
    }

    @Test
    fun theHintsSwitchAndThePrivacyNote() {
        show()
        rule.onNodeWithText("Privacy of dictation").assertExists()
        rule.onNodeWithText("Without dictation").assertExists()
        rule.onNodeWithText("Hide dictation hints").performSemanticsAction(SemanticsActions.OnClick)
        assertFalse(hints)
    }

    @Test
    fun theSettingsKeepThemCleanAcrossARestart() {
        val context = RuntimeEnvironment.getApplication()
        TvSettings(context).apply {
            setSnippets(listOf(" Bin unterwegs ", "", "Bin unterwegs", "Komme später"))
            setDictationHints(false)
        }
        val again = TvSettings(context)
        assertEquals(listOf("Bin unterwegs", "Komme später"), again.snippets.value)
        assertFalse(again.dictationHints.value)
    }
}
