package com.gatecontrol.android.ui.settings

import com.gatecontrol.android.common.TvPairLink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** "TV koppeln" on the phone: the server's answers and the server check of the TV's QR code. */
class TvPairApprovalTest {

    @Test
    fun `error codes of pair approve`() {
        assertEquals(TvPairError.INVALID_CODE, TvPairApproval.errorOf(400, "invalid_code"))
        assertEquals(TvPairError.LOCKED, TvPairApproval.errorOf(429, "locked"))
        assertEquals(TvPairError.RATE_LIMITED, TvPairApproval.errorOf(429, "rate_limited"))
        assertEquals(TvPairError.RATE_LIMITED, TvPairApproval.errorOf(429, null))
        assertEquals(TvPairError.NOT_ALLOWED, TvPairApproval.errorOf(403, "enroll_not_allowed"))
        assertEquals(TvPairError.NOT_ALLOWED, TvPairApproval.errorOf(403, "token_required"))
        assertEquals(TvPairError.USER_DISABLED, TvPairApproval.errorOf(403, "user_disabled"))
        assertEquals(TvPairError.NOT_ALLOWED, TvPairApproval.errorOf(401, null))
        // a server before 1.318.0 has no such route
        assertEquals(TvPairError.NOT_SUPPORTED, TvPairApproval.errorOf(404, null))
        assertEquals(TvPairError.NETWORK, TvPairApproval.errorOf(502, null))
    }

    @Test
    fun `the TV's server must be the phone's`() {
        val link = TvPairLink("https://gate.example.com", "AbCdEfGhIjKlMnOpQrSt_-")
        assertTrue(TvPairApproval.sameServer(link, "https://gate.example.com"))
        assertTrue(TvPairApproval.sameServer(link, "https://GATE.example.com/"))
        assertTrue(TvPairApproval.sameServer(link, "https://gate.example.com:443"))
        assertFalse(TvPairApproval.sameServer(link, "https://other.example.com"))
        assertFalse(TvPairApproval.sameServer(link, "https://gate.example.com:8443"))
        assertFalse(TvPairApproval.sameServer(link, ""))
    }
}
