package com.gatecontrol.android.quickactions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** A long press on a door quick action opens the door screen; other plugins' actions have none. */
class QuickActionLongPressTest {

    private val doorOpen = QuickActionItem("gatecontrol-door", "Tür & Kamera", "1:open", "Haustür öffnen", confirm = true)
    private val doorLock = QuickActionItem("gatecontrol-door", "Tür & Kamera", "1:lock", "Haustür zusperren")
    private val skoda = QuickActionItem("gatecontrol-skoda", "Skoda", "lock", "Abschließen", confirm = true)

    @Test
    fun `door actions open the door screen`() {
        assertEquals(QuickActionLongPress.Target.DOOR, QuickActionLongPress.target(doorOpen, doorListed = true))
        assertEquals(QuickActionLongPress.Target.DOOR, QuickActionLongPress.target(doorLock, doorListed = true))
    }

    @Test
    fun `other plugins keep no long press`() {
        assertNull(QuickActionLongPress.target(skoda, doorListed = true))
        assertNull(QuickActionLongPress.target(skoda, doorListed = false))
    }

    @Test
    fun `a stored door pin opens nothing once the plugin is gone`() {
        assertNull(QuickActionLongPress.target(doorOpen, doorListed = false))
    }
}
