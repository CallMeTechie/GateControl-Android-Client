package com.gatecontrol.android.ui.settings

/**
 * Pages reachable from the settings list. On wide screens one of them is
 * shown next to the list (AppNavigation), on phones each is its own route.
 */
enum class SettingsPane(val key: String) {
    /** Own profile: picture and ring colour (core 1.168+), "Anwesenheit teilen" (Familie presence). */
    Profile("profile"),
    /** Tunnel details (until 1.31 a card on the start tab). */
    Connection("connection"),
    Server("server"),
    SplitTunnel("split"),
    TrustedNetworks("trusted-networks"),
    Location("location"),
    Notifications("notifications"),
    /** GateDrop "Geteilt" (only while the server offers GateDrop). */
    Shared("shared"),
    Logs("logs"),
    Diagnosis("diagnosis"),
    ;

    companion object {
        fun fromKey(key: String?): SettingsPane? = entries.firstOrNull { it.key == key }
    }
}
