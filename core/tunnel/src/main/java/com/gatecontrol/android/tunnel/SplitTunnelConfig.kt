package com.gatecontrol.android.tunnel

import com.gatecontrol.android.common.SplitTunnelMode

/**
 * Resolved split-tunnel configuration passed to TunnelManager.
 * Contains only the data needed for WireGuard config generation.
 */
data class SplitTunnelConfig(
    val mode: SplitTunnelMode = SplitTunnelMode.OFF,
    val networks: List<String> = emptyList(),  // CIDRs only (labels stripped)
    val apps: List<String> = emptyList(),      // package names only
)
