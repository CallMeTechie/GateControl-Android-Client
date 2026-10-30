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

    @Test
    fun `tabs and pages beside Start name each other`() {
        val all = PluginAvailability(familieTab = true, messengerTab = true, vault = true, drive = true)
        val routes = MainTabs.routes(network = true, plugins = all)
        assertEquals(listOf("chats", "vault", "drive", "network", "familie"), MainTabs.features(routes))
        routes.drop(1).forEach { assertEquals(it, MainTabs.routeOf(MainTabs.pageOf(it)!!)) }
        assertNull(MainTabs.pageOf(Screen.Vpn.route))
        assertNull(MainTabs.routeOf("w2"))
    }

    @Test
    fun `routes to navigate to find their page`() {
        assertEquals("network", MainTabs.pageFor(Screen.Network.create(NetworkTabs.REMOTE)))
        assertEquals("network", MainTabs.pageFor(Screen.Network.create()))
        assertEquals("chats", MainTabs.pageFor(Screen.Chats.create(conv = 7, share = true)))
        assertEquals("familie", MainTabs.pageFor("familie?dest=tasks"))
        assertEquals("vault", MainTabs.pageFor(Screen.Vault.route))
        assertEquals("drive", MainTabs.pageFor(Screen.Drive.route))
        // Screens on top of a tab and Start have no page.
        assertNull(MainTabs.pageFor(Screen.ChatConversation.create(7)))
        assertNull(MainTabs.pageFor("familie/page?dest=tasks"))
        assertNull(MainTabs.pageFor(Screen.DriveTrash.route))
        assertNull(MainTabs.pageFor(Screen.Vpn.route))
        // The arguments go along.
        assertEquals("remote", MainTabs.arg(Screen.Network.create(NetworkTabs.REMOTE), Screen.Network.ARG_TAB))
        assertEquals("7", MainTabs.arg(Screen.Chats.create(conv = 7, share = true), Screen.Chats.ARG_CONV))
        assertEquals("true", MainTabs.arg(Screen.Chats.create(conv = 7, share = true), Screen.Chats.ARG_SHARE))
        assertEquals("ride:12 a", MainTabs.arg("familie?dest=ride%3A12%20a", Screen.Familie.ARG_DEST))
        assertNull(MainTabs.arg(Screen.Network.create(), Screen.Network.ARG_TAB))
        assertNull(MainTabs.arg("network?tab=", Screen.Network.ARG_TAB))
    }

    @Test
    fun `pinned functions leave the bar, Start stays`() {
        val routes = MainTabs.routes(network = true, plugins = PluginAvailability(familieTab = true, messengerTab = true))
        assertEquals(listOf(Screen.Vpn.route, Screen.Chats.route), MainTabs.visible(routes, listOf("network", "familie", "w2")))
        assertEquals(listOf(Screen.Vpn.route), MainTabs.visible(routes, listOf("chats", "network", "familie")))
        assertEquals(routes, MainTabs.visible(routes, emptyList()))
    }

    @Test
    fun `the bar goes only when everything is a page and the person wants that`() {
        val onlyStart = listOf(Screen.Vpn.route)
        assertTrue(MainTabs.hideBar(onlyStart, pages = listOf("network"), hideWhenOnlyStart = true))
        assertFalse(MainTabs.hideBar(onlyStart, pages = listOf("network"), hideWhenOnlyStart = false))
        // Nothing pinned (a login with Start only): the bar stays as before.
        assertFalse(MainTabs.hideBar(onlyStart, pages = emptyList(), hideWhenOnlyStart = true))
        assertFalse(MainTabs.hideBar(listOf(Screen.Vpn.route, Screen.Chats.route), pages = listOf("network"), hideWhenOnlyStart = true))
    }

    @Test
    fun `Mail comes after Chats, only with its tab, and can be a page beside Start`() {
        fun plugin(id: String, tab: Boolean) = com.gatecontrol.android.network.ClientPlugin(id = id, tab = if (tab) com.gatecontrol.android.network.ClientPluginTab("Mail", "M3 5h18v14H3z") else null)
        assertTrue(PluginAvailability.from(listOf(plugin("gatecontrol-mail", true))).mail)
        assertFalse(PluginAvailability.from(listOf(plugin("gatecontrol-mail", false))).mail, "listed without its tab: the person is not allowed")
        assertFalse(PluginAvailability.from(emptyList()).mail, "no plugin: nothing changes")
        val all = PluginAvailability(familieTab = true, messengerTab = true, mail = true, vault = true, drive = true)
        assertEquals(
            listOf(Screen.Vpn.route, Screen.Chats.route, Screen.Mail.route, Screen.Vault.route, Screen.Drive.route, Screen.Network.route, Screen.Familie.route),
            MainTabs.routes(network = true, plugins = all),
        )
        assertFalse(Screen.Mail.route in MainTabs.routes(network = true, plugins = all.copy(mail = false)))
        assertTrue(Screen.Mail.route in MainTabs.BAR_ROUTES)
        assertFalse(Screen.MailMessage.route in MainTabs.BAR_ROUTES)
        assertEquals(com.gatecontrol.android.layout.StartPages.MAIL, MainTabs.pageOf(Screen.Mail.route))
        assertEquals(Screen.Mail.route, MainTabs.routeOf(com.gatecontrol.android.layout.StartPages.MAIL))
        assertEquals(com.gatecontrol.android.layout.StartPages.MAIL, MainTabs.pageFor(Screen.Mail.route))
        assertNull(MainTabs.pageFor(Screen.MailMessage.create(4)), "a mail lies on top of the tab")
        assertTrue(com.gatecontrol.android.layout.StartPages.isFeature("mail"))
        assertEquals(listOf(Screen.Vpn.route, Screen.Chats.route), MainTabs.visible(listOf(Screen.Vpn.route, Screen.Chats.route, Screen.Mail.route), listOf("mail")))
    }

    @Test
    fun `mail routes`() {
        assertEquals("mail/m/42", Screen.MailMessage.create(42))
        assertEquals("mail/compose", Screen.MailCompose.create())
        assertEquals("mail/compose?ref=42&mode=replyAll", Screen.MailCompose.create(ref = 42, mode = "replyAll"))
        assertEquals("mail/compose?draft=9", Screen.MailCompose.create(draft = 9))
        assertEquals("mail/compose?account=3&to=a%40b.de", Screen.MailCompose.create(account = 3, to = "a@b.de"))
        assertEquals("mail/compose?ref=1&mode=reply&reply=Danke%2C%20gern%21", Screen.MailCompose.create(ref = 1, mode = "reply", reply = "Danke, gern!"))
        assertEquals("mail/accounts/add", Screen.MailAddAccount.create())
        assertEquals("mail/accounts/add?account=3", Screen.MailAddAccount.create(3))
        assertEquals("mail/accounts/3", Screen.MailAccount.create(3))
        assertEquals("mail/sender?email=a%40b.de&name=Anna%20M%C3%BCller&account=3", Screen.MailSender.create("a@b.de", "Anna Müller", 3))
        assertEquals("mail/sender?email=a%40b.de", Screen.MailSender.create("a@b.de", " ", null))
    }
}
