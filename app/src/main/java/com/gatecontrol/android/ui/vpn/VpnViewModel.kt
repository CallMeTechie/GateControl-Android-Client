package com.gatecontrol.android.ui.vpn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.PermissionFlags
import com.gatecontrol.android.network.TrafficStats
import com.gatecontrol.android.network.VpnService
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.tunnel.TunnelStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class VpnViewModel @Inject constructor(
    private val setupRepository: SetupRepository,
    private val settingsRepository: SettingsRepository,
    private val licenseRepository: LicenseRepository,
    private val apiClientProvider: ApiClientProvider,
    private val tunnelManager: TunnelManager,
) : ViewModel() {

    val tunnelState: StateFlow<TunnelState> = tunnelManager.state

    private val _stats = MutableStateFlow(TunnelStats())
    val stats: StateFlow<TunnelStats> = _stats.asStateFlow()

    private val _trafficUsage = MutableStateFlow<TrafficStats?>(null)
    val trafficUsage: StateFlow<TrafficStats?> = _trafficUsage.asStateFlow()

    private val _permissions = MutableStateFlow(PermissionFlags(
        services = false,
        traffic = false,
        dns = false,
        rdp = false,
    ))
    val permissions: StateFlow<PermissionFlags> = _permissions.asStateFlow()

    private val _services = MutableStateFlow<List<VpnService>>(emptyList())
    val services: StateFlow<List<VpnService>> = _services.asStateFlow()

    val killSwitchEnabled: StateFlow<Boolean> = settingsRepository.getKillSwitch()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        // Poll statistics while connected
        viewModelScope.launch {
            while (true) {
                delay(1_000)
                if (tunnelState.value is TunnelState.Connected) {
                    tunnelManager.getStatistics()?.let { _stats.value = it }
                }
            }
        }
    }

    // --- Actions ---

    fun connect() {
        viewModelScope.launch {
            val config = setupRepository.getWireGuardConfig()
            if (config.isEmpty()) {
                Timber.w("VpnViewModel: no WireGuard config available")
                return@launch
            }
            try {
                tunnelManager.connect(config)
                Timber.d("VpnViewModel: tunnel connect requested")
            } catch (e: Exception) {
                Timber.e(e, "VpnViewModel: connect failed")
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            try {
                tunnelManager.disconnect()
                _stats.value = TunnelStats()
                Timber.d("VpnViewModel: tunnel disconnected")
            } catch (e: Exception) {
                Timber.e(e, "VpnViewModel: disconnect failed")
            }
        }
    }

    fun toggleKillSwitch(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setKillSwitch(enabled)
            Timber.d("VpnViewModel: kill-switch set to $enabled")
        }
    }

    fun loadTrafficStats() {
        viewModelScope.launch {
            try {
                val serverUrl = setupRepository.getServerUrl()
                if (serverUrl.isEmpty()) return@launch
                val client = apiClientProvider.getClient(serverUrl)
                val peerId = setupRepository.getPeerId()
                if (peerId <= 0) return@launch
                val response = client.getTraffic(peerId)
                if (response.ok) {
                    _trafficUsage.value = response.traffic
                }
            } catch (e: Exception) {
                Timber.w(e, "VpnViewModel: failed to load traffic stats")
            }
        }
    }

    fun loadServices() {
        viewModelScope.launch {
            try {
                val serverUrl = setupRepository.getServerUrl()
                if (serverUrl.isEmpty()) return@launch
                val client = apiClientProvider.getClient(serverUrl)
                val response = client.getServices()
                if (response.ok) {
                    _services.value = response.services
                }
            } catch (e: Exception) {
                Timber.w(e, "VpnViewModel: failed to load services")
            }
        }
    }

    fun loadPermissions() {
        viewModelScope.launch {
            try {
                val serverUrl = setupRepository.getServerUrl()
                if (serverUrl.isEmpty()) return@launch
                val client = apiClientProvider.getClient(serverUrl)
                val response = client.getPermissions()
                if (response.ok) {
                    val flags = response.permissions
                    _permissions.value = flags
                    licenseRepository.updatePermissions(
                        services = flags.services,
                        traffic = flags.traffic,
                        dns = flags.dns,
                        rdp = flags.rdp,
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "VpnViewModel: failed to load permissions")
            }
        }
    }
}
