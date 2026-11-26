package com.gatecontrol.android.media

import java.io.File
import java.security.MessageDigest

/**
 * Posters and backdrops of the media pages on the disk (the app's cache folder, at most [maxBytes]; the least used go
 * first), so that opening Medien after a restart of the app does not load them over the tunnel again. The key holds
 * the picture's tag (Jellyfin gives a new one when the picture changes), never an access; the file name is its hash.
 * Pure file work, unit-tested.
 */
class MediaPictureDisk(
    private val dir: File,
    private val maxBytes: Long = MAX_BYTES,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var writes = 0

    private fun file(key: String) = File(dir, sha256(key) + SUFFIX)

    /** The bytes kept for [key] (and marked as used just now); null when none. */
    @Synchronized
    fun get(key: String): ByteArray? {
        val f = file(key)
        if (!f.isFile) return null
        return runCatching { f.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() }?.also { f.setLastModified(now()) }
    }

    /** Keeps [bytes] for [key]; now and then the oldest go until the folder is under [maxBytes]. */
    @Synchronized
    fun put(key: String, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > maxBytes / 4) return
        runCatching {
            dir.mkdirs()
            val f = file(key)
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) tmp.delete()
            f.setLastModified(now())
        }
        if (writes++ % TRIM_EVERY == 0) trim()
    }

    /** The oldest pictures go until the folder holds at most [maxBytes]. */
    @Synchronized
    fun trim() {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            val size = f.length()
            if (f.delete()) total -= size
        }
    }

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        /** About a thousand posters of the size asked away. */
        const val MAX_BYTES = 64L * 1024 * 1024
        private const val TRIM_EVERY = 25
        private const val SUFFIX = ".img"

        private fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
