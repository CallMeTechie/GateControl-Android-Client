package com.gatecontrol.android.ui.quickactions

import com.gatecontrol.android.network.QuickActionReading
import com.gatecontrol.android.network.QuickActionWidget
import com.gatecontrol.android.quickactions.QuickActionControlOptionItem
import com.gatecontrol.android.quickactions.QuickActionWidgetItem
import com.google.gson.JsonPrimitive
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

    @Test
    fun `a widget's badge needs an icon and keeps its value, a widget of only a badge and a reading is kept`() {
        val battery = QuickActionReading(label = "Ladestand", value = JsonPrimitive(24), unit = "%", icon = "M2 7h16v10H2z", state = "good")
        val w = QuickActionWidgetItem.from(QuickActionWidget(badge = battery, readings = listOf(QuickActionReading(id = "range", label = "Reichweite", value = JsonPrimitive(105), unit = "km"))))
        assertNotNull(w)
        assertEquals("24", w!!.badge?.value)
        assertEquals("%", w.badge?.unit)
        assertEquals("good", w.badge?.state)
        assertNull(w.value)
        assertNull(QuickActionWidgetItem.from(QuickActionWidget(badge = battery.copy(icon = null), value = JsonPrimitive(1)))?.badge)
        assertNull(QuickActionWidgetItem.badgeOf(battery.copy(icon = "<svg>")))
        assertEquals(w, w.normalized())
    }

    @Test
    fun `the value's icon (core 1_187) passes when it is a path, else it is dropped`() {
        val cal = "M4 6h16v14H4zM4 10h16M9 3v4M15 3v4"
        val w = QuickActionWidgetItem.from(QuickActionWidget(value = JsonPrimitive(65), unit = "km", label = "Diesen Monat", icon = cal))
        assertEquals(cal, w!!.icon)
        assertEquals("Diesen Monat", w.label, "kept: the accessible name of the icon")
        assertNull(QuickActionWidgetItem.from(QuickActionWidget(value = JsonPrimitive(65), icon = "<svg onload=x>"))!!.icon)
        assertNull(QuickActionWidgetItem.from(QuickActionWidget(value = JsonPrimitive(65)))!!.icon)
    }
}
