package com.gatecontrol.android.drive.vault

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Plaintext of a vault file in memory only (Drive plugin 1.22, the in-app viewer). Pure JVM parts are unit-tested
 * ([PlainBuffer], [DriveVaultVideoSource]); [DriveVaultMemFile] and [DriveVaultShareProvider] need Android.
 */

/** Thrown while decrypting into memory when the plaintext is larger than the viewer allows. */
class DriveVaultTooLarge : IOException("too large")

/**
 * An output into one byte array of at most [limit] bytes (the plaintext of a picture or a text): it never grows by
 * copying (a copy would leave plaintext behind on the heap), [bytes] hands it over, [wipe] overwrites it.
 */
class PlainBuffer(capacity: Int, private val limit: Long) : OutputStream() {
    private var buf = ByteArray(capacity.coerceAtLeast(16))
    private var count = 0

    val size: Int get() = count

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (count.toLong() + len > limit) throw DriveVaultTooLarge()
        if (count + len > buf.size) {
            // larger than the size the ciphertext promised: grow once, the old one overwritten
            val bigger = ByteArray(maxOf(count + len, minOf(limit, buf.size.toLong() * 2).toInt()))
            System.arraycopy(buf, 0, bigger, 0, count)
            buf.fill(0)
            buf = bigger
        }
        System.arraycopy(b, off, buf, count, len)
        count += len
    }

    /** The plaintext, exactly [size] bytes (the buffer itself when it fits, else a copy and the buffer overwritten). */
    fun bytes(): ByteArray {
        if (count == buf.size) return buf
        val out = buf.copyOf(count)
        buf.fill(0)
        buf = out
        return out
    }

    fun wipe() {
        buf.fill(0)
        count = 0
    }
}

/**
 * An anonymous memory file (memfd, Android 11+): a seekable file descriptor in RAM with no path on the storage — for
 * Android's PdfRenderer (it needs a seekable descriptor) and for "Mit anderer App öffnen". [close] empties it
 * (ftruncate to 0 frees its pages; the kernel zeroes pages before reuse) and closes it.
 */
class DriveVaultMemFile private constructor(private val pfd: ParcelFileDescriptor) : Closeable {
    @Volatile private var closed = false

    /** Bytes written. */
    var size: Long = 0
        private set

    /** Where the content is written (once, from the start); [size] counts it. */
    fun output(): OutputStream = object : OutputStream() {
        private val raw = FileOutputStream(pfd.fileDescriptor)
        override fun write(b: Int) { raw.write(b); size++ }
        override fun write(b: ByteArray, off: Int, len: Int) { raw.write(b, off, len); size += len }
    }

    /** A new descriptor of its own (its own offset) for reading — the caller (PdfRenderer, another app) closes it. */
    fun openRead(): ParcelFileDescriptor {
        if (closed) throw FileNotFoundException("closed")
        return ParcelFileDescriptor.open(File("/proc/self/fd/${pfd.fd}"), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { Os.ftruncate(pfd.fileDescriptor, 0) }
        runCatching { pfd.close() }
    }

    companion object {
        fun create(name: String = "gcvault"): DriveVaultMemFile {
            val fd = Os.memfd_create(name, OsConstants.MFD_CLOEXEC)
            return DriveVaultMemFile(ParcelFileDescriptor.dup(fd).also { Os.close(fd) })
        }
    }
}

/**
 * "Mit anderer App öffnen" (an explicit export): the decrypted file is handed out as `content://…vaultshare/<token>/<name>`
 * from a memory file — never a file of the cache. [clear] forgets every entry and empties the memory: when the person
 * comes back to the vault, when the vaults lock and when the app starts. An app that opened it keeps only the descriptor
 * it already has.
 */
object DriveVaultShares {
    data class Entry(val file: DriveVaultMemFile, val name: String, val mime: String)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val random = SecureRandom()

    fun authority(packageName: String) = "$packageName.vaultshare"

    /** Offers [file] as [name] → its content URI. */
    fun add(packageName: String, file: DriveVaultMemFile, name: String, mime: String): Uri {
        val token = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        entries[token] = Entry(file, name, mime)
        return Uri.Builder().scheme("content").authority(authority(packageName)).appendPath(token).appendPath(name).build()
    }

    fun get(uri: Uri): Entry? = uri.pathSegments.firstOrNull()?.let { entries[it] }

    /** Every entry goes, its memory emptied. */
    fun clear() {
        val all = entries.values.toList()
        entries.clear()
        all.forEach { it.file.close() }
    }

    fun isEmpty(): Boolean = entries.isEmpty()
}

/** Serves [DriveVaultShares] read-only to the app the person chose (not exported; a URI grant per intent). */
class DriveVaultShareProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val e = DriveVaultShares.get(uri) ?: return null
        val cols = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()?.takeIf { it.isNotEmpty() }
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply { addRow(cols.map { if (it == OpenableColumns.SIZE) e.file.size else e.name }) }
    }

    override fun getType(uri: Uri): String? = DriveVaultShares.get(uri)?.mime

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val e = DriveVaultShares.get(uri) ?: throw FileNotFoundException("gone")
        return e.file.openRead()
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

/**
 * A video of a vault for Media3, decrypted while it plays: [cipher] is the ciphertext (as the server keeps it) in the
 * cache, [content] opens its chunks; at most one chunk of plaintext is in memory (overwritten when the next one comes
 * and on [close]). Nothing in plaintext is written — no Media3 cache is put in front of it.
 */
@UnstableApi
class DriveVaultVideoSource(private val cipher: File, private val content: DriveVaultCrypto.Content) : BaseDataSource(false) {
    private var raf: RandomAccessFile? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var chunk = -1L
    private var plain: ByteArray? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        if (dataSpec.position > content.plainSize) throw IOException("position beyond the end")
        raf = RandomAccessFile(cipher, "r")
        position = dataSpec.position
        val left = content.plainSize - position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) left else minOf(dataSpec.length, left)
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        val i = content.chunkOf(position)
        val p = plainOf(i)
        val inChunk = (position - i * content.chunkSize).toInt()
        val n = minOf(length.toLong(), remaining, (p.size - inChunk).toLong()).toInt()
        if (n <= 0) return C.RESULT_END_OF_INPUT
        System.arraycopy(p, inChunk, buffer, offset, n)
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    private fun plainOf(i: Long): ByteArray {
        plain?.let { if (chunk == i) return it }
        val f = raf ?: throw IOException("not open")
        val (off, len) = content.cipherRange(i)
        val buf = ByteArray(len)
        f.seek(off)
        f.readFully(buf)
        val p = try {
            content.decryptChunk(i, buf)
        } catch (e: DriveVaultCrypto.DriveVaultException) {
            throw IOException("vault: " + e.code, e)
        }
        plain?.fill(0)
        plain = p
        chunk = i
        return p
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        plain?.fill(0)
        plain = null
        chunk = -1
        runCatching { raf?.close() }
        raf = null
        if (uri != null) {
            uri = null
            transferEnded()
        }
    }
}
