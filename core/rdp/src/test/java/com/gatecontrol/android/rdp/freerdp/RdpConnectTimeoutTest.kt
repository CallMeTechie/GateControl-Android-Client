package com.gatecontrol.android.rdp.freerdp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RdpConnectTimeoutTest {

    private val cert = RdpSessionEvent.VerifyCertificate(
        host = "10.0.0.5", port = 3389, commonName = "pc", subject = "CN=pc",
        issuer = "CN=pc", fingerprint = "00:11", flags = 0,
    )

    @Test
    fun `about thirty seconds`() {
        assertEquals(30_000L, RdpConnectTimeout.MILLIS)
    }

    @Test
    fun `runs while connecting`() {
        assertTrue(RdpConnectTimeout.shouldRun(hasPicture = false, failed = false, event = RdpSessionEvent.Idle))
        assertTrue(RdpConnectTimeout.shouldRun(hasPicture = false, failed = false, event = RdpSessionEvent.PreConnect))
    }

    @Test
    fun `stops with the first picture or a failure`() {
        assertFalse(RdpConnectTimeout.shouldRun(hasPicture = true, failed = false, event = RdpSessionEvent.Idle))
        assertFalse(RdpConnectTimeout.shouldRun(hasPicture = false, failed = true, event = RdpSessionEvent.Idle))
    }

    @Test
    fun `pauses while a dialog waits for the person`() {
        assertFalse(RdpConnectTimeout.shouldRun(hasPicture = false, failed = false, event = cert))
        assertFalse(
            RdpConnectTimeout.shouldRun(
                hasPicture = false, failed = false,
                event = RdpSessionEvent.AuthenticationRequired(gateway = false),
            )
        )
        assertTrue(RdpConnectTimeout.waitsForPerson(cert))
        assertFalse(RdpConnectTimeout.waitsForPerson(RdpSessionEvent.Disconnected(1L)))
    }
}
