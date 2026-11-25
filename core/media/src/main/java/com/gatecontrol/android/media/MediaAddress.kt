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

    /**
     * A WebDAV folder as typed: `https://192.168.1.20:5006/filme` (without a scheme HTTPS is meant). Plain HTTP only
     * in the home network, as [normalize]; the port stays as given (443 for HTTPS without one).
     */
    fun normalizeWebDav(input: String): String? {
        val raw = input.trim()
        if (raw.isEmpty()) return null
        return normalize(if (raw.contains("://")) raw else "https://$raw")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    /** An SMB share: the server, its port, the share and a folder in it ("" the share itself). */
    data class Smb(val host: String, val port: Int, val share: String, val path: String) {
        /** `smb://nas/filme/Neu` (stored). */
        val url: String get() = "smb://" + host + (if (port != SMB_PORT) ":$port" else "") + "/" + share + (if (path.isNotEmpty()) "/$path" else "")

        /** `\\nas\filme\Neu` (shown, as Windows writes it). */
        val unc: String get() = "\\\\" + host + "\\" + share + (if (path.isNotEmpty()) "\\" + path.replace('/', '\\') else "")
    }

    /** SMB's TCP port. */
    const val SMB_PORT = 445

    private val SMB_HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]{0,252})$")

    /**
     * An SMB share as typed: `\\nas\filme`, `\\nas\filme\Neu`, `smb://192.168.1.20/filme`, `nas/filme`. Only a
     * host of the home network ([isHomeHost]): SMB never goes over the internet (away it goes through the tunnel).
     */
    fun parseSmb(input: String): Smb? {
        var raw = input.trim().replace('\\', '/')
        if (raw.isEmpty() || raw.length > 512 || raw.any { it.isISOControl() }) return null
        raw = when {
            raw.startsWith("smb://", ignoreCase = true) -> raw.substring(6)
            raw.contains("://") -> return null
            else -> raw.trimStart('/')
        }
        val parts = raw.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        if (parts.any { it == "." || it == ".." || it.contains('@') }) return null
        val hostPort = parts[0]
        val host = hostPort.substringBefore(':')
        val port = if (hostPort.contains(':')) hostPort.substringAfter(':').toIntOrNull()?.takeIf { it in 1..65535 } ?: return null else SMB_PORT
        if (!SMB_HOST.matches(host) || !isHomeHost(host)) return null
        val share = parts[1]
        if (share.length > 80 || share.any { it in "\"<>|?*:" }) return null
        val path = parts.drop(2).joinToString("/")
        if (path.any { it in "\"<>|?*:" }) return null
        return Smb(host.lowercase(Locale.ROOT), port, share, path)
    }

    /** A share's address as the store keeps it; null when it is not one of [kind]. */
    fun normalizeShare(kind: MediaSourceKind, input: String): String? = when (kind) {
        MediaSourceKind.SMB -> parseSmb(input)?.url
        MediaSourceKind.WEBDAV -> normalizeWebDav(input)
        MediaSourceKind.JELLYFIN -> normalize(input)
    }

    /** The last folder of a share's address ("filme" of `\\nas\filme` and of `https://nas:5006/filme`), lower case. */
    fun folderName(kind: MediaSourceKind, address: String): String? = when (kind) {
        MediaSourceKind.SMB -> parseSmb(address)?.let { s -> (if (s.path.isNotEmpty()) s.path.substringAfterLast('/') else s.share) }
        MediaSourceKind.WEBDAV -> address.toHttpUrlOrNull()?.pathSegments?.lastOrNull { it.isNotEmpty() }
        MediaSourceKind.JELLYFIN -> null
    }?.lowercase(Locale.ROOT)

    /** The /24 network of a private IPv4 host ("192.168.2.151" → "192.168.2.0/24"), as a hint; null for anything else. */
    fun subnet24(host: String?): String? = host?.takeIf { isPrivateIpv4(it) }?.substringBeforeLast('.')?.let { "$it.0/24" }

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
