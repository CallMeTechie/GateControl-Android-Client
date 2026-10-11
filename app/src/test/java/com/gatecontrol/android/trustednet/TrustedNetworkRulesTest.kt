package com.gatecontrol.android.trustednet

import com.gatecontrol.android.network.HomeNetworkStatus
import com.gatecontrol.android.trustednet.TrustedNetDecision.Reason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TrustedNetworkRulesTest {

    private val homeKey = "wifi:100"
    private val otherKey = "wifi:200"
    private val cell = TrustedNetworkRules.KEY_CELL

    private val homeViaTunnel = HomeProbe(HomeNetworkStatus.Home(viaTunnel = true), throughTunnel = true)

    /** decide() with everything allowed; override what a test is about. */
    private fun decide(
        previousKey: String? = cell,
        currentKey: String? = homeKey,
        ssidTrusted: Boolean = false,
        homeMode: Boolean = true,
        tunnel: TunnelPhase = TunnelPhase.CONNECTED,
        homeProbe: HomeProbe? = null,
        canDisconnect: Boolean = true,
        prepared: Boolean = true,
        configured: Boolean = true,
        enabled: Boolean = true,
        connectAttempted: Boolean = false,
    ) = TrustedNetworkRules.decide(
        previousKey, currentKey, ssidTrusted, homeMode, tunnel, homeProbe,
        canDisconnect, prepared, configured, enabled, connectAttempted,
    )

    @Nested
    inner class Transitions {

        @Test
        fun `same network key never acts`() {
            for (tunnel in TunnelPhase.entries) {
                for (ssid in listOf(true, false)) {
                    for (probe in listOf(null, homeViaTunnel)) {
                        val d = decide(previousKey = homeKey, currentKey = homeKey, ssidTrusted = ssid, tunnel = tunnel, homeProbe = probe)
                        assertEquals(TrustedNetAction.NONE, d.action, "tunnel=$tunnel ssid=$ssid probe=$probe")
                        assertEquals(Reason.SAME_NETWORK, d.reason)
                    }
                }
            }
        }

        @Test
        fun `app restart on the same network does not act`() {
            // Persisted key equals the current one after a restart.
            val d = decide(previousKey = cell, currentKey = cell, homeMode = false, tunnel = TunnelPhase.DOWN)
            assertEquals(TrustedNetAction.NONE, d.action)
        }

        @Test
        fun `no network neither acts nor remembers`() {
            val d = decide(currentKey = null, tunnel = TunnelPhase.DOWN)
            assertEquals(TrustedNetAction.NONE, d.action)
            assertFalse(d.commitKey)
        }

        @Test
        fun `first run without a remembered key acts`() {
            assertEquals(TrustedNetAction.DISCONNECT, decide(previousKey = null, ssidTrusted = true).action)
        }

        @Test
        fun `feature off only remembers the network`() {
            val d = decide(ssidTrusted = true, enabled = false)
            assertEquals(TrustedNetAction.NONE, d.action)
            assertTrue(d.commitKey)
            assertEquals(Reason.FEATURE_OFF, d.reason)
        }
    }

    @Nested
    inner class SsidMode {

        @Test
        fun `trusted ssid disconnects when connected`() {
            val d = decide(ssidTrusted = true, tunnel = TunnelPhase.CONNECTED)
            assertEquals(TrustedNetAction.DISCONNECT, d.action)
            assertEquals(Reason.TRUSTED_SSID, d.reason)
            assertTrue(d.commitKey)
        }

        @Test
        fun `trusted ssid does nothing when already disconnected`() {
            val d = decide(ssidTrusted = true, tunnel = TunnelPhase.DOWN)
            assertEquals(TrustedNetAction.NONE, d.action)
            assertEquals(Reason.ALREADY_DISCONNECTED, d.reason)
            assertTrue(d.commitKey)
        }

        @Test
        fun `trusted ssid needs no home check`() {
            assertEquals(TrustedNetAction.DISCONNECT, decide(ssidTrusted = true, homeMode = true, homeProbe = null).action)
        }
    }

    @Nested
    inner class Untrusted {

        @Test
        fun `untrusted network connects when disconnected, prepared and configured`() {
            val d = decide(previousKey = homeKey, currentKey = cell, homeMode = false, tunnel = TunnelPhase.DOWN)
            assertEquals(TrustedNetAction.CONNECT, d.action)
            assertTrue(d.commitKey)
        }

        @Test
        fun `untrusted network does nothing when already connected`() {
            assertEquals(TrustedNetAction.NONE, decide(currentKey = otherKey, homeMode = false).action)
            assertEquals(TrustedNetAction.NONE, decide(currentKey = otherKey, homeMode = false, tunnel = TunnelPhase.CONNECTING).action)
        }

        @Test
        fun `untrusted network does not connect without VPN consent`() {
            val d = decide(currentKey = otherKey, homeMode = false, tunnel = TunnelPhase.DOWN, prepared = false)
            assertEquals(TrustedNetAction.NONE, d.action)
            assertEquals(Reason.NO_VPN_CONSENT, d.reason)
        }

        @Test
        fun `untrusted network does not connect without a configured setup`() {
            val d = decide(currentKey = otherKey, homeMode = false, tunnel = TunnelPhase.DOWN, configured = false)
            assertEquals(TrustedNetAction.NONE, d.action)
            assertEquals(Reason.NOT_CONFIGURED, d.reason)
        }
    }

    @Nested
    inner class HomeMode {

        @Test
        fun `vpn down connects first without remembering the network`() {
            val d = decide(tunnel = TunnelPhase.DOWN)
            assertEquals(TrustedNetAction.CONNECT, d.action)
            assertEquals(Reason.CONNECT_FOR_HOME_CHECK, d.reason)
            assertFalse(d.commitKey)
        }

        @Test
        fun `connect then probe then disconnect, and no loop on the same key`() {
            // 1. New WLAN, VPN down: connect, key stays open.
            val first = decide(previousKey = cell, tunnel = TunnelPhase.DOWN)
            assertEquals(TrustedNetAction.CONNECT, first.action)
            assertFalse(first.commitKey)
            // 2. Tunnel on its way: wait.
            val waiting = decide(previousKey = cell, tunnel = TunnelPhase.CONNECTING, connectAttempted = true)
            assertEquals(TrustedNetAction.NONE, waiting.action)
            assertFalse(waiting.commitKey)
            // 3. Tunnel up: ask the server.
            val probe = decide(previousKey = cell, tunnel = TunnelPhase.CONNECTED, connectAttempted = true)
            assertEquals(TrustedNetAction.PROBE, probe.action)
            assertFalse(probe.commitKey)
            // 4. Home through the tunnel: disconnect and remember the network.
            val home = decide(previousKey = cell, tunnel = TunnelPhase.CONNECTED, homeProbe = homeViaTunnel, connectAttempted = true)
            assertEquals(TrustedNetAction.DISCONNECT, home.action)
            assertEquals(Reason.TRUSTED_HOME, home.reason)
            assertTrue(home.commitKey)
            // 5. Tunnel down on the same, now remembered network: nothing, no reconnect.
            val after = decide(previousKey = homeKey, tunnel = TunnelPhase.DOWN, homeProbe = homeViaTunnel)
            assertEquals(TrustedNetAction.NONE, after.action)
            assertEquals(Reason.SAME_NETWORK, after.reason)
        }

        @Test
        fun `vpn up probes right away`() {
            assertEquals(TrustedNetAction.PROBE, decide(tunnel = TunnelPhase.CONNECTED, homeProbe = null).action)
        }

        @Test
        fun `not home, older server and failures stay connected and remember the network`() {
            for (status in listOf(
                HomeNetworkStatus.NotHome(viaTunnel = true),
                HomeNetworkStatus.Unsupported,
                HomeNetworkStatus.Unknown,
            )) {
                val d = decide(homeProbe = HomeProbe(status, throughTunnel = true))
                assertEquals(TrustedNetAction.NONE, d.action, "$status")
                assertEquals(Reason.NOT_HOME, d.reason)
                assertTrue(d.commitKey)
            }
        }

        @Test
        fun `a direct answer is ignored even if it says same_lan`() {
            val direct = HomeProbe(HomeNetworkStatus.Home(viaTunnel = false), throughTunnel = true)
            assertFalse(direct.trusted)
            val d = decide(homeProbe = direct)
            assertEquals(TrustedNetAction.NONE, d.action)
            assertEquals(Reason.NOT_HOME, d.reason)
        }

        @Test
        fun `a probe made while the vpn was down never counts`() {
            val whileDown = HomeProbe(HomeNetworkStatus.Home(viaTunnel = true), throughTunnel = false)
            assertFalse(whileDown.trusted)
            // Tunnel up now: asked again through it, never disconnected on the old answer.
            assertEquals(TrustedNetAction.PROBE, decide(homeProbe = whileDown).action)
            // Tunnel down: connect first.
            assertEquals(TrustedNetAction.CONNECT, decide(tunnel = TunnelPhase.DOWN, homeProbe = whileDown).action)
        }

        @Test
        fun `failed connect for the home check gives up instead of looping`() {
            val d = decide(tunnel = TunnelPhase.DOWN, connectAttempted = true)
            assertEquals(TrustedNetAction.NONE, d.action)
            assertEquals(Reason.TUNNEL_NOT_UP, d.reason)
            assertTrue(d.commitKey)
        }

        @Test
        fun `home check can't run without consent or setup`() {
            val noConsent = decide(tunnel = TunnelPhase.DOWN, prepared = false)
            assertEquals(TrustedNetAction.NONE, noConsent.action)
            assertEquals(Reason.NO_VPN_CONSENT, noConsent.reason)
            assertTrue(noConsent.commitKey)
            val notConfigured = decide(tunnel = TunnelPhase.DOWN, configured = false)
            assertEquals(TrustedNetAction.NONE, notConfigured.action)
            assertEquals(Reason.NOT_CONFIGURED, notConfigured.reason)
        }
    }

    @Nested
    inner class AlwaysOn {

        @Test
        fun `always-on policy never disconnects`() {
            for (d in listOf(
                decide(ssidTrusted = true, canDisconnect = false),
                decide(homeProbe = homeViaTunnel, canDisconnect = false),
            )) {
                assertEquals(TrustedNetAction.NONE, d.action)
                assertEquals(Reason.ALWAYS_ON_POLICY, d.reason)
                assertTrue(d.commitKey)
            }
        }

        @Test
        fun `always-on policy makes the feature do nothing at all`() {
            assertEquals(TrustedNetAction.NONE, decide(homeMode = false, tunnel = TunnelPhase.DOWN, canDisconnect = false).action)
            assertEquals(TrustedNetAction.NONE, decide(tunnel = TunnelPhase.DOWN, canDisconnect = false).action)
        }
    }

    @Nested
    inner class Ssids {

        @Test
        fun `normalisation strips quotes and whitespace`() {
            assertEquals("Heimnetz", TrustedNetworkRules.normalizeSsid("\"Heimnetz\""))
            assertEquals("Heim netz", TrustedNetworkRules.normalizeSsid("  Heim netz "))
            assertEquals("Büro 5G", TrustedNetworkRules.normalizeSsid("\"Büro 5G\""))
            // A lone quote is part of the name.
            assertEquals("\"", TrustedNetworkRules.normalizeSsid("\""))
        }

        @Test
        fun `unknown ssid and empty mean none`() {
            assertNull(TrustedNetworkRules.normalizeSsid("<unknown ssid>"))
            assertNull(TrustedNetworkRules.normalizeSsid("\"<unknown ssid>\""))
            assertNull(TrustedNetworkRules.normalizeSsid(""))
            assertNull(TrustedNetworkRules.normalizeSsid("\"\""))
            assertNull(TrustedNetworkRules.normalizeSsid(null))
        }

        @Test
        fun `matching is exact after normalisation`() {
            val list = setOf("Heimnetz", "Büro")
            assertTrue(TrustedNetworkRules.ssidMatches("\"Heimnetz\"", list))
            assertTrue(TrustedNetworkRules.ssidMatches("Büro", list))
            assertFalse(TrustedNetworkRules.ssidMatches("heimnetz", list))
            assertFalse(TrustedNetworkRules.ssidMatches("Heimnetz-Gast", list))
            assertFalse(TrustedNetworkRules.ssidMatches("<unknown ssid>", list))
            assertFalse(TrustedNetworkRules.ssidMatches(null, list))
            assertFalse(TrustedNetworkRules.ssidMatches("Heimnetz", emptySet()))
        }

        @Test
        fun `user input is limited to 32 bytes`() {
            assertEquals("Heimnetz", TrustedNetworkRules.validSsidInput(" \"Heimnetz\" "))
            assertEquals("a".repeat(32), TrustedNetworkRules.validSsidInput("a".repeat(32)))
            assertNull(TrustedNetworkRules.validSsidInput("a".repeat(33)))
            // 17 × "ü" = 34 bytes in UTF-8
            assertNull(TrustedNetworkRules.validSsidInput("ü".repeat(17)))
            assertNull(TrustedNetworkRules.validSsidInput("   "))
        }

        @Test
        fun `network keys`() {
            assertEquals("wifi:42", TrustedNetworkRules.networkKey(42L, hasOtherNetwork = true))
            assertEquals("cell", TrustedNetworkRules.networkKey(null, hasOtherNetwork = true))
            assertNull(TrustedNetworkRules.networkKey(null, hasOtherNetwork = false))
        }
    }
}
