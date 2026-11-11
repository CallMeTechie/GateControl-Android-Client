package com.gatecontrol.android.ui.rules

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.gatecontrol.android.familie.rules.RuleAction
import com.gatecontrol.android.familie.rules.RuleActionType
import com.gatecontrol.android.familie.rules.RuleInternetOp
import com.gatecontrol.android.familie.rules.RuleInternetProblem
import com.gatecontrol.android.familie.rules.RuleJson
import com.gatecontrol.android.familie.rules.RuleOptionChild
import com.gatecontrol.android.familie.rules.RuleOptionInternet
import com.gatecontrol.android.familie.rules.RuleOptions
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The rule editor's dialog „Internet zu Hause“ (approved mockup, section 4) and the lines of the action. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w411dp-h891dp")
class RuleInternetUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val options = RuleOptions(
        internet = RuleOptionInternet(available = true, children = listOf(RuleOptionChild(4, "Lea"), RuleOptionChild(5, "Tim"), RuleOptionChild(6, "Mia"))),
    )
    private val content = mutableStateOf<@Composable () -> Unit>({})

    private fun page(content: @Composable () -> Unit) {
        this.content.value = content
        rule.setContent { GateControlTheme(darkTheme = false) { this.content.value() } }
        rule.waitForIdle()
    }

    @Test
    fun `a new action - child, pause or resume, how long`() {
        var done: RuleAction? = null
        page { InternetActionDialog(null, options, onDone = { done = it }, onDismiss = {}) }
        listOf("Internet zu Hause", "Lea", "Tim", "Mia", "Pausieren", "Fortsetzen", "30 Min.", "1 Std.", "2 Std.", "bis Uhrzeit").forEach {
            rule.onNodeWithText(it).assertIsDisplayed()
        }
        rule.onNodeWithText("Tim").performClick()
        rule.onNodeWithText("Fertig").performClick()
        assertEquals(listOf(RuleInternetOp.PAUSE, 5, "Tim", 30, null), done!!.let { listOf(it.op, it.userId, it.title, it.minutes, it.until) })

        rule.onNodeWithText("bis Uhrzeit").performClick()
        rule.onNodeWithText("18:30").assertIsDisplayed()
        rule.onNodeWithText("Fertig").performClick()
        assertEquals(listOf<Any?>(null, "18:30"), done!!.let { listOf(it.minutes, it.until) })

        rule.onNodeWithText("Fortsetzen").performClick()
        assertEquals("resume has no duration", 0, rule.onAllNodesWithText("30 Min.").fetchSemanticsNodes().size)
        rule.onNodeWithText("Fertig").performClick()
        assertEquals(listOf<Any?>(RuleInternetOp.RESUME, null, null), done!!.let { listOf(it.op, it.minutes, it.until) })
    }

    @Test
    fun `changing an action keeps its own duration and what the server sent with it`() {
        val stored = RuleJson.action(JsonParser.parseString("""{"type":"internet","op":"pause","userId":9,"name":"Jonas","minutes":45,"until":null,"available":false,"problem":"not_found","extra":1}""").asJsonObject)!!
        var done: RuleAction? = null
        page { InternetActionDialog(stored, options, onDone = { done = it }, onDismiss = {}) }
        // The portal's 45 minutes and the child that is no longer offered stay as choices.
        rule.onNodeWithText("45 Min.").assertIsDisplayed()
        rule.onNodeWithText("Jonas").assertIsDisplayed()
        rule.onNodeWithText("Lea").performClick()
        rule.onNodeWithText("Fertig").performClick()
        assertEquals(listOf<Any?>(4, "Lea", 45), done!!.let { listOf(it.userId, it.title, it.minutes) })
        assertEquals(1, done!!.raw!!.get("extra").asInt)
    }

    @Test
    fun `the lines of the action in the editor and the list`() {
        val pause = RuleAction(RuleActionType.INTERNET, title = "Tim", op = RuleInternetOp.PAUSE, userId = 5, minutes = 30)
        val until = RuleAction(RuleActionType.INTERNET, op = RuleInternetOp.PAUSE, userId = 4, until = "18:30")
        val resume = RuleAction(RuleActionType.INTERNET, title = "Mia", op = RuleInternetOp.RESUME, userId = 6)
        val gone = RuleAction(RuleActionType.INTERNET, title = "Jonas", op = RuleInternetOp.PAUSE, userId = 9, minutes = 30, available = false, problem = RuleInternetProblem.NOT_FOUND)
        val texts = mutableListOf<String?>()
        page {
            listOf(pause, until, resume, gone).forEach {
                texts += actionText(it, options, 2) { null }
                texts += internetActionDetail(it)
            }
        }
        assertEquals(
            listOf(
                "Internet von Tim pausieren", "für 30 Min.",
                "Internet von Lea pausieren", "bis 18:30",
                "Internet von Mia fortsetzen", null,
                "Internet von Jonas pausieren", "Nicht verfügbar: Jonas ist kein minderjähriges Kind der Familie mehr",
            ),
            texts.take(8),
        )
    }
}
