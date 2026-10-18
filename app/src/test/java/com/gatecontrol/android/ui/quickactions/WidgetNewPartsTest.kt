package com.gatecontrol.android.ui.quickactions

import com.gatecontrol.android.quickactions.QuickActionDeviceGroup
import com.gatecontrol.android.quickactions.QuickActionItem
import com.gatecontrol.android.quickactions.QuickActionReadingItem
import com.gatecontrol.android.quickactions.QuickActionWidgetItem
import com.gatecontrol.android.quickactions.StartWidgetPin
import com.gatecontrol.android.quickactions.WidgetPart
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WidgetNewPartsTest {

    private fun clima(vararg readings: QuickActionReadingItem) = QuickActionDeviceGroup(
        key = "gatecontrol-clima/ac",
        plugin = "gatecontrol-clima",
        pluginName = "Klima",
        title = "Klimaanlage",
        actions = listOf(
            QuickActionItem(
                plugin = "gatecontrol-clima", pluginName = "Klima", id = "power", title = "An", toggle = true, state = "off",
                widget = QuickActionWidgetItem(value = "10,5", unit = "°C", label = "Innentemperatur", readings = readings.toList()),
            ),
        ),
    )

    private val humidity = QuickActionReadingItem("humidity", "Feuchte", "40", "%")
    private val outdoor = QuickActionReadingItem("outdoor", "Außen", "4,0", "°C")

    @Test
    fun `a part the device got after the choice is shown, a hidden one stays hidden`() {
        val before = clima(humidity)
        val pin = StartWidgetPin.withWidget(emptyList(), before, parts = listOf("action:power", WidgetPart.VALUE), wide = false, max = 8).single()
        assertEquals(before.parts().map { it.id }, pin.known)
        val after = clima(humidity, outdoor)
        val shown = pin.shownParts(after).map { it.id }
        assertTrue(WidgetPart.reading("outdoor") in shown) // new: shown
        assertFalse(WidgetPart.reading("humidity") in shown) // hidden on purpose: stays hidden
        assertFalse(WidgetPart.STATUS in shown)
    }

    @Test
    fun `a choice from before the known parts shows readings it did not list`() {
        val legacy = StartWidgetPin(device = "gatecontrol-clima/ac", parts = listOf("action:power", WidgetPart.VALUE))
        val shown = legacy.shownParts(clima(outdoor)).map { it.id }
        assertEquals(listOf("action:power", WidgetPart.VALUE, WidgetPart.reading("outdoor")), shown)
    }

    @Test
    fun `known parts survive storage, older pins decode without them`() {
        val pin = StartWidgetPin(device = "d", parts = listOf("value"), known = listOf("value", "status"))
        assertEquals(pin, StartWidgetPin.decode(StartWidgetPin.encode(listOf(pin))).single())
        assertEquals(null, StartWidgetPin.decode("""[{"device":"d","parts":["value"]}]""").single().known)
    }

    @Test
    fun `temperatures get the degree sign alone`() {
        assertTrue(isTemperature("°C"))
        assertTrue(isTemperature("°"))
        assertFalse(isTemperature("%"))
        assertFalse(isTemperature(null))
        assertEquals("10,5°", compactValue("10,5", "°C"))
        assertEquals("125 km", compactValue("125", "km"))
        assertEquals("3", compactValue("3", null))
    }
}
