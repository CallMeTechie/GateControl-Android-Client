package com.gatecontrol.android.terminal

import android.os.Handler
import android.os.Looper
import com.gatecontrol.android.remote.RemoteApi
import com.gatecontrol.android.remote.RemoteResult
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Where the one SSH session of the app stands (shown by [TerminalActivity]). */
sealed interface TermPhase {
    /** Ticket → token → socket → `hello`; [attempt] 0 is the first connection. */
    data class Connecting(val attempt: Int = 0) : TermPhase
    /** First connection: the person compares the fingerprint (60 s, then the server gives up). */
    data class HostKey(val key: TermProtocol.HostKey, val until: Long) : TermPhase
    data class Ready(val tmuxSession: String?, val tmuxMissing: Boolean, val recording: Boolean, val view: Boolean) : TermPhase
    /** Lost without an end: next attempt [attempt] of 5 at [at] (epoch ms). */
    data class Reconnecting(val attempt: Int, val at: Long) : TermPhase
    /** Five attempts without success. */
    data object Offline : TermPhase
    /** The shell or tmux ended (`exit`). */
    data class Exited(val reason: String, val code: Int?) : TermPhase
    /** An error that ends the session ([verdict] says how). */
    data class Failed(val code: String, val verdict: TermProtocol.Verdict) : TermPhase
}

/** The session as the terminal shows it; [cols] × [rows] the size the server knows. */
data class TermUi(
    val deviceId: Long,
    val deviceName: String,
    val tmux: String?,
    val phase: TermPhase,
    val cols: Int = 0,
    val rows: Int = 0,
)

/**
 * The one SSH session of the app at a time (design decision 4): the ticket's ref, the single-use tokens, the
 * WebSocket `gc-term.v1` and the terminal emulator ([TerminalSession]). It outlives the activity — a rotation or a
 * trip to another app keeps the screen; a lost socket reconnects with the backoff 1/2/4/8/15 s, each time with a new
 * token and the same ref (contract §5). No foreground service: back in the app a closed socket reconnects at once.
 *
 * Never logs the ref, a token, a fingerprint or anything of the terminal's content.
 */
@Singleton
class TermSessions @Inject constructor(private val api: RemoteApi) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _ui = MutableStateFlow<TermUi?>(null)
    val ui: StateFlow<TermUi?> = _ui.asStateFlow()

    private var ticket: TermTicket? = null
    private var session: TerminalSession? = null
    private var viewClient: TerminalSessionClient? = null
    private var socket: WebSocket? = null
    private var open = false
    private var cols = 0
    private var rows = 0
    private var connectJob: Job? = null
    private var pingJob: Job? = null
    private var lastPong = 0L
    /** Set once the session ended for good or the person left: no reconnect any more. */
    private var stopped = true

    /** The emulator of the running session; created on first use, bound to the view's client. */
    fun terminal(client: TerminalSessionClient): TerminalSession? {
        if (ticket == null) return null
        viewClient = client
        val s = session ?: TerminalSession(TRANSCRIPT_ROWS, client, remote).also { session = it }
        s.updateTerminalSessionClient(client)
        return s
    }

    /** The view is gone (activity destroyed): keep the session, forget the view. */
    fun detach(client: TerminalSessionClient) {
        if (viewClient === client) viewClient = null
    }

    /** A new session with [t] — the one running before ends (its tmux keeps running on the device). */
    fun start(t: TermTicket, deviceName: String) {
        endSocket(sendBye = true)
        session?.finish()
        session = null
        ticket = t
        stopped = false
        cols = 0
        rows = 0
        _ui.value = TermUi(t.deviceId, t.deviceName.ifBlank { deviceName }, t.tmux, TermPhase.Connecting())
        // the socket opens once the view knows its size (the first `hello` carries it)
    }

    private val remote = object : TerminalSession.Remote {
        override fun send(data: ByteArray, offset: Int, count: Int) {
            // input only while connected and allowed (view mode: the server drops it anyway)
            val p = _ui.value?.phase
            if (!open || p !is TermPhase.Ready || p.view) return
            socket?.send(data.toByteString(offset, count))
        }

        override fun resize(columns: Int, rows: Int) = onSize(columns, rows)

        // OSC 52 of the remote side: never written to the phone's clipboard without the person
        override fun onRemoteClipboard(text: String?) = Unit
    }

    private fun onSize(c: Int, r: Int) {
        if (c <= 0 || r <= 0) return
        val changed = c != cols || r != rows
        cols = c
        rows = r
        _ui.value = _ui.value?.copy(cols = c, rows = r)
        if (open && changed) socket?.send(TermProtocol.resize(c, r))
        if (!open && connectJob == null && !stopped && _ui.value?.phase is TermPhase.Connecting) connect(0)
    }

    private fun setPhase(p: TermPhase) {
        _ui.value = _ui.value?.copy(phase = p)
    }

    /** Ticket → token → socket. [attempt] 0: the first connection or „Neu verbinden“; 1…5: the backoff. */
    private fun connect(attempt: Int) {
        val t = ticket ?: return
        connectJob?.cancel()
        attemptNow = attempt
        connectJob = scope.launch {
            if (attempt > 0) {
                val wait = TermProtocol.BACKOFF_SECONDS[(attempt - 1).coerceAtMost(TermProtocol.BACKOFF_SECONDS.size - 1)] * 1000L
                setPhase(TermPhase.Reconnecting(attempt, System.currentTimeMillis() + wait))
                delay(wait)
            }
            setPhase(TermPhase.Connecting(attempt))
            when (val r = api.termToken(t.ref)) {
                is RemoteResult.Ok -> if (!openSocket(r.value)) fail("invalid_answer")
                is RemoteResult.Failed -> {
                    connectJob = null
                    onError(r.code, attempt)
                }
                RemoteResult.Offline -> {
                    connectJob = null
                    lost(attempt)
                }
            }
        }
    }

    private fun openSocket(token: TermToken): Boolean {
        val (base, client) = api.relayClient() ?: return false
        val url = base.toHttpUrlOrNull()?.newBuilder()?.encodedPath(token.wsPath)?.build() ?: return false
        val ws = client.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
            .newWebSocket(Request.Builder().url(url).header("Sec-WebSocket-Protocol", TermProtocol.protocolHeader(token.token)).build(), Listener())
        socket = ws
        return true
    }

    private var attemptNow = 0

    private fun onError(code: String, attempt: Int) {
        when (val v = TermProtocol.verdict(code)) {
            TermProtocol.Verdict.RETRY -> lost(attempt)
            else -> fail(code, v)
        }
    }

    /** Lost without an end: the next attempt, or [TermPhase.Offline] after the fifth. */
    private fun lost(attempt: Int) {
        endSocket(sendBye = false)
        if (stopped) return
        val next = attempt + 1
        if (next > TermProtocol.BACKOFF_SECONDS.size) {
            connectJob = null
            setPhase(TermPhase.Offline)
            return
        }
        connect(next)
    }

    private fun fail(code: String, verdict: TermProtocol.Verdict = TermProtocol.verdict(code)) {
        stopped = true
        connectJob?.cancel()
        connectJob = null
        endSocket(sendBye = false)
        setPhase(TermPhase.Failed(code, verdict))
    }

    private fun endSocket(sendBye: Boolean) {
        pingJob?.cancel()
        pingJob = null
        val ws = socket
        socket = null
        open = false
        if (ws != null) {
            if (sendBye) ws.send(TermProtocol.bye())
            ws.close(1000, null)
        }
    }

    /** The person's answer to the fingerprint (`distrust` ends — „Abbrechen“ goes back to the list). */
    fun answerHostKey(trust: Boolean) {
        if (_ui.value?.phase !is TermPhase.HostKey) return
        socket?.send(TermProtocol.trust(trust))
        if (trust) setPhase(TermPhase.Connecting(attemptNow)) else fail("host_key_rejected")
    }

    /** „Jetzt versuchen“ / „Neu verbinden“ / back in the app: a new attempt with the same ref now. */
    fun reconnectNow() {
        val p = _ui.value?.phase ?: return
        val again = when (p) {
            is TermPhase.Reconnecting, TermPhase.Offline -> true
            is TermPhase.Exited -> !TermProtocol.endedByAdmin(p.reason)
            is TermPhase.Failed -> p.verdict == TermProtocol.Verdict.FINAL || p.verdict == TermProtocol.Verdict.LIMIT
            else -> false
        }
        if (!again || ticket == null) return
        stopped = false
        attemptNow = 0
        connect(0)
    }

    /** The app came back: a socket that closed while away reconnects at once (no foreground service). */
    fun onForeground() {
        val p = _ui.value?.phase ?: return
        if (p is TermPhase.Reconnecting || p == TermPhase.Offline) reconnectNow()
    }

    /** „Trennen“: `bye`, then forget the session (its tmux keeps running). */
    fun disconnect() {
        stopped = true
        connectJob?.cancel()
        connectJob = null
        endSocket(sendBye = true)
        session?.finish()
        session = null
        ticket = null
        _ui.value = null
    }

    /** A paste: through the emulator, bracketed when the shell asked for it (ESC and C1 controls removed). */
    fun paste(text: String) {
        val s = session ?: return
        s.emulator?.paste(TermProtocol.pasteText(text)) ?: s.write(TermProtocol.pasteText(text))
    }

    private fun startPing(ws: WebSocket) {
        pingJob?.cancel()
        lastPong = System.currentTimeMillis()
        pingJob = scope.launch {
            while (isActive && socket === ws) {
                delay(TermProtocol.PING_SECONDS * 1000L)
                if (socket !== ws) break
                // two missed answers: the line is dead although the socket did not notice (a dead spot)
                if (System.currentTimeMillis() - lastPong > TermProtocol.PING_SECONDS * 2500L) {
                    ws.cancel()
                    break
                }
                ws.send(TermProtocol.ping())
            }
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            main.post {
                if (webSocket !== socket) return@post
                if (response.header("Sec-WebSocket-Protocol") != TermProtocol.PROTOCOL) {
                    Timber.d("Terminal: unexpected subprotocol")
                    fail("invalid_answer")
                    return@post
                }
                open = true
                connectJob = null
                webSocket.send(TermProtocol.hello(cols.coerceAtLeast(10), rows.coerceAtLeast(5)))
                startPing(webSocket)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            main.post {
                if (webSocket !== socket) return@post
                when (val m = TermProtocol.parse(text)) {
                    is TermProtocol.Message.AskHostKey -> setPhase(TermPhase.HostKey(m.hostKey, System.currentTimeMillis() + HOST_KEY_MS))
                    is TermProtocol.Message.Ready -> {
                        attemptNow = 0
                        setPhase(TermPhase.Ready(m.tmuxSession ?: _ui.value?.tmux, m.tmuxAvailable == false, m.recording, m.view))
                    }
                    is TermProtocol.Message.Exit -> {
                        stopped = true
                        endSocket(sendBye = false)
                        setPhase(TermPhase.Exited(m.reason, m.code))
                    }
                    is TermProtocol.Message.Error -> fail(m.code)
                    TermProtocol.Message.Pong -> lastPong = System.currentTimeMillis()
                    null -> Unit
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val data = bytes.toByteArray()
            main.post {
                if (webSocket !== socket) return@post
                lastPong = System.currentTimeMillis()
                session?.append(data, 0, data.size)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = gone(webSocket, null)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = gone(webSocket, response?.code)

        private fun gone(webSocket: WebSocket, http: Int?) {
            main.post {
                if (webSocket !== socket) return@post
                Timber.d("Terminal: socket closed (%s)", http ?: "-")
                when (http) {
                    503 -> fail("plugin_unavailable")
                    else -> lost(attemptNow)
                }
            }
        }
    }

    companion object {
        private const val TRANSCRIPT_ROWS = 3000
        private const val HOST_KEY_MS = 60_000L
    }
}
