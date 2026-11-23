package com.gatecontrol.android.tv.tunnel

import com.gatecontrol.android.common.SplitTunnelMode
import com.gatecontrol.android.tunnel.SplitTunnelConfig

/**
 * "Geteilter Tunnel" of the TV: which other apps on the stick use the tunnel (Einstellungen › Verbindung).
 *
 * - [SplitTunnelMode.OFF]: every app (default, as before),
 * - [SplitTunnelMode.INCLUDE]: only the chosen apps — GateControl TV itself always (cameras, the home network),
 * - [SplitTunnelMode.EXCLUDE]: every app but the chosen ones (e.g. streaming apps go direct).
 *
 * The routes stay the server's (AllowedIPs): only the apps are chosen here.
 */
data class TvSplitTunnelSettings(
    val mode: SplitTunnelMode = SplitTunnelMode.OFF,
    val apps: Set<String> = emptySet(),
)

/** Pure, unit-tested. */
object TvSplitTunnel {
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    const val MAX_APPS = 100

    fun isPackageName(name: String): Boolean = name.length <= 255 && PACKAGE.matches(name)

    /**
     * The tunnel's split config for [settings]: [ownPackage] is never excluded and always included; [allowedIps]
     * (the config's `AllowedIPs`) keep the routes in the include mode, where core/tunnel only routes the networks
     * named. No usable app left: the whole tunnel.
     */
    fun config(settings: TvSplitTunnelSettings, ownPackage: String, allowedIps: String): SplitTunnelConfig {
        val apps = settings.apps.filter(::isPackageName).filter { it != ownPackage }.sorted().take(MAX_APPS)
        return when (settings.mode) {
            SplitTunnelMode.OFF -> SplitTunnelConfig()
            SplitTunnelMode.EXCLUDE ->
                if (apps.isEmpty()) SplitTunnelConfig() else SplitTunnelConfig(SplitTunnelMode.EXCLUDE, emptyList(), apps)
            SplitTunnelMode.INCLUDE -> {
                if (apps.isEmpty()) return SplitTunnelConfig()
                val networks = allowedIps.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (networks.isEmpty()) return SplitTunnelConfig()
                SplitTunnelConfig(SplitTunnelMode.INCLUDE, networks, (apps + ownPackage).sorted())
            }
        }
    }
}
