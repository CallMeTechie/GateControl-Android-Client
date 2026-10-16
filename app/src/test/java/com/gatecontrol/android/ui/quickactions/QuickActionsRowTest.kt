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
    fun `only widgets pinned - the section shows, without buttons`() {
        val row = QuickActionsRow.of(emptyList(), listOf(light), hasWidgets = true) as QuickActionsRow.Tiles
        assertTrue(row.items.isEmpty())
    }

    @Test
    fun `a pin takes the server's widget too`() {
        val widget = QuickActionWidgetItem(value = "21,5", unit = "°C")
        val row = QuickActionsRow.of(listOf(light), listOf(light.copy(widget = widget))) as QuickActionsRow.Tiles
        assertEquals(widget, row.items.single().widget)
    }
}
