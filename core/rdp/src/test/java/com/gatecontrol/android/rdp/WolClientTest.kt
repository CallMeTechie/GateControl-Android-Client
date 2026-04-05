package com.gatecontrol.android.rdp

import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.RdpRouteStatusResponse
import com.gatecontrol.android.network.RdpRouteStatus
import com.gatecontrol.android.network.WolResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WolClientTest {

    private lateinit var wolClient: WolClient
    private lateinit var apiClient: ApiClient

    @BeforeEach
    fun setUp() {
        wolClient = WolClient()
        apiClient = mockk()
    }

    // -------------------------------------------------------------------------
    // sendWol
    // -------------------------------------------------------------------------

    @Test
    fun `sendWol returns true on ok response`() = runTest {
        coEvery { apiClient.sendWol(1) } returns WolResponse(ok = true)

        val result = wolClient.sendWol(apiClient, routeId = 1)

        assertTrue(result)
    }

    @Test
    fun `sendWol returns false on non-ok response`() = runTest {
        coEvery { apiClient.sendWol(1) } returns WolResponse(ok = false, error = "Not found")

        val result = wolClient.sendWol(apiClient, routeId = 1)

        assertFalse(result)
    }

    @Test
    fun `sendWol returns false when network throws`() = runTest {
        coEvery { apiClient.sendWol(any()) } throws RuntimeException("Network error")

        val result = wolClient.sendWol(apiClient, routeId = 2)

        assertFalse(result)
    }

    // -------------------------------------------------------------------------
    // pollUntilOnline — timeout behavior
    // -------------------------------------------------------------------------

    @Test
    fun `pollUntilOnline returns false when host never comes online before timeout`() = runTest {
        // Always returns offline
        coEvery { apiClient.getRdpRouteStatus(any()) } returns RdpRouteStatusResponse(
            ok = true,
            status = RdpRouteStatus(online = false, lastCheck = null)
        )

        val result = wolClient.pollUntilOnline(
            apiClient = apiClient,
            routeId = 1,
            maxWaitMs = 100,       // Very short timeout for test speed
            pollIntervalMs = 10
        )

        assertFalse(result, "Should return false when host never comes online")
    }

    @Test
    fun `pollUntilOnline returns true when host comes online`() = runTest {
        var callCount = 0
        coEvery { apiClient.getRdpRouteStatus(any()) } answers {
            callCount++
            // Comes online on the second poll
            val isOnline = callCount >= 2
            RdpRouteStatusResponse(
                ok = true,
                status = RdpRouteStatus(online = isOnline, lastCheck = null)
            )
        }

        val result = wolClient.pollUntilOnline(
            apiClient = apiClient,
            routeId = 1,
            maxWaitMs = 5_000,
            pollIntervalMs = 10
        )

        assertTrue(result, "Should return true when host comes online")
    }

    @Test
    fun `pollUntilOnline returns false when API always throws`() = runTest {
        coEvery { apiClient.getRdpRouteStatus(any()) } throws RuntimeException("Unreachable")

        val result = wolClient.pollUntilOnline(
            apiClient = apiClient,
            routeId = 1,
            maxWaitMs = 100,
            pollIntervalMs = 10
        )

        assertFalse(result, "Should return false when API never succeeds")
    }

    @Test
    fun `pollUntilOnline invokes onProgress callback`() = runTest {
        val progressValues = mutableListOf<Long>()

        // Always offline to exhaust the timeout
        coEvery { apiClient.getRdpRouteStatus(any()) } returns RdpRouteStatusResponse(
            ok = true,
            status = RdpRouteStatus(online = false, lastCheck = null)
        )

        wolClient.pollUntilOnline(
            apiClient = apiClient,
            routeId = 1,
            maxWaitMs = 100,
            pollIntervalMs = 10,
            onProgress = { elapsed -> progressValues.add(elapsed) }
        )

        assertTrue(progressValues.isNotEmpty(), "onProgress should have been called at least once")
        // Each value should be non-negative
        progressValues.forEach { elapsed ->
            assertTrue(elapsed >= 0, "Elapsed time should be non-negative, got $elapsed")
        }
    }

    @Test
    fun `pollUntilOnline with zero maxWaitMs returns false immediately`() = runTest {
        // Even if API would succeed, a zero timeout means we exit immediately
        coEvery { apiClient.getRdpRouteStatus(any()) } returns RdpRouteStatusResponse(
            ok = true,
            status = RdpRouteStatus(online = true, lastCheck = null)
        )

        val result = wolClient.pollUntilOnline(
            apiClient = apiClient,
            routeId = 1,
            maxWaitMs = 0,
            pollIntervalMs = 10
        )

        assertFalse(result, "Zero maxWaitMs should return false immediately")
    }
}
