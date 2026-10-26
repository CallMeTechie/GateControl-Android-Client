package com.gatecontrol.android.navigation

import com.gatecontrol.android.familie.PluginAvailability
import com.gatecontrol.android.ui.network.NetworkTabs

/**
 * Entries of the bottom bar / navigation rail (pure, unit-tested): Start,
 * Chats (messenger plugin), Vault (vault plugin), Drive (drive plugin), Netzwerk (Dienste, Pi-hole and Remote as its
 * segments) and Familie. The door plugin gets no tab (its screen opens from
 * pushes and its quick actions, docs/DOOR.md). The settings are no tab
 * since 1.35: they open from the profile (gear top right).
 */
internal object MainTabs {

    /** Routes that show the bottom bar or the rail. */
    val BAR_ROUTES: Set<String> = setOf(
        Screen.Vpn.route,
        Screen.Network.route,
        Screen.Familie.route,
        Screen.Chats.route,
        Screen.Vault.route,
        Screen.Drive.route,
    )

    /** Routes of the tabs in their order: Start, Chats, Vault, Drive, Netzwerk, Familie. */
    fun routes(network: Boolean, plugins: PluginAvailability): List<String> = buildList {
        add(Screen.Vpn.route)
        if (plugins.messengerTab) add(Screen.Chats.route)
        if (plugins.vault) add(Screen.Vault.route)
        if (plugins.drive) add(Screen.Drive.route)
        if (network) add(Screen.Network.route)
        if (plugins.familieTab) add(Screen.Familie.route)
    }

    /** The Netzwerk tab exists when any of its segments may be used (Remote included). */
    fun hasNetwork(services: Boolean, pihole: Boolean, rdp: Boolean): Boolean =
        NetworkTabs.available(services = services, pihole = pihole, remote = rdp).isNotEmpty()

    /**
     * Where a route of an older version leads now: the former Remote tab
     * (`rdp`) is the Remote segment of the Netzwerk tab. Null: the route is
     * current (the settings are still `settings`, now a normal destination
     * with back).
     */
    fun redirect(route: String?): String? = when (route) {
        Screen.Rdp.route -> Screen.Network.create(NetworkTabs.REMOTE)
        else -> null
    }
}
