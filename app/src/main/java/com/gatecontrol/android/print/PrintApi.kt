package com.gatecontrol.android.print

import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.Response
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The print plugin's person API (plugin README "API") through the core's plugin bridge
 * (`/api/v1/client/plugins/gatecontrol-print/api/…`) and the core's media upload (`PUT …/media`). Uses the app's
 * normal server connection (public address, with or without the VPN): the server hands the job to the gateway at
 * home, which prints over IPP. The rights (print at all, pages a day, black and white only) are the plugin's — it
 * checks them on every call. Never throws (except cancellation).
 */
@Singleton
class PrintApi @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
    private val settingsRepository: SettingsRepository,
) {
    companion object {
        const val CODE_INVALID = "invalid_answer"
        private const val TIMEOUT_MS = 25_000L
        private const val UPLOAD_TIMEOUT_MS = 10 * 60_000L
    }

    private suspend fun lang(): Map<String, String> =
        mapOf("lang" to (runCatching { settingsRepository.getLocale().first() }.getOrNull()?.takeIf { it == "de" || it == "en" } ?: "de"))

    private suspend fun <T> call(
        timeoutMs: Long = TIMEOUT_MS,
        media: Boolean = false,
        request: suspend ApiClient.() -> Response<JsonObject>,
        parse: (JsonObject) -> T?,
    ): PResult<T> {
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) return PResult.NotConfigured
        return try {
            val client = if (media) apiClientProvider.mediaClient(url) else apiClientProvider.getClient(url)
            val response = withTimeoutOrNull(timeoutMs) { client.request() } ?: return PResult.Offline
            if (response.isSuccessful) {
                val body = response.body() ?: JsonObject()
                if (PrintJson.isRefusal(body)) return failed(response.code(), body)
                parse(body)?.let { PResult.Ok(it) } ?: PResult.Failed(CODE_INVALID, response.code())
            } else {
                failed(response.code(), errorJson(response))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            PResult.Offline
        } catch (e: Exception) {
            Timber.d("Print: call failed (%s)", e.javaClass.simpleName)
            PResult.Failed(CODE_INVALID, 0)
        }
    }

    private fun failed(status: Int, o: JsonObject?): PResult.Failed = PResult.Failed(
        code = PrintJson.errorCode(o) ?: "http_$status",
        status = status,
        text = PrintJson.errorText(o),
        left = PrintJson.errorLeft(o),
        pages = PrintJson.errorPages(o),
    )

    private fun errorJson(response: Response<*>): JsonObject? = runCatching {
        response.errorBody()?.use { b -> b.string().takeIf { it.length < 65_536 } }
            ?.let { JsonParser.parseString(it).takeIf { e -> e.isJsonObject }?.asJsonObject }
    }.getOrNull()

    /**
     * Rights, pages left today, the printers the person may use (none without the print right) and the last jobs.
     * `Failed(not_found, 404)`: the plugin is not there for the apps (not installed, not licensed, stopped).
     */
    suspend fun overview(): PResult<POverview> =
        call(request = { pluginGet(PRINT_PLUGIN, "overview", lang()) }, parse = PrintJson::overview)

    /** A file of this device → the plugin's media id. [mime] must be one of [PrintLogic.UPLOAD_TYPES]. */
    suspend fun upload(file: File, mime: String): PResult<String> {
        val type = mime.toMediaTypeOrNull() ?: return PResult.Failed("unsupported_type", 0)
        return call(UPLOAD_TIMEOUT_MS, media = true, request = { pluginMediaUpload(PRINT_PLUGIN, file.asRequestBody(type)) }, parse = PrintJson::mediaId)
    }

    /** `POST print`: the plugin checks the rights, counts the pages and hands the job to the gateway. */
    suspend fun print(deviceId: String, mediaId: String, copies: Int, color: Boolean, duplex: Boolean, name: String, media: String? = null): PResult<PPrinted> {
        if (!PrintJson.validDeviceId(deviceId)) return PResult.Failed("invalid", 0)
        val body = JsonObject().apply {
            addProperty("deviceId", deviceId)
            addProperty("mediaId", mediaId)
            addProperty("copies", copies.coerceIn(1, 99))
            addProperty("color", color)
            addProperty("duplex", duplex)
            addProperty("name", PrintLogic.jobName(name))
            // the paper chosen in the print dialog (plugin 1.1.0; only one the printer names)
            if (PrintJson.validMedia(media)) addProperty("media", media)
        }
        return call(timeoutMs = 60_000L, request = { pluginPost(PRINT_PLUGIN, "print", body, lang()) }, parse = PrintJson::printed)
    }

    /**
     * `GET jobs/<id>` (plugin 1.1.0): one job of the person, fresh from GateControl – with the printer's stop reason
     * (`message`) and the pages after converting. An older plugin answers `not_found`.
     */
    suspend fun job(jobId: String): PResult<PJob> {
        if (!PrintJson.validJobId(jobId)) return PResult.Failed("invalid", 0)
        return call(request = { pluginGet(PRINT_PLUGIN, "jobs/$jobId", lang()) }, parse = PrintJson::jobAnswer)
    }

    /** `POST jobs/<id>/cancel` (only while queued or waiting) → whether it was canceled. */
    suspend fun cancel(jobId: String): PResult<Boolean> {
        if (!PrintJson.validJobId(jobId)) return PResult.Failed("invalid", 0)
        return call(request = { pluginPost(PRINT_PLUGIN, "jobs/$jobId/cancel", JsonObject(), lang()) }, parse = PrintJson::canceled)
    }
}
