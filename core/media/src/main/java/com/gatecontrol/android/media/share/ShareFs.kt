package com.gatecontrol.android.media.share

import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaSourceKind
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder

/** A file or folder of a share. [path]: below the share's root, "/" between folders ("Serien/Graufeld/S01E01.mkv"). */
data class ShareEntry(
    val name: String,
    val path: String,
    val directory: Boolean,
    val size: Long = 0,
    /** Last written (ms since 1970); null: unknown. */
    val modifiedAt: Long? = null,
)

/** A file opened for the player at a place: its whole [length] (-1: unknown) and the bytes from there. */
class ShareInput(val length: Long, val stream: InputStream) : Closeable {
    override fun close() = stream.close()
}

/**
 * The folders of one share, SMB ([SmbFs]) or WebDAV ([WebDavFs]); in tests a fake. Paths are relative to the share's
 * root and never leave it ("..", absolute paths are refused). Errors are reported as [MediaResult], never thrown,
 * except by [open] (the player's loader thread wants an [IOException]).
 */
interface ShareFs : Closeable {
    val kind: MediaSourceKind

    /** The entries of folder [dir] ("" the root). */
    suspend fun list(dir: String): MediaResult<List<ShareEntry>>

    /** A small file whole (a poster), at most [max] bytes; null: not there, too big or not reachable. */
    suspend fun bytes(path: String, max: Int): ByteArray?

    /** File [path] from byte [position]. Blocking. */
    @Throws(IOException::class)
    fun open(path: String, position: Long): ShareInput

    override fun close() {}
}

/** Paths below a share's root: no way out of it. Pure, unit-tested. */
object SharePaths {
    /** [path] cleaned ("a//b/" → "a/b"); null when it would leave the root or carries what a share cannot have. */
    fun clean(path: String): String? {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (parts.any { it == "." || it == ".." || it.any { c -> c.isISOControl() } }) return null
        return parts.joinToString("/")
    }

    fun join(dir: String, name: String): String = if (dir.isEmpty()) name else "$dir/$name"

    fun parent(path: String): String = path.substringBeforeLast('/', "")

    fun name(path: String): String = path.substringAfterLast('/')
}

/**
 * The URL a share's file gets in the player (`gcshare://share/Filme/Kobaltnacht%20(2025).mkv`): the player's data
 * source reads it through the library's [ShareFs]. The extension stays visible for the player's format guess.
 */
object ShareUri {
    const val SCHEME = "gcshare"
    private const val PREFIX = "$SCHEME://share/"

    fun of(path: String): String = PREFIX + path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /** The path of a [of] URL; null for any other. */
    fun path(uri: String): String? {
        if (!uri.startsWith(PREFIX)) return null
        val parts = uri.removePrefix(PREFIX).split('/').map { runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null }
        return SharePaths.clean(parts.joinToString("/"))?.takeIf { it.isNotEmpty() }
    }
}
