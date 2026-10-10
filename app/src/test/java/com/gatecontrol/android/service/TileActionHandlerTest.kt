package com.gatecontrol.android.service

import com.gatecontrol.android.tunnel.TunnelManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TileActionHandlerTest {

    private val tunnelConnector: TunnelConnector = mockk(relaxed = true)
    private val tunnelManager: TunnelManager = mockk(relaxed = true)
    private val handler = TileActionHandler(tunnelConnector, tunnelManager, fakeClientPolicyManager())

    @Test
    fun `only known tile actions are accepted`() {
        assertEquals(TileAction.CONNECT, TileAction.parse(VpnTileService.ACTION_TILE_CONNECT))
        assertEquals(TileAction.DISCONNECT, TileAction.parse(VpnTileService.ACTION_TILE_DISCONNECT))
        assertNull(TileAction.parse(null))
        assertNull(TileAction.parse(""))
        assertNull(TileAction.parse("TILE_CONNECT"))
        assertNull(TileAction.parse("tile_connect;rm"))
    }

    @Test
    fun `connect uses the connector with the user's split-tunnel settings`() = runTest {
        coEvery { tunnelConnector.connectWithUserSettings() } returns true

        assertTrue(handler.connect())

        coVerify { tunnelConnector.connectWithUserSettings() }
    }

    @Test
    fun `connect failure is reported, not thrown`() = runTest {
        coEvery { tunnelConnector.connectWithUserSettings() } throws IllegalStateException("backend")

        assertFalse(handler.connect())
    }

    @Test
    fun `disconnect stops the tunnel and swallows errors`() = runTest {
        coEvery { tunnelManager.disconnect() } throws IllegalStateException("backend")

        handler.disconnect()

        coVerify { tunnelManager.disconnect() }
    }

    @Test
    fun `always-on client policy refuses the tile disconnect`() = kotlinx.coroutines.test.runTest {
        val locked = TileActionHandler(
            tunnelConnector,
            tunnelManager,
            fakeClientPolicyManager(com.gatecontrol.android.common.ClientPolicy(autoConnect = com.gatecontrol.android.common.ClientPolicy.AutoConnect.ALWAYS_ON)),
        )
        locked.disconnect()
        coVerify(exactly = 0) { tunnelManager.disconnect() }
    }
}
