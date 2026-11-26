package com.gatecontrol.android.media.server

import java.net.URI
import java.util.Locale

/**
 * The app gets another GateControl server or token (Einstellungen › Server, the setup again): Medien signs this device
 * out of Jellyfin through the plugin (`DELETE v1/jellyfin/device`) while the old token still counts, and forgets the
 * old server's sources once the switch went through. Bound to [MediaServerSources]; [NONE] where Medien is not used.
 * Never blocks the switch: [leaving] gives up after a few seconds, a refusal is ignored.
 */
interface MediaServerSwitch {
    /** Before the new access data are stored. True when this device was signed out of Jellyfin (it had a Jellyfin source). */
    suspend fun leaving(): Boolean

    /**
     * The switch went through ([ok]) or the old setup was restored. [sameServer]: the same server with another token.
     * [signedOut]: what [leaving] gave.
     */
    fun switched(ok: Boolean, sameServer: Boolean, signedOut: Boolean)

    companion object {
        /** Nothing to do (tests, an app without Medien). */
        val NONE: MediaServerSwitch = object : MediaServerSwitch {
            override suspend fun leaving() = false
            override fun switched(ok: Boolean, sameServer: Boolean, signedOut: Boolean) = Unit
        }

        /** The new access data are other ones than those stored (a server set up before). Pure, unit-tested. */
        fun changes(oldUrl: String, oldToken: String, newUrl: String, newToken: String): Boolean =
            oldUrl.isNotBlank() && oldToken.isNotBlank() && (!sameServer(oldUrl, newUrl) || oldToken != newToken)

        /** [a] and [b] name the same server (scheme, host and port; a trailing slash or the case of the host aside). */
        fun sameServer(a: String, b: String): Boolean {
            val x = key(a) ?: return a.trim().trimEnd('/') == b.trim().trimEnd('/')
            return x == key(b)
        }

        private fun key(url: String): String? = runCatching {
            val u = URI(url.trim())
            val host = u.host?.lowercase(Locale.ROOT) ?: return null
            val scheme = u.scheme?.lowercase(Locale.ROOT) ?: return null
            val port = if (u.port > 0) u.port else if (scheme == "http") 80 else 443
            "$scheme://$host:$port${u.path.orEmpty().trimEnd('/')}"
        }.getOrNull()
    }
}
