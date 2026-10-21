package com.gatecontrol.android.logbook

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

/** Outcome of a call to the logbook API of the vehicles plugin. */
sealed interface LogbookResult<out T> {
    data class Ok<T>(val value: T) : LogbookResult<T>

    /** The server answered with an error: [code] from `{ok:false, code}` or `http_<status>`; [text] the plugin's wording. */
    data class Failed(val code: String, val http: Int, val text: String? = null) : LogbookResult<Nothing>

    /** No answer (network, VPN, timeout). */
    data object Offline : LogbookResult<Nothing>

    data object NotConfigured : LogbookResult<Nothing>
}

fun LogbookResult<*>.errorCode(): String? = when (this) {
    is LogbookResult.Failed -> code
    LogbookResult.Offline, LogbookResult.NotConfigured -> LogbookApi.CODE_OFFLINE
    is LogbookResult.Ok -> null
}

/**
 * The logbook ("Fahrtenbuch") of the vehicles plugin through the core
 * server's client plugin bridge
 * (`/api/v1/client/plugins/gatecontrol-skoda/api/app/logbook…`, plugin
 * 1.6+): one method per endpoint, typed answers from [LogbookJson]. Never
 * throws. Paths are built from fixed words and server ids only.
 */
@Singleton
class LogbookApi @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
    private val settingsRepository: SettingsRepository,
) {
    companion object {
        const val CODE_OFFLINE = "offline"
        const val CODE_INVALID = "invalid_answer"
        private const val TIMEOUT_MS = 20_000L
        /** A PDF of many months takes the plugin a moment. */
        private const val EXPORT_TIMEOUT_MS = 60_000L
        private const val BASE = "app/logbook"
        const val FORMAT_PDF = "pdf"
        const val FORMAT_CSV = "csv"
        private val FORMATS = setOf(FORMAT_PDF, FORMAT_CSV)
    }

    /** `?lang=` in the app's language, so texts (`dateLabel`, errors, the PDF) match it. */
    private suspend fun lang(): String =
        runCatching { settingsRepository.getLocale().first() }.getOrNull()?.takeIf { it == "de" || it == "en" } ?: "de"

    private suspend fun <T> call(
        request: suspend ApiClient.(lang: String) -> Response<JsonObject>,
        parse: (JsonObject) -> T?,
        timeoutMs: Long = TIMEOUT_MS,
    ): LogbookResult<T> {
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) return LogbookResult.NotConfigured
        return try {
            val lang = lang()
            val response = withTimeoutOrNull(timeoutMs) { apiClientProvider.getClient(url).request(lang) }
                ?: return LogbookResult.Offline
            if (response.isSuccessful) {
                val body = response.body() ?: JsonObject()
                val okEl = body.get("ok")
                if (okEl != null && okEl.isJsonPrimitive && okEl.asJsonPrimitive.isBoolean && !okEl.asBoolean) {
                    return failed(body, response.code())
                }
                parse(body)?.let { LogbookResult.Ok(it) } ?: LogbookResult.Failed(CODE_INVALID, response.code())
            } else {
                val err = runCatching {
                    response.errorBody()?.use { b -> b.string().takeIf { it.length < 16_384 } }
                        ?.let { JsonParser.parseString(it).takeIf { e -> e.isJsonObject }?.asJsonObject }
                }.getOrNull()
                Timber.d("Logbook: HTTP %d", response.code())
                failed(err, response.code())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            LogbookResult.Offline
        } catch (e: Exception) {
            Timber.d("Logbook: call failed (%s)", e.javaClass.simpleName)
            LogbookResult.Failed(CODE_INVALID, 0)
        }
    }

    private fun failed(body: JsonObject?, http: Int): LogbookResult.Failed = LogbookResult.Failed(
        code = LogbookJson.errorCode(body) ?: if (body != null && http in 200..299) "failed" else "http_$http",
        http = http,
        text = LogbookJson.errorText(body),
    )

    private fun query(lang: String, extra: Map<String, String> = emptyMap()) = extra + ("lang" to lang)

    // ─── endpoints ──────────────────────────────────────────────────

    /** `GET app/logbook`: [vehicleId] null = the first own vehicle, [month] (`YYYY-MM`) null = this month. */
    suspend fun month(vehicleId: Long?, month: String?): LogbookResult<LogbookMonth> {
        val q = buildMap {
            if (vehicleId != null && vehicleId > 0) put("vehicle", vehicleId.toString())
            if (month != null && LogbookJson.MONTH_RE.matches(month)) put("month", month)
        }
        return call({ lang -> pluginGet(SKODA_PLUGIN, BASE, query(lang, q)) }, LogbookJson::month)
    }

    /** `PUT app/logbook/trips/<id>` → the trip as saved. */
    suspend fun updateTrip(tripId: Long, change: LogbookTripChange): LogbookResult<LogbookTrip> {
        if (tripId <= 0) return LogbookResult.Failed(CODE_INVALID, 0)
        val body = LogbookJson.changeBody(change)
        if (body.size() == 0) return LogbookResult.Failed(CODE_INVALID, 0)
        return call({ lang -> pluginPut(SKODA_PLUGIN, "$BASE/trips/$tripId", body, query(lang)) }, LogbookJson::savedTrip)
    }

    /** `GET app/logbook/export`: the months [from]..[to] (`YYYY-MM`) as `pdf` or `csv`. */
    suspend fun export(vehicleId: Long, from: String, to: String, format: String): LogbookResult<LogbookExportFile> {
        if (vehicleId <= 0 || !LogbookJson.MONTH_RE.matches(from) || !LogbookJson.MONTH_RE.matches(to) || format !in FORMATS) {
            return LogbookResult.Failed(CODE_INVALID, 0)
        }
        val q = mapOf("vehicle" to vehicleId.toString(), "from" to from, "to" to to, "format" to format)
        return call({ lang -> pluginGet(SKODA_PLUGIN, "$BASE/export", query(lang, q)) }, LogbookJson::export, EXPORT_TIMEOUT_MS)
    }
}
