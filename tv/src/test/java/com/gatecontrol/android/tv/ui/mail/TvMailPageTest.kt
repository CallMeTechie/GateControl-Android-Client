package com.gatecontrol.android.tv.ui.mail

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.gatecontrol.android.mail.MAi
import com.gatecontrol.android.mail.MAccount
import com.gatecontrol.android.mail.MAddress
import com.gatecontrol.android.mail.MAttachment
import com.gatecontrol.android.mail.MMessage
import com.gatecontrol.android.mail.MSummary
import com.gatecontrol.android.tv.mail.TvMailMode
import com.gatecontrol.android.tv.mail.TvMailSettings
import com.gatecontrol.android.tv.mail.TvMailUi
import com.gatecontrol.android.tv.ui.settings.TvMailOptions
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import com.gatecontrol.android.tv.zuhause.TvSourceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The tab „Mail“ and Einstellungen › Mail on Android 9 (Fire OS 7). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
class TvMailPageTest {

    @get:Rule
    val rule = createComposeRule()

    private fun account(id: Long, name: String) = MAccount(
        id = id, provider = MAccount.PROVIDER_IMAP, email = "konto$id@example.com", name = name, color = null, kind = MAccount.KIND_PERSONAL,
        state = MAccount.STATE_OK, errorCode = null, errorAt = null, push = "relevant", ai = true, attachments = "off", signature = null,
    )

    private val summary = MSummary(
        id = 7, accountId = 1, folderId = 5, threadId = null, threadCount = 1, from = MAddress("Mila", "mila@example.com"),
        to = listOf(MAddress(null, "konto1@example.com")), subject = "Grillen am Samstag", snippet = "Hallo", date = 0, unread = true,
        flagged = false, answered = false, hasAttachments = true, tab = MSummary.TAB_RELEVANT, savedToDrive = false, aiSummary = null,
    )
    private val message = MMessage(
        summary, emptyList(), emptyList(), emptyList(), null, "Hallo zusammen,\nwir grillen am Samstag um 18 Uhr.", false, 0,
        listOf(
            MAttachment(1, "einladung.pdf", "application/pdf", 1_000, false, null, null, null),
            MAttachment(2, "liste.xlsx", "application/vnd.ms-excel", 1_000, false, null, null, null),
        ),
        null,
    )

    @Test
    fun withoutChosenAccountsOnlyTheHintShows() {
        rule.setContent { GateControlTvTheme { TvMailPage(TvMailUi(meState = TvSourceState.OK, allAccounts = listOf(account(1, "Privat"))), TvMailActions()) } }
        rule.onNodeWithText("No mail account shows on this TV yet. Choose them in Settings › Mail.").assertExists()
    }

    @Test
    fun theListTheMailInLargeTypeAndTheAnswers() {
        var answered: Pair<TvMailMode, Long>? = null
        val ui = TvMailUi(
            meState = TvSourceState.OK,
            accounts = listOf(account(1, "Privat")),
            allAccounts = listOf(account(1, "Privat")),
            items = listOf(summary),
            listState = TvSourceState.OK,
            selectedId = 7,
            message = message,
            messageState = TvSourceState.OK,
            ai = MAi(true, "Grillen am Samstag um 18 Uhr.", emptyList(), emptyList()),
            aiOn = true,
        )
        rule.setContent { GateControlTvTheme { TvMailPage(ui, TvMailActions(onAnswer = { mode, id -> answered = mode to id })) } }
        rule.onNodeWithText("1 unread").assertExists()
        rule.onNodeWithText("Hallo zusammen,\nwir grillen am Samstag um 18 Uhr.").assertExists()
        rule.onNodeWithText("In short").assertExists()
        rule.onNodeWithText("Grillen am Samstag um 18 Uhr.").assertExists()
        // a PDF opens in the viewer, another file is only its name
        rule.onNodeWithText("einladung.pdf").assertExists()
        rule.onNodeWithText("liste.xlsx · on the phone").assertExists()
        rule.onNodeWithText("Reply").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(TvMailMode.REPLY to 7L, answered)
        // one recipient: no „Reply all“
        assertTrue(runCatching { rule.onNodeWithText("Reply all").assertExists() }.isFailure)
    }

    @Test
    fun accountsAreChosenInTheSettingsAndKeptAcrossARestart() {
        var chosen = emptySet<Long>()
        rule.setContent {
            GateControlTvTheme {
                Column { TvMailOptions(listOf(account(1, "Privat"), account(2, "Verein")), chosen, TvSourceState.OK, onAccount = { id, on -> chosen = if (on) chosen + id else chosen - id }) }
            }
        }
        rule.onNodeWithText("Verein").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(setOf(2L), chosen)

        val context = RuntimeEnvironment.getApplication()
        val settings = TvMailSettings(context)
        // none by default
        assertTrue(settings.accounts.value.isEmpty())
        settings.setAccount(2, true)
        settings.setAccount(3, true)
        assertEquals(setOf(2L, 3L), TvMailSettings(context).accounts.value)
        settings.keepOnly(listOf(1L, 2L))
        assertEquals(setOf(2L), TvMailSettings(context).accounts.value)
        settings.clear()
        assertTrue(TvMailSettings(context).accounts.value.isEmpty())
    }
}
