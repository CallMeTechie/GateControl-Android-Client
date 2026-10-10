package com.gatecontrol.android.ui.pihole

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PiholeFormatTest {
    @Test
    fun `formats minutes and seconds`() {
        assertEquals("01:05", formatMmSs(65))
    }

    @Test
    fun `pads single digit seconds`() {
        assertEquals("00:05", formatMmSs(5))
    }

    @Test
    fun `zero is double-zero`() {
        assertEquals("00:00", formatMmSs(0))
    }

    @Test
    fun `minutes can exceed 59`() {
        assertEquals("60:00", formatMmSs(3600))
    }

    @Test
    fun `negative clamps to zero`() {
        assertEquals("00:00", formatMmSs(-10))
    }
}
