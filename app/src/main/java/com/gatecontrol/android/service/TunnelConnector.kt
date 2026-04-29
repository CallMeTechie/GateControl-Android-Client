package com.gatecontrol.android.service

import com.gatecontrol.android.common.HostnameSanitizer
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.HostnameReportRequest
import com.gatecontrol.android.tunnel.SplitTunnelConfig
import com.gatecontrol.android.tunnel.TunnelManager
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared connect path used by every entry point that starts the tunnel
 * (in-app VPN screen, Quick Settings tile, future auto-connect). Centralises
 * split-tunnel resolution and the DNS pre-resolve step so secondary entry
 * points cannot bring up the tunnel without applying the user's app and
 * network exceptions.
 */
@Singleton
class TunnelConnector @Inject constructor(
    private val setupRepository: SetupRepository,
    private val settingsRepository: SettingsRepository,
    private val apiClientProvider: ApiClientProvider,
    private val tunnelManager: TunnelManager,
) {

    suspend fun connectWithUserSettings(): Boolean {
        val config = setupRepository.getWireGuardConfig()
        if (config.isEmpty()) {
            Timber.w("TunnelConnector: no WireGuard config available")
            return false
        }

        val serverUrl = setupRepository.getServerUrl()

        // Pre-resolve server hostname before the tunnel comes up. Once the
        // VPN is established the system DNS points to 10.8.0.1, which is
        // unreachable from the GateControl app itself when the app is in
        // the user's exclude list.
        if (serverUrl.isNotEmpty()) {
            try {
                val host = java.net.URI(serverUrl).host
                if (host != null) apiClientProvider.preResolveDns(host)
            } catch (_: Exception) {
            }
        }

        val splitTunnelConfig = resolveSplitTunnelConfig(serverUrl)

        return try {
            tunnelManager.connect(config, splitTunnelConfig)
            Timber.d(
                "TunnelConnector: tunnel connect requested (mode=%s, %d networks, %d apps)",
                splitTunnelConfig.mode,
                splitTunnelConfig.networks.size,
                splitTunnelConfig.apps.size,
            )
            reportDeviceHostname(serverUrl)
            true
        } catch (e: Exception) {
            Timber.e(e, "TunnelConnector: connect failed")
            false
        }
    }

    private suspend fun resolveSplitTunnelConfig(serverUrl: String): SplitTunnelConfig {
        var splitTunnelConfig = SplitTunnelConfig()
        try {
            var adminPresetActive = false
            if (serverUrl.isNotEmpty()) {
                try {
                    val client = apiClientProvider.getClient(serverUrl)
                    val preset = client.getSplitTunnelPreset()
                    if (preset.ok && preset.mode != "off" && preset.source != "none") {
                        settingsRepository.setSplitTunnelMode(preset.mode)
                        val arr = JSONArray()
                        preset.networks.forEach {
                            arr.put(JSONObject().put("cidr", it.cidr).put("label", it.label))
                        }
                        settingsRepository.setSplitTunnelNetworks(arr.toString())
                        settingsRepository.setSplitTunnelAdminLocked(preset.locked)
                        adminPresetActive = true

                        val userApps = settingsRepository.getSplitTunnelAppsV2().first()
                        splitTunnelConfig = SplitTunnelConfig(
                            mode = preset.mode,
                            networks = preset.networks.map { it.cidr },
                            apps = parseSplitAppsJson(userApps),
                        )
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Split-tunnel preset fetch failed")
                }
            }

            if (!adminPresetActive) {
                val mode = settingsRepository.getSplitTunnelMode().first()
                if (mode != "off") {
                    val networksJson = settingsRepository.getSplitTunnelNetworks().first()
                    val appsJson = settingsRepository.getSplitTunnelAppsV2().first()
                    splitTunnelConfig = SplitTunnelConfig(
                        mode = mode,
                        networks = parseSplitNetworksJsonToCidrs(networksJson),
                        apps = parseSplitAppsJson(appsJson),
                    )
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Split-tunnel config load failed")
        }
        return splitTunnelConfig
    }

    private suspend fun reportDeviceHostname(serverUrl: String) {
        if (serverUrl.isEmpty()) return
        try {
            val sanitized = HostnameSanitizer.sanitize(android.os.Build.MODEL)
            if (sanitized.isNullOrBlank()) return
            val client = apiClientProvider.getClient(serverUrl)
            val response = client.reportHostname(HostnameReportRequest(sanitized))
            Timber.d("Hostname report: assigned=${response.assigned} changed=${response.changed}")
        } catch (e: Exception) {
            Timber.d(e, "Hostname report skipped: ${e.message}")
        }
    }

    private fun parseSplitNetworksJsonToCidrs(json: String): List<String> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getJSONObject(it).getString("cidr") }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse split-tunnel networks JSON, falling back to empty")
            emptyList()
        }
    }

    private fun parseSplitAppsJson(json: String): List<String> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getJSONObject(it).getString("package") }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse split-tunnel apps JSON, falling back to empty")
            emptyList()
        }
    }
}
