package com.gatecontrol.android.ui.chat

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.gatecontrol.android.chat.ChatCalendarTarget
import com.gatecontrol.android.chat.ChatDatePrefill
import com.gatecontrol.android.chat.ChatSuggestedEventDraft
import com.gatecontrol.android.ui.theme.GateControlTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * „Termin anlegen“ of a message opens the form with its content — title without the date words, the day the text
 * names, the time, the calendar (Mail first) — light and dark, and saves only on „Speichern“ (up to 1.129.3 the tap
 * saved into Familie at once and showed no form).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w360dp-h740dp")
class ChatSuggestedEventUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val targets = listOf(
        ChatCalendarTarget("mail:2", "Privat", 2, "#336699"),
        ChatCalendarTarget("mail:1", "Arbeit", 1, "#993366"),
        ChatCalendarTarget(ChatCalendarTarget.FAMILIE, "Familie-Kalender"),
    )

    private fun form(dark: Boolean) {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        val zone = ZoneId.of("Europe/Berlin")
        val start = LocalDateTime.of(2026, 11, 30, 8, 0).atZone(zone).toInstant().toEpochMilli()
        var saved: ChatSuggestedEventDraft? = null
        rule.setContent {
            GateControlTheme(darkTheme = dark) {
                SuggestedEventDialog(
                    prefill = ChatDatePrefill(start, false, "kommt der Schornsteinfeger"),
                    targets = targets,
                    initialTarget = "mail:2",
                    mailMissing = false,
                    busy = false,
                    onSave = { saved = it },
                    onDismiss = {},
                )
            }
        }
        rule.onNodeWithText("Termin anlegen").assertIsDisplayed()
        rule.onNodeWithText("kommt der Schornsteinfeger").assertIsDisplayed()
        rule.onNodeWithText("Mo. 30.11.2026").assertIsDisplayed()
        rule.onNodeWithText("08:00").assertIsDisplayed()
        rule.onNodeWithText("Kalender").assertIsDisplayed()
        rule.onNodeWithText("Privat").assertIsDisplayed()
        // nothing is saved before „Speichern“
        assertNull(saved)
        // another calendar
        rule.onNodeWithText("Privat").performClick()
        rule.onNodeWithText("Arbeit").performClick()
        rule.onNodeWithText("Speichern").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(ChatSuggestedEventDraft("kommt der Schornsteinfeger", start, false, "mail:1"), saved)
    }

    @Test fun formLight() = form(dark = false)

    @Test fun formDark() = form(dark = true)

    @Test
    fun withoutMailFamilieAndTheHint() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        val start = LocalDateTime.of(2026, 11, 30, 0, 0).atZone(ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli()
        rule.setContent {
            GateControlTheme(darkTheme = false) {
                SuggestedEventDialog(
                    prefill = ChatDatePrefill(start, true, "Elternabend"),
                    targets = targets.takeLast(1),
                    initialTarget = ChatCalendarTarget.FAMILIE,
                    mailMissing = true,
                    busy = false,
                    onSave = {},
                    onDismiss = {},
                )
            }
        }
        rule.onNodeWithText("Elternabend").assertIsDisplayed()
        rule.onNodeWithText("Familie-Kalender").assertIsDisplayed()
        rule.onNodeWithText("Deine Kalender aus Mail", substring = true).assertExists()
    }
}
