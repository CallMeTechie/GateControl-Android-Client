package com.gatecontrol.android.ui.services

import app.cash.turbine.test
import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.DnsCheckResponse
import com.gatecontrol.android.network.ServiceOpenResponse
import com.gatecontrol.android.network.ServicesResponse
import com.gatecontrol.android.network.VpnService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class ServicesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var setupRepository: SetupRepository
    private lateinit var apiClientProvider: ApiClientProvider
    private lateinit var licenseRepository: LicenseRepository
    private lateinit var apiClient: ApiClient
    private lateinit var settingsRepository: SettingsRepository
    private val favorites = MutableStateFlow<Set<String>>(emptySet())
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
        settingsRepository = mockk(relaxUnitFun = true) {
            every { getServiceFavorites() } returns favorites
        }
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): ServicesViewModel =
        ServicesViewModel(setupRepository, apiClientProvider, licenseRepository, settingsRepository)

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

    private val nas = VpnService(id = 7, name = "NAS", domain = "nas.local", url = "https://nas.local", hasAuth = true)

    @Test
    fun `openService opens the one-time link from the server`() = runTest {
        coEvery { apiClient.getServices() } returns ServicesResponse(ok = true, services = listOf(nas))
        coEvery { apiClient.requestServiceLink(7) } returns
            Response.success(ServiceOpenResponse(ok = true, url = "https://nas.local/route-auth/app/t", autoLogin = true))

        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.navigationEvent.test {
            viewModel.openService(nas)
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("https://nas.local/route-auth/app/t", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(null, viewModel.uiState.value.openingId)
    }

    @Test
    fun `openService falls back to the route address`() = runTest {
        coEvery { apiClient.getServices() } returns ServicesResponse(ok = true, services = listOf(nas))
        coEvery { apiClient.requestServiceLink(7) } throws RuntimeException("offline")

        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.navigationEvent.test {
            viewModel.openService(nas)
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("https://nas.local", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `favourites come first, both groups by name`() = runTest {
        val services = listOf(
            VpnService(id = 1, name = "proxmox", domain = "pve.local", url = "https://pve.local", hasAuth = false),
            nas,
            VpnService(id = 3, name = "Grafana", domain = "grafana.local", url = "https://grafana.local", hasAuth = false),
            VpnService(id = 4, name = "Alpha", domain = "alpha.local", url = "https://alpha.local", hasAuth = false),
        )
        coEvery { apiClient.getServices() } returns ServicesResponse(ok = true, services = services)
        favorites.value = setOf("pve.local", "nas.local")

        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val sections = viewModel.uiState.value.sections
        assertEquals(listOf("NAS", "proxmox"), sections.favorites.map { it.name })
        assertEquals(listOf("Alpha", "Grafana"), sections.others.map { it.name })
    }

    @Test
    fun `toggleFavorite stores the domain`() = runTest {
        coEvery { apiClient.getServices() } returns ServicesResponse(ok = true, services = listOf(nas))

        viewModel = createViewModel()
        viewModel.toggleFavorite(nas)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { settingsRepository.toggleServiceFavorite("nas.local") }
    }
}
