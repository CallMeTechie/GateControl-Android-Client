package com.gatecontrol.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.TrustedNetworkSettings
import com.gatecontrol.android.service.ClientPolicyManager
import com.gatecontrol.android.trustednet.TrustedNetSnapshot
import com.gatecontrol.android.trustednet.TrustedNetStatus
import com.gatecontrol.android.trustednet.TrustedNetworkMonitor
import com.gatecontrol.android.trustednet.TrustedNetworkRules
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class TrustedNetworksUiState(
    val settings: TrustedNetworkSettings = TrustedNetworkSettings(),
    val network: TrustedNetSnapshot = TrustedNetSnapshot(),
    val status: TrustedNetStatus = TrustedNetStatus(),
    /** Client policy "always on": the feature does nothing. */
    val locked: Boolean = false,
)

@HiltViewModel
class TrustedNetworksViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val monitor: TrustedNetworkMonitor,
    clientPolicyManager: ClientPolicyManager,
) : ViewModel() {

    val uiState: StateFlow<TrustedNetworksUiState> = combine(
        settingsRepository.getTrustedNetworkSettings(),
        monitor.network,
        monitor.status,
        clientPolicyManager.policy,
    ) { settings, network, status, policy ->
        TrustedNetworksUiState(settings, network, status, locked = !policy.canDisconnect)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrustedNetworksUiState())

    fun setEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setTrustedNetworksEnabled(value) }
    }

    fun setHomeEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setTrustedHomeEnabled(value) }
    }

    /** Adds a typed SSID; false when it is not a valid WLAN name. */
    fun addSsid(raw: String): Boolean {
        val ssid = TrustedNetworkRules.validSsidInput(raw) ?: return false
        viewModelScope.launch { settingsRepository.updateTrustedSsids { it + ssid } }
        return true
    }

    fun removeSsid(ssid: String) {
        viewModelScope.launch { settingsRepository.updateTrustedSsids { it - ssid } }
    }

    /**
     * Adds the SSID of the WLAN the device is in. Re-registers the network
     * callback first (a just-granted permission only applies to a new
     * registration) and waits briefly for the name. [onResult] gets the
     * added SSID, or null when Android gave none.
     */
    fun addCurrentSsid(onResult: (String?) -> Unit) {
        monitor.refresh()
        viewModelScope.launch {
            val ssid = withTimeoutOrNull(CURRENT_SSID_WAIT_MS) {
                monitor.network.first { it.ssid != null }.ssid
            }
            if (ssid != null) settingsRepository.updateTrustedSsids { it + ssid }
            onResult(ssid)
        }
    }

    /** Screen resumed (maybe back from the permission settings). */
    fun refresh() = monitor.refresh()

    private companion object {
        const val CURRENT_SSID_WAIT_MS = 4_000L
    }
}
