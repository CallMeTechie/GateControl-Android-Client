package com.gatecontrol.android.navigation

import com.gatecontrol.android.familie.PluginAvailability

/**
 * Entries of the bottom bar / navigation rail (pure, unit-tested). Of the
 * plugins only Familie gets a tab; the door plugin gets none (its screen
 * opens from pushes and its quick actions, docs/DOOR.md), so the bar does
 * not fill up.
 */
internal object MainTabs {

    /** Routes that show the bottom bar or the rail. */
    val BAR_ROUTES: Set<String> = setOf(
        Screen.Vpn.route,
        Screen.Rdp.route,
        Screen.Network.route,
        Screen.Familie.route,
        Screen.Settings.route,
    )

    /** Routes of the tabs in their order: built-in ones, Familie, settings last. */
    fun routes(rdp: Boolean, network: Boolean, plugins: PluginAvailability): List<String> = buildList {
        add(Screen.Vpn.route)
        if (rdp) add(Screen.Rdp.route)
        if (network) add(Screen.Network.route)
        if (plugins.familieTab) add(Screen.Familie.route)
        add(Screen.Settings.route)
    }
}
