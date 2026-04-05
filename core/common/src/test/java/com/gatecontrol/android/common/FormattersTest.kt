package com.gatecontrol.android.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FormattersTest {

    // --- formatBytes ---

    @Test
    fun `formatBytes zero returns 0 B`() {
        assertEquals("0 B", Formatters.formatBytes(0))
    }

    @Test
    fun `formatBytes 512 returns 512 B`() {
        assertEquals("512 B", Formatters.formatBytes(512))
    }

    @Test
    fun `formatBytes 1024 returns 1_0 KB`() {
        assertEquals("1.0 KB", Formatters.formatBytes(1024))
    }

    @Test
    fun `formatBytes 1536 returns 1_5 KB`() {
        assertEquals("1.5 KB", Formatters.formatBytes(1536))
    }

    @Test
    fun `formatBytes 1048576 returns 1_0 MB`() {
        assertEquals("1.0 MB", Formatters.formatBytes(1_048_576))
    }

    @Test
    fun `formatBytes 1073741824 returns 1_0 GB`() {
        assertEquals("1.0 GB", Formatters.formatBytes(1_073_741_824))
    }

    @Test
    fun `formatBytes 4724464025 returns 4_4 GB`() {
        assertEquals("4.4 GB", Formatters.formatBytes(4_724_464_025))
    }

    // --- formatSpeed ---

    @Test
    fun `formatSpeed zero returns 0 B per s`() {
        assertEquals("0 B/s", Formatters.formatSpeed(0))
    }

    @Test
    fun `formatSpeed 1258291 returns 1_2 MB per s`() {
        assertEquals("1.2 MB/s", Formatters.formatSpeed(1_258_291))
    }

    @Test
    fun `formatSpeed 348160 returns 340 KB per s`() {
        assertEquals("340 KB/s", Formatters.formatSpeed(348_160))
    }

    // --- formatDuration ---

    @Test
    fun `formatDuration less than 60 seconds returns less than 1m`() {
        assertEquals("<1m", Formatters.formatDuration(0))
        assertEquals("<1m", Formatters.formatDuration(59))
    }

    @Test
    fun `formatDuration exactly 60 seconds returns 1m`() {
        assertEquals("1m", Formatters.formatDuration(60))
    }

    @Test
    fun `formatDuration 8100 seconds returns 2h 15m`() {
        assertEquals("2h 15m", Formatters.formatDuration(8_100))
    }

    @Test
    fun `formatDuration 97200 seconds returns 1d 3h`() {
        assertEquals("1d 3h", Formatters.formatDuration(97_200))
    }

    // --- formatHandshakeAge ---

    @Test
    fun `formatHandshakeAge 0 seconds in English returns now`() {
        assertEquals("now", Formatters.formatHandshakeAge(0, "en"))
    }

    @Test
    fun `formatHandshakeAge 30 seconds in English returns 30s ago`() {
        assertEquals("30s ago", Formatters.formatHandshakeAge(30, "en"))
    }

    @Test
    fun `formatHandshakeAge 180 seconds in English returns 3m ago`() {
        assertEquals("3m ago", Formatters.formatHandshakeAge(180, "en"))
    }

    @Test
    fun `formatHandshakeAge 0 seconds in German returns jetzt`() {
        assertEquals("jetzt", Formatters.formatHandshakeAge(0, "de"))
    }

    @Test
    fun `formatHandshakeAge 30 seconds in German returns vor 30s`() {
        assertEquals("vor 30s", Formatters.formatHandshakeAge(30, "de"))
    }

    @Test
    fun `formatHandshakeAge 180 seconds in German returns vor 3m`() {
        assertEquals("vor 3m", Formatters.formatHandshakeAge(180, "de"))
    }

    @Test
    fun `formatHandshakeAge hours in English returns Xh ago`() {
        assertEquals("2h ago", Formatters.formatHandshakeAge(7_200, "en"))
    }

    @Test
    fun `formatHandshakeAge hours in German returns vor Xh`() {
        assertEquals("vor 2h", Formatters.formatHandshakeAge(7_200, "de"))
    }

    @Test
    fun `formatHandshakeAge default locale is English`() {
        assertEquals("30s ago", Formatters.formatHandshakeAge(30))
    }
}
