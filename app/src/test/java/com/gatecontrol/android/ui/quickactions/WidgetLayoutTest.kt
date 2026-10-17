package com.gatecontrol.android.ui.quickactions

import com.gatecontrol.android.quickactions.QuickActionControlOptionItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WidgetLayoutTest {

    @Test
    fun `action buttons fill whole rows - up to three, four as two by two`() {
        val rows = (1..7).associateWith { n -> (1..n).toList().chunked(buttonsPerRow(n)).map { it.size } }
        assertEquals(listOf(1), rows[1])
        assertEquals(listOf(2), rows[2])
        assertEquals(listOf(3), rows[3])
        assertEquals(listOf(2, 2), rows[4])
        assertEquals(listOf(3, 2), rows[5])
        assertEquals(listOf(3, 3), rows[6])
        assertEquals(listOf(3, 3, 1), rows[7])
    }

    @Test
    fun `choice options draw the server's icon, else a built-in one for known modes, else none`() {
        assertEquals("M1 1h2", QuickActionControlOptionItem("cool", "Kühlen", "M1 1h2").glyph)
        for (id in listOf("auto", "cool", "heat", "dry", "fan")) assertNotNull(QuickActionControlOptionItem(id, id).glyph, id)
        assertNull(QuickActionControlOptionItem("turbo", "Turbo").glyph)
    }
}
