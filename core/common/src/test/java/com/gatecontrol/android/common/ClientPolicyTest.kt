package com.gatecontrol.android.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClientPolicyTest {

    @Test
    fun `unrestricted policy restricts nothing`() {
        val p = ClientPolicy.UNRESTRICTED
        assertFalse(p.managed)
        assertTrue(p.canDisconnect)
        assertNull(p.forcedAutoConnect)
        assertFalse(p.autoConnectLocked)
        assertFalse(p.needsSystemVpnSettings)
        SplitTunnelMode.entries.forEach { assertTrue(p.isModeAllowed(it)) }
    }

    @Test
    fun `from parses server values and falls back on invalid ones`() {
        val p = ClientPolicy.from(
            killSwitch = "required",
            autoConnect = "sometimes",
            autostart = "forbidden",
            splitTunnelModes = listOf("include", "bogus", "OFF"),
            splitTunnelLocked = null,
            lockSettings = true,
            lockServer = null,
        )
        assertEquals(ClientPolicy.KillSwitch.REQUIRED, p.killSwitch)
        assertEquals(ClientPolicy.AutoConnect.USER, p.autoConnect)
        assertEquals(ClientPolicy.Autostart.FORBIDDEN, p.autostart)
        assertEquals(setOf(SplitTunnelMode.OFF, SplitTunnelMode.INCLUDE), p.splitTunnelModes)
        assertFalse(p.splitTunnelLocked)
        assertTrue(p.lockSettings)
        assertFalse(p.lockServer)
        assertTrue(p.managed)
        assertTrue(p.needsSystemVpnSettings)

        val empty = ClientPolicy.from(null, null, null, emptyList(), null, null, null)
        assertEquals(ClientPolicy.UNRESTRICTED, empty)
    }

    @Test
    fun `auto-connect forcing - required wins over autostart forbidden`() {
        assertEquals(true, ClientPolicy(autoConnect = ClientPolicy.AutoConnect.REQUIRED, autostart = ClientPolicy.Autostart.FORBIDDEN).forcedAutoConnect)
        assertEquals(true, ClientPolicy(autostart = ClientPolicy.Autostart.REQUIRED).forcedAutoConnect)
        assertEquals(false, ClientPolicy(autostart = ClientPolicy.Autostart.FORBIDDEN).forcedAutoConnect)
        assertNull(ClientPolicy(lockSettings = true).forcedAutoConnect)
        assertTrue(ClientPolicy(lockSettings = true).autoConnectLocked)
    }

    @Test
    fun `always-on blocks disconnect and needs the system settings`() {
        val p = ClientPolicy(autoConnect = ClientPolicy.AutoConnect.ALWAYS_ON)
        assertFalse(p.canDisconnect)
        assertTrue(p.needsSystemVpnSettings)
        assertTrue(ClientPolicy(autoConnect = ClientPolicy.AutoConnect.REQUIRED).canDisconnect)
    }

    @Test
    fun `clampMode keeps allowed modes and prefers full tunnel`() {
        val offExclude = ClientPolicy(splitTunnelModes = setOf(SplitTunnelMode.OFF, SplitTunnelMode.EXCLUDE))
        assertEquals(SplitTunnelMode.EXCLUDE, offExclude.clampMode(SplitTunnelMode.EXCLUDE))
        assertEquals(SplitTunnelMode.OFF, offExclude.clampMode(SplitTunnelMode.INCLUDE))
        val includeOnly = ClientPolicy(splitTunnelModes = setOf(SplitTunnelMode.INCLUDE))
        assertEquals(SplitTunnelMode.INCLUDE, includeOnly.clampMode(SplitTunnelMode.OFF))
    }

    @Test
    fun `json round trip and broken json`() {
        val p = ClientPolicy(
            killSwitch = ClientPolicy.KillSwitch.REQUIRED,
            autoConnect = ClientPolicy.AutoConnect.ALWAYS_ON,
            autostart = ClientPolicy.Autostart.REQUIRED,
            splitTunnelModes = setOf(SplitTunnelMode.EXCLUDE),
            splitTunnelLocked = true,
            lockSettings = true,
            lockServer = true,
        )
        assertEquals(p, ClientPolicy.fromJson(p.toJson()))
        assertNull(ClientPolicy.fromJson("not json"))
        assertNull(ClientPolicy.fromJson(""))
        assertNull(ClientPolicy.fromJson(null))
    }
}
