package com.gatecontrol.android.ui.chat.settings

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatBridgeError
import com.gatecontrol.android.chat.ChatBridgeLogin
import com.gatecontrol.android.chat.ChatBridgeNetwork
import com.gatecontrol.android.chat.ChatBridges
import com.gatecontrol.android.chat.ChatBridgesInfo
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.theme.GateControlTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * „Verknüpfte Dienste“ drawn in German, light and dark: every state of the overview (not linked, linked, signed
 * out, error, locked for a child, starting, not reachable, outdated), the notes with the tick, the number, the
 * code, the QR code and a code that ran out. With `LINKED_SHOTS=<dir>` the drawn pages are saved there (for a
 * look, not part of CI).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w411dp-h891dp")
class ChatLinkedServicesUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val wa = ChatBridges.WHATSAPP

    private fun login(state: String) = ChatBridgeLogin("wa1", "+49 1512 0000000", state, "2026-11-02T08:00:00.000Z")

    private fun overview(
        state: String = ChatBridges.NET_RUNNING,
        allowed: Boolean = true,
        outdated: Boolean = false,
        login: ChatBridgeLogin? = null,
    ) = LinkedUiState(
        loading = false,
        info = ChatBridgesInfo(
            available = true,
            networks = listOf(ChatBridgeNetwork(wa, state, allowed, if (allowed) null else ChatBridges.LOCK_CHILD, consent = login != null, outdated = outdated, logins = listOfNotNull(login))),
        ),
    )

    private fun link(phase: LinkPhase, flow: String = ChatBridges.FLOW_PHONE) = overview().copy(view = LinkedView.Link(wa, flow, phase))

    private class Shown(val ui: LinkedUiState, val dark: Boolean, val actions: LinkedActions)

    private var shown by mutableStateOf<Shown?>(null)
    private var content = false

    /** The page as the chat settings show it: the bar, then the content in the scrolling column. Each call draws anew. */
    private fun page(ui: LinkedUiState, dark: Boolean = false, actions: LinkedActions = LinkedActions()) {
        shown = Shown(ui, dark, actions)
        if (!content) {
            content = true
            rule.setContent {
                val s = shown ?: return@setContent
                // a new page per call: what the last one remembered (a tick, a typed number) is gone
                key(s) {
                    GateControlTheme(darkTheme = s.dark) {
                        Scaffold(containerColor = MaterialTheme.colorScheme.background, modifier = Modifier.testTag("shot")) { inner ->
                            Box(Modifier.padding(inner).fillMaxSize()) {
                                Column {
                                    GcSubpageBar(title = stringResource(R.string.chat_linked_title), onBack = {}, backDescription = stringResource(R.string.chat_back))
                                    Column(
                                        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                    ) { LinkedServicesContent(s.ui, s.actions) }
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun shot(name: String, dark: Boolean) {
        val dir = System.getenv("LINKED_SHOTS") ?: return
        val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
        File(dir).mkdirs()
        File(dir, "$name-${if (dark) "dunkel" else "hell"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Draws [ui] light and dark, saves both and runs [check] on each. */
    private fun both(name: String, ui: LinkedUiState, actions: LinkedActions = LinkedActions(), check: () -> Unit) {
        for (dark in listOf(false, true)) {
            page(ui, dark, actions)
            shot(name, dark)
            check()
        }
    }

    private fun text(s: String) = rule.onNodeWithText(s, substring = true)

    // ─── overview ───────────────────────────────────────────────────

    @Test
    fun `not linked yet - Verknuepfen, where the bridges run, and that the chats follow later`() {
        val begun = mutableListOf<String>()
        both("01-uebersicht-leer", overview(), LinkedActions(begin = { begun += it })) {
            text("Deine Konten dort bleiben bestehen").assertIsDisplayed()
            rule.onNodeWithText("WhatsApp").assertIsDisplayed()
            rule.onNodeWithText("Noch nicht verknüpft").assertIsDisplayed()
            rule.onNodeWithText("Läuft auf").assertIsDisplayed()
            rule.onNodeWithText("Server · Brücken aktuell").assertIsDisplayed()
            rule.onNodeWithText("In Ordnung").assertIsDisplayed()
            text("Die Chats mit Kontakten des Dienstes folgen in einer späteren Version").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Verknüpfen").performScrollTo().performClick()
        }
        assertEquals(listOf(wa, wa), begun)
    }

    @Test
    fun `linked - since when, the own number, Trennen`() {
        val asked = mutableListOf<String>()
        both("02-uebersicht-verknuepft", overview(login = login(ChatBridges.LOGIN_CONNECTED)), LinkedActions(unlink = { asked += it })) {
            text("Verknüpft seit 02.11.2026 · +49 1512 0000000").assertIsDisplayed()
            rule.onNodeWithText("Verknüpft").assertIsDisplayed()
            assertEquals(0, rule.onAllNodesWithText("Verknüpfen").fetchSemanticsNodes().size)
            rule.onNodeWithText("Trennen").performClick()
        }
        assertEquals(listOf(wa, wa), asked)
    }

    @Test
    fun `signed out - Abgemeldet, Neu verknuepfen, Eintrag entfernen`() {
        val begun = mutableListOf<String>()
        val forgotten = mutableListOf<String>()
        both("03-uebersicht-abgemeldet", overview(login = login(ChatBridges.LOGIN_LOGGED_OUT)), LinkedActions(begin = { begun += it }, forget = { forgotten += it })) {
            rule.onNodeWithText("Abgemeldet").assertIsDisplayed()
            text("Abgemeldet am 02.11.2026. WhatsApp hat die Verknüpfung beendet").assertIsDisplayed()
            rule.onNodeWithText("Neu verknüpfen").assertIsDisplayed()
            text("Ein neuer Code genügt").assertIsDisplayed()
            rule.onNodeWithText("WhatsApp neu verknüpfen").performClick()
            rule.onNodeWithText("Eintrag entfernen").performScrollTo().performClick()
            assertEquals(0, rule.onAllNodesWithText("Trennen").fetchSemanticsNodes().size)
        }
        assertEquals(listOf(wa, wa), begun)
        assertEquals(listOf(wa, wa), forgotten)
    }

    @Test
    fun `an error - Fehler, Trennen and Neu verknuepfen`() {
        both("04-uebersicht-fehler", overview(login = login(ChatBridges.LOGIN_ERROR))) {
            rule.onNodeWithText("Fehler").assertIsDisplayed()
            text("Die Verknüpfung meldet einen Fehler").assertIsDisplayed()
            rule.onNodeWithText("Trennen").assertIsDisplayed()
            rule.onNodeWithText("WhatsApp neu verknüpfen").performScrollTo().assertIsDisplayed()
            assertEquals(0, rule.onAllNodesWithText("Eintrag entfernen").fetchSemanticsNodes().size)
        }
    }

    @Test
    fun `a child - Gesperrt with the hint to the parents, nothing to tap`() {
        both("05-uebersicht-gesperrt", overview(allowed = false)) {
            rule.onNodeWithText("Gesperrt").assertIsDisplayed()
            rule.onNodeWithText("Für dein Konto gesperrt, bis deine Eltern den Dienst freischalten.").assertIsDisplayed()
            assertEquals(0, rule.onAllNodesWithText("Verknüpfen").fetchSemanticsNodes().size)
            assertEquals(0, rule.onAllNodesWithText("Trennen").fetchSemanticsNodes().size)
        }
    }

    @Test
    fun `a child whose permission was taken back while signed out - no way to link again`() {
        both("06-uebersicht-gesperrt-abgemeldet", overview(allowed = false, login = login(ChatBridges.LOGIN_LOGGED_OUT))) {
            rule.onNodeWithText("Abgemeldet").assertIsDisplayed()
            rule.onNodeWithText("Für dein Konto gesperrt, bis deine Eltern den Dienst freischalten.").assertIsDisplayed()
            assertEquals(0, rule.onAllNodesWithText("WhatsApp neu verknüpfen").fetchSemanticsNodes().size)
            rule.onNodeWithText("Eintrag entfernen").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `the bridge starts or does not answer`() {
        both("07-uebersicht-startet", overview(state = ChatBridges.NET_STARTING)) {
            rule.onNodeWithText("Startet").assertIsDisplayed()
            text("Die Brücke wird gerade gestartet").assertIsDisplayed()
            assertEquals(0, rule.onAllNodesWithText("Verknüpfen").fetchSemanticsNodes().size)
        }
        both("08-uebersicht-nicht-erreichbar", overview(state = ChatBridges.NET_UNREACHABLE, login = login(ChatBridges.LOGIN_CONNECTED))) {
            rule.onNodeWithText("Nicht erreichbar").assertIsDisplayed()
            text("Die Brücke antwortet gerade nicht").assertIsDisplayed()
            assertEquals(0, rule.onAllNodesWithText("Trennen").fetchSemanticsNodes().size)
        }
    }

    @Test
    fun `an outdated bridge - the banner, Veraltet, the administrator updates it`() {
        both("09-uebersicht-veraltet", overview(outdated = true, login = login(ChatBridges.LOGIN_CONNECTED))) {
            text("Die Brücke für WhatsApp ist älter als vorgesehen").assertIsDisplayed()
            assertEquals(2, rule.onAllNodesWithText("Veraltet").fetchSemanticsNodes().size)
            rule.onNodeWithText("Server · Brücke veraltet").performScrollTo().assertIsDisplayed()
            text("Das Aktualisieren macht dein Administrator").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `loading, no answer, nothing offered`() {
        both("10-laedt", LinkedUiState()) { rule.onNodeWithText("Wird geladen …").assertIsDisplayed() }
        var again = 0
        both("11-nicht-geladen", LinkedUiState(loading = false, loadError = ChatBridgeError.OFFLINE), LinkedActions(refresh = { again++ })) {
            text("Keine Verbindung zu deinem GateControl-Server").assertIsDisplayed()
            rule.onNodeWithText("Erneut versuchen").performClick()
        }
        assertEquals(2, again)
        both("12-nichts-angeboten", LinkedUiState(loading = false, info = ChatBridgesInfo(available = false))) {
            rule.onNodeWithText("Auf diesem Server ist gerade kein Dienst zum Verknüpfen eingeschaltet.").assertIsDisplayed()
        }
    }

    // ─── the notes ──────────────────────────────────────────────────

    @Test
    fun `the notes - Weiter only with the tick`() {
        var confirmed = 0
        both("13-hinweise", overview().copy(view = LinkedView.Notes(wa)), LinkedActions(confirmNotes = { confirmed++ })) {
            rule.onNodeWithText("Bevor du WhatsApp verknüpfst").assertIsDisplayed()
            text("kein offizielles Programm von WhatsApp").assertIsDisplayed()
            text("WhatsApp kann Konten sperren").assertIsDisplayed()
            text("mindestens alle 14 Tage online").assertIsDisplayed()
            text("endet an der Brücke auf deinem GateControl-Server").assertIsDisplayed()
            rule.onNodeWithText("Ein Hinweis, kein Verbot – du entscheidest.").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Weiter").performScrollTo().assertIsNotEnabled()
            rule.onNodeWithTag("linked-notes-check").performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithText("Weiter").performScrollTo().assertIsEnabled().performClick()
        }
        assertEquals(2, confirmed)
    }

    @Test
    fun `the notes with the tick set`() {
        for (dark in listOf(false, true)) {
            page(overview().copy(view = LinkedView.Notes(wa)), dark)
            rule.onNodeWithTag("linked-notes-check").performScrollTo().performClick()
            rule.waitForIdle()
            shot("14-hinweise-bestaetigt", dark)
            rule.onNodeWithText("Weiter").assertIsEnabled()
        }
    }

    // ─── the link ───────────────────────────────────────────────────

    @Test
    fun `the number - typed, handed over as typed, never kept by the page`() {
        val sent = mutableListOf<String>()
        both("15-nummer", link(LinkPhase.Phone("phone", "phone_number")), LinkedActions(submitPhone = { sent += it })) {
            rule.onNodeWithText("WhatsApp verknüpfen").assertIsDisplayed()
            rule.onNodeWithText("Mit Code (dieses Handy)").assertIsSelected()
            rule.onNodeWithText("QR-Code (zweites Gerät)").assertIsDisplayed()
            rule.onNodeWithText("Deine Nummer bei WhatsApp").assertIsDisplayed()
            text("Die Nummer geht an die Brücke und wird im Messenger nicht gespeichert").assertIsDisplayed()
            rule.onNode(hasSetTextAction()).performTextInput("+49 1512 1234567")
            rule.onNodeWithText("Code anfordern").performClick()
        }
        assertEquals(listOf("+49 1512 1234567", "+49 1512 1234567"), sent)
    }

    @Test
    fun `the number - not a number, refused by the service`() {
        both("16-nummer-ungueltig", link(LinkPhase.Phone("phone", "phone_number", error = PhoneError.INVALID))) {
            text("Bitte die Nummer mit Ländervorwahl eingeben").assertIsDisplayed()
        }
        both("17-nummer-abgelehnt", link(LinkPhase.Phone("phone", "phone_number", error = PhoneError.REFUSED))) {
            text("Mit dieser Nummer hat es nicht geklappt").assertIsDisplayed()
        }
    }

    @Test
    fun `the code - large, to copy, with the way through WhatsApp`() {
        val copied = mutableListOf<String>()
        both("18-code", link(LinkPhase.Code("ABCD-1234")), LinkedActions(copyCode = { copied += it })) {
            rule.onNodeWithText("Gib diesen Code in WhatsApp ein:").assertIsDisplayed()
            rule.onNodeWithContentDescription("Code: ABCD-1234").assertIsDisplayed()
            rule.onNodeWithText("WhatsApp öffnen › Einstellungen › Verknüpfte Geräte").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("„Gerät hinzufügen“ › „Stattdessen mit Telefonnummer verknüpfen“").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Code eingeben").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Der Code gilt nur kurz und wird nirgends gespeichert.").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Warte auf die Bestätigung am Handy …").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Code kopieren").performScrollTo().performClick()
        }
        assertEquals(listOf("ABCD-1234", "ABCD-1234"), copied)
    }

    @Test
    fun `the QR code - for a second phone, and the way out for one phone only`() {
        val matrix = ChatBridges.qr("2@AbCdEfGhIjKlMnOpQrStUvWxYz0123456789,abcdefghijklmnop,qrstuvwxyz")!!
        val flows = mutableListOf<String>()
        both("19-qr", link(LinkPhase.Qr(matrix), ChatBridges.FLOW_QR), LinkedActions(selectFlow = { flows += it })) {
            rule.onNodeWithText("QR-Code (zweites Gerät)").assertIsSelected()
            text("Zeige diesen Code auf einem zweiten Bildschirm an").assertIsDisplayed()
            rule.onNodeWithContentDescription("QR-Code zum Verknüpfen").assertIsDisplayed()
            rule.onNodeWithText("„Gerät hinzufügen“ und den Code scannen").performScrollTo().assertIsDisplayed()
            text("ein Handy kann seinen eigenen Bildschirm nicht scannen").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Mit Code (dieses Handy)").performScrollTo().performClick()
        }
        assertEquals(listOf(ChatBridges.FLOW_PHONE, ChatBridges.FLOW_PHONE), flows)
        // the code is drawn dark on white in both themes: a scanner needs that
        page(link(LinkPhase.Qr(matrix), ChatBridges.FLOW_QR), dark = true)
        val bmp = rule.onNodeWithTag("linked-qr").captureToImage().asAndroidBitmap()
        val cell = bmp.width / (matrix.size + 8f)
        fun px(x: Int, y: Int) = bmp.getPixel(((x + 4.5f) * cell).toInt(), ((y + 4.5f) * cell).toInt()) and 0xFFFFFF
        assertEquals("a dark module of the finder pattern", 0x000000, px(0, 0))
        assertEquals("the light ring inside it", 0xFFFFFF, px(1, 1))
        assertEquals("the quiet zone", 0xFFFFFF, bmp.getPixel(bmp.width / 2, (cell * 2).toInt()) and 0xFFFFFF)
    }

    @Test
    fun `the code ran out, the bridge refused, no connection - a new attempt`() {
        var again = 0
        both("20-abgelaufen", link(LinkPhase.Failed(ChatBridgeError.EXPIRED)), LinkedActions(retry = { again++ })) {
            rule.onNodeWithText("Der Code ist abgelaufen, bevor er bestätigt wurde.").assertIsDisplayed()
            rule.onNodeWithText("Neuen Code anfordern").performClick()
        }
        assertEquals(2, again)
        both("21-qr-nicht-anzeigbar", link(LinkPhase.Failed(ChatBridgeError.UNSUPPORTED), ChatBridges.FLOW_QR)) {
            text("Diesen Anmeldeschritt kann GateControl nicht anzeigen").assertIsDisplayed()
            rule.onNodeWithText("Neuen QR-Code anzeigen").assertIsDisplayed()
        }
        both("22-keine-verbindung", link(LinkPhase.Failed(ChatBridgeError.OFFLINE))) {
            text("Keine Verbindung zu deinem GateControl-Server").assertIsDisplayed()
        }
        both("23-startet", link(LinkPhase.Starting)) { rule.onNodeWithText("Wird geladen …").assertIsDisplayed() }
    }

    @Test
    fun `every error has its own words`() {
        val res = rule.activity.resources
        val texts = ChatBridgeError.entries.associateWith { res.getString(errorText(it)) }
        assertEquals(ChatBridgeError.entries.size, texts.values.toSet().size)
        assertTrue(texts.getValue(ChatBridgeError.LOCKED).contains("Eltern"))
        assertEquals("02.11.2026", linkedDate("2026-11-02T08:00:00.000Z", java.util.Locale.GERMANY, java.time.ZoneId.of("Europe/Berlin")))
        assertEquals("", linkedDate(null, java.util.Locale.GERMANY))
        assertEquals("", linkedDate("nonsense", java.util.Locale.GERMANY))
    }
}
