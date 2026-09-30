package com.gatecontrol.android.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VpnSubnetTest {

    @Test
    fun `host address maps to the surrounding 24`() {
        assertEquals("10.8.0.0/24", VpnSubnet.fromAddress("10.8.0.5/32"))
        assertEquals("10.13.7.0/24", VpnSubnet.fromAddress("10.13.7.42/32"))
        assertEquals("172.20.1.0/24", VpnSubnet.fromAddress("172.20.1.9"))
    }

    @Test
    fun `an explicit shorter prefix wins`() {
        assertEquals("10.20.0.0/16", VpnSubnet.fromAddress("10.20.3.4/16"))
    }

    @Test
    fun `IPv4 is picked from a dual-stack list`() {
        assertEquals("10.9.0.0/24", VpnSubnet.fromAddress("fd00::5/128, 10.9.0.5/32"))
    }

    @Test
    fun `no usable IPv4 address yields null`() {
        assertNull(VpnSubnet.fromAddress("fd00::5/128"))
        assertNull(VpnSubnet.fromAddress(""))
        assertNull(VpnSubnet.fromAddress("not-an-ip/32"))
    }

    @Test
    fun `contains checks membership`() {
        assertTrue(VpnSubnet.contains("10.8.0.0/24", "10.8.0.1"))
        assertFalse(VpnSubnet.contains("10.8.0.0/24", "10.8.1.1"))
        assertTrue(VpnSubnet.contains("10.20.0.0/16", "10.20.200.1"))
        assertFalse(VpnSubnet.contains("10.8.0.0/24", "fd00::1"))
    }
}
