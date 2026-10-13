package com.gatecontrol.android.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/** What the server offers for the speed test. */
sealed interface SpeedTestAvailability {
    data class Available(val maxDownloadBytes: Long, val maxUploadBytes: Long, val viaTunnel: Boolean?) : SpeedTestAvailability
    /** Older server without the endpoints (404). */
    data object Unsupported : SpeedTestAvailability
    /** Too many measurements (429). */
    data object RateLimited : SpeedTestAvailability
}

/** One measured transfer: [bytes] moved in [millis]; [serverMs] = the server's own timing (upload). */
data class SpeedTransfer(val bytes: Long, val millis: Long, val serverMs: Long? = null, val serverBytes: Long? = null)

/** Non-2xx answer of a speed test endpoint. */
class SpeedTestHttpException(val code: Int) : IOException("HTTP $code")

/**
 * Speed test against the own GateControl server (diagnosis):
 * `GET /client/speedtest` (limits), `GET /client/speedtest/download?bytes=N`
 * read as a stream and `POST /client/speedtest/upload` streamed from one
 * reused buffer. Both transfers stop after [maxMillis] at the latest; the
 * result is what actually moved in the time measured on this device.
 * Runs on [ApiClientProvider.speedTestClient] (auth and machine-binding
 * headers included, longer timeouts).
 */
@Singleton
class SpeedTestClient @Inject constructor(
    private val apiClientProvider: ApiClientProvider,
) {
    suspend fun info(serverUrl: String): SpeedTestAvailability = info(apiClientProvider.speedTestClient(serverUrl))

    suspend fun download(serverUrl: String, bytes: Long, maxMillis: Long): SpeedTransfer =
        download(apiClientProvider.speedTestClient(serverUrl), bytes, maxMillis)

    suspend fun upload(serverUrl: String, bytes: Long, maxMillis: Long): SpeedTransfer =
        upload(apiClientProvider.speedTestClient(serverUrl), bytes, maxMillis)

    companion object {
        private const val CHUNK = 64 * 1024
        private val OCTET_STREAM = "application/octet-stream".toMediaType()

        suspend fun info(api: ApiClient): SpeedTestAvailability {
            val response = api.getSpeedTestInfo()
            return when {
                response.code() == 404 -> SpeedTestAvailability.Unsupported.also { response.errorBody()?.close() }
                response.code() == 429 -> SpeedTestAvailability.RateLimited.also { response.errorBody()?.close() }
                !response.isSuccessful -> {
                    response.errorBody()?.close()
                    throw SpeedTestHttpException(response.code())
                }
                else -> {
                    val body = response.body() ?: throw IOException("empty speed test answer")
                    SpeedTestAvailability.Available(body.maxDownloadBytes, body.maxUploadBytes, body.viaTunnel)
                }
            }
        }

        /**
         * Reads up to [bytes] of the download, stopping after [maxMillis].
         * The clock starts once the response headers arrived (the latency is
         * measured separately) and the body is never held in memory.
         */
        suspend fun download(
            api: ApiClient,
            bytes: Long,
            maxMillis: Long,
            nanoTime: () -> Long = System::nanoTime,
        ): SpeedTransfer {
            val response = api.speedTestDownload(bytes)
            if (!response.isSuccessful) {
                response.errorBody()?.close()
                throw SpeedTestHttpException(response.code())
            }
            val body = response.body() ?: throw IOException("empty speed test body")
            return withContext(Dispatchers.IO) {
                body.use {
                    val input = it.byteStream()
                    val buffer = ByteArray(CHUNK)
                    val start = nanoTime()
                    val limit = maxMillis * 1_000_000L
                    var total = 0L
                    while (total < bytes) {
                        coroutineContext.ensureActive()
                        val wanted = minOf(buffer.size.toLong(), bytes - total).toInt()
                        val n = input.read(buffer, 0, wanted)
                        if (n < 0) break
                        total += n
                        if (nanoTime() - start >= limit) break
                    }
                    SpeedTransfer(total, ((nanoTime() - start) / 1_000_000L).coerceAtLeast(1))
                }
            }
        }

        /**
         * Uploads [bytes] (fixed length, from one reused buffer). When
         * [maxMillis] runs out first the request is aborted and the rate
         * comes from what was written until then.
         */
        suspend fun upload(
            api: ApiClient,
            bytes: Long,
            maxMillis: Long,
            nanoTime: () -> Long = System::nanoTime,
        ): SpeedTransfer {
            val body = CappedUploadBody(bytes, maxMillis * 1_000_000L, nanoTime)
            val response = try {
                api.speedTestUpload(body)
            } catch (e: IOException) {
                if (body.capped && body.written > 0) return SpeedTransfer(body.written, body.elapsedMillis())
                throw e
            }
            val end = body.elapsedMillis()
            if (!response.isSuccessful) {
                response.errorBody()?.close()
                throw SpeedTestHttpException(response.code())
            }
            val answer = response.body()
            return SpeedTransfer(
                bytes = body.written,
                millis = end,
                serverMs = answer?.serverMs,
                serverBytes = answer?.bytes?.takeIf { it > 0 },
            )
        }
    }

    /** Thrown inside [CappedUploadBody.writeTo] when the time is up. */
    private class UploadCapReached : IOException("speed test upload time cap reached")

    /**
     * Fixed-length body written in [CHUNK]s of incompressible filler.
     * One-shot, so OkHttp never retries (and re-sends) it.
     */
    private class CappedUploadBody(
        private val total: Long,
        private val limitNanos: Long,
        private val nanoTime: () -> Long,
    ) : RequestBody() {
        @Volatile var written = 0L
            private set
        @Volatile var capped = false
            private set
        @Volatile private var start = 0L

        fun elapsedMillis(): Long = if (start == 0L) 1L else ((nanoTime() - start) / 1_000_000L).coerceAtLeast(1)

        override fun contentType(): MediaType = OCTET_STREAM
        override fun contentLength(): Long = total
        override fun isOneShot(): Boolean = true

        override fun writeTo(sink: BufferedSink) {
            val buffer = Random.nextBytes(CHUNK)
            start = nanoTime()
            var left = total
            while (left > 0) {
                val n = minOf(left, buffer.size.toLong()).toInt()
                sink.write(buffer, 0, n)
                left -= n
                written += n
                if (left > 0 && nanoTime() - start >= limitNanos) {
                    sink.flush()
                    capped = true
                    throw UploadCapReached()
                }
            }
        }
    }
}
