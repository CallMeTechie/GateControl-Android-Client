package com.gatecontrol.android.profile

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI

/** The own GateControl account as `GET /api/v1/client/me/profile` returns it (core 1.168+). */
data class MyProfile(
    val id: Int,
    val name: String,
    val initials: String,
    /** Palette key of the favourite colour (the server's default when never chosen). */
    val color: String?,
    /** `#RRGGBB` of [color]. */
    val colorHex: String?,
    /** `/avatars/<id>.<ext>` (or an absolute URL of the server), null = no picture. */
    val avatarUrl: String?,
    val palette: List<PaletteColor>,
)

/**
 * Parses the profile answer and keeps the cached copy. Strict where it
 * matters: no user id → no profile; texts are plain and capped; colours
 * and the picture path must have their format.
 */
object ProfileJson {

    private const val MAX_NAME = 60

    /** Capability path of an avatar (core: 22+ random url-safe characters). */
    private val AVATAR_PATH_RE = Regex("^/avatars/[A-Za-z0-9_-]{16,128}\\.(png|jpe?g|webp)$")

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && (it.asJsonPrimitive.isString || it.asJsonPrimitive.isNumber) }?.asString

    private fun JsonObject.int(key: String): Int? {
        val p = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        return when {
            p.isNumber -> p.asDouble.takeIf { it % 1.0 == 0.0 && it > 0 && it <= Int.MAX_VALUE }?.toInt()
            p.isString -> p.asString.trim().toIntOrNull()?.takeIf { it > 0 }
            else -> null
        }
    }

    private fun clean(s: String?, max: Int): String = s.orEmpty().filter { !it.isISOControl() }.trim().take(max)

    /**
     * A usable avatar address: the capability path, or an absolute http(s)
     * URL whose path is one (the app only fetches it from its own server,
     * see [AvatarUrls]). Anything else → null.
     */
    fun avatarUrl(raw: String?): String? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= 512 } ?: return null
        if (s.startsWith("/")) return s.takeIf { AVATAR_PATH_RE.matches(it) }
        val uri = runCatching { URI(s) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("https", "http") || uri.host.isNullOrBlank()) return null
        if (uri.rawQuery != null || uri.rawFragment != null) return null
        val path = uri.rawPath ?: return null
        val at = path.lastIndexOf("/avatars/")
        return s.takeIf { at >= 0 && AVATAR_PATH_RE.matches(path.substring(at)) }
    }

    fun palette(arr: JsonArray?): List<PaletteColor> = arr?.mapNotNull { e ->
        if (!e.isJsonObject) return@mapNotNull null
        val o = e.asJsonObject
        val key = AvatarPalette.validKey(o.str("key")) ?: return@mapNotNull null
        val hex = AvatarPalette.validHex(o.str("hex")) ?: return@mapNotNull null
        PaletteColor(key, hex)
    }.orEmpty().distinctBy { it.key }.take(24)

    /** `{ ok, user: {...}, palette: [...] }` → profile, null when unusable. */
    fun parse(o: JsonObject): MyProfile? {
        if (o.get("ok")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == false) return null
        val u = o.get("user")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return user(u, palette(o.get("palette")?.takeIf { it.isJsonArray }?.asJsonArray))
    }

    private fun user(u: JsonObject, serverPalette: List<PaletteColor>): MyProfile? {
        val id = u.int("id") ?: return null
        val name = clean(u.str("name"), MAX_NAME)
        val palette = serverPalette.ifEmpty { AvatarPalette.DEFAULT }
        val key = AvatarPalette.validKey(u.str("color"))
        val hex = AvatarPalette.validHex(u.str("colorHex")) ?: AvatarPalette.hexOf(key, palette)
        return MyProfile(
            id = id,
            name = name,
            initials = AvatarInitials.pick(u.str("initials"), name),
            color = key ?: if (hex == null) AvatarPalette.defaultFor(id).key else null,
            colorHex = hex ?: AvatarPalette.defaultFor(id).hex,
            avatarUrl = avatarUrl(u.str("avatarUrl")),
            palette = serverPalette,
        )
    }

    // ─── Cached copy (DataStore), bound to the login ─────────────────

    fun encode(profile: MyProfile, owner: String): String = JsonObject().apply {
        addProperty("v", 1)
        addProperty("owner", owner)
        add("user", JsonObject().apply {
            addProperty("id", profile.id)
            addProperty("name", profile.name)
            addProperty("initials", profile.initials)
            profile.color?.let { addProperty("color", it) }
            profile.colorHex?.let { addProperty("colorHex", it) }
            profile.avatarUrl?.let { addProperty("avatarUrl", it) }
        })
        add("palette", JsonArray().apply {
            profile.palette.forEach { c -> add(JsonObject().apply { addProperty("key", c.key); addProperty("hex", c.hex) }) }
        })
    }.toString()

    /** The cached profile when it belongs to [owner] (another server or token: null). */
    fun decode(raw: String?, owner: String): MyProfile? = runCatching {
        if (raw.isNullOrBlank() || owner.isEmpty()) return null
        val o = JsonParser.parseString(raw).asJsonObject
        if (o.str("owner") != owner) return null
        parse(o)
    }.getOrNull()
}

/** Where an avatar is fetched from. */
object AvatarUrls {

    /**
     * The absolute address of [url] on the server at [serverUrl] (the base
     * the client API uses): a path is appended to it, an absolute URL is
     * only accepted on the same scheme, host and port. Anything else → null,
     * so the app never fetches from a host the user did not set up.
     */
    fun resolve(serverUrl: String, url: String?): String? {
        if (url.isNullOrBlank() || serverUrl.isBlank()) return null
        val base = runCatching { URI(serverUrl.trim()) }.getOrNull() ?: return null
        val scheme = base.scheme?.lowercase()
        if (scheme != "https" && scheme != "http" || base.host.isNullOrBlank()) return null
        val u = url.trim()
        if (u.startsWith("//")) return null
        if (u.startsWith("/")) return serverUrl.trim().trimEnd('/') + u
        val abs = runCatching { URI(u) }.getOrNull() ?: return null
        if (abs.scheme == null) return null
        val samePort = effectivePort(abs) == effectivePort(base)
        return u.takeIf {
            abs.scheme.equals(base.scheme, ignoreCase = true) && abs.host.equals(base.host, ignoreCase = true) && samePort
        }
    }

    private fun effectivePort(u: URI): Int = when {
        u.port != -1 -> u.port
        u.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }
}
