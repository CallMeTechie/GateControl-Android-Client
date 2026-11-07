package com.gatecontrol.android.door

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.push.NotifyMediaLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** A still of a home camera, decoded (memory only). [at] from `X-Snapshot-At`. */
class HomeStill(val bitmap: Bitmap, val at: String?)

/**
 * Pictures and video of the home cameras from the own server's `/camera-media/…`
 * (capability URLs, no login) over the client API's OkHttp — kept in memory only like the
 * push pictures (they may show people); thumbnails in a small LRU keyed by clip id (the
 * address changes with every token). Saving a still is the person's own action
 * ("Standbild"): it goes to Pictures/GateControl or, for sharing, to one file in the cache.
 */
@Singleton
class HomeCameraMedia @Inject constructor(
    @ApplicationContext private val context: Context,
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
) {
    private val thumbs = object : LruCache<String, Bitmap>(THUMB_MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    private fun server(): String? = setupRepository.getServerUrl().takeIf { it.isNotBlank() }

    /** Absolute address of a camera-media [path] on the own server. */
    fun resolve(path: String?): String? = server()?.let { CameraMediaUrls.resolve(it, path) }

    /** The still at [path] (snapshot.jpg of a live / list answer), fetched fresh; null when unusable. */
    suspend fun still(path: String?, n: Long): HomeStill? {
        val abs = resolve(path) ?: return null
        val got = fetch(CameraMediaUrls.fresh(abs, n), STILL_MAX_BYTES) ?: return null
        val bmp = withContext(Dispatchers.Default) { NotifyMediaLoader.decode(got.first, STILL_SIDE_PX) } ?: return null
        return HomeStill(bmp, got.second)
    }

    fun peekThumb(clipId: String): Bitmap? = thumbs.get(clipId)

    /** Thumbnail of a clip (256 px), from memory or the server. */
    suspend fun thumb(clipId: String, path: String?): Bitmap? {
        thumbs.get(clipId)?.let { return it }
        val abs = resolve(path) ?: return null
        val got = fetch(abs, THUMB_MAX_BYTES) ?: return null
        val bmp = withContext(Dispatchers.Default) { NotifyMediaLoader.decode(got.first, NotifyMediaLoader.THUMB_SIDE_PX) } ?: return null
        thumbs.put(clipId, bmp)
        return bmp
    }

    fun clearThumbs() = thumbs.evictAll()

    /** Bytes and `X-Snapshot-At` of an image answer up to [max] bytes. */
    private suspend fun fetch(abs: String, max: Int): Pair<ByteArray, String?>? {
        val server = server() ?: return null
        return try {
            withContext(Dispatchers.IO) {
                val response = withTimeoutOrNull(TIMEOUT_MS) { apiClientProvider.getClient(server).fetchCameraMedia(abs) } ?: return@withContext null
                val body = response.body()
                if (!response.isSuccessful || body == null) {
                    response.errorBody()?.close()
                    body?.close()
                    return@withContext null
                }
                val at = response.headers()["X-Snapshot-At"]?.trim()?.takeIf { it.length in 10..40 }
                body.use { b ->
                    val type = b.contentType()
                    if (type == null || type.type != "image" || b.contentLength() > max) return@withContext null
                    b.byteStream().use { input ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(8_192)
                        var total = 0
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > max) return@withContext null
                            out.write(buf, 0, n)
                        }
                        out.toByteArray() to at
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Camera picture not loaded (%s)", e.javaClass.simpleName)
            null
        }
    }

    /**
     * Data sources for the player: OkHttp of the client API, every address checked by
     * [allow] first (the live list may only load live files of the same server under the
     * newest token, a clip only its own address); anything else fails the load.
     */
    @OptIn(UnstableApi::class)
    fun dataSourceFactory(allow: (String) -> String?): DataSource.Factory? {
        val server = server() ?: return null
        val upstream = OkHttpDataSource.Factory(apiClientProvider.cameraMediaClient(server))
        return ResolvingDataSource.Factory(upstream) { spec ->
            val target = allow(spec.uri.toString()) ?: throw IOException("camera media address refused")
            spec.withUri(target.toUri())
        }
    }

    // ─── "Standbild": save or share ─────────────────────────────────

    /** Saves the JPEG to Pictures/GateControl (MediaStore, no permission needed); the entry or null. */
    suspend fun saveToGallery(bytes: ByteArray, cameraName: String, at: Instant): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, HomeCameraLogic.stillFileName(cameraName, at))
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GateControl")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return@withContext null
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("no stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            Timber.w("Camera still not saved (%s)", e.javaClass.simpleName)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /**
     * The JPEG as a file to share (cacheDir/camera-still, one at a time: earlier ones are
     * removed first), as a content URI of the app's FileProvider.
     */
    suspend fun shareFile(bytes: ByteArray, cameraName: String, at: Instant): Uri? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, HomeCameraLogic.stillFileName(cameraName, at))
            file.writeBytes(bytes)
            FileProvider.getUriForFile(context, context.packageName + ".provider", file)
        } catch (e: Exception) {
            Timber.w("Camera still not prepared for sharing (%s)", e.javaClass.simpleName)
            null
        }
    }

    /** Removes the shared still (screen closed). */
    fun clearShared() {
        runCatching { File(context.cacheDir, SHARE_DIR).listFiles()?.forEach { it.delete() } }
    }

    companion object {
        private const val SHARE_DIR = "camera-still"
        private const val STILL_MAX_BYTES = 4 * 1024 * 1024
        private const val THUMB_MAX_BYTES = 512 * 1024
        private const val STILL_SIDE_PX = 1280
        private const val THUMB_MEMORY_BYTES = 4 * 1024 * 1024
        private const val TIMEOUT_MS = 15_000L
    }
}
