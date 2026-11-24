package com.gatecontrol.android.tv.ui.input

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.gatecontrol.android.tv.gatedrop.TvDropSend
import com.gatecontrol.android.tv.gatedrop.TvDropTarget
import com.gatecontrol.android.tv.input.TvHandoff
import com.gatecontrol.android.tv.input.TvSnippet
import com.gatecontrol.android.tv.input.TvSnippetKind
import com.gatecontrol.android.tv.input.TvSnippetSource
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The input building block of the TV: field, building blocks, preview „So senden?“, Zurück, „Am Handy weiter“. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
class TvComposerTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var open by mutableStateOf(true)

    @Composable
    private fun Host(
        onSend: suspend (String) -> Boolean,
        sources: List<TvSnippetSource> = emptyList(),
        handoff: TvHandoff? = null,
        hints: Boolean = true,
    ) {
        GateControlTvTheme {
            CompositionLocalProvider(LocalTvInputVariant provides TvInputVariant.COMPOSE, LocalTvDictationHints provides hints) {
                if (open) TvComposer(title = "Antwort an Familie", onSend = onSend, onClose = { open = false }, previewMeta = "an Familie", sources = sources, handoff = handoff)
            }
        }
    }

    private fun back() = rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }

    private val field get() = rule.onNodeWithContentDescription("Your text")

    @Test
    fun thePreviewShowsTheSmoothedTextAndSendsIt() {
        val sent = mutableListOf<String>()
        rule.setContent { Host(onSend = { sent += it; true }) }
        rule.onNodeWithText("OK opens the keyboard. Then hold the microphone button and speak; letting go adds the text.").assertExists()
        rule.onNodeWithText("Continue to preview").assertIsNotEnabled()
        field.performTextInput("ich hole die Pizza, bin um halb sieben da")
        rule.onNodeWithText("41 characters").assertExists()
        // „Fertig“ on the keyboard: the preview
        field.performImeAction()
        rule.onNodeWithText("Send like this?").assertExists()
        rule.onNodeWithText("an Familie").assertExists()
        rule.onNodeWithText("Ich hole die Pizza, bin um halb sieben da.").assertExists()
        rule.onNodeWithText("Send").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(listOf("Ich hole die Pizza, bin um halb sieben da."), sent)
        assertTrue("closed after sending", !open)
    }

    @Test
    fun aFailedSendKeepsTheDraftAndSaysSo() {
        var tries = 0
        rule.setContent { Host(onSend = { tries++; false }) }
        field.performTextInput("Bin gleich da")
        rule.onNodeWithText("Continue to preview").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("Send").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithText("Not sent. Try again?").assertExists()
        assertEquals(1, tries)
        // „Weiter sprechen“: back at the field, the next dictation after a space
        rule.onNodeWithText("Keep speaking").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("Send like this?").assertDoesNotExist()
        rule.onNodeWithText("Bin gleich da ").assertExists()
        assertTrue(open)
    }

    @Test
    fun buildingBlocksFromAllSourcesGoIntoTheDraft() {
        val ai = TvSnippetSource { listOf(TvSnippet("Ich bin an dem Tag nicht da.", TvSnippetKind.AI)) }
        val own = TvSnippetSource { listOf(TvSnippet("Passt, bis dann!", TvSnippetKind.OWN)) }
        val broken = TvSnippetSource { error("offline") }
        rule.setContent { Host(onSend = { true }, sources = listOf(ai, broken, own)) }
        rule.onNodeWithText("Text snippets").assertExists()
        rule.onNodeWithText("AI SUGGESTION").assertExists()
        rule.onNodeWithText("OWN").assertExists()
        field.performTextInput("Danke.")
        rule.onNodeWithText("Passt, bis dann!").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("Danke. Passt, bis dann!").assertExists()
    }

    @Test
    fun backAsksBeforeTheDraftGoes() {
        rule.setContent { Host(onSend = { true }) }
        field.performTextInput("Entwurf")
        back()
        rule.onNodeWithText("Discard draft?").assertExists()
        rule.onNodeWithText("Cancel").performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(open)
        field.performTextReplacement("")
        back()
        rule.waitForIdle()
        assertTrue("an empty draft closes at once", !open)
    }

    @Test
    fun theHandoffSendsTheDraftToAnOwnDevice() {
        val sent = mutableListOf<Pair<String, String>>()
        val handoff = TvHandoff(
            targets = { listOf(TvDropTarget("d2", "Pixel", device = true, platform = "android", online = true)) },
            send = { to, text -> sent += to.id to text; TvDropSend.Done(to.name) },
        )
        rule.setContent { Host(onSend = { true }, handoff = handoff) }
        field.performTextInput("Ich schreibe gleich am Handy weiter")
        rule.onNodeWithText("Continue on the phone").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("Continue writing on").assertExists()
        rule.onNodeWithText("Pixel").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithText("Sent to Pixel.").assertExists()
        assertEquals(listOf("d2" to "Ich schreibe gleich am Handy weiter"), sent)
    }

    @Test
    fun withoutHandoffOrHintsTheirPartsAreGone() {
        rule.setContent { Host(onSend = { true }, hints = false) }
        rule.onNodeWithText("Continue on the phone").assertDoesNotExist()
        rule.onNodeWithText("OK opens the keyboard. Then hold the microphone button and speak; letting go adds the text.").assertDoesNotExist()
        // the ways without dictation stay
        rule.onNodeWithText("Dictation not working? Take a snippet, continue on the phone, or type with the Fire TV app on your phone or a Bluetooth keyboard.").assertExists()
    }
}
