package com.gatecontrol.android.tv.ui.home

import android.app.Application
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotSelected
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The empty start page of stage 0 on Android 9 (Fire OS 7): rail, status bar and placeholder rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
class TvHomeTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show() = rule.setContent { GateControlTvTheme { TvHome() } }

    @Test
    fun railListsAllPagesAndStartsOnStart() {
        show()
        for (label in listOf("Start", "Cameras", "Drive", "Media", "GateDrop", "Notes", "Home", "Settings")) {
            rule.onNodeWithContentDescription(label).assertExists()
        }
        rule.onNodeWithContentDescription("Start").assertIsSelected().assertIsFocused()
    }

    @Test
    fun startPageShowsThePlaceholderRows() {
        show()
        rule.onNodeWithText("GateControl").assertExists()
        rule.onNodeWithText("Not paired yet").assertExists()
        rule.onNodeWithText("Quick actions").assertExists()
        rule.onNodeWithText("Recent in Drive").assertExists()
        // "Cameras" is a row title (text) and a rail entry (content description)
        rule.onNodeWithText("Cameras").assertExists()
        assertTrue(rule.onAllNodesWithText("Coming soon").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun anotherPageIsAPlaceholderForNow() {
        show()
        rule.onNodeWithContentDescription("Drive").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithContentDescription("Drive").assertIsSelected()
        rule.onNodeWithContentDescription("Start").assertIsNotSelected()
        rule.onNodeWithText("Drive is coming in a later version.").assertExists()
        rule.onNodeWithText("Quick actions").assertDoesNotExist()
    }
}
