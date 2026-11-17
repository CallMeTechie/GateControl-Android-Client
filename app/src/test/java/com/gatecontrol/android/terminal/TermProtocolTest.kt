package com.gatecontrol.android.terminal

import android.view.KeyEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The frames of `gc-term.v1` and the app's rules around them (contract §3, §5; design questions 6, 9, 10). */
class TermProtocolTest {

    @Test
    fun `frames the app sends`() {
        assertEquals("""{"t":"hello","cols":80,"rows":24,"term":"xterm-256color"}""", TermProtocol.hello(80, 24))
        // clamped to the server's limits
        assertEquals("""{"t":"resize","cols":500,"rows":5}""", TermProtocol.resize(900, 1))
        assertEquals("""{"t":"trust"}""", TermProtocol.trust(true))
        assertEquals("""{"t":"distrust"}""", TermProtocol.trust(false))
        assertEquals("""{"t":"bye"}""", TermProtocol.bye())
        assertEquals("gc-term.v1, token.abc", TermProtocol.protocolHeader("abc"))
    }

    @Test
    fun `frames of the server`() {
        val hk = TermProtocol.parse("""{"t":"hostkey","hostKey":{"alg":"ssh-ed25519","sha256":"q7Xv2mL0cR9sT4bN8kW1zE5yH3gJ6uA0pD2fK9iO7lM"}}""")
        assertEquals(TermProtocol.Message.AskHostKey(TermProtocol.HostKey("ssh-ed25519", "q7Xv2mL0cR9sT4bN8kW1zE5yH3gJ6uA0pD2fK9iO7lM")), hk)
        assertEquals(
            TermProtocol.Message.Ready(true, "tmux", true, false, true),
            TermProtocol.parse("""{"t":"ready","hostKey":{"alg":"ssh-ed25519","sha256":"x"},"hostKeyNew":true,"tmux":{"available":true,"session":"tmux"},"recording":true,"mode":"control"}"""),
        )
        assertEquals(TermProtocol.Message.Ready(false, null, false, true, false), TermProtocol.parse("""{"t":"ready","tmux":{"available":false,"session":null},"mode":"view"}"""))
        assertEquals(TermProtocol.Message.Exit(0, "closed"), TermProtocol.parse("""{"t":"exit","code":0,"reason":"closed"}"""))
        assertEquals(TermProtocol.Message.Exit(null, "closed"), TermProtocol.parse("""{"t":"exit","code":null,"reason":"<b>"}"""))
        assertEquals(TermProtocol.Message.Error("host_key_mismatch"), TermProtocol.parse("""{"t":"error","code":"host_key_mismatch"}"""))
        assertEquals(TermProtocol.Message.Pong, TermProtocol.parse("""{"t":"pong"}"""))
        assertNull(TermProtocol.parse("""{"t":"hostkey","hostKey":{"alg":"bad alg","sha256":"x"}}"""))
        assertNull(TermProtocol.parse("not json"))
        assertNull(TermProtocol.parse("""{"t":"other"}"""))
    }

    @Test
    fun `which errors end for good`() {
        listOf("host_key_rejected", "host_key_unpinned", "auth_failed", "key_unreadable", "credential_required", "credential_unreadable", "recording_unavailable", "plugin_unavailable")
            .forEach { assertEquals(TermProtocol.Verdict.FINAL, TermProtocol.verdict(it), it) }
        assertEquals(TermProtocol.Verdict.LOCKED, TermProtocol.verdict("host_key_mismatch"))
        // design question 9: a refused shell does not change with another attempt
        assertEquals(TermProtocol.Verdict.FINAL, TermProtocol.verdict("shell_refused"))
        listOf("unreachable", "timeout", "listener_required", "hello_timeout", "target_offline", "http_502")
            .forEach { assertEquals(TermProtocol.Verdict.RETRY, TermProtocol.verdict(it), it) }
        assertEquals(TermProtocol.Verdict.LIMIT, TermProtocol.verdict("limit_reached"))
        assertEquals(TermProtocol.Verdict.EXPIRED, TermProtocol.verdict("not_found"))
        assertEquals(listOf(1, 2, 4, 8, 15), TermProtocol.BACKOFF_SECONDS)
        assertTrue(TermProtocol.endedByAdmin("ended_by_plugin"))
        assertFalse(TermProtocol.endedByAdmin("closed"))
    }

    @Test
    fun `tmux names`() {
        assertEquals("tmux", TermProtocol.suggestName(emptyList()))
        assertEquals("tmux-2", TermProtocol.suggestName(listOf("tmux")))
        assertEquals("tmux-3", TermProtocol.suggestName(listOf("tmux", "tmux-2")))
        assertEquals("werk", TermProtocol.suggestName(listOf("tmux"), "werk"))
        assertTrue(TermProtocol.validName("build_2-a"))
        listOf("", "-x", "_x", "a.b", "a:b", "a b", "a'b", "x".repeat(33)).forEach { assertFalse(TermProtocol.validName(it), it) }
    }

    @Test
    fun `fingerprint in groups of four`() {
        assertEquals("q7Xv 2mL0 cR9s T4bN 8kW1 zE5y H3gJ 6uA0 pD2f K9iO 7lM", TermProtocol.groups("q7Xv2mL0cR9sT4bN8kW1zE5yH3gJ6uA0pD2fK9iO7lM="))
    }

    @Test
    fun `pasting asks for more than one line and cannot end a bracketed paste`() {
        assertEquals(1, TermProtocol.pasteLines("ls -la\n"))
        assertEquals(3, TermProtocol.pasteLines("a\r\nb\nc"))
        assertEquals(0, TermProtocol.pasteLines(""))
        assertEquals("a\rb\tc", TermProtocol.pasteText("a\nb\tc\u001b[201~".replace("[201~", "")))
        assertEquals("x[201~y", TermProtocol.pasteText("x\u001b[201~y"))
    }

    @Test
    fun `Strg and Alt latch once, then locked, then off`() {
        assertEquals(Latch.ONCE, Latch.OFF.tapped())
        assertEquals(Latch.LOCKED, Latch.ONCE.tapped())
        assertEquals(Latch.OFF, Latch.LOCKED.tapped())
        assertEquals(Latch.OFF, Latch.ONCE.used())
        assertEquals(Latch.LOCKED, Latch.LOCKED.used())
    }

    @Test
    fun `the bar has two rows of six upright and one row with F in landscape`() {
        assertEquals(listOf(6, 6), TermKeys.portrait.map { it.size })
        assertEquals(1, TermKeys.landscape.size)
        assertEquals(TermKey.FN, TermKeys.landscape[0].last().modifier)
        assertEquals(listOf("Esc", "Tab", "Ctrl", "Alt", "|", "~", "/", "-", "←", "↓", "↑", "→"), TermKeys.portrait.flatten().map { it.label })
        assertEquals(14, TermKeys.functions.size)
        assertEquals(KeyEvent.KEYCODE_F12, TermKeys.functions[11].code)
    }

    @Test
    fun `open a window and the lines of the questions`() {
        // tmux's prefix Ctrl-B, then the digit; from 10 on „select window by index“ with Enter
        assertEquals(listOf<Byte>(2, '0'.code.toByte()), TermProtocol.selectWindow(0)!!.toList())
        assertEquals(listOf<Byte>(2, '9'.code.toByte()), TermProtocol.selectWindow(9)!!.toList())
        assertEquals("\u0002'12\r", String(TermProtocol.selectWindow(12)!!, Charsets.US_ASCII))
        assertNull(TermProtocol.selectWindow(-1))

        val windows = listOf(
            TmuxWindow("@1", 0, "bash", false, "bash"),
            TmuxWindow("@2", 1, "logs", true, "journalctl"),
            TmuxWindow("@3", 2, "htop", false, null),
            TmuxWindow("@4", 3, "x", false, "make"),
            TmuxWindow("@5", 4, "y", false, "vim"),
        )
        val s = TmuxSession("gc-appserver", 5, 0, null, true, "$1", windows)
        assertEquals("bash, journalctl, htop, make, …", TermProtocol.commands(s))
        assertFalse(TermProtocol.lastWindow(s))
        assertTrue(TermProtocol.lastWindow(s.copy(windows = 1, windowList = windows.take(1))))
    }
}
