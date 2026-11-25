package com.gatecontrol.android.media

import com.gatecontrol.android.media.server.MediaHomeProbe
import java.util.Locale

/**
 * The additional confirmation of the home network (Netz-Regel): once GateControl's own home-network check says „at
 * home“, the first local network on which one of the plugin's probes answers (Jellyfin's `Id`, the NAS's
 * certificate) — the network media are bound to. Only over the Wi-Fi / Ethernet networks themselves (the caller binds
 * each probe to its network), never the tunnel. A probe never makes „home“ on its own. Pure, unit-tested.
 */
object MediaHomeCheck {
    suspend fun <N> first(networks: List<N>, probes: List<MediaHomeProbe>, probe: suspend (N, MediaHomeProbe) -> Boolean): N? {
        if (probes.isEmpty()) return null
        for (n in networks) {
            for (p in probes) if (probe(n, p)) return n
        }
        return null
    }

    /** Jellyfin's `Id` is the server's (upper or lower case). */
    fun sameId(answer: String?, serverId: String): Boolean =
        answer != null && answer.lowercase(Locale.ROOT).filter { it != '-' } == serverId.lowercase(Locale.ROOT).filter { it != '-' }
}
