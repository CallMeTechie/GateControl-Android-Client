package com.gatecontrol.android.navigation

sealed class Screen(val route: String) {
    data object Setup : Screen("setup")
    data object Vpn : Screen("vpn")
    data object Rdp : Screen("rdp")
    /** Services + Pi-hole; `tab` picks the initial segment. */
    data object Network : Screen("network?tab={tab}") {
        const val ARG_TAB = "tab"
        const val BASE = "network"
        fun create(tab: String? = null): String = if (tab == null) BASE else "$BASE?tab=$tab"
    }
    /** `pane` picks the page shown next to the list on wide screens (SettingsPane.key). */
    data object Settings : Screen("settings?pane={pane}") {
        const val ARG_PANE = "pane"
        const val BASE = "settings"
        fun create(pane: String? = null): String = if (pane == null) BASE else "$BASE?pane=$pane"
    }
    /** Notification center: inbox ("Mitteilungen") and one message; `select` opens one beside the list on wide screens. */
    data object Inbox : Screen("inbox?select={select}") {
        const val ARG_SELECT = "select"
        const val BASE = "inbox"
        fun create(select: Long? = null): String = if (select == null) BASE else "$BASE?select=$select"
    }
    data object NotificationDetail : Screen("inbox/{id}") {
        const val ARG_ID = "id"
        fun create(id: Long): String = "inbox/$id"
    }
    /**
     * Tab of the Familie plugin (only when the server lists it with an app
     * tab). `dest` (FamilieDest key) is shown beside the start screen on
     * wide windows.
     */
    data object Familie : Screen("familie?dest={dest}") {
        const val ARG_DEST = "dest"
        const val BASE = "familie"
        fun create(dest: String? = null): String = if (dest == null) BASE else "$BASE?dest=" + android.net.Uri.encode(dest)
    }
    /** A screen of the Familie tab on its own (phones). */
    data object FamiliePage : Screen("familie/page?dest={dest}") {
        const val ARG_DEST = "dest"
        fun create(dest: String): String = "familie/page?dest=" + android.net.Uri.encode(dest)
    }
    /**
     * Door screen "Tür & Kamera" of the door plugin: a normal destination,
     * never a tab, opened by a door push or a long press on a door quick
     * action (docs/DOOR.md). `dest` (DoorDest key) is shown beside its list
     * on wide windows.
     */
    data object Door : Screen("door?dest={dest}") {
        const val ARG_DEST = "dest"
        const val BASE = "door"
        fun create(dest: String? = null): String = if (dest == null) BASE else "$BASE?dest=" + android.net.Uri.encode(dest)
    }
    /** A screen on top of the door screen (phones): history, a ring's detail. */
    data object DoorPage : Screen("door/page?dest={dest}") {
        const val ARG_DEST = "dest"
        fun create(dest: String): String = "door/page?dest=" + android.net.Uri.encode(dest)
    }
    data object SettingsNotifications : Screen("settings/notifications")
    /** Own profile: picture and favourite colour (Settings → Profil, own avatar on the start tab). */
    data object Profile : Screen("settings/profile")
    /** Tunnel details (Settings → Verbindung → Verbindungsdetails). */
    data object ConnectionDetails : Screen("settings/connection")
    data object SettingsServer : Screen("settings/server")
    data object SplitTunnel : Screen("settings/split")
    data object TrustedNetworks : Screen("settings/trusted-networks")
    data object LocationSharing : Screen("settings/location")
    /** Diagnose & Problem melden (Settings › Hilfe, start screen on connection problems). */
    data object Diagnosis : Screen("settings/diagnosis")
    data object QuickActions : Screen("quick-actions")
    data object Logs : Screen("settings/logs")
    data object QrScanner : Screen("setup/qr")
    /** Setup screen opened from Settings to connect an existing install (scan starts right away). */
    data object Enroll : Screen("setup/enroll?link={link}") {
        const val ARG_LINK = "link"
        fun create(link: String? = null): String =
            if (link == null) "setup/enroll" else "setup/enroll?link=" + android.net.Uri.encode(link)
    }
}
