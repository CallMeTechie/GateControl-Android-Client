package com.gatecontrol.android.common

/**
 * Split-tunnel routing mode. [wire] is the value stored in the settings and
 * sent by the server's split-tunnel preset API.
 */
enum class SplitTunnelMode(val wire: String) {
    /** Full tunnel — the config's own AllowedIPs, no app filtering. */
    OFF("off"),

    /** Everything through the VPN except the listed networks/apps. */
    EXCLUDE("exclude"),

    /** Only the listed networks/apps go through the VPN. */
    INCLUDE("include");

    companion object {
        /** Unknown or missing values fall back to [OFF] (full tunnel). */
        fun fromWire(value: String?): SplitTunnelMode =
            entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) } ?: OFF
    }
}
