package com.gatecontrol.android.drive

import androidx.media3.common.PlaybackException
import java.util.Locale

/**
 * Where the player streams a Drive video from (Drive 1.21 viewer): the app's media route of the own server
 * (`/api/v1/client/plugins/gatecontrol-drive/media/<mediaId>`, the client token, Range → 206). [mime] and [size] as
 * `GET nodes/<id>/content` said them.
 */
data class DriveStream(val url: String, val mime: String?, val size: Long?)

/** The rules of the viewer of pictures and videos (Drive 1.21), without Android: what it shows, where it streams from. */
object DriveMediaLogic {
    const val MEDIA_PREFIX = "/api/v1/client/plugins/$DRIVE_PLUGIN/media/"
    private val MEDIA_ID = Regex("^[A-Za-z0-9_-]{8,128}$")

    /** A picture or a video the viewer shows (no folder, nothing of a vault: its content is ciphertext). */
    fun viewable(n: DNode): Boolean {
        if (n.isDir || n.encrypted) return false
        val k = DriveLogic.kind(n)
        return k == DriveLogic.Kind.IMAGE || k == DriveLogic.Kind.VIDEO
    }

    fun isVideo(n: DNode): Boolean = !n.isDir && DriveLogic.kind(n) == DriveLogic.Kind.VIDEO

    /**
     * The pictures and videos of [items] (a folder, a search) to go through from [tapped], and where it is among them;
     * just [tapped] when it is not in [items].
     */
    fun viewerList(items: List<DNode>, tapped: DNode): Pair<List<DNode>, Int> {
        val list = items.filter(::viewable)
        val at = list.indexOfFirst { it.id == tapped.id }
        return if (at < 0) listOf(tapped) to 0 else list to at
    }

    /** The absolute address of [mediaId] on [server] (`https://gc.example.com`); null for an id that is not one. */
    fun mediaUrl(server: String, mediaId: String): String? {
        if (!MEDIA_ID.matches(mediaId)) return null
        return server.trimEnd('/') + MEDIA_PREFIX + mediaId
    }

    /**
     * May the player load [url]? Only a Drive file of the own [server] (scheme, host and port the same), no query, no
     * other path — the client token goes nowhere else.
     */
    fun allowed(server: String, url: String): Boolean {
        val s = runCatching { java.net.URI(server.trimEnd('/')) }.getOrNull() ?: return false
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (u.scheme == null || !u.scheme.equals(s.scheme, ignoreCase = true)) return false
        if (u.host == null || !u.host.equals(s.host, ignoreCase = true) || u.port != s.port) return false
        if (u.rawQuery != null || u.rawFragment != null || u.userInfo != null) return false
        val base = (s.rawPath ?: "").trimEnd('/')
        val path = u.rawPath ?: return false
        if (!path.startsWith(base + MEDIA_PREFIX)) return false
        return MEDIA_ID.matches(path.removePrefix(base + MEDIA_PREFIX))
    }

    /** What went wrong in the player, in the words of the viewer. */
    enum class Trouble { CANNOT_PLAY, NETWORK, OTHER }

    /** [PlaybackException.errorCode] → what to say: the format (often MKV with HEVC, an old codec), the network, else. */
    fun trouble(errorCode: Int): Trouble = when (errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        -> Trouble.CANNOT_PLAY
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_TIMEOUT,
        -> Trouble.NETWORK
        else -> Trouble.OTHER
    }

    /** The type of a file as the hint says it ("MKV"; else its MIME type). */
    fun typeName(n: DNode): String {
        val ext = n.name.substringAfterLast('.', "")
        return if (ext.isNotEmpty() && ext.length <= 5) ext.uppercase(Locale.ROOT) else n.mime.orEmpty()
    }

    /** A picture this large is not loaded for the viewer (the large preview stays): memory. */
    const val ORIGINAL_MAX_BYTES = 40L * 1024 * 1024

    /** The longest side the original of a picture is decoded to (zoom stays sharp, memory stays bounded). */
    const val ORIGINAL_SIDE_PX = 4096

    /** BitmapFactory's inSampleSize for a picture of [w] × [h] to fit [max] on its longest side (a power of two). */
    fun sampleSize(w: Int, h: Int, max: Int = ORIGINAL_SIDE_PX): Int {
        var s = 1
        val side = maxOf(w, h)
        while (side / s > max) s *= 2
        return s
    }
}

/**
 * Where a video was left (Drive 1.21 viewer): kept while the app runs, per file and revision, for the last 50 — not
 * before [KEEP_MS], nor within the last [KEEP_MS] (it starts from the beginning then).
 */
class DriveVideoPlaces(private val max: Int = 50) {
    private val places = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > max
    }

    private fun key(n: DNode) = "${n.id}:${n.rev}"

    fun get(n: DNode): Long? = synchronized(places) { places[key(n)] }

    fun keep(n: DNode, positionMs: Long, durationMs: Long) {
        synchronized(places) {
            if (durationMs > 0 && positionMs >= durationMs - KEEP_MS) places.remove(key(n))
            else if (positionMs > KEEP_MS) places[key(n)] = positionMs
        }
    }

    fun forget(n: DNode) {
        synchronized(places) { places.remove(key(n)) }
    }

    companion object {
        const val KEEP_MS = 5_000L

        /** The one of the app. */
        val shared = DriveVideoPlaces()
    }
}
