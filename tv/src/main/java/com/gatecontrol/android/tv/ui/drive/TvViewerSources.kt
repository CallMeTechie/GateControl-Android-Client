package com.gatecontrol.android.tv.ui.drive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveMediaLogic
import com.gatecontrol.android.drive.DriveVideoPlaces
import com.gatecontrol.android.tv.drive.TvDriveMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** What the viewer goes through: Drive's pictures (server previews) or files received with GateDrop (on the TV). */
interface TvPhotoSource {
    val count: Int
    fun key(i: Int): String
    fun name(i: Int): String
    fun peek(i: Int): Bitmap?
    suspend fun load(i: Int): Bitmap?
}

/** The answer of [TvVideoSource.open]. */
sealed interface TvOpen {
    data class Ready(val item: MediaItem) : TvOpen
    data object Offline : TvOpen
    data object Failed : TvOpen
}

/** The videos the player goes through: Drive's (streamed with Range) or received ones (a file on the TV). */
interface TvVideoSource {
    val count: Int
    fun key(i: Int): String
    fun name(i: Int): String
    /** "MKV" for the hint when the TV cannot play it. */
    fun typeName(i: Int): String
    fun factory(context: Context): DataSource.Factory?
    suspend fun open(i: Int): TvOpen
    fun startAt(i: Int): Long? = null
    fun keep(i: Int, positionMs: Long, durationMs: Long) {}
    fun forget(i: Int) {}
}

class TvDrivePhotos(private val items: List<DNode>, private val media: TvDriveMedia) : TvPhotoSource {
    override val count: Int get() = items.size
    override fun key(i: Int) = "d:" + items[i].id
    override fun name(i: Int) = items[i].name
    override fun peek(i: Int): Bitmap? = media.peek(items[i], large = true)
    override suspend fun load(i: Int): Bitmap? = media.picture(items[i])
}

class TvDriveVideos(
    private val items: List<DNode>,
    private val media: TvDriveMedia,
    private val places: DriveVideoPlaces,
) : TvVideoSource {
    override val count: Int get() = items.size
    override fun key(i: Int) = "d:" + items[i].id
    override fun name(i: Int) = items[i].name
    override fun typeName(i: Int) = DriveMediaLogic.typeName(items[i])
    override fun factory(context: Context): DataSource.Factory? = media.sourceFactory()

    override suspend fun open(i: Int): TvOpen = when (val s = media.stream(items[i])) {
        is DResult.Ok -> TvOpen.Ready(MediaItem.Builder().setUri(s.value.url).apply { s.value.mime?.let(::setMimeType) }.build())
        DResult.Offline -> TvOpen.Offline
        else -> TvOpen.Failed
    }

    override fun startAt(i: Int): Long? = places.get(items[i])
    override fun keep(i: Int, positionMs: Long, durationMs: Long) = places.keep(items[i], positionMs, durationMs)
    override fun forget(i: Int) = places.forget(items[i])
}

/** A file kept on the TV (GateDrop): its [name] and [mime] as the sender said them. */
data class TvLocalFile(val file: File, val name: String, val mime: String)

class TvLocalPhotos(private val files: List<TvLocalFile>, private val sidePx: Int = TvDriveMedia.SCREEN_SIDE_PX) : TvPhotoSource {
    override val count: Int get() = files.size
    override fun key(i: Int) = "f:" + files[i].file.path
    override fun name(i: Int) = files[i].name
    override fun peek(i: Int): Bitmap? = null

    override suspend fun load(i: Int): Bitmap? = withContext(Dispatchers.IO) {
        val path = files[i].file.path
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = DriveMediaLogic.sampleSize(bounds.outWidth, bounds.outHeight, sidePx) })
    }
}

class TvLocalVideos(private val files: List<TvLocalFile>) : TvVideoSource {
    override val count: Int get() = files.size
    override fun key(i: Int) = "f:" + files[i].file.path
    override fun name(i: Int) = files[i].name
    override fun typeName(i: Int): String {
        val ext = files[i].name.substringAfterLast('.', "")
        return if (ext.isNotEmpty() && ext.length <= 5) ext.uppercase(Locale.ROOT) else files[i].mime
    }

    @OptIn(UnstableApi::class)
    override fun factory(context: Context): DataSource.Factory = DefaultDataSource.Factory(context)

    override suspend fun open(i: Int): TvOpen =
        if (files[i].file.isFile) TvOpen.Ready(MediaItem.Builder().setUri(files[i].file.toUri()).setMimeType(files[i].mime).build()) else TvOpen.Failed
}
