package com.gatecontrol.android.media.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.gatecontrol.android.media.share.ShareFs
import com.gatecontrol.android.media.share.ShareInput
import com.gatecontrol.android.media.share.ShareUri
import com.gatecontrol.android.media.share.SubtitleText
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Media3's way into a share's file (`gcshare://share/…`, [ShareUri]): opened through the library's [ShareFs] (SMB with
 * smbj, WebDAV with a range request and its access) at the player's place, read as the player wants it. Played as it
 * is, nothing converted. [files]: asked at each open (a pair changes from SMB to WebDAV with the network).
 */
@OptIn(UnstableApi::class)
class ShareDataSource(private val files: () -> ShareFs?) : BaseDataSource(true) {

    private var uri: Uri? = null
    private var input: ShareInput? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val path = ShareUri.path(dataSpec.uri.toString()) ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val fs = files() ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        val opened = try {
            if (SubtitleText.isSubtitle(path)) subtitle(fs, path, dataSpec.position) else fs.open(path, dataSpec.position)
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        input = opened
        val left = if (opened.length >= 0) (opened.length - dataSpec.position).coerceAtLeast(0) else C.LENGTH_UNSET.toLong()
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            if (left != C.LENGTH_UNSET.toLong()) minOf(left, dataSpec.length) else dataSpec.length
        } else {
            left
        }
        this.opened = true
        transferStarted(dataSpec)
        return remaining
    }

    /**
     * A subtitle file beside the video, read whole and given to the player as UTF-8 ([SubtitleText]: Windows-1252
     * umlauts right); one larger than [SubtitleText.MAX_BYTES] as it is.
     */
    private fun subtitle(fs: ShareFs, path: String, position: Long): ShareInput {
        val whole = fs.open(path, 0)
        if (whole.length > SubtitleText.MAX_BYTES) {
            whole.close()
            return fs.open(path, position)
        }
        val raw = whole.use { it.stream.readNBytesCompat(SubtitleText.MAX_BYTES + 1) }
        if (raw.size > SubtitleText.MAX_BYTES) return fs.open(path, position)
        val text = SubtitleText.toUtf8(raw)
        val from = position.coerceIn(0, text.size.toLong()).toInt()
        return ShareInput(text.size.toLong(), ByteArrayInputStream(text, from, text.size - from))
    }

    private fun InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() <= max) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val stream = input?.stream ?: return C.RESULT_END_OF_INPUT
        val want = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(remaining, length.toLong()).toInt()
        val n = try {
            stream.read(buffer, offset, want)
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            input?.close()
        } catch (_: IOException) {
            // closing a file of a share that is gone
        } finally {
            input = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(private val files: () -> ShareFs?) : DataSource.Factory {
        override fun createDataSource(): DataSource = ShareDataSource(files)
    }
}
