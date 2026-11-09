package com.gatecontrol.android.ui.mail

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.SavedStateHandle
import com.gatecontrol.android.mail.MAccount
import com.gatecontrol.android.mail.MAddress
import com.gatecontrol.android.mail.MBulkDone
import com.gatecontrol.android.mail.MBulkUndo
import com.gatecontrol.android.mail.MChanged
import com.gatecontrol.android.mail.MCounts
import com.gatecontrol.android.mail.MFolder
import com.gatecontrol.android.mail.MLimits
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MMicrosoft
import com.gatecontrol.android.mail.MPage
import com.gatecontrol.android.mail.MResult
import com.gatecontrol.android.mail.MSettings
import com.gatecontrol.android.mail.MSummary
import com.gatecontrol.android.mail.MUndo
import com.gatecontrol.android.mail.MailApi
import com.gatecontrol.android.mail.MailCache
import com.gatecontrol.android.mail.MailRepository
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.google.gson.JsonObject
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The Mail tab itself with a selection (German): a long press ticks a mail, taps tick more, the bar takes the place
 * of „Neue Mail“, back ends it; „Archivieren“ takes the rows out and says so with „Rückgängig“.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w411dp-h891dp")
class MailHomeSelectionUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val api: MailApi = mockk(relaxed = true)
    private val cache: MailCache = mockk(relaxed = true)
    private val repository: MailRepository = mockk(relaxed = true)
    private val now = System.currentTimeMillis()

    private fun s(id: Long, from: String, subject: String, snippet: String, account: Long = 1, unread: Boolean = false) = MSummary(
        id = id, accountId = account, folderId = 10, threadId = null, threadCount = 1, from = MAddress(from, "x$id@beispiel.de"), to = emptyList(),
        subject = subject, snippet = snippet, date = now - id * 3_600_000L, unread = unread, flagged = false, answered = false,
        hasAttachments = false, tab = MSummary.TAB_RELEVANT, savedToDrive = false, aiSummary = null,
    )

    private val mails = listOf(
        s(1, "Lena Hoffmann", "Fotos vom Sommerfest", "Hier sind die Bilder, wie versprochen!", unread = true),
        s(2, "Stadtwerke Musterstadt", "Ihre Abschlagsrechnung für März", "Im Anhang finden Sie Ihre Rechnung.", unread = true),
        s(3, "TSV Beispielhausen", "Einladung zur Jahreshauptversammlung", "Liebe Mitglieder …", account = 2),
        s(4, "Paketdienst Flink", "Ihr Paket kommt heute", "Zustellung zwischen 10 und 14 Uhr."),
    )

    private fun account(id: Long, name: String, email: String, color: String) = MAccount(
        id = id, provider = MAccount.PROVIDER_IMAP, email = email, name = name, color = color, kind = MAccount.KIND_PERSONAL,
        state = MAccount.STATE_OK, errorCode = null, errorAt = null, push = "relevant", ai = false, attachments = "off", signature = null,
    )

    private val me = MMe(
        features = setOf(MMe.FEATURE_MAIL, MMe.FEATURE_COPY, MMe.FEATURE_UNDO, MMe.FEATURE_SWIPE_V2),
        limits = MLimits(5, 0, 0, 12), microsoft = MMicrosoft(null, "https://login.microsoftonline.com/common", emptyList()),
        accounts = listOf(account(1, "Privat", "mia.wagner@beispiel.de", "#46E0A0"), account(2, "TSV", "kasse@tsv-beispielhausen.de", "#79B8FF")),
        unreadAll = 2, unreadByAccount = mapOf(1L to 2), settings = MSettings(threads = false, swipeLeft = "delete", swipeRight = "flag"),
    )

    @Before
    fun setUp() {
        every { repository.api } returns api
        every { repository.cache } returns cache
        every { repository.me } returns MutableStateFlow(me)
        every { repository.meError } returns MutableStateFlow(null)
        every { repository.folders } returns MutableStateFlow(mapOf(1L to listOf(MFolder(10, 1, "INBOX", "INBOX", MFolder.ROLE_INBOX, null, 2, 4))))
        every { repository.undo } returns MutableStateFlow<MUndo?>(null)
        every { repository.folds } returns MutableStateFlow(emptyMap())
        every { repository.folderFolds } returns MutableStateFlow(emptyMap())
        every { repository.recentFolders } returns MutableStateFlow(emptyList())
        every { repository.changed } returns MutableSharedFlow<MChanged>()
        coEvery { cache.list(any()) } returns null
        coEvery { api.messages(any()) } returns MResult.Ok(MPage(mails, null, MCounts(4, 0)) to JsonObject())
        coEvery { api.filing() } returns MResult.Ok(emptyList())
        coEvery { api.outbox() } returns MResult.Ok(emptyList())
    }

    private fun shot(name: String) {
        val dir = System.getenv("MAILMULTI_SHOTS") ?: return
        val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `long press ticks, taps tick more, the bar replaces Neue Mail, back ends it`() {
        val vm = MailHomeViewModel(repository, SavedStateHandle())
        val nav = MailNav(open = {}, compose = {}, draft = {}, search = {}, filing = {}, addAccount = {}, settings = {}, account = {}, outbox = {})
        rule.setContent {
            GateControlTheme(darkTheme = false) {
                Scaffold(containerColor = MaterialTheme.colorScheme.background, modifier = Modifier.testTag("shot")) { inner ->
                    Box(Modifier.padding(inner).fillMaxSize()) { MailHomeScreen(nav = nav, viewModel = vm) }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Neue Mail").assertIsDisplayed()
        rule.onNodeWithText("Lena Hoffmann").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("1 ausgewählt").assertIsDisplayed()
        rule.onNodeWithText("Paketdienst Flink").performClick()
        rule.onNodeWithText("2 ausgewählt").assertIsDisplayed()
        rule.onNodeWithText("Posteingang · Alle Konten").assertIsDisplayed()
        rule.onAllNodesWithText("Neue Mail").assertCountEquals(0)
        rule.onNodeWithText("Kopieren").assertIsDisplayed()
        shot("00-liste-auswahl")

        // back ends the selection
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onAllNodesWithText("2 ausgewählt").assertCountEquals(0)
        rule.onNodeWithText("Neue Mail").assertIsDisplayed()

        // archive two: the rows go, the snackbar says so with Rückgängig
        coEvery { api.bulk("archive", any()) } returns MResult.Ok(MBulkDone(emptyList(), 2, listOf(1, 4), MBulkUndo("tok", "archive", 2, null), emptyList()))
        rule.onNodeWithText("Lena Hoffmann").performTouchInput { longClick() }
        rule.onNodeWithText("Paketdienst Flink").performClick()
        // the snackbar stays a few seconds: the test's clock goes by hand from here
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Archivieren").performClick()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        rule.onAllNodesWithText("Lena Hoffmann").assertCountEquals(0)
        rule.onNodeWithText("2 Mails archiviert").assertIsDisplayed()
        rule.onNodeWithText("Rückgängig").assertIsDisplayed()
        shot("10-liste-archiviert")
        coEvery { api.undo("tok") } returns MResult.Ok(com.gatecontrol.android.mail.MUndone("archive", 2, 0, emptyList()))
        rule.onNodeWithText("Rückgängig").performClick()
        rule.waitForIdle()
        coVerify { api.undo("tok") }
    }
}
