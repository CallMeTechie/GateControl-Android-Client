package com.gatecontrol.android.service

import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.ConfigCheckResponse
import com.gatecontrol.android.tunnel.SplitTunnelConfig
import com.gatecontrol.android.tunnel.TunnelManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Covers the config reload that runs on every connect. Motivating incident:
 * after the server moved hosts the stored config still carried the old
 * Endpoint, so the tunnel came up but routed nowhere.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TunnelConnectorTest {

    private companion object {
        const val SERVER_URL = "https://gate.example.com"
        const val PEER_ID = 7
        const val STORED_HASH = "hash-old"

        fun config(endpoint: String) = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = CyeI87ssPVm18g0yRG9AZV0vdIe9qtkKvFKsOlTCTHI=")
            appendLine("Address = 10.8.0.2/32")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = 86R0I45ZRx/P7WQdj+GkW+q0+MU0cS4Zccy+CVTTvY4=")
            appendLine("Endpoint = $endpoint")
            appendLine("AllowedIPs = 0.0.0.0/0")
        }

        val OLD_CONFIG = config("54.36.233.20:51820")
        val NEW_CONFIG = config("vpn.example.com:51820")
        const val BROKEN_CONFIG = "[Interface]\nPrivateKey = not-a-key\n"
    }

    private lateinit var setupRepository: SetupRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var apiClientProvider: ApiClientProvider
    private lateinit var apiClient: ApiClient
    private lateinit var tunnelManager: TunnelManager
    private lateinit var connector: TunnelConnector

    @BeforeEach
    fun setUp() {
        setupRepository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        apiClientProvider = mockk(relaxed = true)
        apiClient = mockk(relaxed = true)
        tunnelManager = mockk(relaxed = true)

        every { setupRepository.getWireGuardConfig() } returns OLD_CONFIG
        every { setupRepository.getServerUrl() } returns SERVER_URL
        every { setupRepository.getPeerId() } returns PEER_ID
        every { setupRepository.getConfigHash() } returns STORED_HASH
        every { apiClientProvider.getClient(any()) } returns apiClient
        // No admin split-tunnel preset; the connector falls back to user settings.
        coEvery { apiClient.getSplitTunnelPreset() } throws IllegalStateException("no preset")
        every { settingsRepository.getSplitTunnelMode() } returns flowOf("off")
        coEvery { tunnelManager.connect(any(), any<SplitTunnelConfig>()) } returns Unit

        connector = TunnelConnector(
            setupRepository,
            settingsRepository,
            apiClientProvider,
            tunnelManager,
        )
    }

    @Test
    fun `unchanged config connects the stored one`() = runTest {
        coEvery { apiClient.checkConfigUpdate(PEER_ID, STORED_HASH) } returns
            ConfigCheckResponse(ok = true, updated = false, config = null, hash = STORED_HASH)

        assertTrue(connector.connectWithUserSettings())

        coVerify { tunnelManager.connect(OLD_CONFIG, any<SplitTunnelConfig>()) }
        verify(exactly = 0) { setupRepository.saveWireGuardConfig(any()) }
    }

    @Test
    fun `changed config is validated, stored and connected`() = runTest {
        coEvery { apiClient.checkConfigUpdate(PEER_ID, STORED_HASH) } returns
            ConfigCheckResponse(ok = true, updated = true, config = NEW_CONFIG, hash = "hash-new")

        assertTrue(connector.connectWithUserSettings())

        coVerify { tunnelManager.connect(NEW_CONFIG, any<SplitTunnelConfig>()) }
        verify { setupRepository.saveWireGuardConfig(NEW_CONFIG) }
        verify { setupRepository.saveConfigHash("hash-new") }
    }

    @Test
    fun `invalid server config is discarded and the stored one is used`() = runTest {
        coEvery { apiClient.checkConfigUpdate(PEER_ID, STORED_HASH) } returns
            ConfigCheckResponse(ok = true, updated = true, config = BROKEN_CONFIG, hash = "hash-bad")

        assertTrue(connector.connectWithUserSettings())

        coVerify { tunnelManager.connect(OLD_CONFIG, any<SplitTunnelConfig>()) }
        verify(exactly = 0) { setupRepository.saveWireGuardConfig(any()) }
        verify(exactly = 0) { setupRepository.saveConfigHash(any()) }
    }

    @Test
    fun `unreachable server does not block the connect`() = runTest {
        coEvery { apiClient.checkConfigUpdate(any(), any()) } throws
            java.io.IOException("connection refused")

        assertTrue(connector.connectWithUserSettings())

        coVerify { tunnelManager.connect(OLD_CONFIG, any<SplitTunnelConfig>()) }
    }

    @Test
    fun `unregistered client skips the check entirely`() = runTest {
        every { setupRepository.getPeerId() } returns -1

        assertTrue(connector.connectWithUserSettings())

        coVerify(exactly = 0) { apiClient.checkConfigUpdate(any(), any()) }
        coVerify { tunnelManager.connect(OLD_CONFIG, any<SplitTunnelConfig>()) }
    }
}
