package com.gatecontrol.android.quickactions

import com.gatecontrol.android.network.QuickAction
import com.gatecontrol.android.network.QuickActionOpens
import com.gatecontrol.android.push.PushNavTarget
import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `opens` of a quick action (core 1.189+): parsed, kept in pins, and only the logbook opens a screen. */
class QuickActionScreensTest {

    private fun action(plugin: String = "gatecontrol-skoda", opens: QuickActionOpens? = QuickActionOpens("logbook", "3")) =
        QuickAction(plugin = plugin, pluginName = "Fahrzeuge", id = "3:logbook", title = "Fahrtenbuch", kind = "button", opens = opens)

    @Test
    fun `opens is parsed from the server's JSON`() {
        val raw = Gson().fromJson(
            """{"plugin":"gatecontrol-skoda","id":"3:logbook","title":"Fahrtenbuch","kind":"button","opens":{"screen":"logbook","ref":"3"}}""",
            QuickAction::class.java,
        )
        val item = QuickActionRepository.sanitize(listOf(raw)).single()
        assertEquals("logbook", item.opensScreen)
        assertEquals("3", item.opensRef)
    }

    @Test
    fun `invalid screens are dropped, refs capped`() {
        val bad = QuickActionRepository.sanitize(listOf(action(opens = QuickActionOpens("Log Book!", "3")))).single()
        assertNull(bad.opensScreen)
        assertNull(bad.opensRef)
        val long = QuickActionRepository.sanitize(listOf(action(opens = QuickActionOpens("logbook", "x".repeat(81))))).single()
        assertEquals("logbook", long.opensScreen)
        assertNull(long.opensRef)
        val none = QuickActionRepository.sanitize(listOf(action(opens = null))).single()
        assertNull(none.opensScreen)
    }

    @Test
    fun `opens survives the pin storage`() {
        val item = QuickActionRepository.sanitize(listOf(action())).single()
        val back = QuickActionRepository.decodePins(QuickActionRepository.encodePins(listOf(item))).single()
        assertEquals("logbook", back.opensScreen)
        assertEquals("3", back.opensRef)
        // Pins stored before 1.56 have no such fields.
        val old = QuickActionRepository.decodePins("""[{"plugin":"gatecontrol-skoda","pluginName":"Fahrzeuge","id":"3:logbook","title":"Fahrtenbuch"}]""").single()
        assertNull(old.opensScreen)
        assertFalse(QuickActionScreens.opensScreen(old))
    }

    @Test
    fun `the vehicles plugin's logbook opens the logbook of the vehicle`() {
        val item = QuickActionRepository.sanitize(listOf(action())).single()
        assertEquals(PushNavTarget.Logbook(3L), QuickActionScreens.target(item))
        assertTrue(QuickActionScreens.opensScreen(item))
        val noRef = QuickActionRepository.sanitize(listOf(action(opens = QuickActionOpens("logbook", null)))).single()
        assertEquals(PushNavTarget.Logbook(null), QuickActionScreens.target(noRef))
        val oddRef = QuickActionRepository.sanitize(listOf(action(opens = QuickActionOpens("logbook", "Enyaq")))).single()
        assertEquals(PushNavTarget.Logbook(null), QuickActionScreens.target(oddRef))
    }

    @Test
    fun `unknown screens and other plugins run as before`() {
        val other = QuickActionRepository.sanitize(listOf(action(opens = QuickActionOpens("charging", "3")))).single()
        assertNull(QuickActionScreens.target(other))
        val foreign = QuickActionRepository.sanitize(listOf(action(plugin = "gatecontrol-other"))).single()
        assertNull(QuickActionScreens.target(foreign))
        assertFalse(QuickActionScreens.open(foreign))
    }
}
