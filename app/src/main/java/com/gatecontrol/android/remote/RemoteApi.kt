package com.gatecontrol.android.remote

import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import retrofit2.Response
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a call of the screen sharing (plugin „Fernzugriff“ and the core's token). */
sealed interface RemoteResult<out T> {
    data class Ok<T>(val value: T) : RemoteResult<T>

    /** The server answered with an error: [code] of `{ ok:false, code }` or `http_<status>`. */
    data class Failed(val code: String, val http: Int) : RemoteResult<Nothing>

    /** No answer (network, timeout) or no server set up. */
    data object Offline : RemoteResult<Nothing>
}

/**
 * The app API of the plugin „Fernzugriff“ for this phone (`/api/v1/client/plugins/gatecontrol-remote/api/host/…`,
 * contract „Fernzugriff Stufe 5“ §5) and the core's token of the relay. Never throws; never logs a code, a ref or a
 * token. Paths are built from fixed words and checked request ids only.
 */
@Singleton
class RemoteApi @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
) {
    companion object {
        private const val TIMEOUT_MS = 20_000L
        private val REQUEST_ID = Regex("^rq_[A-Za-z0-9_-]{8,40}$")
        const val CODE_INVALID = "invalid_answer"
    }

    private suspend fun <T> call(request: suspend ApiClient.() -> Response<JsonObject>, parse: (JsonObject) -> T?): RemoteResult<T> {
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) return RemoteResult.Offline
        return try {
            val response = withTimeoutOrNull(TIMEOUT_MS) { apiClientProvider.getClient(url).request() } ?: return RemoteResult.Offline
            if (response.isSuccessful) {
                val body = response.body() ?: JsonObject()
                val ok = body.get("ok")
                if (ok != null && ok.isJsonPrimitive && ok.asJsonPrimitive.isBoolean && !ok.asBoolean) {
                    return RemoteResult.Failed(RemoteJson.errorCode(body) ?: "failed", response.code())
                }
                parse(body)?.let { RemoteResult.Ok(it) } ?: RemoteResult.Failed(CODE_INVALID, response.code())
            } else {
                val err = runCatching {
                    response.errorBody()?.use { b -> b.string().takeIf { it.length < 16_384 } }
                        ?.let { JsonParser.parseString(it).takeIf { e -> e.isJsonObject }?.asJsonObject }
                }.getOrNull()
                Timber.d("Fernzugriff: HTTP %d", response.code())
                RemoteResult.Failed(RemoteJson.errorCode(err) ?: "http_${response.code()}", response.code())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            RemoteResult.Offline
        } catch (e: Exception) {
            Timber.d("Fernzugriff: call failed (%s)", e.javaClass.simpleName)
            RemoteResult.Failed(CODE_INVALID, 0)
        }
    }

    private fun post(path: String, body: JsonObject = JsonObject()): suspend ApiClient.() -> Response<JsonObject> = { pluginPost(REMOTE_PLUGIN, path, body) }

    /** Switch on: this phone becomes a device of its owner that shares its screen. */
    suspend fun register(name: String, appVersion: String): RemoteResult<ScreenHost> = call(post("host/register", JsonObject().apply {
        addProperty("platform", "android")
        addProperty("name", name.take(100))
        addProperty("appVersion", appVersion.take(40))
        add("screen", JsonObject().apply {
            addProperty("view", true)
            addProperty("control", false)
        })
    }), RemoteJson::host)

    /** The heartbeat: still here, and the questions a lost signal did not bring. */
    suspend fun heartbeat(): RemoteResult<ScreenState> =
        call(post("host/state", JsonObject().apply { addProperty("online", true) }), RemoteJson::state)

    suspend fun setShareMode(open: Boolean): RemoteResult<String> = call({
        pluginPut(REMOTE_PLUGIN, "host/share", JsonObject().apply { addProperty("shareMode", if (open) "open" else "code") })
    }, RemoteJson::shareMode)

    /** Switch off: the code is gone, a running sharing ends. */
    suspend fun unregister(): RemoteResult<Unit> = call({ pluginDelete(REMOTE_PLUGIN, "host") }, { })

    suspend fun newCode(): RemoteResult<ScreenCode> = call(post("host/code"), RemoteJson::code)

    /** The person's answer; allowed → the sending side of GateControl's relay ticket ([ScreenDecision.ticket], null when denied). */
    suspend fun decide(requestId: String, allow: Boolean): RemoteResult<ScreenDecision> {
        if (!REQUEST_ID.matches(requestId)) return RemoteResult.Failed(CODE_INVALID, 0)
        return call(post("host/requests/$requestId", JsonObject().apply {
            addProperty("decision", if (allow) "allow" else "deny")
            addProperty("mode", "view")
        })) { body ->
            if (!allow) ScreenDecision(null) else RemoteJson.ticket(body)?.let { ScreenDecision(it) }
        }
    }

    /** The request ended on this phone before the picture ran (Android's question declined, stopped, failed). */
    suspend fun endRequest(requestId: String, reason: String): RemoteResult<Unit> {
        if (!REQUEST_ID.matches(requestId)) return RemoteResult.Failed(CODE_INVALID, 0)
        val r = if (reason in setOf("consent_denied", "stopped", "failed")) reason else "stopped"
        return call(post("host/screen/$requestId/end", JsonObject().apply { addProperty("reason", r) }), { })
    }

    /** A single-use token (60 s) of GateControl for the sending side of [ticket]. */
    suspend fun token(ticket: ScreenTicket): RemoteResult<ScreenToken> =
        call({ remoteScreenToken(JsonObject().apply { addProperty("ref", ticket.ref) }) }, RemoteJson::token)

    /** The server's base address and an OkHttp client with its DNS and headers (for the WebSocket of the relay). */
    fun relayClient(): Pair<String, OkHttpClient>? {
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) return null
        return url to apiClientProvider.mediaStreamClient(url)
    }
}
