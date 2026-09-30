package com.gatecontrol.android.tunnel

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CidrComplementTest {

    @Test
    fun `empty exclude list returns full range`() {
        val result = CidrComplement.computeAllowedIps(emptyList())
        assertEquals(listOf("0.0.0.0/0", "::/0"), result)
    }

    @Test
    fun `exclude full IPv4 range leaves only IPv6`() {
        val result = CidrComplement.computeAllowedIps(listOf("0.0.0.0/0"))
        assertEquals(listOf("::/0"), result)
    }

    @Test
    fun `exclude single slash-8 network`() {
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
    fun `IPv6 exclusions are removed from the tunnel, not added`() {
        val result = CidrComplement.computeAllowedIps(listOf("10.0.0.0/8", "fe80::/10"))
        assertFalse(result.contains("fe80::/10"))
        assertFalse(result.contains("::/0"))
        // the address space around fe80::/10 stays in the tunnel
        assertTrue(result.contains("::/1"))
        assertTrue(result.contains("fec0::/10"))
        assertTrue(result.contains("ff00::/8"))
    }

    @Test
    fun `without IPv6 exclusions all IPv6 goes through the tunnel`() {
        val result = CidrComplement.computeAllowedIps(listOf("192.168.0.0/16"))
        assertTrue(result.contains("::/0"))
    }

    @Test
    fun `IPv6 complement covers everything except the excluded range`() {
        val ex = CidrComplement.parseCidr6("2001:db8::/32")!!
        val allowed = CidrComplement.complement6(listOf(ex)).map { CidrComplement.parseCidr6(it)!! }
        val total = allowed.fold(java.math.BigInteger.ZERO) { acc, (s, e) -> acc + (e - s + java.math.BigInteger.ONE) }
        val all = java.math.BigInteger.ONE.shiftLeft(128)
        val excluded = ex.second - ex.first + java.math.BigInteger.ONE
        assertEquals(all - excluded, total)
        allowed.forEach { (s, e) -> assertTrue(e < ex.first || s > ex.second) }
    }

    @Test
    fun `IPv6 output uses the compressed form`() {
        assertEquals("::", CidrComplement.bigToIp6(java.math.BigInteger.ZERO))
        assertEquals("fe80::", CidrComplement.bigToIp6(CidrComplement.parseCidr6("fe80::/10")!!.first))
        assertEquals("2001:db8::1", CidrComplement.bigToIp6(CidrComplement.parseCidr6("2001:db8::1/128")!!.first))
        assertEquals("1:0:1::", CidrComplement.bigToIp6(CidrComplement.parseCidr6("1:0:1::/48")!!.first))
    }

    @Test
    fun `invalid IPv6 entries are skipped`() {
        assertEquals(null, CidrComplement.parseCidr6("fe80::/200"))
        assertEquals(null, CidrComplement.parseCidr6("example.com:80/64"))
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
    fun `single host slash-32 exclude`() {
        val result = CidrComplement.computeAllowedIps(listOf("10.0.0.1/32"))
        assertTrue(result.isNotEmpty())
        assertFalse(result.contains("10.0.0.1/32"))
    }
}
