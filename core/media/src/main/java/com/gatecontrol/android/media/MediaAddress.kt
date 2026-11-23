package com.gatecontrol.android.media

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/**
 * The address of a media server as typed at the TV or found in the home network: `192.168.1.20`,
 * `192.168.1.20:8096`, `http://nas.local:8096/jellyfin`. Pure, unit-tested.
 *
 * Plain HTTP only in the home network (a private IPv4 address, a `.local` / `.lan` / `.home.arpa` name or a name
 * without dots): Jellyfin runs on HTTP there, and away the connection goes through the WireGuard tunnel. Any other
 * host needs HTTPS — the access token never goes unencrypted over the internet.
 */
object MediaAddress {
    /** Jellyfin's HTTP port. */
    const val JELLYFIN_HTTP_PORT = 8096

    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".fritz.box", ".internal")

    /** The base URL without a trailing slash, or null when [input] is not a usable address. */
    fun normalize(input: String, defaultPort: Int = JELLYFIN_HTTP_PORT): String? {
        val raw = input.trim().trimEnd('/')
        if (raw.isEmpty() || raw.length > 512 || raw.any { it.isWhitespace() || it.isISOControl() }) return null
        val withScheme = if (raw.contains("://")) raw else "http://$raw"
        val url = withScheme.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
        val explicitPort = Regex("^[a-zA-Z]+://[^/]+:(\\d+)").containsMatchIn(withScheme)
        val port = if (explicitPort || raw.contains("://")) url.port else defaultPort
        if (url.scheme == "http" && !isHomeHost(url.host)) return null
        val rebuilt = HttpUrl.Builder().scheme(url.scheme).host(url.host).port(port).encodedPath(url.encodedPath).build()
        return rebuilt.toString().trimEnd('/')
    }

    /** A host of the home network: private IPv4, a local name or a single label ("nas"). */
    fun isHomeHost(host: String): Boolean {
        val h = host.lowercase(Locale.ROOT).trimEnd('.')
        if (isPrivateIpv4(h)) return true
        if (h.isEmpty() || h.contains(':')) return false
        if (h.all { it.isDigit() || it == '.' }) return false
        if (!h.contains('.')) return true
        return LOCAL_SUFFIXES.any { h.endsWith(it) }
    }

    /** The host of a base URL ("192.168.1.20"); null when it is not one. */
    fun host(baseUrl: String): String? = baseUrl.toHttpUrlOrNull()?.host

    /** 10/8, 172.16/12, 192.168/16, strict dotted form. */
    fun isPrivateIpv4(address: String): Boolean {
        val parts = address.split('.')
        if (parts.size != 4) return false
        val o = parts.map { p ->
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' } || (p.length > 1 && p[0] == '0')) return false
            p.toInt().takeIf { it <= 255 } ?: return false
        }
        return when (o[0]) {
            10 -> true
            172 -> o[1] in 16..31
            192 -> o[1] == 168
            else -> false
        }
    }
}
