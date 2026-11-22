package com.gatecontrol.android.tv.ui.pair

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.gatecontrol.android.tv.pair.PairCode
import com.gatecontrol.android.tv.pair.PairError
import com.gatecontrol.android.tv.pair.PairEvent
import com.gatecontrol.android.tv.pair.PairState
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The pairing screen on Android 9 (Fire OS 7): code, QR code and steps; the server address; a failure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
class TvPairScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val events = mutableListOf<PairEvent>()

    private fun show(state: PairState) = rule.setContent { GateControlTvTheme { TvPairScreen(state, onEvent = { events += it }) } }

    @Test
    fun waitingShowsCodeQrAndSteps() {
        val code = PairCode(
            "AbCdEfGhIjKlMnOpQrSt_-", "s".repeat(43), "K7Q4-MZAB",
            "gatecontrol://tv-pair?url=https%3A%2F%2Fgate.example.com&id=AbCdEfGhIjKlMnOpQrSt_-",
            expiresAt = System.currentTimeMillis() + 600_000L, intervalMs = 5_000L,
        )
        show(PairState.Waiting("https://gate.example.com", code, nextPollAt = Long.MAX_VALUE))
        rule.onNodeWithText("Pair with your phone").assertExists()
        rule.onNodeWithText("K7Q4-MZAB").assertExists()
        rule.onNodeWithContentDescription("QR code for pairing with the phone").assertExists()
        rule.onNodeWithText("Waiting for phone …").assertExists()
        rule.onNodeWithText("Set up without phone").assertExists()
        rule.onNodeWithText("Other server address").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf<PairEvent>(PairEvent.ChangeServer), events)
    }

    @Test
    fun theServerIsAskedOnce() {
        show(PairState.NeedServer())
        // heading and the field's label
        assertEquals(2, rule.onAllNodesWithText("Server address").fetchSemanticsNodes().size)
        rule.onNodeWithContentDescription("Server address").assertExists()
        rule.onNodeWithText("Next").assertExists()
    }

    @Test
    fun aFailureOffersANewCode() {
        show(PairState.Failed("https://gate.example.com", PairError.NOT_SUPPORTED))
        rule.onNodeWithText(
            "This GateControl server cannot pair TVs yet (from version 1.318.0). Use “Set up without phone”.",
        ).assertExists()
        rule.onNodeWithText("Get a new code").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf<PairEvent>(PairEvent.Retry), events)
    }
}
