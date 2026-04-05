package com.gatecontrol.android.navigation

sealed class Screen(val route: String) {
    data object Setup : Screen("setup")
    data object Vpn : Screen("vpn")
    data object Rdp : Screen("rdp")
    data object Services : Screen("services")
    data object Settings : Screen("settings")
    data object Logs : Screen("settings/logs")
    data object QrScanner : Screen("setup/qr")
}
