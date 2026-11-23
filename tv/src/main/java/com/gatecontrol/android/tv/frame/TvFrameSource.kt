package com.gatecontrol.android.tv.frame

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.drive.DRIVE_PLUGIN
import com.gatecontrol.android.drive.DriveMediaLogic
import com.gatecontrol.android.network.ApiClientProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the photo frame's pictures come from: Drive's `GET frame` (Drive 1.31: for the TV as an app device of its
 * person) — only the photos shared on purpose for the photo frame. The answer is kept for [TvFrameSchedule.REFRESH_MS]
 * (its URLs hold 2 h); the pictures are the server's large previews, in memory only.
 */
@Singleton
class TvFrameSource @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
) {
    /** The wall clock (tests set their own). */
    internal var clock: () -> Long = System::currentTimeMillis

    private val _status = MutableStateFlow<TvFrameStatus>(TvFrameStatus.Unknown)

    /** What the frame can show, as of the last `GET frame`. */
    val status: StateFlow<TvFrameStatus> = _status.asStateFlow()

    private val lock = Mutex()

    @Volatile
    private var feed: TvFrameFeed? = null

    @Volatile
    private var loadedAt = 0L

    /** The last answer (null: none yet, or the last one failed). */
    val last: TvFrameFeed? get() = feed

    /**
     * `GET frame` when due ([TvFrameSchedule.refreshDue]) or [force]d; the answer kept. Null when it failed (the old
     * answer is kept for a while then: its URLs still hold).
     */
    suspend fun load(force: Boolean = false): TvFrameFeed? = lock.withLock {
        val now = clock()
        if (!force && !TvFrameSchedule.refreshDue(loadedAt, now, _status.value)) return@withLock feed
        val (fresh, unavailable) = fetch()
        val status = TvFrameSchedule.status(fresh, unavailable)
        if (status == TvFrameStatus.Failed && feed != null && _status.value is TvFrameStatus.Photos) {
            // photos still in hand: shown on (their URLs hold 2 h), asked again in a minute
            loadedAt = now - TvFrameSchedule.REFRESH_MS + TvFrameSchedule.RETRY_MS
            return@withLock null
        }
        loadedAt = now
        feed = fresh
        _status.value = status
        fresh
    }

    /** "Kopplung aufheben": the answer belonged to that account. */
    fun clear() {
        feed = null
        loadedAt = 0L
        _status.value = TvFrameStatus.Unknown
    }

    private suspend fun fetch(): Pair<TvFrameFeed?, Boolean> {
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) return null to true
        return try {
            val r = withTimeoutOrNull(TIMEOUT_MS) { apiClientProvider.getClient(url).pluginGet(DRIVE_PLUGIN, "frame") } ?: return null to false
            if (r.isSuccessful) {
                (r.body()?.let(TvFrameJson::feed)) to false
            } else {
                r.errorBody()?.close()
                // no Drive, or a Drive that answers `GET frame` to kiosk devices only (before 1.31)
                null to (r.code() == 403 || r.code() == 404)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            null to false
        } catch (e: Exception) {
            Timber.d("Frame: GET frame failed (%s)", e.javaClass.simpleName)
            null to false
        }
    }

    /** The large preview of [photo] (fits 1280 px), decoded to at most [sidePx]; null when it cannot be had. */
    suspend fun picture(photo: TvFramePhoto, sidePx: Int = SCREEN_SIDE_PX): Bitmap? {
        val path = TvFrameJson.safePath(photo.url) ?: return null
        val server = setupRepository.getServerUrl().takeIf { it.isNotBlank() } ?: return null
        return try {
            val bytes = withTimeoutOrNull(TIMEOUT_MS) {
                val r = apiClientProvider.mediaClient(server).getRaw(path.removePrefix("/"))
                val body = r.body()
                if (!r.isSuccessful || body == null) {
                    r.errorBody()?.close()
                    body?.close()
                    return@withTimeoutOrNull null
                }
                withContext(Dispatchers.IO) { body.use { b -> b.byteStream().use(::readCapped) } }
            } ?: return null
            withContext(Dispatchers.Default) { decode(bytes, sidePx) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Frame: picture not loaded (%s)", e.javaClass.simpleName)
            null
        }
    }

    private fun readCapped(input: java.io.InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) return null
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
        private const val TIMEOUT_MS = 25_000L

        /** A large preview is a JPEG of at most 1280 px: a few hundred KB; more is not a preview. */
        private const val MAX_BYTES = 12 * 1024 * 1024

        /** The longest side a picture is decoded to on the TV. */
        const val SCREEN_SIDE_PX = 2560
    }
}
