package com.gatecontrol.android.tv.push

import com.gatecontrol.android.notify.PushSettings
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TvPushPlanTest {
    @Test
    fun `runs only while paired and switched on`() {
        assertTrue(TvPushPlan.run(configured = true, settings = PushSettings(enabled = true)))
        assertFalse(TvPushPlan.run(configured = true, settings = PushSettings(enabled = false)))
        assertFalse(TvPushPlan.run(configured = false, settings = PushSettings(enabled = true)))
    }

    @Test
    fun `switched on once after the pairing, never again after the user switched it off`() {
        assertTrue(TvPushPlan.autoEnable(configured = true, settings = PushSettings()))
        assertFalse(TvPushPlan.autoEnable(configured = false, settings = PushSettings()))
        assertFalse(TvPushPlan.autoEnable(configured = true, settings = PushSettings(enabled = false, everEnabled = true)))
        assertFalse(TvPushPlan.autoEnable(configured = true, settings = PushSettings(enabled = true, everEnabled = true)))
    }
}
