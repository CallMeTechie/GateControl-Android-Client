package com.gatecontrol.android.rdp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RdpSessionEndReasonTest {

    @Test
    fun `timeout before the first picture`() {
        assertEquals("connect_timeout", RdpSessionEndReason.of(hasPicture = false, failed = true, timedOut = true))
    }

    @Test
    fun `failure before the first picture`() {
        assertEquals("connect_failed", RdpSessionEndReason.of(hasPicture = false, failed = true, timedOut = false))
    }

    @Test
    fun `closed by the person before the first picture`() {
        assertEquals("user_cancel", RdpSessionEndReason.of(hasPicture = false, failed = false, timedOut = false))
    }

    @Test
    fun `normal end after the picture`() {
        assertEquals("user_disconnect", RdpSessionEndReason.of(hasPicture = true, failed = false, timedOut = false))
    }

    @Test
    fun `every reason fits the server's characters`() {
        listOf(
            RdpManager.END_USER_DISCONNECT, RdpManager.END_USER_CANCEL, RdpManager.END_CONNECT_FAILED,
            RdpManager.END_CONNECT_TIMEOUT, RdpManager.END_LAUNCH_FAILED,
        ).forEach { assertEquals(it, it.replace(Regex("[^a-z0-9_-]"), "").take(40)) }
    }
}
