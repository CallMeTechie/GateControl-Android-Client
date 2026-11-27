package com.gatecontrol.android.media.server

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
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/** An answer of the plugin: its value, or the refusal (`code`, or [MediaServerError.OFFLINE] without an answer). */
sealed interface MediaServerResult<out T> {
    data class Ok<T>(val value: T) : MediaServerResult<T>

    data class Refused(val error: MediaServerError) : MediaServerResult<Nothing>

    fun valueOrNull(): T? = (this as? Ok<T>)?.value
}

/**
 * The routes of the plugin „Medien“ the apps use (App-API v1, README of gatecontrol-medien), through the server's
 * plugin bridge with the device token. Nothing here keeps state; [MediaServerSources] does. Never throws except for
 * cancellation. A password of [secret] is never logged.
 */
interface MediaServerApi {
    suspend fun sources(since: String?): MediaServerResult<MediaServerList>

    suspend fun secret(sourceId: String): MediaServerResult<MediaShareSecret>

    /** `POST v1/jellyfin/pair { sourceId, code }`: the plugin confirms this device's Quick Connect code. */
    suspend fun pair(sourceId: String, code: String): MediaServerResult<Unit>

    suspend fun paired(sourceId: String, jellyfinDeviceId: String): MediaServerResult<Unit>

    /** `DELETE v1/jellyfin/device[?sourceId=]`: this device signed out of Jellyfin (null: of all Jellyfin sources). */
    suspend fun deleteDevice(sourceId: String?): MediaServerResult<Unit>

    suspend fun checked(sourceId: String, ok: Boolean, problem: String?): MediaServerResult<Unit>

    suspend fun report(sourceId: String, way: String, ok: Boolean, problem: String?): MediaServerResult<Unit>

    /** `GET /api/v1/client/plugins`: the plugin has an app tab for this person (true), none (false), not known (null). */
    suspend fun tab(): Boolean?
}

/**
 * The plugin's cast routes („Auf TV/PC abspielen“, cast contract sections 2 and 3): the own TVs and PCs, and one
 * message to exactly one of them. Refusals as `code`: `forbidden` (cast off), `no_access`, `rate_limited` (60 a minute,
 * 600 an hour per sending device), `too_large` (more than 3000 bytes), `invalid`.
 */
interface MediaCastApi {
    /** `GET v1/cast/targets`. */
    suspend fun castTargets(): MediaServerResult<com.gatecontrol.android.media.cast.CastTargetList>

    /** `POST v1/cast/{session}/message { to, body }`. */
    suspend fun castMessage(to: String, message: com.gatecontrol.android.media.cast.CastMessage): MediaServerResult<Unit>
}

@Singleton
class MediaServerHttpApi @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
) : MediaServerApi, MediaCastApi, MediaConvertApi {

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    private suspend fun <T> call(request: suspend ApiClient.() -> Response<JsonObject>, parse: (JsonObject) -> T?): MediaServerResult<T> {
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) return MediaServerResult.Refused(MediaServerError(0, "not_configured"))
        return try {
            val response = withTimeoutOrNull(TIMEOUT_MS) { apiClientProvider.getClient(url).request() }
                ?: return MediaServerResult.Refused(MediaServerError(0, MediaServerError.OFFLINE))
            val body = if (response.isSuccessful) {
                response.body()
            } else {
                runCatching {
                    response.errorBody()?.use { b -> b.string().takeIf { it.length < 65_536 } }
                        ?.let { JsonParser.parseString(it).takeIf { e -> e.isJsonObject }?.asJsonObject }
                }.getOrNull()
            }
            if (!response.isSuccessful || body?.get("ok")?.let { it.isJsonPrimitive && !it.asBoolean } == true) {
                return MediaServerResult.Refused(MediaServerJson.error(response.code(), body))
            }
            parse(body ?: JsonObject())?.let { MediaServerResult.Ok(it) } ?: MediaServerResult.Refused(MediaServerError(response.code(), "invalid"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            MediaServerResult.Refused(MediaServerError(0, MediaServerError.OFFLINE))
        } catch (e: Exception) {
            Timber.d("Medien: plugin call failed (%s)", e.javaClass.simpleName)
            MediaServerResult.Refused(MediaServerError(0, "invalid"))
        }
    }

    private val unit: (JsonObject) -> Unit = { }

    override suspend fun sources(since: String?) =
        call({ pluginGet(MEDIA_PLUGIN, "v1/sources", since?.let { mapOf("since" to it) } ?: emptyMap()) }, MediaServerJson::list)

    override suspend fun secret(sourceId: String): MediaServerResult<MediaShareSecret> {
        if (!MediaServerJson.isSourceId(sourceId)) return MediaServerResult.Refused(MediaServerError(0, "invalid"))
        return call({ pluginGet(MEDIA_PLUGIN, "v1/sources/${enc(sourceId)}/secret") }, MediaServerJson::secret)
    }

    override suspend fun pair(sourceId: String, code: String) = call(
        {
            pluginPost(MEDIA_PLUGIN, "v1/jellyfin/pair", JsonObject().apply { addProperty("sourceId", sourceId); addProperty("code", code) })
        },
        unit,
    )

    override suspend fun paired(sourceId: String, jellyfinDeviceId: String) = call(
        {
            pluginPost(MEDIA_PLUGIN, "v1/jellyfin/paired", JsonObject().apply { addProperty("sourceId", sourceId); addProperty("jellyfinDeviceId", jellyfinDeviceId) })
        },
        unit,
    )

    override suspend fun deleteDevice(sourceId: String?) =
        call({ pluginDelete(MEDIA_PLUGIN, "v1/jellyfin/device", sourceId?.let { mapOf("sourceId" to it) } ?: emptyMap()) }, unit)

    override suspend fun checked(sourceId: String, ok: Boolean, problem: String?): MediaServerResult<Unit> {
        if (!MediaServerJson.isSourceId(sourceId)) return MediaServerResult.Refused(MediaServerError(0, "invalid"))
        val body = JsonObject().apply {
            addProperty("protocol", "smb")
            addProperty("ok", ok)
            problem?.let { addProperty("problem", it) }
        }
        return call({ pluginPost(MEDIA_PLUGIN, "v1/shares/${enc(sourceId)}/checked", body) }, unit)
    }

    override suspend fun report(sourceId: String, way: String, ok: Boolean, problem: String?): MediaServerResult<Unit> {
        if (!MediaServerJson.isSourceId(sourceId)) return MediaServerResult.Refused(MediaServerError(0, "invalid"))
        val body = JsonObject().apply {
            addProperty("way", way)
            addProperty("ok", ok)
            problem?.let { addProperty("problem", it) }
        }
        return call({ pluginPost(MEDIA_PLUGIN, "v1/sources/${enc(sourceId)}/report", body) }, unit)
    }

    override suspend fun castTargets() =
        call({ pluginGet(MEDIA_PLUGIN, "v1/cast/targets") }, com.gatecontrol.android.media.cast.CastEnvelopes::targetList)

    override suspend fun castMessage(to: String, message: com.gatecontrol.android.media.cast.CastMessage): MediaServerResult<Unit> {
        if (!com.gatecontrol.android.media.cast.CastProtocol.validSession(message.session) || !com.gatecontrol.android.media.cast.CastEnvelopes.validDevice(to)) {
            return MediaServerResult.Refused(MediaServerError(0, "invalid"))
        }
        val body = com.gatecontrol.android.media.cast.CastEnvelopes.request(to, message)
        return call({ pluginPost(MEDIA_PLUGIN, "v1/cast/${message.session}/message", body) }, unit)
    }

    override suspend fun convertStatus(itemIds: List<String>): MediaServerResult<MediaConvertStatus> {
        val ids = itemIds.mapNotNull(MediaConvertLogic::itemId).distinct()
        if (ids.isEmpty() || ids.size > MediaConvertLogic.MAX_IDS) return MediaServerResult.Refused(MediaServerError(0, "invalid"))
        return call({ pluginGet(MEDIA_PLUGIN, "v1/convert/status", mapOf("items" to ids.joinToString(","))) }, MediaConvertLogic::status)
    }

    override suspend fun convertRequest(itemId: String): MediaServerResult<MediaConvertItem> {
        val id = MediaConvertLogic.itemId(itemId) ?: return MediaServerResult.Refused(MediaServerError(0, "invalid"))
        return call({ pluginPost(MEDIA_PLUGIN, "v1/convert/request", JsonObject().apply { addProperty("itemId", id) }) }) { MediaConvertLogic.requested(id, it) }
    }

    override suspend fun tab(): Boolean? {
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) return false
        return try {
            val r = withTimeoutOrNull(TIMEOUT_MS) { apiClientProvider.getClient(url).getClientPlugins() } ?: return null
            when {
                r.isSuccessful -> r.body()?.plugins.orEmpty().any { it.id == MEDIA_PLUGIN && it.tab != null }
                r.code() == 404 || r.code() == 401 || r.code() == 403 -> false
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
