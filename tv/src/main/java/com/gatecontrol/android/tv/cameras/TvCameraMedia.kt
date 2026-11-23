package com.gatecontrol.android.tv.cameras

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.door.CameraMediaUrls
import com.gatecontrol.android.door.DoorImage
import com.gatecontrol.android.network.ApiClientProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pictures and the live video of the home cameras on the TV, like the phone app's `HomeCameraMedia`: the own server's
 * `/camera-media/…` (capability URLs) over the client API's OkHttp, memory only (they may show people), a small cache
 * for the previews of the start page (the stick has 2 GB). No saving or sharing on the TV.
 */
@Singleton
class TvCameraMedia @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
) {
    private val previews = object : LruCache<String, Bitmap>(PREVIEW_MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    private fun server(): String? = setupRepository.getServerUrl().takeIf { it.isNotBlank() }

    fun peekPreview(cameraId: String): Bitmap? = previews.get(cameraId)

    /** A fresh still of the camera at [path] (`snapshot.jpg` of the list / live answer), kept as its preview. */
    suspend fun still(cameraId: String, path: String?, n: Long, maxSidePx: Int = PREVIEW_SIDE_PX): Bitmap? {
        val server = server() ?: return null
        val abs = CameraMediaUrls.resolve(server, CameraMediaUrls.path(path, CameraMediaUrls.FILE_SNAPSHOT)) ?: return null
        val bytes = fetch(server, CameraMediaUrls.fresh(abs, n)) ?: return null
        val bmp = withContext(Dispatchers.Default) { decode(bytes, maxSidePx) } ?: return null
        if (maxSidePx == PREVIEW_SIDE_PX) previews.put(cameraId, bmp)
        return bmp
    }

    /** The still of `GET cameras/home/<id>/still` as the camera's preview (cached for the next start). */
    suspend fun preview(cameraId: String, image: DoorImage): Bitmap? {
        val bmp = withContext(Dispatchers.Default) { decode(image.bytes, PREVIEW_SIDE_PX) } ?: return null
        previews.put(cameraId, bmp)
        return bmp
    }

    /** A picture the door API sent (the doorbell's snapshot), decoded for the overlay. */
    suspend fun decodeImage(image: DoorImage, maxSidePx: Int = OVERLAY_SIDE_PX): Bitmap? =
        withContext(Dispatchers.Default) { decode(image.bytes, maxSidePx) }

    /** Data sources for the player: every address checked by [allow] first (live files of the same server only). */
    @OptIn(UnstableApi::class)
    fun dataSourceFactory(allow: (String) -> String?): DataSource.Factory? {
        val server = server() ?: return null
        val upstream = OkHttpDataSource.Factory(apiClientProvider.cameraMediaClient(server))
        return ResolvingDataSource.Factory(upstream) { spec ->
            val target = allow(spec.uri.toString()) ?: throw IOException("camera media address refused")
            spec.withUri(target.toUri())
        }
    }

    private suspend fun fetch(server: String, abs: String): ByteArray? = try {
        withContext(Dispatchers.IO) {
            val response = withTimeoutOrNull(TIMEOUT_MS) { apiClientProvider.getClient(server).fetchCameraMedia(abs) }
                ?: return@withContext null
            val body = response.body()
            if (!response.isSuccessful || body == null) {
                response.errorBody()?.close()
                body?.close()
                return@withContext null
            }
            body.use { b ->
                val type = b.contentType()
                if (type == null || type.type != "image" || b.contentLength() > MAX_BYTES) return@withContext null
                b.byteStream().use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8_192)
                    var total = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) return@withContext null
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.d("TV camera picture not loaded (%s)", e.javaClass.simpleName)
        null
    }

    companion object {
        const val PREVIEW_SIDE_PX = 480
        const val OVERLAY_SIDE_PX = 1280
        private const val MAX_BYTES = 4 * 1024 * 1024
        private const val PREVIEW_MEMORY_BYTES = 6 * 1024 * 1024
        private const val TIMEOUT_MS = 15_000L

        /** Decodes at most [maxSidePx] on the long side (power-of-two sampling, as the phone app). */
        fun decode(bytes: ByteArray, maxSidePx: Int): Bitmap? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val long = maxOf(bounds.outWidth, bounds.outHeight)
            if (long <= 0) return null
            var sample = 1
            while (long / (sample * 2) >= maxSidePx) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }
}
