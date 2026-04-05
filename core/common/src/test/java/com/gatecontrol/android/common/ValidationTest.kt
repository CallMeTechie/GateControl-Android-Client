package com.gatecontrol.android.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ValidationTest {

    // ── validateIp ────────────────────────────────────────────────────────────

    @Test
    fun `validateIp accepts valid IPv4 addresses`() {
        assertTrue(Validation.validateIp("10.0.0.1"))
        assertTrue(Validation.validateIp("192.168.1.1"))
        assertTrue(Validation.validateIp("0.0.0.0"))
        assertTrue(Validation.validateIp("255.255.255.255"))
    }

    @Test
    fun `validateIp rejects invalid IPv4 addresses`() {
        assertFalse(Validation.validateIp("256.0.0.1"))
        assertFalse(Validation.validateIp("abc"))
        assertFalse(Validation.validateIp(""))
        assertFalse(Validation.validateIp("10.0.0"))
        assertFalse(Validation.validateIp("10.0.0.1.1"))
    }

    // ── validateCidr ──────────────────────────────────────────────────────────

    @Test
    fun `validateCidr accepts valid CIDR blocks`() {
        assertTrue(Validation.validateCidr("10.0.0.0/8"))
        assertTrue(Validation.validateCidr("192.168.1.0/24"))
        assertTrue(Validation.validateCidr("0.0.0.0/0"))
        assertTrue(Validation.validateCidr("10.8.0.5/32"))
    }

    @Test
    fun `validateCidr rejects invalid CIDR blocks`() {
        assertFalse(Validation.validateCidr("10.0.0.0/33"))
        assertFalse(Validation.validateCidr("10.0.0.0"))
        assertFalse(Validation.validateCidr("abc/24"))
        assertFalse(Validation.validateCidr("/24"))
    }

    // ── validatePort ──────────────────────────────────────────────────────────

    @Test
    fun `validatePort accepts valid port numbers`() {
        assertTrue(Validation.validatePort(1))
        assertTrue(Validation.validatePort(80))
        assertTrue(Validation.validatePort(65535))
    }

    @Test
    fun `validatePort rejects invalid port numbers`() {
        assertFalse(Validation.validatePort(0))
        assertFalse(Validation.validatePort(-1))
        assertFalse(Validation.validatePort(65536))
    }

    // ── validateServerUrl ─────────────────────────────────────────────────────

    @Test
    fun `validateServerUrl accepts valid HTTPS URLs`() {
        assertTrue(Validation.validateServerUrl("https://gate.example.com"))
        assertTrue(Validation.validateServerUrl("https://vpn.local:3000"))
        assertTrue(Validation.validateServerUrl("https://10.0.0.1"))
    }

    @Test
    fun `validateServerUrl rejects non-HTTPS or empty URLs`() {
        assertFalse(Validation.validateServerUrl(""))
        assertFalse(Validation.validateServerUrl("ftp://example.com"))
        assertFalse(Validation.validateServerUrl("not-a-url"))
    }

    // ── validateApiToken ──────────────────────────────────────────────────────

    @Test
    fun `validateApiToken accepts valid tokens`() {
        assertTrue(Validation.validateApiToken("gc_abc123def456"))
        assertTrue(Validation.validateApiToken("gc_x"))
    }

    @Test
    fun `validateApiToken rejects invalid tokens`() {
        assertFalse(Validation.validateApiToken(""))
        assertFalse(Validation.validateApiToken("abc123"))
        assertFalse(Validation.validateApiToken("GC_abc"))
    }

    // ── validateFingerprint ───────────────────────────────────────────────────

    @Test
    fun `validateFingerprint accepts valid 64-char lowercase hex strings`() {
        assertTrue(Validation.validateFingerprint("a".repeat(64)))
        assertTrue(Validation.validateFingerprint("0123456789abcdef".repeat(4)))
    }

    @Test
    fun `validateFingerprint rejects invalid fingerprints`() {
        assertFalse(Validation.validateFingerprint("a".repeat(63)))
        assertFalse(Validation.validateFingerprint("g".repeat(64)))
        assertFalse(Validation.validateFingerprint(""))
    }

    // ── parseSplitRoutes ──────────────────────────────────────────────────────

    @Test
    fun `parseSplitRoutes returns only valid CIDRs from multi-line input`() {
        val input = "10.0.0.0/8\n\n192.168.1.0/24\ninvalid\n172.16.0.0/12"
        val result = Validation.parseSplitRoutes(input)
        assertEquals(listOf("10.0.0.0/8", "192.168.1.0/24", "172.16.0.0/12"), result)
    }

    @Test
    fun `parseSplitRoutes returns empty list for blank input`() {
        assertEquals(emptyList<String>(), Validation.parseSplitRoutes(""))
        assertEquals(emptyList<String>(), Validation.parseSplitRoutes("  \n  "))
    }
}
