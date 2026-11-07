package com.gatecontrol.android.door

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Media addresses of the home cameras: `/camera-media/<token>/<file>` on the own server
 * (GateControl `src/services/homeCameras.js`; the token is `<base64url body>.<base64url
 * HMAC>`). Pure, unit-tested: the app never fetches camera media from another host or
 * another path, also not when an HLS list names one.
 */
object CameraMediaUrls {

    const val FILE_SNAPSHOT = "snapshot.jpg"
    const val FILE_HLS = "index.m3u8"
    const val FILE_CLIP = "clip.mp4"
    const val FILE_THUMB = "thumb.jpg"
    private const val PREFIX = "/camera-media/"

    private val TOKEN_RE = Regex("^[A-Za-z0-9_-]{16,400}\\.[A-Za-z0-9_-]{43}$")
    private val FILES = setOf(FILE_SNAPSHOT, FILE_HLS, "live.mp4", FILE_CLIP, FILE_THUMB)

    /** Files of a live token the player may load: the list, its init segment and media segments (and the still). */
    private val LIVE_FILE_RE = Regex("^(index\\.m3u8|init\\.mp4|s\\d{1,10}\\.m4s|snapshot\\.jpg)$")

    /** [value] when it is a camera-media path of the own server naming [file], else null. */
    fun path(value: String?, file: String): String? {
        val p = value?.trim() ?: return null
        if (!p.startsWith(PREFIX)) return null
        val rest = p.removePrefix(PREFIX).split('/')
        if (rest.size != 2 || !TOKEN_RE.matches(rest[0]) || rest[1] != file || file !in FILES) return null
        return p
    }

    /** The absolute address of a camera-media [path] on the server at [serverUrl] (null when either is unusable). */
    fun resolve(serverUrl: String, path: String?): String? {
        if (path == null || path != path.trim() || !path.startsWith(PREFIX)) return null
        val base = serverUrl.trim().trimEnd('/')
        val scheme = base.substringBefore("://", "").lowercase()
        if ((scheme != "https" && scheme != "http") || base.substringAfter("://").isBlank()) return null
        if (base.contains('?') || base.contains('#')) return null
        return base + path
    }

    private data class Parts(val origin: String, val token: String, val file: String)

    private fun split(url: String): Parts? {
        val i = url.indexOf(PREFIX)
        if (i <= 0) return null
        val rest = url.substring(i + PREFIX.length)
        if (rest.contains('?') || rest.contains('#')) return null
        val seg = rest.split('/')
        if (seg.size != 2 || !TOKEN_RE.matches(seg[0])) return null
        return Parts(url.substring(0, i), seg[0], seg[1])
    }

    /**
     * The address the player really loads for [requested] (from the HLS list or its reload)
     * while [current] is the newest live list of the camera: the same file under the newest
     * token, so the picture keeps running when the 10-minute token is renewed. Null — the
     * load is refused — for anything that is not a live file of the same server.
     */
    fun retoken(requested: String, current: String): String? {
        val cur = split(current) ?: return null
        val req = split(requested) ?: return null
        if (req.origin != cur.origin || !LIVE_FILE_RE.matches(req.file)) return null
        return cur.origin + PREFIX + cur.token + "/" + req.file
    }

    /** A clip (or its picture) of the own server: [requested] must be exactly [allowed]. */
    fun sameFile(requested: String, allowed: String): Boolean = requested == allowed && split(allowed) != null

    /** [abs] with a cache buster (`?t=<n>`, the server's advice for the still every 10 s). */
    fun fresh(abs: String, n: Long): String = "$abs?t=$n"
}

/** Pure decisions of the home-camera part of the door screen, unit-tested. */
object HomeCameraLogic {

    /** Media tokens live 10 minutes: renew 1 minute before, within 2…9 minutes after the answer. */
    const val TOKEN_TTL_MS = 10 * 60_000L
    private const val RENEW_BEFORE_MS = 60_000L
    private const val RENEW_MIN_MS = 2 * 60_000L
    private const val RENEW_MAX_MS = 9 * 60_000L

    /** Thumbnail and clip addresses live 1 h: a list older than this is fetched again before a clip plays. */
    const val CLIPS_FRESH_MS = 50 * 60_000L

    /** The camera list (online states) while the door screen is in front. */
    const val LIST_POLL_MS = 60_000L

    /** "Clips heute" while the door screen is in front. */
    const val CLIPS_POLL_MS = 2 * 60_000L

    /**
     * When to ask for a new live answer (fresh tokens), [expiresAt] the server's time of the
     * token's end: one minute before it, but never earlier than 2 and never later than 9
     * minutes (the device clock may be off; without [expiresAt] 9 minutes).
     */
    fun renewInMs(expiresAt: String?, nowMs: Long): Long {
        val end = expiresAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return RENEW_MAX_MS
        return (end - nowMs - RENEW_BEFORE_MS).coerceIn(RENEW_MIN_MS, RENEW_MAX_MS)
    }

    /** Wait before the next try of the live video after [failures] failed ones (3 s, 6 s, … ≤ 60 s). */
    fun retryInMs(failures: Int): Long {
        val n = failures.coerceIn(1, 6)
        return (3_000L shl (n - 1)).coerceAtMost(60_000L)
    }

    /** "n online" of the section title. */
    fun onlineCount(cameras: List<HomeCamera>): Int = cameras.count { it.online }

    /**
     * The camera to show: [wanted] (a push, a tap) when it is listed, else the one shown
     * before, else the first online one, else the first.
     */
    fun pick(cameras: List<HomeCamera>, wanted: String?, current: String?): String? {
        if (cameras.isEmpty()) return null
        wanted?.let { w -> if (cameras.any { it.id == w }) return w }
        current?.let { c -> if (cameras.any { it.id == c }) return c }
        return (cameras.firstOrNull { it.online } ?: cameras.first()).id
    }

    /** Quality of the live picture: from the video height once known, else from the stream (`sub` = SD). */
    fun isHd(videoHeight: Int?, stream: String): Boolean =
        if (videoHeight != null && videoHeight > 0) videoHeight >= 720 else stream != "sub"

    /** "0:24", "1:05", "12:00" (minutes:seconds, rounded up to a whole second). */
    fun duration(ms: Long): String {
        val s = ((ms + 999) / 1000).coerceAtLeast(0)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** File name of a saved still: `GateControl_<camera>_<yyyyMMdd_HHmmss>.jpg`, only safe characters. */
    fun stillFileName(cameraName: String, at: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val safe = cameraName.map { if (it.isLetterOrDigit() || it == '-') it else '_' }.joinToString("")
            .replace(Regex("_+"), "_").trim('_').take(40).ifEmpty { "Kamera" }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(at.atZone(zone))
        return "GateControl_${safe}_$stamp.jpg"
    }

    /** `?day=` of the clips: the server's own day (its time zone) unless one is asked for. */
    val DAY_RE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
}
