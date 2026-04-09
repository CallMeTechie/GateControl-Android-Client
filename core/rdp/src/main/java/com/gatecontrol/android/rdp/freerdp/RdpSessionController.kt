package com.gatecontrol.android.rdp.freerdp

import android.content.Context
import com.freerdp.freerdpcore.application.GlobalApp
import com.freerdp.freerdpcore.application.SessionState
import com.freerdp.freerdpcore.services.LibFreeRDP
import com.gatecontrol.android.rdp.RdpConnectionParams
import kotlinx.coroutines.flow.MutableStateFlow
import timber.log.Timber

/**
 * Owns one FreeRDP session. Construction allocates a LibFreeRDP instance and
 * registers a UIEventListener. [connect] runs the blocking `LibFreeRDP.connect`
 * call on a dedicated background thread so the activity's main thread stays
 * responsive. [disconnect] and [release] are safe to call multiple times.
 *
 * Session state is observable via [events].
 *
 * Thread model: `connect()` returns immediately; callers observe [events]
 * for `ConnectionSuccess` / `ConnectionFailure`. `sendCursor*`, `sendKey*`
 * may be called from any thread (LibFreeRDP is thread-safe for input events).
 */
class RdpSessionController(
    private val context: Context,
    params: RdpConnectionParams,
    verifyCertificate: (
        unknown: RdpSessionEvent.VerifyCertificate?,
        changed: RdpSessionEvent.VerifyChangedCertificate?,
    ) -> Int,
    authenticate: (username: StringBuilder, password: StringBuilder) -> Boolean,
) {
    val events: MutableStateFlow<RdpSessionEvent> = MutableStateFlow(RdpSessionEvent.Idle)

    private val listener = GateControlUiEventListener(events, verifyCertificate, authenticate)
    private val bookmark = RdpBookmarkBuilder.build(params)
    private val sessionState: SessionState = GlobalApp.createSession(bookmark, context)
    private var connectThread: Thread? = null
    @Volatile private var released = false

    init {
        sessionState.setUIEventListener(listener)
        Timber.i("RdpSessionController: allocated instance=${sessionState.instance}")
    }

    val instance: Long get() = sessionState.instance

    fun connect() {
        check(connectThread == null) { "connect() already called" }
        events.value = RdpSessionEvent.PreConnect
        connectThread = Thread({
            try {
                sessionState.connect(context)
            } catch (t: Throwable) {
                Timber.e(t, "RdpSessionController.connect crashed")
                events.value = RdpSessionEvent.ConnectionFailure(sessionState.instance, t.message)
            }
        }, "RdpSession-${sessionState.instance}").apply { start() }
    }

    fun disconnect() {
        if (released) return
        try {
            LibFreeRDP.disconnect(sessionState.instance)
        } catch (t: Throwable) {
            Timber.w(t, "LibFreeRDP.disconnect threw")
        }
    }

    fun release() {
        if (released) return
        released = true
        try {
            LibFreeRDP.freeInstance(sessionState.instance)
        } catch (t: Throwable) {
            Timber.w(t, "LibFreeRDP.freeInstance threw")
        }
        connectThread?.interrupt()
        connectThread = null
    }

    fun sendCursor(x: Int, y: Int, flags: Int) {
        if (released) return
        LibFreeRDP.sendCursorEvent(sessionState.instance, x, y, flags)
    }

    fun sendKey(keycode: Int, down: Boolean) {
        if (released) return
        LibFreeRDP.sendKeyEvent(sessionState.instance, keycode, down)
    }

    fun sendUnicodeKey(codepoint: Int, down: Boolean) {
        if (released) return
        LibFreeRDP.sendUnicodeKeyEvent(sessionState.instance, codepoint, down)
    }
}
