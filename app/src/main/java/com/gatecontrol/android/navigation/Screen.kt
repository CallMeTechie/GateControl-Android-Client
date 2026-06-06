package com.gatecontrol.android.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    data object Setup : Screen("setup")
    data object Vpn : Screen("vpn")
    data object Rdp : Screen("rdp")
    data object Services : Screen("services")
    data object Settings : Screen("settings")
    data object Logs : Screen("settings/logs")
    data object QrScanner : Screen("setup/qr")
    data object NetworkGroups : Screen("settings/network_groups")
    data object NetworkGroupEdit : Screen("settings/network_groups/{groupId}?name={groupName}") {
        fun createRoute(groupId: Long, groupName: String) =
            "settings/network_groups/$groupId?name=${Uri.encode(groupName)}"
    }
}
