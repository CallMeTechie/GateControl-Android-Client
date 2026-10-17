package com.gatecontrol.android.navigation

import com.gatecontrol.android.familie.PluginAvailability
import com.gatecontrol.android.push.PushNavTarget
import com.gatecontrol.android.ui.network.NetworkTabs
import com.gatecontrol.android.ui.settings.SettingsPane
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 1.35: Remote is a segment of Netzwerk, the settings open from the profile (no tabs of their own). */
class MainTabsTest {

    private val familie = PluginAvailability(familieTab = true)

    @Test
    fun `the bar is Start, Netzwerk and Familie, no Remote and no settings`() {
        assertEquals(
            listOf(Screen.Vpn.route, Screen.Network.route, Screen.Familie.route),
            MainTabs.routes(network = true, plugins = familie),
        )
        assertEquals(listOf(Screen.Vpn.route, Screen.Network.route), MainTabs.routes(network = true, plugins = PluginAvailability.NONE))
        assertEquals(listOf(Screen.Vpn.route), MainTabs.routes(network = false, plugins = PluginAvailability.NONE))
        listOf(true, false).forEach { network ->
            val tabs = MainTabs.routes(network = network, plugins = familie)
            assertFalse(Screen.Rdp.route in tabs)
            assertFalse(Screen.Settings.route in tabs)
        }
        assertFalse(Screen.Rdp.route in MainTabs.BAR_ROUTES)
        assertFalse(Screen.Settings.route in MainTabs.BAR_ROUTES)
        assertFalse(Screen.Profile.route in MainTabs.BAR_ROUTES)
        assertTrue(Screen.Network.route in MainTabs.BAR_ROUTES)
    }

    @Test
    fun `Chats comes right after Start when the server offers the messenger`() {
        val chats = PluginAvailability(familieTab = true, messengerTab = true)
        assertEquals(
            listOf(Screen.Vpn.route, Screen.Chats.route, Screen.Network.route, Screen.Familie.route),
            MainTabs.routes(network = true, plugins = chats),
        )
        assertEquals(listOf(Screen.Vpn.route, Screen.Chats.route), MainTabs.routes(network = false, plugins = PluginAvailability(messengerTab = true)))
        assertTrue(Screen.Chats.route in MainTabs.BAR_ROUTES)
        assertFalse(Screen.ChatConversation.route in MainTabs.BAR_ROUTES)
    }

    @Test
    fun `the messenger's tab follows the plugin list`() {
        fun plugin(id: String, tab: Boolean) = com.gatecontrol.android.network.ClientPlugin(id = id, tab = if (tab) com.gatecontrol.android.network.ClientPluginTab("Chats", "M4 5h16v11H9l-5 4z") else null)
        assertTrue(PluginAvailability.from(listOf(plugin("gatecontrol-messenger", true))).messengerTab)
        assertFalse(PluginAvailability.from(listOf(plugin("gatecontrol-messenger", false))).messengerTab, "listed without a tab (e.g. kiosk device)")
        assertFalse(PluginAvailability.from(emptyList()).messengerTab, "older servers")
    }

    @Test
    fun `chat routes`() {
        assertEquals("chats", Screen.Chats.create())
        assertEquals("chats?conv=12", Screen.Chats.create(conv = 12))
        assertEquals("chats?conv=12&share=true", Screen.Chats.create(conv = 12, share = true))
        assertEquals("chats/c/12", Screen.ChatConversation.create(12))
        assertEquals("chats/c/12?share=true", Screen.ChatConversation.create(12, share = true))
        assertEquals("chats/new?group=true", Screen.ChatNew.create(group = true))
        assertEquals("chats/info/12", Screen.ChatInfo.create(12))
    }

    @Test
    fun `the Netzwerk tab exists for any of its segments, Remote included`() {
        assertTrue(MainTabs.hasNetwork(services = false, pihole = false, rdp = true))
        assertTrue(MainTabs.hasNetwork(services = true, pihole = false, rdp = false))
        assertTrue(MainTabs.hasNetwork(services = false, pihole = true, rdp = false))
        assertFalse(MainTabs.hasNetwork(services = false, pihole = false, rdp = false))
    }

    @Test
    fun `Netzwerk has Remote as third segment next to Dienste and Pi-hole`() {
        assertEquals(
            listOf(NetworkTabs.SERVICES, NetworkTabs.PIHOLE, NetworkTabs.REMOTE),
            NetworkTabs.available(services = true, pihole = true, remote = true),
        )
        assertEquals(listOf(NetworkTabs.REMOTE), NetworkTabs.available(services = false, pihole = false, remote = true))
        assertEquals(listOf(NetworkTabs.SERVICES), NetworkTabs.available(services = true, pihole = false, remote = false))
    }

    @Test
    fun `the old Remote destination leads to Netzwerk on Remote`() {
        assertEquals("network?tab=remote", MainTabs.redirect(Screen.Rdp.route))
        assertEquals(Screen.Network.create(NetworkTabs.REMOTE), MainTabs.redirect("rdp"))
        // Current routes stay as they are (the settings keep their route, now with back).
        assertNull(MainTabs.redirect(Screen.Settings.route))
        assertNull(MainTabs.redirect(Screen.Vpn.route))
        val all = NetworkTabs.available(services = true, pihole = true, remote = true)
        assertEquals(NetworkTabs.REMOTE, NetworkTabs.initial("rdp", all))
        assertEquals(NetworkTabs.REMOTE, NetworkTabs.initial("remote", all))
        assertEquals(NetworkTabs.PIHOLE, NetworkTabs.initial("pihole", all))
        // Not allowed (any more) or unknown: the first segment.
        assertEquals(NetworkTabs.SERVICES, NetworkTabs.initial("remote", listOf(NetworkTabs.SERVICES)))
        assertEquals(NetworkTabs.SERVICES, NetworkTabs.initial(null, all))
        assertNull(NetworkTabs.initial("remote", emptyList()))
    }

    @Test
    fun `pushes to the RDP hosts open the Remote segment`() {
        assertEquals(PushNavTarget.Remote, PushNavTarget.forRoute("rdp", 5))
        assertEquals(PushNavTarget.Remote, PushNavTarget.forRoute("remote", 5))
        assertEquals(PushNavTarget.Services, PushNavTarget.forRoute("services", 5))
    }

    @Test
    fun `settings links keep working as routes of a normal destination`() {
        assertEquals("settings", Screen.Settings.create())
        assertEquals("settings?pane=notifications", Screen.Settings.create(SettingsPane.Notifications.key))
        assertEquals(SettingsPane.Connection, SettingsPane.fromKey("connection"))
        assertEquals(SettingsPane.Notifications, SettingsPane.fromKey(SettingsPane.Notifications.key))
        // The profile, the way into the settings, is its own destination.
        assertEquals("settings/profile", Screen.Profile.route)
        assertEquals("info-card", Screen.InfoCardPicker.route)
    }
}
