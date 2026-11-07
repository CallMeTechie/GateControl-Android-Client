package com.gatecontrol.android.quickactions

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The row "Tür & Kamera" on Start for people who may only see cameras (no door quick action). */
class DoorEntryTest {

    private val doorLock = QuickActionItem("gatecontrol-door", "Tür & Kamera", "1:lock", "Haustür zusperren")
    private val ringMode = QuickActionItem("gatecontrol-door", "Tür & Kamera", "mode:loc1", "Ring-Modus")
    private val skoda = QuickActionItem("gatecontrol-skoda", "Skoda", "lock", "Abschließen")

    @Test
    fun `only cameras to see - the row on Start`() {
        assertTrue(DoorEntry.show(doorListed = true, page = null, loaded = true, actions = emptyList()))
        assertTrue(DoorEntry.show(doorListed = true, page = null, loaded = true, actions = listOf(skoda)))
    }

    @Test
    fun `a door quick action already leads there - no row`() {
        assertFalse(DoorEntry.show(doorListed = true, page = null, loaded = true, actions = listOf(doorLock)))
        assertFalse(DoorEntry.show(doorListed = true, page = null, loaded = true, actions = listOf(skoda, ringMode)))
    }

    @Test
    fun `nothing before the actions loaded, without the plugin or on another page`() {
        assertFalse(DoorEntry.show(doorListed = true, page = null, loaded = false, actions = emptyList()))
        assertFalse(DoorEntry.show(doorListed = false, page = null, loaded = true, actions = emptyList()))
        assertFalse(DoorEntry.show(doorListed = true, page = "chats", loaded = true, actions = emptyList()))
    }
}
