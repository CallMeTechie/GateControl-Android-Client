package com.gatecontrol.android.rdp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RdpMonitor {

    private val activeSessions = mutableMapOf<Int, RdpSession>()
    private var heartbeatJob: Job? = null

    /**
     * Register a new active session. Returns the created [RdpSession].
     */
    fun startSession(
        routeId: Int,
        sessionId: Int,
        host: String,
        isExternal: Boolean
    ): RdpSession {
        val session = RdpSession(
            routeId = routeId,
            sessionId = sessionId,
            host = host,
            startTime = System.currentTimeMillis(),
            isExternal = isExternal,
            active = true
        )
        activeSessions[routeId] = session
        return session
    }

    /**
     * Mark a session as ended and remove it. Returns the removed [RdpSession], or null if not found.
     */
    fun endSession(routeId: Int): RdpSession? {
        val session = activeSessions.remove(routeId)
        return session?.copy(active = false)
    }

    /**
     * Return the active session for the given route, or null if none.
     */
    fun getActiveSession(routeId: Int): RdpSession? = activeSessions[routeId]

    /**
     * Return all currently active sessions.
     */
    fun getAllActiveSessions(): List<RdpSession> = activeSessions.values.toList()

    /**
     * Start a periodic heartbeat coroutine. Calls [onHeartbeat] for each active session
     * every [intervalMs] milliseconds. Cancels any previously running heartbeat.
     */
    fun startHeartbeat(
        scope: CoroutineScope,
        intervalMs: Long = 60_000,
        onHeartbeat: suspend (RdpSession) -> Unit
    ) {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (true) {
                delay(intervalMs)
                activeSessions.values.toList().forEach { session ->
                    onHeartbeat(session)
                }
            }
        }
    }

    /**
     * Cancel the heartbeat coroutine if running.
     */
    fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
}
