package com.gatecontrol.android.familie.presence

import com.gatecontrol.android.network.HomeNetworkStatus
import com.gatecontrol.android.network.HomeSite
import com.gatecontrol.android.trustednet.TrustedNetSnapshot.Kind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PresenceLogicTest {

    private fun obs(sense: PresenceSense, check: PresenceCheck) = PresenceObservation(sense, check)

    @Test
    fun `home whenever the server says so - through the tunnel or directly at a gateway`() {
        assertEquals(obs(PresenceSense.HOME, PresenceCheck.OK), PresenceLogic.interpret(HomeNetworkStatus.Home(viaTunnel = true)))
        assertEquals(
            obs(PresenceSense.HOME, PresenceCheck.OK),
            PresenceLogic.interpret(HomeNetworkStatus.Home(viaTunnel = false, site = HomeSite(HomeSite.Type.GATEWAY, "7", "DS218+"))),
        )
    }

    @Test
    fun `away when the no is reliable`() {
        assertEquals(
            obs(PresenceSense.AWAY, PresenceCheck.OK),
            PresenceLogic.interpret(HomeNetworkStatus.NotHome(viaTunnel = false, siteAware = true)),
            "core 1.167+, VPN off",
        )
        assertEquals(
            obs(PresenceSense.AWAY, PresenceCheck.OK),
            PresenceLogic.interpret(HomeNetworkStatus.NotHome(viaTunnel = true, siteAware = true)),
        )
        assertEquals(
            obs(PresenceSense.AWAY, PresenceCheck.OLD_SERVER),
            PresenceLogic.interpret(HomeNetworkStatus.NotHome(viaTunnel = true, siteAware = false)),
            "older server through the tunnel: its own rule still holds",
        )
    }

    @Test
    fun `a direct no from an older server, errors and missing support decide nothing`() {
        assertEquals(
            obs(PresenceSense.UNKNOWN, PresenceCheck.OLD_SERVER),
            PresenceLogic.interpret(HomeNetworkStatus.NotHome(viaTunnel = false, siteAware = false)),
        )
        assertEquals(obs(PresenceSense.UNKNOWN, PresenceCheck.UNSUPPORTED), PresenceLogic.interpret(HomeNetworkStatus.Unsupported))
        assertEquals(obs(PresenceSense.UNKNOWN, PresenceCheck.FAILED), PresenceLogic.interpret(HomeNetworkStatus.Unknown))
    }

    @Test
    fun `no question needed on mobile data or without a network`() {
        assertEquals(PresenceSense.AWAY, PresenceLogic.senseWithoutAsking(Kind.OTHER, hasLanAddress = false), "mobile data: away")
        assertEquals(PresenceSense.UNKNOWN, PresenceLogic.senseWithoutAsking(Kind.NONE, hasLanAddress = false))
        assertNull(PresenceLogic.senseWithoutAsking(Kind.WIFI, hasLanAddress = true))
        assertNull(PresenceLogic.senseWithoutAsking(Kind.WIFI, hasLanAddress = false), "Wi-Fi without IPv4: the server-LAN rule may still apply")
        assertNull(PresenceLogic.senseWithoutAsking(Kind.OTHER, hasLanAddress = true), "Ethernet: ask")
    }

    @Test
    fun `can't tell keeps the last state`() {
        assertEquals(PresenceSense.HOME, PresenceLogic.next(PresenceSense.HOME, PresenceSense.UNKNOWN))
        assertEquals(PresenceSense.AWAY, PresenceLogic.next(PresenceSense.AWAY, PresenceSense.UNKNOWN))
        assertEquals(PresenceSense.AWAY, PresenceLogic.next(PresenceSense.HOME, PresenceSense.AWAY))
        assertEquals(PresenceSense.HOME, PresenceLogic.next(PresenceSense.UNKNOWN, PresenceSense.HOME))
        assertEquals(PresenceSense.UNKNOWN, PresenceLogic.next(PresenceSense.UNKNOWN, PresenceSense.UNKNOWN))
    }

    @Test
    fun `reports only with consent, module on, a known state and a change`() {
        assertTrue(PresenceLogic.shouldReport(PresenceSense.HOME, consent = true, moduleOn = true, lastReported = null))
        assertTrue(PresenceLogic.shouldReport(PresenceSense.AWAY, consent = true, moduleOn = true, lastReported = true))
        assertFalse(PresenceLogic.shouldReport(PresenceSense.HOME, consent = true, moduleOn = true, lastReported = true), "no change")
        assertFalse(PresenceLogic.shouldReport(PresenceSense.UNKNOWN, consent = true, moduleOn = true, lastReported = null))
        assertFalse(PresenceLogic.shouldReport(PresenceSense.HOME, consent = false, moduleOn = true, lastReported = null), "no consent")
        assertFalse(PresenceLogic.shouldReport(PresenceSense.HOME, consent = true, moduleOn = false, lastReported = null), "module off")
        assertEquals(120_000L, PresenceLogic.DEBOUNCE_MS)
    }

    @Test
    fun `first report right after consent - the first clear answer, no debounce`() {
        // Sharing just went on, nothing reported yet: one clear check is enough.
        assertTrue(PresenceLogic.reportFirstNow(PresenceSense.HOME, firstPending = true, consent = true, moduleOn = true, lastReported = null))
        assertTrue(PresenceLogic.reportFirstNow(PresenceSense.AWAY, firstPending = true, consent = true, moduleOn = true, lastReported = null))
        // "Can't tell" waits for a clear answer.
        assertFalse(PresenceLogic.reportFirstNow(PresenceSense.UNKNOWN, firstPending = true, consent = true, moduleOn = true, lastReported = null))
        // Later changes keep the two minutes.
        assertFalse(PresenceLogic.reportFirstNow(PresenceSense.AWAY, firstPending = false, consent = true, moduleOn = true, lastReported = null))
        assertFalse(PresenceLogic.reportFirstNow(PresenceSense.AWAY, firstPending = true, consent = true, moduleOn = true, lastReported = true), "already reported")
        assertFalse(PresenceLogic.reportFirstNow(PresenceSense.HOME, firstPending = true, consent = false, moduleOn = true, lastReported = null), "no consent")
        assertFalse(PresenceLogic.reportFirstNow(PresenceSense.HOME, firstPending = true, consent = true, moduleOn = false, lastReported = null), "module off")
    }

    @Test
    fun `wire states`() {
        assertEquals(PresenceState.HOME, PresenceState.fromWire("home"))
        assertEquals(PresenceState.NOT_SHARED, PresenceState.fromWire("not shared"))
        assertEquals(PresenceState.NOT_SHARED, PresenceState.fromWire("not-shared"))
        assertEquals(PresenceState.UNKNOWN, PresenceState.fromWire("unknown"))
        assertEquals(null, PresenceState.fromWire("maybe"))
    }
}
