package com.gatecontrol.android.terminal

import android.app.Application
import android.os.Looper
import com.gatecontrol.android.remote.RemoteApi
import com.gatecontrol.android.remote.RemoteResult
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * The one session against a test server speaking `gc-term.v1`: the subprotocol with the token (never in the URL),
 * `hello` with the size, `ready`, the terminal's bytes into the emulator, input out, a lost socket reconnecting
 * with a new token after 1 s, and `shell_refused` ending for good (no further token).
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], application = Application::class)
class TermSessionsTest {
    private val server = MockWebServer()
    private val api = mockk<RemoteApi>()
    private val received = CopyOnWriteArrayList<String>()
    private val serverSocket = AtomicReference<WebSocket?>()
    private val ref = "R".repeat(43)
    private var tokens = 0
    /** What the test server answers to `hello`. */
    @Volatile private var onHello: (WebSocket) -> Unit = { it.send("""{"t":"ready","tmux":{"available":true,"session":"tmux"},"recording":true,"mode":"control"}""") }

    private val client = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = Unit
        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) = Unit
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) = Unit
        override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    private fun upgrade() = MockResponse().setHeader("Sec-WebSocket-Protocol", TermProtocol.PROTOCOL).withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            serverSocket.set(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received += text
            if (text.contains("\"hello\"")) onHello(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            received += "bin:" + bytes.utf8()
        }
    })

    @Before
    fun setUp() {
        repeat(4) { server.enqueue(upgrade()) }
        server.start()
        coEvery { api.termToken(ref) } answers {
            tokens++
            RemoteResult.Ok(TermToken("T$tokens".padEnd(43, 'x'), "/api/v1/remote/ssh-tunnel"))
        }
        every { api.relayClient() } returns (server.url("/").toString() to OkHttpClient())
    }

    private val created = mutableListOf<TermSessions>()

    private fun sessions() = TermSessions(api).also { created += it }

    @After
    fun tearDown() {
        created.forEach { it.disconnect() }
        serverSocket.get()?.close(1000, null)
        repeat(25) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        server.shutdown()
    }

    private fun waitFor(what: String, cond: () -> Boolean) {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out: $what")
    }

    private fun screen(s: TerminalSession) = s.emulator.screen.transcriptText

    @Test
    fun `connect, ready, output, input, reconnect with a new token, shell_refused ends`() {
        val sessions = sessions()
        sessions.start(TermTicket(ref, 0L, true, "tmux", 2, "pi-werkstatt"), "pi-werkstatt")
        val term = sessions.terminal(client)!!
        term.updateSize(80, 24, 10, 20)

        waitFor("ready") { sessions.ui.value?.phase is TermPhase.Ready }
        val first = server.takeRequest()
        assertEquals("/api/v1/remote/ssh-tunnel", first.path)
        assertEquals("gc-term.v1, token.T1".padEnd("gc-term.v1, token.".length + 43, 'x'), first.getHeader("Sec-WebSocket-Protocol"))
        assertEquals("""{"t":"hello","cols":80,"rows":24,"term":"xterm-256color"}""", received.first())
        assertTrue((sessions.ui.value!!.phase as TermPhase.Ready).recording)

        // output into the emulator, input out as binary
        serverSocket.get()!!.send("pi@pi-werkstatt:~ $ ".encodeUtf8())
        waitFor("output") { screen(term).contains("pi@pi-werkstatt") }
        term.write("ls\r")
        waitFor("input") { received.contains("bin:ls\r") }

        // a resize goes out at once
        term.updateSize(60, 20, 10, 20)
        waitFor("resize") { received.contains("""{"t":"resize","cols":60,"rows":20}""") }

        // lost without exit: reconnect after 1 s with a new token and a new hello
        received.clear()
        serverSocket.get()!!.close(1001, null)
        waitFor("reconnecting") { sessions.ui.value?.phase is TermPhase.Reconnecting }
        assertEquals(1, (sessions.ui.value!!.phase as TermPhase.Reconnecting).attempt)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100))
        waitFor("ready again") { sessions.ui.value?.phase is TermPhase.Ready && received.any { it.contains("hello") } }
        assertEquals(2, tokens)
        assertEquals("""{"t":"hello","cols":60,"rows":20,"term":"xterm-256color"}""", received.first { it.contains("hello") })

        // a refused shell: for good (design question 9), no further token
        onHello = { it.send("""{"t":"error","code":"shell_refused"}"""); it.close(1000, null) }
        serverSocket.get()!!.close(1001, null)
        waitFor("reconnecting again") { sessions.ui.value?.phase is TermPhase.Reconnecting }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100))
        waitFor("failed") { sessions.ui.value?.phase is TermPhase.Failed }
        assertEquals(TermPhase.Failed("shell_refused", TermProtocol.Verdict.FINAL), sessions.ui.value!!.phase)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        coVerify(exactly = 3) { api.termToken(ref) }

        sessions.disconnect()
        assertEquals(null, sessions.ui.value)
    }

    @Test
    fun `an expired ticket goes back to the list, a host key is asked and answered`() {
        coEvery { api.termToken(ref) } returns RemoteResult.Failed("not_found", 404)
        val sessions = sessions()
        sessions.start(TermTicket(ref, 0L, false, null, 2, "pi"), "pi")
        sessions.terminal(client)!!.updateSize(80, 24, 10, 20)
        waitFor("expired") { sessions.ui.value?.phase is TermPhase.Failed }
        assertEquals(TermProtocol.Verdict.EXPIRED, (sessions.ui.value!!.phase as TermPhase.Failed).verdict)

        coEvery { api.termToken(ref) } returns RemoteResult.Ok(TermToken("K".repeat(43), "/api/v1/remote/ssh-tunnel"))
        onHello = { it.send("""{"t":"hostkey","hostKey":{"alg":"ssh-ed25519","sha256":"q7Xv2mL0cR9sT4bN8kW1zE5yH3gJ6uA0pD2fK9iO7lM"}}""") }
        val again = sessions()
        again.start(TermTicket(ref, 0L, false, null, 2, "pi"), "pi")
        again.terminal(client)!!.updateSize(80, 24, 10, 20)
        waitFor("host key") { again.ui.value?.phase is TermPhase.HostKey }
        again.answerHostKey(false)
        waitFor("distrust") { received.contains("""{"t":"distrust"}""") || again.ui.value?.phase is TermPhase.Failed }
        assertEquals(TermPhase.Failed("host_key_rejected", TermProtocol.Verdict.FINAL), again.ui.value!!.phase)
    }
}
