package com.gatecontrol.android.drive.faces

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Fetches one model file into [dir] (Drive 1.16, SFace): the [sources] in order, each answer checked against [sha256]
 * and [size] — a wrong one is thrown away and the next source tried. The bytes go into `<name>.part` (an interrupted
 * download goes on from there with a `Range` request to the same source, else starts anew) and become `<name>` by a
 * rename only once complete and right. No Android here (unit tests); [open] makes the request.
 */
class DriveFaceModelFetcher(
    private val dir: File,
    private val name: String,
    private val sha256: String,
    private val size: Long,
    /** A request of [url] from byte [offset] on (0: all); null when the server cannot be reached. */
    private val open: (url: String, offset: Long) -> Response?,
) {
    /** An answer: its HTTP [code] (200, 206 for a range), the body. */
    interface Response : Closeable {
        val code: Int
        val body: InputStream
    }

    sealed class Result {
        data class Done(val file: File) : Result()
        /** `offline` (try again later, the part stays), `hash` (every source gave wrong bytes), `http_<code>`, `cancelled`. */
        data class Failed(val code: String) : Result()
    }

    val file: File get() = File(dir, name)
    private val part: File get() = File(dir, "$name.part")
    private val source: File get() = File(dir, "$name.src")

    /** The finished file is there (it only appears complete and checked). */
    fun ready(): Boolean = file.isFile && file.length() == size

    /** Everything of it goes (switched off). */
    fun delete() {
        file.delete()
        part.delete()
        source.delete()
    }

    /** [progress] hears bytes so far and the whole size; [cancelled] stops between blocks. */
    fun fetch(sources: List<String>, progress: (Long, Long) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false }): Result {
        dir.mkdirs()
        if (ready()) return Result.Done(file)
        var failure = "offline"
        for (url in sources) {
            if (cancelled()) return Result.Failed(CANCELLED)
            // go on only with what the same source sent before
            val had = if (part.isFile && runCatching { source.readText() }.getOrNull() == url) part.length() else 0L
            val offset = if (had in 1 until size) had else 0L
            if (offset == 0L) part.delete()
            source.writeText(url)
            val response = try {
                open(url, offset)
            } catch (e: IOException) {
                null
            }
            if (response == null) {
                failure = OFFLINE
                continue
            }
            response.use { r ->
                val append = when {
                    r.code == 206 && offset > 0 -> true
                    r.code == 200 -> false
                    else -> {
                        failure = "http_${r.code}"
                        part.delete()
                        return@use
                    }
                }
                var done = if (append) offset else 0L
                progress(done, size)
                try {
                    FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(BLOCK)
                        while (true) {
                            if (cancelled()) return Result.Failed(CANCELLED)
                            val n = r.body.read(buf)
                            if (n < 0) break
                            done += n
                            if (done > size) break
                            out.write(buf, 0, n)
                            progress(done, size)
                        }
                    }
                } catch (e: IOException) {
                    // interrupted: the part stays for the next try
                    return Result.Failed(OFFLINE)
                }
                if (part.length() == size && hash(part) == sha256) {
                    if (!part.renameTo(file)) {
                        file.delete()
                        if (!part.renameTo(file)) return Result.Failed(OFFLINE)
                    }
                    source.delete()
                    return Result.Done(file)
                }
                // wrong bytes (or too many): away with them, the next source
                part.delete()
                failure = HASH
            }
        }
        source.delete()
        return Result.Failed(failure)
    }

    companion object {
        const val OFFLINE = "offline"
        const val HASH = "hash"
        const val CANCELLED = "cancelled"
        private const val BLOCK = 64 * 1024

        fun hash(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(BLOCK)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
