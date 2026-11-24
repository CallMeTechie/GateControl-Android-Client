package com.gatecontrol.android.tv.ui.input

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The default input field: a classic EditText (dictation of Fire OS), kept in step with the Compose state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
class TvInputFieldTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun find(v: View): EditText? = when (v) {
        is EditText -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
        else -> null
    }

    @Test
    fun theDefaultIsTheClassicFieldAndItFollowsTheState() {
        assertEquals(TvInputVariant.CLASSIC, TvInput.DEFAULT)
        var text by mutableStateOf("")
        var done = 0
        rule.setContent {
            GateControlTvTheme { TvInputField(value = text, onValueChange = { text = it }, label = "New entry", maxLength = 10, onDone = { done++ }) }
        }
        val edit = find(rule.activity.window.decorView)!!
        assertEquals("New entry", edit.contentDescription)
        assertTrue(edit.imeOptions and EditorInfo.IME_ACTION_DONE != 0)
        // dictation writes into the view: the state follows, cut at maxLength
        rule.runOnUiThread { edit.setText("Butter und Eier") }
        rule.waitForIdle()
        assertEquals("Butter und", text)
        // the state changes (a building block, cleared): the view follows, the cursor at the end
        text = "Milch"
        rule.waitForIdle()
        assertEquals("Milch", edit.text.toString())
        assertEquals(5, edit.selectionStart)
        rule.runOnUiThread { edit.onEditorAction(EditorInfo.IME_ACTION_DONE) }
        assertEquals(1, done)
    }
}
