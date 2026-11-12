package com.gatecontrol.android.dnsprotect

import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.Response
import timber.log.Timber

/**
 * The app API of the DNS protection's plugin
 * (`/api/v1/client/plugins/<permissions.vpnDns.plugin>/api/…`): the state
 * for this device, suspending and switching back on, and for administrators
 * and parents the people they decide for. The plugin's id always comes from
 * the permissions. [snapshot] is the last status, shared by the start tile
 * and the screen (kept in memory only). Never throws.
 */
@Singleton
class DnsRepository @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
    private val licenseRepository: LicenseRepository,
    private val settingsRepository: SettingsRepository,
) {
    private val _snapshot = MutableStateFlow<DnsSnapshot?>(null)
    val snapshot: StateFlow<DnsSnapshot?> = _snapshot.asStateFlow()

    /** `GET status`. An answer replaces the snapshot; "gone" drops it; no answer keeps it. */
    suspend fun refresh(nowMs: Long = System.currentTimeMillis()): DnsResult<DnsSnapshot> =
        took(call({ plugin, lang -> pluginGet(plugin, "status", lang) }, DnsJson::status), nowMs)

    suspend fun pause(duration: DnsDuration, target: DnsTarget = DnsTarget.Own, nowMs: Long = System.currentTimeMillis()): DnsResult<DnsSnapshot> =
        took(call({ plugin, lang -> pluginPost(plugin, "pause", DnsJson.pauseBody(duration, target), lang) }, DnsJson::changed), nowMs)

    suspend fun resume(target: DnsTarget = DnsTarget.Own, nowMs: Long = System.currentTimeMillis()): DnsResult<DnsSnapshot> =
        took(call({ plugin, lang -> pluginPost(plugin, "resume", DnsJson.targetBody(target), lang) }, DnsJson::changed), nowMs)

    /** `GET people` (only with `status.manage`). */
    suspend fun people(): DnsResult<DnsPeople> = call({ plugin, lang -> pluginGet(plugin, "people", lang) }) { DnsJson.people(it) }

    /**
     * The plugin said the protection is gone: ask the server what holds now
     * (`GET permissions`), so the tab and the tile follow. Without an answer
     * nothing changes.
     */
    suspend fun reloadPermissions() {
        val url = setupRepository.getServerUrl()
        if (url.isBlank()) return
        try {
            val response = withTimeoutOrNull(TIMEOUT_MS) { apiClientProvider.getClient(url).getPermissions() } ?: return
            if (!response.ok) return
            val p = response.permissions
            licenseRepository.updatePermissions(
                services = p.services,
                traffic = p.traffic,
                dns = p.dns,
                rdp = p.rdp,
                pihole = p.pihole,
                piholeControl = p.piholeControl,
                vpnDns = LicenseRepository.vpnDnsOf(response.vpnDns?.plugin, response.vpnDns?.state),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("DNS-Schutz: permissions not reloaded (%s)", e.javaClass.simpleName)
        }
    }

    /** The answer of a change is the whole new status. */
    private fun took(r: DnsResult<DnsStatus>, nowMs: Long): DnsResult<DnsSnapshot> = when (r) {
        is DnsResult.Ok -> DnsSnapshot(r.value, nowMs).let {
            _snapshot.value = it
            DnsResult.Ok(it)
        }
        is DnsResult.Failed -> {
            if (DnsLogic.failure(r) == DnsFailure.GONE) _snapshot.value = null
            r
        }
        DnsResult.Offline -> DnsResult.Offline
        DnsResult.Unavailable -> {
            _snapshot.value = null
            DnsResult.Unavailable
        }
    }

    private suspend fun <T> call(
        request: suspend ApiClient.(plugin: String, lang: Map<String, String>) -> Response<JsonObject>,
        parse: (JsonObject) -> T?,
    ): DnsResult<T> {
        val url = setupRepository.getServerUrl()
        val plugin = licenseRepository.permissions.value.vpnDns?.plugin
        if (url.isBlank() || plugin == null) return DnsResult.Unavailable
        return try {
            val lang = mapOf("lang" to lang())
            val response = withTimeoutOrNull(TIMEOUT_MS) { apiClientProvider.getClient(url).request(plugin, lang) }
                ?: return DnsResult.Offline
            if (response.isSuccessful) {
                val body = response.body() ?: JsonObject()
                if (body.get("ok")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && !it.asBoolean } == true) {
                    return DnsJson.failed(body, response.code())
                }
                parse(body)?.let { DnsResult.Ok(it) } ?: DnsResult.Failed(CODE_INVALID, response.code())
            } else {
                val err = runCatching {
                    response.errorBody()?.use { b -> b.string().takeIf { it.length < 16_384 } }
                        ?.let { JsonParser.parseString(it).takeIf { e -> e.isJsonObject }?.asJsonObject }
                }.getOrNull()
                Timber.d("DNS-Schutz: HTTP %d", response.code())
                DnsJson.failed(err, response.code())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            DnsResult.Offline
        } catch (e: Exception) {
            Timber.d("DNS-Schutz: call failed (%s)", e.javaClass.simpleName)
            DnsResult.Failed(CODE_INVALID, 0)
        }
    }

    /** The app's own UI language, so the server's sentences match it. */
    private suspend fun lang(): String =
        runCatching { settingsRepository.getLocale().first() }.getOrNull()?.takeIf { it == "de" || it == "en" } ?: "de"

    companion object {
        const val CODE_INVALID = "invalid_answer"
        private const val TIMEOUT_MS = 15_000L
    }
}
