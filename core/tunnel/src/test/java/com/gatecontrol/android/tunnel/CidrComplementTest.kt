package com.gatecontrol.android.tunnel

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CidrComplementTest {

    @Test
    fun `empty exclude list returns full range`() {
        val result = CidrComplement.computeAllowedIps(emptyList())
        assertEquals(listOf("0.0.0.0/0"), result)
    }

    @Test
    fun `exclude full range returns empty`() {
        val result = CidrComplement.computeAllowedIps(listOf("0.0.0.0/0"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `exclude single /8 network`() {
        val result = CidrComplement.computeAllowedIps(listOf("10.0.0.0/8"))
        // Should not contain anything in 10.x.x.x
        assertTrue(result.isNotEmpty())
        assertFalse(result.contains("10.0.0.0/8"))
        // First entry should be 0.0.0.0/5 (covers 0-7.x)
        assertTrue(result.contains("0.0.0.0/5"))
    }

    @Test
    fun `exclude 192_168 returns complement`() {
        val result = CidrComplement.computeAllowedIps(listOf("192.168.0.0/16"))
        assertTrue(result.isNotEmpty())
        assertFalse(result.any { it.startsWith("192.168.") })
    }

    @Test
    fun `overlapping CIDRs are merged`() {
        // 10.0.0.0/8 already contains 10.1.0.0/16
        val result1 = CidrComplement.computeAllowedIps(listOf("10.0.0.0/8", "10.1.0.0/16"))
        val result2 = CidrComplement.computeAllowedIps(listOf("10.0.0.0/8"))
        assertEquals(result1, result2)
    }

    @Test
    fun `invalid CIDRs are skipped`() {
        val result = CidrComplement.computeAllowedIps(listOf("not-a-cidr", "10.0.0.0/8"))
        val resultClean = CidrComplement.computeAllowedIps(listOf("10.0.0.0/8"))
        assertEquals(result, resultClean)
    }

    @Test
    fun `IPv6 CIDRs are passed through`() {
        val result = CidrComplement.computeAllowedIps(listOf("10.0.0.0/8", "fe80::/10"))
        assertTrue(result.contains("fe80::/10"))
    }

    @Test
    fun `parseCidr handles valid input`() {
        val range = CidrComplement.parseCidr("192.168.0.0/16")
        assertNotNull(range)
        assertEquals(CidrComplement.ipToLong("192.168.0.0"), range!!.first)
        assertEquals(CidrComplement.ipToLong("192.168.255.255"), range.second)
    }

    @Test
    fun `parseCidr rejects invalid input`() {
        assertNull(CidrComplement.parseCidr("not-a-cidr"))
        assertNull(CidrComplement.parseCidr("10.0.0.0/33"))
        assertNull(CidrComplement.parseCidr("999.0.0.0/8"))
    }

    @Test
    fun `single host /32 exclude`() {
        val result = CidrComplement.computeAllowedIps(listOf("10.0.0.1/32"))
        assertTrue(result.isNotEmpty())
        assertFalse(result.contains("10.0.0.1/32"))
    }
}
