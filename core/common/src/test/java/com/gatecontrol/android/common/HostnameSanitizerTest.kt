package com.gatecontrol.android.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HostnameSanitizerTest {

    @Test
    fun `passes clean RFC-1123 label through`() {
        assertEquals("desktop-8f36qk8", HostnameSanitizer.sanitize("desktop-8f36qk8"))
        assertEquals("laptop42", HostnameSanitizer.sanitize("laptop42"))
    }

    @Test
    fun `lowercases`() {
        assertEquals("pixel-7", HostnameSanitizer.sanitize("Pixel-7"))
    }

    @Test
    fun `strips dotted suffix`() {
        assertEquals("marcs-mbp", HostnameSanitizer.sanitize("Marcs-MBP.local"))
        assertEquals("machine", HostnameSanitizer.sanitize("machine.corp.example.com"))
    }

    @Test
    fun `replaces spaces apostrophes and underscores`() {
        // Each invalid char becomes a single '-', repeats collapsed,
        // so "Marc's Pixel 7" -> "marc-s-pixel-7" (not "marcs-pixel-7").
        assertEquals("marc-s-pixel-7", HostnameSanitizer.sanitize("Marc's Pixel 7"))
        assertEquals("my-pc", HostnameSanitizer.sanitize("MY_PC"))
    }

    @Test
    fun `collapses repeated hyphens`() {
        assertEquals("foo-bar", HostnameSanitizer.sanitize("foo---bar"))
        assertEquals("a-b-c", HostnameSanitizer.sanitize("a__b__c"))
    }

    @Test
    fun `trims leading and trailing hyphens`() {
        assertEquals("foo", HostnameSanitizer.sanitize("-foo-"))
        assertEquals("bar", HostnameSanitizer.sanitize("___bar___"))
    }

    @Test
    fun `truncates to 63 chars and trims trailing hyphen`() {
        val long = "a".repeat(80)
        val out = HostnameSanitizer.sanitize(long)!!
        assertEquals(63, out.length)
    }

    @Test
    fun `returns null for empty and unusable input`() {
        assertNull(HostnameSanitizer.sanitize(""))
        assertNull(HostnameSanitizer.sanitize(null))
        assertNull(HostnameSanitizer.sanitize("---"))
        assertNull(HostnameSanitizer.sanitize("..."))
        assertNull(HostnameSanitizer.sanitize("@@@"))
    }
}
