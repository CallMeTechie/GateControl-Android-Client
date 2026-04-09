package com.gatecontrol.android.ui.rdp

import app.cash.turbine.test
import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.RdpRoute
import com.gatecontrol.android.network.RdpRouteStatus
import com.gatecontrol.android.network.RdpRoutesResponse
import com.gatecontrol.android.rdp.RdpManager
import com.gatecontrol.android.rdp.RdpProgress
import com.gatecontrol.android.rdp.RdpSession
import com.gatecontrol.android.rdp.WolClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RdpViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: RdpViewModel
    private lateinit var setupRepository: SetupRepository
    private lateinit var apiClientProvider: ApiClientProvider
    private lateinit var licenseRepository: LicenseRepository
    private lateinit var rdpManager: RdpManager
    private lateinit var wolClient: WolClient
    private lateinit var apiClient: ApiClient

    private val serverUrl = "https://gate.example.com"

    // A small set of test routes
    private val onlineRoute = RdpRoute(
        id = 1,
        name = "Dev Server",
        host = "10.0.0.10",
        port = 3389,
        externalHostname = null,
        externalPort = null,
        accessMode = "direct",
        credentialMode = "full",
        domain = null,
        resolutionMode = null,
        resolutionWidth = null,
        resolutionHeight = null,
        multiMonitor = null,
        colorDepth = null,
        redirectClipboard = null,
        redirectPrinters = null,
        redirectDrives = null,
        audioMode = null,
        networkProfile = null,
        sessionTimeout = null,
        adminSession = null,
        wolEnabled = false,
        maintenanceEnabled = false,
        status = RdpRouteStatus(online = true, lastCheck = null)
    )

    private val offlineRoute = RdpRoute(
        id = 2,
        name = "Build Server",
        host = "10.0.0.20",
        port = 3389,
        externalHostname = null,
        externalPort = null,
        accessMode = "direct",
        credentialMode = "user_only",
        domain = null,
        resolutionMode = null,
        resolutionWidth = null,
        resolutionHeight = null,
        multiMonitor = null,
        colorDepth = null,
        redirectClipboard = null,
        redirectPrinters = null,
        redirectDrives = null,
        audioMode = null,
        networkProfile = null,
        sessionTimeout = null,
        adminSession = null,
        wolEnabled = true,
        maintenanceEnabled = false,
        status = RdpRouteStatus(online = false, lastCheck = null)
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        setupRepository = mockk {
            every { getServerUrl() } returns serverUrl
        }
        apiClient = mockk()
        apiClientProvider = mockk {
            every { getClient(serverUrl) } returns apiClient
        }
        licenseRepository = mockk()
        rdpManager = mockk()
        wolClient = mockk()

        val tunnelManager = mockk<com.gatecontrol.android.tunnel.TunnelManager>(relaxed = true)
        every { tunnelManager.state } returns kotlinx.coroutines.flow.MutableStateFlow(com.gatecontrol.android.tunnel.TunnelState.Connected())

        viewModel = RdpViewModel(
            setupRepository = setupRepository,
            apiClientProvider = apiClientProvider,
            licenseRepository = licenseRepository,
            rdpManager = rdpManager,
            wolClient = wolClient,
            tunnelManager = tunnelManager
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -------------------------------------------------------------------------
    // loadRoutes
    // -------------------------------------------------------------------------

    @Test
    fun `loadRoutes populates filteredRoutes from API`() = runTest {
        coEvery { apiClient.getRdpRoutes() } returns RdpRoutesResponse(
            ok = true,
            routes = listOf(onlineRoute, offlineRoute)
        )

        viewModel.loadRoutes()
        testDispatcher.scheduler.advanceUntilIdle()

        val routes = viewModel.filteredRoutes.value
        assertEquals(2, routes.size)
        assertTrue(routes.any { it.id == 1 })
        assertTrue(routes.any { it.id == 2 })
    }

    @Test
    fun `loadRoutes sets isLoading true then false`() = runTest {
        coEvery { apiClient.getRdpRoutes() } returns RdpRoutesResponse(
            ok = true,
            routes = emptyList()
        )

        viewModel.isLoading.test {
            // initial false
            assertEquals(false, awaitItem())

            viewModel.loadRoutes()

            // becomes true while loading
            assertEquals(true, awaitItem())

            testDispatcher.scheduler.advanceUntilIdle()

            // back to false after load
            assertEquals(false, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadRoutes sets ServerError when API returns non-ok`() = runTest {
        coEvery { apiClient.getRdpRoutes() } returns RdpRoutesResponse(
            ok = false,
            routes = emptyList()
        )

        viewModel.loadRoutes()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.filteredRoutes.value.isEmpty())
        assertEquals(ErrorType.ServerError, viewModel.error.value)
    }

    @Test
    fun `loadRoutes sets Forbidden error on HTTP 403`() = runTest {
        val response = Response.error<Any>(403, "".toResponseBody())
        coEvery { apiClient.getRdpRoutes() } throws HttpException(response)

        viewModel.loadRoutes()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.filteredRoutes.value.isEmpty())
        assertEquals(ErrorType.Forbidden, viewModel.error.value)
        assertEquals(false, viewModel.isLoading.value)
    }

    @Test
    fun `loadRoutes sets Network error on IOException`() = runTest {
        coEvery { apiClient.getRdpRoutes() } throws IOException("Connection refused")

        viewModel.loadRoutes()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.filteredRoutes.value.isEmpty())
        assertEquals(ErrorType.Network, viewModel.error.value)
        assertEquals(false, viewModel.isLoading.value)
    }

    @Test
    fun `loadRoutes sets ServerError on unexpected exception`() = runTest {
        coEvery { apiClient.getRdpRoutes() } throws RuntimeException("Unexpected")

        viewModel.loadRoutes()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.filteredRoutes.value.isEmpty())
        assertEquals(ErrorType.ServerError, viewModel.error.value)
        assertEquals(false, viewModel.isLoading.value)
    }

    @Test
    fun `loadRoutes clears error on successful retry`() = runTest {
        // First call fails
        val response = Response.error<Any>(403, "".toResponseBody())
        coEvery { apiClient.getRdpRoutes() } throws HttpException(response)

        viewModel.loadRoutes()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(ErrorType.Forbidden, viewModel.error.value)

        // Retry succeeds
        coEvery { apiClient.getRdpRoutes() } returns RdpRoutesResponse(
            ok = true,
            routes = listOf(onlineRoute)
        )

        viewModel.loadRoutes()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.error.value)
        assertEquals(1, viewModel.filteredRoutes.value.size)
    }

    // -------------------------------------------------------------------------
    // setSearchQuery
    // -------------------------------------------------------------------------

    @Test
    fun `setSearchQuery filters routes by name containing query`() = runTest {
        coEvery { apiClient.getRdpRoutes() } returns RdpRoutesResponse(
            ok = true,
            routes = listOf(onlineRoute, offlineRoute)
        )
        viewModel.loadRoutes()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setSearchQuery("Dev")

        val filtered = viewModel.filteredRoutes.value
        assertEquals(1, filtered.size)
        assertEquals("Dev Server", filtered.first().name)
    }

    @Test
    fun `setSearchQuery is case-insensitive`() = runTest {
        coEvery { apiClient.getRdpRoutes() } returns RdpRoutesResponse(
            ok = true,
            routes = listOf(onlineRoute, offlineRoute)
        )
        viewModel.loadRoutes()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setSearchQuery("build")

        val filtered = viewModel.filteredRoutes.value
        assertEquals(1, filtered.size)
        assertEquals("Build Server", filtered.first().name)
    }

    @Test
    fun `setSearchQuery with empty string shows all routes`() = runTest {
        coEvery { apiClient.getRdpRoutes() } returns RdpRoutesResponse(
            ok = true,
            routes = listOf(onlineRoute, offlineRoute)
        )
        viewModel.loadRoutes()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setSearchQuery("Dev")
        viewModel.setSearchQuery("")

        assertEquals(2, viewModel.filteredRoutes.value.size)
    }

    // -------------------------------------------------------------------------
    // setStatusFilter
    // -------------------------------------------------------------------------

    @Test
    fun `setStatusFilter ONLINE shows only online routes`() = runTest {
        coEvery { apiClient.getRdpRoutes() } returns RdpRoutesResponse(
            ok = true,
            routes = listOf(onlineRoute, offlineRoute)
        )
        viewModel.loadRoutes()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setStatusFilter(StatusFilter.ONLINE)

        val filtered = viewModel.filteredRoutes.value
        assertEquals(1, filtered.size)
        assertEquals(true, filtered.first().status?.online)
    }

    @Test
    fun `setStatusFilter OFFLINE shows only offline routes`() = runTest {
        coEvery { apiClient.getRdpRoutes() } returns RdpRoutesResponse(
            ok = true,
            routes = listOf(onlineRoute, offlineRoute)
        )
        viewModel.loadRoutes()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setStatusFilter(StatusFilter.OFFLINE)

        val filtered = viewModel.filteredRoutes.value
        assertEquals(1, filtered.size)
        assertEquals(false, filtered.first().status?.online)
    }

    @Test
    fun `setStatusFilter ALL shows all routes`() = runTest {
        coEvery { apiClient.getRdpRoutes() } returns RdpRoutesResponse(
            ok = true,
            routes = listOf(onlineRoute, offlineRoute)
        )
        viewModel.loadRoutes()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setStatusFilter(StatusFilter.ONLINE)
        viewModel.setStatusFilter(StatusFilter.ALL)

        assertEquals(2, viewModel.filteredRoutes.value.size)
    }

    // -------------------------------------------------------------------------
    // selectRoute
    // -------------------------------------------------------------------------

    @Test
    fun `selectRoute sets selectedRoute and resets connectState to Idle`() = runTest {
        viewModel.selectRoute(onlineRoute)

        assertEquals(onlineRoute, viewModel.selectedRoute.value)
        assertEquals(ConnectState.Idle, viewModel.connectState.value)
    }

    @Test
    fun `dismissSheet clears selectedRoute and connectState`() = runTest {
        viewModel.selectRoute(onlineRoute)
        viewModel.dismissSheet()

        assertNull(viewModel.selectedRoute.value)
        assertEquals(ConnectState.Idle, viewModel.connectState.value)
    }

    // -------------------------------------------------------------------------
    // connect — progress steps
    // -------------------------------------------------------------------------

    @Test
    fun `connect updates connectState through progress steps and ends Connected`() = runTest {
        coEvery { apiClient.getRdpRoutes() } returns RdpRoutesResponse(
            ok = true,
            routes = listOf(onlineRoute)
        )
        viewModel.loadRoutes()
        testDispatcher.scheduler.advanceUntilIdle()

        val expectedSession = RdpSession(
            routeId = onlineRoute.id,
            sessionId = 42,
            host = onlineRoute.host,
            startTime = System.currentTimeMillis(),
            isExternal = false
        )

        coEvery {
            rdpManager.connect(
                route = onlineRoute,
                apiClient = apiClient,
                isVpnConnected = true,
                userPassword = null,
                forceMaintenanceBypass = false,
                onProgress = any()
            )
        } coAnswers {
            // Simulate calling the onProgress callback for each step
            val onProgress = arg<(RdpProgress) -> Unit>(5)
            onProgress(RdpProgress.VPN_CHECK)
            onProgress(RdpProgress.TCP_CHECK)
            onProgress(RdpProgress.CREDENTIALS)
            onProgress(RdpProgress.CLIENT_LAUNCH)
            onProgress(RdpProgress.SESSION_START)
            onProgress(RdpProgress.SERVICE_START)
            onProgress(RdpProgress.COMPLETE)
            RdpManager.ConnectResult.Success(expectedSession)
        }

        viewModel.connectState.test {
            assertEquals(ConnectState.Idle, awaitItem())

            viewModel.connect(onlineRoute.id)

            // Progress steps
            assertEquals(ConnectState.Connecting(RdpProgress.VPN_CHECK), awaitItem())
            assertEquals(ConnectState.Connecting(RdpProgress.TCP_CHECK), awaitItem())
            assertEquals(ConnectState.Connecting(RdpProgress.CREDENTIALS), awaitItem())
            assertEquals(ConnectState.Connecting(RdpProgress.CLIENT_LAUNCH), awaitItem())
            assertEquals(ConnectState.Connecting(RdpProgress.SESSION_START), awaitItem())
            assertEquals(ConnectState.Connecting(RdpProgress.SERVICE_START), awaitItem())
            assertEquals(ConnectState.Connecting(RdpProgress.COMPLETE), awaitItem())

            // Final connected state
            val finalState = awaitItem()
            assertTrue(finalState is ConnectState.Connected)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `connect transitions to NeedsPassword when credentials incomplete`() = runTest {
        coEvery { apiClient.getRdpRoutes() } returns RdpRoutesResponse(
            ok = true,
            routes = listOf(offlineRoute.copy(status = RdpRouteStatus(online = true, lastCheck = null)))
        )
        viewModel.loadRoutes()
        testDispatcher.scheduler.advanceUntilIdle()

        coEvery {
            rdpManager.connect(
                route = any(),
                apiClient = apiClient,
                isVpnConnected = true,
                userPassword = null,
                forceMaintenanceBypass = false,
                onProgress = any()
            )
        } returns RdpManager.ConnectResult.NeedsPassword(
            username = "alice",
            domain = null
        )

        viewModel.connect(offlineRoute.id)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.connectState.value
        assertTrue(state is ConnectState.NeedsPassword)
        assertEquals("alice", (state as ConnectState.NeedsPassword).username)
    }

    @Test
    fun `connect transitions to Error on failure`() = runTest {
        coEvery { apiClient.getRdpRoutes() } returns RdpRoutesResponse(
            ok = true,
            routes = listOf(onlineRoute)
        )
        viewModel.loadRoutes()
        testDispatcher.scheduler.advanceUntilIdle()

        coEvery {
            rdpManager.connect(
                route = any(),
                apiClient = apiClient,
                isVpnConnected = true,
                userPassword = null,
                forceMaintenanceBypass = false,
                onProgress = any()
            )
        } returns RdpManager.ConnectResult.Error(
            message = "TCP check failed",
            step = RdpProgress.TCP_CHECK
        )

        viewModel.connect(onlineRoute.id)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.connectState.value
        assertTrue(state is ConnectState.Error)
        assertEquals("TCP check failed", (state as ConnectState.Error).message)
    }

    @Test
    fun `connect does nothing when routeId not in loaded routes`() = runTest {
        // No routes loaded
        viewModel.connect(routeId = 999)
        testDispatcher.scheduler.advanceUntilIdle()

        // State should remain Idle, no API calls made
        assertEquals(ConnectState.Idle, viewModel.connectState.value)
        coVerify(exactly = 0) { rdpManager.connect(any(), any(), any(), any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // connect — embedded client
    // -------------------------------------------------------------------------

    @Test
    fun `connect with embedded client sets Connected with passwordCopied false`() = runTest {
        coEvery { apiClient.getRdpRoutes() } returns RdpRoutesResponse(
            ok = true,
            routes = listOf(onlineRoute)
        )
        viewModel.loadRoutes()
        testDispatcher.scheduler.advanceUntilIdle()

        val expectedSession = RdpSession(
            routeId = onlineRoute.id,
            sessionId = 42,
            host = onlineRoute.host,
            startTime = System.currentTimeMillis(),
            isExternal = false
        )

        coEvery {
            rdpManager.connect(
                route = onlineRoute,
                apiClient = apiClient,
                isVpnConnected = true,
                userPassword = null,
                forceMaintenanceBypass = false,
                onProgress = any()
            )
        } returns RdpManager.ConnectResult.Success(expectedSession, passwordCopied = false)

        viewModel.connect(onlineRoute.id)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.connectState.value
        assertTrue(state is ConnectState.Connected)
        assertEquals(false, (state as ConnectState.Connected).passwordCopied)
    }
}
