package com.gatecontrol.android.media.offline

import com.gatecontrol.android.drive.vault.DriveVaultCrypto
import com.gatecontrol.android.familie.emergency.CacheCipher
import com.gatecontrol.android.media.MediaTitle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * The downloads on the phone (question 22 a): one folder per title in the app's own storage (never the gallery),
 * the video, its subtitles and its poster each encrypted in the format of Drive's vault folders with the title's
 * own random key ([DriveVaultCrypto.ContentWriter], read chunk by chunk while it plays). The keys and the list are in
 * one index file, encrypted with a key of the Android Keystore ([cipher]). An index that cannot be read (another
 * phone's backup, a lost key) counts as no downloads: the folders go. Pure JVM besides [cipher].
 */
class MediaOfflineStore(
    private val dir: File,
    private val cipher: CacheCipher,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val _items = MutableStateFlow(read())
    val items: StateFlow<List<OfflineItem>> = _items.asStateFlow()

    private val index: File get() = File(dir, INDEX)

    private fun read(): List<OfflineItem> {
        val f = File(dir, INDEX)
        if (!f.exists()) {
            wipeFolders(keep = emptySet())
            return emptyList()
        }
        val list = try {
            MediaOfflineIndex.decode(String(cipher.decrypt(f.readBytes()), Charsets.UTF_8))
        } catch (e: Exception) {
            Timber.w("Medien offline: index not readable (%s), downloads dropped", e.javaClass.simpleName)
            f.delete()
            emptyList()
        }
        // a download cut off when the app went is waiting again
        val fixed = list.map { if (it.state == OfflineState.LOADING) it.copy(state = OfflineState.QUEUED) else it }
        wipeFolders(keep = fixed.map { it.id }.toSet())
        return fixed
    }

    /** Folders without an entry (left from a removal the app did not finish) go. */
    private fun wipeFolders(keep: Set<String>) {
        dir.listFiles()?.filter { it.isDirectory && it.name !in keep }?.forEach { it.deleteRecursively() }
    }

    private fun write(list: List<OfflineItem>) {
        dir.mkdirs()
        val tmp = File(dir, "$INDEX.tmp")
        tmp.writeBytes(cipher.encrypt(MediaOfflineIndex.encode(list).toByteArray(Charsets.UTF_8)))
        if (!tmp.renameTo(index)) {
            index.delete()
            tmp.renameTo(index)
        }
        _items.value = list
    }

    fun get(id: String): OfflineItem? = _items.value.firstOrNull { it.id == id }

    fun find(sourceId: String, itemId: String): OfflineItem? = get(MediaOfflineLogic.idOf(sourceId, itemId))

    /** [title] to be loaded in [quality]; one already there stays as it is (a failed one waits again). */
    fun add(title: MediaTitle, quality: OfflineQuality): OfflineItem = synchronized(lock) {
        val id = MediaOfflineLogic.idOf(title.sourceId, title.id)
        val list = _items.value
        list.firstOrNull { it.id == id }?.let { old ->
            if (old.state != OfflineState.FAILED) return old
            val again = old.copy(state = OfflineState.QUEUED, failure = null, quality = quality, loaded = 0)
            folder(id).deleteRecursively()
            write(list.map { if (it.id == id) again else it })
            return again
        }
        val item = OfflineItem(
            id = id,
            sourceId = title.sourceId,
            title = title,
            quality = quality,
            addedAt = now(),
            positionMs = title.positionMs,
            played = title.played,
            playedAt = title.playedAt ?: 0,
            key = DriveVaultCrypto.randomBytes(DriveVaultCrypto.KEY_BYTES),
        )
        write(list + item)
        item
    }

    /** Changes item [id]; null when it is gone (removed meanwhile). */
    fun update(id: String, f: (OfflineItem) -> OfflineItem): OfflineItem? = synchronized(lock) {
        val list = _items.value
        val old = list.firstOrNull { it.id == id } ?: return null
        val new = f(old)
        if (new != old) write(list.map { if (it.id == id) new else it })
        new
    }

    /** The download and its files go. */
    fun remove(id: String) {
        synchronized(lock) {
            val list = _items.value
            if (list.none { it.id == id }) return
            write(list.filterNot { it.id == id })
            folder(id).deleteRecursively()
        }
    }

    /** A source was removed: its downloads go. */
    fun removeSource(sourceId: String) {
        synchronized(lock) {
            val gone = _items.value.filter { it.sourceId == sourceId }
            if (gone.isEmpty()) return
            write(_items.value.filterNot { it.sourceId == sourceId })
            gone.forEach { folder(it.id).deleteRecursively() }
        }
    }

    /** Everything goes (unpaired, signed out of the app). */
    fun clear() {
        synchronized(lock) {
            _items.value = emptyList()
            dir.deleteRecursively()
        }
    }

    /** Bytes taken: the ready ones and what the others have loaded. */
    fun used(except: String? = null): Long = _items.value.filter { it.id != except }.sumOf { if (it.ready) it.size else it.loaded }

    fun folder(id: String): File = File(dir, id)

    fun file(id: String, part: String): File = File(folder(id), "$part.gcdv")

    /** Bytes of [id]'s files on the phone. */
    fun bytesOf(id: String): Long = folder(id).listFiles()?.sumOf { it.length() } ?: 0

    /**
     * Part [part] ([MediaOfflineLogic.VIDEO], a subtitle) of ready (or loading) download [id], opened for reading
     * chunk by chunk; null when it is not there or cannot be opened with its key.
     */
    fun open(id: String, part: String): Pair<File, DriveVaultCrypto.Content>? {
        val item = get(id) ?: return null
        val f = file(id, part)
        if (!f.isFile || f.length() < DriveVaultCrypto.HEADER) return null
        return try {
            val head = ByteArray(DriveVaultCrypto.HEADER)
            RandomAccessFile(f, "r").use { it.readFully(head) }
            f to DriveVaultCrypto.openContent({ item.key }, id, head, f.length(), MediaOfflineLogic.PREFIX)
        } catch (e: Exception) {
            Timber.w("Medien offline: file not opened (%s)", e.javaClass.simpleName)
            null
        }
    }

    /** A small file (the poster) of [id] encrypted with its key. */
    fun writeSmall(id: String, part: String, bytes: ByteArray) {
        val item = get(id) ?: return
        val f = file(id, part)
        f.parentFile?.mkdirs()
        f.outputStream().buffered().use { out ->
            DriveVaultCrypto.ContentWriter.start(item.key, id, 1, out, MediaOfflineLogic.PREFIX).use { w ->
                w.write(bytes)
                w.finish()
            }
        }
    }

    /** A small file of [id] decrypted (the poster); null when it is not there. */
    fun readSmall(id: String, part: String): ByteArray? {
        val item = get(id) ?: return null
        val f = file(id, part)
        if (!f.isFile || f.length() > MAX_SMALL) return null
        return runCatching {
            val out = ByteArrayOutputStream()
            f.inputStream().buffered().use { DriveVaultCrypto.decryptContent({ item.key }, id, it, out, MediaOfflineLogic.PREFIX) }
            out.toByteArray()
        }.getOrNull()
    }

    companion object {
        const val INDEX = "index.bin"
        const val POSTER = "poster"
        const val BACKDROP = "backdrop"
        private const val MAX_SMALL = 8L * 1024 * 1024
    }
}
