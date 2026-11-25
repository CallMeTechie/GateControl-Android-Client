package com.gatecontrol.android.media.server

import com.gatecontrol.android.media.MediaLogic
import com.gatecontrol.android.media.MediaRoute
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind

/** How a source of the server stands on this device (Medien › Quellen, a display only). */
data class MediaSourceStatus(
    val source: MediaSource,
    /** The other half of a share (SMB ↔ WebDAV). */
    val partner: MediaSource?,
    val route: MediaRoute,
    /** Signing in at Jellyfin right now. */
    val pairing: Boolean,
    /** What is wrong (a code of the plugin or of the sign-in); null: ready. */
    val problem: String?,
) {
    val ready: Boolean get() = problem == null && !pairing && (source.kind.share || source.token != null)
}

/** Pure: the rows of Medien › Quellen (phone and TV) from the store and the server's state. Unit-tested. */
object MediaSourcesLogic {
    fun rows(list: List<MediaSource>, state: MediaServerState, route: (MediaSource) -> MediaRoute): List<MediaSourceStatus> {
        val shown = list.filter { it.kind == MediaSourceKind.JELLYFIN } + MediaLogic.shares(list)
        return shown.map { s ->
            val partner = s.pairWith?.let { id -> list.firstOrNull { it.id == id } }
            val pairing = s.id in state.pairing
            val problem = when {
                s.kind == MediaSourceKind.JELLYFIN && s.problem != null -> s.problem
                s.kind == MediaSourceKind.JELLYFIN && s.token == null && !pairing -> state.pairProblems[s.id] ?: if (s.managed) null else MediaSourcesText.SIGNED_OUT
                s.kind.share && s.managed && s.password == null && listOfNotNull(s, partner).all { it.password == null } -> MediaSourcesText.NO_PASSWORD
                else -> null
            }
            MediaSourceStatus(s, partner, route(s), pairing, problem)
        }
    }
}

/** Codes of the device itself beside the plugin's (`no_mapping`, `quick_connect_off`, …). */
object MediaSourcesText {
    const val SIGNED_OUT = "signed_out"
    const val NO_PASSWORD = "no_password"

    /** The plugin is off, uninstalled or without licence (a sign-in cannot be asked for). */
    const val UNAVAILABLE = "plugin_unavailable"

    /** „vor 3 Min.“: minutes (below an hour), hours (below a day) or days since [at]; null: never. */
    fun ago(now: Long, at: Long): Pair<Char, Long>? {
        if (at <= 0) return null
        val m = ((now - at).coerceAtLeast(0)) / 60_000
        return when {
            m < 1 -> 'n' to 0L
            m < 60 -> 'm' to m
            m < 24 * 60 -> 'h' to m / 60
            else -> 'd' to m / (24 * 60)
        }
    }
}

