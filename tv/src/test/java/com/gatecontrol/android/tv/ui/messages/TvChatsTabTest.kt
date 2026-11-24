package com.gatecontrol.android.tv.ui.messages

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import com.gatecontrol.android.tv.chat.TvChatFixtures
import com.gatecontrol.android.tv.chat.TvChatPending
import com.gatecontrol.android.tv.chat.TvChatSendError
import com.gatecontrol.android.tv.chat.TvChatsUi
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import com.gatecontrol.android.tv.zuhause.TvSourceState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Nachrichten › Chats on the TV: the list, the history's lines, „Nicht gesendet“, a bridge that is down, Mail's tab. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
class TvChatsTabTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val family = TvChatFixtures.conversation(5, "Familie", managedBy = "gatecontrol-familie", unread = 2)
    private val photo = TvChatFixtures.message(2, text = null, kind = "image", extra = ""","media":{"id":"0123456789abcdef0123456789abcdef","mime":"image/jpeg"}""")
    private val voice = TvChatFixtures.message(3, text = null, kind = "voice", extra = ""","media":{"id":"0123456789abcdef0123456789abcdef","mime":"audio/ogg"}""")

    private fun ui(c: com.gatecontrol.android.chat.ChatConversation = family, pending: List<TvChatPending> = emptyList()) = TvChatsUi(
        state = TvSourceState.OK,
        chats = listOf(c),
        open = c,
        messages = listOf(TvChatFixtures.message(1, conversationId = c.id, text = "Essen ist fertig"), photo, voice),
        pending = pending,
        history = TvSourceState.OK,
        unread = 2,
    )

    @Test
    fun theHistoryShowsTextPhotoAndAPlaceholder() {
        var photos: Pair<Int, Int>? = null
        rule.setContent {
            GateControlTvTheme {
                TvMessagesPage(chats = { TvChatsTab(ui(), TvChatsActions(onPhoto = { list, at -> photos = list.size to at })) }, chatsUnread = 2)
            }
        }
        rule.onNodeWithText("Essen ist fertig").assertExists()
        rule.onNodeWithText("Voice message (on the phone)").assertExists()
        rule.onNodeWithText("Answer").assertExists()
        rule.onAllNodesWithText("Familie").assertCountEquals(2)
    }

    @Test
    fun notSentOffersTheRetryWithTheSameClientId() {
        var retried: String? = null
        rule.setContent {
            GateControlTvTheme {
                TvChatsTab(
                    ui(pending = listOf(TvChatPending("tvretry0000000000", 5, "Bin gleich da", null, 1L, error = TvChatSendError.OFFLINE))),
                    TvChatsActions(onRetry = { retried = it }),
                )
            }
        }
        rule.onNodeWithText("Not sent · no connection").assertExists()
        rule.onNodeWithText("Bin gleich da").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("Try again").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("tvretry0000000000", retried)
    }

    @Test
    fun aBridgeThatIsDownTakesNothing() {
        val oma = TvChatFixtures.conversation(7, "Oma", kind = "direct", bridge = TvChatFixtures.bridge(state = "unreachable"))
        rule.setContent { GateControlTvTheme { TvChatsTab(ui(oma), TvChatsActions()) } }
        rule.onNodeWithText("WhatsApp is not connected right now: nothing can be sent. Check the linked services on the phone.").assertExists()
        rule.onAllNodesWithText("Answer").assertCountEquals(0)
        rule.onNodeWithText("via WhatsApp").assertExists()
    }

    @Test
    fun mailHasItsPlaceAsATab() {
        rule.setContent { GateControlTvTheme { TvMessagesPage(chats = { TvChatsTab(ui(), TvChatsActions()) }) } }
        rule.onNodeWithText("Mail").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("Mail follows here with the next update.").assertExists()
    }
}
