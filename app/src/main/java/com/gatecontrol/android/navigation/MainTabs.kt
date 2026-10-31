package com.gatecontrol.android.navigation

import com.gatecontrol.android.familie.PluginAvailability
import com.gatecontrol.android.layout.StartPages
import com.gatecontrol.android.ui.network.NetworkTabs

/**
 * Entries of the bottom bar / navigation rail (pure, unit-tested): Start,
 * Chats (messenger plugin), Mail (mail plugin), Vault (vault plugin), Drive (drive plugin), Netzwerk (Dienste, Pi-hole and Remote as its
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
        Screen.Mail.route,
        Screen.Vault.route,
        Screen.Drive.route,
    )

    /** Routes of the tabs in their order: Start, Chats, Mail, Vault, Drive, Netzwerk, Familie. */
    fun routes(network: Boolean, plugins: PluginAvailability): List<String> = buildList {
        add(Screen.Vpn.route)
        if (plugins.messengerTab) add(Screen.Chats.route)
        if (plugins.mail) add(Screen.Mail.route)
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

    /** The page id ([StartPages]) of a tab's route (the route pattern); null for Start. */
    fun pageOf(route: String): String? = when (route) {
        Screen.Chats.route -> StartPages.CHATS
        Screen.Mail.route -> StartPages.MAIL
        Screen.Vault.route -> StartPages.VAULT
        Screen.Drive.route -> StartPages.DRIVE
        Screen.Network.route -> StartPages.NETWORK
        Screen.Familie.route -> StartPages.FAMILIE
        else -> null
    }

    /** The tab's route pattern of a function page (the reverse of [pageOf]). */
    fun routeOf(page: String): String? = when (page) {
        StartPages.CHATS -> Screen.Chats.route
        StartPages.MAIL -> Screen.Mail.route
        StartPages.VAULT -> Screen.Vault.route
        StartPages.DRIVE -> Screen.Drive.route
        StartPages.NETWORK -> Screen.Network.route
        StartPages.FAMILIE -> Screen.Familie.route
        else -> null
    }

    /**
     * The page a route to navigate to (with its arguments, e.g.
     * `network?tab=remote`) shows when its function is pinned to Start;
     * null for Start and for the screens on top of a tab (`chats/c/7`).
     */
    fun pageFor(route: String): String? = when (route.substringBefore('?')) {
        Screen.Chats.BASE -> StartPages.CHATS
        Screen.Mail.route -> StartPages.MAIL
        Screen.Vault.route -> StartPages.VAULT
        Screen.Drive.route -> StartPages.DRIVE
        Screen.Network.BASE -> StartPages.NETWORK
        Screen.Familie.BASE -> StartPages.FAMILIE
        else -> null
    }

    /** The argument [name] of a route to navigate to (`familie?dest=…` → its dest, decoded), null when it has none. */
    fun arg(route: String, name: String): String? {
        val query = route.substringAfter('?', "")
        if (query.isEmpty()) return null
        return query.split('&').firstNotNullOfOrNull { part ->
            val key = part.substringBefore('=')
            if (key == name) java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8").takeIf { it.isNotEmpty() } else null
        }
    }

    /**
     * The functions of the tab [routes] that can be pinned to Start (all but Start), and Mail's pages without a tab
     * ([mailPages]: calendar, contacts — only with Mail) right after Mail.
     */
    fun features(routes: List<String>, mailPages: Collection<String> = emptyList()): List<String> = routes.mapNotNull(::pageOf).flatMap { page ->
        if (page == StartPages.MAIL) listOf(page) + StartPages.PAGE_ONLY.filter { it in mailPages }.sortedBy { StartPages.FEATURES.indexOf(it) } else listOf(page)
    }

    /**
     * Mail's pages without a tab the server offers ([MMe] features `calendar`, `contacts`); [lenient]: while `me` is
     * not known yet, both (a pinned page does not vanish for a moment at start).
     */
    fun mailPages(me: com.gatecontrol.android.mail.MMe?, lenient: Boolean = false): List<String> = when {
        me == null -> if (lenient) listOf(StartPages.CALENDAR, StartPages.CONTACTS) else emptyList()
        else -> listOfNotNull(
            StartPages.CALENDAR.takeIf { me.has(com.gatecontrol.android.mail.MMe.FEATURE_CALENDAR) },
            StartPages.CONTACTS.takeIf { me.has(com.gatecontrol.android.mail.MMe.FEATURE_CONTACTS) },
        )
    }

    /** The tabs left in the bar: [routes] without the functions pinned to Start ([pinned] page ids); Start always stays. */
    fun visible(routes: List<String>, pinned: Collection<String>): List<String> =
        routes.filter { route -> pageOf(route)?.let { it in pinned } != true }

    /**
     * Whether the bar (or the rail) is left out: only when the person wants
     * that ([hideWhenOnlyStart], "Leiste ausblenden, wenn nur Start
     * bleibt"), only Start is left as a tab and there are pages beside it
     * to reach by swiping (else nothing changes for those without pages).
     */
    fun hideBar(visibleTabs: List<String>, pages: List<String>, hideWhenOnlyStart: Boolean): Boolean =
        hideWhenOnlyStart && visibleTabs.size <= 1 && pages.isNotEmpty()
}
