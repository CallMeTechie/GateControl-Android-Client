package com.gatecontrol.android.service

import com.gatecontrol.android.common.HostnameSanitizer
import com.gatecontrol.android.common.SplitTunnelMode
import com.gatecontrol.android.common.VpnSubnet
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.data.SplitTunnelJson
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.HostnameReportRequest
import com.gatecontrol.android.tunnel.SplitTunnelConfig
import com.gatecontrol.android.tunnel.TunnelConfig
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.tunnel.WgConfigValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    private val clientPolicyManager: ClientPolicyManager,
) {

    /** Connects started from a screen; they outlive it (rotation, folding, an activity rebuilt after an update). */
    private val detached = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** [connectWithUserSettings] outside the caller's lifetime: a screen that goes away does not abort it half-way. */
    fun connectDetached(): Job = detached.launch {
        if (!connectWithUserSettings()) Timber.w("TunnelConnector: connect not possible")
    }

    suspend fun connectWithUserSettings(): Boolean {
        var config = setupRepository.getWireGuardConfig()
        if (config.isEmpty()) {
            Timber.w("TunnelConnector: no WireGuard config available")
            return false
        }

        val serverUrl = setupRepository.getServerUrl()
        applyVpnSubnet(config)

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
            config = refreshConfig(serverUrl, config)
            applyVpnSubnet(config)
        }

        val splitTunnelConfig = applyPolicy(resolveSplitTunnelConfig(serverUrl))

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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "TunnelConnector: connect failed")
            false
        }
    }

    /**
     * The server said the peer's config changed (push `config_changed`, or
     * `hello.state.config_hash` differs from the stored hash): run the same
     * config check as before a connect now. The new config is stored; a
     * connected tunnel is brought up again with it through the normal
     * connect path (split tunnel, policy). Returns true when a new config
     * was stored.
     */
    suspend fun syncConfigFromServer(): Boolean {
        val serverUrl = setupRepository.getServerUrl()
        val current = setupRepository.getWireGuardConfig()
        if (serverUrl.isEmpty() || current.isEmpty()) return false
        val fresh = refreshConfig(serverUrl, current)
        if (fresh == current) return false
        if (tunnelManager.state.value is TunnelState.Connected) {
            Timber.i("TunnelConnector: applying the new config to the running tunnel")
            connectWithUserSettings()
        }
        return true
    }

    /**
     * Ask the server whether the stored WireGuard config is still current and
     * adopt the new one if it is not. Runs right after the DNS pre-resolve —
     * the last moment with working internet before the tunnel comes up.
     *
     * Best-effort by design: an unreachable server, a rejected token or a
     * syntactically broken config must never block the connect, so every
     * failure path returns the stored config unchanged.
     */
    private suspend fun refreshConfig(serverUrl: String, current: String): String {
        val peerId = setupRepository.getPeerId()
        if (peerId <= 0) return current
        return try {
            val client = apiClientProvider.getClient(serverUrl)
            val response = client.checkConfigUpdate(
                peerId,
                setupRepository.getConfigHash().ifBlank { null },
            )
            val fresh = response.config
            if (!response.updated || fresh.isNullOrBlank()) return current

            val validation = WgConfigValidator.validate(fresh)
            if (!validation.ok) {
                Timber.w(
                    "TunnelConnector: server config rejected (%s), keeping stored config",
                    validation.errors.joinToString(", "),
                )
                return current
            }
            if (TunnelConfig.peerCount(fresh) != 1) {
                Timber.w("TunnelConnector: server config has multiple [Peer] sections, keeping stored config")
                return current
            }

            setupRepository.saveWireGuardConfig(fresh)
            response.hash?.let { setupRepository.saveConfigHash(it) }
            Timber.i("TunnelConnector: WireGuard config updated from server")
            fresh
        } catch (e: Exception) {
            Timber.w(e, "TunnelConnector: config check failed, using stored config")
            current
        }
    }

    /**
     * Client policy: only the split-tunnel modes the admin allows. A locked
     * server preset already carries the one allowed mode; anything else is
     * clamped (full tunnel when allowed). Never-fetched policy = unchanged.
     */
    private suspend fun applyPolicy(config: SplitTunnelConfig): SplitTunnelConfig {
        val policy = try { clientPolicyManager.current() } catch (e: Exception) {
            Timber.w(e, "TunnelConnector: client policy unavailable")
            return config
        }
        val mode = policy.clampMode(config.mode)
        if (mode == config.mode) return config
        Timber.i("TunnelConnector: split-tunnel mode %s not allowed by policy, using %s", config.mode, mode)
        if (mode == SplitTunnelMode.OFF) return SplitTunnelConfig()
        return config.copy(mode = mode)
    }

    /** Tell the DNS workaround which subnet is VPN-internal for this server. */
    private fun applyVpnSubnet(config: String) {
        val address = runCatching { TunnelConfig.parse(config).address }.getOrNull() ?: return
        apiClientProvider.vpnSubnet = VpnSubnet.fromAddress(address) ?: VpnSubnet.DEFAULT
    }

    private suspend fun resolveSplitTunnelConfig(serverUrl: String): SplitTunnelConfig {
        var splitTunnelConfig = SplitTunnelConfig()
        try {
            var adminPresetActive = false
            if (serverUrl.isNotEmpty()) {
                try {
                    val client = apiClientProvider.getClient(serverUrl)
                    val preset = client.getSplitTunnelPreset()
                    val presetMode = SplitTunnelMode.fromWire(preset.mode)
                    if (preset.ok && presetMode != SplitTunnelMode.OFF && preset.source != "none") {
                        settingsRepository.setSplitTunnelMode(presetMode)
                        settingsRepository.setSplitTunnelNetworks(
                            SplitTunnelJson.encodeNetworks(
                                preset.networks.map { SplitTunnelJson.Network(it.cidr, it.label) },
                            ),
                        )
                        settingsRepository.setSplitTunnelAdminLocked(preset.locked)
                        adminPresetActive = true

                        val userApps = settingsRepository.getSplitTunnelAppsV2().first()
                        splitTunnelConfig = SplitTunnelConfig(
                            mode = presetMode,
                            networks = preset.networks.map { it.cidr },
                            apps = SplitTunnelJson.decodeApps(userApps),
                        )
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Split-tunnel preset fetch failed")
                }
            }

            if (!adminPresetActive) {
                val mode = settingsRepository.getSplitTunnelMode().first()
                if (mode != SplitTunnelMode.OFF) {
                    val networksJson = settingsRepository.getSplitTunnelNetworks().first()
                    val appsJson = settingsRepository.getSplitTunnelAppsV2().first()
                    splitTunnelConfig = SplitTunnelConfig(
                        mode = mode,
                        networks = SplitTunnelJson.decodeNetworks(networksJson).map { it.cidr },
                        apps = SplitTunnelJson.decodeApps(appsJson),
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
}
