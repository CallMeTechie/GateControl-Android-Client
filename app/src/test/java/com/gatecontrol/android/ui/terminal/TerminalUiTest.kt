package com.gatecontrol.android.ui.terminal

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.gatecontrol.android.terminal.Latch
import com.gatecontrol.android.terminal.SshDevice
import com.gatecontrol.android.terminal.SshSheet
import com.gatecontrol.android.terminal.TermKey
import com.gatecontrol.android.terminal.TermPhase
import com.gatecontrol.android.terminal.TermProtocol
import com.gatecontrol.android.terminal.TermSettings
import com.gatecontrol.android.terminal.TermUi
import com.gatecontrol.android.terminal.TmuxChoice
import com.gatecontrol.android.terminal.TmuxList
import com.gatecontrol.android.terminal.TmuxSession
import com.gatecontrol.android.terminal.TmuxState
import com.gatecontrol.android.ui.theme.GateControlTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The SSH terminal drawn in German, light and dark: the sheet (resume preselected, a bad name locks the button, the
 * recording hint before a recorded start, a locked device), the terminal's states (ready with the red dot,
 * interrupted, ended by an administrator, the host key, a changed key) and the bar. The terminal's surface stays
 * dark in the light theme too (decision 5).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w411dp-h891dp")
class TerminalUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val device = SshDevice(2, "pi-werkstatt", "pi@192.168.1.40", true, "term", "tmux", "optional")
    private val list = TmuxList(
        listOf(
            TmuxSession("build", 1, 1, 1_000L, true),
            TmuxSession("tmux", 3, 0, 2_000L, true),
            TmuxSession("backup.alt", 1, 0, null, false),
        ),
        "tmux", "pinned",
    )

    private fun sheet(sheet: SshSheet, dark: Boolean, onConnect: (TmuxChoice?, Boolean) -> Unit = { _, _ -> }) {
        rule.setContent {
            GateControlTheme(darkTheme = dark) {
                Box(androidx.compose.ui.Modifier.background(MaterialTheme.colorScheme.surface)) { SshConnectContent(sheet, onConnect, {}) }
            }
        }
    }

    private fun sheetFlow(dark: Boolean) {
        var picked: Pair<TmuxChoice?, Boolean>? = null
        sheet(SshSheet(device, TmuxState.Loaded(list)), dark) { c, r -> picked = c to r }
        // the most recently active session first and preselected; the name the core cannot attach is only counted
        rule.onNodeWithText("Fortsetzen: tmux").assertIsDisplayed()
        rule.onNodeWithText("Fortsetzen: build").assertIsDisplayed()
        rule.onNodeWithText("angehängt").assertIsDisplayed()
        rule.onNodeWithText("1 weitere Sitzung(en) haben einen Namen, den die App nicht anhängen kann. Sie werden nicht angeboten.").assertIsDisplayed()
        rule.onNodeWithTag("ssh_connect").performClick()
        assertEquals(TmuxChoice.Attach("tmux") to false, picked)

        // a new session: tmux is taken → tmux-2; a bad name locks the button
        rule.onNodeWithTag("tmux_new").performClick()
        rule.onNodeWithTag("tmux_name").performTextReplacement("bad.name")
        rule.onNodeWithTag("ssh_connect").assertIsNotEnabled()
        rule.onNodeWithTag("tmux_name").performTextReplacement("tmux-2")
        rule.onNodeWithTag("ssh_connect").assertIsEnabled()

        // recording switched on: the hint first, record: true only after „Verstanden, verbinden“
        rule.onNodeWithText("Diese Sitzung aufzeichnen").performClick()
        rule.onNodeWithTag("ssh_connect").performClick()
        rule.onNodeWithText("Diese Sitzung wird aufgezeichnet").assertIsDisplayed()
        rule.onNodeWithTag("rec_ok").performClick()
        assertEquals(TmuxChoice.Create("tmux-2") to true, picked)
    }

    @Test fun `sheet light`() = sheetFlow(dark = false)
    @Test fun `sheet dark`() = sheetFlow(dark = true)

    @Test
    fun `first connection, tmux missing and a locked device`() {
        var state by mutableStateOf<TmuxState>(TmuxState.Unpinned)
        rule.setContent { GateControlTheme(darkTheme = false) { SshConnectContent(SshSheet(device, state), { _, _ -> }, {}) } }
        rule.onNodeWithText("Erste Verbindung").assertIsDisplayed()
        rule.onNodeWithText("Standardsitzung").assertIsDisplayed()
        state = TmuxState.Missing
        rule.onNodeWithText("tmux fehlt auf dem Gerät").assertIsDisplayed()
        rule.onNodeWithText("Trotzdem verbinden").assertIsDisplayed()
        state = TmuxState.Locked
        rule.onNodeWithText("Host-Schlüssel hat sich geändert").assertIsDisplayed()
        rule.onNodeWithText("Zurück zur Liste").assertIsDisplayed()
    }

    private val noop = TerminalScreenActions(
        createView = { error("no view in tests") }, releaseView = {}, onKey = {}, onPaste = {}, onPasteConfirm = { _, _ -> }, onPasteCancel = {},
        onCopyAll = {}, onFont = {}, onExtraKeys = {}, onTrust = {}, onReconnect = {}, onLeave = {}, onLeaveCancel = {}, onDisconnect = {},
    )

    private fun terminal(ui: TermUi, dark: Boolean, actions: TerminalScreenActions = noop, ctrl: Latch = Latch.OFF) {
        rule.setContent {
            GateControlTheme(darkTheme = dark) {
                TerminalScreen(
                    ui = ui, settings = TermSettings(), ctrl = ctrl, alt = Latch.OFF, pendingPaste = null, fontHint = 0L,
                    confirmLeave = false, actions = actions, terminal = { m -> Box(m) },
                )
            }
        }
    }

    private fun ready(dark: Boolean) {
        var key: TermKey? = null
        terminal(
            TermUi(2, "pi-werkstatt", "tmux", TermPhase.Ready("tmux", false, true, false), 46, 24), dark,
            actions = TerminalScreenActions(
                createView = { error("no view") }, releaseView = {}, onKey = { key = it }, onPaste = {}, onPasteConfirm = { _, _ -> }, onPasteCancel = {},
                onCopyAll = {}, onFont = {}, onExtraKeys = {}, onTrust = {}, onReconnect = {}, onLeave = {}, onLeaveCancel = {}, onDisconnect = {},
            ),
            ctrl = Latch.ONCE,
        )
        rule.onNodeWithTag("rec_dot").assertIsDisplayed()
        rule.onNodeWithText("Wird aufgezeichnet · tmux: tmux · 46×24").assertIsDisplayed()
        rule.onNodeWithTag("key_Strg").assertIsDisplayed()
        rule.onNodeWithTag("key_Esc").performClick()
        assertEquals("Esc", key?.label)
        // the terminal's surface is dark in both themes, the bars follow the theme
        val img = rule.onNodeWithTag("terminal_surface").captureToImage().asAndroidBitmap()
        assertEquals(Color.Black.toArgb(), img.getPixel(img.width / 2, img.height / 2))
        val bar = rule.onNodeWithTag("term_keys").captureToImage().asAndroidBitmap()
        if (dark) assertNotEquals(Color.White.toArgb(), bar.getPixel(2, 2)) else assertNotEquals(Color.Black.toArgb(), bar.getPixel(2, 2))
    }

    @Test fun `terminal ready light`() = ready(dark = false)
    @Test fun `terminal ready dark`() = ready(dark = true)

    @Test
    fun `interrupted, ended by an administrator, host key and a changed key`() {
        var phase by mutableStateOf<TermPhase>(TermPhase.Reconnecting(3, System.currentTimeMillis() + 4_000))
        var trusted: Boolean? = null
        rule.setContent {
            GateControlTheme(darkTheme = false) {
                TerminalScreen(
                    ui = TermUi(2, "pi-werkstatt", "tmux", phase, 46, 17), settings = TermSettings(), ctrl = Latch.OFF, alt = Latch.OFF,
                    pendingPaste = null, fontHint = 0L, confirmLeave = false,
                    actions = TerminalScreenActions(
                        createView = { error("no view") }, releaseView = {}, onKey = {}, onPaste = {}, onPasteConfirm = { _, _ -> }, onPasteCancel = {},
                        onCopyAll = {}, onFont = {}, onExtraKeys = {}, onTrust = { trusted = it }, onReconnect = {}, onLeave = {}, onLeaveCancel = {}, onDisconnect = {},
                    ),
                    terminal = { m -> Box(m) },
                )
            }
        }
        rule.onNodeWithText("Verbindung unterbrochen").assertIsDisplayed()
        rule.onNodeWithText("Jetzt versuchen").assertIsDisplayed()
        phase = TermPhase.Exited("ended_by_plugin", null)
        rule.onNodeWithText("Ein Administrator hat die Sitzung beendet.").assertIsDisplayed()
        rule.onNodeWithText("Neu verbinden").assertDoesNotExist()
        phase = TermPhase.HostKey(TermProtocol.HostKey("ssh-ed25519", "q7Xv2mL0cR9sT4bN8kW1zE5yH3gJ6uA0pD2fK9iO7lM"), System.currentTimeMillis() + 60_000)
        rule.onNodeWithText("q7Xv 2mL0 cR9s T4bN 8kW1 zE5y H3gJ 6uA0 pD2f K9iO 7lM").assertIsDisplayed()
        rule.onNodeWithTag("hk_trust").performClick()
        assertEquals(true, trusted)
        phase = TermPhase.Failed("host_key_mismatch", TermProtocol.Verdict.LOCKED)
        rule.onNodeWithText("Host-Schlüssel hat sich geändert").assertIsDisplayed()
        rule.onNodeWithTag("hk_back").assertIsDisplayed()
        phase = TermPhase.Failed("shell_refused", TermProtocol.Verdict.FINAL)
        rule.onNodeWithText("Das Gerät hat die Shell abgelehnt.").assertIsDisplayed()
    }
}
