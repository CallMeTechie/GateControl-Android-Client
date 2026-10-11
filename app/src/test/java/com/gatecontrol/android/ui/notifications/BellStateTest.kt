package com.gatecontrol.android.ui.notifications

import com.gatecontrol.android.notify.PushSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BellStateTest {

    private val neverOn = PushSettings(enabled = false, everEnabled = false)
    private val switchedOff = PushSettings(enabled = false, everEnabled = true)
    private val on = PushSettings(enabled = true, everEnabled = true)

    @Test
    fun `nothing is shown before the settings are loaded`() {
        assertEquals(BellState(), bellState(null, hintDismissed = false, systemAllowed = false))
    }

    @Test
    fun `never switched on - bell leads to the settings and the hint shows`() {
        assertEquals(BellState(pushOff = true, showHint = true), bellState(neverOn, hintDismissed = false, systemAllowed = true))
    }

    @Test
    fun `a closed hint stays closed, the bell still says off`() {
        assertEquals(BellState(pushOff = true), bellState(neverOn, hintDismissed = true, systemAllowed = true))
    }

    @Test
    fun `switched off on purpose - bell changes, no hint`() {
        assertEquals(BellState(pushOff = true), bellState(switchedOff, hintDismissed = false, systemAllowed = true))
    }

    @Test
    fun `on but blocked by Android - hint with the blocked text`() {
        assertEquals(BellState(pushOff = true, blocked = true, showHint = true), bellState(on, hintDismissed = false, systemAllowed = false))
    }

    @Test
    fun `on and allowed - plain bell`() {
        assertEquals(BellState(), bellState(on, hintDismissed = false, systemAllowed = true))
    }
}
