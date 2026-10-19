package com.gatecontrol.android.quickactions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QuickActionDevicesTest {

    private val widget = QuickActionWidgetItem(
        value = "78", unit = "%", label = "Ladestand", power = false,
        readings = listOf(QuickActionReadingItem("range", "Reichweite", "312", "km"), QuickActionReadingItem("lock", "Verriegelung", icon = "M1 1h2", state = "good")),
    )
    private val climate = QuickActionItem("skoda", "Fahrzeuge", "climate:1", "Klimatisierung", toggle = true, state = "off", deviceId = "v:1", deviceTitle = "Enyaq", widget = widget)
    private val lock = QuickActionItem(
        "skoda", "Fahrzeuge", "lock:1", "Verriegelt", toggle = true, state = "on", confirm = true, confirmWhen = "off",
        deviceId = "v:1", deviceTitle = "Enyaq", icon = "M0 0h1",
        look = QuickActionLookItem(on = QuickActionLookStateItem("M1 1h2", "good"), off = QuickActionLookStateItem("M2 2h2", "crit")),
    )
    private val scene = QuickActionItem("smarthome", "Smart Home", "scene:4", "Abend", subtitle = "Szene")
    private val ac = QuickActionItem(
        "midea", "Klima", "ac:2", "Wohnzimmer", toggle = true, state = "on", deviceId = "ac:2", deviceTitle = "Wohnzimmer",
        widget = QuickActionWidgetItem(value = "23,5", unit = "°C", label = "Innentemperatur", status = "Kühlen",
            controls = listOf(QuickActionControlItem("target", QuickActionControlItem.KIND_STEPPER, "Ziel", 22.0, 16.0, 30.0, 0.5, "°C"))),
    )

    @Test
    fun `actions are grouped by device in the order they come, lone actions are their own device`() {
        val devices = QuickActionDevices.group(listOf(climate, scene, lock))
        assertEquals(listOf("skoda/v:1", "smarthome/@scene:4"), devices.map { it.key })
        assertEquals("Enyaq", devices[0].title)
        assertEquals(listOf("climate:1", "lock:1"), devices[0].actions.map { it.id })
        assertEquals("Abend", devices[1].title)
        assertEquals("Szene", devices[1].subtitle)
    }

    @Test
    fun `the parts of a widget follow the device's widget and actions`() {
        val car = QuickActionDevices.group(listOf(climate, lock)).single()
        assertNull(car.powerAction, "power: false — the carrier is a button like the others")
        assertEquals(
            listOf("value", "reading:range", "reading:lock", "status", "action:climate:1", "action:lock:1"),
            car.parts().map { it.id },
        )
        val room = QuickActionDevices.group(listOf(ac)).single()
        assertEquals(ac, room.powerAction)
        assertEquals(listOf("action:ac:2", "value", "status", "control:target"), room.parts().map { it.id })
        assertEquals(WidgetPart.Kind.POWER, room.parts().first().kind)
        val plain = QuickActionDevices.group(listOf(scene)).single()
        assertEquals(listOf("action:scene:4"), plain.parts().map { it.id }, "a device without a widget shows its buttons")
    }

    @Test
    fun `the lock asks only before unlocking and shows the state's look`() {
        assertTrue(lock.needsConfirm, "locked → the tap unlocks")
        assertEquals("M1 1h2", lock.displayIcon)
        assertEquals("good", lock.currentLook?.tone)
        val open = lock.copy(state = "off")
        assertFalse(open.needsConfirm, "unlocked → the tap locks, no question")
        assertEquals("crit", open.currentLook?.tone)
        val unknown = lock.copy(state = null)
        assertFalse(unknown.needsConfirm, "unknown → locks")
        assertEquals("M0 0h1", unknown.displayIcon)
        assertTrue(scene.copy(confirm = true).needsConfirm, "buttons keep confirm")
    }

    @Test
    fun `buttons of a device are set without moving the others`() {
        val car = QuickActionDevices.group(listOf(climate, lock)).single()
        val pins = listOf(scene, climate)
        assertEquals(listOf("scene:4", "lock:1"), QuickActionDevices.withButtons(pins, car, setOf("lock:1"), 12).map { it.id })
        assertEquals(listOf("scene:4", "climate:1", "lock:1"), QuickActionDevices.withButtons(pins, car, setOf("climate:1", "lock:1"), 12).map { it.id })
        assertEquals(listOf("scene:4"), QuickActionDevices.withButtons(pins, car, emptySet(), 12).map { it.id })
        assertEquals(listOf("scene:4", "climate:1"), QuickActionDevices.withButtons(pins, car, setOf("climate:1", "lock:1"), 2).map { it.id }, "capped")
    }

    @Test
    fun `a widget pin finds its device again by an action and keeps a copy`() {
        val car = QuickActionDevices.group(listOf(climate, lock)).single()
        val old = StartWidgetPin(device = "skoda/@climate:1", anchor = "skoda/climate:1")
        assertEquals(car, old.resolve(listOf(car)))
        val synced = old.synced(listOf(car))
        assertEquals("skoda/v:1", synced.device)
        assertEquals(car, synced.snapshot)
        assertEquals(car, synced.resolve(emptyList()), "offline: the copy")
        assertNull(StartWidgetPin("x/y").resolve(listOf(car)))
    }

    @Test
    fun `widget pins survive a round trip and broken storage reads as empty`() {
        val car = QuickActionDevices.group(listOf(climate, lock)).single()
        val pins = StartWidgetPin.withWidget(emptyList(), car, listOf("value"), wide = false, max = 8)
        assertEquals(pins, StartWidgetPin.decode(StartWidgetPin.encode(pins)))
        assertTrue(StartWidgetPin.decode("{nope").isEmpty())
        assertTrue(StartWidgetPin.decode("""[{"parts":["value"]}]""").isEmpty())
        val replaced = StartWidgetPin.withWidget(pins, car, listOf("status"), wide = true, max = 8)
        assertEquals(1, replaced.size)
        assertEquals(listOf("status"), replaced.single().parts)
    }

    @Test
    fun `pins and widgets of a replaced plugin move to its successor`() {
        val renames = mapOf("midea" to "raumklima")
        val pins = QuickActionDevices.renamedPins(listOf(ac, scene, ac.copy(plugin = "raumklima")), renames)
        assertEquals(listOf("raumklima/ac:2", "smarthome/scene:4"), pins.map { it.key }, "renamed, the duplicate dropped")
        val device = QuickActionDevices.group(listOf(ac)).single()
        val widgets = listOf(
            StartWidgetPin("midea/ac:2", "midea/ac:2", listOf("value"), wide = true, snapshot = device),
            StartWidgetPin("midea-two/x", "midea-two/x"),
            StartWidgetPin("skoda/v:1", "skoda/climate:1"),
        )
        val moved = StartWidgetPin.renamed(widgets, renames)
        assertEquals(listOf("raumklima/ac:2", "midea-two/x", "skoda/v:1"), moved.map { it.device }, "only the whole plugin id")
        assertEquals("raumklima/ac:2", moved[0].anchor)
        assertEquals(listOf("value"), moved[0].parts)
        assertTrue(moved[0].wide)
        assertNull(moved[0].snapshot, "the old copy goes, the next sync brings the new one")
        assertEquals(widgets[2], moved[2])
        val listed = QuickActionDevices.group(listOf(ac.copy(plugin = "raumklima", state = "off")))
        assertEquals("off", moved[0].resolve(listed)?.actions?.single()?.state, "found again under the new plugin")
    }
}
