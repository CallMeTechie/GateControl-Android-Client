package com.gatecontrol.android.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SplitTunnelModeTest {

    @Test
    fun `wire values round-trip`() {
        SplitTunnelMode.entries.forEach { assertEquals(it, SplitTunnelMode.fromWire(it.wire)) }
    }

    @Test
    fun `stored values keep their legacy spelling`() {
        assertEquals("off", SplitTunnelMode.OFF.wire)
        assertEquals("exclude", SplitTunnelMode.EXCLUDE.wire)
        assertEquals("include", SplitTunnelMode.INCLUDE.wire)
    }

    @Test
    fun `case and whitespace are tolerated`() {
        assertEquals(SplitTunnelMode.EXCLUDE, SplitTunnelMode.fromWire(" Exclude "))
    }

    @Test
    fun `unknown or missing value falls back to full tunnel`() {
        assertEquals(SplitTunnelMode.OFF, SplitTunnelMode.fromWire(null))
        assertEquals(SplitTunnelMode.OFF, SplitTunnelMode.fromWire(""))
        assertEquals(SplitTunnelMode.OFF, SplitTunnelMode.fromWire("bogus"))
    }
}
