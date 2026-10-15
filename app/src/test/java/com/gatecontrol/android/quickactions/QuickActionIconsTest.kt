package com.gatecontrol.android.quickactions

import com.gatecontrol.android.network.QuickAction
import com.gatecontrol.android.ui.components.GcIcons
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class QuickActionIconsTest {

    private val bulb = "M9 18h6M10 22h4M12 2a7 7 0 0 0-4 12.7V17h8v-2.3A7 7 0 0 0 12 2z"

    @Test
    fun `svg paths are accepted, other text is not`() {
        assertEquals(bulb, QuickActionIcons.sanitize("  $bulb "))
        assertNull(QuickActionIcons.sanitize(null))
        assertNull(QuickActionIcons.sanitize(""))
        assertNull(QuickActionIcons.sanitize("<svg><path d='M0 0'/></svg>"))
        assertNull(QuickActionIcons.sanitize("L1 1"), "must start with a move")
        assertNull(QuickActionIcons.sanitize("M" + "1 ".repeat(3_000)), "too long")
    }

    @Test
    fun `a valid path becomes a vector, cached per string`() {
        val v = QuickActionIcons.vectorOrNull(bulb)
        assertNotNull(v)
        assertSame(v, QuickActionIcons.vectorOrNull(bulb))
        assertEquals(24f, v!!.viewportWidth)
    }

    @Test
    fun `missing or broken icons fall back to the bolt`() {
        assertSame(GcIcons.Bolt, QuickActionIcons.vector(null))
        assertSame(GcIcons.Bolt, QuickActionIcons.vector("hello"))
        assertSame(GcIcons.Bolt, QuickActionIcons.vector("M"))
        assertNull(QuickActionIcons.nodes("M"))
    }

    @Test
    fun `sanitize keeps the icon of an action, pins from before 1_32 still load`() {
        val items = QuickActionRepository.sanitize(
            listOf(
                QuickAction(plugin = "gatecontrol-smarthome", id = "light:1", title = "Licht", kind = "toggle", state = "on", icon = bulb),
                QuickAction(plugin = "gatecontrol-smarthome", id = "light:2", title = "Flur", icon = "<script>"),
            ),
        )
        assertEquals(bulb, items[0].icon)
        assertNull(items[1].icon)
        val old = """[{"plugin":"p","pluginName":"P","id":"a","title":"A","toggle":false,"confirm":false}]"""
        val pins = QuickActionRepository.decodePins(old)
        assertEquals(1, pins.size)
        assertNull(pins[0].icon)
        val round = QuickActionRepository.decodePins(QuickActionRepository.encodePins(items))
        assertEquals(bulb, round[0].icon)
    }
}
