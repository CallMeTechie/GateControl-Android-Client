package com.gatecontrol.android.door

import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.Response
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a call to the door plugin's app API. */
sealed interface DoorResult<out T> {
    data class Ok<T>(val value: T) : DoorResult<T>

    /**
     * The server answered with an error: [code] from `{ok:false, code}` or
     * `http_<status>`; [retryAfter] (s) with too_soon / rate_limited;
     * [text] the plugin's own wording of it, when sent.
     */
    data class Failed(val code: String, val http: Int, val retryAfter: Int? = null, val text: String? = null) : DoorResult<Nothing>

    /** No answer (network, VPN, timeout). */
    data object Offline : DoorResult<Nothing>

    data object NotConfigured : DoorResult<Nothing>
}

fun <T> DoorResult<T>.valueOrNull(): T? = (this as? DoorResult.Ok)?.value

fun DoorResult<*>.errorCode(): String? = when (this) {
    is DoorResult.Failed -> code
    DoorResult.Offline, DoorResult.NotConfigured -> DoorApi.CODE_OFFLINE
    is DoorResult.Ok -> null
}

/**
 * The door plugin's app API through the core server's client plugin bridge
 * (`/api/v1/client/plugins/gatecontrol-door/api/…`): one method per
 * endpoint, typed answers from [DoorJson]. Never throws. Paths are built
 * from fixed words and server ids only.
 */
@Singleton
class DoorApi @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
    private val settingsRepository: SettingsRepository,
) {
    companion object {
        const val CODE_OFFLINE = "offline"
        const val CODE_INVALID = "invalid_answer"
        private const val TIMEOUT_MS = 20_000L
        /** Lock actions take a moment (bridge, motor): a longer wait. */
        private const val ACTION_TIMEOUT_MS = 40_000L
    }

    /** `?lang=` in the app's language, so `message` / `reasonText` match it. */
    private suspend fun lang(): String =
        runCatching { settingsRepository.getLocale().first() }.getOrNull()?.takeIf { it == "de" || it == "en" } ?: "de"

    private suspend fun <T> call(
        request: suspend ApiClient.(lang: String) -> Response<JsonObject>,
        parse: (JsonObject) -> T?,
        timeoutMs: Long = TIMEOUT_MS,
    ): DoorResult<T> {
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) return DoorResult.NotConfigured
        return try {
            val lang = lang()
            val response = withTimeoutOrNull(timeoutMs) { apiClientProvider.getClient(url).request(lang) }
                ?: return DoorResult.Offline
            if (response.isSuccessful) {
                val body = response.body() ?: JsonObject()
                val okEl = body.get("ok")
                if (okEl != null && okEl.isJsonPrimitive && okEl.asJsonPrimitive.isBoolean && !okEl.asBoolean) {
                    return failed(body, response.code())
                }
                parse(body)?.let { DoorResult.Ok(it) } ?: DoorResult.Failed(CODE_INVALID, response.code())
            } else {
                val err = runCatching {
                    response.errorBody()?.use { b -> b.string().takeIf { it.length < 16_384 } }
                        ?.let { JsonParser.parseString(it).takeIf { e -> e.isJsonObject }?.asJsonObject }
                }.getOrNull()
                Timber.d("Door: HTTP %d", response.code())
                failed(err, response.code())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            DoorResult.Offline
        } catch (e: Exception) {
            Timber.d("Door: call failed (%s)", e.javaClass.simpleName)
            DoorResult.Failed(CODE_INVALID, 0)
        }
    }

    private fun failed(body: JsonObject?, http: Int): DoorResult.Failed = DoorResult.Failed(
        code = DoorJson.errorCode(body) ?: if (body != null && http in 200..299) "failed" else "http_$http",
        http = http,
        retryAfter = DoorJson.retryAfter(body),
        text = DoorJson.errorText(body),
    )

    private fun query(lang: String, extra: Map<String, String> = emptyMap()) = extra + ("lang" to lang)

    // ─── endpoints ──────────────────────────────────────────────────

    /** `GET me`, also as raw JSON for the offline cache. */
    suspend fun meRaw(): DoorResult<Pair<DoorOverview, JsonObject>> =
        call({ lang -> pluginGet(DOOR_PLUGIN, "me", query(lang)) }, { body -> DoorJson.overview(body)?.let { it to body } })

    suspend fun lockAction(lockId: Long, action: String): DoorResult<DoorActionResult> {
        if (lockId <= 0 || action !in DoorAction.ALL) return DoorResult.Failed(CODE_INVALID, 0)
        return call({ lang -> pluginPost(DOOR_PLUGIN, "locks/$lockId/$action", JsonObject(), query(lang)) }, DoorJson::actionResult, ACTION_TIMEOUT_MS)
    }

    suspend fun snapshot(cameraId: Long, refresh: Boolean): DoorResult<DoorSnapshot> {
        if (cameraId <= 0) return DoorResult.Failed(CODE_INVALID, 0)
        val q = if (refresh) mapOf("refresh" to "1") else emptyMap()
        return call({ lang -> pluginGet(DOOR_PLUGIN, "cameras/$cameraId/snapshot", query(lang, q)) }, DoorJson::snapshot, if (refresh) ACTION_TIMEOUT_MS else TIMEOUT_MS)
    }

    suspend fun setPrefs(cameraId: Long, ding: Boolean? = null, motion: Boolean? = null): DoorResult<DoorCameraPrefs> {
        if (cameraId <= 0) return DoorResult.Failed(CODE_INVALID, 0)
        val body = JsonObject().apply {
            ding?.let { addProperty("ding", it) }
            motion?.let { addProperty("motion", it) }
        }
        return call({ lang -> pluginPut(DOOR_PLUGIN, "cameras/$cameraId/prefs", body, query(lang)) }, DoorJson::prefs)
    }

    suspend fun history(cursor: Long?, kind: String?, limit: Int = 30): DoorResult<DoorHistoryPage> {
        val q = buildMap {
            put("limit", limit.coerceIn(1, 100).toString())
            if (cursor != null && cursor > 0) put("cursor", cursor.toString())
            if (kind != null && kind in DoorHistoryItem.KINDS) put("kind", kind)
        }
        return call({ lang -> pluginGet(DOOR_PLUGIN, "history", query(lang, q)) }, DoorJson::history)
    }

    suspend fun event(eventId: Long): DoorResult<DoorEventDetail> {
        if (eventId <= 0) return DoorResult.Failed(CODE_INVALID, 0)
        return call({ lang -> pluginGet(DOOR_PLUGIN, "events/$eventId", query(lang)) }, DoorJson::event)
    }
}
