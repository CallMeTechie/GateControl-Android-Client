package com.gatecontrol.android.ui.services

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.VpnService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

sealed class DnsTestResult {
    data object Idle : DnsTestResult()
    data object Testing : DnsTestResult()
    data class Pass(val servers: String) : DnsTestResult()
    data class Fail(val servers: String) : DnsTestResult()
}

data class ServicesUiState(
    val services: List<VpnService> = emptyList(),
    val isLoading: Boolean = false,
    val dnsTestResult: DnsTestResult = DnsTestResult.Idle,
    val error: String? = null
)

@HiltViewModel
class ServicesViewModel @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
    private val licenseRepository: LicenseRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServicesUiState())
    val uiState: StateFlow<ServicesUiState> = _uiState.asStateFlow()

    private val _navigationEvent = MutableSharedFlow<String>()
    val navigationEvent: SharedFlow<String> = _navigationEvent.asSharedFlow()

    init {
        loadServices()
    }

    fun loadServices() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val serverUrl = setupRepository.getServerUrl()
                if (serverUrl.isBlank()) {
                    _uiState.update { it.copy(isLoading = false, services = emptyList()) }
                    return@launch
                }
                val client = apiClientProvider.getClient(serverUrl)
                val response = client.getServices()
                if (response.ok) {
                    _uiState.update { it.copy(isLoading = false, services = response.services) }
                } else {
                    _uiState.update { it.copy(isLoading = false, services = emptyList()) }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load services")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        services = emptyList(),
                        error = e.message
                    )
                }
            }
        }
    }

    fun runDnsLeakTest() {
        viewModelScope.launch {
            _uiState.update { it.copy(dnsTestResult = DnsTestResult.Testing) }
            try {
                val serverUrl = setupRepository.getServerUrl()
                if (serverUrl.isBlank()) {
                    _uiState.update { it.copy(dnsTestResult = DnsTestResult.Fail("No server configured")) }
                    return@launch
                }
                val client = apiClientProvider.getClient(serverUrl)
                val response = client.dnsCheck()
                if (response.ok) {
                    val servers = response.vpnDns
                    _uiState.update { it.copy(dnsTestResult = DnsTestResult.Pass(servers)) }
                } else {
                    _uiState.update { it.copy(dnsTestResult = DnsTestResult.Fail("")) }
                }
            } catch (e: Exception) {
                Timber.e(e, "DNS leak test failed")
                _uiState.update {
                    it.copy(dnsTestResult = DnsTestResult.Fail(e.message ?: "Unknown error"))
                }
            }
        }
    }

    fun openService(url: String) {
        viewModelScope.launch {
            _navigationEvent.emit(url)
        }
    }
}
