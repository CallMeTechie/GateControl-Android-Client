package com.gatecontrol.android.ui.mail

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.gatecontrol.android.mail.MAccount
import com.gatecontrol.android.mail.MAddress
import com.gatecontrol.android.mail.MFolder
import com.gatecontrol.android.mail.MLimits
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MMicrosoft
import com.gatecontrol.android.mail.MSettings
import com.gatecontrol.android.mail.MSummary
import com.gatecontrol.android.mail.MailBulk
import com.gatecontrol.android.mail.MailBulk.Action
import com.gatecontrol.android.ui.theme.GateControlTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Mail 1.13 in German: the head and the bar of a selection (Kopieren only with `copy`, moving greyed out across
 * accounts), the folder picker, the question before a delete and the page „Wischen“ (seven actions with `swipeV2`, the
 * old four before). With `MAILMULTI_SHOTS=<dir>` the drawn pages are saved there (for a look, not part of CI).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w411dp-h891dp")
class MailMultiUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val now = System.currentTimeMillis()

    private fun s(id: Long, from: String, subject: String, snippet: String, account: Long = 1, unread: Boolean = false, flagged: Boolean = false, folder: Long = 10) = MSummary(
        id = id, accountId = account, folderId = folder, threadId = null, threadCount = 1, from = MAddress(from, "x$id@beispiel.de"), to = emptyList(),
        subject = subject, snippet = snippet, date = now - id * 3_600_000L, unread = unread, flagged = flagged, answered = false,
        hasAttachments = id % 2L == 1L, tab = MSummary.TAB_RELEVANT, savedToDrive = false, aiSummary = null,
    )

    private val mails = listOf(
        s(1, "Lena Hoffmann", "Fotos vom Sommerfest", "Hier sind die Bilder, wie versprochen – viel Spaß beim Anschauen!", unread = true),
        s(2, "Stadtwerke Musterstadt", "Ihre Abschlagsrechnung für März", "Im Anhang finden Sie Ihre aktuelle Rechnung als PDF.", unread = true),
        s(3, "TSV Beispielhausen", "Einladung zur Jahreshauptversammlung", "Liebe Mitglieder, wir laden euch herzlich ein …", account = 2),
        s(4, "Paketdienst Flink", "Ihr Paket kommt heute", "Zustellung zwischen 10 und 14 Uhr."),
        s(5, "Tom Becker", "Re: Grillabend am Samstag", "Ich bringe Salat und Getränke mit.", flagged = true),
    )

    private fun account(id: Long, name: String, email: String, color: String) = MAccount(
        id = id, provider = MAccount.PROVIDER_IMAP, email = email, name = name, color = color, kind = MAccount.KIND_PERSONAL,
        state = MAccount.STATE_OK, errorCode = null, errorAt = null, push = "relevant", ai = false, attachments = "off", signature = null,
    )

    private val accounts = listOf(account(1, "Privat", "mia.wagner@beispiel.de", "#46E0A0"), account(2, "TSV", "kasse@tsv-beispielhausen.de", "#79B8FF"))

    private fun me(vararg features: String, settings: MSettings = MSettings.DEFAULT) = MMe(
        features = setOf(MMe.FEATURE_MAIL) + features, limits = MLimits(5, 0, 0, 12),
        microsoft = MMicrosoft(null, "https://login.microsoftonline.com/common", emptyList()), accounts = accounts,
        unreadAll = 2, unreadByAccount = emptyMap(), settings = settings,
    )

    private val v2 = me(MMe.FEATURE_COPY, MMe.FEATURE_UNDO, MMe.FEATURE_BULK_ALL, MMe.FEATURE_THREAD_SCOPE, MMe.FEATURE_SWIPE_V2)

    private fun page(dark: Boolean = false, content: @Composable () -> Unit) {
        rule.setContent {
            GateControlTheme(darkTheme = dark) {
                Scaffold(containerColor = MaterialTheme.colorScheme.background, modifier = Modifier.testTag("shot")) { inner ->
                    Box(Modifier.padding(inner).fillMaxSize()) { content() }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        val dir = System.getenv("MAILMULTI_SHOTS") ?: return
        val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * A sheet or a dialog drawn in the page (Robolectric draws no second window): the list dimmed behind, [content] on
     * a surface at the bottom (a sheet) or in the middle (a dialog).
     */
    @Composable
    private fun Over(behind: @Composable () -> Unit, dialog: Boolean = false, content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            behind()
            Box(Modifier.fillMaxSize().background(GateControlTheme.extraColors.scrim))
            if (dialog) {
                Box(Modifier.align(Alignment.Center).padding(horizontal = 32.dp)) { content() }
            } else {
                Surface(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) { Column(Modifier.padding(top = 20.dp)) { content() } }
            }
        }
    }

    /** The list while mails are ticked, as the Mail tab shows it (head, rows, bar). */
    @Composable
    private fun SelectingList(me: MMe, ticked: Set<Long>, all: MailBulk.All? = null, onAction: (Action) -> Unit = {}, onMore: () -> Unit = {}) {
        val selection = MailBulk.Selection(ticked, all)
        val chosen = mails.filter { it.id in ticked }
        Box(Modifier.fillMaxSize()) {
            Column {
                MailSelectionHead(
                    selection = selection,
                    where = "Posteingang · Alle Konten",
                    folderName = "Posteingang",
                    allSelected = MailBulk.allSelected(selection, mails.map { it.id }),
                    loadingAll = false,
                    read = MailBulk.readAction(chosen),
                    onRead = {},
                    onClose = {},
                    onAll = {},
                )
                mails.forEach { m ->
                    MailRow(m, accounts.first { it.id == m.accountId }, onClick = {}, onLongClick = {}, selected = m.id in ticked, selecting = true, onAvatar = {})
                    MailDivider()
                }
            }
            MailSelectionBar(
                me = me,
                oneAccount = MailBulk.accounts(chosen).size <= 1,
                hasFolders = true,
                onAction = onAction,
                onMore = onMore,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    @Test
    fun `selection - N ausgewaehlt, Gelesen in the head, the five actions with copy`() {
        val acted = mutableListOf<Action>()
        page { SelectingList(v2, setOf(1, 2, 4), onAction = { acted += it }) }
        shot("01-auswahl")
        rule.onNodeWithText("3 ausgewählt").assertIsDisplayed()
        rule.onNodeWithText("Posteingang · Alle Konten").assertIsDisplayed()
        rule.onNodeWithContentDescription("Als gelesen markieren").assertIsDisplayed()
        rule.onNodeWithText("Alle").assertIsDisplayed()
        listOf("Archivieren", "Löschen", "Verschieben", "Kopieren", "Mehr").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithText("Verschieben").performClick()
        rule.onNodeWithText("Kopieren").performClick()
        rule.onNodeWithText("Archivieren").performClick()
        assertEquals(listOf(Action.MOVE, Action.COPY, Action.ARCHIVE), acted)
        // the ticked rows say so to TalkBack
        rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Ausgewählt")).assertCountEquals(3)
    }

    @Test
    fun `selection - an old server - no Kopieren, mails of two accounts cannot be moved`() {
        val acted = mutableListOf<Action>()
        page { SelectingList(me(), setOf(1, 3), onAction = { acted += it }) }
        rule.onAllNodesWithText("Kopieren").assertCountEquals(0)
        rule.onNodeWithText("Verschieben").performClick()
        assertTrue("greyed out: a tap does not move", acted.isEmpty())
        rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Verschieben, nicht verfügbar: Nur mit Mails aus einem Konto")).assertCountEquals(1)
    }

    @Test
    fun `selection - Alle of the whole folder says how many, Keine to take it back`() {
        page { SelectingList(v2, mails.map { it.id }.toSet() + (100L..142L), all = MailBulk.All(48, truncated = false)) }
        shot("02-alle")
        rule.onNodeWithText("48 ausgewählt").assertIsDisplayed()
        rule.onNodeWithText("Alle 48 Mails in „Posteingang“").assertIsDisplayed()
        rule.onNodeWithText("Keine").assertIsDisplayed()
        rule.onNodeWithContentDescription("Als gelesen markieren").assertIsDisplayed()
    }

    @Test
    fun `Mehr - read, unread, flag, Sonstige in an inbox, spam, and the menu of one mail`() {
        val acted = mutableListOf<Action>()
        var one = 0
        page {
            Over({ SelectingList(v2, setOf(5)) }) {
                MailMoreContent(count = 1, items = listOf(mails[4]), inbox = true, onAction = { acted += it }, onOne = { one++ }, onDismiss = {})
            }
        }
        shot("03-mehr")
        rule.onNodeWithText("1 Mail").assertIsDisplayed()
        listOf("Als gelesen markieren", "Als ungelesen markieren", "Markierung entfernen", "Nach Sonstige", "In Spam").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithText("Markierung entfernen").performClick()
        rule.onNodeWithText("Alle Aktionen für diese Mail").performClick()
        assertEquals(listOf(Action.UNFLAG), acted)
        assertEquals(1, one)
    }

    private val folders = listOf(
        MFolder(10, 1, "INBOX", "INBOX", MFolder.ROLE_INBOX, null, 0, 0),
        MFolder(11, 1, "Archiv", "Archiv", MFolder.ROLE_ARCHIVE, null, 0, 0),
        MFolder(12, 1, "Rechnungen", "Rechnungen", null, null, 0, 0),
        MFolder(13, 1, "2025", "Rechnungen/2025", null, 12, 0, 0),
        MFolder(14, 1, "2026", "Rechnungen/2026", null, 12, 0, 0),
        MFolder(15, 1, "Verein", "Verein", null, null, 0, 0),
        MFolder(16, 1, "Papierkorb", "Trash", MFolder.ROLE_TRASH, null, 0, 0),
    )

    @Test
    fun `picker - Verschieben or Kopieren, Zuletzt, the tree, here greyed, the button names the folder`() {
        var picked: MFolder? = null
        var mode by mutableStateOf(Action.MOVE)
        page {
            Over({ SelectingList(v2, setOf(1, 2, 4)) }) {
                MailFolderContent(
                    picker = MailPicker(listOf(1, 2, 4), 12, setOf(1), mode, copy = true, current = 10, fromSelection = true),
                    account = accounts[0],
                    folders = folders,
                    recent = listOf(12, 15, 99),
                    onMode = { mode = it },
                    onPick = { picked = it },
                    onDismiss = {},
                    severalAccounts = true,
                )
            }
        }
        rule.onNodeWithText("12 Mails verschieben").assertIsDisplayed()
        rule.onNodeWithText("Zuletzt".uppercase()).assertIsDisplayed()
        rule.onNodeWithText("hier").assertIsDisplayed()
        rule.onNodeWithText("Ordner wählen").assertIsDisplayed()
        // the arrow of "Rechnungen" opens its children
        rule.onNodeWithContentDescription("Rechnungen aufklappen").performClick()
        rule.onNodeWithText("2026").performClick()
        rule.onNodeWithText("Nach „2026“ verschieben").assertIsDisplayed()
        shot("04-verschieben")
        rule.onNodeWithText("Nach „2026“ verschieben").performClick()
        assertEquals(14L, picked?.id)
        // the bar behind has "Kopieren" too: the sheet's switch is the last one
        rule.onAllNodesWithText("Kopieren").onLast().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("12 Mails kopieren").assertIsDisplayed()
    }

    @Test
    fun `delete - the question with Nicht mehr fragen, in the trash for good`() {
        var answer: Boolean? = null
        var ask by mutableStateOf(MailDeleteAsk(listOf(1), 12, purge = false, fromSelection = true))
        page { Over({ SelectingList(v2, setOf(1, 2, 4)) }, dialog = true) { MailDeleteCard(ask, onConfirm = { answer = it }, onDismiss = {}) } }
        rule.onNodeWithText("12 Mails löschen?").assertIsDisplayed()
        rule.onNodeWithText("Sie kommen in den Papierkorb. Dort kannst du sie wiederherstellen.").assertIsDisplayed()
        rule.onNodeWithText("Nicht mehr fragen").performClick()
        shot("05-loeschen")
        rule.onAllNodesWithText("Löschen").onLast().performClick()
        assertEquals(true, answer)
        ask = MailDeleteAsk(listOf(1), 5, purge = true, fromSelection = true)
        rule.waitForIdle()
        rule.onNodeWithText("5 Mails endgültig löschen?").assertIsDisplayed()
        rule.onAllNodesWithText("Nicht mehr fragen").assertCountEquals(0)
        shot("06-endgueltig")
        rule.onNodeWithText("Endgültig löschen").performClick()
        assertEquals(false, answer)
    }

    @Test
    fun `snackbar - moved with Rueckgaengig`() {
        page {
            val host = remember { SnackbarHostState() }
            val context = mailContext()
            LaunchedEffect(Unit) {
                host.showSnackbar(snackText(context, MailSnack(1, Action.MOVE, 12, folders[4], tokens = listOf("t"))), actionLabel = "Rückgängig")
            }
            Box(Modifier.fillMaxSize()) {
                Column { mails.forEach { MailRow(it, accounts.first { a -> a.id == it.accountId }, onClick = {}, onLongClick = {}); MailDivider() } }
                SnackbarHost(host, Modifier.align(Alignment.BottomCenter).padding(12.dp))
            }
        }
        rule.onNodeWithText("12 Mails nach „2026“ verschoben").assertIsDisplayed()
        rule.onNodeWithText("Rückgängig").assertIsDisplayed()
        shot("07-snackbar")
        val ctx = rule.activity
        assertEquals("12 Mails in den Papierkorb gelegt", snackText(ctx, MailSnack(2, Action.DELETE, 12)))
        assertEquals("1 Mail archiviert", snackText(ctx, MailSnack(3, Action.ARCHIVE, 1)))
        assertEquals("5 Mails endgültig gelöscht", snackText(ctx, MailSnack(4, Action.DELETE, 5, purged = true)))
    }

    // ─── "Wischen" ──────────────────────────────────────────────────

    @Test
    fun `Wischen - seven actions per way with swipeV2, the question, the note`() {
        val left = mutableListOf<String>()
        var confirm: Boolean? = null
        page { MailSwipeContent(v2.copy(settings = MSettings(true, "delete", "read")), onLeft = { left += it }, onRight = {}, onConfirm = { confirm = it }) }
        shot("08-wischen")
        rule.onNodeWithText("NACH LINKS WISCHEN").assertIsDisplayed()
        rule.onAllNodesWithText("Verschieben …").assertCountEquals(2)
        rule.onAllNodesWithText("In Spam").assertCountEquals(2)
        rule.onAllNodesWithText("Gelesen / Ungelesen").assertCountEquals(2) // both lists (the preview is one picture for TalkBack)
        rule.onAllNodesWithText("Markieren")[0].performClick()
        assertEquals(listOf("flag"), left)
        rule.onNodeWithText("Vor dem Löschen fragen").performScrollTo().performClick()
        assertEquals(true, confirm)
        rule.onNodeWithText("Gilt für alle deine Geräte und für die Schnellaktionen am PC.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `Wischen - an old server - the old four only, no question`() {
        page { MailSwipeContent(me(), onLeft = {}, onRight = {}, onConfirm = {}) }
        rule.onAllNodesWithText("Verschieben …").assertCountEquals(0)
        rule.onAllNodesWithText("Markieren").assertCountEquals(0)
        rule.onAllNodesWithText("Nichts").assertCountEquals(2)
        rule.onAllNodesWithText("Vor dem Löschen fragen").assertCountEquals(0)
        rule.onNodeWithText("Weitere Aktionen gibt es, sobald GateControl Mail auf dem Server neuer ist.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `Wischen - dark`() {
        page(dark = true) { MailSwipeContent(v2.copy(settings = MSettings(true, "move", "flag", confirmDelete = true)), onLeft = {}, onRight = {}, onConfirm = {}) }
        shot("09-wischen-dunkel")
        rule.onAllNodesWithText("Verschieben", substring = true).fetchSemanticsNodes().isNotEmpty().let(::assertTrue)
    }
}
