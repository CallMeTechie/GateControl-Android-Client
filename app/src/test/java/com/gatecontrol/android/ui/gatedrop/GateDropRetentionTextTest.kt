package com.gatecontrol.android.ui.gatedrop

import android.app.Application
import com.gatecontrol.android.gatedrop.GdRetention
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The footer of "Geteilt": how long received files stay on the server — as the administrator set it (GateDrop 1.5),
 * else `limits.retentionDays`, else 7 days as before. German and English.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GateDropRetentionTextTest {

    private fun text(qualifiers: String, r: GdRetention): String {
        RuntimeEnvironment.setQualifiers(qualifiers)
        return retentionText(RuntimeEnvironment.getApplication().resources, r)
    }

    @Test
    fun `german - as before, one span, right after the pick-up, days after it`() {
        assertEquals("Empfangenes wird nach 7 Tagen vom Server gelöscht.", text("de", GdRetention()))
        assertEquals("Empfangenes wird nach 14 Tagen vom Server gelöscht.", text("de", GdRetention(unpickedDays = 14)))
        // after ≥ unpicked: a picked-up send never stays longer — one span
        assertEquals("Empfangenes wird nach 7 Tagen vom Server gelöscht.", text("de", GdRetention(unpickedDays = 7, afterPickupDays = 7)))
        assertEquals(
            "Empfangenes wird vom Server gelöscht – kurz nach dem Abholen, sonst nach 30 Tagen.",
            text("de", GdRetention(unpickedDays = 30, afterPickupDays = 0)),
        )
        assertEquals(
            "Empfangenes wird vom Server gelöscht – abgeholt nach 1 Tag, sonst nach 3 Tagen.",
            text("de", GdRetention(unpickedDays = 3, afterPickupDays = 1)),
        )
    }

    @Test
    fun `english`() {
        assertEquals("Received items are deleted from the server after 7 days.", text("en", GdRetention()))
        assertEquals(
            "Received items are deleted from the server – shortly after pickup, otherwise after 14 days.",
            text("en", GdRetention(unpickedDays = 14, afterPickupDays = 0)),
        )
        assertEquals(
            "Received items are deleted from the server – 1 day after pickup, otherwise after 7 days.",
            text("en", GdRetention(unpickedDays = 7, afterPickupDays = 1)),
        )
    }
}
