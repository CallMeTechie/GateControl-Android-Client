package com.gatecontrol.android.ui.quickactions

import com.gatecontrol.android.quickactions.QuickActionItem
import com.gatecontrol.android.quickactions.QuickActionWidgetItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QuickActionsRowTest {

    private val light = QuickActionItem("smarthome", "Smart Home", "light:1", "Licht", toggle = true, state = "off")
    private val door = QuickActionItem("skoda", "Auto", "lock", "Abschließen", confirm = true)

    @Test
    fun `nothing pinned and nothing offered - hidden`() {
        assertEquals(QuickActionsRow.Hidden, QuickActionsRow.of(emptyList(), emptyList()))
    }

    @Test
    fun `nothing pinned but actions offered - only the choose button`() {
        assertEquals(QuickActionsRow.Choose, QuickActionsRow.of(emptyList(), listOf(light)))
    }

    @Test
    fun `pins show as tiles with the server's current state and icon`() {
        val fresh = light.copy(state = "on", title = "Wohnzimmer", icon = "M1 1h2")
        val row = QuickActionsRow.of(listOf(light, door), listOf(fresh))
        assertTrue(row is QuickActionsRow.Tiles)
        val items = (row as QuickActionsRow.Tiles).items
        assertEquals(listOf("Wohnzimmer", "Abschließen"), items.map { it.title })
        assertTrue(items[0].isOn)
        assertEquals("M1 1h2", items[0].icon)
    }

    @Test
    fun `offline pins still show`() {
        val row = QuickActionsRow.of(listOf(door), emptyList()) as QuickActionsRow.Tiles
        assertEquals(listOf(door), row.items)
    }

    @Test
    fun `a pin shown as widget keeps that choice and the server's widget`() {
        val widget = QuickActionWidgetItem(value = "21,5", unit = "°C", status = "Kühlen")
        val pinned = light.copy(asWidget = true, widget = QuickActionWidgetItem(value = "20"))
        val fresh = light.copy(state = "on", widget = widget)
        val row = QuickActionsRow.of(listOf(pinned, door), listOf(fresh)) as QuickActionsRow.Tiles
        assertEquals(listOf(door), row.buttons)
        assertEquals(1, row.widgets.size)
        assertEquals(widget, row.widgets[0].widget)
        assertTrue(row.widgets[0].isOn)
    }

    @Test
    fun `a pin marked as widget without a widget from the server stays a button`() {
        val row = QuickActionsRow.of(listOf(light.copy(asWidget = true)), listOf(light)) as QuickActionsRow.Tiles
        assertEquals(1, row.buttons.size)
        assertTrue(row.widgets.isEmpty())
    }
}
