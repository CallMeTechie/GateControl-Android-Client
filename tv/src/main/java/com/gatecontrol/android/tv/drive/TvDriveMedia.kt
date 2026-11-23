package com.gatecontrol.android.tv.drive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.media3.datasource.DataSource
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveMediaLogic
import com.gatecontrol.android.drive.DrivePreviews
import com.gatecontrol.android.drive.DriveStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The pictures and videos of Drive on the TV, through the shared Drive API of the phone app: the server's previews
 * (with the home gateway's copies at home, [DrivePreviews]), the original of a picture without one (in memory only,
 * at most [DriveMediaLogic.ORIGINAL_MAX_BYTES], decoded to the TV's screen) and the stream of a video (Range).
 */
@Singleton
class TvDriveMedia @Inject constructor(
    private val api: DriveApi,
    private val previews: DrivePreviews,
) {
    fun peek(node: DNode, large: Boolean = false): Bitmap? = previews.peek(node, large)

    suspend fun preview(node: DNode, large: Boolean = false): Bitmap? = previews.load(node, large)

    /**
     * The picture of [node] for the viewer: its large preview (fits 1280 px, enough for a TV), else the original
     * downloaded into memory and decoded to [sidePx]; null when neither works.
     */
    suspend fun picture(node: DNode, sidePx: Int = SCREEN_SIDE_PX): Bitmap? {
        if (node.preview) previews.load(node, large = true)?.let { return it }
        if (node.size > DriveMediaLogic.ORIGINAL_MAX_BYTES || node.encrypted || node.blocked) return null
        return try {
            val r = api.download(node.id, expectedSha256 = node.sha256)
            if (r !is DResult.Ok) return null
            val bytes = withContext(Dispatchers.IO) {
                r.value.body()?.use { body -> body.byteStream().use(::readCapped) }
            } ?: return null
            withContext(Dispatchers.Default) { decode(bytes, sidePx) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Drive picture not loaded (%s)", e.javaClass.simpleName)
            null
        }
    }

    /** Where the player streams [node] from (the rights and the virus scan checked first, `GET …/content`). */
    suspend fun stream(node: DNode): DResult<DriveStream> = api.stream(node)

    /** The player's data sources: only Drive files of the own server, with the client token. */
    fun sourceFactory(): DataSource.Factory? = api.streamSourceFactory()

    private fun readCapped(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > DriveMediaLogic.ORIGINAL_MAX_BYTES) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray, sidePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = DriveMediaLogic.sampleSize(bounds.outWidth, bounds.outHeight, sidePx) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    companion object {
        /** The longest side a picture is decoded to on the TV (4K screens; memory stays bounded on a 2 GB stick). */
        const val SCREEN_SIDE_PX = 2560
    }
}

/** The list the viewer goes through, handed from the Drive page to [com.gatecontrol.android.tv.ui.drive.TvMediaActivity]. */
@Singleton
class TvMediaSelection @Inject constructor() {
    @Volatile
    var items: List<DNode> = emptyList()
        private set

    @Volatile
    var at: Int = 0
        private set

    /** A document's preview instead of pictures or videos. */
    @Volatile
    var document: Boolean = false
        private set

    /** Files received with GateDrop instead of Drive's (their index in the send for "Senden an …"). */
    @Volatile
    var local: List<Pair<Int, com.gatecontrol.android.tv.ui.drive.TvLocalFile>> = emptyList()
        private set

    @Volatile
    var localSend: String? = null
        private set

    /** Films and episodes of a media source (Medien) instead of Drive's or GateDrop's files. */
    @Volatile
    var media: com.gatecontrol.android.tv.media.TvMediaQueue? = null
        private set

    fun setMedia(queue: com.gatecontrol.android.tv.media.TvMediaQueue) {
        items = emptyList()
        document = false
        local = emptyList()
        localSend = null
        at = queue.at
        media = queue
    }

    fun set(items: List<DNode>, at: Int, document: Boolean = false) {
        this.items = items
        this.at = at.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        this.document = document
        local = emptyList()
        localSend = null
        media = null
    }

    /** The pictures or videos of a GateDrop send kept on the TV ([sendId]), from [at]. */
    fun setLocal(sendId: String, files: List<Pair<Int, com.gatecontrol.android.tv.ui.drive.TvLocalFile>>, at: Int) {
        items = emptyList()
        document = false
        local = files
        localSend = sendId
        media = null
        this.at = at.coerceIn(0, (files.size - 1).coerceAtLeast(0))
    }

    fun clear() {
        items = emptyList()
        at = 0
        document = false
        local = emptyList()
        localSend = null
        media = null
    }
}
