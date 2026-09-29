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
    data object Settings : Screen("settings")
    data object SettingsServer : Screen("settings/server")
    data object SplitTunnel : Screen("settings/split")
    data object Logs : Screen("settings/logs")
    data object QrScanner : Screen("setup/qr")
    /** Setup screen opened from Settings to connect an existing install (scan starts right away). */
    data object Enroll : Screen("setup/enroll?link={link}") {
        const val ARG_LINK = "link"
        fun create(link: String? = null): String =
            if (link == null) "setup/enroll" else "setup/enroll?link=" + android.net.Uri.encode(link)
    }
}
