package com.gatecontrol.android.tv.ui.input

import android.app.Application
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.tv.input.TvTyping
import com.gatecontrol.android.tv.ui.components.TvButton
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 3d's known problems in the shared field: ▲ / ▼ leave a multi-line field (it kept them for its cursor), and the
 * field says while it has the focus (the PIN then waits for the dictation instead of locking).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
class TvInputFocusTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @After
    fun tearDown() = TvTyping.reset()

    private fun find(v: View): EditText? = when (v) {
        is EditText -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
        else -> null
    }

    @Test
    fun downLeavesTheMultiLineComposeField() {
        var text by mutableStateOf("Zeile eins\nZeile zwei")
        rule.setContent {
            GateControlTvTheme {
                CompositionLocalProvider(LocalTvInputVariant provides TvInputVariant.COMPOSE) {
                    val focus = remember { FocusRequester() }
                    Column {
                        TvInputField(value = text, onValueChange = { text = it }, label = "Your text", minHeight = 110.dp, fieldModifier = Modifier.focusRequester(focus))
                        TvButton("Weiter", onClick = {})
                    }
                    androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Your text").assertIsFocused()
        assertTrue(TvTyping.active)
        rule.onNodeWithContentDescription("Your text").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithText("Weiter").assertIsFocused()
        assertFalse(TvTyping.active)
    }

    @Test
    fun theClassicFieldGivesUpAndDownAway() {
        rule.setContent {
            GateControlTvTheme {
                Column {
                    TvInputField(value = "Zeile eins\nZeile zwei", onValueChange = {}, label = "Your text", minHeight = 110.dp)
                    TvButton("Weiter", onClick = {})
                }
            }
        }
        val edit = find(rule.activity.window.decorView)!!
        rule.runOnUiThread { edit.requestFocus() }
        rule.waitForIdle()
        assertTrue(TvTyping.active)
        var handled = false
        rule.runOnUiThread { handled = edit.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)) }
        rule.waitForIdle()
        // the field never keeps ▼ for its cursor any more
        assertTrue(handled)
        assertFalse(edit.hasFocus())
    }
}
