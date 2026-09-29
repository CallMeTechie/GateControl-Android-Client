package com.gatecontrol.android.ui.vpn

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ServerTimeParseTest {

    private val expected = java.time.Instant.parse("2026-10-01T12:00:00Z").toEpochMilli()

    @Test
    fun `parses ISO instant`() {
        assertEquals(expected, VpnViewModel.parseServerTime("2026-10-01T12:00:00Z"))
        assertEquals(expected, VpnViewModel.parseServerTime("2026-10-01T12:00:00.000Z"))
    }

    @Test
    fun `parses offset date time`() {
        assertEquals(expected, VpnViewModel.parseServerTime("2026-10-01T14:00:00+02:00"))
    }

    @Test
    fun `parses SQLite datetime as UTC`() {
        assertEquals(expected, VpnViewModel.parseServerTime("2026-10-01 12:00:00"))
    }

    @Test
    fun `returns null for empty or garbage`() {
        assertNull(VpnViewModel.parseServerTime(null))
        assertNull(VpnViewModel.parseServerTime(""))
        assertNull(VpnViewModel.parseServerTime("never"))
    }
}
