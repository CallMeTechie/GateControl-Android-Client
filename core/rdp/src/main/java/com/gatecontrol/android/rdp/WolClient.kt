package com.gatecontrol.android.rdp

import com.gatecontrol.android.network.ApiClient
import kotlinx.coroutines.delay

class WolClient {

    /**
     * Send a Wake-on-LAN magic packet for the given route via the server API.
     * Uses /api/v1/rdp/:id/wol (not under /client/).
     *
     * @return true if the server accepted the WoL request.
     */
    suspend fun sendWol(apiClient: ApiClient, routeId: Int): Boolean {
        return try {
            val response = apiClient.sendWol(routeId)
            response.ok
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Repeatedly poll the RDP route status until it reports online or [maxWaitMs] elapses.
     *
     * @param apiClient      API client to use for polling.
     * @param routeId        The route to poll.
     * @param maxWaitMs      Maximum total wait time in milliseconds (default 60 s).
     * @param pollIntervalMs Delay between poll attempts in milliseconds (default 3 s).
     * @param onProgress     Callback invoked before each poll with the elapsed time so far.
     * @return true if the route came online within the timeout, false otherwise.
     */
    suspend fun pollUntilOnline(
        apiClient: ApiClient,
        routeId: Int,
        maxWaitMs: Long = 60_000,
        pollIntervalMs: Long = 3_000,
        onProgress: (elapsedMs: Long) -> Unit = {}
    ): Boolean {
        val startTime = System.currentTimeMillis()

        while (true) {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed >= maxWaitMs) {
                return false
            }

            onProgress(elapsed)

            delay(pollIntervalMs)

            val elapsedAfterDelay = System.currentTimeMillis() - startTime
            if (elapsedAfterDelay >= maxWaitMs) {
                return false
            }

            try {
                val statusResponse = apiClient.getRdpRouteStatus(routeId)
                if (statusResponse.ok && statusResponse.status?.online == true) {
                    return true
                }
            } catch (_: Exception) {
                // Network error — keep polling
            }
        }
    }
}
