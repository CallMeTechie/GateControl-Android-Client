package com.gatecontrol.android.door

import com.gatecontrol.android.door.DoorJson.flag
import com.gatecontrol.android.door.DoorJson.obj
import com.gatecontrol.android.door.DoorJson.str
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Parses the answers of the plugin's home-camera API (README "Kameras im Heimnetz",
 * `server/home.js`). Untrusted like every answer: ids must have the plugin's shape, enums
 * known, texts plain and capped, media addresses only `/camera-media/<token>/<file>` paths
 * of the own server with the file the field stands for. A malformed camera or clip is
 * dropped. Pure (Gson tree only), unit-tested.
 */
object HomeCameraJson {

    val CAMERA_ID_RE = Regex("^c[0-9a-f]{15}$")
    val EVENT_ID_RE = Regex("^e[0-9a-f]{15}$")
    private val TOKEN_RE = Regex("^[a-z][a-z0-9_-]{0,40}$")
    private val TIME_RE = Regex("^[0-9TZ:+.-]{10,40}$")
    private val DAY_RE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val VERSION_RE = Regex("^\\d{1,4}\\.\\d{1,4}\\.\\d{1,6}$")
    private val RECORD = setOf("motion", "away", "never")

    private fun JsonObject.prim(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive

    private fun JsonObject.num(key: String): Double? = prim(key)?.takeIf { it.isNumber }?.asDouble?.takeIf { it.isFinite() }

    private fun JsonObject.arr(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonElement.objOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

    private fun time(value: String?): String? = value?.trim()?.takeIf { TIME_RE.matches(it) }

    private fun token(value: String?): String? = value?.takeIf { TOKEN_RE.matches(it) }

    private fun kinds(arr: JsonArray?): List<String> =
        arr?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it in HomeCameraKind.ALL } }
            ?.distinct()
            ?.sortedBy { HomeCameraKind.ALL.indexOf(it) }
            .orEmpty()

    /** Seconds between stills: the server's value within 5…60 s, else 10. */
    private fun every(o: JsonObject?): Int = o?.num("every")?.toInt()?.takeIf { it in 5..60 } ?: DEFAULT_EVERY_S

    const val DEFAULT_EVERY_S = 10

    // ─── list ───────────────────────────────────────────────────────

    fun list(o: JsonObject): HomeCameraList? {
        val supported = o.flag("supported") ?: return null
        val cameras = if (!supported) emptyList() else o.arr("cameras")?.mapNotNull { it.objOrNull()?.let(::camera) }?.distinctBy { it.id }.orEmpty()
        return HomeCameraList(
            supported = supported,
            minGatecontrol = o.str("minGatecontrol")?.takeIf { VERSION_RE.matches(it) },
            every = every(o),
            cameras = cameras,
        )
    }

    fun camera(o: JsonObject): HomeCamera? {
        val id = o.str("id")?.takeIf { CAMERA_ID_RE.matches(it) } ?: return null
        // Only cameras of the home network here; Ring cameras come with `GET me`.
        if (o.str("kind").let { it != null && it != "home" }) return null
        return HomeCamera(
            id = id,
            name = DoorJson.text(o.str("name"), 60) ?: id,
            gateway = DoorJson.text(o.str("gateway"), 60),
            model = DoorJson.text(o.str("model"), 80),
            state = token(o.str("state")),
            online = o.flag("online") == true,
            codec = DoorJson.text(o.str("codec"), 40),
            settings = settings(o.obj("settings")),
            snapshotAt = time(o.str("snapshotAt")),
            lastEventAt = time(o.str("lastEventAt")),
            width = dimension(o, "width"),
            height = dimension(o, "height"),
            audio = o.flag("audio") == true,
            hasAudio = o.flag("hasAudio"),
            push = o.flag("push") != false,
            myPush = o.obj("prefs")?.flag("push"),
        )
    }

    /** A side of the resolution: a whole number of pixels, 16…16384, else unknown. */
    private fun dimension(o: JsonObject, key: String): Int? =
        o.num(key)?.takeIf { it == Math.floor(it) }?.toInt()?.takeIf { it in 16..16384 }

    /** `{ prefs: { push } }` of `PUT cameras/home/<id>/prefs` (plugin 1.7.0) → the own push. */
    fun prefs(o: JsonObject): Boolean? = o.obj("prefs")?.flag("push")

    fun settings(o: JsonObject?): HomeCameraSettings = HomeCameraSettings(
        record = o?.str("record")?.takeIf { it in RECORD } ?: "motion",
        events = kinds(o?.arr("events")),
        retentionDays = o?.num("retentionDays")?.toInt()?.takeIf { it in 1..90 },
        stream = if (o?.str("stream") == "sub") "sub" else "main",
        audio = o?.flag("audio") == true,
    )

    // ─── live ───────────────────────────────────────────────────────

    fun live(o: JsonObject): HomeLive? {
        val l = o.obj("live") ?: return null
        val snapshot = CameraMediaUrls.path(l.str("snapshot"), CameraMediaUrls.FILE_SNAPSHOT)
        val hls = CameraMediaUrls.path(l.str("hls"), CameraMediaUrls.FILE_HLS)
        val mode = when (l.str("mode")) {
            HomeLive.MODE_LIVE -> if (hls != null) HomeLive.MODE_LIVE else HomeLive.MODE_SNAPSHOT
            HomeLive.MODE_SNAPSHOT -> HomeLive.MODE_SNAPSHOT
            else -> return null
        }
        // Without a picture of any kind there is nothing to show.
        if (mode == HomeLive.MODE_SNAPSHOT && snapshot == null) return null
        return HomeLive(
            mode = mode,
            via = l.str("via")?.takeIf { it == "vpn" || it == "lan" || it == "internet" },
            // Older answers without `ready`: assume the ring runs.
            ready = l.flag("ready") ?: true,
            hlsPath = if (mode == HomeLive.MODE_LIVE) hls else null,
            snapshotPath = snapshot,
            audio = mode == HomeLive.MODE_LIVE && l.flag("audio") == true,
            every = every(l),
            expiresAt = time(l.str("expiresAt")),
            reason = token(l.str("reason")),
        )
    }

    // ─── still ("Standbild") ────────────────────────────────────────

    /** `{ still: { data, mime, at } }` → the JPEG (≤ 1 MB decoded), null when unusable. */
    fun still(o: JsonObject): DoorImage? {
        val s = o.obj("still")?.deepCopy() ?: return null
        // The plugin sends `mime` image/jpeg; an answer without it is JPEG as well.
        if (s.get("mime") == null) s.addProperty("mime", "image/jpeg")
        return DoorJson.image(s)
    }

    // ─── clips ──────────────────────────────────────────────────────

    fun clips(o: JsonObject): HomeClips? {
        val list = o.arr("clips") ?: return null
        return HomeClips(
            day = o.str("day")?.takeIf { DAY_RE.matches(it) },
            clips = list.mapNotNull { it.objOrNull()?.let(::clip) }.distinctBy { it.id },
        )
    }

    fun clip(o: JsonObject): HomeClip? {
        val id = o.str("id")?.takeIf { EVENT_ID_RE.matches(it) } ?: return null
        val kinds = kinds(o.arr("kinds"))
        val kind = o.str("kind")?.takeIf { it in HomeCameraKind.ALL } ?: kinds.firstOrNull() ?: HomeCameraKind.MOTION
        val clipPath = CameraMediaUrls.path(o.str("clipUrl"), CameraMediaUrls.FILE_CLIP)
        return HomeClip(
            id = id,
            kind = kind,
            kinds = (listOf(kind) + kinds).distinct().sortedBy { HomeCameraKind.ALL.indexOf(it) },
            startedAt = time(o.str("startedAt")),
            endedAt = time(o.str("endedAt")),
            nobodyHome = o.flag("nobodyHome"),
            ms = o.num("ms")?.toLong()?.takeIf { it in 1..3_600_000 },
            thumbPath = CameraMediaUrls.path(o.str("thumbUrl"), CameraMediaUrls.FILE_THUMB),
            clipPath = clipPath,
        )
    }
}
