package com.gatecontrol.android.profile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AvatarWizardTest {

    private val rect = CropRect(10, 20, 300)

    @Test
    fun `photo - crop first, then colour, then the plan`() {
        val start = AvatarWizard.forPhoto("blue")
        assertEquals(AvatarWizardStep.CROP, start.step)
        assertEquals(1, start.stepNumber)
        assertEquals(2, start.stepCount)
        assertFalse(start.canFinish, "no Fertig on the crop step")
        assertNull(start.plan())
        assertEquals(start, start.pick("green"), "no colour before the crop is confirmed")

        val color = start.next(rect)
        assertEquals(AvatarWizardStep.COLOR, color.step)
        assertEquals(2, color.stepNumber)
        assertTrue(color.canFinish)
        assertEquals(AvatarWizardPlan(rect, null), color.plan(), "same colour: only the upload")

        val green = color.pick("green")
        assertEquals(AvatarWizardPlan(rect, "green"), green.plan(), "upload + colour")
        assertEquals(green, green.next(CropRect(0, 0, 1)), "next only from the crop step")
    }

    @Test
    fun `colour only - straight to step 2, no indicator, no upload`() {
        val w = AvatarWizard.colorOnly("teal")
        assertEquals(AvatarWizardStep.COLOR, w.step)
        assertEquals(1, w.stepCount)
        assertTrue(w.canFinish)
        val plan = w.plan()!!
        assertTrue(plan.nothing, "unchanged: nothing to send")
        assertEquals(AvatarWizardPlan(null, "pink"), w.pick("pink").plan())
        assertEquals(AvatarWizardPlan(null, null), w.pick("pink").pick("teal").plan(), "back to the start colour")
    }

    @Test
    fun `current colour - the stored key, else the server default's key`() {
        val chosen = MyProfile(3, "Tim", "TI", "orange", "#F97316", null, AvatarPalette.DEFAULT)
        assertEquals("orange", AvatarWizard.currentColorKey(chosen))
        val derived = chosen.copy(color = null, colorHex = "#a855f7")
        assertEquals("purple", AvatarWizard.currentColorKey(derived))
        assertNull(AvatarWizard.currentColorKey(chosen.copy(color = null, colorHex = null)))
    }
}
