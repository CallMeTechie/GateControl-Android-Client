package com.gatecontrol.android.tunnel

/**
 * Resolved split-tunnel configuration passed to TunnelManager.
 * Contains only the data needed for WireGuard config generation.
 */
data class SplitTunnelConfig(
    val mode: String = "off",        // "off" | "exclude" | "include"
    val networks: List<String> = emptyList(),  // CIDRs only (labels stripped)
    val apps: List<String> = emptyList(),      // package names only
)
