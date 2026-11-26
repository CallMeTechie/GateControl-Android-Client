package com.gatecontrol.android.media.server

import com.gatecontrol.android.media.MediaAddress
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.jellyfin.JellyfinJson.bool
import com.gatecontrol.android.media.jellyfin.JellyfinJson.obj
import com.gatecontrol.android.media.jellyfin.JellyfinJson.objects
import com.gatecontrol.android.media.jellyfin.JellyfinJson.str
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.time.Instant

/** The plugin „Medien“ (gatecontrol-medien, App-API v1). */
const val MEDIA_PLUGIN = "gatecontrol-medien"

/** The topic of its silent data pushes (`media.revoke`, `media.cast`). */
const val MEDIA_SIGNAL_TOPIC = "plugin:$MEDIA_PLUGIN:media_signal"

/**
 * A probe of the plugin (`network.home.probes`): only an additional confirmation of GateControl's own home-network
 * check, over the local network (never the tunnel). Only probes that identify the NAS itself are taken (Jellyfin's
 * `Id`, the certificate); a `tcp` probe (an open port) is passed over: any device with that address would pass it.
 */
sealed interface MediaHomeProbe {
    /** `GET url` answers within 1.5 s with `Id == serverId` (Jellyfin's `System/Info/Public`). */
    data class Jellyfin(val url: String, val serverId: String) : MediaHomeProbe

    /** A TLS handshake at `url` shows the certificate with this SHA-256 (hex, lower case). */
    data class Tls(val url: String, val certSha256: String) : MediaHomeProbe
}

/** `network` of `v1/sources`: the rule (home direct, away through the VPN) and how home is recognised. */
data class MediaNetworkRule(
    val rule: String = RULE,
    val vpnRequiredAway: Boolean = true,
    val probes: List<MediaHomeProbe> = emptyList(),
) {
    companion object {
        const val RULE = "home_direct_away_vpn"
    }
}

/** One source of `v1/sources` as the plugin sends it. Unknown fields are passed over. */
data class MediaServerSource(
    val id: String,
    val kind: Kind,
    val name: String,
    /** Jellyfin. */
    val serverId: String? = null,
    val version: String? = null,
    val homeAddress: String? = null,
    val userId: String? = null,
    val userName: String? = null,
    val problem: String? = null,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val paired: Boolean = false,
    val quickConnect: Boolean = true,
    val downloads: Boolean = true,
    val alwaysOriginal: Boolean = false,
    /** Away without VPN (`away.mode = "direct"`): the HTTPS address; null: away only through the VPN. */
    val awayAddress: String? = null,
    /** Away through the VPN (`away.mode = "vpn"` with an address): the route's HTTPS address; null: the home address. */
    val awayVpnAddress: String? = null,
    /** A share. */
    val folder: String? = null,
    val smb: String? = null,
    val webdav: String? = null,
    val certSha256: String? = null,
    val shareUser: String? = null,
    val secretRev: Int = 0,
) {
    enum class Kind { JELLYFIN, SHARE }
}

/** `GET v1/sources`: the list (or [unchanged] since the asked `rev`). */
data class MediaServerList(
    val rev: String,
    val at: Long?,
    val unchanged: Boolean = false,
    val media: Boolean = true,
    val maxOfflineDays: Int = DEFAULT_MAX_OFFLINE_DAYS,
    val offlineHintDays: Int = DEFAULT_OFFLINE_HINT_DAYS,
    val network: MediaNetworkRule = MediaNetworkRule(),
    val cast: Boolean = false,
    val sources: List<MediaServerSource> = emptyList(),
) {
    companion object {
        const val DEFAULT_MAX_OFFLINE_DAYS = 90
        const val DEFAULT_OFFLINE_HINT_DAYS = 30
    }
}

/** A refusal `{ ok: false, code, reason? }` (or no answer at all: [code] [OFFLINE]). */
data class MediaServerError(val http: Int, val code: String, val reason: String? = null) {
    /** Plugin off, uninstalled or without its own licence: Medien hidden, deleted after 24 h (question 37 a). */
    val unavailable: Boolean
        get() = (http == 404 && (reason == "plugin_unavailable" || code == "not_found" || code == "unknown")) || code == "license_required"

    companion object {
        const val OFFLINE = "offline"
    }
}

/** `media.revoke` (silent push): the sources ([sourceIds] null: all) and why. */
data class MediaRevoke(val sourceIds: List<String>?, val reason: String?)

/** `GET v1/sources/{id}/secret`. */
data class MediaShareSecret(val user: String?, val password: String, val rev: Int) {
    override fun toString(): String = "MediaShareSecret(user=$user, rev=$rev)"
}

/**
 * The JSON of the plugin's App-API v1, read by hand like Jellyfin's ([com.gatecontrol.android.media.jellyfin.JellyfinJson]):
 * wrong or unknown fields are left out, never thrown. Addresses go through [MediaAddress] (plain HTTP only in the home
 * network, away only HTTPS). Pure, unit-tested.
 */
object MediaServerJson {
    private val SOURCE_ID = Regex("^[a-z]{1,8}-[A-Za-z0-9_-]{1,40}$")
    private val DEVICE_ID = Regex("^[A-Za-z0-9_.-]{4,64}$")
    private val REV = Regex("^[A-Za-z0-9_.:-]{1,64}$")
    private val HEX = Regex("^[0-9a-f]{64}$")
    private const val MAX_SOURCES = 16
    private const val MAX_PROBES = 8

    fun isSourceId(id: String?): Boolean = id != null && SOURCE_ID.matches(id)

    private fun JsonObject.int(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.let { runCatching { it.asInt }.getOrNull() }

    /** `9c:41:…` or `9C41…` → `9c41…`; null when it is not a SHA-256. */
    fun sha256(text: String?): String? = text?.lowercase()?.filter { it != ':' && !it.isWhitespace() }?.takeIf { HEX.matches(it) }

    fun time(text: String?): Long? = text?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    fun list(o: JsonObject): MediaServerList? {
        if (o.bool("ok") == false) return null
        val rev = o.str("rev")?.takeIf { REV.matches(it) } ?: return null
        val at = time(o.str("at"))
        if (o.bool("unchanged") == true) return MediaServerList(rev = rev, at = at, unchanged = true)
        val media = o.bool("media") != false
        return MediaServerList(
            rev = rev,
            at = at,
            media = media,
            maxOfflineDays = o.int("maxOfflineDays")?.takeIf { it in 1..3650 } ?: MediaServerList.DEFAULT_MAX_OFFLINE_DAYS,
            offlineHintDays = o.int("offlineHintDays")?.takeIf { it in 1..3650 } ?: MediaServerList.DEFAULT_OFFLINE_HINT_DAYS,
            network = network(o.obj("network")),
            cast = o.obj("cast")?.bool("enabled") == true,
            sources = if (!media) emptyList() else o.objects("sources").mapNotNull(::source).distinctBy { it.id }.take(MAX_SOURCES),
        )
    }

    fun network(o: JsonObject?): MediaNetworkRule {
        o ?: return MediaNetworkRule()
        val probes = o.obj("home")?.objects("probes").orEmpty().mapNotNull { p ->
            val url = p.str("url") ?: return@mapNotNull null
            when (p.str("kind")) {
                "jellyfin" -> {
                    val base = MediaAddress.normalize(url) ?: return@mapNotNull null
                    if (!MediaAddress.isHomeHost(MediaAddress.host(base) ?: return@mapNotNull null)) return@mapNotNull null
                    MediaHomeProbe.Jellyfin(url.trim(), p.str("serverId") ?: return@mapNotNull null)
                }
                "tls" -> {
                    if (!url.trim().startsWith("https://")) return@mapNotNull null
                    MediaAddress.normalizeWebDav(url) ?: return@mapNotNull null
                    MediaHomeProbe.Tls(url.trim(), sha256(p.str("certSha256")) ?: return@mapNotNull null)
                }
                else -> null
            }
        }.take(MAX_PROBES)
        return MediaNetworkRule(rule = o.str("rule") ?: MediaNetworkRule.RULE, vpnRequiredAway = o.bool("vpnRequiredAway") != false, probes = probes)
    }

    /**
     * `away: { mode: [mode], address: "https://…" }` → the address (`direct`: without the VPN; `vpn`: the route through
     * the tunnel); another mode or no address (an older plugin's `{ mode: "vpn" }`) → null.
     */
    private fun away(o: JsonObject?, kind: MediaSourceKind, mode: String): String? {
        if (o?.str("mode") != mode) return null
        val a = o.str("address")?.takeIf { it.startsWith("https://") } ?: return null
        return when (kind) {
            MediaSourceKind.JELLYFIN -> MediaAddress.normalize(a)
            else -> MediaAddress.normalizeWebDav(a)
        }?.takeIf { it.startsWith("https://") }
    }

    fun source(o: JsonObject): MediaServerSource? {
        val id = o.str("id")?.takeIf { isSourceId(it) } ?: return null
        val name = (o.str("name") ?: id).take(80)
        return when (o.str("kind")) {
            "jellyfin" -> {
                val home = (o.obj("home")?.str("address") ?: o.str("address"))?.let { MediaAddress.normalize(it) } ?: return null
                val user = o.obj("user")
                val device = o.obj("device")
                MediaServerSource(
                    id = id,
                    kind = MediaServerSource.Kind.JELLYFIN,
                    name = name,
                    serverId = o.str("serverId"),
                    version = o.str("version"),
                    homeAddress = home,
                    userId = user?.str("id"),
                    userName = user?.str("name"),
                    problem = o.str("problem"),
                    deviceId = device?.str("id")?.takeIf { DEVICE_ID.matches(it) },
                    deviceName = device?.str("name")?.take(120),
                    paired = o.bool("paired") == true,
                    quickConnect = o.bool("quickConnect") != false,
                    downloads = o.bool("downloads") != false,
                    alwaysOriginal = o.bool("alwaysOriginal") == true,
                    awayAddress = away(o.obj("away"), MediaSourceKind.JELLYFIN, "direct"),
                    awayVpnAddress = away(o.obj("away"), MediaSourceKind.JELLYFIN, "vpn"),
                )
            }
            "share" -> {
                val smb = o.obj("smb")?.str("address")?.let { MediaAddress.normalizeShare(MediaSourceKind.SMB, it) }
                val dav = o.obj("webdav")
                val webdav = dav?.str("address")?.let { MediaAddress.normalizeShare(MediaSourceKind.WEBDAV, it) }
                if (smb == null && webdav == null) return null
                MediaServerSource(
                    id = id,
                    kind = MediaServerSource.Kind.SHARE,
                    name = name,
                    folder = o.str("folder"),
                    smb = smb,
                    webdav = webdav,
                    certSha256 = sha256(dav?.str("certSha256")),
                    shareUser = o.str("user"),
                    secretRev = o.int("secretRev")?.coerceAtLeast(0) ?: 0,
                    downloads = o.bool("downloads") != false,
                    awayAddress = away(o.obj("away"), MediaSourceKind.WEBDAV, "direct"),
                    awayVpnAddress = away(o.obj("away"), MediaSourceKind.WEBDAV, "vpn"),
                )
            }
            else -> null
        }
    }

    fun error(http: Int, o: JsonObject?): MediaServerError =
        MediaServerError(http, o?.str("code")?.take(40) ?: (if (http == 404) "not_found" else "unknown"), o?.str("reason")?.take(40))

    fun secret(o: JsonObject): MediaShareSecret? {
        val password = o.get("password")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        return MediaShareSecret(o.str("user"), password, o.int("rev") ?: 0)
    }

    /** The data of a silent push: `{ kind: "media.revoke", sourceIds, reason }`; null for anything else. */
    fun revoke(data: JsonElement?): MediaRevoke? {
        val o = data?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        if (o.str("kind") != "media.revoke") return null
        val ids = o.get("sourceIds")
        val list = when {
            ids == null || ids.isJsonNull -> null
            ids.isJsonArray -> ids.asJsonArray.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString?.takeIf { isSourceId(it) } }
            else -> return null
        }
        return MediaRevoke(list, o.str("reason")?.take(40))
    }
}
