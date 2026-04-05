package com.gatecontrol.android.ui.services

import app.cash.turbine.test
import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.DnsCheckResponse
import com.gatecontrol.android.network.ServicesResponse
import com.gatecontrol.android.network.VpnService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServicesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var setupRepository: SetupRepository
    private lateinit var apiClientProvider: ApiClientProvider
    private lateinit var licenseRepository: LicenseRepository
    private lateinit var apiClient: ApiClient
    private lateinit var viewModel: ServicesViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        setupRepository = mockk {
            every { getServerUrl() } returns "https://gate.example.com"
        }
        apiClient = mockk()
        apiClientProvider = mockk {
            every { getClient(any()) } returns apiClient
        }
        licenseRepository = mockk()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): ServicesViewModel =
        ServicesViewModel(setupRepository, apiClientProvider, licenseRepository)

    @Test
    fun `loadServices populates list on success`() = runTest {
        val fakeServices = listOf(
            VpnService(id = 1, name = "Intranet", domain = "intranet.local", url = "https://intranet.local", hasAuth = false),
            VpnService(id = 2, name = "Grafana", domain = "grafana.local", url = "https://grafana.local", hasAuth = true)
        )
        coEvery { apiClient.getServices() } returns ServicesResponse(ok = true, services = fakeServices)

        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.services.size)
        assertEquals("Intranet", state.services[0].name)
        assertEquals("Grafana", state.services[1].name)
        assertTrue(state.services[1].hasAuth)
    }

    @Test
    fun `loadServices sets empty list on API error`() = runTest {
        coEvery { apiClient.getServices() } throws RuntimeException("Network error")

        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.services.isEmpty())
        assertNotNull(state.error)
    }

    @Test
    fun `loadServices sets empty list when server URL is blank`() = runTest {
        every { setupRepository.getServerUrl() } returns ""

        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.services.isEmpty())
    }

    @Test
    fun `runDnsLeakTest sets Pass result on success`() = runTest {
        coEvery { apiClient.getServices() } returns ServicesResponse(ok = true, services = emptyList())
        coEvery { apiClient.dnsCheck() } returns DnsCheckResponse(
            ok = true,
            vpnSubnet = "10.8.0.0/24",
            vpnDns = "10.8.0.1",
            gatewayIp = "10.8.0.1"
        )

        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            awaitItem() // initial / after loadServices

            viewModel.runDnsLeakTest()
            testDispatcher.scheduler.advanceUntilIdle()

            val testing = awaitItem()
            assertInstanceOf(DnsTestResult.Testing::class.java, testing.dnsTestResult)

            val done = awaitItem()
            val result = done.dnsTestResult
            assertInstanceOf(DnsTestResult.Pass::class.java, result)
            assertEquals("10.8.0.1", (result as DnsTestResult.Pass).servers)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `runDnsLeakTest sets Fail result on exception`() = runTest {
        coEvery { apiClient.getServices() } returns ServicesResponse(ok = true, services = emptyList())
        coEvery { apiClient.dnsCheck() } throws RuntimeException("DNS check failed")

        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.runDnsLeakTest()
        testDispatcher.scheduler.advanceUntilIdle()

        val result = viewModel.uiState.value.dnsTestResult
        assertInstanceOf(DnsTestResult.Fail::class.java, result)
    }

    @Test
    fun `openService emits navigation event`() = runTest {
        coEvery { apiClient.getServices() } returns ServicesResponse(ok = true, services = emptyList())

        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.navigationEvent.test {
            viewModel.openService("https://intranet.local")
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("https://intranet.local", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
