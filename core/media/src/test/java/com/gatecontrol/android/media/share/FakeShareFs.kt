package com.gatecontrol.android.media.share

import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.ShareProgress
import com.gatecontrol.android.media.ShareProgressStore
import java.io.ByteArrayInputStream
import java.io.IOException

/** A share in memory: its files by path (folders follow from them), [answer] instead of the listing when set. */
class FakeShareFs(
    files: Map<String, ByteArray>,
    override val kind: MediaSourceKind = MediaSourceKind.SMB,
    private val modified: Map<String, Long> = emptyMap(),
) : ShareFs {
    val files = files.toMutableMap()
    var answer: MediaResult<List<ShareEntry>>? = null
    val listed = mutableListOf<String>()
    var closed = false

    override suspend fun list(dir: String): MediaResult<List<ShareEntry>> {
        listed += dir
        answer?.let { return it }
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        if (dir.isNotEmpty() && files.keys.none { it.startsWith(prefix) }) return MediaResult.NotFound
        val entries = files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.map { rest ->
            val name = rest.substringBefore('/')
            ShareEntry(name, SharePaths.join(dir, name), directory = rest.contains('/'), size = files[prefix + rest]?.size?.toLong() ?: 0, modifiedAt = modified[prefix + name])
        }.distinctBy { it.name }
        return MediaResult.Ok(entries)
    }

    override suspend fun bytes(path: String, max: Int): ByteArray? = files[path]?.takeIf { it.size <= max }

    override fun open(path: String, position: Long): ShareInput {
        val b = files[path] ?: throw IOException("not found")
        return ShareInput(b.size.toLong(), ByteArrayInputStream(b, position.toInt(), b.size - position.toInt()))
    }

    override fun close() {
        closed = true
    }
}

class MapProgress : ShareProgressStore {
    val map = LinkedHashMap<String, ShareProgress>()
    override fun progress(key: String) = map[key]
    override fun all(prefix: String) = map.filterKeys { it.startsWith(prefix) }
    override fun setProgress(key: String, progress: ShareProgress) {
        map[key] = progress
    }
}
