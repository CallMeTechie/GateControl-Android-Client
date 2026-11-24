package com.gatecontrol.android.tv.ui.settings

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.gatecontrol.android.tv.TvStartRow
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Einstellungen: "Spracheingabe testen" (the dictation spike at the stick) and the rows of the start page. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
class TvVoiceTestTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun voiceTestShowsBothFieldsTheHintAndWhatArrived() {
        rule.setContent { GateControlTvTheme { TvVoiceTestOption() } }
        rule.onNodeWithText("Test voice input").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("OK for the keyboard, then hold the microphone button and speak.").assertExists()
        rule.onNodeWithText("Field 2 (classic Android field)").assertExists()
        rule.onNodeWithText("Dictation is a feature of Fire OS and Alexa: Amazon processes what you say, not GateControl.").assertExists()
        rule.onNodeWithContentDescription("Field 1 (Compose)").performTextInput("Hallo Welt")
        // in the field and, large, under "What arrived"
        rule.onAllNodesWithText("Hallo Welt").assertCountEquals(2)
        rule.onNodeWithText("Clear").performSemanticsAction(SemanticsActions.OnClick)
        rule.onAllNodesWithText("Hallo Welt").assertCountEquals(0)
        rule.onNodeWithText("Close").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("Field 2 (classic Android field)").assertDoesNotExist()
    }

    @Test
    fun eachStartRowSwitchesOnItsOwn() {
        val set = mutableListOf<Pair<TvStartRow, Boolean>>()
        rule.setContent { GateControlTvTheme { androidx.compose.foundation.layout.Column { TvStartRowOptions(setOf(TvStartRow.DRIVE)) { r, on -> set += r to on } } } }
        rule.onNodeWithText("At home today").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("Recent in Drive").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(TvStartRow.HOME_TODAY to false, TvStartRow.DRIVE to true), set)
    }
}
