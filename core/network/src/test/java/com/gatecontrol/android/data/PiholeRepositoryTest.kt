package com.gatecontrol.android.data

import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.PiholeBlocking
import com.gatecontrol.android.network.PiholeQueries
import com.gatecontrol.android.network.PiholeSummary
import com.gatecontrol.android.network.PiholeSummaryResponse
import com.gatecontrol.android.network.SimpleResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PiholeRepositoryTest {

    private val setupRepository: SetupRepository = mockk(relaxed = true)
    private val apiClientProvider: ApiClientProvider = mockk(relaxed = true)
    private val apiClient: ApiClient = mockk(relaxed = true)

    private fun repo(): PiholeRepository {
        every { setupRepository.getServerUrl() } returns "https://gate.example.com"
        every { apiClientProvider.getClient(any()) } returns apiClient
        return PiholeRepository(setupRepository, apiClientProvider)
    }

    @Test
    fun `getSummary returns mapped data on ok`() = runTest {
        coEvery { apiClient.getPiholeSummary() } returns PiholeSummaryResponse(
            ok = true,
            data = PiholeSummary(
                queries = PiholeQueries(total = 64, blocked = 3, percent = 4.7),
                gravity = 84973,
                clients = com.gatecontrol.android.network.PiholeClients(active = 3),
                blocking = PiholeBlocking(state = "enabled", timer = null),
                attribution = "collapsed",
                lastSyncAt = 123L
            )
        )
        val r = repo().getSummary()
        assertEquals(64L, r?.queries?.total)
        assertEquals(84973L, r?.gravity)
    }

    @Test
    fun `getSummary returns null when server url blank`() = runTest {
        every { setupRepository.getServerUrl() } returns ""
        every { apiClientProvider.getClient(any()) } returns apiClient
        val r = PiholeRepository(setupRepository, apiClientProvider).getSummary()
        assertNull(r)
    }

    @Test
    fun `getSummary returns null on exception (e g 403)`() = runTest {
        coEvery { apiClient.getPiholeSummary() } throws RuntimeException("HTTP 403")
        assertNull(repo().getSummary())
    }

    @Test
    fun `setBlocking posts request`() = runTest {
        coEvery { apiClient.setPiholeBlocking(any()) } returns SimpleResponse(ok = true)
        val ok = repo().setBlocking(false, 300)
        assertEquals(true, ok)
        coVerify { apiClient.setPiholeBlocking(match { it.enabled == false && it.timer == 300 }) }
    }
}
