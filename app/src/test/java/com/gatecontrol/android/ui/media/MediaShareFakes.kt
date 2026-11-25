package com.gatecontrol.android.ui.media

import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.ShareProgress
import com.gatecontrol.android.media.ShareProgressStore
import com.gatecontrol.android.media.share.ShareEntry
import com.gatecontrol.android.media.share.ShareFs
import com.gatecontrol.android.media.share.ShareInput
import com.gatecontrol.android.media.share.SharePaths
import java.io.ByteArrayInputStream
import java.io.IOException

/** A share in memory for the page "Ordner": its files by path, the folders follow from them; [offline]: not reachable. */
internal class MemFs(val files: Map<String, ByteArray>, override val kind: MediaSourceKind) : ShareFs {
    var offline = false

    override suspend fun list(dir: String): MediaResult<List<ShareEntry>> {
        if (offline) return MediaResult.Offline
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        return MediaResult.Ok(
            files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.map { rest ->
                val name = rest.substringBefore('/')
                ShareEntry(name, SharePaths.join(dir, name), directory = rest.contains('/'), size = files[prefix + rest]?.size?.toLong() ?: 0)
            }.distinctBy { it.name },
        )
    }

    override suspend fun bytes(path: String, max: Int): ByteArray? = files[path]

    override fun open(path: String, position: Long): ShareInput {
        val b = files[path] ?: throw IOException("not found")
        return ShareInput(b.size.toLong(), ByteArrayInputStream(b, position.toInt(), (b.size - position.toInt()).coerceAtLeast(0)))
    }
}

/** The progress kept on the phone, in memory. */
internal class MapProgress : ShareProgressStore {
    val map = LinkedHashMap<String, ShareProgress>()
    override fun progress(key: String) = map[key]
    override fun all(prefix: String) = map.filterKeys { it.startsWith(prefix) }
    override fun setProgress(key: String, progress: ShareProgress) {
        map[key] = progress
    }
}
