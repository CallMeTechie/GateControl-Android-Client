package com.gatecontrol.android.media.jellyfin

import com.gatecontrol.android.media.MediaClientInfo
import com.gatecontrol.android.media.MediaResult
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The REST API of one Jellyfin server (10.9+), over [http]. [token]: the access of this device once signed in. The
 * token goes in the `Authorization: MediaBrowser …` header, never in a logged URL; errors are logged by class only.
 */
class JellyfinClient(
    baseUrl: String,
    private val http: OkHttpClient,
    private val info: MediaClientInfo,
    private val token: String? = null,
) {
    val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

    /** The header of every call: who asks and, once signed in, with which access. */
    fun authorization(): String {
        fun q(s: String) = s.replace("\"", "").replace(",", " ").filterNot { it.isISOControl() }
        val parts = mutableListOf(
            "Client=\"${q(info.client)}\"",
            "Device=\"${q(info.device)}\"",
            "DeviceId=\"${q(info.deviceId)}\"",
            "Version=\"${q(info.version)}\"",
        )
        if (token != null) parts += "Token=\"${q(token)}\""
        return "MediaBrowser " + parts.joinToString(", ")
    }

    /** [path] ("Items/abc/PlaybackInfo") under the server's base with [query]. */
    fun url(path: String, query: Map<String, Any?> = emptyMap()): HttpUrl {
        val b = base.newBuilder()
        path.trim('/').split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
        for ((k, v) in query) if (v != null) b.addQueryParameter(k, v.toString())
        return b.build()
    }

    /**
     * A path the server gave (a converting URL "/videos/…/master.m3u8?…", a subtitle's URL) as an absolute URL under
     * [base]. An absolute URL is taken by its path and query only: the access header never goes to another host.
     */
    fun absolute(relative: String): String {
        val pathAndQuery = if (relative.startsWith("http://", true) || relative.startsWith("https://", true)) {
            relative.toHttpUrlOrNull()?.let { u -> u.encodedPath + (u.encodedQuery?.let { "?$it" } ?: "") } ?: "/"
        } else {
            if (relative.startsWith('/')) relative else "/$relative"
        }
        val root = base.newBuilder().encodedPath("/").query(null).build().toString().trimEnd('/')
        val basePath = base.encodedPath.trimEnd('/')
        // the server's paths carry its base path already when it has one ("/jellyfin/videos/…")
        val path = if (basePath.isNotEmpty() && pathAndQuery.startsWith("$basePath/")) pathAndQuery else basePath + pathAndQuery
        return root + path
    }

    suspend fun get(path: String, query: Map<String, Any?> = emptyMap()): MediaResult<JsonElement?> =
        call(Request.Builder().url(url(path, query)).get())

    suspend fun post(path: String, query: Map<String, Any?> = emptyMap(), body: JsonObject? = null): MediaResult<JsonElement?> =
        call(Request.Builder().url(url(path, query)).post((body?.toString() ?: "").toRequestBody(JSON)))

    suspend fun delete(path: String, query: Map<String, Any?> = emptyMap()): MediaResult<JsonElement?> =
        call(Request.Builder().url(url(path, query)).delete())

    /** `GET` of [path] read to its end for [size] bytes: the bit/s, or null when it did not work. */
    suspend fun measure(path: String, query: Map<String, Any?>, now: () -> Long = System::nanoTime): Long? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url(path, query)).header("Authorization", authorization()).get().build()
        try {
            val response = http.newCall(request).await()
            response.use { r ->
                if (!r.isSuccessful) return@withContext null
                val body = r.body
                val source = body.source()
                val start = now()
                var total = 0L
                val buf = ByteArray(64 * 1024)
                val stream = source.inputStream()
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    total += n
                }
                val nanos = (now() - start).coerceAtLeast(1)
                if (total <= 0) null else total * 8 * 1_000_000_000L / nanos
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Jellyfin: bandwidth not measured (%s)", e.javaClass.simpleName)
            null
        }
    }

    private suspend fun call(builder: Request.Builder): MediaResult<JsonElement?> = withContext(Dispatchers.IO) {
        val request = builder.header("Authorization", authorization()).header("Accept", "application/json").build()
        val response = try {
            http.newCall(request).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.d("Jellyfin: %s not reached (%s)", request.url.encodedPath, e.javaClass.simpleName)
            return@withContext MediaResult.Offline
        }
        response.use { r ->
            when {
                r.code == 401 || r.code == 403 -> MediaResult.Unauthorized
                r.code == 404 -> MediaResult.NotFound
                !r.isSuccessful -> MediaResult.Failed(r.code)
                else -> {
                    val text = try {
                        r.body.string()
                    } catch (e: IOException) {
                        return@use MediaResult.Offline
                    }
                    if (text.isBlank()) {
                        MediaResult.Ok(null)
                    } else {
                        runCatching { JsonParser.parseString(text) }.fold(
                            onSuccess = { MediaResult.Ok(it) },
                            onFailure = { MediaResult.Failed(r.code) },
                        )
                    }
                }
            }
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** [Call.enqueue] as a suspending call, cancelled with the coroutine. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, value, _ -> value.close() }
            }
        },
    )
}
