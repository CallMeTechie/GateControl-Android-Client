package com.gatecontrol.android.familie.presence

import com.gatecontrol.android.trustednet.HomeCheck
import com.gatecontrol.android.trustednet.TrustedNetSnapshot.Kind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PresenceLogicTest {

    @Test
    fun `home only when the server confirmed its network`() {
        assertEquals(PresenceSense.HOME, PresenceLogic.sense(true, Kind.WIFI, HomeCheck.HOME))
        assertEquals(PresenceSense.AWAY, PresenceLogic.sense(true, Kind.WIFI, HomeCheck.NOT_HOME))
        assertEquals(PresenceSense.AWAY, PresenceLogic.sense(true, Kind.OTHER, HomeCheck.OFF), "mobile data: away")
        listOf(HomeCheck.CHECKING, HomeCheck.FAILED, HomeCheck.UNSUPPORTED, HomeCheck.NEXT_CONNECT, HomeCheck.OFF).forEach {
            assertEquals(PresenceSense.UNKNOWN, PresenceLogic.sense(true, Kind.WIFI, it), it.name)
        }
        assertEquals(PresenceSense.UNKNOWN, PresenceLogic.sense(true, Kind.NONE, HomeCheck.HOME), "no network: nothing known")
    }

    @Test
    fun `without the home check nothing is sensed - not even away`() {
        assertEquals(PresenceSense.UNKNOWN, PresenceLogic.sense(false, Kind.OTHER, HomeCheck.OFF))
        assertEquals(PresenceSense.UNKNOWN, PresenceLogic.sense(false, Kind.WIFI, HomeCheck.HOME))
        assertTrue(PresenceLogic.detectionAvailable(trustedEnabled = true, homeEnabled = true, canDisconnect = true))
        assertFalse(PresenceLogic.detectionAvailable(trustedEnabled = false, homeEnabled = true, canDisconnect = true))
        assertFalse(PresenceLogic.detectionAvailable(trustedEnabled = true, homeEnabled = false, canDisconnect = true))
        assertFalse(PresenceLogic.detectionAvailable(trustedEnabled = true, homeEnabled = true, canDisconnect = false))
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
    fun `wire states`() {
        assertEquals(PresenceState.HOME, PresenceState.fromWire("home"))
        assertEquals(PresenceState.NOT_SHARED, PresenceState.fromWire("not shared"))
        assertEquals(PresenceState.NOT_SHARED, PresenceState.fromWire("not-shared"))
        assertEquals(PresenceState.UNKNOWN, PresenceState.fromWire("unknown"))
        assertEquals(null, PresenceState.fromWire("maybe"))
    }
}
