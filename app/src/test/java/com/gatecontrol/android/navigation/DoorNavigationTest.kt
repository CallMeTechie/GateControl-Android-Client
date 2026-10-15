package com.gatecontrol.android.navigation

import com.gatecontrol.android.door.DoorDest
import com.gatecontrol.android.familie.PluginAvailability
import com.gatecontrol.android.network.ClientPlugin
import com.gatecontrol.android.network.ClientPluginTab
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The door plugin has no tab (nor a card): its pushes open the door screen (a normal destination). */
class DoorNavigationTest {

    private val door = ClientPlugin("gatecontrol-door", "Tür & Kamera", "1.0.0", ClientPluginTab("Tür", "M3 21h18"))
    private val familie = ClientPlugin("gatecontrol-familie", "Familie", "1.0.0", ClientPluginTab("Familie", null))

    @Test
    fun `no door tab in the tab list or the bar routes, Familie keeps its tab`() {
        val plugins = PluginAvailability.from(listOf(door, familie))
        assertTrue(plugins.door)
        val tabs = MainTabs.routes(network = true, plugins = plugins)
        assertEquals(listOf(Screen.Vpn.route, Screen.Network.route, Screen.Familie.route), tabs)
        assertFalse(Screen.Door.route in tabs)
        assertFalse(Screen.Door.route in MainTabs.BAR_ROUTES)
        assertFalse(Screen.DoorPage.route in MainTabs.BAR_ROUTES)
        // Only the door plugin: start, nothing more.
        assertEquals(
            listOf(Screen.Vpn.route),
            MainTabs.routes(network = false, plugins = PluginAvailability.from(listOf(door))),
        )
    }

    @Test
    fun `other door pushes open the door screen`() {
        assertEquals(listOf(DoorStep.Main()), DoorNavigation.forPush("home", pluginListed = true, twoPane = false))
        assertEquals(listOf(DoorStep.Main()), DoorNavigation.forPush("home", pluginListed = true, twoPane = true))
        // Unknown key: the door screen as well.
        assertEquals(listOf(DoorStep.Main()), DoorNavigation.forPush("nope", pluginListed = true, twoPane = false))
    }

    @Test
    fun `a ring opens its detail on top of the door screen, beside it on wide windows`() {
        assertEquals(
            listOf(DoorStep.Main(), DoorStep.Page(DoorDest.Event(120))),
            DoorNavigation.forPush("event:120", pluginListed = true, twoPane = false),
        )
        assertEquals(listOf(DoorStep.Main(DoorDest.Event(120))), DoorNavigation.forPush("event:120", pluginListed = true, twoPane = true))
        assertEquals(
            listOf(DoorStep.Main(), DoorStep.Page(DoorDest.History)),
            DoorNavigation.steps(DoorDest.History, twoPane = false),
        )
    }

    @Test
    fun `without the plugin the message itself opens`() {
        assertNull(DoorNavigation.forPush("event:120", pluginListed = false, twoPane = false))
        assertNull(DoorNavigation.forPush("home", pluginListed = false, twoPane = true))
    }
}
