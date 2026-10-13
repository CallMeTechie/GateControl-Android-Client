package com.gatecontrol.android.ui.settings

/**
 * Pages reachable from the settings list. On wide screens one of them is
 * shown next to the list (AppNavigation), on phones each is its own route.
 */
enum class SettingsPane(val key: String) {
    Server("server"),
    SplitTunnel("split"),
    TrustedNetworks("trusted-networks"),
    Location("location"),
    Notifications("notifications"),
    Logs("logs"),
    Diagnosis("diagnosis"),
    ;

    companion object {
        fun fromKey(key: String?): SettingsPane? = entries.firstOrNull { it.key == key }
    }
}
