package com.gatecontrol.android.familie

import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.Response
import timber.log.Timber
import java.io.IOException

/**
 * Calls to a plugin's app API through the core server's client plugin bridge
 * (`/api/v1/client/plugins/<plugin>/api/…`, contract A3), shared by the
 * Familie, Alltag and Schule calls: `{ok:false, code}` and HTTP errors become
 * [FamilieResult.Failed], no answer [FamilieResult.Offline]. Never throws
 * (except for cancellation).
 */
class FamiliePluginCalls(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
) {
    companion object {
        const val CODE_OFFLINE = "offline"
        const val CODE_INVALID = "invalid_answer"
        const val TIMEOUT_MS = 20_000L

        /** No slashes, no leading dot (`..`): one plain path segment. */
        private val SEGMENT_RE = Regex("^[A-Za-z0-9_:-][A-Za-z0-9_.:-]{0,63}$")

        /** Path from fixed words and ids; an id that is not a plain token is refused. */
        fun path(vararg segments: Any): String? {
            val parts = segments.map { it.toString() }
            return if (parts.all { SEGMENT_RE.matches(it) }) parts.joinToString("/") else null
        }
    }

    /** One call; [slow]: on the media client (long read timeout, no call limit) for calls the plugin needs long for. */
    suspend fun <T> call(
        plugin: String = FAMILIE_PLUGIN,
        request: suspend ApiClient.() -> Response<JsonObject>,
        parse: (JsonObject) -> T?,
        timeoutMs: Long = TIMEOUT_MS,
        slow: Boolean = false,
    ): FamilieResult<T> {
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) return FamilieResult.NotConfigured
        return try {
            val client = if (slow) apiClientProvider.mediaClient(url) else apiClientProvider.getClient(url)
            val response = withTimeoutOrNull(timeoutMs) { client.request() }
                ?: return FamilieResult.Offline
            if (response.isSuccessful) {
                val body = response.body() ?: JsonObject()
                if (body.has("ok") && body.get("ok").let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && !it.asBoolean }) {
                    return failed(body, response.code())
                }
                parse(body)?.let { FamilieResult.Ok(it) } ?: FamilieResult.Failed(CODE_INVALID, response.code())
            } else {
                val err = runCatching {
                    response.errorBody()?.use { b -> b.string().takeIf { it.length < 16_384 } }
                        ?.let { JsonParser.parseString(it).takeIf { e -> e.isJsonObject }?.asJsonObject }
                }.getOrNull()
                Timber.d("Familie: %s answered HTTP %d", plugin, response.code())
                failed(err, response.code())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            FamilieResult.Offline
        } catch (e: Exception) {
            Timber.d("Familie: call failed (%s)", e.javaClass.simpleName)
            FamilieResult.Failed(CODE_INVALID, 0)
        }
    }

    private fun failed(body: JsonObject?, http: Int): FamilieResult.Failed = FamilieResult.Failed(
        code = FamilieHomeJson.errorCode(body) ?: if (body != null && http in 200..299) "failed" else "http_$http",
        http = http,
        retryAfter = FamilieHomeJson.retryAfter(body),
        requestId = FamilieHomeJson.refusedRequestId(body),
        field = FamilieHomeJson.errorField(body),
        detail = FamilieHomeJson.errorDriver(body),
    )

    suspend fun <T> get(path: String?, query: Map<String, String> = emptyMap(), plugin: String = FAMILIE_PLUGIN, parse: (JsonObject) -> T?): FamilieResult<T> {
        val p = path ?: return FamilieResult.Failed(CODE_INVALID, 0)
        return call(plugin, { pluginGet(plugin, p, query) }, parse)
    }

    suspend fun <T> post(path: String?, body: JsonObject = JsonObject(), parse: (JsonObject) -> T?): FamilieResult<T> {
        val p = path ?: return FamilieResult.Failed(CODE_INVALID, 0)
        return call(FAMILIE_PLUGIN, { pluginPost(FAMILIE_PLUGIN, p, body) }, parse)
    }

    /** A POST without a body to another plugin's app API; [slow] for calls the plugin needs long for. */
    suspend fun <T> postTo(
        plugin: String,
        path: String?,
        timeoutMs: Long = TIMEOUT_MS,
        slow: Boolean = false,
        parse: (JsonObject) -> T?,
    ): FamilieResult<T> {
        val p = path ?: return FamilieResult.Failed(CODE_INVALID, 0)
        return call(plugin, { pluginPost(plugin, p, JsonObject()) }, parse, timeoutMs, slow)
    }

    suspend fun <T> patch(path: String?, body: JsonObject, query: Map<String, String> = emptyMap(), parse: (JsonObject) -> T?): FamilieResult<T> {
        val p = path ?: return FamilieResult.Failed(CODE_INVALID, 0)
        return call(FAMILIE_PLUGIN, { pluginPatch(FAMILIE_PLUGIN, p, body, query) }, parse)
    }

    suspend fun <T> put(path: String?, body: JsonObject, parse: (JsonObject) -> T?): FamilieResult<T> {
        val p = path ?: return FamilieResult.Failed(CODE_INVALID, 0)
        return call(FAMILIE_PLUGIN, { pluginPut(FAMILIE_PLUGIN, p, body) }, parse)
    }

    suspend fun delete(path: String?, query: Map<String, String> = emptyMap()): FamilieResult<Unit> {
        val p = path ?: return FamilieResult.Failed(CODE_INVALID, 0)
        return call(FAMILIE_PLUGIN, { pluginDelete(FAMILIE_PLUGIN, p, query) }, parse = { })
    }
}
